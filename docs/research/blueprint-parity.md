---
summary: "蓝本复刻对照表：哪些已搬、哪些没搬、按什么顺序搬"
read_when:
  - 准备动手改 search/eval 任何一块之前
  - 判断「这是不是蓝本里的东西」时
---

# 蓝本复刻对照表

> 用途：把「复刻到什么程度了」变成可查的清单，避免凭印象判断漏项、也避免自己发明机制。
> 判定口径：**能在蓝本源码里指出对应实现（文件 + 大致行）** 才算「已复刻」；
> 想不出来源的，一律记为「未复刻 / 不适用」，不允许用「应该差不多」结案。

蓝本路径：`reference/sts2-combatsolver/`，搜索层在 `src/Search/`（176 个文件）。
我们：`src/main/java/io/github/youqing/sts1solver/{search,mirror,sim,verify}`。

## 1. 估值（权重与打分）

| 蓝本 | 我们的 | 状态 |
|---|---|---|
| `SolverWeights.cs` 全部常量 | `Score` 里的常量 | ✅ 已复刻（逐值照抄） |
| `StateEvaluation.cs` 基础分（投影血量 / 最大生命成长 / 已损血量 / 胜负 / 敌方有效血 / 牌库杂质） | `Score.baseTotal()` | ✅ |
| `BeamRetentionPolicy.Ranking.cs:892` `BeamRankScore`（能量 / 持续成长 / 延迟收益 / 攻击总量成长 / 易伤 / 虚弱 / 力量削减 / 动作数） | `Score.beamTotal()` | ✅ |
| `FinalPlanOrdering.cs:253-271` 词典序 | `BeamSolver.better()` | 🔶 部分：17 条键里搬了 6 条（有 STS1 对应物的） |
| `ActEndingBossPolicy.StrategicHpDeficit` | `BeamSolver.strategicHpDeficit` | ✅（`BossHpRelief.None` 档） |
| `StateEvaluation.cs` `StrategicEffects` / 逐能力 RetentionValue | `mirror/PowerValuation` + `Score.persistentBuffValue` | 🔶 **部分（2026-10-24）**：`StrategicEffectModel` 的五维模型 + 7 个能力的专门分支已搬（力量/敏捷/恶魔形态/无痛/黑暗拥抱/腐化/荆棘），其余走蓝本自己的默认档 `Scaling(amount)`（`mechanics-reference` §32）。⚠️ 两处近似：`IncomingHitCount` 用怪物只数、`AverageCardValue` 用面板伤害。仍缺：`LatentSetupValue` 逐卡表、`ReplayPotentialValue`（⛔ 无对应）、`DelayedDamage`/`ReactiveDamage`（铁甲无施加源） |
| `LatentCardSetupValue` 逐卡分值表 | 走默认分支（能力牌 2 点） | 🔶 部分：表是 STS2 卡，不臆造 |
| `ReplayPotentialValue`（附魔重放次数） | — | ⛔ 不适用：STS1 无附魔/重放机制（唯一接近的是双发/爆发，但那是「下一次打两次」，不是可重放卡） |
| 药水估值（`PotionStrategicCost` / `SmartRequiredHpSaved`） | `mirror/PotionValuation` + `BeamSolver.gatePotionSpending` | 🔶 **部分（2026-10-24）**：`PotionUsePolicy.StrategicHpCost` 三档成本（9/14/18，逐值照抄 `SolverWeights`）+ **对账闸**已搬 —— 喝药必须比「不喝药基线」多省 ≥ Σ成本，否则退回不喝药的路线（`mechanics-reference` §31）。仍缺：`SolverPotionPolicy` 档位（Smart / RequireAtLeastOne / Disabled）、强制用药指令、`PotionRewardOutlook`（腾槽位换奖励）—— 都要 UI 侧栏额度输入；药水镜像 31 张（2026-10-26） |
| `DeathSave*`（`DeathSaveHpRestored` / `DeathSavePremium` / `DeathSaveUseCount`） | `mirror/DeathSaveRelic` + `Score.deathSaveBeamCost` + `BeamSolver.strategicHpDeficit` | 🔶 **遗物侧已做**：**蜥蜴尾巴**在仿真里真拦一次致死伤害、记回血量、进三处计价；**瓶中仙子**（药水）等药水建模后接 |
| `GrowthRewards` / `GrowthHpCredit` / `LongTermResourceValue`（局外收益） | 只有 `Feed` 的最大生命增长进了基础分 | 🔶 **部分**：`GrowthSource` 里就有 **Feed**；我们缺的是「成长奖励」这条独立计分轴与逐来源计数 |
| `UnrecoveredLoot` / `TheftEncounterStrategy`（被偷资源） | — | ❌ **未做**：STS1 有 **Looter（掠夺者）** 偷金币后逃走 |
| `RelicCounterEvaluation`（遗物计数估值） | 计数遗物在仿真里真触发 + `Score.thresholdPostCombatHeal` | 🔶 **部分**：战斗内计数（修女/手里剑/开信刀/墨水瓶）真触发；**战后回血**两条通道都在了 —— 无条件回血（燃烧之血/黑血）进 `StrategicHpDeficit`，**阈值回血（带骨肉）进基础分**（`StateEvaluation.cs:200-207` 的 `HealingHpCredit`，2026-10-24，`mechanics-reference` §30）。仍缺：`RelicCounterTarget` 那套「计数值达标给 HpCredit / 优先级 / 距离」的目标策略（依赖 UI 目标配置，未建模） |
| `_isActEndingBoss` 分支（cap 32 / 虚弱 2 / horizon 8） | — | 🔶 **部分**：蓝本靠 `BossHpRelief` 分三档 —— `RunEnding`（最后一战，后面没战斗）在 STS1 **适用**（心脏/终幕 Boss）；`ActClearHeal`（幕末回 80 伤害）STS1 没有，那一段不适用 |

