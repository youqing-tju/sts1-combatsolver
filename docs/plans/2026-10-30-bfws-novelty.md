# BFWS 新颖度搜索（NoveltySearch + NoveltyPortfolio）

> 目标：让第二章的战斗**真的把预算算掉**。真机现状：宽 135 / 节点帽 50 万，第二章精英战
> 只用 3769 个展开（2% 预算、131 ms）。蓝本在同一档位下是几秒到几十秒。
> 本文只写「搬什么、怎么判、判据是什么」，机制细节以蓝本文件为准。

---

## 一、蓝本对应物

| 蓝本文件 | 作用 |
|---|---|
| `Search/NoveltySearchOptions.cs` | 参数：`Width=2, FamiliarAllowance=0, MaxOpen=2048, MaxNoveltyEntries=1M, MaxActions=128, MaxTurns=32` |
| `Search/BfwsPackedNovelty.cs` | 新颖度表：事实 → 原子 id；按分区记「已见过的 1 元组 / 2 元组」；`Evaluate` 返回 1 / 2 / 3 |
| `Search/BfwsBoundedOpen.cs` | 容量有界的双端优先队列，满了丢**最差**的那个 |
| `Search/BfwsEscapeBudget.cs` | 「熟悉子节点」的准入额度（默认 0 = 一个都不放） |
| `Search/CombatBeamSolver.NoveltySearch.cs` | 主循环 `RunNoveltyOpen` |
| `Search/CombatSearchCoordinator.NoveltyPortfolio.cs` | 组合：先跑有限的探索，再跑 Beam，二选一 |
| `Search/NoveltyPortfolioBudget.cs` | 预算：`时间 min(5000, 总预算/2)`（Boss `/4`）、`节点 min(2500, 总节点/4)` |

蓝本的顺序（`RunNoveltyPortfolioPass`）：

1. 用 `explorationProfile` 跑一趟 **novelty 模式**的 solver（它有独立的节点/时间帽）；
2. 用剩余预算跑 **Beam**（`Remaining` 扣掉已用时间/节点）；
3. `selectExploration = baseline 完成 && exploration 是完整胜利 && 药水政策更优`；
4. 选中的那条写进 `NoveltyPortfolio` 遥测。

## 二、STS1 上的落地

### 2.1 新颖度搜索本身（`NoveltySearch.java`）

一个**最佳优先**搜索，替代「每回合层层剪枝」的 beam：

```
open  = BoundedOpen(maxOpen)                  // 优先级 (新颖度 desc, score desc, 序号 asc)
novelty = PackedNovelty(width=2, maxEntries)
enqueue(root)
while open 非空:
    到节点帽 / 到时间帽 → stop
    parent = open.dequeue()
    if parent.actions >= 128 || parent.turn >= startTurn + 32 → horizonLeaf++; continue
    novelty.prepareParent(partition(parent), facts(parent))
    for child in expand(parent):              // 全部出牌分支 + 结束回合
        if child 是终局 → completed 收集；若胜利且掉血更少 → 记最优
        else enqueue(child, parentEscape)     // 新颖度 > 2 且没有额度 → 丢弃
    if completed > 64 → 按 better() 只留最好的一条
```

- **`Width=2`**：一个子状态只要「至少有一对事实组合是新的」就准进；全都见过 = 熟悉 = 丢。
- **`FamiliarAllowance=0`**：熟悉子节点一个都不放（蓝本默认）。
- **`MaxOpen=2048`**：open 满了丢最差的，所以内存有上界（每个节点是一份 `SimState` 拷贝）。

### 2.2 事实集（`CaptureNoveltyFacts` 的 STS1 等价物）

蓝本 20 个标量 + 4 个牌堆 + 前 3 张抽牌堆 + 双方 Power。我们只搬 `SimState` 里**真有**的：

| 类别 | 事实 |
|---|---|
| 能量 / 格挡 / 血量 | `E{energy}`、`B{block/5}`、`H{hp/5}` |
| 手牌规模 | `HN{hand.size}` |
| 四个牌堆 | `Z{zone}{cardKey}{min(3,count)}`（手牌 / 抽牌 / 弃牌 / 消耗） |
| 抽牌堆顶 | `D{i}{cardKey}`，i < 3 |
| 双方状态 | `P{owner}{powerId}{amount}` |
| 敌人 | `AM{存活数}`、`MT{i}{hp/10}` |

分区（`partition`）= `(敌人总血/10, 药水数)` —— 与蓝本一字不差。
**不搬**的：蓝本的 20 个战略标量里有十来个（`ReachableHandValue`、`LatentSetupValue`、
`StrategicEffects.*`…）我们没有对应字段，硬凑等于自创，宁可少几维。

### 2.3 组合（`BeamSolver.bestFirstAction` 内）

```
exploration = novelty(root, min(5000ms, budget/2), min(2500, nodeLimit/4))   // Boss 时间 /4
beam        = 现有 beam 搜索（剩余预算/节点）
selected    = better(exploration, beam) ? exploration : beam
```

`better()` 已经是全序（胜利 > 存活 > 死免用得少 > 战略掉血少 > 结束回合早 > 敌人血少 > 分高 > 动作少），
所以「二选一」不用另写规则。**Beam 那条路一行不改** → 第一章基线不会被动到。

### 2.4 开关

`SolverConfig` 新增 `novelty`（默认 `true`），面板/配置可关。

## 三、验收判据

| 编号 | 判据 | 怎么量 |
|---|---|---|
| N1 | 新颖度搜索本身能跑完不崩，`stop` 原因是 `open_exhausted` / `node_limit` / `time_limit` 之一 | 单测 |
| N2 | 第二章精英战 ≥ 5 千展开、冠军 Boss ≥ 7 万展开 | `ActTwoSearchSizeTest` |
| N3 | 精英战单步 ≥ 0.5 s、Boss 单步 ≥ 3 s（VeryHigh 档） | 同上 |
| N4 | 三条真机链路不退化：`background search failed` = 0、失配率不升、`AUTO end turn with N energy left` 不增 | 真机日志 |
| N5 | `ActOneGauntletTest` 总战损 ≤ 105（现基线 93 毛 / 63 净） | 全量测试 |

## 四、风险

- **内存**：open 里每个节点攥一份 `SimState`，2048 × ~几十 KB 可能上百 MB。先按 2048 跑，
  真机看 GC；必要时只保留 `MaxOpen` 的 1/4。
- **时间**：novelty 帽 5 秒是蓝本的默认值。第一章战斗也会吃这一趟，实测如果 > 300 ms
  就把 `MaximumMilliseconds` 降到 1500（对第一章足够）。
- **不吃预算**：若第一章也出现「算满 5 秒」，说明新颖度表的原子爆炸（`MaxNoveltyEntries` 打到 1M），
  这时应缩事实集而不是缩预算。
