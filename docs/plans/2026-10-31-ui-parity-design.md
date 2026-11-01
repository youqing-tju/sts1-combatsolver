---
summary: "UI 剩余两刀的已批准设计：只读策略现状区 + 有停止边界的 AUTO"
read_when:
  - 实施 UI 第三刀或第四刀之前
  - 需要判断蓝本复刻、STS1 适配与项目自定义扩展的边界时
status: "用户已于 2026-10-31 批准；实施须遵循 TDD 计划"
---

# UI 剩余两刀设计：只读策略现状区 + AUTO 至目标回合末

> ⛔ **第四刀有界 AUTO 已废止**：2026-11-01 19:35 用户最新规则为禁止自创、蓝本没有 = 不做。
> 本文仅作历史审计，不得再用于新增/保留 BoundedAutoTarget 或默认冻结；当前施工只看
> `docs/plans/2026-11-01-strict-blueprint-parity.md`。
>
> 本文是已批准设计，**不是实施完成记录**；实施必须按 `2026-10-31-ui-parity.md` 的 TDD 步骤执行。
> 用户已拍板两项产品语义：
> 1. 策略区本轮 **先只读，后控制**；真正的策略控制器另开任务。
> 2. 点路线行 = **打完目标回合 Tn 再停**：继续执行 Tn 的 `END_TURN` 与随后敌方阶段，在 Tn+1 玩家回合交还控制；每回合根据真局面重搜，不冻结旧预览。

## 1. 先纠正旧计划的两处事实错误

### 1.1 蓝本三个策略面板是控制器，不是状态摘要

蓝本证据：

- `reference/sts2-combatsolver/src/UI/SolverPotionStrategyPanel.cs`
  - 四个批量预设：全部智能、全部保护、全部强制、仅用已强制；
  - 每瓶药按槽位设置指令，循环 `Smart → Disabled → Force → Smart`；
  - `DirectiveChanged` / `PresetRequested` 会把用户选择发回控制层。
- `reference/sts2-combatsolver/src/UI/SolverRelicStrategyPanel.cs`
  - 总开关、逐遗物启用、目标计数最小/最大值、HP 额度、优先级；
  - `PolicyChanged` 发布 `RelicCounterRule[]`。
- `reference/sts2-combatsolver/src/UI/SolverGrowthStrategyPanel.cs`
  - 忽略局外收益、特定成长来源的 HP 额度等；
  - `IgnoreLongTermRewardsChanged` / `PolicyChanged` 会改变后续策略。

因此，本轮的只读折叠区只能命名为 **“策略现状（只读）”**，属于 STS1 当前状态的展示适配；不得标成“蓝本策略面板复刻完成”，也不能放没有实际作用的假控件。

### 1.2 蓝本没有 `ApplyRoute`，路线行也不接收点击

- 当前蓝本源码全库无 `ApplyRoute`；此前对蓝本 Git 历史 `git log --all -SApplyRoute -- src` 也无结果。
- `SolverRouteRow.cs:28` 为 `MouseFilter = MouseFilterEnum.Ignore`。
- 蓝本实际入口是 `ApplyCurrentTurn` 与 `AdoptCurrentRoute`，不是“点路线行执行到该回合”。
- `SolverController` 的 `DeployCurrentTurn` 只部署 `action.Turn == result.StartTurnNumber` 的动作；旧对照表把本项目每回合重规划归为同构，这个判断保留。需注意蓝本后续回合会先做精确 `ContinuationStamp` 校验，命中时复用 continuation、未命中才重搜；不能把它概括成“蓝本每回合必定从头重搜”。

因此，第四刀必须命名为 **项目自定义扩展：“AUTO 至目标回合末”**。它不实现蓝本 `AdoptRoute`，不冻结跨回合路线，也不再使用“复刻蓝本 `ApplyRoute`”的表述。

## 2. 范围与非目标

### 本轮做

