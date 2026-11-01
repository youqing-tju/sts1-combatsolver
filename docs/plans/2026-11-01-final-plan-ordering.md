# 完整 FinalPlanOrdering / 路线质量实施计划

> **执行方必读**：全程 TDD。每个行为先写失败测试并亲眼看到 RED，再实现到 GREEN。禁止先写实现后补测试。
> **设计依据**：`docs/plans/2026-11-01-final-plan-ordering-design.md`。
> **最高规则**：禁止自创，蓝本没有 = 不做；不得改变 18 键顺序、候选范围、默认值或失败语义。

## 基线与工具

- 项目：`C:\Users\22757\Desktop\sts1-combatsolver`，分支 `master`。
- 代码基线：`de9de4b`；文档基线：`1214068`；交接称 696 tests 全绿，需亲自复跑确认。
- 蓝本：`reference/sts2-combatsolver/src/Search/CombatBeamSolver.FinalPlanOrdering.cs`。
- 依赖源码：`Terminal.cs`、`StateEvaluation.cs`、`GrowthPolicy.cs`、`RouteQuality*.cs`、`Runtime/BattleDamageTracker.cs`、`Prediction/CorePowerSupport.cs`。
- 构建：Gradle 9.8.2，`options.release=8`；测试命令统一 `./gradlew test --offline`。
- 每个 Task 独立本地 commit；不 push，不用 `reset --hard`，不覆盖他人改动。

---

## Task 0：封存基线并复测夜间 8 项

**目标**：让后续 A/B 有真实旧实现基线，且不把夜间未重启的旧 jar 当成新代码。

### Step 0.1 自动化与旧实现快照基线

运行：

```bash
git status --short --branch
git log -12 --oneline
./gradlew test --offline
./gradlew test --offline --tests "*SnapshotBatchProbeTest" -Dbatch=C:/Users/22757/Desktop/sts1-combatsolver/solver-snapshots
```

保存旧实现逐快照结果：首手、完整路线、胜负、停止原因、结束 HP/maxHP、敌方 HP、动作数、药水数、Score。若当前探针缺这些字段，**先只扩展诊断输出，不改搜索行为**，补 `SnapshotBatchProbeTest` 的可重复输出合同后单独 commit：

```text
test: snapshot baseline exports deterministic route-quality metrics
```

### Step 0.2 真机前置复测

构建、安装并**重启 MTS**，确认面板 build 是新 jar。逐项验证交接 §十一 8 个修复：多段意图、神化、木乃伊、觉醒者半死/REBIRTH、无活怪结束回合、开局发牌、LiveRisk 死亡豁免、抢钱怪。

记录：`场景 | 日志/数值 | 通过/失败 | 是否阻断本轮`。夜间修复本身失败时先修根因，不用排序改动掩盖。真机不可用则明确标“未验证”，不得写成通过。

---

## Task 1：纯数据 FinalPlanOrdering——18 键逐行翻译

**目标**：在不接搜索行为的情况下，先用合成候选钉死完整最终选择语义。

### Step 1.1 RED：18 键与优先级合同

创建：

- `src/test/java/io/github/youqing/sts1solver/search/FinalPlanOrderingTest.java`
- `src/test/java/io/github/youqing/sts1solver/search/RouteQualityTest.java`

至少覆盖：

1. 源码实际是 18 键；逐键构造前序全部相同，只改变当前键。
2. 每个键的比较方向；高优先级键能压倒所有低优先级键。
3. 两条胜利路线的第二键恒同；失败路线才比较 Survives。
4. `CombatEndedTurn` 只有完整胜利有值；玩家死亡和未结束都是 `Integer.MAX_VALUE`。
5. `HealthResourceCost = initialHp-finalHp + initialMaxHp-finalMaxHp`，允许负值，不夹 0。
6. `PolicyHpDeficit` 只在有效 RequireAtLeastOne 时增加 `max(0, optionalCost-9)`。
7. `PolicyBoundaryRank` 五档精确映射，非法枚举显式失败。
8. `OptionalPotionCount = max(0, explicitCount-forcedCount)`，自动药不进显式数。
9. `ActionCount` 是动作数，不是回合层数。
10. 完全同键保持输入稳定顺序；不得增加 StateKey/指纹等第 19 键。
11. `PotionStrategicCost` 不作为额外独立排序键。

