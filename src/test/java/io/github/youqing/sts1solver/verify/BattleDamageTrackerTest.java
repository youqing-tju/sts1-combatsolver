package io.github.youqing.sts1solver.verify;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 战斗内「已承诺卖血」记账：蓝本 {@code Runtime/BattleDamageTracker.cs:7-128}。
 *
 * <p>它存在的唯一理由：**跨回合重搜不能忘账**。每回合重新搜索时卖血计数从零开始，
 * 于是上一回合已经卖掉的 12 点血被当成没发生过，路线会一直再卖一遍。
 *
 * <p>纯数据：{@link BattleDamageTracker#observe(int, int, int)} 由主线程喂真实血量，
 * 单测里直接喂数字，不需要真机对象。
 */
class BattleDamageTrackerTest {

    @BeforeEach
    void setUp() {
        BattleDamageTracker.reset();
    }

    /** 只累计**往下掉**的部分；回血不会把它冲掉。 */
    @Test
    void observesOnlyDownwardHpChanges() {
        BattleDamageTracker.begin(80);

        BattleDamageTracker.Snapshot first = BattleDamageTracker.observe(70, 1, 0);
        assertEquals(10, first.hpLostSoFar);

        BattleDamageTracker.Snapshot healed = BattleDamageTracker.observe(75, 1, 0);
        assertEquals(10, healed.hpLostSoFar, "回血不抵消已经掉过的");
        assertEquals(5, healed.hpRecoveredOrGainedSoFar, "10 掉血 + 75 - 80 = 5");

        BattleDamageTracker.Snapshot again = BattleDamageTracker.observe(65, 2, 0);
        assertEquals(20, again.hpLostSoFar, "10 + 10 = 20");
    }

    /** 回合翻页时结算 {@code min(计划卖血, 实际掉血)}。 */
    @Test
    void commitsTheSmallerOfPlannedAndActualLoss() {
        BattleDamageTracker.begin(80);
        BattleDamageTracker.observe(80, 1, 0);
        BattleDamageTracker.registerPlan(1, 12, 80);

        BattleDamageTracker.Snapshot committed = BattleDamageTracker.observe(68, 2, 0);
        assertEquals(12, committed.soldHpCommitted, "计划 12、实际 12 -> 记 12");
    }

    /** 实际掉得比计划少 -> 只按实际记，不超额。 */
    @Test
    void doesNotOverchargeWhenTheTurnWasCheaper() {
        BattleDamageTracker.begin(80);
        BattleDamageTracker.observe(80, 1, 0);
        BattleDamageTracker.registerPlan(1, 20, 80);

        BattleDamageTracker.Snapshot committed = BattleDamageTracker.observe(74, 2, 0);
        assertEquals(6, committed.soldHpCommitted, "计划 20、实际 6 -> 只记 6");
    }

    /** 同一回合内的多次观察不许重复结算。 */
    @Test
    void commitsOnlyOncePerTurn() {
        BattleDamageTracker.begin(80);
        BattleDamageTracker.observe(80, 1, 0);
        BattleDamageTracker.registerPlan(1, 10, 80);

        assertEquals(10, BattleDamageTracker.observe(70, 2, 0).soldHpCommitted);
        assertEquals(10, BattleDamageTracker.observe(70, 2, 0).soldHpCommitted,
                "回合没变就不该再结算一次");
    }

    /** 药水数只增不减（腰带上的空格不会让计数回退）。 */
    @Test
    void potionCountNeverDecreases() {
        BattleDamageTracker.begin(80);
        assertEquals(2, BattleDamageTracker.observe(80, 1, 2).potionsUsedSoFar);
        assertEquals(2, BattleDamageTracker.observe(80, 1, 1).potionsUsedSoFar);
    }

    /** 新战斗清零；未 begin 时第一次 observe 会自动记起点。 */
    @Test
    void resetClearsEverythingAndObserveBootstraps() {
        BattleDamageTracker.begin(80);
        BattleDamageTracker.observe(60, 1, 1);
        BattleDamageTracker.registerPlan(1, 5, 60);

        BattleDamageTracker.reset();
        BattleDamageTracker.Snapshot fresh = BattleDamageTracker.observe(50, 1, 0);
        assertEquals(0, fresh.hpLostSoFar, "这一场才刚开始，没有历史掉血");
        assertEquals(0, fresh.soldHpCommitted);
        assertEquals(0, fresh.potionsUsedSoFar);
    }

    /** 展示口径：净回血 = 累计掉血 + 当前血 − 起始血。 */
    @Test
    void recoveredDisplayIsNetOfLossAndGain() {
        assertEquals(5, BattleDamageTracker.recoveredOrGainedForDisplay(80, 75, 10));
        assertEquals(0, BattleDamageTracker.recoveredOrGainedForDisplay(80, 80, 0));
        assertEquals(0, BattleDamageTracker.recoveredOrGainedForDisplay(80, 60, 0),
                "纯掉血时没有「回血」可言");
    }
}
