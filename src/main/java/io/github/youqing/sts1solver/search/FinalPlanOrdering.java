package io.github.youqing.sts1solver.search;

import io.github.youqing.sts1solver.mirror.PostCombatRelicHeal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 最终路线的选择器：蓝本 {@code CombatBeamSolver.FinalPlanOrdering.cs:28-321} 的逐行翻译。
 *
 * <p><b>为什么单独一个类。</b> 蓝本把三种比较器分得很清楚：
 * <ul>
 *   <li>{@code SolverInterimResultOrdering} —— 搜索中/面板上的临时结果；</li>
 *   <li>{@code BeamRetentionPolicy} —— Beam 里留哪些半成品；</li>
 *   <li>{@code FinalPlanOrdering}（本类）—— **最终**从全部合格候选里选一条。</li>
 * </ul>
 * STS1 以前用 {@code BeamSolver.better()} 一身兼任三者，键只有 9 个，缺的正是「收益」那一段，
 * 于是「赢得更省」这件事根本无法表达。用户 2026-11-01 22:41 的要求就是补上它。
 *
 * <p><b>键的个数是 18，不是 17。</b> 蓝本 {@code 253-271} 是 1 个 {@code OrderBy} + 17 个
 * {@code ThenBy}；{@code PotionStrategicCost} 只参与资格与赤字计算，**不是**独立键。
 *
 * <p><b>纯数据。</b> 不读 {@code BeamSolver} 的任何可变状态、不读真机对象、不读 {@code SolverConfig}；
 * 参数全部由调用方显式传入，所以可以在后台线程、单测和离线快照里跑出同一个结果。
 *
 * <h3>与蓝本的两处翻译缺口（不是放宽）</h3>
 * <ol>
 *   <li><b>无 {@code StateKey}</b>：蓝本 {@code ComparePotionFreePolicyBaselines} 的最后一项比较
 *       {@code StateKey}，我们没有对应的稳定键，末位平手时按**输入顺序**取先者。差别只在两条
 *       「所有可观测量都相同」的路线之间，不影响任何下游数值，也不破坏确定性。</li>
 *   <li><b>无 Amber（琥珀）</b>：STS1 没有这张药水，{@code optionalAmbergrisCount} 恒为 0，
 *       {@code MeetsAmbergrisRestriction} 在 0 时恒真，所以整段不实现。若将来 STS1 出现同类机制，
 *       必须按蓝本补上，不能在这里自造。</li>
 * </ol>
 */
public final class FinalPlanOrdering {

    /** 键的个数：1 个 {@code OrderBy} + 17 个 {@code ThenBy}。 */
    public static final int KEY_COUNT = 18;

    private static final List<String> KEYS = Collections.unmodifiableList(java.util.Arrays.asList(
            "won",
            "notWonNotSurvives",
            "deathSaveUseCount",
            "outstandingStolenResource",
            "strategicHpDeficit",
            "growthHpCredit",
            "growthRewardCount",
            "combatEndedTurn",
            "policyHpDeficit",
            "healthResourceCost",
            "longTermResourceValue",
            "angerCopiesGenerated",
            "policyBoundaryRank",
            "optionalPotionCount",
            "strategicSold",
            "enemyHp",
            "score",
            "actionCount"));

    private FinalPlanOrdering() {
    }

    /** 18 个键的名字，**按比较顺序**。测试用它钉住「不多不少、顺序不换」。 */
    public static List<String> keyNames() {
        return KEYS;
    }

    /** 蓝本 {@code SolverTheftPolicy}：抢钱怪在场时要不要为追回金币牺牲血量。 */
    public enum TheftPolicy {
        /** 默认。第 4 键生效：钱追不回来就是更差。 */
        PRESERVE_RESOURCES,
        /** 让它们跑：第 4 键恒为 0。 */
        LET_ESCAPE
    }

