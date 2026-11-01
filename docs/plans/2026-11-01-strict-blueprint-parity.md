# 严格蓝本复刻纠偏计划（2026-11-01）

> **最高硬规则：禁止自创。蓝本没有 = 不做；不得用“用户此前要求”“最小等价”“本地扩展”“UI 适配”豁免。**
> STS1/STS2 API 差异只允许改变翻译方式，不允许改变机制、默认值、约束强弱、状态顺序或失败语义。
> 本文件取代 `2026-11-01-continuation-potion-design.md` 中所有与蓝本不一致的设计；旧文件仅保留历史记录。

## 1. 当前事实与纠偏目标

当前 HEAD `a927e8c`、672 测试全绿，但全绿包含多条把自创行为钉成合同的测试，**不能视为 parity 证据**。

必须纠正：

| 当前行为 | 蓝本标准 | 证据 |
|---|---|---|
| 默认采纳并冻结，失配仍照打 | 默认不冻结；回合开始精确 Continuation，miss 重算 | `Runtime/SolverController.cs:133-158,1077-1140` |
| 把 `AdoptRoute` 与 `RouteFrozen` 合成“按序打到底” | 两者独立；冻结会关 FullAuto、暂停自动重算，失配路线只保留参考 | `SolverController.cs:128-158`；`UI/SolverOverlay.cs` 冻结按钮 tooltip |
| 单步不可用只剔一步 | DeploymentDrift 清 source 并从根重算 | `SolverController.cs` 药水/卡牌/回合漂移部署分支 |
| 无生还路线禁止出牌 | `Survives` 是排序键；只有 DeathTurn/LiveRisk 等明确停手机制 | `CombatBeamSolver.FinalPlanOrdering.cs:246-270`；`SolverController.cs:2783,3086-3110` |
| 从最终节点链直接生成 continuation | best 只用于选边界；必须回放最终路线，在边界 `CapturePredicted` | `CombatBeamSolver.Terminal.cs:337-390` |
| `ContinuationStamp.from` 单入口，手牌排序 | `CaptureLive` / `CapturePredicted` 分别归一化；四个牌堆均按原顺序 | `Runtime/ContinuationStamp.cs:68-182,337-349` |
| 全局 Disabled 压过 Force | 显式 Force 仍可展开；Disabled 只决定未登记槽默认值 | `Search/PotionStrategySnapshot.cs:66-87` |
| 全局 PotionPolicy 落盘 | 蓝本 `[JsonIgnore]`，每次新设置默认 Smart；只持久化每槽 directives | `Runtime/SolverSettings.cs:94-96` |
| 自造 `policyRevision` | 蓝本无该字段；修改策略走保存、清 continuation/baseline、按自动计算状态 Manual 重搜 | `SolverController.cs:1899-1942,1964-2008` |
| Force 失败时自造“钉住补搜→null” | 在统一候选池做资格过滤；无合格候选抛 `PotionPolicyUnsatisfiedException` | `CombatBeamSolver.FinalPlanOrdering.cs:246-280` |
| 点 Tn 有界 AUTO、冻结诊断增强、自适应 16 行等本地行为 | 蓝本没有对应机制就不能冒充 parity；严格模式下撤销，不另造例外 | 蓝本 `ApplyCurrentTurn/AdoptRoute/RouteFrozen` 各自语义不同 |

## 2. 实施原则

1. **先撤自创，再修影子**。不得继续用冻结、剔步或删 stamp 字段掩盖镜像错误。
2. 恢复蓝本行为不需要 paired A/B 证明“更好”；parity 本身就是验收。A/B 只用于评估蓝本已有机制的翻译是否正确。
3. 不把 STS1 没有 STS2 的物种字段硬造出来；只映射 STS1 真实存在且蓝本有对应语义的状态。蓝本有而 STS1 未镜像的状态，应登记缺口，不得改成较宽松判等。
4. 每个 Task 先把当前错误行为改成失败测试，再实现到蓝本语义；旧测试若断言自创行为，必须改成反向合同，不得删除后失去覆盖。
5. 目标内自主修改、测试、构建、安装、真机验证和本地 commit；不 push，不砍搜索。

