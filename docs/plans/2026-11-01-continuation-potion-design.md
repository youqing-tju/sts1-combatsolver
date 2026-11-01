# P7 跨回合 Continuation 与 D5 药水策略设计

> 状态：**设计已获用户逐项拍板（2026-11-01）**，本文件是实现依据，不是“已完成”记录。
> 已批准的 P7 架构：**精确状态续跑，复用现有最终 `SearchNode` 链，不照搬蓝本再次回放路线**。
> 已批准的 D5 交互：**完整搬全局/栏位/预设/持久化/搜索约束；自动部署执行中拒绝修改策略**。
> 施工必须先读 `docs/handoff-prompt.md`、本设计与对应 TDD 计划。

## 1. 目标与边界

### 1.1 目标

1. P7：最终搜索路线在稳定回合边界留下精确预测；后续回合真局面完全一致时复用原世界线，不重新搜索；不一致时归因并给出首个状态差异后重算。
2. D5：让全局药水档位、每槽 directive 和四个预设成为搜索请求、并行搜索、后处理、最终答案及真机执行的共同约束，而非 UI 装饰。
3. 保持现有产品语义：点路线 Tn 仍表示“打完 Tn 的 END_TURN 与敌方阶段，在 Tn+1 玩家行动前停”。

### 1.2 非目标

- 不做蓝本“冻结路线”；Continuation 每个回合都校验，不是冻结。
- 不做回合内 continuation；本回合喝药、三选一、ShadowTracker 漂移仍使当前计划失效并重搜。
- 不新增药水镜像覆盖，不把无法安全搜索的药水强行变为可搜索。
- 不复活旧 P1“open 表过早耗尽”或 P2“能力牌应强制先开”的施工前提。
- 不为省时砍预算、束宽、深度、novelty、portfolio 或其他搜索机制。
- 不追求类名/容器与蓝本一一同形；复刻的是状态校验和最终答案语义。

## 2. 蓝本与 STS1 证据

| 主题 | 蓝本事实 | STS1 现状 |
|---|---|---|
| 精确续跑 | `src/Runtime/ContinuationStamp.cs:14-135`：精确状态文本，不是哈希；提供差异诊断 | `sim/SimState.java:604-714` 的 `fingerprint()` 是 64 位哈希且不包含意图，不能代替 |
| 续跑结果 | `src/Search/CombatPlan.cs:258-303`：`ExpectedState + StartTurnNumber + ForecastOffset` | `search/AsyncSearch.java:34-42` 只带 steps，无预测边界 |
| 边界生成 | `src/Search/CombatBeamSolver.Terminal.cs:159-274`：最终最优路线的稳定回合边界 | `search/SearchNode.java` 已持有每个节点的 `SimState`，可直接从最终链提取 |
| 运行时命中 | `src/Runtime/SolverController.cs:1078-1140`：命中免搜索，miss 归因 | `verify/AutoPilot.java:251-263` 回合翻页直接 `clearPlan()` |
| 药水全局档位 | `src/Search/PotionUsePolicy.cs:26-49`：Disabled/Smart/RequireAtLeastOne | `search/BeamSolver.java:810` 只实现 Smart 成本闸 |
| 每槽指令 | `src/Search/PotionStrategySnapshot.cs:63-101`：Smart/Disabled/Force | STS1 没有 |
| 预设与持久化 | `src/Runtime/SolverSettings.cs:77-104,229-245`：四预设及 `(slot,potionId)` 覆盖 | `config/SolverConfig.java` 没有 |
| 搜索一致性 | `src/Search/CombatBeamSolver.cs:213-215,409-420`：policy snapshot 与 forced-use | `BeamSolver` 的 worker/baseline/rescue 尚无统一策略快照 |

## 3. P7 架构

### 3.1 数据流