    /**
     * 蓝本 {@code PolicyBoundaryRank}（{@code CombatBeamSolver.Retention.cs:2182-2193}）。
     *
     * <p>五档，**不是「所有枚举都有档位」**：{@code DynamicResolution} 与
     * {@code MemoryNoProgress} 在蓝本里落到 {@code _ => throw}，这里同样抛。
     * 静默给它们一个档位就等于自造语义。
     */
    public static int policyBoundaryRank(SearchBoundaryReason reason) {
        if (reason == null) {
            throw new IllegalArgumentException("boundary reason must not be null");
        }
        switch (reason) {
            case NONE:
                return 0;
            case NO_CARDS:
            case SHUFFLE:
            case TURN_LIMIT:
            case NODE_LIMIT:
            case TIME_LIMIT:
                return 1;
            case PENDING_CHOICE:
                return 2;
            case UNSUPPORTED_EFFECT:
                return 3;
            case EVENT_DEFEAT:
                return 4;
            default:
                throw new IllegalArgumentException("no policy boundary rank for " + reason);
        }
    }

    // ------------------------------------------------------------------ 输入

    /** 一条候选路线：节点事实 + 快照结论 + 药水指令评估结果。 */
    public static final class Candidate {
        /** 诊断标签（快照名 / portfolio 成员名）。**不参与比较**。 */
        public final String label;
        public final SearchFeatures features;
        public final RouteSnapshot snapshot;
        /** 这条路线满足了几条强制用药指令。 */
        public final int forcedUseCount;
        /** 强制用药是否全部满足（不合格的直接被资格链淘汰）。 */
        public final boolean forcedUsesSatisfied;
        /** 被强制的那几瓶一共占掉多少战略成本。 */
        public final int forcedStrategicHpCost;

        public Candidate(String label, SearchFeatures features, RouteSnapshot snapshot,
                         int forcedUseCount, boolean forcedUsesSatisfied) {
            this(label, features, snapshot, forcedUseCount, forcedUsesSatisfied, 0);
        }

        public Candidate(String label, SearchFeatures features, RouteSnapshot snapshot,
                         int forcedUseCount, boolean forcedUsesSatisfied,
                         int forcedStrategicHpCost) {
            this.label = label;
            this.features = features;
            this.snapshot = snapshot;
            this.forcedUseCount = forcedUseCount;
            this.forcedUsesSatisfied = forcedUsesSatisfied;
            this.forcedStrategicHpCost = forcedStrategicHpCost;
        }
    }

    /** 一次最终选择用的**全部**外部参数（没有一项从全局状态偷偷读）。 */
    public static final class Params {
        public final int initialHp;
        public final int initialPlayerMaxHp;
        /** 战斗里**已经承诺**卖掉的、可避免的血（{@code BattleDamageTracker.SoldHpCommitted}）。 */
        public final int battleSoldHpCommitted;
        /** 「至少用几瓶」的硬下限（蓝本 {@code minimumPotionUses}，默认 0）。 */
        public final int minimumPotionUses;
        public final SolverPotionPolicy potionPolicy;
        public final boolean enforcePotionDirectives;
        public final TheftPolicy theftPolicy;
        /** 腾出一格药水槽能换回的奖励价值（蓝本 {@code potionReplacementHpCredit}，默认 0）。 */
        public final int potionReplacementHpCredit;
        /** 战后遗物回血（燃烧之血 / 黑血 / 带骨肉），只有打赢才算。 */
        public final PostCombatRelicHeal postCombatHeal;
        /** 蓝本 {@code PotionFreePolicyBaseline} 审计覆盖值；{@code null} = 从候选池里自己选。 */
        public final PotionFreeBaseline potionFreeBaseline;

        public Params(int initialHp, int initialPlayerMaxHp, int battleSoldHpCommitted,
                      int minimumPotionUses, SolverPotionPolicy potionPolicy,
                      boolean enforcePotionDirectives, TheftPolicy theftPolicy,
                      int potionReplacementHpCredit, PostCombatRelicHeal postCombatHeal,
                      PotionFreeBaseline potionFreeBaseline) {
            this.initialHp = initialHp;
            this.initialPlayerMaxHp = initialPlayerMaxHp;
            this.battleSoldHpCommitted = battleSoldHpCommitted;
            this.minimumPotionUses = minimumPotionUses;
            this.potionPolicy = potionPolicy == null ? SolverPotionPolicy.SMART : potionPolicy;
            this.enforcePotionDirectives = enforcePotionDirectives;
            this.theftPolicy = theftPolicy == null ? TheftPolicy.PRESERVE_RESOURCES : theftPolicy;
            this.potionReplacementHpCredit = potionReplacementHpCredit;
            this.postCombatHeal = postCombatHeal == null ? PostCombatRelicHeal.none() : postCombatHeal;
            this.potionFreeBaseline = potionFreeBaseline;
        }
    }