---

## Task 1：撤销默认冻结、单步剔除与无生还禁打

**Files:**
- Modify: `src/main/java/io/github/youqing/sts1solver/verify/AutoPilot.java`
- Modify: `src/main/java/io/github/youqing/sts1solver/search/AsyncSearch.java`（仅删除禁打所需字段/注释；`survives` 若排序仍用则保留）
- Modify: `src/main/java/io/github/youqing/sts1solver/search/BeamSolver.java`（只保留蓝本 Survives 排序语义）
- Test: `src/test/java/io/github/youqing/sts1solver/verify/AdoptRouteTest.java`
- Test: `src/test/java/io/github/youqing/sts1solver/verify/NoSurvivingRouteTest.java`
- Test: `src/test/java/io/github/youqing/sts1solver/verify/AutoPilotContinuationTest.java`

**RED 合同：**
```java
@Test public void routeIsNotFrozenByDefault();
@Test public void stateMismatchReplansInsteadOfKeepingAdoptedRoute();
@Test public void unplayableStepReplansFromRootInsteadOfDroppingOnlyThatStep();
@Test public void noSurvivingRouteDoesNotInventDeploymentBan();
@Test public void deathTurnStopStillMatchesBlueprint();
@Test public void liveEndTurnRiskStopStillMatchesBlueprint();
```

`noSurvivingRouteDoesNotInventDeploymentBan` 必须区分：`deathTurn == currentTurn` 仍按蓝本停；仅 `survives=false` 不得自造另一道闸。删除 `ADOPT_ROUTE_TO_THE_END` 及 adopt 分支，不把默认值从 true 改 false 后还保留自创模式作为产品开关。

**GREEN：** 默认走精确 Continuation；任何部署漂移清旧 source 并 root replan；没有“按序打到底”“失配照打”“单步剔除”分支。

**Commit:** `fix(exec): restore blueprint deployment and survival semantics`

---

## Task 2：撤销本地有界 AUTO 与伪 Adopt/Freeze；只保留蓝本已有控制

**Files:**
- Modify: `src/main/java/io/github/youqing/sts1solver/verify/AutoPilot.java`
- Modify: `src/main/java/io/github/youqing/sts1solver/ui/SolverPanel.java`
- Modify/remove tests: `src/test/java/io/github/youqing/sts1solver/verify/BoundedAutoTargetTest.java`
- Modify/remove tests: `src/test/java/io/github/youqing/sts1solver/verify/RoutePreviewTest.java`
- Reference: `reference/sts2-combatsolver/src/Runtime/SolverController.cs:128-158`
- Reference: `reference/sts2-combatsolver/src/Runtime/SolverProgress.cs`
- Reference: `reference/sts2-combatsolver/src/UI/SolverOverlay.cs`（Adopt/Freeze 按钮与 tooltip）

**要求：**
1. 删除“点 Tn = 自动打到 Tn 末”本地机制及 `BoundedAutoTarget`，不得继续以“用户此前批准”为豁免。
2. 不得再把 `AdoptRoute` 写成 `RouteFrozen`。若本轮不能完整复刻蓝本 search takeover，则两者都登记未做，不实现伪按钮。
3. 若实现 `RouteFrozen`，必须完整满足：默认 false；仅在非搜索/非部署且有 retained route 时可设；设 true 会 `FullAutoEnabled=false`、取消 deferred search；冻结时失配路线只供参考，不自动照打；Manual search 解冻。
4. 删除冻结模式下“仍逐回合 stamp 诊断”的本地增强；蓝本冻结逻辑不做的，不自创。

**RED/GREEN：** 默认无冻结；不存在 bounded target；Adopt 与 Freeze 状态独立；若未完整实现，则无对应控制路径而非半成品。

