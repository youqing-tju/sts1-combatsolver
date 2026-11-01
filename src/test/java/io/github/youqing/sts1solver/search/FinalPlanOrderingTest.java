package io.github.youqing.sts1solver.search;

import io.github.youqing.sts1solver.mirror.PostCombatRelicHeal;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 最终路线排序：蓝本 {@code CombatBeamSolver.FinalPlanOrdering.cs:253-271} 的**18 键**词典序。
 *
 * <p>为什么要有这个文件：STS1 以前用 {@code BeamSolver.betterInterim()} 一身兼任「终局选择 + 当前回合候选 +
 * novelty + portfolio + 最终池」，键只有 9 个，缺的正是**收益**那一段（成长、长期资源、药水、卖血）。
 * 用户 2026-11-01 22:41 的要求是「不能光赢，要战损最小 + 收益最大的赢法」；蓝本把这件事实现在一个
 * **独立的纯数据比较器**里，所以这里先把它单独钉死，再接搜索。
 *
 * <p>蓝本键数是 **18**（1 个 {@code OrderBy} + 17 个 {@code ThenBy}），不是旧交接写的 17；
 * {@code PotionStrategicCost} **不是**独立键（设计 §3 已纠正），所以既不许 17 也不许 19。
 */
class FinalPlanOrderingTest {

    // ---------------------------------------------------------------- 键的形状

    /**
     * 键必须是 18 个，且顺序与蓝本逐字对应。
     *
     * <p>用「精确列表」而不是「数量」：数量对不上是错，**顺序被换也是错** ——
     * 而顺序被换是不会报错的那一类，牌会突然打得很怪，但什么异常都没有。
     */
    @Test
    void orderingHasExactlyTheBlueprintsEighteenKeysInOrder() {
        assertEquals(Arrays.asList(
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
                "actionCount"), FinalPlanOrdering.keyNames());
        assertEquals(18, FinalPlanOrdering.KEY_COUNT);
    }

    /** {@code PotionStrategicCost} 只参与资格与赤字，**不许**变成第 19 个键。 */
    @Test
    void potionStrategicCostIsNotAnIndependentKey() {
        assertFalse(FinalPlanOrdering.keyNames().contains("potionStrategicCost"),
                "设计 §3：PotionStrategicCost 不是这 18 键中的独立一项");
        for (String key : FinalPlanOrdering.keyNames()) {
            assertFalse(key.toLowerCase().contains("potionstrategiccost"), key);
        }
    }

    // ---------------------------------------------------------------- 逐键方向

    /**
     * 逐键扫描：以 base 为**每个键都最优**的候选，每次只把一个键变差，断言 base 仍排第一。
     *
     * <p>同时证明两件事：「每个键真的在起作用」与「方向正确」。跳过第 2 键（{@code !Won && !Survives}）
     * 与第 9 键（{@code PolicyHpDeficit}）：前者要求两条候选**都没赢**、后者要求
     * {@code RequireAtLeastOne}，与其余键同一份夹具不共存，各自有专门测试。
     */
    @Test
    void everyKeyBreaksTiesInTheBlueprintsDirection() {
        for (int key = 0; key < FinalPlanOrdering.KEY_COUNT; key++) {
            if (key == 1 || key == 8) {
                continue;             // 见 secondKeyOnlySeparates… / policyHpDeficitOnlyCharges…
            }
            Spec base = base();
            Spec other = worseOn(key);
            assertTrue(base.sameThrough(other, key),
                    "变体 " + FinalPlanOrdering.keyNames().get(key) + " 在前 " + key + " 键里就已经不同了");
            assertTrue(base.differsAt(other, key),
                    "变体 " + FinalPlanOrdering.keyNames().get(key) + " 并没有改变第 " + key + " 键");

            FinalPlanOrdering.Selection sel = select(base, other);
            assertSame(base.label, winner(sel).candidate.label,
                    "第 " + (key + 1) + " 键（" + FinalPlanOrdering.keyNames().get(key) + "）方向反了");
        }
    }