    /** 蓝本 {@code PotionFreePolicyBaseline}：外部审计过的「不喝药能打成什么样」。 */
    public static final class PotionFreeBaseline {
        public final boolean won;
        public final int hpDeficit;
        public final int playerHp;
        public final int deathSaveUseCount;
        public final Integer combatEndedTurn;

        public PotionFreeBaseline(boolean won, int hpDeficit, int playerHp,
                                  int deathSaveUseCount, Integer combatEndedTurn) {
            this.won = won;
            this.hpDeficit = hpDeficit;
            this.playerHp = playerHp;
            this.deathSaveUseCount = deathSaveUseCount;
            this.combatEndedTurn = combatEndedTurn;
        }
    }

    // ------------------------------------------------------------------ 输出

    /** 一条候选算完派生量之后的样子。 */
    public static final class CandidateResult {
        public final Candidate candidate;
        /** 等于 {@code candidate.label}；诊断与测试直接读它。 */
        public final String label;
        public final SearchFeatures features;
        public final RouteSnapshot snapshot;
        public final RouteQuality quality;

        public final boolean completeVictory;
        public final int explicitPotionCount;
        public final int optionalPotionCount;
        public final int optionalPotionStrategicCost;
        public final SolverPotionPolicy effectivePotionPolicy;
        public final int strategicHpDeficit;
        public final int policyHpDeficit;
        public final int healthResourceCost;
        public final int strategicSold;

        CandidateResult(Candidate candidate, RouteQuality quality, boolean completeVictory,
                        int explicitPotionCount, int optionalPotionCount,
                        int optionalPotionStrategicCost, SolverPotionPolicy effectivePotionPolicy,
                        int strategicHpDeficit, int policyHpDeficit, int healthResourceCost,
                        int strategicSold) {
            this.candidate = candidate;
            this.label = candidate.label;
            this.features = candidate.features;
            this.snapshot = candidate.snapshot;
            this.quality = quality;
            this.completeVictory = completeVictory;
            this.explicitPotionCount = explicitPotionCount;
            this.optionalPotionCount = optionalPotionCount;
            this.optionalPotionStrategicCost = optionalPotionStrategicCost;
            this.effectivePotionPolicy = effectivePotionPolicy;
            this.strategicHpDeficit = strategicHpDeficit;
            this.policyHpDeficit = policyHpDeficit;
            this.healthResourceCost = healthResourceCost;
            this.strategicSold = strategicSold;
        }
    }

    /** 选择结果。{@link #results} 已经按 18 键排好，第 0 个就是选中的那条。 */
    public static final class Selection {
        public final List<CandidateResult> results;
        public final int selectedIndex;
        public final int candidatesEvaluated;
        public final int candidatesRejected;
        /** 用了药的候选里，被资格链否掉了几条（蓝本 {@code potionBranchesRejected}）。 */
        public final int potionBranchesRejected;

        Selection(List<CandidateResult> results, int candidatesEvaluated, int candidatesRejected,
                  int potionBranchesRejected) {
            this.results = Collections.unmodifiableList(results);
            this.selectedIndex = 0;
            this.candidatesEvaluated = candidatesEvaluated;
            this.candidatesRejected = candidatesRejected;
            this.potionBranchesRejected = potionBranchesRejected;
        }

        public CandidateResult selected() {
            return results.get(selectedIndex);
        }
    }

    // ------------------------------------------------------------------ 选择

