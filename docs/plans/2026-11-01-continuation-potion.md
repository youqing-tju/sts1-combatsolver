# P7 Continuation + D5 Potion Strategy Implementation Plan

> **For implementer:** Use TDD throughout. Write failing test first. Watch it fail. Then implement.
> 本计划是唯一施工依据；旧 `next-round-prompt.md` 中 P1/P2 前提已废止。
> 已批准设计：`docs/plans/2026-11-01-continuation-potion-design.md`，先完整阅读。

**Goal:** 精确校验并续用最终最优路线的跨回合世界线，同时让完整药水策略约束所有搜索通道、最终答案和真机执行。

**Architecture:** P7 从后处理后的最终 `SearchNode` 链提取稳定边界，以同一规范化状态文本校验预测与真机；命中复用、miss 归因重算。D5 以不可变策略快照和 `policyRevision` 贯穿请求、并行 worker、baseline、后处理、补搜、结果采纳与 UI。

**Tech Stack:** Java 8、JUnit/现有 Gradle 测试、ModTheSpire、STS1 纯数据 `SimState` 镜像。

---

## 全局施工规则

1. 先跑基线：`git status --short --branch`、`git log -5 --oneline`、`./gradlew test --offline`。保留他人改动，禁止 `reset --hard`。
2. 每个 Task 按 RED → 最小实现 → GREEN → 相关回归 → 独立 commit；不得先写实现再补一个永远通过的测试。
3. 真机对象只在主线程；后台结果和 continuation 必须是纯数据。渲染不 capture。
4. 不改有界 AUTO 的“打完 Tn 再停”检查位置；不引入冻结路线；不砍任何搜索能力。
5. 遇到失败直接定位、修复、复测，不等逐项批准。只有发现需要改变已批准产品语义时才问。
6. 每个 commit 后记录：测试数、关键证据、未验证项、回退 commit。不 push。

---

## Task 1: P7 精确 ContinuationStamp 与字段差异

**Files:**
- Create: `src/main/java/io/github/youqing/sts1solver/search/ContinuationStamp.java`
- Create: `src/test/java/io/github/youqing/sts1solver/search/ContinuationStampTest.java`
- Reference: `src/main/java/io/github/youqing/sts1solver/sim/SimState.java`
- Reference: `src/main/java/io/github/youqing/sts1solver/sim/SimCard.java`
- Reference: `src/main/java/io/github/youqing/sts1solver/sim/SimMonster.java`
- Blueprint: `reference/sts2-combatsolver/src/Runtime/ContinuationStamp.cs`

**API contract:**
```java
public final class ContinuationStamp {
    public static ContinuationStamp from(SimState state);
    public String canonicalText();
    public String describeFirstDifference(ContinuationStamp actual);
    public List<String> describeDifferences(ContinuationStamp actual, int limit);
    @Override public boolean equals(Object other);
    @Override public int hashCode();
}
```

`from` 是预测/实机唯一入口。内部用稳定字段路径和值编码，不能序列化 Java 对象身份或 `toString()`。相等与差异读取同一字段表，避免两套口径。

**Step 1: 写失败测试**

至少覆盖以下独立测试，不复用现有 `fingerprint()` 代替断言：

```java
@Test public void identicalStatesHaveIdenticalCanonicalText();
@Test public void handIsAnUnorderedMultisetButDuplicatesSurvive();
@Test public void drawPileOrderMatters();
@Test public void cardFutureFieldsMatterEvenWhenEqualsWouldMatch();
@Test public void powerAmountAndStepsMatter();
@Test public void relicCounterMatters();
@Test public void monsterHistoryAndPendingMovePayloadMatter();
@Test public void everyCombatRngCounterAndInternalStateMatters();
@Test public void potionSlotIdentityAndDiscardMatter();
@Test public void firstDifferenceNamesExactFieldPath();
```

使用现有 `SimState` 测试 builder/fixture；若没有 builder，先在测试内建立最小纯数据状态，不接触真机。`cardFutureFields…` 必须构造 `id/upgraded/cost` 相同但 `baseDamage`、`type` 或 `costForTurn` 不同的两张牌，证明当前 `SimCard.equals()` 不足以放行。

差异格式至少包含字段路径与双方值，例如：
```text
monsters[0].pendingMove.damage: expected=12 actual=9
drawPile[3].upgraded: expected=false actual=true
aiRng.counter: expected=7 actual=8
```