```text
后台 BeamSolver
  最终 novelty 二选一
    → gatePotionSpending
    → zeroCostOpeningRescue
    → 最终 selected
    → 沿 selected.parent 链提取稳定回合边界
    → ContinuationStamp.from(predicted SimState)
    → AsyncSearch.Result（steps + continuations）
    → AutoPilot 采纳时继续用完整 steps 建立并持有 RoutePreview

主线程 AutoPilot（稳定新回合、任何搜索/行动前）
  SimState.capture()
    → ContinuationStamp.from(live SimState)
    → 精确相等：裁出当前及未来步骤，更新 preview，SEARCH_REUSED，不搜索
    → 不相等：归因 + DescribeFirstDifference + DescribeDifferences，SEARCH_REUSE_MISS，重搜
```

Continuation 必须来自**所有后处理后的最终 `selected`**。不得从 novelty 候选、药水闸之前候选或补搜之前候选单独生成，否则缓存的不是实际采纳的世界线。

### 3.2 纯数据模型

建议最小类型：

- `ContinuationStamp`：不可变精确状态文本及字段级差异诊断。
- `CachedContinuation`：`expectedState`、`startTurnNumber`、`forecastOffset`。
- `ContinuationPlan`：不可变边界集合；负责按回合查找、精确续接、保留更晚边界。

`RouteStep` 继续只表达执行步骤，不塞完整状态。`AsyncSearch.Result` 可以扩展承载 continuation，不必为了形式 parity 新建蓝本式大型 `CombatPlan`。

### 3.3 稳定边界资格

只缓存由 `END_TURN`（或以后明确证明等效的动作）抵达的边界，并同时满足：

1. 下一节点确属下一回合；
2. 玩家未死；
3. 敌人未全灭；
4. 没有搜索边界异常；
5. 边界之后确有后续动作。

末尾终局、死亡、胜利及无后续动作的边界不缓存。预测必须来自最终节点链；测试要证明它与沿最终路线重放得到的边界状态等价，而不是声称实现路径也与蓝本相同。

### 3.4 精确状态戳

预测侧和真机侧必须调用**同一个** `ContinuationStamp.from(SimState)`。禁止维护两套容易漂移的序列化逻辑，禁止用对象 `toString()`、对象身份或派生对象地址比较。

采用带版本、字段路径和明确转义/长度编码的规范化文本。至少覆盖：

- 回合、当前/每回合能量、初始手牌/能量、HP/maxHP/block；
- 手牌、抽牌堆、弃牌堆、消耗堆；药水槽位、ID、discard；
- 玩家与怪物 Power 的 ID/amount/steps 等全部镜像 hook 输入；
- 遗物 ID/counter 等全部镜像生效字段；
- 怪物 ID、HP/maxHP/block、intentDmg/intentAttack、invincibleMax、moveHistory、pendingMoveId、damageBase，以及 `pendingMove` 的全部**规范化语义载荷**；
- `cardRandomRng / monsterRng / aiRng / shuffleRng / miscRng` 的内部状态与 counter；
- 未来新增且会影响推演的字段必须纳入同一清单。

规范化规则：

- 手牌按无序多重集编码：每张牌先形成完整稳定 key，再排序；同 ID 的多张牌保留重复计数。
- 抽牌堆等有序牌堆保持原顺序。
- 卡牌不能只靠当前 `SimCard.equals()`；至少覆盖 `id/upgraded/cost/costForTurn/baseDamage/type` 及所有未来字段。
- Power、遗物等无序集合按稳定 ID/槽位排序，但不能丢掉 amount/counter/steps。
- `pendingMove` 比较语义字段，不比较对象身份。若实机与预测的派生对象由相同原始状态重建，则同一编码器应得到相同文本；若语义不同，必须 miss。

显式排除：路线冻结状态、UI revision、搜索 transposition 缓存、控制器字段和仅有哈希意义的值。

### 3.5 命中、miss 与生命周期

命中：

- 复用当前回合及后续步骤，完整 preview 保留绝对回合编号，只更新下一手与剩余世界线。
- 只保留 `startTurnNumber` 更晚的 continuation。
- 记录 `SEARCH_REUSED from_turn=… remaining_turns=… validation=exact_state_text`。
- 不请求新搜索，不因为“每回合固定重算”清掉已验证路线。