1. 主面板内增加一个可折叠的只读策略现状区，包含药水、遗物、成长三块。
2. 路线行显示绝对回合号；点击有效行后开启有停止边界的 AUTO。
3. 为上述行为添加纯数据单元测试、执行状态机测试与真机验收留证。
4. 修正 `docs/plans/2026-10-31-ui-parity.md` 与 `docs/research/blueprint-parity.md` 的错误口径。

### 本轮不做

- 不修改 `search/`、`eval`、`mirror/` 的搜索与估值行为，不降低任何预算、宽度或深度。
- 不做 Smart / Disabled / Force、遗物目标范围、成长 HP 额度等控制器。
- 不把 `BeamSolver` 的药水对账私有遥测搬到面板；这需要另开 Result DTO 设计。
- 不实现蓝本 `AdoptRoute`，不按旧预测跨回合照打。
- 不新增路线目标的持久化配置；停止目标只属于当前战斗的一次运行意图。
- 不为只读区新增独立 Godot 式窗口；继续使用 STS1 主面板自绘体系。

## 3. 方案比较与选择

### 3.1 策略区

| 方案 | 优点 | 缺点 | 结论 |
|---|---|---|---|
| A. 直接复刻三个交互控制器 | 一步到位 | 必须改 config/search/eval，违背本轮边界；假控件更危险 | 否决 |
| B. 只读现状区，后续另做控制器 | 数据真实、边界小、不会误导 | 不是蓝本控制面板复刻 | **采用** |
| C. 本轮完全不做，等控制器 | 没有“半复刻”争议 | 无法交付用户要求的现状可见性 | 不采用 |

### 3.2 路线点击

| 方案 | 优点 | 缺点 | 结论 |
|---|---|---|---|
| A. 冻结点击时的整条路线，逐回合照打 | 看似最贴近“执行路线” | 真实抽牌/随机效果会漂移；与当前每回合重搜冲突 | 否决 |
| B. 只设绝对停止回合，其余沿用现有 AUTO | 每回合重算；改动集中；安全边界明确 | 预览不等于执行承诺，需在 UI 明示 | **采用** |
| C. 点击后只执行当前回合 | 最简单 | 不符合“执行到 Tn”的用户目标 | 不采用 |

## 4. 第三刀：只读策略现状区

### 4.1 展示结构

默认折叠，折叠状态仅保留在本次进程，不写入 `SolverConfig`。标题始终可见：

```text
策略现状（只读） [+]
```

展开后按实际内容显示，最多三块；示例仅说明结构，不是硬编码文案：

```text
策略现状（只读，不会修改搜索） [-]
药[1] Fire Potion · 门槛 9 HP · 主动/目标
药[2] FairyPotion · 被动 · 无主动使用门槛
遗物 Shuriken=2 · InkBottle=7 · 未镜像 1: ...
成长 hp/max 63/83 · Strength 2 · Dexterity 1 · Demon Form 1
```

具体规则：

- **药水**：按槽位列出；空槽可聚合为一行，不必为每个空槽占行。
  - 数据源：`SimState.potions` / `potionSlots`。
  - HP 门槛：仅对可主动使用的药水只读调用 `PotionValuation.strategicHpCost(id)`，文案必须是“门槛”，不是“实际省血”；被动药水不显示默认值 9，改显“无主动使用门槛”。
  - 动作形态：`Potions.canUse` 区分主动/被动，`needsTarget` / `needsChoice` 标注目标/选牌；不得据此宣称真机此刻一定可使用。
  - 不显示 `lastPotionHpSaved` / `lastPotionSpendRejected` / 本次实际 `strategicCost`，因为 `AsyncSearch.Result` 没有这些字段。
- **遗物**：显示当前持有遗物的 `id=counter`，计数 `-1` 原样显示，不擅自翻译成“未触发/已用完”。
  - 保留 `Coverage.unmirroredRelics(snapshot)` 告警；它是数据干净度诊断，不是蓝本遗物目标策略。
  - 行数受限时优先保留未镜像告警与计数会发生战斗变化的遗物；不得虚称完整策略列表。
