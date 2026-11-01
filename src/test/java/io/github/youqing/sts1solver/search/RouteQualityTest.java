package io.github.youqing.sts1solver.search;

import io.github.youqing.sts1solver.mirror.PostCombatRelicHeal;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 路线质量模型：蓝本 {@code Search/RouteQuality.cs} 与 {@code ActEndingBossPolicy} 的纯数据部分。
 *
 * <p>为什么单独测：{@link RouteQuality} 被**四个**比较器共用（最终选择、搜索中临时选择、
 * 药水资格、Beam 保留）。共用的东西一旦有一处口径漂了，会同时污染四处，
 * 而症状只是「牌打得怪」—— 没有任何异常。
 */
class RouteQualityTest {

    /** {@code primary} 只带主要轴，其余必须是 0 / null（蓝本就是这么构造的）。 */
    @Test
    void primaryCarriesOnlyTheMainAxes() {
        RouteQuality quality = RouteQuality.primary(true, 12, Integer.valueOf(7), 4, 2, 1);

        assertTrue(quality.won);
        assertEquals(false, quality.survives, "基线比较不看 Survives，构造时就是 false");
        assertEquals(12, quality.strategicHpDeficit);
        assertEquals(Integer.valueOf(7), quality.combatEndedTurn);
        assertEquals(4, quality.growthHpCredit);
        assertEquals(2, quality.growthRewardCount);
        assertEquals(1, quality.deathSaveUseCount);
        assertEquals(0, quality.outstandingStolenResource);
        assertEquals(0, quality.enemyHp);
        assertEquals(0d, quality.score);
    }

    /** {@code forRetention} 只承载 Beam 保留用的两项。 */
    @Test
    void retentionCarriesOnlyHealthRiskAndPotionCost() {
        RouteQuality quality = RouteQuality.forRetention(1234L, 18);

        assertEquals(1234L, quality.retentionHealthRisk);
        assertEquals(18, quality.potionStrategicCost);
        assertEquals(false, quality.won);
        assertNull(quality.combatEndedTurn);
    }

    /**
     * 第 13 键的五档，以及**两个不给档位**的枚举。
     *
     * <p>蓝本 {@code PolicyBoundaryRank} 的 {@code default} 分支是 {@code throw}，
     * 所以 {@code DynamicResolution} 与 {@code MemoryNoProgress} 必须显式失败 ——
     * 静默给它们一个排名就是自造语义，而这一键排在第 13 位、会真的改变选哪条路线。
     */
    @Test
    void policyBoundaryRankHasFiveBandsAndRejectsUnmappedReasons() {
        assertEquals(0, FinalPlanOrdering.policyBoundaryRank(SearchBoundaryReason.NONE));

        assertEquals(1, FinalPlanOrdering.policyBoundaryRank(SearchBoundaryReason.NO_CARDS));
        assertEquals(1, FinalPlanOrdering.policyBoundaryRank(SearchBoundaryReason.SHUFFLE));
        assertEquals(1, FinalPlanOrdering.policyBoundaryRank(SearchBoundaryReason.TURN_LIMIT));
        assertEquals(1, FinalPlanOrdering.policyBoundaryRank(SearchBoundaryReason.NODE_LIMIT));
        assertEquals(1, FinalPlanOrdering.policyBoundaryRank(SearchBoundaryReason.TIME_LIMIT));

        assertEquals(2, FinalPlanOrdering.policyBoundaryRank(SearchBoundaryReason.PENDING_CHOICE));
        assertEquals(3, FinalPlanOrdering.policyBoundaryRank(SearchBoundaryReason.UNSUPPORTED_EFFECT));
        assertEquals(4, FinalPlanOrdering.policyBoundaryRank(SearchBoundaryReason.EVENT_DEFEAT));

        assertThrows(IllegalArgumentException.class,
                () -> FinalPlanOrdering.policyBoundaryRank(SearchBoundaryReason.DYNAMIC_RESOLUTION));
        assertThrows(IllegalArgumentException.class,
                () -> FinalPlanOrdering.policyBoundaryRank(SearchBoundaryReason.MEMORY_NO_PROGRESS));
    }

