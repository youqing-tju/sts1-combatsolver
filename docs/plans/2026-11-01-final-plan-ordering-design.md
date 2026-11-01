# 最终路线质量设计：完整 FinalPlanOrdering 与收益/战损数据源

> 状态：**设计已获用户批准（2026-11-01 22:43 后）**，尚未实施。
> 目标：不能只求赢，要按蓝本实现“战损最小 + 收益最大”的赢法。
> 施工依据：`docs/plans/2026-11-01-final-plan-ordering.md`。

## 1. 不可谈判的边界

1. **禁止自创，蓝本没有 = 不做。** 只允许把蓝本机制翻译到 STS1 API，不允许改变键的顺序、默认值、约束强弱、候选范围或失败语义。
2. 蓝本 `CombatBeamSolver.FinalPlanOrdering.cs:253-271` 是 **1 个 `OrderBy` + 17 个 `ThenBy` = 18 键**，不是旧交接写的 17 键。
3. 不得只给 `BeamSolver.better()` 增加几个 `if`。蓝本的最终选择、搜索中临时选择、Beam 保留是不同比较器，必须保持调用边界。
4. 不得用 paired A/B 或真机手感调换蓝本顺序。A/B 用于发现回归和定位镜像问题，不是自创权重的许可证。
5. 不恢复 P1 的“open 表过早耗尽”诊断，不按旧 P2 强制先开能力牌，不砍任何搜索预算/宽度/深度/机制。

## 2. 已核实的现状与纠偏

### 2.1 蓝本最终选择链

权威入口：`reference/sts2-combatsolver/src/Search/CombatBeamSolver.FinalPlanOrdering.cs:28-321`。

顺序是：

```text
全部 evaluated 候选
  → 为每个候选捕获 SearchFeatures 与 RouteQuality
  → 选择/覆盖无药基线
  → forced / minimumUse / soft potion policy 等资格过滤
  → 对合格候选执行完整 18 键词典序
  → 空集抛 PotionPolicyUnsatisfiedException
  → 返回唯一 FinalPlanSelection 与药水诊断值
```

最终池来自 `completed + frontier`，见 `CombatBeamSolver.Phases.cs` 的 `finalPool` 构造；不是只拿每趟搜索的第一名。

### 2.2 STS1 当前真实比较键

`src/main/java/io/github/youqing/sts1solver/search/BeamSolver.java` 的 `better()` 当前为 9 键：

```text
Won → Survives → DeathSaveUseCount → OutstandingStolenGold
→ StrategicHpDeficit → CombatEndedTurn → EnemyHp → Score → depth
```

`depth` 在出牌、喝药和 `END_TURN` 建节点时都 `+1`，可作为蓝本 `ActionCount` 的现有对应物；应通过合同测试固定，而不是再维护一份不一致的计数。

### 2.3 不只是“少 9 键”

已确认的结构性差异：

- `better()` 同时服务终局、当前回合候选、novelty、portfolio 和最终池，混淆蓝本的 interim/final 比较器边界。
- `lastFinalPool` 实际只加入 `bestTerminal + frontier + best`，并未保存全部 completed；注释与实现不一致。
- 当前主流程是“先选一条 → `gatePotionSpending` → 0 费补搜 → `verifyPotionStrategy`”，并非蓝本“完整候选池资格过滤后统一排序”。
- 当前 `Survives` 在胜利路线之间也可能继续区分；蓝本第二键是 `!Won && !Survives`，两条都赢时该项恒同。
- 当前 `combatEndedTurn()` 对任何 `node.terminal()` 返回回合，包括玩家死亡；蓝本只对完整胜利赋值，其余为 `null/int.MaxValue`。
- 当前 `strategicHpDeficit()` 使用根到节点的净血量近似；本轮必须重新对照累计掉血、累计回血、最大生命、战后回血、死亡豁免和 `StrategicHpCredit`，不得预设它已经完全等价。

## 3. 完整 18 键（顺序不可改）