**Step 2: RED**
```bash
./gradlew test --offline --tests "io.github.youqing.sts1solver.search.ContinuationStampTest"
```
Expected: FAIL，类型/方法尚不存在。

**Step 3: 最小实现**

按设计 §3.4 编码全部字段：回合/资源、四牌堆、药水、Power、遗物、怪物、五路 RNG。手牌完整 key 排序，其他有序堆保序；`pendingMove` 用规范化语义载荷。加入版本头与可靠分隔/转义，测试分隔符不会制造字段歧义。

**Step 4: GREEN**
```bash
./gradlew test --offline --tests "io.github.youqing.sts1solver.search.ContinuationStampTest"
./gradlew test --offline --tests "io.github.youqing.sts1solver.sim.*"
```
Expected: PASS。

**Step 5: Commit**
```bash
git add src/main/java/io/github/youqing/sts1solver/search/ContinuationStamp.java src/test/java/io/github/youqing/sts1solver/search/ContinuationStampTest.java
git commit -m "feat(search): add exact continuation state stamps"
```

---

## Task 2: P7 稳定边界模型与纯数据续接判定

**Files:**
- Create: `src/main/java/io/github/youqing/sts1solver/search/CachedContinuation.java`
- Create: `src/main/java/io/github/youqing/sts1solver/search/ContinuationPlan.java`
- Create: `src/test/java/io/github/youqing/sts1solver/search/ContinuationPlanTest.java`
- Blueprint: `reference/sts2-combatsolver/src/Search/CombatPlan.cs:258-303`
- Blueprint: `reference/sts2-combatsolver/src/Search/CombatBeamSolver.Terminal.cs:159-274`

**API contract:**
```java
public final class CachedContinuation {
    public final ContinuationStamp expectedState;
    public final int startTurnNumber;
    public final int forecastOffset;
}

public final class ContinuationPlan {
    public static ContinuationPlan fromFinalRoute(List<SearchNode> finalRoute);
    public Lookup lookup(int liveTurn, ContinuationStamp liveStamp);
    public ContinuationPlan onlyAfter(int reusedTurn);
}

public final class Lookup { // 可为嵌套不可变类型
    public enum Status { HIT, CONTINUATION_MISSING, STATE_MISMATCH }
    // status、matched、firstDifference、forecastOffset 只读访问器
}
```

**Step 1: 写失败测试**

```java
@Test public void onlyStableEndTurnBoundaryWithFutureActionsIsCached();
@Test public void terminalDeathAndVictoryBoundariesAreNotCached();
@Test public void exactStampHitsWithoutChangingForecastOffset();
@Test public void missingTurnIsContinuationMissing();
@Test public void sameTurnDifferentStampIsStateMismatchWithDifference();
@Test public void reuseDropsCurrentAndOlderContinuations();
```

构造带 `state/action/parent` 的最小 `SearchNode` 链。边界资格逐项构造：普通出牌节点不算、END_TURN 跨回合算、玩家死/敌全灭/无后续不算。`forecastOffset` 必须能无歧义裁出该回合应执行的原始步骤。

**Step 2: RED**
```bash
./gradlew test --offline --tests "io.github.youqing.sts1solver.search.ContinuationPlanTest"
```
Expected: FAIL。

**Step 3: 最小实现**

只从最终节点链读取，不在 `RouteStep` 里塞状态。`fromFinalRoute` 不重新搜索、不读真机。对缺预计边界等内部不一致显式抛出，不静默生成半个缓存。

**Step 4: GREEN**
```bash
./gradlew test --offline --tests "io.github.youqing.sts1solver.search.ContinuationPlanTest"
./gradlew test --offline --tests "io.github.youqing.sts1solver.search.ContinuationStampTest"
```
Expected: PASS。

**Step 5: Commit**
```bash
git add src/main/java/io/github/youqing/sts1solver/search/CachedContinuation.java src/main/java/io/github/youqing/sts1solver/search/ContinuationPlan.java src/test/java/io/github/youqing/sts1solver/search/ContinuationPlanTest.java
git commit -m "feat(search): model stable turn continuations"
```

---

## Task 3: P7 从最终 selected 产出并传递 Continuation