- **成长**：显示 `hp/maxHp` 与当前已有的正向关键 Power 层数；第一版只列已有且 `amount > 0` 的 `Strength`、`Dexterity`、`Demon Form`，没有则显示“无关键成长层数”。
  - `maxHp` 包含 Feed / Fruit Juice 等已经落地的结果，但不声称能区分来源。
  - 不显示“成长 HP 额度”“忽略局外收益”等不存在的设置。
  - 不把 `PowerValuation.persistentBuffValue` 称为成长层数；它是估值分，不是层数，本轮也不新增这项昂贵计算。

所有行必须遵循现有 `fit()` 截断规则；内容变化只置 `layoutDirty`，不在绘制循环里做新搜索。

### 4.2 数据流与刷新时机

```text
主线程、动作队列稳定后
  SimState.capture()
      ├─ turnLineOf(...)                 → 现有状态条
      └─ StrategyStatusSnapshot.from(...) → SolverPanel.setStrategyStatus(...)

后台搜索：不接触该 DTO，不接触游戏对象
渲染：只读缓存 DTO，不 capture，不读真机
```

新增 `ui/StrategyStatusSnapshot.java` 作为不可变、纯数据 DTO/格式化器，输入仅为 `SimState`，输出预先构造的只读行。它可调用现有只读静态查询，但不得持有真机对象。

刷新分两类：

1. **回合开始**：沿用 `AutoPilot.dumpTurnSnapshot(turn)`，同一份 `snap` 同时推状态条与策略现状；删除 `strategyLine()` 中第二次 `SimState.capture()`。
2. **回合内动作后**：设置 `strategySnapshotDirty`；仅在主线程同时满足 `ShadowTracker.isIdle()`、`drained()`、无打开界面、非回合切换后捕获一次并清 dirty。打牌、喝药及选择界面完成后触发；搜索完成本身不触发。

这样避免“只在回合初刷新”导致药水和计数长期陈旧，也避免每帧 capture。若第一版实施证明回合内刷新需要大范围改执行器，则回退为“回合初快照”且标题必须明确写 `Tn 回合初`，不能假装实时；不允许无标注地展示陈旧数据。

### 4.3 面板布局与输入

- `SolverPanel.Row` 增加行类型/目标数据，不再让所有行都只是无身份字符串。
- 折叠标题是一条真实命中区域；路线行与折叠标题在按钮、拖拽标题之前处理，避免同一点击落入两个动作。
- `ensureLayout()` 缓存本次用于布局、渲染、命中的同一个 `Row[]`，避免 update 与 render 各自重建出不同行序。
- 展开内容导致面板变高时仍走 `clampToScreen()`；拖拽结束再夹取。
- 本刀优先沿用现有面板输入时机；真机必须验证点击不会同时选到下方卡牌。若出现 click-through，直接记录复现、评估最小输入消费修复及回退，实施并补真机验证；不得隐瞒全局输入补丁等范围变化，但不因此停工等待确认。

## 5. 第四刀：AUTO 至目标回合末

### 5.1 预览与执行队列分离

新增纯数据类 `verify/RoutePreview.java`（或经评审后放入 `ui`，但只能有一个权威实现）：

- 在接受 `AsyncSearch.Result` 时，从**完整** `result.steps` 构造预览；
- 保存 `originTurn = planTurn`（即 `GameActionManager.turn` 对应的绝对玩家回合）；
- 按 `END_TURN` 分组，生成 `absoluteTurn / displayText / hasEndTurn / steps`；
- 每项保留不可变 `RouteStep` 引用，用 `step == plan.peek()` 标当前下一手；
- `AutoPilot.plan` 仍是被消费的 `ArrayDeque<RouteStep>`，执行语义不变；
- `SolverPanel` 不再调用 `planLines()` 现场从剩余队列重新编号。