| # | 蓝本键与方向 | 本轮数据口径/现状 |
|---|---|---|
| 1 | `Won` 降序 | 完整胜利；非“分数高”或单纯怪物暂为 0 |
| 2 | `!Won && !Survives` 升序 | 修正当前胜利路线也比较存活的问题 |
| 3 | `DeathSaveUseCount` 升序 | 复用现有死亡豁免计数，核对 projected 口径 |
| 4 | `OutstandingStolenResource` 升序 | 仅 `PreserveResources` 生效；否则两侧键值均为 0 |
| 5 | `StrategicHpDeficit` 升序 | 按蓝本完整公式重验，不用根到节点净血量替代证明 |
| 6 | `GrowthHpCredit` 降序 | 成长预算 × 已兑现成长次数；默认预算为 0，另有遗物目标 credit 时按蓝本合并 |
| 7 | `GrowthRewardCount` 降序 | 已兑现成长事件数；无 HP 预算也可比较免费成长 |
| 8 | `CombatEndedTurn ?? MaxValue` 升序 | 只有完整胜利有值；死亡/未结束一律无穷 |
| 9 | `PolicyHpDeficit` 升序 | 战略赤字 + RequireAtLeastOne 的额外药水成本 |
| 10 | `HealthResourceCost` 升序 | `initialHp-finalHp + initialMaxHp-finalMaxHp`，不夹 0 |
| 11 | `LongTermResourceValue` 降序 | 已兑现长期资源；STS1 同名 Hand of Greed 金币可映射，Feed 不冒充长期资源 |
| 12 | `AngerCopiesGenerated` 升序 | 记录本路线 Anger 实际生成的复制牌数 |
| 13 | `PolicyBoundaryRank` 升序 | None=0；NoCards/Shuffle/TurnLimit/NodeLimit/TimeLimit=1；PendingChoice=2；UnsupportedEffect=3；EventDefeat=4 |
| 14 | `OptionalPotionCount` 升序 | 显式药数扣除 forced；自动药不计入显式药数 |
| 15 | `StrategicSold` 升序 | 战斗已承诺卖血 + 本路线未来卖血，不等于总掉血 |
| 16 | `EnemyHp` 升序 | 按蓝本有效敌方生命口径复核，不能只看字段名相似 |
| 17 | `Score` 降序 | 蓝本基础分；与 Beam rank 分开 |
| 18 | `ActionCount` 升序 | 路线动作总数；用合同证明当前 `depth` 等价 |

**`PotionStrategicCost` 不是这 18 键中的独立一项。** 它参与药水资格、`PolicyHpDeficit` 和其他蓝本比较器；不得因为旧交接简写而额外插入第 19 键。最终 18 键完全并列时保持蓝本稳定候选顺序，不自造额外 tie-breaker。

## 4. 数据源设计

### 4.1 统一纯数据路线特征

新增与蓝本对应的纯数据特征/路线质量模型，至少承载：

- 胜负、投影生存、死亡豁免、敌方有效 HP、基础分、动作数、每候选 Boundary。
- 累计掉血、累计回血、最大生命、战后回血、死亡豁免回血、战略 HP credit。
- 成长事件向量、成长 HP credit、长期资源、Anger 复制数。
- 总药数、自动药数、显式药数、强制药数/成本、可选药数/成本。
- 当前路线未来卖血、战斗已承诺卖血。

所有字段必须可 `copy/fork`，后台只读纯数据；不得从后台搜索线程读取 `SolverConfig` 或真机对象。凡进入后续推演、去重或 continuation 的字段，必须同步审计 `SimState.copy()`、指纹、状态戳、JSON 与 diff，禁止“复制后归零”。

### 4.2 成长与长期资源

蓝本证据：

- `Search/GrowthPolicy.cs`：`GrowthValues`、`Credit` 与逐来源计数。
- `Prediction/CorePowerSupport.cs`：Feed 成功致死增加最大生命并 `RecordGrowthReward(Feed)`；Hand of Greed 成功致死获得金币、`RecordLongTermResource(gainedGold)` 并记成长。
- `Prediction/CorePowerSupport.cs`：Anger 生成复制后 `RecordAngerCopyGenerated()`。

STS1 只映射**同名且原版语义已核实**的机制：Feed、Hand of Greed、Anger。不得把 Fruit Juice 等“看起来也算成长”的效果自行登记为蓝本 GrowthSource，也不得搬 STS2 独有的 TheHunt/GeneticAlgorithm 等来源。

默认成长预算与遗物目标均按蓝本默认为空/零：免费成长仍可凭第 7 键胜出；没有额度时不许拿战损换 `GrowthHpCredit`。成长/遗物策略 UI 不在本轮顺带自创；若以后实现，必须单独按蓝本复刻。

### 4.3 卖血与战斗历史

蓝本 `CombatBeamSolver.Terminal.cs:30-145`：

- 以 `(turn, turnStart.StateKey, potionSlotsUsed)` 分组。
- 对可比较候选求该组 `minimumHpLost`。
- `soldThisTurn = max(0, hpLost - minimumHpLost)`。
- `futureSold = parent.FutureSoldHp + soldThisTurn`。
- PendingChoice / UnsupportedEffect 不参与最低战损基线，且自身 sold 为 0。