    /** 高优先级键压倒所有低优先级键：赢的路线哪怕每项都更差，也排在输的路线前面。 */
    @Test
    void higherPriorityKeyDominatesEveryLowerOne() {
        Spec winner = base();
        winner.playerHp = 1;                 // 战损极差
        winner.actionCount = 40;             // 动作极多
        winner.enemyHp = 999;
        winner.score = -1_000;
        winner.deathSaveUseCount = 3;
        winner.boundary = SearchBoundaryReason.TIME_LIMIT;
        winner.potionCount = 2;
        winner.explicitPotionStrategicCost = 40;

        Spec loser = base();
        loser.allEnemiesDead = false;
        loser.playerHp = 80;
        loser.actionCount = 3;
        loser.enemyHp = 1;
        loser.score = 1_000_000;

        assertSame(winner.label, winner(select(loser, winner)).candidate.label,
                "赢的路线必须压倒所有低优先级优势");
    }

    /**
     * 第二键（{@code !Won && !Survives}）**只可能**区分输的路线。
     *
     * <p>因为蓝本的完整胜利定义本身就含 {@code !PlayerDead && ProjectedPlayerHp > 0}，
     * 所以「赢了但没活下来」不存在，赢的路线 {@code survives} 必为 true、这一项恒为 0。
     * 旧实现在胜利路线之间也比 {@code Survives}，那是自创 —— 它只会让「赢」这件事被别的东西压过去。
     */
    @Test
    void secondKeyOnlySeparatesLosingRoutes() {
        assertFalse(FinalPlanOrdering.isCompleteVictory(5, true, true, 20),
                "玩家死了就不可能是完整胜利");
        assertFalse(FinalPlanOrdering.isCompleteVictory(5, true, false, 0),
                "投影血量必须是正的");

        Spec cheapSaves = base();
        cheapSaves.deathSaveUseCount = 0;
        Spec manySaves = base();
        manySaves.deathSaveUseCount = 1;

        assertTrue(cheapSaves.won() && manySaves.won(), "前提：两条都赢");
        assertEquals(0, cheapSaves.notWonNotSurvives());
        assertEquals(0, manySaves.notWonNotSurvives());
        assertSame(cheapSaves.label, winner(select(manySaves, cheapSaves)).candidate.label,
                "都是胜利路线时第二键不参与，第 3 键（死豁免次数）决定先后");
    }

    /** 只有输了的两条路线才比「活着」。 */
    @Test
    void secondKeyRanksSurvivalAmongLosers() {
        Spec dead = base();
        dead.allEnemiesDead = false;
        dead.playerDead = true;

        Spec alive = base();
        alive.allEnemiesDead = false;
        alive.playerHp = 40;

        assertTrue(dead.won() == alive.won() && !dead.won(), "前提：两条都没赢");
        assertSame(alive.label, winner(select(dead, alive)).candidate.label, "都没赢时，活着的那条优先");
    }

    // ---------------------------------------------------------------- 派生量

    /** 第 8 键：只有完整胜利才有 {@code CombatEndedTurn}，其余是无穷大。 */
    @Test
    void combatEndedTurnIsInfiniteUnlessTheRouteWon() {
        Spec lost = base();
        lost.allEnemiesDead = false;
        lost.combatEndedTurn = 1;            // 想靠「结束得早」占便宜

        Spec won = base();
        won.combatEndedTurn = 9;

        FinalPlanOrdering.Selection sel = select(lost, won);
        assertSame(won.label, winner(sel).candidate.label);
        assertNull(result(sel, lost).quality.combatEndedTurn,
                "没赢就没有结束回合，不许拿 1 充数");
        assertEquals(Integer.valueOf(9), result(sel, won).quality.combatEndedTurn);
    }