    /**
     * 从**完整候选池**里选出最终路线。
     *
     * <p>顺序与蓝本一致：算派生量 → 选无药基线 → 资格过滤 → 18 键词典序 → 空集抛异常。
     * 关键点是**资格在排序之前**：先排再挑会得到「第一名不合格就抛」的退化行为，
     * 而蓝本的口径是「池子里还有合格的，就选合格里最好的那一条」。
     */
    public static Selection select(List<Candidate> candidates, Params params) {
        List<CandidateResult> evaluated = new ArrayList<CandidateResult>(candidates.size());
        for (Candidate candidate : candidates) {
            evaluated.add(evaluate(candidate, params));
        }

        int baselineIndex = selectPotionFreeBaseline(evaluated, params);
        boolean hasBaseline = params.potionFreeBaseline != null || baselineIndex >= 0;
        boolean potionFreeWon;
        int potionFreeDeficit;
        int potionFreeOutstanding;
        int potionFreeDeathSave;
        int potionFreePlayerHp;
        if (params.potionFreeBaseline != null) {
            PotionFreeBaseline audited = params.potionFreeBaseline;
            potionFreeWon = audited.won;
            potionFreeDeficit = audited.hpDeficit;
            potionFreePlayerHp = audited.playerHp;
            potionFreeDeathSave = audited.deathSaveUseCount;
            potionFreeOutstanding = Integer.MAX_VALUE;
        } else if (baselineIndex >= 0) {
            CandidateResult baseline = evaluated.get(baselineIndex);
            potionFreeWon = baseline.completeVictory;
            potionFreeDeficit = baseline.strategicHpDeficit;
            potionFreePlayerHp = baseline.features.playerHp;
            potionFreeDeathSave = baseline.snapshot.deathSaveUseCount;
            potionFreeOutstanding = baseline.features.outstandingStolenResource;
        } else {
            // 蓝本同款缺省：没有无药路线时，基线是「开局血量、一瓶都没喝、什么都不剩」。
            potionFreeWon = false;
            potionFreeDeficit = params.initialHp;
            potionFreePlayerHp = 0;
            potionFreeDeathSave = Integer.MAX_VALUE;
            potionFreeOutstanding = Integer.MAX_VALUE;
        }
        boolean anyRouteWon = potionFreeWon;
        for (CandidateResult result : evaluated) {
            if (result.completeVictory) {
                anyRouteWon = true;
                break;
            }
        }

        List<CandidateResult> eligible = new ArrayList<CandidateResult>();
        int potionBranches = 0;
        int potionBranchesEligible = 0;
        for (CandidateResult result : evaluated) {
            if (result.features.potionCount > 0) {
                potionBranches++;
            }
            if (isEligible(result, params, hasBaseline, potionFreeWon, potionFreeDeficit,
                    potionFreeOutstanding, potionFreeDeathSave, potionFreePlayerHp, anyRouteWon)) {
                eligible.add(result);
                if (result.features.potionCount > 0) {
                    potionBranchesEligible++;
                }
            }
        }
        if (eligible.isEmpty()) {
            throw new PotionPolicyUnsatisfiedException(describeEmptyPool(params));
        }
        Collections.sort(eligible, finalOrder(params.theftPolicy));
        return new Selection(eligible, evaluated.size(), evaluated.size() - eligible.size(),
                potionBranches - potionBranchesEligible);
    }

    /** 蓝本 {@code 273-281}：空集时按「有没有 Force / 是不是 RequireAtLeastOne」给三种文案。 */
    private static String describeEmptyPool(Params params) {
        if (params.enforcePotionDirectives) {
            return "指定药水必须使用，但搜索没有找到可执行路线。";
        }
        if (params.potionPolicy == SolverPotionPolicy.REQUIRE_AT_LEAST_ONE) {
            return "本场药水策略要求至少使用一瓶，但搜索没有找到可执行的用药路线。";
        }
        return "本场药水策略没有可执行路线。";
    }