这解决两个现有问题：`planLines()` 从 1 开始，以及每出一手后续回合可能重排。预览被替换或作废时整体清空；目标停止回合不因预览清空而丢失。

### 5.2 点击资格与目标设置

新增公开入口建议为：

```java
public static boolean runUntil(int targetTurn)
```

行为：

1. 读取主线程当前绝对回合 `turnNow`；拒绝 `targetTurn < turnNow`，不改变现有状态，并返回 false。
2. UI 只允许 `hasEndTurn == true` 且 `targetTurn >= turnNow` 的预览行调用；执行入口仍自行校验过期目标，不能只信任 UI。
3. 设置 `stopAfterTurn = targetTurn`；`NO_STOP_TARGET` 用 `-1` 表示。
4. 若 AUTO 已开：保留开启，新的有效点击替换旧目标。
5. 若 AUTO 未开：开启 AUTO，但不走 `toggle()`，避免状态翻转歧义；清理项与手动开启保持一致。
6. 记录 `AUTO bounded start target=Tn current=Tm`；面板显示 `AUTO → Tn（每回合重算）`。
7. 不因点击而取消 `AsyncSearch`；当前搜索若有效就继续等，若失效由现有指纹机制丢弃。

空路线、搜索中、非战斗阶段没有可点击行；旧预览若已作废，其命中区域也必须消失。相同目标再次点击不定义成取消，避免误操作；取消统一走 AUTO 按钮。

### 5.3 停止状态机

有界 AUTO 不是一种新执行算法，只是在现有 AUTO 上增加停止条件：

```text
IDLE / 普通 AUTO（stopAfterTurn = -1）
       │ 点击有效 Tn
       ▼
BOUNDED_AUTO（enabled = true, stopAfterTurn = Tn）
       │ 每回合照旧搜索、漂移重算、执行当前真局面
       │ Tn 的 END_TURN 仍走 requestEndTurn()
       ▼
WAIT_ENEMY（现有 endPhase = 1 → 2）
       │ 等敌方阶段完成、动作队列稳定、已进入 Tn+1 玩家回合
       ▼
STOPPED（enabled = false, stopAfterTurn = -1）
```

每个 `tick()` 的顺序必须保证：

1. `enabled == false` 时直接返回；
2. 更新回合计数；
3. 若 `waitingForNextTurn()` 仍在等待则返回；
4. **在任何搜索、出牌或结束回合之前**检查 `stopAfterTurn >= 0 && turnNow > stopAfterTurn`；满足就同时 `enabled = false`、清目标并记录 `AUTO bounded stop after Tn; control returned at T(n+1)`，立即返回；
5. 其余现有逻辑不变。

停止判定不能只放在 `requestEndTurn()`：那里敌方阶段还没打完，立即停会让交接语义含糊；也不能只放在回合变化清计划处：那里可能继续本 tick 出牌。

### 5.4 异常与外部操作矩阵

| 情况 | 目标处理 | 行为 |
|---|---|---|
| 指纹/ShadowTracker 漂移、药水后重算 | **保留** | 清旧计划与旧预览，按真局面重搜；停止边界仍是绝对 Tn |
| 搜索暂时繁忙/等待 | 保留 | 等结果；不伪造路线 |
| 现有搜索失败/逐张兜底 | 保留 | 沿用当前 AUTO 既有兜底，不在本刀改变打牌策略 |
| 连续卡死、异常等现有逻辑关闭 AUTO | 清除 | 有界任务结束，不能下次开启时莫名其妙继承旧目标 |
| 手动点 AUTO 关闭 | 清除 | 立即交还控制；已入队动作仍由游戏自然结算，不强杀游戏动作 |
| 手动再开普通 AUTO | 保持无目标 | 只有路线点击能设置停止目标 |
| 点击另一有效路线行 | 替换 | 使用新的绝对回合；过期目标拒绝 |
| 战斗提前胜利/死亡/离开房间 | 清除 | `reset()` 清目标；下一场不继承 |
| 目标回合被游戏机制提前结束 | 保留至边界 | 仍等进入下一玩家回合后停；不要求必须由 solver 发出 END_TURN |
| 到达边界但战斗同时结束 | 清除 | 战斗结束优先，不能阻挡结算画面 |
| `AsyncSearch` 仍在跑 | 不强制取消 | 停止只约束执行；结果稍后因 AUTO 关闭不被消费， combat reset 仍按原逻辑处理 |