## 2. 搜索循环

| 蓝本 | 我们的 | 状态 |
|---|---|---|
| `Phases.cs:1479-1600` 回合层循环（外层回合 / 内层 playDepth） | `BeamSolver.searchToDepth` | ✅ |
| `MinimumTurnLayerExpandedNodes` 每层节点预算 + 强制结束回合 | 同 | ✅ |
| `reservedTurnLayers`（4 / Boss 8） | `STANDARD/BOSS_RESERVED_TURN_LAYERS` | ✅ |
| `AcceptExpandedChild` 分流（同回合 → plays，跨回合/终局 → ended） | `expandTurnLayer` + `route()` | ✅ |
| `EscalateSearchWhenNoVictory`（Beam+节点帽+分支一起 ×2，最多 2 次，封顶 512/100，耗时预估，严格改善才采纳） | `bestFirstAction` 的加宽循环 | ✅ |
| `ConsiderCompleteVictory`（完整胜利登记） | `bestTerminal` + `better()` | ✅ |
| `ConsiderCurrentTurnCandidate` + `adoptedNode = currentBestNode ?? currentTurnCandidateNode`（`Phases.cs:372 / 2218`） | `currentTurnCandidate` + 三档选择顺序 | ✅ 选择顺序已复刻。**早停不搬**（2026-10-23 考证修正）：早停是**手动面板模式**的「立即交付」——用户点「执行本回合」按钮后，后台搜索在三个检查点（`Phases.cs:1445 / 1554 / 2154`）看到接管请求就提前收工，交付 `currentBestNode ?? currentTurnCandidateNode`。**FullAuto（全自动）不点这个按钮**（`SolverController.cs:1485 SetFullAuto` → 每回合 `AutoTurnStart` 自动准备后续部署；精确 continuation 命中可复用，未命中才重搜），等得起；我们的 AutoPilot 对应的就是 FullAuto（同构性见 §3 执行层行），不走早停路径 |
| `_earlyTurnScoutDepth`（开局侦察层数） | — | ❌ 未做：它本身只是「回合层循环上界 + 保留层数」的旋钮（`Phases.cs:1428 / :1480`），唯一消费者是 `RunEarlyTurnExploration`（`EarlyTurnExploration.cs`，浅搜 scout → 收 1/2 回合边界前沿 → 按前缀续搜）。单独搬进来只会把搜索截浅，没有消费者 → 无意义 |
| `IncrementalVerificationMaxTurns = 32` | — | ❌ 未做（验证模式上限） |
| `acceptableBattleHpLossReached`（打到达标战损就停） | — | ⛔ 不适用：需要跨战斗血量目标 |
| `Expansion.Opening.cs`（开局展开特化） | `BeamSolver.openingNoCostPrefixes` + `zeroCostOpeningRescue` | 🔶 **部分**：**0 费开局补搜**已整条搬（`RunZeroCostOpeningRescue` + `BuildOpeningNoCostPrefixes` + `ApplyFixedPrefix`，2026-10-23，`mechanics-reference` §27）。其余 `Build*` 全家（power / fetched power / hand setup / copy / target variant …）依赖 STS2 专属物：`OpeningActionRegistry` 的两条规则是 `WHITE_NOISE`（STS1 属故障机器人）与 `DUPLICATOR` 药水，且 `PowerCardValuationModels.Registry` 未建模 → 不搬 |
| `CombatSearchCoordinator.EarlyTurnExploration` 的前沿选择 `SelectEarlyTurnFrontier` | — | ❌ 未做：分组键是 7 元组，其中 **4 项**未建模（`SearchRouteTraits` / `PersistentSetupTraits` / `ReachableHandValue` / `FocusTargetCombatId`，`EarlyTurnFrontier.cs:55-62`）。`PersistentBuffValue` **已建模**（`PowerValuation.persistentBuffValue`，2026-10-27 核对）。自选子集做分桶 = 自创语义，与 Retention 批 3 同判据。⚠️ **依赖链已核清**：`ReachableHandValue` 需要逐张牌的 `CardValue`（蓝本 `CardChoiceSupport.CardValue` = `damage + block*0.8 + draw*3 + power?8:0`），而 STS1 的 `SimCard` 只有 `baseDamage`、没有 `baseBlock`/抽牌数 —— 要做得先给捕获层补这两个字段，再谈分桶 |
| `Expansion.Candidates.cs` / `Expansion.Choices.cs`（候选与牌内选择的展开） | `expandTurnLayer` 的三种分支 | 🔶 部分：只有「目标 / 选牌下标」两档，没有蓝本的候选评估 |
| `ExpansionExecutor.cs` / `ParallelExpansion.cs`（并行展开） | `search/ParallelExpansion` + `BeamSolver.withParallelLanes` | 🔶 **结构已搬，默认关**（2026-10-24，`mechanics-reference` §28）：lane 隔离 + 按父节点原序提交两条不变式都照抄，`ParallelExpansionTest` 逐位验证「并行结果 = 串行结果」。**实测不划算**：修完指纹后同一场景 1 lane 524–562ms，2/4/8 lane 649–680 / 614–635 / 571–578ms，全都更慢 —— 我们的层太小（每层 45–135 个父节点、每个约 30µs），派发开销盖过收益；蓝本每层要 fork 整套模拟器，所以它 8 lane 划算。默认 1，`-Dsts1solver.searchLanes=N` 可开 |
| `CyclePlanning.cs` / `CycleReplay` / `CycleRegionRetention`（循环检测与区域保留） | `TranspositionTable`（按指纹去重） | 🔶 部分：只有去重，没有循环区域机制 |
| `NoveltySearch.cs` / `Bfws*`（新颖度搜索） | `search/NoveltySearch` + `BeamSolver.withNovelty` | 🔶 **核心已搬**（2026-10-30，`docs/plans/2026-10-30-bfws-novelty.md`）：`BfwsPackedNovelty`（1/2/熟悉三档 + 分区历史 + 惰性记配对）、`BfwsBoundedOpen`（2048 容量、丢最差）、`BfwsEscapeBudget`、`RunNoveltyOpen` 主循环（`MaxActions=128` / `MaxTurns=32` / `NoveltyPortfolioBudget` = `min(5s, 总/2)` 与 `min(2500, 节点/4)`）全部逐行翻译；事实集只取 `SimState` 真有的维度（能量/格挡/血/手牌数/四牌堆/抽牌堆顶三张/双方 Power/敌人血量桶），蓝本的 20 个战略标量里我们没有对应字段的那些**不硬凑**。**未搬**：`AdaptiveNoveltyRefinement`、`NoveltyPortfolioBudget.Remaining` 的二次分配、以及 `NoveltyPortfolioTelemetry` 的完整遥测 |
| `Retention.cs` / `RetentionJobs.cs` / `BeamRetentionPolicy.*`（保留策略族：路由 / 有序变异 / 泳道） | `prune`（按 rank 排序取前 N） | 🔶 部分：排序三键已补齐（`CompareBeamRankOrder` 第三键 `offensiveProgress`，2026-10-23）+ **边界多样化** `DiversifyOrdinaryBeamBoundary` 已搬（beam 尾部按药水桶 × 推进组轮转占位）；Pareto cohort 前排保留 / 药水配额 / 承诺代表 / 有序变异 / 泳道路由未做。`OrderOrdinaryBeamTacticalCohorts` 的战术重排刻意不搬（依赖 ZeroCostPlayableCount / ReachableHandValue / HandCount 三个快照量，未建模） |
| `CrossTurnPlanning.cs` / `RoundTransition.cs`（跨回合调度证据 / 回合推进） | `endTurnOf` + `CombatFlow.endTurn` | 🔶 部分：回合推进有，调度证据没有 |
| `BeamPortfolioSelector.cs` / `BeamWidthPortfolio*.cs`（多宽度组合） | `search/BeamWidthPortfolio` + `BeamSolver.withPortfolio` | 🔶 **组合器与准入门已搬**（2026-10-30）：`ProductionMembers` 五成员 `[基线, ×2/3, ×3/2, 基线+次段, 基线+基础分]`、`ScaledWidth`（四舍五入远离零）、`EstimateMemberCost`（宽度比 × 3/2 保险系数）、`BeamWidthPortfolioGate.RejectRefinement` 五道门（零伤 / 未搜干净 / 基线吃掉 >1/4 时间 / 节点余量 / 时间余量）逐行翻译；`SecondRankBand`（普通席位取第 W+1…2W 位）与 `BaseScoreOnly`（中途只用 score 排序）在 `prune` 里生效。节点/时间**共享**、同分保留基线、撞帽非终局成员不参与比较（`SkippedNodeLimitNotTerminal`）。**未搬**：`BeamPortfolioSelector` 是**训练出来的决策树模型**（33 维特征 + JSON 权重 + `MinimumBattles`/`ImprovementRate` 判据），没有他们的训练数据无法复现，属不可搬项；`AggressivePowerCommitment` / `OffensiveRefinement` / `BoundedRefinement` 三个成员依赖我们没有的 profile 开关（且蓝本默认也关）|
| `ContextualRankingModel.cs`（上下文排序模型） | — | ❌ 未做 |
| `CardChoiceSupport*` / `CardChoiceMirrors` / `CardChoiceResolution`（牌内选择估值） | — | ❌ 未做 |
| 药水动作（`BlockPotionInsertion` / `PotionChoiceContinuation` / `SolverPotionPolicy`） | `expandPotion` 的三种形态 + `gatePotionSpending` | 🔶 **部分**：动作空间（目标 / 单选 / 多选）与成本闸有了；`BlockPotionInsertion`（把药水插进已解路线）与 `PotionChoiceContinuation`（药水开出的选牌续搜）未做，`SolverPotionPolicy` 档位未做 |
| （无对应文件）怪物 AI 镜像 | `sim/MonsterMoves` + `SimMonster.moveHistory` | 🔶 **逐只推进**（2026-10-24 起）：原来只建模意图伤害（`IntentLearning`），现在按字节码翻译 `getMove`+`takeTurn`，把**格挡与减益**也带进影子。已镜像：`SphericGuardian`（`mechanics-reference` §33）。其余怪继续走老路，行为不变 |

