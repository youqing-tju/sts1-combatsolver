package io.github.youqing.sts1solver.search;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 全局档位的**资格判定**（蓝本 {@code PotionUsePolicy.IsEligible}）。
 *
 * <p>三段语义必须分开：
 * <ul>
 *   <li>{@code DISABLED} —— 一瓶都不许喝，Force 也压不过；</li>
 *   <li>{@code SMART} —— 可以不喝；喝就必须「买到命」或「买到胜利」；</li>
 *   <li>{@code REQUIRE_AT_LEAST_ONE} —— 至少一瓶，而且第二瓶起还要额外买到血。</li>
 * </ul>
 */
class PotionUsePolicyTest {

    @Test
    void disabledRejectsAnyExplicitUse() {
        assertTrue(PotionUsePolicy.isEligible(SolverPotionPolicy.DISABLED, 0,
                false, false, false, 0, 9));
        assertFalse(PotionUsePolicy.isEligible(SolverPotionPolicy.DISABLED, 1,
                true, true, true, 999, 9), "一瓶都不许喝，救不救命都一样");
    }

    @Test
    void smartAllowsZeroUses() {
        assertTrue(PotionUsePolicy.isEligible(SolverPotionPolicy.SMART, 0,
                false, true, true, 0, 9), "不喝药永远合法");
    }

    @Test
    void smartAllowsPotionOnlyWin() {
        assertTrue(PotionUsePolicy.isEligible(SolverPotionPolicy.SMART, 1,
                true, false, true, 0, 18),
                "不喝就输、喝了能赢 —— 药水买到的是整场胜利，不需要血量算术");
        assertFalse(PotionUsePolicy.isEligible(SolverPotionPolicy.SMART, 1,
                true, true, true, 8, 9),
                "两边都能赢、又只省下 8 血（成本 9）-> 不值得喝");
    }

    @Test
    void smartRequiresHpSavedToCoverCost() {
        assertTrue(PotionUsePolicy.isEligible(SolverPotionPolicy.SMART, 1,
                true, true, true, 9, 9), "刚好买回成本就允许");
        assertTrue(PotionUsePolicy.isEligible(SolverPotionPolicy.SMART, 1,
                true, true, true, 10, 9));
        assertFalse(PotionUsePolicy.isEligible(SolverPotionPolicy.SMART, 1,
                true, true, true, 0, 9));
    }

    @Test
    void requireAtLeastOneNeedsAtLeastOneUse() {
        assertFalse(PotionUsePolicy.isEligible(SolverPotionPolicy.REQUIRE_AT_LEAST_ONE, 0,
                false, true, true, 0, 9), "一瓶都没喝，直接违反档位");
        assertTrue(PotionUsePolicy.isEligible(SolverPotionPolicy.REQUIRE_AT_LEAST_ONE, 1,
                true, true, true, 0, 9), "第一瓶不额外要求血量");
    }

    @Test
    void requireAtLeastOneSecondPotionNeedsExtraHpSaved() {
        int cost = 18;      // 高价值药水（水果汁/赌徒酿造那一档）
        int extra = PotionUsePolicy.additionalRequiredUseStrategicHpCost(cost);
        assertEquals(9, extra, "额外门槛 = 战略成本 - 一瓶的基准 9");

        assertFalse(PotionUsePolicy.isEligible(SolverPotionPolicy.REQUIRE_AT_LEAST_ONE, 2,
                true, true, true, extra - 1, cost));
        assertTrue(PotionUsePolicy.isEligible(SolverPotionPolicy.REQUIRE_AT_LEAST_ONE, 2,
                true, true, true, extra, cost));
    }

    @Test
    void requireAtLeastOneStillRejectsLosingPotionRoute() {
        assertFalse(PotionUsePolicy.isEligible(SolverPotionPolicy.REQUIRE_AT_LEAST_ONE, 1,
                true, true, false, 999, 9),
                "有条路线能赢、喝药那条反而输：不许为了满足档位去送死");
    }

    @Test
    void requiredHpSavedScalesWithPotionCount() {
        assertEquals(9, PotionUsePolicy.requiredHpSaved(1));
        assertEquals(18, PotionUsePolicy.requiredHpSaved(2));
        assertEquals(0, PotionUsePolicy.requiredHpSaved(0));
    }
}