蓝本 `Runtime/BattleDamageTracker.cs:7-128` 还跟踪：本场已发生掉血、已承诺卖血、已用药及回血；换回合时把 `min(plannedSoldHp, actualLoss)` 计入 committed。跨回合重搜必须携带这些基线，不能每回合从零重算。

STS1 应翻译同一机制，不以“总掉血”冒充“卖血”。真机历史采集若缺字段，先用 `javap` 核实原生历史/状态来源；无法确认时显式拒绝该统计，不得静默编数。

### 4.4 每候选 Boundary

`PolicyBoundaryRank` 读取的是**该候选自身**的 `SearchFeatures.BoundaryReason`，不是整趟搜索唯一的 `lastStopReason`。需要把候选边界随节点保存并复制；不得把全局 `horizon/node_limit/time_limit` 字符串随便映射成一个排名。

## 5. 搜索接入设计

1. **临时候选与最终候选分离**：蓝本 `SolverInterimResultOrdering` 对应搜索中展示/采用；`FinalPlanOrdering` 只负责最终合格候选。Beam 的 `rank` 仍只管保留，不混入最终 18 键。
2. **完整候选池**：保留全部 completed 与最后 frontier；portfolio 聚合所有成员的完整池；novelty 候选按蓝本同一最终入口参与，不得在后处理后把已否决路线捞回来。
3. **统一资格与排序**：无药/forced-only 基线、forced/minimum/soft policy、专用异常与 18 键在同一最终选择器内完成。
4. **后处理顺序按蓝本**：任何会真实替换答案的 post-search 候选都必须进入相应蓝本比较/资格路径；最终 `lastRoute/lastSteps/continuations` 只能从最终选中的同一路线发布。
5. **线程边界不变**：主线程捕获真机及战斗历史；后台只使用不可变搜索根与纯数据；最终路线回放和 continuation 仍遵守已完成的严格纠偏语义。

## 6. 明确不做

- 不把 `persistentBuffValue` 自造为额外最终键；它不在上述 18 键中。
- 不新增“无生还路线禁止出牌”、默认冻结、单步剔除、点 Tn 有界 AUTO。
- 不实现 STS2 独有琥珀、TheHunt、GeneticAlgorithm、幕末 80% 回血等分支。
- 不顺带实现成长/遗物策略 UI、PotionRewardOutlook、AdoptRoute、RouteFrozen；未做就保留“未做”。
- 不用快照投影收益冒充真机收益，不用自动化全绿冒充真机闭环。

## 7. 验收设计

### 7.1 合同测试

- 18 键逐键构造“当前键之前的所有键全同，仅当前键不同”，证明方向与顺序；再构造高优先级覆盖低优先级的反例。
- 胜利路线之间第二键不生效；死亡终局不享受早结束优势；ActionCount 包含出牌、药水、END_TURN。
- 成长：免费 Feed/Hand of Greed 可争；默认零额度不支付 HP；长期资源只记真实到手金币；Anger 每次真实复制 +1；fork 隔离。
- 卖血：同组最低掉血为基线，不同 turnStart/药槽不串组；PendingChoice/UnsupportedEffect 排除；跨回合 committed 不丢。
- 完整池：次优合格候选能胜过“当前第一名但不合格”；全部 completed 不丢；novelty/portfolio/补搜不能绕过最终资格；空集抛专用异常。
- 串行/并行、DOP1/DOP2 结果一致；最终路线、预览与 continuation 同源。

### 7.2 同一快照 paired A/B

基线 = `de9de4b` 的代码语义；同一批 `turn-*.json`、同一配置、同一顺序分别运行旧/新实现。至少报告：

- 完整胜利数、由负转胜/由胜转负。
- 选中路线 StrategicHpDeficit 的配对差值与改善/不变/恶化数。
- Feed 次数与最大生命增量、Hand of Greed 金币、Anger 复制数、动作数。
- 首手与完整路线变化数、停止原因、异常数。

快照是相关样本时，不能把 497 个回合快照当成 497 个独立战斗做虚假显著性声明；能归属战斗则按战斗聚类，否则只报描述统计与配对方向。投影指标必须标“仿真”，不得当真机战损。

### 7.3 真机

先完成交接 §十一已有的 8 项复测，再针对本轮至少留证：

1. 同能获胜时选择实际战损更低的路线，记录结束 HP。
2. Feed 致死时最大生命/当前 HP 按原版增加，重搜后成长计数不丢。
3. Hand of Greed 致死时实际金币增加，重搜后收益不丢。
4. Anger 路线在其他高优先级条件相同的情况下少产生无意义复制。
5. 连续重搜/continuation 后战斗已承诺卖血与药水基线不归零。

最终仍需全量 `./gradlew test --offline`、jar、安装并重启 MTS；真机未做的逐项写“未验证”。