运行并看到“类/方法不存在”或断言失败：

```bash
./gradlew test --offline --tests "*FinalPlanOrderingTest" --tests "*RouteQualityTest"
```

### Step 1.2 GREEN：实现纯数据模型与比较器

建议创建（名称可按蓝本调整，但不得改变职责）：

- `search/SearchBoundaryReason.java`
- `search/SearchFeatures.java`
- `search/RouteQuality.java`
- `search/FinalPlanOrdering.java`

蓝本证据：`FinalPlanOrdering.cs:28-321`、`Retention.cs` 的 `PolicyBoundaryRank`、`RouteQualityPolicy.cs`。

实现：

- 从纯数据候选计算特征和 RouteQuality。
- 资格过滤与 18 键选择分开表达；本 Task 可用合成基线测试资格，不接 Beam。
- 无合格候选抛现有 `PotionPolicyUnsatisfiedException`，文案沿用蓝本三类。
- 不读取 `BeamSolver` 可变全局状态，不读取真机或 `SolverConfig`。

### Step 1.3 全量回归与 commit

```bash
./gradlew test --offline
git diff --check
git add src/main/java src/test/java && git commit -m "feat(search): translate complete final plan ordering"
```

---

## Task 2：成长、长期资源与 Anger 的真实事件账本

**目标**：让第 6/7/11/12 键来自真实路线事件，而不是从结果血量/金币猜。

### Step 2.1 RED：成长与复制事件合同

创建/扩展：

- `search/GrowthValuesTest.java`
- `search/GrowthOrderingIntegrationTest.java`
- `mirror/FeedGrowthTest.java`
- `mirror/HandOfGreedGrowthTest.java`
- `mirror/AngerCopyLedgerTest.java`

必须覆盖：

- Feed 只有原版认可的成功致死才 `Feed+1`；未击杀不记；升级数值按 javap。
- Hand of Greed 只有成功致死且实际得金才记成长和 `LongTermResourceValue += gainedGold`；金币被其他规则截断时只记实际到手值。
- Feed 的最大生命增长不额外冒充 LongTermResourceValue。
- Anger 每实际生成一张复制牌 `AngerCopiesGenerated+1`；其他 copy 效果不冒充 Anger。
- 默认成长预算为 0：GrowthRewardCount 可比较免费成长，GrowthHpCredit 为 0，不允许卖血换信用。
- 显式预算测试只使用蓝本 `GrowthValues.Credit` 乘法，不发明默认值或 UI。
- `copy/fork` 后事件账本独立；跨回合重搜保留根状态已发生事件；新战斗 reset 清零。

运行看到 RED：

```bash
./gradlew test --offline --tests "*GrowthValuesTest" --tests "*GrowthOrderingIntegrationTest" --tests "*FeedGrowthTest" --tests "*HandOfGreedGrowthTest" --tests "*AngerCopyLedgerTest"
```

### Step 2.2 GREEN：接入 SimState 与卡牌镜像

修改/创建：

- `search/GrowthValues.java`
- `sim/SimState.java`
- `mirror/Effects.java`
- 必要时 `MirrorRegistry.java`、`search/Score.java`、`search/BeamSolver.java`

要求：

- 事件在实际效果成功提交的位置记录，不在排序器里反推。
- `SimState.copy()`、fingerprint、ContinuationStamp、toJson/GoldenPositions、diff 按字段语义同步；老 JSON 的默认值只能是蓝本空账本。
- 真机历史/根基线必须来自已核实来源。需要读原生历史时先 javap；不得用 `maxHp` 或 `gold` 差值伪造事件次数。
- `Score` 若接蓝本长期资源 Beam 分与 GrowthHpCredit，常量、cap 和 ignore 源头逐项照 `StateEvaluation.cs`/`SolverWeights.cs`；不得只加最终键却让成长路线在 Beam 中被系统性剪掉。