**Files:**
- Modify: `src/main/java/io/github/youqing/sts1solver/search/BeamSolver.java`
- Modify: `src/main/java/io/github/youqing/sts1solver/search/AsyncSearch.java`
- Test: `src/test/java/io/github/youqing/sts1solver/search/BeamSolverContinuationTest.java`
- Reference: `src/main/java/io/github/youqing/sts1solver/verify/RoutePreview.java`

**Step 1: 写失败测试**

```java
@Test public void resultCarriesContinuationsFromFinalSelectedRoute();
@Test public void potionGateReplacementChangesContinuationSource();
@Test public void zeroCostRescueReplacementChangesContinuationSource();
@Test public void noveltyCannotLeaveContinuationFromRejectedCandidate();
@Test public void routeReplayAndNodeChainProduceEquivalentBoundaryStamps();
```

至少一个多回合 fixture 必须存在合法 continuation。分别让药水闸和 0 费补搜替换候选，断言 Result 的 steps 与 continuation 来自同一条最终路线；不得只检查字段非空。

**Step 2: RED**
```bash
./gradlew test --offline --tests "io.github.youqing.sts1solver.search.BeamSolverContinuationTest"
```
Expected: FAIL，`Result` 无 continuation 且当前未提取。

**Step 3: 最小实现**

在 `BeamSolver.solve` 当前顺序之后：
```java
selected = gatePotionSpending(root, selected, deadline);
selected = zeroCostOpeningRescue(root, selected, deadline);
lastRoute = routeOf(selected);
lastSteps = stepsOf(selected);
lastContinuations = ContinuationPlan.fromFinalRoute(routeNodesOf(selected));
```

将同一 `lastContinuations` 写入后台 `AsyncSearch.Result`。当前 Result 只有完整 `steps`，不要虚构另一个 `previewSteps` 字段；AutoPilot 采纳时用完整 steps 建立并持有 `RoutePreview`，续跑时物理裁剪的只有执行队列，不把过去回合从 preview 中删除。`routeNodesOf` 只回溯最终 `parent` 链，不重跑 solver。

**Step 4: GREEN**
```bash
./gradlew test --offline --tests "io.github.youqing.sts1solver.search.BeamSolverContinuationTest"
./gradlew test --offline --tests "io.github.youqing.sts1solver.search.BeamSolverTest"
./gradlew test --offline --tests "io.github.youqing.sts1solver.search.PotionGateBeatsNoveltyTest"
```
Expected: PASS。

**Step 5: Commit**
```bash
git add src/main/java/io/github/youqing/sts1solver/search/BeamSolver.java src/main/java/io/github/youqing/sts1solver/search/AsyncSearch.java src/test/java/io/github/youqing/sts1solver/search/BeamSolverContinuationTest.java
git commit -m "feat(search): return continuations from final selected route"
```

---

## Task 4: P7 AutoPilot 命中续跑、miss 归因与生命周期

**Files:**
- Modify: `src/main/java/io/github/youqing/sts1solver/verify/AutoPilot.java`
- Modify: `src/main/java/io/github/youqing/sts1solver/verify/RoutePreview.java`（更新“每回合重搜”的过时注释，不改绝对回合模型）
- Create: `src/main/java/io/github/youqing/sts1solver/verify/ContinuationDecision.java`（若需要把纯决策从静态真机类抽出）
- Create: `src/test/java/io/github/youqing/sts1solver/verify/AutoPilotContinuationTest.java`
- Test: `src/test/java/io/github/youqing/sts1solver/verify/BoundedAutoTargetTest.java`
- Blueprint: `reference/sts2-combatsolver/src/Runtime/SolverController.cs:1078-1140`

**Step 1: 写失败测试**

```java
@Test public void exactNewTurnHitReusesWithoutRequestingSearch();
@Test public void hitKeepsAbsolutePreviewAndAdvancesNextAction();
@Test public void missingContinuationLogsReasonAndRequestsSearch();
@Test public void stateMismatchLogsFirstDifferenceAndRequestsSearch();
@Test public void manualDivergenceIsNotReportedAsStateMismatch();
@Test public void deploymentDriftInvalidatesFutureContinuation();
@Test public void inTurnPotionOrChoiceInvalidatesOnlyCurrentWorldline();
@Test public void combatResetClearsContinuation();
@Test public void boundedTargetStillStopsAfterTargetEnemyPhaseOnReuse();
@Test public void reuseMissDoesNotClearBoundedTarget();
```