## 3. 执行层（对应 Runtime/）

| 蓝本 | 我们的 | 状态 |
|---|---|---|
| `SearchTakeoverKind.ApplyCurrentTurn`（搜到本回合候选就停，下回合重搜） | `AutoPilot` 每回合重规划 | ✅ 同构于蓝本 **FullAuto 模式**（`SolverController.cs:1485 SetFullAuto` → 每回合 `AutoTurnStart` 自动重搜 → `StartDeployment`（:2799）的 `DeployCurrentTurn` **只部署 `action.Turn == StartTurnNumber` 的动作**）；漂移重搜对应我们的指纹校验丢弃（`DeploymentDrift` / `PlanExhausted`）。⚠️ 蓝本后续回合若精确 `ContinuationStamp` 命中会**复用** continuation，未命中才重搜（`SolverController.cs:1078-1157`），不能笼统写成“蓝本每回合必定从头重搜”；本项目当前仍是每回合重算。手动面板的早停不搬（判定见 §2 `ConsiderCurrentTurnCandidate` 行） |
| `SearchTakeoverKind.AdoptRoute` + 独立 `RouteFrozen` | — | ❌ 均未做。注意两者不是同一状态：`AdoptCurrentRoute` 物化当前渲染候选；`RouteFrozen` 另管是否禁止后续自动搜索（`SolverController.cs:128-158,1701-1744`）。不得把 AdoptRoute 简写成“冻结路线” |
| `SolverProgress`（面板进度：世界线 / 回合层 / play_depth） | `SolverPanel` | 🔶 部分：有节点/深度/路线，没有 play_depth 与分阶段进度 |
| `SolverPotionStrategyPanel` / `SolverRelicStrategyPanel` / `SolverGrowthStrategyPanel` | 只读策略现状区（`ui/StrategyStatusSnapshot` + `SolverPanel` 折叠区） | ❌ **控制器未复刻；本轮只有只读现状适配**（2026-10-31）：药水按槽位列形态 + 战略 HP 门槛（被动药不显示默认 9）、遗物 `id=counter` 原样 + 未镜像告警、成长 `hp/max` + 正向关键层数。无 setter、无假控件、不改变搜索。蓝本那三个面板会**编辑并持久化**策略、使旧结果失效并改变后续搜索 —— 这部分仍全未做（见设计 §10 的独立立项清单） |
| `CardRewardScreen.cardSelectUpdate()`（发现/三选一） | `GridSelector.markReward` 用「虚拟光标 + `hb.clicked`」点中目标卡 | 🔧 **原版行为补正（2026-11-01，`769f919`）**。原版只认「鼠标悬停的那张卡的 `hb.clicked`」（字节码 58-70 / 73-88 / 142-153），只置标记不挪光标 = 界面永远不关（真机 22:13 卡住 9.1 秒）。现在按本类 `prepareConfirm` 同一套做法挪光标，且界面没关就继续点同一张 |
| `CombatBeamSolver.Phases.cs:2222` `finalPool = [..completed, ..frontier]` | 最终选路线在「所有终局 + 所有前沿」里排序（`BeamSolver.lastFinalPool` + `runPortfolio` 全局池） | ✅ **已复刻（2026-11-01，commit `9536d7d`）**。以前每趟只交出 1 个节点，别处已经打出来的好局面白扔。实测：123 个真机快照的首手变化 **0 次**（因为 `better()` 优先「END_TURN 之前血量最高」的边界节点），属于**补对了口径但样本不敏感**，不要据此宣称变强 |
| `StateEvaluation` `LatentSetupValue` 启用条件 `_isActEndingBoss \|\| _initialEnemyCount > 1` | `Score.initialBoss` | ✅ **已补齐 Boss 那一半（2026-11-01，`9536d7d`）**。以前只有 `enemyCount > 1`，单只 Boss 房里「手上还没打出去的能力牌」在 beam 排序里等于 0 分。A/B：123 快照首手变化 0 次（快照没有 boss 标志，探针按楼层 16/33/50 推断） |
| `BeamRetentionPolicy.DiversifyOrdinaryBeamBoundary(required: …)` | 首手保留（`required` 分支） | ❌ **仍未做**。2026-11-01 试做过「每个首手至少留一条后代」，实测首手变化 3/123、胜负 0 变化、节点 +3%，按「不加无证据复杂度」回退，未入库 |
| （蓝本无对应；`SolverRouteRow.cs:28` 鼠标忽略，源码及历史无 `ApplyRoute`） | 点击路线行 = AUTO 至目标回合末（`verify/BoundedAutoTarget` + `verify/RoutePreview`） | 📝 **项目自定义扩展，已实施（2026-10-31）**：只设**绝对停止回合**（`AutoPilot.runUntil`），每回合按真局面重算（`RoutePreview` 保留完整 steps，行号是 `originTurn` 起的绝对 T4/T5，不冻结路线）。Tn 的 END_TURN 与敌方阶段完成后，在 Tn+1 **任何搜索/出牌之前**停。停止目标只属于本次运行意图，不入 `SolverConfig`、不跨战斗继承。**不是** `AdoptRoute` |