**Commit:** `fix(ui): remove non-blueprint route control modes`

---

## Task 3：Continuation 改为蓝本回放捕获与双侧重一化

**Files:**
- Modify: `src/main/java/io/github/youqing/sts1solver/search/ContinuationStamp.java`
- Modify: `src/main/java/io/github/youqing/sts1solver/search/ContinuationPlan.java`
- Modify: `src/main/java/io/github/youqing/sts1solver/search/BeamSolver.java`
- Modify: `src/main/java/io/github/youqing/sts1solver/verify/AutoPilot.java`
- Test: `src/test/java/io/github/youqing/sts1solver/search/ContinuationStampTest.java`
- Test: `src/test/java/io/github/youqing/sts1solver/search/ContinuationPlanTest.java`
- Test: `src/test/java/io/github/youqing/sts1solver/search/BeamSolverContinuationTest.java`
- Blueprint: `reference/sts2-combatsolver/src/Runtime/ContinuationStamp.cs:18-182,337-349`
- Blueprint: `reference/sts2-combatsolver/src/Search/CombatBeamSolver.Terminal.cs:337-390`
- Blueprint: `reference/sts2-combatsolver/src/Search/CombatPlan.cs:1696-1861`

**RED 合同：**
```java
@Test public void handOrderMattersExactlyLikeDrawDiscardAndExhaust();
@Test public void liveAndPredictedCaptureHaveSeparateNormalizationPaths();
@Test public void bestNodeSelectsBoundariesButFinalReplayCapturesTheirStates();
@Test public void replayMissingExpectedBoundaryFailsLoudly();
@Test public void pendingChoiceTerminalOrNonForkableBoundaryIsRejected();
@Test public void reusedResultKeepsBestNodeAndFiltersCurrentTurnActions();
```

**实现：**
1. `CaptureLive` 与 `CapturePredicted` 分开；共享低级 append helper 可以，但不能再用一个 `from(SimState)` 代替两侧归一化。
2. hand/draw/discard/exhaust 全部按列表原顺序；删除 `sortedByFullKey` 和手牌无序多重集测试。
3. `PrepareContinuationCapture(best)` 只选稳定边界；随后回放最终路线，在预期 action/turn 调 `CapturePredicted`。边界缺一个就显式失败。
4. 续用按蓝本 `SolverResult.TryCreateContinuation`：保留原 best route，按 `StartTurnNumber` 过滤本回合动作、切 forecast，只留更晚 continuation。不得只返回一个本地 lookup 后另造执行语义。
5. 不删字段提高命中率；命中低继续修影子。

**Commit:** `fix(search): replay final route for exact continuations`

---

## Task 4：药水策略恢复蓝本组合、持久化与失败语义

**Files:**
- Modify: `src/main/java/io/github/youqing/sts1solver/search/PotionStrategySnapshot.java`
- Modify: `src/main/java/io/github/youqing/sts1solver/search/PotionUsePolicy.java`
- Modify: `src/main/java/io/github/youqing/sts1solver/search/BeamSolver.java`
- Modify: `src/main/java/io/github/youqing/sts1solver/config/SolverConfig.java`
- Modify: `src/main/java/io/github/youqing/sts1solver/search/AsyncSearch.java`
- Modify: `src/main/java/io/github/youqing/sts1solver/verify/AutoPilot.java`
- Test: `src/test/java/io/github/youqing/sts1solver/search/PotionStrategySnapshotTest.java`
- Test: `src/test/java/io/github/youqing/sts1solver/search/PotionUsePolicyTest.java`
- Test: `src/test/java/io/github/youqing/sts1solver/search/PotionFinalAnswerPolicyTest.java`
- Test: `src/test/java/io/github/youqing/sts1solver/config/SolverConfigPotionStrategyTest.java`
- Test: `src/test/java/io/github/youqing/sts1solver/verify/PotionPolicyRevisionTest.java`