用 fake solver/纯数据 decision 记录“是否请求搜索”，不要在单测中实例化 AbstractDungeon。命中结果的 fingerprint 应取主线程新捕获的 live state，不能继续携带上一回合 fingerprint。

**Step 2: RED**
```bash
./gradlew test --offline --tests "io.github.youqing.sts1solver.verify.AutoPilotContinuationTest"
```
Expected: FAIL，当前回合翻页直接 `clearPlan()`。

**Step 3: 最小实现**

主线程稳定新回合，在 `waitingForNextTurn()` 后且任何搜索/行动前：

1. 保留现有 bounded 停止检查。
2. 若可续跑，capture 一次并生成 live stamp。
3. `HIT`：按 `forecastOffset` 裁当前及未来执行 steps，保留完整 preview，保留更晚 continuation，打 `SEARCH_REUSED … validation=exact_state_text`，不调用 solver。
4. miss：区分 `ManualDivergence/ContinuationMissing/StateMismatch`，打 `SEARCH_REUSE_MISS reason=… first_difference=…` 后重搜。
5. 现有 `mismatchSequence` 继续对应 `DeploymentDrift`，不能并入正常新回合。

reset、手工输入、本回合喝药/三选一和部署漂移按设计清 continuation；有界目标只在原有手动取消、异常、reset 条件清理。

**Step 4: GREEN**
```bash
./gradlew test --offline --tests "io.github.youqing.sts1solver.verify.AutoPilotContinuationTest"
./gradlew test --offline --tests "io.github.youqing.sts1solver.verify.BoundedAutoTargetTest"
./gradlew test --offline --tests "io.github.youqing.sts1solver.verify.RoutePreviewTest"
./gradlew test --offline --tests "io.github.youqing.sts1solver.verify.ShadowTrackerQueueTest"
```
Expected: PASS。

**Step 5: Commit**
```bash
git add src/main/java/io/github/youqing/sts1solver/verify src/test/java/io/github/youqing/sts1solver/verify
git commit -m "feat(exec): reuse exact continuations across turns"
```

---

## Task 5: D5 不可变药水策略、预设与持久化

**Files:**
- Create: `src/main/java/io/github/youqing/sts1solver/search/PotionStrategySnapshot.java`
- Create: `src/main/java/io/github/youqing/sts1solver/search/PotionUsePolicy.java`（若拆出最终资格判定）
- Modify: `src/main/java/io/github/youqing/sts1solver/config/SolverConfig.java`
- Create: `src/test/java/io/github/youqing/sts1solver/search/PotionStrategySnapshotTest.java`
- Create: `src/test/java/io/github/youqing/sts1solver/search/PotionUsePolicyTest.java`
- Create: `src/test/java/io/github/youqing/sts1solver/config/SolverConfigPotionStrategyTest.java`
- Blueprint: `reference/sts2-combatsolver/src/Search/PotionStrategySnapshot.cs`
- Blueprint: `reference/sts2-combatsolver/src/Search/PotionUsePolicy.cs`
- Blueprint: `reference/sts2-combatsolver/src/Runtime/SolverSettings.cs:77-104,229-245`

**API contract:**
```java
enum SolverPotionPolicy { DISABLED, SMART, REQUIRE_AT_LEAST_ONE }
enum SolverPotionDirective { SMART, DISABLED, FORCE }
enum SolverPotionPreset { ALL_SMART, ALL_PROTECTED, ALL_FORCED, ONLY_FORCED }

public final class PotionStrategySnapshot {
    public SolverPotionPolicy globalPolicy();
    public SolverPotionDirective directiveFor(int slot, String potionId);
    public boolean allowsExplicitUse(int slot, String potionId);
    public PotionStrategySnapshot withDirective(int slot, String id, SolverPotionDirective value);
    public PotionStrategySnapshot withPreset(SolverPotionPreset preset, List<SlotStatus> potions);
    public ForcedUseResult evaluateFinalRoute(List<RouteStep> steps);

    // search 层自有纯数据输入，避免依赖 ui.StrategyStatusSnapshot。
    public static final class SlotStatus {
        public SlotStatus(int slot, String potionId, boolean searchable) { /* 只读字段 */ }
    }
}
```

类型可放独立文件以符合 Java 8；命名与项目现有枚举风格统一，但语义不得缩写。

**Step 1: 写失败测试**