## 4. 下一步该按什么顺序做

> ⚠️ 2026-10-23 更正：这一版之前把「药水 / 死亡豁免 / 局外收益 / 被偷资源 / `_isActEndingBoss`」
> 一律记成「STS1 无对应机制」。**这是错的** —— 那是「我们没建模」，不是「游戏没有」。
> 四项在 STS1 全都有对应物（药水、蜥蜴尾巴/瓶中仙子、Feed、掠夺者）。
> 教训：⛔ 只能用在「蓝本机制依赖的游戏机制在 STS1 确实不存在」上（如附魔重放、幕末 80% 回血），
> 其余一律记 ❌ 未做。

按蓝本 `CombatSearchCoordinator.SolveCore` 的流水线顺序：

1. ~~`ConsiderCurrentTurnCandidate`~~ → **已搬**（选择顺序）。
2. ~~`ApplyCurrentTurn` 的早停（`Phases.cs:1445 / 1554 / 2154`）~~ → **不搬**（2026-10-23 考证修正，
   依据记入 `mechanics-reference` §22.4）。
   早停 = **手动面板模式**的「立即交付」：用户点「执行本回合」按钮（`PlayerTurnSetupPatches.cs:374`）后，
   后台搜索在三个检查点看到接管请求（`_interaction.CurrentTakeoverRequest`）就提前收工，
   交付 `currentBestNode ?? currentTurnCandidateNode`（整场胜利优先，其次本回合候选）。
   **FullAuto 不走这条路径**：`SolverController.cs:1485 SetFullAuto` 起全自动后每回合
   `AutoTurnStart` 自动准备后续部署（:1558 `PrepareAutomaticSearchForTurn`；:1078-1157 先校验并复用精确 continuation，未命中才重搜），等得起；
   我们 AutoPilot 对应 FullAuto —— 同构性逐行核实：`StartDeployment`（:2799）的
   `DeployCurrentTurn` 只部署 `action.Turn == StartTurnNumber` 的动作、
   `DeploymentDrift` / `PlanExhausted` 重搜对应我们的指纹校验丢弃重算。