### Step 2.3 全量回归与 commit

```bash
./gradlew test --offline
git diff --check
git add src/main/java src/test/java && git commit -m "feat(search): record realized growth and deck resource events"
```

---

## Task 3：逐回合 FutureSoldHp 与战斗已承诺卖血

**目标**：实现第 15 键 `StrategicSold`，并保留跨回合/重搜历史。

### Step 3.1 RED：最低战损基线与 committed 合同

创建：

- `search/TurnOutcomeTest.java`
- `search/SoldHpAnnotationTest.java`
- `verify/BattleDamageTrackerTest.java`

覆盖：

1. 同一 `(turn, turnStart, potionSlotsUsed)` 组：`sold = hpLost - minHpLost`，最小者为 0。
2. 不同 turnStart 或不同药槽组合不得共享最低值；自动药按蓝本额外占位处理。
3. PendingChoice/UnsupportedEffect 不进最低值且自身 sold=0。
4. `futureSold = parentFutureSold + soldThisTurn`，回放与逐节点累计一致。
5. 战斗翻页：`committed += min(plannedSold, actualLoss)`；实际掉血较少时不超额。
6. 重搜保留 `hpLostSoFar/soldHpCommitted/potionsUsed/recovered`；战斗 reset 才清零。
7. 快照投影卖血与真机实际 committed 分开命名，不混写。

运行看到 RED：

```bash
./gradlew test --offline --tests "*TurnOutcomeTest" --tests "*SoldHpAnnotationTest" --tests "*BattleDamageTrackerTest"
```

### Step 3.2 GREEN：翻译 Terminal 注记与 BattleDamageTracker

修改/创建：

- `search/TurnOutcome.java`
- `search/SearchNode.java`
- `search/BeamSolver.java`
- `verify/BattleDamageTracker.java`
- `verify/AutoPilot.java`（仅主线程观察/注册计划）

严格对照 `CombatBeamSolver.Terminal.cs:30-200` 与 `Runtime/BattleDamageTracker.cs:7-128`。`StrategicSold = battleCommitted + futureSold`，不得用总掉血替代。

`Score` 的 SoldHpPenalty 若接入，必须复用蓝本同一注记时机，先移除旧 penalty 再加新累计，避免重复扣罚；真机对象只留主线程。

### Step 3.3 全量回归与 commit

```bash
./gradlew test --offline
git diff --check
git add src/main/java src/test/java && git commit -m "feat(search): track avoidable hp sold across battle routes"
```

---

## Task 4：完整候选池与比较器调用边界

**目标**：所有最终候选都能进入 Task 1 选择器；搜索中临时候选不被强行套最终 18 键。

### Step 4.1 RED：完整池与 interim/final 分离

创建/扩展：

- `search/FinalCandidatePoolTest.java`
- `search/InterimOrderingBoundaryTest.java`
- `search/BeamSolverContinuationTest.java`

覆盖：

- 两个以上 completed 中，当前 `bestTerminal` 不是最终 18 键第一名时，完整池仍保留真正最佳者。
- 最后 frontier 不丢；空 completed/空 frontier 的 fallback 与蓝本一致。
- portfolio 聚合每个成员完整池，而非每成员只交一个 best。
- 当前回合 interim 候选仍按蓝本 interim 口径，不因最终新增 Growth/Sold 等键改变调用语义。
- 每个候选携带自己的 Boundary；不得读取全局 `lastStopReason` 替代。
- `depth == ActionCount`：出牌/药水/END_TURN 各 +1，根为 0。

运行看到 RED：

```bash
./gradlew test --offline --tests "*FinalCandidatePoolTest" --tests "*InterimOrderingBoundaryTest" --tests "*BeamSolverContinuationTest"
```