```java
@Test public void globalDisabledBeatsForceOverride();
@Test public void globalRequireAtLeastOneDoesNotForceEverySlot();
@Test public void smartDirectiveRemovesPersistentOverride();
@Test public void sameIdAtDifferentSlotHasIndependentDirective();
@Test public void changedPotionIdDoesNotInheritOldSlotOverride();
@Test public void onlyForcedKeepsForcedAndDisablesOtherSearchablePotions();
@Test public void presetsSkipAbsentOrNonSearchablePotions();
@Test public void finalRouteMustContainEveryForcedSlotAndPotionId();
@Test public void requireAtLeastOneRejectsZeroPotionFinalRoute();
@Test public void legacyConfigDefaultsToSmartWithoutOverrides();
```

持久化 key 必须含 slot 与 potionId；切回 Smart 删除旧覆盖。配置文件测试隔离临时目录，不能写用户真实 `config.properties`。

**Step 2: RED**
```bash
./gradlew test --offline --tests "io.github.youqing.sts1solver.search.PotionStrategySnapshotTest"
./gradlew test --offline --tests "io.github.youqing.sts1solver.config.SolverConfigPotionStrategyTest"
```
Expected: FAIL。

**Step 3: 最小实现**

实现全局资格、每槽覆盖、四预设和最终 forced-use 检查。`Force` 必须验证 `(slot,potionId)`，不能只数药水瓶数。`PotionUsePolicy` 保留现有 Smart 成本语义并补 Disabled/RequireAtLeastOne；不改变蓝本 HP 成本公式。

**Step 4: GREEN**
```bash
./gradlew test --offline --tests "io.github.youqing.sts1solver.search.PotionStrategySnapshotTest"
./gradlew test --offline --tests "io.github.youqing.sts1solver.search.PotionUsePolicyTest"
./gradlew test --offline --tests "io.github.youqing.sts1solver.config.SolverConfigPotionStrategyTest"
./gradlew test --offline --tests "io.github.youqing.sts1solver.search.PotionCostGateTest"
```
Expected: PASS。

**Step 5: Commit**
```bash
git add src/main/java/io/github/youqing/sts1solver/search/Potion*.java src/main/java/io/github/youqing/sts1solver/config/SolverConfig.java src/test/java/io/github/youqing/sts1solver/search/Potion*Test.java src/test/java/io/github/youqing/sts1solver/config/SolverConfigPotionStrategyTest.java
git commit -m "feat(search): add persistent potion strategy snapshots"
```

---

## Task 6: D5 策略快照贯穿所有搜索通道与最终答案

**Files:**
- Modify: `src/main/java/io/github/youqing/sts1solver/search/BeamSolver.java`
- Modify: `src/main/java/io/github/youqing/sts1solver/search/AsyncSearch.java`
- Modify: `src/main/java/io/github/youqing/sts1solver/search/PlanAction.java`
- Modify: `src/main/java/io/github/youqing/sts1solver/search/RouteStep.java`（仅当现有步骤无法绑定 slot+id）
- Create: `src/test/java/io/github/youqing/sts1solver/search/PotionPolicyPropagationTest.java`
- Create: `src/test/java/io/github/youqing/sts1solver/search/PotionFinalAnswerPolicyTest.java`
- Test: `src/test/java/io/github/youqing/sts1solver/search/PotionGateBeatsNoveltyTest.java`

**Step 1: 写失败测试**

```java
@Test public void disabledPolicyRejectsMainBeamPotionAnswer();
@Test public void noveltyCannotResurrectDisabledPotion();
@Test public void portfolioCannotResurrectDisabledPotion();
@Test public void rescueCannotResurrectDisabledPotion();
@Test public void noPotionBaselineStillCompetesWhenPotionsAreDisabled();
@Test public void baselineIsSkippedWhenStrategyAllowsSearchablePotions();
@Test public void forcedSlotMustAppearInFinalSelectedRoute();
@Test public void forcedSlotBindsSlotAndPotionIdNotJustCardLikeId();
@Test public void failedForcedAnswerNeverFallsBackToUnforcedRoute();
@Test public void requireAtLeastOneAppliesAfterGateAndRescue();
@Test public void continuationComesFromPolicyCompliantFinalRoute();
```

每个通道必须构造“无策略时会选含药路线”的可失败 fixture，不能用空路线或禁用整个搜索来让测试通过。`PlanAction.Potion`/`RouteStep` 的 potion 标识须保留 slot+id，确保两瓶同 ID 不混淆。