    /** 算出这条候选的全部派生量。纯函数，不碰任何全局状态。 */
    static CandidateResult evaluate(Candidate candidate, Params params) {
        SearchFeatures features = candidate.features;
        RouteSnapshot snapshot = candidate.snapshot;

        boolean completeVictory = isCompleteVictory(features.actionCount, features.allEnemiesDead,
                snapshot.playerDead, features.projectedPlayerHp);
        boolean survives = !snapshot.playerDead && features.projectedPlayerHp > 0;
        int explicitPotionCount = features.potionCount - snapshot.automaticPotionUseCount;
        int optionalPotionCount = Math.max(0, explicitPotionCount - candidate.forcedUseCount);
        int optionalPotionStrategicCost = applyReplacementCredit(
                Math.max(0, snapshot.explicitPotionStrategicCost - candidate.forcedStrategicHpCost),
                optionalPotionCount, params.potionReplacementHpCredit);
        SolverPotionPolicy effectivePolicy = effectivePotionPolicy(params.potionPolicy,
                candidate.forcedUseCount, optionalPotionCount);
        int strategicHpDeficit = strategicHpDeficit(features, snapshot, params, completeVictory);
        int healthResourceCost = params.initialHp - features.playerHp
                + params.initialPlayerMaxHp - features.playerMaxHp;
        int policyHpDeficit = strategicHpDeficit
                + (effectivePolicy == SolverPotionPolicy.REQUIRE_AT_LEAST_ONE
                ? PotionUsePolicy.additionalRequiredUseStrategicHpCost(optionalPotionStrategicCost)
                : 0);
        int strategicSold = params.battleSoldHpCommitted + features.futureSoldHp;

        RouteQuality quality = new RouteQuality(
                completeVictory,
                survives,
                snapshot.deathSaveUseCount,
                features.outstandingStolenResource,
                strategicHpDeficit,
                snapshot.strategyGoalHpCredit,
                snapshot.strategyGoalCount,
                completeVictory ? snapshot.combatEndedTurn : null,
                features.cumulativePlayerHpLost,
                snapshot.potionStrategicCost,
                features.potionCount,
                features.enemyHp,
                features.score,
                0L);

        return new CandidateResult(candidate, quality, completeVictory, explicitPotionCount,
                optionalPotionCount, optionalPotionStrategicCost, effectivePolicy,
                strategicHpDeficit, policyHpDeficit, healthResourceCost, strategicSold);
    }

    /** 蓝本 {@code 58-65}：Require 被强制药满足 → 降为 Smart；Disabled 但还有可选药 → 也按 Smart 判。 */
    static SolverPotionPolicy effectivePotionPolicy(SolverPotionPolicy policy,
                                                    int forcedUseCount,
                                                    int optionalPotionCount) {
        if (policy == SolverPotionPolicy.REQUIRE_AT_LEAST_ONE && forcedUseCount > 0) {
            return SolverPotionPolicy.SMART;
        }
        if (policy == SolverPotionPolicy.DISABLED && optionalPotionCount > 0) {
            return SolverPotionPolicy.SMART;
        }
        return policy;
    }

    /** 蓝本 {@code PotionUsePolicy.ApplyReplacementCredit}：底价 1，不是 0。 */
    static int applyReplacementCredit(int optionalPotionStrategicCost, int optionalPotionCount,
                                      int replacementHpCredit) {
        return optionalPotionStrategicCost > 0 && optionalPotionCount > 0 && replacementHpCredit > 0
                ? Math.max(1, optionalPotionStrategicCost - replacementHpCredit)
                : optionalPotionStrategicCost;
    }

    /** 蓝本 {@code SolverInterimResultOrdering.IsCompleteVictory}（{@code SolverInterimResultOrdering.cs:5-13}）。 */
    public static boolean isCompleteVictory(int actionCount, boolean allEnemiesDead,
                                            boolean playerDead, int projectedPlayerHp) {
        return actionCount > 0 && allEnemiesDead && !playerDead && projectedPlayerHp > 0;
    }

    /**
     * 蓝本 {@code ActEndingBossPolicy.StrategicHpDeficit}（{@code BossHpRelief.None} 档）。
     *
     * <pre>
     * cumulativeHpLost + maxHpDeficit
     *   - PersistentValueOfRecoveredHp(recovered + relicHeal - deathSaveHpRestored)
     *   + DeathSavePremium(deathSaveHpRestored)
     *   - StrategicHpCredit
     * </pre>
     *
     * <p>{@code BossHpRelief.None} 档下 {@code RawHpRequiredForPersistentValue(1) = 1}，
     * 所以「回血的价值」就是它本身（负数按 0）。死亡豁免恢复的那部分**不**算这条路线挣的，
     * 换成 {@code DeathSavePremium} 的溢价（{@code SolverWeights.DeathSavePremiumPercent = 900}）。
     */
    public static int strategicHpDeficit(SearchFeatures features, RouteSnapshot snapshot,
                                         Params params, boolean completeVictory) {
        int maxHpDeficit = Math.max(0, params.initialPlayerMaxHp - features.playerMaxHp);
        int relicHeal = completeVictory && features.playerHp > 0
                ? params.postCombatHeal.monotoneHealFor(features.playerHp, features.playerMaxHp)
                : 0;
        int earned = features.recoveredPlayerHp + relicHeal - features.deathSaveHpRestored;
        int recoveredValue = Math.max(0, earned);
        int premium = Math.max(0, features.deathSaveHpRestored)
                * Score.DEATH_SAVE_PREMIUM_PERCENT / 100;
        return features.cumulativePlayerHpLost + maxHpDeficit - recoveredValue + premium
                - snapshot.strategicHpCredit;
    }