### Step 4.2 GREEN：保存全部 completed 并接入最终入口

修改：

- `search/SearchNode.java`
- `search/BeamSolver.java`
- 必要时 `search/AsyncSearch.java`

要求：

- `lastFinalPool` 与注释一致，确实是全部 completed + 最后 frontier + 蓝本规定 fallback。
- 主 Beam、portfolio、novelty 最终候选进入统一 `FinalPlanOrdering.Select`；不再用 `better()` 代替最终选择器。
- `better()` 若保留给 interim，改名或加注释明确职责；Beam `rank` 仍只用于保留。
- 最终选择后再统一发布 `lastRoute/lastSteps/continuations`，三者同源。

### Step 4.3 全量回归与 commit

```bash
./gradlew test --offline
git diff --check
git add src/main/java src/test/java && git commit -m "fix(search): select from complete final candidate pools"
```

---

## Task 5：统一药水资格、基线与 post-search 采纳

**目标**：删除“先选一条再补救”的本地流程，恢复蓝本最终资格链。

### Step 5.1 RED：资格过滤覆盖所有通道

扩展：

- `PotionCostGateTest.java`
- `PotionGateBeatsNoveltyTest.java`
- `PotionPolicyChangeTest.java`
- 新建 `FinalPotionEligibilityTest.java`

覆盖：

- 当前第一名不满足 Force/Require/Smart，但池中存在次优合格路线时选次优者，而不是直接抛或退回违规路线。
- 无药基线按 `ComparePotionFreePolicyBaselines` 选择，不能取列表第一瓶无药路线。
- Smart+Force 使用 forced-only 基线；optional 药只相对该基线计价。
- 全局 Disabled 不压 Force；Require 被 Force 满足后 optional 按 Smart。
- 没有合格候选才抛 `PotionPolicyUnsatisfiedException`；预算不足/基线截断不得放宽硬约束。
- novelty、portfolio、0 费补搜及任何真实 post-search 候选都不能把被否决路线捞回来。
- 最终 `lastRoute/lastSteps` 与资格结果一致。

运行看到 RED：

```bash
./gradlew test --offline --tests "*FinalPotionEligibilityTest" --tests "*PotionCostGateTest" --tests "*PotionGateBeatsNoveltyTest" --tests "*PotionPolicyChangeTest"
```

### Step 5.2 GREEN：合并最终资格与后处理顺序

修改：

- `search/BeamSolver.java`
- `search/PotionStrategySnapshot.java`
- `search/PotionUsePolicy.java`
- 必要时 coordinator 对应的本项目搜索入口

要求：

- 先核对蓝本 `CombatSearchCoordinator` post-search 的真实顺序，再决定 `gatePotionSpending`/`verifyPotionStrategy` 的删除与迁移；不得保留双套资格语义。
- 所有最终采纳候选都通过同一资格入口；反事实 baseline 不递归套用用户硬约束。
- 0 费补搜只在蓝本允许的前提出发，其答案仍按蓝本相应比较路径处理。
- 最终 continuation 只能从所有后处理完成后的选中路线回放产生。

### Step 5.3 全量回归与 commit

```bash
./gradlew test --offline
git diff --check
git add src/main/java src/test/java && git commit -m "fix(search): apply potion eligibility before final ordering"
```

---

## Task 6：全通道一致性与全量回归

**目标**：证明并行、portfolio、novelty、continuation 与最终发布没有分叉。

### Step 6.1 RED/GREEN 合同

新增/扩展测试：

- 同一夹具串行与 DOP2/DOP4 的最终选中路线、18 键特征、first action 完全一致。
- novelty 开/关、portfolio 开/关均只对同一最终池做资格与排序。
- 续跑命中沿用原选中世界线；新指标不会使 continuation 状态在 copy 后意外归零。
- reset 后 Growth/Sold/BattleDamage 不跨战斗泄漏。
- 所有既有 696+ 测试通过；旧测试若钉住 17 键/局部池/本地闸门，改为蓝本反向合同，不直接删除。