**Step 2: RED**
```bash
./gradlew test --offline --tests "io.github.youqing.sts1solver.search.PotionPolicyPropagationTest"
./gradlew test --offline --tests "io.github.youqing.sts1solver.search.PotionFinalAnswerPolicyTest"
```
Expected: FAIL，当前只有 Smart 成本闸。

**Step 3: 最小实现**

- 搜索请求保存不可变 snapshot；BeamSolver、expansionWorker、portfolio 子 solver、baseline 与 rescue 都使用同一份。
- 候选展开和最终资格共用 `allowsExplicitUse`/`evaluateFinalRoute`。
- 顺序保持：Beam/novelty 选定候选 → 药水后处理 → 0 费补搜 → 最终策略复核 → `lastRoute/lastSteps/lastContinuations`。
- 最终复核不通过时不得被任何通道重新捞回；无可行路线要显式暴露原因。
- 不修改预算、束宽或 horizon 来规避 fixture。

**Step 4: GREEN**
```bash
./gradlew test --offline --tests "io.github.youqing.sts1solver.search.PotionPolicyPropagationTest"
./gradlew test --offline --tests "io.github.youqing.sts1solver.search.PotionFinalAnswerPolicyTest"
./gradlew test --offline --tests "io.github.youqing.sts1solver.search.PotionGateBeatsNoveltyTest"
./gradlew test --offline --tests "io.github.youqing.sts1solver.mirror.PotionsTest"
./gradlew test --offline --tests "io.github.youqing.sts1solver.search.BeamSolverContinuationTest"
```
Expected: PASS。

**Step 5: Commit**
```bash
git add src/main/java/io/github/youqing/sts1solver/search src/test/java/io/github/youqing/sts1solver/search
git commit -m "feat(search): enforce potion strategy across final answer"
```

---

## Task 7: D5 policyRevision、执行槽位与真实 UI 控制

**Files:**
- Modify: `src/main/java/io/github/youqing/sts1solver/verify/AutoPilot.java`
- Modify: `src/main/java/io/github/youqing/sts1solver/search/AsyncSearch.java`
- Modify: `src/main/java/io/github/youqing/sts1solver/ui/SolverPanel.java`
- Modify: `src/main/java/io/github/youqing/sts1solver/ui/StrategyStatusSnapshot.java`
- Modify: `src/main/java/io/github/youqing/sts1solver/config/SolverConfig.java`
- Create: `src/test/java/io/github/youqing/sts1solver/verify/PotionPolicyRevisionTest.java`
- Create: `src/test/java/io/github/youqing/sts1solver/ui/SolverPanelPotionStrategyTest.java`
- Blueprint: `reference/sts2-combatsolver/src/UI/SolverPotionStrategyPanel.cs`
- Blueprint: `reference/sts2-combatsolver/src/UI/SolverOverlay.cs:1574-1610`

**Step 1: 写失败测试**

```java
@Test public void staleRevisionIsRejectedEvenWithSameFingerprint();
@Test public void directiveChangeClearsContinuationAndRequestsManualSearch();
@Test public void autoComputeOffSavesPolicyWithoutStartingSearch();
@Test public void policyChangeDuringDeploymentIsRejected();
@Test public void directiveCyclesSmartDisabledForceSmart();
@Test public void nonSearchablePotionHasNoEnabledControl();
@Test public void oldPotionStepCannotDrinkSameIdFromDifferentSlot();
@Test public void relicAndGrowthSectionsRemainReadOnly();
@Test public void changingPolicyPreventsOldContinuationReuse();
```

UI 测试使用缓存 snapshot/行命中，不在 render 中创建真机状态。执行中拒绝必须同时由控制器防护，不能只把文字变灰而保留可调用 setter。

**Step 2: RED**
```bash
./gradlew test --offline --tests "io.github.youqing.sts1solver.verify.PotionPolicyRevisionTest"
./gradlew test --offline --tests "io.github.youqing.sts1solver.ui.SolverPanelPotionStrategyTest"
```
Expected: FAIL。

**Step 3: 最小实现**