    /** 第 10 键：{@code initialHp - finalHp + initialMaxHp - finalMaxHp}，**允许负值**。 */
    @Test
    void healthResourceCostCanBeNegativeAndIsNotClamped() {
        Spec grown = base();
        grown.playerMaxHp = 90;              // 喂食涨的最大生命
        grown.playerHp = 85;

        FinalPlanOrdering.Selection sel = select(grown, base());
        assertEquals(-15, result(sel, grown).healthResourceCost,
                "80-85 + 80-90 = -15：涨上去的最大生命是真的赚到，不许夹 0");
    }

    /** 第 9 键：只有**有效** RequireAtLeastOne 才加 {@code max(0, optionalCost-9)}。 */
    @Test
    void policyHpDeficitOnlyChargesExtraUnderRequireAtLeastOne() {
        Spec cheap = base();
        cheap.potionCount = 1;
        cheap.explicitPotionStrategicCost = 9;

        Spec costly = base();
        costly.potionCount = 1;
        costly.explicitPotionStrategicCost = 30;

        FinalPlanOrdering.Selection smart = select(params(SolverPotionPolicy.SMART), costly, cheap);
        assertEquals(10, result(smart, cheap).policyHpDeficit,
                "SMART 下药水成本不进 policyHpDeficit");
        assertEquals(10, result(smart, costly).policyHpDeficit);

        FinalPlanOrdering.Selection require = select(params(SolverPotionPolicy.REQUIRE_AT_LEAST_ONE), costly, cheap);
        assertEquals(10, result(require, cheap).policyHpDeficit, "strategic 10 + max(0, 9-9)");
        assertEquals(31, result(require, costly).policyHpDeficit, "strategic 10 + max(0, 30-9)");
        assertSame(cheap.label, winner(require).label,
                "RequireAtLeastOne 下，多花的药水成本排在第 9 键");
    }

    /** 第 14 键：{@code max(0, 显式 - 强制)}，自动药不算显式。 */
    @Test
    void optionalPotionCountSubtractsForcedAndIgnoresAutomatic() {
        Spec automatic = base();
        automatic.potionCount = 1;
        automatic.automaticPotionUseCount = 1;      // Boss 自动喝的那瓶
        assertEquals(0, result(select(automatic, base()), automatic).optionalPotionCount,
                "自动药不进显式数，也就不进可选数");

        Spec twoWithOneForced = base();
        twoWithOneForced.potionCount = 2;
        twoWithOneForced.forcedUseCount = 1;
        assertEquals(1, result(select(twoWithOneForced, base()), twoWithOneForced).optionalPotionCount);

        Spec overForced = base();
        overForced.potionCount = 1;
        overForced.forcedUseCount = 3;              // 防御性：不许出现负数
        assertEquals(0, result(select(overForced, base()), overForced).optionalPotionCount);
    }

    /** 第 18 键是**动作数**，不是回合层数。 */
    @Test
    void actionCountCountsActionsNotTurnLayers() {
        Spec three = base();
        three.actionCount = 3;
        three.combatEndedTurn = 8;

        Spec four = base();
        four.actionCount = 4;
        four.combatEndedTurn = 8;

        assertSame(three.label, winner(select(four, three)).candidate.label,
                "同样在第 8 回合结束，动作少的那条优先");
    }

    /** 第 4 键只在保留资源的策略下生效（蓝本 {@code theftPolicy == PreserveResources}）。 */
    @Test
    void stolenResourceOnlyRanksUnderPreserveResources() {
        Spec rich = base();
        rich.outstandingStolenResource = 30;
        Spec clean = base();
        clean.outstandingStolenResource = 0;

        assertSame(clean.label, winner(select(params(SolverPotionPolicy.SMART,
                        FinalPlanOrdering.TheftPolicy.PRESERVE_RESOURCES), rich, clean)).label,
                "PreserveResources：钱先追回来");

        assertSame(rich.label, winner(select(params(SolverPotionPolicy.SMART,
                        FinalPlanOrdering.TheftPolicy.LET_ESCAPE), rich, clean)).label,
                "LetEscape：这一键恒为 0，于是回到输入顺序（第 1 个在前）");
    }