    /**
     * 战略赤字 = 累计掉血 + 最大生命亏空 − 挣回来的回血 + 死亡豁免溢价 − 战略信用。
     *
     * <p>死亡豁免恢复的那部分**要**从回血里剔掉：它是拿一次性资源换的，不是这条路线挣的。
     */
    @Test
    void strategicHpDeficitFollowsTheBlueprintFormula() {
        // 掉 30 血、没回血、最大生命没变 → 30
        assertEquals(30, deficit(30, 0, 0, 80, 0));

        // 最大生命从 80 掉到 75 → 再加 5
        assertEquals(35, deficit(30, 0, 0, 75, 0));

        // 掉了 30 但回了 10 → 20
        assertEquals(20, deficit(30, 10, 0, 80, 0));

        // 最大生命涨到 90：亏空按 max(0, initial - final)，涨上去**不**算负亏空
        assertEquals(30, deficit(30, 0, 0, 90, 0));

        // 死亡豁免回了 10：那 10 点不算挣来的回血（先剔出去），再按 9 倍溢价计
        assertEquals(30 + 90, deficit(30, 10, 10, 80, 0));

        // 战略信用（拿血换来的成长）直接抵掉
        assertEquals(25, deficit(30, 0, 0, 80, 5));
    }

    /** 完整胜利的四个必要条件，缺一不可。 */
    @Test
    void completeVictoryNeedsAllFourConditions() {
        assertTrue(FinalPlanOrdering.isCompleteVictory(1, true, false, 1));
        org.junit.jupiter.api.Assertions.assertFalse(
                FinalPlanOrdering.isCompleteVictory(0, true, false, 20), "没动作不算打完");
        org.junit.jupiter.api.Assertions.assertFalse(
                FinalPlanOrdering.isCompleteVictory(5, false, false, 20), "还有敌人活着");
        org.junit.jupiter.api.Assertions.assertFalse(
                FinalPlanOrdering.isCompleteVictory(5, true, true, 20), "玩家死了");
        org.junit.jupiter.api.Assertions.assertFalse(
                FinalPlanOrdering.isCompleteVictory(5, true, false, 0), "投影血量必须是正的");
    }

    /** 有效档位：Require 被强制药满足后降 Smart；Disabled 但还有可选药时也按 Smart 判。 */
    @Test
    void effectivePolicyDowngradesExactlyLikeTheBlueprint() {
        assertEquals(SolverPotionPolicy.SMART,
                FinalPlanOrdering.effectivePotionPolicy(
                        SolverPotionPolicy.REQUIRE_AT_LEAST_ONE, 1, 0));
        assertEquals(SolverPotionPolicy.REQUIRE_AT_LEAST_ONE,
                FinalPlanOrdering.effectivePotionPolicy(
                        SolverPotionPolicy.REQUIRE_AT_LEAST_ONE, 0, 0));
        assertEquals(SolverPotionPolicy.SMART,
                FinalPlanOrdering.effectivePotionPolicy(SolverPotionPolicy.DISABLED, 0, 1));
        assertEquals(SolverPotionPolicy.DISABLED,
                FinalPlanOrdering.effectivePotionPolicy(SolverPotionPolicy.DISABLED, 0, 0));
        assertEquals(SolverPotionPolicy.SMART,
                FinalPlanOrdering.effectivePotionPolicy(SolverPotionPolicy.SMART, 1, 1));
    }

    private static int deficit(int hpLost, int recovered, int deathSaveRestored, int finalMaxHp,
                               int strategicCredit) {
        SearchFeatures features = SearchFeatures.builder()
                .playerHp(50)
                .playerMaxHp(finalMaxHp)
                .cumulativePlayerHpLost(hpLost)
                .recoveredPlayerHp(recovered)
                .deathSaveHpRestored(deathSaveRestored)
                .build();
        RouteSnapshot snapshot = RouteSnapshot.builder()
                .strategicHpCredit(strategicCredit)
                .build();
        FinalPlanOrdering.Params params = new FinalPlanOrdering.Params(
                80, 80, 0, 0, SolverPotionPolicy.SMART, true,
                FinalPlanOrdering.TheftPolicy.PRESERVE_RESOURCES, 0,
                PostCombatRelicHeal.none(), null);
        // 不传 completeVictory：这里测的是「没打赢时没有战后回血」的分支
        return FinalPlanOrdering.strategicHpDeficit(features, snapshot, params, false);
    }
}