- AutoPilot 持有主线程 `policyRevision`；每次请求带 revision，Result 原样带回，采纳时与 fingerprint 一起校验。
- directive/preset 改变：保存配置、revision+1、清 continuation/待处理基线，按 autoCompute 决定 Manual 重搜或记录跳过。
- 部署/执行中直接拒绝修改；非执行中栏位循环 Smart→Disabled→Force→Smart，预设作用于当前可搜索药水。
- 面板只读缓存渲染；药水区改为真实控件，遗物/成长仍只读。同步更新“策略现状（只读）”等过时文案、revision 和自动布局。
- 执行 potion 前校验步骤 slot+id 与当前槽位一致；不一致按计划失效处理，不喝另一瓶。

**Step 4: GREEN**
```bash
./gradlew test --offline --tests "io.github.youqing.sts1solver.verify.PotionPolicyRevisionTest"
./gradlew test --offline --tests "io.github.youqing.sts1solver.ui.SolverPanelPotionStrategyTest"
./gradlew test --offline --tests "io.github.youqing.sts1solver.verify.AutoPilotContinuationTest"
./gradlew test --offline --tests "io.github.youqing.sts1solver.ui.*"
```
Expected: PASS。

**Step 5: Commit**
```bash
git add src/main/java/io/github/youqing/sts1solver/verify/AutoPilot.java src/main/java/io/github/youqing/sts1solver/search/AsyncSearch.java src/main/java/io/github/youqing/sts1solver/ui src/main/java/io/github/youqing/sts1solver/config/SolverConfig.java src/test/java/io/github/youqing/sts1solver/verify/PotionPolicyRevisionTest.java src/test/java/io/github/youqing/sts1solver/ui/SolverPanelPotionStrategyTest.java
git commit -m "feat(ui): wire potion directives into safe replanning"
```

---

## Task 8: 全量评审、真机验收与文档闭环

**Files:**
- Modify: `docs/handoff-prompt.md`
- Modify: `docs/research/blueprint-parity.md`
- Modify: `docs/plans/2026-11-01-continuation-potion.md`（只填实际实施结果）
- Modify: `记录.txt`（仅沉淀可复用新经验，不记流水账）

**Step 1: 自动化与双评审**

```bash
./gradlew test --offline
./gradlew jar --offline
git diff --check
```
Expected: 全绿、jar 成功、无 whitespace error。记录真实测试总数。

另开 fresh-eyes spec review 与 quality review：
- spec：逐项对照已批准设计，尤其最终 selected、精确 stamp、全搜索链、revision、有界停止。
- quality：线程、不可变性、重复序列化、UI capture、静默默认、测试是否真能失败。
发现项直接修并复测，不以“现有行为”解释偏离。

**Step 2: 真机 P7**

安装后使用至少三回合战斗：
1. 第二回合命中 `SEARCH_REUSED … validation=exact_state_text`，该回合无新搜索，实际动作接续原 preview。
2. 手工改一次路线，确认 `ManualDivergence`；制造镜像差异时确认 `StateMismatch` 与首个字段。
3. 点击 Tn，续跑情况下仍完整执行 Tn 敌方阶段并在 Tn+1 玩家行动前停。
4. reset/新战斗后无旧 continuation 日志或旧动作。

**Step 3: 真机 D5**

1. Disabled 不喝药；RequireAtLeastOne 的路线与实际行动至少一瓶；Smart 不回归现有成本闸。
2. 单槽 Disabled 禁止该槽、Force 最终真实使用该槽；OnlyForced 保留强制项并禁用其余可搜索药。
3. 修改策略后旧结果/旧 continuation 不落地；autoCompute off 只保存不搜索；执行中修改被拒绝。
4. 同 ID 两瓶药水按 slot 正确执行；三选一/液态记忆等现有回合内失效行为不回归。

未运行的项写“未验证”，不得用单测代替真机勾选。

**Step 4: 文档与最终 commit**

更新真实 commit、测试数、日志路径、真机逐项结果和未解决风险。P7/D5 只有相应自动化与真机证据齐全才标 ✅；否则标 🔶 并列出缺口。明确旧 P1/P2 不在本轮范围。

```bash
git add docs/handoff-prompt.md docs/research/blueprint-parity.md docs/plans/2026-11-01-continuation-potion.md 记录.txt
git commit -m "docs: record continuation and potion strategy verification"
```

**最终汇报格式：** `任务 | commit | 自动化结果 | 真机/回放结果 | 证据位置 | 状态`，随后列关键文件、未闭环问题、回退方式。不得只写“测试全绿”。