3. **`Retention` / `BeamRetentionPolicy` 保留策略族** —— **批 1+2 已搬**（排序第三键
   `offensiveProgress` + 边界多样化，2026-10-23，`mechanics-reference` §26）。
   剩余批次与依赖：**批 3 Pareto cohort**（支配判据依赖 40+ 快照量，其中
   LongTermResourceValue / RelicCounters.SatisfiedMask / ReplayPotentialValue /
   Osty/Stars/Sandpit 等未建模 —— 自选子集做支配 = 自创语义，先补量再搬）；
   **批 4 药水配额**（依赖 `PotionStrategicCost` 药水战略成本，未建模）；
   **批 5 承诺代表**（依赖 PowerCardValuation 模型族）；**批 6 OrderedMutation**
   （依赖 NoveltySearch，对照表第 5 项）。
4. **~~`Expansion.Opening` + `_earlyTurnScoutDepth`~~ → 拆开后只有一半可搬，已搬（2026-10-23）**：
   - ✅ **0 费开局补搜**（`RunZeroCostOpeningRescue`，`PostSearch.cs:352-392`）已整条搬，
     连带三个前置件：固定前缀回放（`ApplyFixedPrefix`，`Phases.cs:2296-2400`）、
     药水上限闸（`maximumPotionUses`）、免费前缀枚举（`BuildOpeningNoCostPrefixes`）。
     细节见 `mechanics-reference` §27。
   - ❌ `_earlyTurnScoutDepth` 单独搬无意义（只是循环上界），它的消费者
     `RunEarlyTurnExploration` 卡在 `SelectEarlyTurnFrontier` 的 7 元组分桶 ——
     其中 5 项快照量没建模（`EarlyTurnFrontier.cs:55-62`）。**先补量，再搬前沿。**
   - ⛔ `OpeningActionRegistry` 两条规则本期无对应（`WHITE_NOISE` 是故障机器人的卡、
     `DUPLICATOR` 药水要配 `PowerCardValuation` 模型族）。
   - 🔴 由此得出一个顺序结论：**第一梯队的 4/5/6 三项都以「补齐快照量」为前提**
     （批 3 Pareto / 批 5 承诺代表 / 前沿分桶都在等 `PersistentBuffValue` 这一族）。
     下一步要么补量，要么转第二梯队（机制镜像）。