    // ---------------------------------------------------------------- 稳定性

    /** 18 键全同 → 保持输入顺序，不得自造第 19 键（标签 / StateKey / 指纹都不算）。 */
    @Test
    void tiesFollowInputOrderInBothDirections() {
        assertEquals(Arrays.asList("a", "b"), labels(select(named("a"), named("b"))));
        assertEquals(Arrays.asList("b", "a"), labels(select(named("b"), named("a"))));
    }

    // ---------------------------------------------------------------- 资格

    /** 第一名不合格、次优合格 → 选次优；不抛异常，也不退回违规路线。 */
    @Test
    void ineligibleFirstPlaceYieldsTheBestEligibleRoute() {
        Spec illegal = base();
        illegal.label = "illegal";
        illegal.potionCount = 1;
        illegal.explicitPotionStrategicCost = 0;
        illegal.forcedUsesSatisfied = false;     // 强制用药没被满足
        illegal.playerHp = 79;                   // 其他键都更优

        Spec legal = base();
        legal.label = "legal";
        legal.playerHp = 40;

        FinalPlanOrdering.Selection sel = select(illegal, legal);
        assertSame("legal", winner(sel).candidate.label);
        assertEquals(1, sel.candidatesRejected, "被淘汰 1 条");
    }

    /** 一条都不合格 → 抛专用异常（蓝本 {@code PotionPolicyUnsatisfiedException}）。 */
    @Test
    void noEligibleCandidateThrowsTheDedicatedException() {
        Spec forced = base();
        forced.forcedUsesSatisfied = false;

        assertThrows(PotionPolicyUnsatisfiedException.class, () -> select(forced));
    }

    /** 「至少一瓶」在没有任何用药路线时必须失败，不能悄悄返回一条不喝药的路线。 */
    @Test
    void requireAtLeastOneRejectsEveryPotionFreeRoute() {
        assertThrows(PotionPolicyUnsatisfiedException.class,
                () -> select(params(SolverPotionPolicy.REQUIRE_AT_LEAST_ONE), base()));
    }

    // ---------------------------------------------------------------- 夹具

    private static FinalPlanOrdering.Selection select(Spec... specs) {
        return select(params(SolverPotionPolicy.SMART), specs);
    }

    private static FinalPlanOrdering.Selection select(FinalPlanOrdering.Params params, Spec... specs) {
        List<FinalPlanOrdering.Candidate> candidates = new ArrayList<FinalPlanOrdering.Candidate>();
        for (Spec spec : specs) {
            candidates.add(spec.build());
        }
        return FinalPlanOrdering.select(candidates, params);
    }

    private static FinalPlanOrdering.Params params(SolverPotionPolicy policy) {
        return params(policy, FinalPlanOrdering.TheftPolicy.PRESERVE_RESOURCES);
    }

    private static FinalPlanOrdering.Params params(SolverPotionPolicy policy,
                                                   FinalPlanOrdering.TheftPolicy theft) {
        return new FinalPlanOrdering.Params(80, 80, 0, 0, policy, true, theft, 0,
                PostCombatRelicHeal.none(), null);
    }

    private static FinalPlanOrdering.CandidateResult winner(FinalPlanOrdering.Selection selection) {
        return selection.results.get(selection.selectedIndex);
    }

    private static FinalPlanOrdering.CandidateResult result(FinalPlanOrdering.Selection selection, Spec spec) {
        for (FinalPlanOrdering.CandidateResult candidate : selection.results) {
            if (candidate.candidate.label.equals(spec.label)) {
                return candidate;
            }
        }
        throw new AssertionError("没找到候选 " + spec.label);
    }

    private static List<String> labels(FinalPlanOrdering.Selection selection) {
        List<String> out = new ArrayList<String>();
        for (FinalPlanOrdering.CandidateResult candidate : selection.results) {
            out.add(candidate.candidate.label);
        }
        return out;
    }