### 5.5 UI 文案与视觉

- 路线使用 `T{absoluteTurn}`，不再用相对 `T1/T2` 冒充绝对回合。
- 可点击行：正常亮色；当前目标行：明显金色或前缀 `◎`，并在状态行写 `AUTO → Tn`。
- 无 `END_TURN` 的预测尾行：暗色且不可点，不能承诺“打完此回合”。
- 旧预览被清后，有界目标仍可显示 `AUTO → Tn · 重算中`，防止用户误以为任务丢失。
- 停止后按钮回到 `AUTO`，目标高亮与提示清空；不自动再次开始本回合。

## 6. 精确改动边界（实施时不得悄悄扩大）

预计文件：

- 新建 `src/main/java/io/github/youqing/sts1solver/ui/StrategyStatusSnapshot.java`
- 新建 `src/main/java/io/github/youqing/sts1solver/verify/RoutePreview.java`
- 修改 `src/main/java/io/github/youqing/sts1solver/ui/SolverPanel.java`
- 修改 `src/main/java/io/github/youqing/sts1solver/verify/AutoPilot.java`
- 新建对应测试：
  - `src/test/java/io/github/youqing/sts1solver/ui/StrategyStatusSnapshotTest.java`
  - `src/test/java/io/github/youqing/sts1solver/verify/RoutePreviewTest.java`
  - `src/test/java/io/github/youqing/sts1solver/verify/BoundedAutoStopTest.java`
- 修改 `docs/plans/2026-10-31-ui-parity.md`
- 修改 `docs/research/blueprint-parity.md`
- 实施完成后更新 UI 实施计划、`docs/research/blueprint-parity.md` 与 `docs/handoff-prompt.md` 的真实状态；本仓库无 CHANGELOG 惯例，不凭空新建。

不改：`search/`、`mirror/`、`sim/`、`config/SolverConfig.java`、游戏语义补丁。若 `BoundedAutoStop` 无法在不实例化 STS1 游戏对象的情况下测试，应把“目标设置/取消/到期判断”抽成纯数据小类，而不是用反射绕过测试或在单元测试里启动游戏。

## 7. 副作用与风险

1. **预览寿命变长**：预览不再随每手消费而缩；必须与执行队列区分，计划失效时显式清除，避免展示旧路线。
2. **回合内 capture 次数增加**：只在动作稳定且 dirty 时捕获；禁止每帧捕获。真机检查帧率与日志次数。
3. **静态 UI 状态跨战斗残留**：`onCombatEnd()` 清 DTO、目标高亮和缓存行；折叠偏好可保留。
4. **命中区域与绘制错位**：使用同一次布局缓存的 `Row[]` 与 `rowTop()`；折叠/目标/新计划变化必须置脏。
5. **停止晚一手**：停止检查必须在等待结束后、任何行动前；这是最重要的执行回归点。
6. **停止早半回合**：不能在 Tn 的 END_TURN 入队时立刻 disable；必须等敌方阶段结束。
7. **点击穿透**：现有面板是 AbstractDungeon update 的后置补丁；必须真机验证。若需要前置输入补丁，评估副作用与回退后直接实施并留证，不等逐项批准。
8. **用户误解预览**：界面明示“每回合重算”，文档不得把路线预览称为执行承诺。
9. **测试数口径漂移**：当前实际测试数以实施时 Gradle 输出为准，不沿用旧文档的固定数字。