    // ------------------------------------------------------------------ 资格

    private static boolean isEligible(CandidateResult result, Params params, boolean hasBaseline,
                                      boolean potionFreeWon, int potionFreeDeficit,
                                      int potionFreeOutstanding, int potionFreeDeathSave,
                                      int potionFreePlayerHp, boolean anyRouteWon) {
        if (!result.candidate.forcedUsesSatisfied) {
            return false;
        }
        if (result.explicitPotionCount < params.minimumPotionUses) {
            return false;
        }
        boolean preserve = params.theftPolicy == TheftPolicy.PRESERVE_RESOURCES;
        boolean recoversMoreStolenResource = preserve
                && result.features.potionCount > 0
                && result.features.outstandingStolenResource < potionFreeOutstanding;
        boolean protectsDeathSave = hasBaseline
                && potionFreeWon
                && result.completeVictory
                && params.potionPolicy != SolverPotionPolicy.DISABLED
                && result.optionalPotionCount > 0
                && result.snapshot.deathSaveUseCount < potionFreeDeathSave;
        int hpSaved = PotionUsePolicy.hpSaved(potionFreeDeficit, result.strategicHpDeficit);
        boolean passesSoftPotionPolicy = PotionUsePolicy.isEligible(
                result.effectivePotionPolicy,
                result.optionalPotionCount,
                anyRouteWon,
                potionFreeWon,
                result.completeVictory,
                hpSaved,
                smartRequiredHpSaved(result.optionalPotionStrategicCost))
                || protectsDeathSave
                || recoversMoreStolenResource;
        // 琥珀（Ambergris）在 STS1 不存在：optionalAmbergrisCount 恒为 0，
        // MeetsAmbergrisRestriction 在 0 时恒真，整段不实现（见类注释的翻译缺口 ②）。
        return passesSoftPotionPolicy;
    }

    /** 蓝本 {@code ScalePotionCost} → {@code SmartRequiredHpSaved}，{@code BossHpRelief.None} 档是恒等。 */
    private static int smartRequiredHpSaved(int strategicHpCost) {
        return Math.max(0, strategicHpCost);
    }

    /**
     * 蓝本 {@code 146-162}：在「显式用药为 0」的候选里挑一条做无药基线。
     *
     * <p>不是「取列表第一瓶无药路线」—— 那会让「随便一条没喝药的烂路线」成为计价基准，
     * 于是任何喝药路线都显得很划算。这里按 {@link #comparePotionFreeBaselines} 逐个替换更好的那条。
     */
    private static int selectPotionFreeBaseline(List<CandidateResult> evaluated, Params params) {
        int best = -1;
        for (int index = 0; index < evaluated.size(); index++) {
            CandidateResult candidate = evaluated.get(index);
            if (candidate.explicitPotionCount != 0) {
                continue;
            }
            if (best >= 0 && comparePotionFreeBaselines(candidate, evaluated.get(best), params) >= 0) {
                continue;
            }
            best = index;
        }
        return best;
    }