miss：

- `ManualDivergence`：上一回合发生手工输入分歧。
- `ContinuationMissing`：没有缓存，或缓存没有当前稳定回合。
- `StateMismatch`：有当前回合预测但精确状态不同；日志必须给首个字段差异，必要时给完整差异。
- `DeploymentDrift`：沿用现有 ShadowTracker 部署漂移通道，不能改名为普通新回合。

统一记录 `SEARCH_REUSE_MISS reason=… first_difference=…`，再重搜。非预期 `StateMismatch/DeploymentDrift` 是诊断信号，不写成“正常现象所以忽略”。

以下情况清空 continuation：战斗 reset、手工输入、本回合计划失效、ShadowTracker 部署漂移、药水策略/preset 改变。旧搜索结果按 fingerprint 与策略代际共同判时效。

### 3.6 有界 AUTO 与线程

- 有界目标与“使用哪条世界线”正交；重搜、miss、回合内喝药不得清 bounded target。
- `waitingForNextTurn()` 后、任何搜索或行动前的停止检查保持原位。续跑同样不能越过目标。
- `SimState.capture()`、真机校验、执行只在主线程；后台只处理纯数据。
- 最优链提取和预测 stamp 可在后台生成；渲染路径不得 capture。

## 4. D5 架构

### 4.1 完整策略模型

全局 `SolverPotionPolicy`：

- `Disabled`：最终路线显式用药数必须为 0，强制 override 也不能突破。
- `Smart`：允许不用药；用药必须满足救命或战略 HP 成本约束。
- `RequireAtLeastOne`：最终路线至少一瓶，并满足蓝本胜负与额外药水成本条件。

每槽 `SolverPotionDirective`：

- `Smart`：默认；持久化时删除显式覆盖。
- `Disabled`：该 `(slot,potionId)` 不可显式使用。
- `Force`：最终路线必须真实使用该槽药水，不是排序加分。

预设：

- `AllSmart`：当前存在且可搜索的药水全设 Smart。
- `AllProtected`：全部 Disabled。
- `AllForced`：全部 Force。
- `OnlyForced`：保留已 Force 的药水，其余当前可搜索药水设 Disabled；**不等于**全局 `RequireAtLeastOne`。

### 4.2 不可变快照与代际

- `SolverConfig` 持久化全局 policy 与 `(slot,potionId,directive)` 覆盖，兼容旧配置缺省 Smart。
- 每次搜索请求携带不可变 `PotionStrategySnapshot` 与单调递增 `policyRevision`。
- `AsyncSearch.Result` 带回同一 revision；AutoPilot 同时校验 fingerprint 和 revision。即使 fingerprint 相同，旧策略结果也不得采纳。
- 后台 worker、baseline、补搜子 solver 只读请求快照，不读取会被 UI 中途修改的全局可变配置。

### 4.3 全搜索链统一约束

同一策略必须覆盖：

1. 主 Beam 候选展开；
2. novelty 二选一；
3. portfolio 子搜索与最终比较；
4. 不喝药 baseline；
5. `gatePotionSpending`；
6. `zeroCostOpeningRescue`；
7. 最终 `selected` 与 `lastRoute/lastSteps`；
8. 最终 continuation 的生成来源。

资格判定统一经过 `PotionStrategySnapshot`/`PotionUsePolicy`。最终路线确定后再次执行 `allowsExplicitUse`、全局显式用药数量和 forced-use 完整性检查，禁止后处理或 novelty 把禁用药物捞回来。

`buildNoPotionBaseline` 必须同时满足：请求有药、策略允许搜索用药、且根状态存在可用药水。策略禁用或全部不可用时允许 baseline 参与终局比较。

若最终没有任何满足 Force/RequireAtLeastOne 的路线，应显式表示无满足约束的计划并继续调查搜索空间；不得静默退回违反策略的路线，也不得把 Force 降级为普通奖励分。

### 4.4 主线程控制与真机槽位