## 8. 回退方案

两刀分别提交，允许独立回退：

1. **第三刀回退**：revert 只读策略区 commit；恢复单行 `strategyLine` 状态提示。路线执行不受影响。
2. **第四刀回退**：revert 路线点击 commit；恢复 `planLines()` 文本展示与普通 AUTO 开关。没有配置文件迁移、没有持久化目标、没有 search 语义变更，因此不需要数据回滚。
3. **运行期最小止血**：点击 AUTO 关闭即可清目标并停止 solver 新动作；不需要删配置或重启游戏。
4. 若只读区回合内刷新引起性能问题，可单独退回“明确标注回合初”的快照，不必回退路线功能。

禁止用 `reset --hard` 或覆盖其他工作作为回退；只 revert 对应提交。

## 9. 验收标准

### 单元测试（先失败后通过）

只读区：

- 槽位顺序、空槽、被动/目标/选牌形态与 9/14/18 HP 门槛准确；
- 遗物计数原样显示，未镜像告警存在；成长只显示已有正向关键层数；
- 相同快照重复生成结果一致，且没有设置/控制入口；
- `AutoPilot` 不在渲染路径调用 capture（以代码评审 + 真机日志次数验证，不写脆弱的全局 mock）。

路线预览：

- origin=4 时显示 T4/T5，而不是 T1/T2；
- 模拟消费当前步骤后，回合号与行身份不变，下一手箭头移动；
- 只有含 END_TURN 的回合可点；无结束标记尾行不可点；
- 计划替换/作废后旧行不能继续点击。

有界停止：

- 当前回合与未来的有效目标可设，过期目标拒绝；
- 重算/漂移保留目标；手动关闭、异常关闭、reset 清目标；
- Tn 的 END_TURN 后不提前停；Tn+1 玩家回合在下一动作前停；
- 普通 AUTO 无目标，行为与旧版一致；
- 战斗结束不会被停止逻辑卡住。

### 真机验收

1. AUTO 关闭时点 Tn：开始执行，最终停在 Tn+1 玩家回合，不自动出新一手。
2. AUTO 已开时点更晚 Tn：替换目标；中途药水或漂移重算后仍停在原绝对目标。
3. Tn 的敌方阶段完整执行，怪物不重复行动、不跳过行动。
4. 到达边界前手动关 AUTO：不再自动开启；再开为普通 AUTO。
5. 战斗提前结束：正常进入结算，下一场无旧目标。
6. 展开/折叠策略区、拖拽、三档分辨率至少抽查 1920×1080 与 2560×1440；无文字重叠、命中错位或下方卡牌误选。
7. 只读区在喝药/打牌后按约定刷新，日志证明没有每帧 capture。

### 构建与留证

```bash
./gradlew test --offline
./gradlew jar --offline
```

- 全量测试绿，报告实际总数；不能用“旧测试没动”替代新行为测试。
- 真机留关键日志：bounded start / replan target retained / bounded stop。
- 两刀各自 commit；是否 push 由用户确认，不在设计阶段自动推送。

## 10. 后续独立任务：真正的蓝本策略控制器

只读区验收后再单独立项，设计必须覆盖：

- `SolverConfig` 的策略持久化；
- 药水 Smart / Disabled / Force 与预设如何进入搜索；
- 遗物 `RelicCounterRule` 对应 STS1 的哪些计数与战后收益；
- 成长 `GrowthRewards` / HP 额度，而不是把 Power 层数当控制；
- `AsyncSearch.Result` 是否需要扩展药水对账遥测；
- 改配置后如何使旧搜索/旧计划失效；
- 对牌技的 A/B 验收（战损、胜率、路线合理性），绝不以耗时下降为目标。

在该任务完成前，对照表中的三个蓝本策略面板仍应标为 **❌ 控制器未做；本轮只有只读现状适配**。