    /**
     * 蓝本 {@code ComparePotionFreePolicyBaselines}（{@code 324-430}）：返回值 &lt; 0 表示
     * {@code left} 更好（C# 的 {@code CompareTo(left, right)} 语义）。
     *
     * <p>末位平手时蓝本比 {@code StateKey}；我们没有它，返回 0，由调用方保留先出现的那条
     * （输入顺序本身就是确定的）。
     */
    static int comparePotionFreeBaselines(CandidateResult left, CandidateResult right,
                                          Params params) {
        boolean leftWon = left.completeVictory;
        boolean rightWon = right.completeVictory;
        if (leftWon != rightWon) {
            return leftWon ? -1 : 1;
        }
        if (!leftWon && !rightWon) {
            boolean leftSurvives = left.quality.survives;
            boolean rightSurvives = right.quality.survives;
            if (leftSurvives != rightSurvives) {
                return leftSurvives ? -1 : 1;
            }
        }
        int comparison = Integer.compare(left.snapshot.deathSaveUseCount,
                right.snapshot.deathSaveUseCount);
        if (comparison != 0) {
            return comparison;
        }
        comparison = compareRecovery(params.theftPolicy, leftWon,
                left.features.outstandingStolenResource, rightWon,
                right.features.outstandingStolenResource);
        if (comparison != 0) {
            return comparison;
        }
        comparison = comparePrimary(left.quality, right.quality);
        if (comparison != 0) {
            return comparison;
        }
        if (params.theftPolicy == TheftPolicy.PRESERVE_RESOURCES) {
            comparison = Integer.compare(left.features.outstandingStolenResource,
                    right.features.outstandingStolenResource);
            if (comparison != 0) {
                return comparison;
            }
        }
        comparison = Integer.compare(left.healthResourceCost, right.healthResourceCost);
        if (comparison != 0) {
            return comparison;
        }
        comparison = Integer.compare(right.features.longTermResourceValue,
                left.features.longTermResourceValue);
        if (comparison != 0) {
            return comparison;
        }
        comparison = Integer.compare(left.features.angerCopiesGenerated,
                right.features.angerCopiesGenerated);
        if (comparison != 0) {
            return comparison;
        }
        comparison = Integer.compare(policyBoundaryRank(left.features.boundaryReason),
                policyBoundaryRank(right.features.boundaryReason));
        if (comparison != 0) {
            return comparison;
        }
        comparison = Integer.compare(left.features.enemyHp, right.features.enemyHp);
        if (comparison != 0) {
            return comparison;
        }
        comparison = Double.compare(right.features.score, left.features.score);
        if (comparison != 0) {
            return comparison;
        }
        comparison = Integer.compare(left.features.futureSoldHp, right.features.futureSoldHp);
        if (comparison != 0) {
            return comparison;
        }
        return Integer.compare(left.features.actionCount, right.features.actionCount);
    }

    /** 蓝本 {@code TheftEncounterStrategy.CompareRecovery}。 */
    private static int compareRecovery(TheftPolicy policy, boolean leftWon, int leftOutstanding,
                                       boolean rightWon, int rightOutstanding) {
        if (leftWon != rightWon) {
            return leftWon ? -1 : 1;
        }
        return policy == TheftPolicy.PRESERVE_RESOURCES
                ? Integer.compare(leftOutstanding, rightOutstanding) : 0;
    }

    /** 蓝本 {@code RouteQualityPolicy.ComparePrimary}。 */
    private static int comparePrimary(RouteQuality left, RouteQuality right) {
        if (left.won != right.won) {
            return left.won ? -1 : 1;
        }
        int comparison = Integer.compare(left.deathSaveUseCount, right.deathSaveUseCount);
        if (comparison != 0) {
            return comparison;
        }
        comparison = Integer.compare(left.strategicHpDeficit, right.strategicHpDeficit);
        if (comparison != 0) {
            return comparison;
        }
        comparison = Integer.compare(right.growthHpCredit, left.growthHpCredit);
        if (comparison != 0) {
            return comparison;
        }
        comparison = Integer.compare(right.growthRewardCount, left.growthRewardCount);
        if (comparison != 0) {
            return comparison;
        }
        return Integer.compare(orMax(left.combatEndedTurn), orMax(right.combatEndedTurn));
    }

    private static int orMax(Integer value) {
        return value == null ? Integer.MAX_VALUE : value;
    }

    // ------------------------------------------------------------------ 18 键