    private static Spec named(String label) {
        Spec spec = base();
        spec.label = label;
        return spec;
    }

    /** 每个键都最优的基准候选。 */
    private static Spec base() {
        return new Spec();
    }

    private static Spec worseOn(int key) {
        Spec s = base();
        switch (key) {
            case 0:
                s.allEnemiesDead = false;            // 没赢
                break;
            case 2:
                s.deathSaveUseCount = 1;
                break;
            case 3:
                s.outstandingStolenResource = 15;
                break;
            case 4:
                s.cumulativeHpLost = 11;             // 战略赤字 10 -> 11
                break;
            case 5:
                s.growthHpCredit = 0;                // 降序：越小越差
                break;
            case 6:
                s.growthRewardCount = 0;
                break;
            case 7:
                s.combatEndedTurn = 9;
                break;
            case 9:
                s.playerHp = 45;                     // 30 -> 35
                break;
            case 10:
                s.longTermResourceValue = 0;         // 降序：越小越差
                break;
            case 11:
                s.angerCopiesGenerated = 1;
                break;
            case 12:
                s.boundary = SearchBoundaryReason.NO_CARDS;
                break;
            case 13:
                s.potionCount = 1;                   // 可选药 0 -> 1（成本 0，仍然合格）
                break;
            case 14:
                s.futureSoldHp = 5;
                break;
            case 15:
                s.enemyHp = 30;
                break;
            case 16:
                s.score = 90;                        // 降序
                break;
            case 17:
                s.actionCount = 6;
                break;
            default:
                throw new IllegalArgumentException("key " + key);
        }
        s.label = "worse-" + key;
        return s;
    }

    /** 可读的候选构造器（测试专用；生产侧只有不可变的 {@code Candidate}）。 */
    private static final class Spec {
        private static int sequence;
        String label = "c" + (++sequence);
        boolean allEnemiesDead = true;
        int projectedPlayerHp = 20;
        int playerHp = 50;
        int playerMaxHp = 80;
        int cumulativeHpLost = 10;
        int recoveredPlayerHp = 0;
        int deathSaveHpRestored = 0;
        int longTermResourceValue = 20;   // 降序键
        int angerCopiesGenerated = 0;
        int enemyHp = 0;
        int outstandingStolenResource = 0;
        int potionCount = 0;
        int automaticPotionUseCount = 0;
        int explicitPotionStrategicCost = 0;
        int potionStrategicCost = 0;
        int futureSoldHp = 0;
        int actionCount = 5;
        double score = 100;
        SearchBoundaryReason boundary = SearchBoundaryReason.NONE;
        boolean playerDead = false;
        int deathSaveUseCount = 0;
        int strategicHpCredit = 0;
        int growthHpCredit = 5;          // 降序键：base 持有更好的值
        int growthRewardCount = 1;
        int combatEndedTurn = 8;
        int forcedUseCount = 0;
        boolean forcedUsesSatisfied = true;
        SolverPotionPolicy policy = SolverPotionPolicy.SMART;

        FinalPlanOrdering.Candidate build() {
            SearchFeatures features = SearchFeatures.builder()
                    .allEnemiesDead(allEnemiesDead)
                    .projectedPlayerHp(projectedPlayerHp)
                    .playerHp(playerHp)
                    .playerMaxHp(playerMaxHp)
                    .cumulativePlayerHpLost(cumulativeHpLost)
                    .recoveredPlayerHp(recoveredPlayerHp)
                    .deathSaveHpRestored(deathSaveHpRestored)
                    .longTermResourceValue(longTermResourceValue)
                    .angerCopiesGenerated(angerCopiesGenerated)
                    .enemyHp(enemyHp)
                    .outstandingStolenResource(outstandingStolenResource)
                    .potionCount(potionCount)
                    .futureSoldHp(futureSoldHp)
                    .actionCount(actionCount)
                    .score(score)
                    .boundaryReason(boundary)
                    .build();
            RouteSnapshot snapshot = RouteSnapshot.builder()
                    .playerDead(playerDead)
                    .deathSaveUseCount(deathSaveUseCount)
                    .strategicHpCredit(strategicHpCredit)
                    .strategyGoalHpCredit(growthHpCredit)
                    .strategyGoalCount(growthRewardCount)
                    .combatEndedTurn(combatEndedTurn)
                    .automaticPotionUseCount(automaticPotionUseCount)
                    .explicitPotionStrategicCost(explicitPotionStrategicCost)
                    .potionStrategicCost(potionStrategicCost)
                    .build();
            return new FinalPlanOrdering.Candidate(label, features, snapshot,
                    forcedUseCount, forcedUsesSatisfied);
        }