5. `NoveltySearch` / `BeamPortfolioSelector`（多轮搜索）。
6. **`ExpansionExecutor` / `ParallelExpansion` 并行展开** —— **结构已搬，默认关**
   （2026-10-24，`mechanics-reference` §28）。
   - 先做了 §4 之外但挡路的一件事：**指纹提速**（`mechanics-reference` §29）——
     `SimState.fingerprint()` 从「拼 JSON + FNV」改成直接哈希，2.24µs → 0.24µs，
     整趟搜索 **2.8×**（1507ms → 约 540ms）。
   - 修完再复测并行：2/4/8 lane 全都比串行慢（层太小，派发开销盖过收益）→ 默认 1，
     `-Dsts1solver.searchLanes=N` 可开。**「先修指纹并行就能用」的假设已被否掉**。

新登记的三块缺口（都是 ❌，不是 ⛔），按「建模成本 / 对现有估值的影响」排序：

7. ~~**死亡豁免（蜥蜴尾巴）**~~ → **已做**（`mirror/DeathSaveRelic`）。
   只剩**瓶中仙子**（药水）那一半，等药水建模后接 —— 蓝本 `TryRevive` 里 fairy 优先于 lizard tail，
   顺序要照抄。
8. ~~**战后阈值回血**（`PostCombatRelicHeal`）~~ → **主路径已做**（`mirror/PostCombatRelicHeal`）：
   燃烧之血/黑血的无条件回血进 `StrategicHpDeficit`，带骨肉的阈值回血照蓝本不进排序。
   剩「阈值回血 → `HealingHpCredit`」那条通道未做（依赖 `RelicCounterEvaluation`）。
9. ~~**药水**~~ → **镜像层第一批已做**（4 张 + 槽位 + `USE_POTION` 动作 + 瓶中仙子接进死亡豁免）。
   剩下的是估值策略（依赖侧栏额度，M4）和其余 30+ 张药水（生成类/选择类形态各不同）。
10. **局外收益**（`GrowthRewards` 逐来源计数 + `GrowthHpCredit` 轴）。
11. **被偷资源**（`Looter` 偷金币逃走 → `UnrecoveredLoot` / `TheftEncounterStrategy`）。

> ⚠️ 施工纪律：一次只搬一项，搬完在本文档把状态改成 ✅，并在 `mechanics-reference.md`
> 记一条「照抄了哪几行、改了哪几处」。**不自己加机制、不自己出量化结论。**