**RED 合同：**
```java
@Test public void globalDisabledStillAllowsExplicitForceDirective();
@Test public void globalDisabledDisablesOnlyUnregisteredSlotsByDefault();
@Test public void globalPolicyIsNotPersistedAndDefaultsToSmartOnNewSettings();
@Test public void onlyPerSlotDirectivesRoundTrip();
@Test public void forcedOnlyBaselineIsUsedWhenForceExists();
@Test public void optionalPotionsAreChargedAgainstForcedOnlyBaseline();
@Test public void requireAtLeastOneDropsToSmartAfterPotionAlreadyUsedThisCombat();
@Test public void unsatisfiedPolicyThrowsDedicatedExceptionInsteadOfPinnedRescueOrNull();
```

**实现：**
1. `AllowsExplicitUse` 逐行对应蓝本：显式 override 优先；Force 不被全局 Disabled 压掉；`forceAllDisabled` 与 forced-only baseline 分开。
2. 全局 policy 不序列化；旧配置里已写入的全局档位不得继续当作蓝本持久化合同。每槽 `(slot,potionId,directive)` 保留。
3. 删除 `policyRevision` 字段、请求/结果代际与相关测试，改走蓝本策略修改链：校验 slot+ID、部署中拒绝、保存、清 ContinuationSource/PendingBaseline、自动计算开启才 `SearchReason.Manual`。
4. 在统一候选池过滤最终资格；无合格候选用专用 `PotionPolicyUnsatisfiedException` 及蓝本原因文本。删除自造“钉住这瓶补搜”通道。
5. 保留蓝本已有的 forced-use 精确 slot+ID 校验和四预设；不要把正确部分一起回退。

**Commit:** `fix(search): match blueprint potion policy composition`

---

## Task 5：全量来源审计，清除“蓝本没有但默认保留”的残留

**Files:**
- Modify: `docs/research/blueprint-parity.md`
- Modify: `docs/handoff-prompt.md`
- Modify: `记录.txt`
- Audit: `src/main/java/**`、`src/test/java/**`

逐条搜索并处置：
- `ADOPT_ROUTE_TO_THE_END`、`ROUTE_FROZEN`、`adopt mode`、`retry/drop`
- `applyNoSurvivingRouteStop`、`no_surviving_route`
- `BoundedAutoTarget`、`AUTO → Tn`
- `sortedByFullKey`、`handIsAnUnorderedMultiset`
- `policyRevision`、`KEY_POTION_STRATEGY` 中全局 policy
- “最小等价”“本地扩展”“项目自定义”“用户规则”“UI 适配”

每个行为只能归为：
1. **蓝本已有**：给精确 file:line 与合同测试；
2. **STS1 原版行为补正**：给 `javap` 证据，不冒充 CombatSolver parity；
3. **蓝本没有**：移除；不得默认保留。

**Commit:** `docs: enforce source-backed blueprint parity`

---

## Task 6：验证与真机验收

```bash
./gradlew test --offline
./gradlew jar --offline
git diff --check
```

真机至少验证：
1. 默认无 `ROUTE_FROZEN`；新回合精确命中则 `SEARCH_REUSED`，miss 则按蓝本归因重算。
2. 单步不可用/药水槽漂移不会剔一步照打，必须 root replan。
3. 仅“所有路线都不生还”不会触发自创禁打；DeathTurn 与 LiveRisk 仍分别按蓝本停手。
4. 手牌顺序变化会使 stamp 不同；最终回放缺边界会显式失败。
5. 全局 Disabled + 单槽 Force 能且只能使用强制药；重启后全局回 Smart、每槽覆盖保留。
6. 修改策略后旧 continuation 被清；AUTO off 不启动新搜索；执行中拒绝修改。
7. 没有点 Tn、本地默认冻结、单步剔除等残留 UI/日志。

未运行的真机项写未验证。最终报告列 `纠偏项 | commit | 蓝本证据 | 自动化 | 真机 | 状态`，不得只写测试全绿。