- 策略面板只有“药水”升级为真实控制器；遗物、成长继续只读。
- 渲染只读缓存，不在 render/rows 中 capture。主线程 settled 快照提供槽位、药水 ID、当前 directive、是否可搜索及是否执行中。
- 点击栏位循环 `Smart → Disabled → Force → Smart`；不可搜索药水禁用控件。
- **自动部署/执行中拒绝修改**（用户已拍板），控件锁定或点击给出明确状态，不改变配置。
- 非执行中修改 directive/preset：保存配置、递增 revision、清 continuation 与待处理预测基线；自动计算开启则请求 `Manual` 重搜，关闭则保留设置并记录跳过重算。
- 最终采纳步骤必须携带或绑定 `(slot,potionId)`。执行前再次校验槽位和 ID，防止两瓶同 ID 药水或旧步骤导致错槽；不一致视为计划失效，不得喝另一瓶冒充。

## 5. 错误处理与诊断

- 状态戳缺字段属于实现缺陷：用字段覆盖测试和差分测试暴露，不以“哈希相同”放行。
- 预测与实机出现语义差异：保留首个字段路径、双方值、回合和必要完整差异，再走 StateMismatch。
- 旧策略结果、旧 fingerprint、旧战斗结果一律不采纳；日志说明 revision/fingerprint 哪一项失效。
- 不通过关闭对拍、吞掉 mismatch、放宽断言或静默禁用搜索通道来制造全绿。

## 6. 测试与验收

### 6.1 P7 自动化

- stamp 同状态相同；逐字段变异至少一项必不同；重复手牌、牌堆顺序、Power 数值、遗物计数、怪物历史/pendingMove 和 RNG counter/internal state 均有专项。
- 首个差异能定位字段、牌堆下标、怪物子字段、RNG 子字段。
- 最优链边界资格与蓝本规则一致；终局/死亡/无后续不缓存。
- 命中无需 solver、保留正确绝对回合；ContinuationMissing/StateMismatch/ManualDivergence 归因正确。
- 有界 Tn 在续跑命中时仍于 Tn+1 玩家行动前停；reset/回合内漂移清缓存。

### 6.2 D5 自动化

- 全局三档、每槽三态、四预设、Smart 删除覆盖、换药键隔离、旧配置兼容。
- 主 Beam/novelty/portfolio/baseline/gate/rescue/最终 selected 全链无法采纳违禁药水。
- Force 与 RequireAtLeastOne 验证最终真实路线；OnlyForced 不等同 RequireAtLeastOne。
- 同 fingerprint 不同 revision 的旧结果被拒绝。
- 同 ID 不同槽位的最终执行步骤仍绑定原槽位。
- P7 continuation 来自药水后处理后的最终路线；策略改变后不能续用旧世界线。

### 6.3 真机验收

1. 一场至少三回合战斗：第二回合出现 `SEARCH_REUSED … validation=exact_state_text`，无对应新搜索；后续动作与原 preview 一致。
2. 手工改牌/喝药制造分歧：出现正确 miss 原因及首个差异，随后重搜，不盲走旧路线。
3. 有界 AUTO 与续跑组合：仍在目标回合结束并完整跑完敌方阶段后停止。
4. 全局 Disabled/Smart/RequireAtLeastOne、单槽 Disabled/Force、OnlyForced 各至少一个可复现场景；路线和实际喝药一致。
5. 修改策略后旧结果不落地、旧 continuation 不复用；执行中控件拒绝修改。
6. 同 ID 两瓶药水不错槽；三选一/液态记忆等本回合失效行为不回归。
7. 全量测试、jar、安装与日志证据齐全；UI 十条和其他历史未闭环项不得被冒充为本功能已验收。

## 7. 提交与回退

P7 按“stamp/纯数据续接 → 最终链生成 → AutoPilot 运行时”切片；D5 按“策略值对象/持久化 → 搜索全链 → revision/UI/槽位”切片。每片先 RED 后 GREEN、独立本地 commit，保证可 revert。

最后做 spec review 与 quality review，更新 `docs/handoff-prompt.md` 和 `docs/research/blueprint-parity.md` 的真实状态。不 push。