    /**
     * 蓝本 {@code 252-272} 的词典序。**顺序就是语义**，改一行等于改玩法。
     *
     * <p>用稳定排序（{@link Collections#sort}）保证「18 键全同 → 保持输入顺序」。
     */
    static Comparator<CandidateResult> finalOrder(final TheftPolicy theftPolicy) {
        final boolean preserve = theftPolicy == TheftPolicy.PRESERVE_RESOURCES;
        return new Comparator<CandidateResult>() {
            @Override
            public int compare(CandidateResult left, CandidateResult right) {
                RouteQuality a = left.quality;
                RouteQuality b = right.quality;
                SearchFeatures fa = left.features;
                SearchFeatures fb = right.features;
                // 1. Won 降序
                if (a.won != b.won) {
                    return a.won ? -1 : 1;
                }
                // 2. (!Won && !Survives) 升序 —— 只在**都没赢**时才区分
                int comparison = Integer.compare(
                        !a.won && !a.survives ? 1 : 0,
                        !b.won && !b.survives ? 1 : 0);
                if (comparison != 0) {
                    return comparison;
                }
                // 3. DeathSaveUseCount 升序
                comparison = Integer.compare(a.deathSaveUseCount, b.deathSaveUseCount);
                if (comparison != 0) {
                    return comparison;
                }
                // 4. OutstandingStolenResource 升序（仅 PreserveResources 生效）
                comparison = Integer.compare(
                        preserve ? a.outstandingStolenResource : 0,
                        preserve ? b.outstandingStolenResource : 0);
                if (comparison != 0) {
                    return comparison;
                }
                // 5. StrategicHpDeficit 升序
                comparison = Integer.compare(a.strategicHpDeficit, b.strategicHpDeficit);
                if (comparison != 0) {
                    return comparison;
                }
                // 6. GrowthHpCredit 降序
                comparison = Integer.compare(b.growthHpCredit, a.growthHpCredit);
                if (comparison != 0) {
                    return comparison;
                }
                // 7. GrowthRewardCount 降序
                comparison = Integer.compare(b.growthRewardCount, a.growthRewardCount);
                if (comparison != 0) {
                    return comparison;
                }
                // 8. CombatEndedTurn ?? MaxValue 升序
                comparison = Integer.compare(orMax(a.combatEndedTurn), orMax(b.combatEndedTurn));
                if (comparison != 0) {
                    return comparison;
                }
                // 9. PolicyHpDeficit 升序
                comparison = Integer.compare(left.policyHpDeficit, right.policyHpDeficit);
                if (comparison != 0) {
                    return comparison;
                }
                // 10. HealthResourceCost 升序（允许负值）
                comparison = Integer.compare(left.healthResourceCost, right.healthResourceCost);
                if (comparison != 0) {
                    return comparison;
                }
                // 11. LongTermResourceValue 降序
                comparison = Integer.compare(fb.longTermResourceValue, fa.longTermResourceValue);
                if (comparison != 0) {
                    return comparison;
                }
                // 12. AngerCopiesGenerated 升序
                comparison = Integer.compare(fa.angerCopiesGenerated, fb.angerCopiesGenerated);
                if (comparison != 0) {
                    return comparison;
                }
                // 13. PolicyBoundaryRank 升序
                comparison = Integer.compare(policyBoundaryRank(fa.boundaryReason),
                        policyBoundaryRank(fb.boundaryReason));
                if (comparison != 0) {
                    return comparison;
                }
                // 14. OptionalPotionCount 升序
                comparison = Integer.compare(left.optionalPotionCount, right.optionalPotionCount);
                if (comparison != 0) {
                    return comparison;
                }
                // 15. StrategicSold 升序
                comparison = Integer.compare(left.strategicSold, right.strategicSold);
                if (comparison != 0) {
                    return comparison;
                }
                // 16. EnemyHp 升序
                comparison = Integer.compare(fa.enemyHp, fb.enemyHp);
                if (comparison != 0) {
                    return comparison;
                }
                // 17. Score 降序
                comparison = Double.compare(fb.score, fa.score);
                if (comparison != 0) {
                    return comparison;
                }
                // 18. ActionCount 升序。到这里必须是最后一项 —— 再加就是第 19 键。
                return Integer.compare(fa.actionCount, fb.actionCount);
            }
        };
    }
}