运行：

```bash
./gradlew test --offline
./gradlew test --offline --tests "*ParallelExpansionTest" --tests "*BeamSolverContinuationTest" --tests "*Potion*Test"
```

修复所有回归后：

```bash
git diff --check
git add src/main/java src/test/java && git commit -m "test(search): prove final selection consistency across search lanes"
```

---

## Task 7：497 快照 paired A/B 与针对性路线夹具

**目标**：用相同输入比较旧/新路线质量，不用“测试全绿”宣称牌技提升。

### Step 7.1 新实现批量输出

扩展 `SnapshotBatchProbeTest` 或新建 `SnapshotRouteQualityProbeTest`，输出与 Task 0 完全相同的字段和顺序，并追加：

- StrategicHpDeficit、HealthResourceCost、GrowthRewardCount、GrowthHpCredit。
- LongTermResourceValue、AngerCopiesGenerated、OptionalPotionCount、StrategicSold、ActionCount。
- 最终候选数、资格淘汰数、最终 Boundary。

运行：

```bash
./gradlew test --offline --tests "*SnapshotBatchProbeTest" -Dbatch=C:/Users/22757/Desktop/sts1-combatsolver/solver-snapshots
```

### Step 7.2 配对分析

写可重复的分析工具/测试，逐文件对齐 Task 0 与 Task 7，报告：

- 由负转胜、由胜转负、胜负不变。
- StrategicHpDeficit 改善/不变/恶化及配对差值；HealthResourceCost 同报。
- Feed 次数和 maxHP 增量、Hand of Greed 金币、Anger 复制数、动作数、StrategicSold。
- 首手变化、完整路线变化、异常与停止原因。

相关快照不能伪称独立样本；能聚类到同一战斗则按战斗聚类，否则只给描述统计。任何由胜转负或战损恶化样本都要建最小 golden 夹具并归因到“比较器正确但镜像暴露旧错”或“实现偏差”，不得悄悄略过。

增加至少四个端到端 golden：低战损胜利、Feed、Hand of Greed、Anger。每个夹具先证明旧/新行为差异，再断言蓝本预期。

提交：

```text
test: compare route quality across captured combat snapshots
```

---

## Task 8：真机验收、双评审与文档闭环

### Step 8.1 构建安装

```bash
./gradlew test --offline
./gradlew jar --offline
tools/install.ps1
git diff --check
```

重启 MTS，核对新 build。未重启的所有结果无效。

### Step 8.2 真机留证

按设计 §7.3 逐项记录：低战损胜利、Feed、Hand of Greed、Anger、跨回合重搜的 sold/battle 基线。保存实际 HP/maxHP/gold、路线和日志，不用投影值代替。

同时确认交接 §十一 8 项没有在排序重构后回归。真机未执行项明确写“未验证”。

### Step 8.3 双评审

- **规范评审**：逐行对照 18 键、候选池、资格、默认值、post-search 顺序；特别查是否误加 PotionStrategicCost 第 19 键。
- **质量评审**：查 copy/fork 泄漏、后台真机访问、候选池提前裁剪、重复 SoldHpPenalty、战斗 reset 和 continuation 同源。

评审发现必须修复后重跑全量，不以“基本符合”收尾。

### Step 8.4 文档与 commit

更新：

- `docs/research/blueprint-parity.md`：18 键逐项状态与证据；未做机制仍写未做。
- `docs/handoff-prompt.md`：自动化/回放/真机三层结果、A/B 数据、未闭环项与回退 commit。
- 本计划：每项实际状态，不把计划写成已完成。
- 根目录 `记录.txt`：只沉淀可复用教训。

最终 commit：

```text
docs: record final plan ordering verification
```

最终汇报必须包含：改动、测试数、paired A/B、真机证据、未验证项、回退方式；不 push。