        boolean won() {
            return actionCount > 0 && allEnemiesDead && !playerDead && projectedPlayerHp > 0;
        }

        boolean survives() {
            return !playerDead && projectedPlayerHp > 0;
        }

        int notWonNotSurvives() {
            return !won() && !survives() ? 1 : 0;
        }

        int explicitPotionCount() {
            return Math.max(0, potionCount - automaticPotionUseCount);
        }

        int optionalPotionCount() {
            return Math.max(0, explicitPotionCount() - forcedUseCount);
        }

        int strategicHpDeficit() {
            int maxHpDeficit = Math.max(0, 80 - playerMaxHp);
            int recovered = Math.max(0, recoveredPlayerHp - deathSaveHpRestored);
            int premium = deathSaveHpRestored * 900 / 100;
            return cumulativeHpLost + maxHpDeficit - recovered + premium - strategicHpCredit;
        }

        int policyHpDeficit() {
            boolean require = policy == SolverPotionPolicy.REQUIRE_AT_LEAST_ONE && forcedUseCount == 0;
            return strategicHpDeficit() + (require
                    ? Math.max(0, explicitPotionStrategicCost - 9) : 0);
        }

        int healthResourceCost() {
            return 80 - playerHp + 80 - playerMaxHp;
        }

        /** 第一个不同的键下标；完全相同返回 18。 */
        int firstDifference(Spec o) {
            if (won() != o.won()) {
                return 0;
            }
            if (notWonNotSurvives() != o.notWonNotSurvives()) {
                return 1;
            }
            if (deathSaveUseCount != o.deathSaveUseCount) {
                return 2;
            }
            if (outstandingStolenResource != o.outstandingStolenResource) {
                return 3;
            }
            if (strategicHpDeficit() != o.strategicHpDeficit()) {
                return 4;
            }
            if (growthHpCredit != o.growthHpCredit) {
                return 5;
            }
            if (growthRewardCount != o.growthRewardCount) {
                return 6;
            }
            if (combatEndedTurn != o.combatEndedTurn) {
                return 7;
            }
            if (policyHpDeficit() != o.policyHpDeficit()) {
                return 8;
            }
            if (healthResourceCost() != o.healthResourceCost()) {
                return 9;
            }
            if (longTermResourceValue != o.longTermResourceValue) {
                return 10;
            }
            if (angerCopiesGenerated != o.angerCopiesGenerated) {
                return 11;
            }
            if (boundary != o.boundary) {
                return 12;
            }
            if (optionalPotionCount() != o.optionalPotionCount()) {
                return 13;
            }
            if (futureSoldHp != o.futureSoldHp) {
                return 14;
            }
            if (enemyHp != o.enemyHp) {
                return 15;
            }
            if (score != o.score) {
                return 16;
            }
            if (actionCount != o.actionCount) {
                return 17;
            }
            return FinalPlanOrdering.KEY_COUNT;
        }

        boolean sameThrough(Spec other, int keyCount) {
            return firstDifference(other) >= keyCount;
        }

        boolean differsAt(Spec other, int key) {
            return firstDifference(other) == key;
        }
    }
}
