package io.github.youqing.sts1solver.mirror;

import io.github.youqing.sts1solver.sim.CombatFlow;
import io.github.youqing.sts1solver.sim.RngStream;
import io.github.youqing.sts1solver.sim.SimCard;
import io.github.youqing.sts1solver.sim.SimMonster;
import io.github.youqing.sts1solver.sim.SimState;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-11-02 真机失配的三个回归点（每个都直接对应一条日志）。
 *
 * <p>这三条之所以值得单独一个类：它们的共同点是「影子与实机的**副产物**不同」——
 * 少走一格 RNG、少挂一个能力、多算一个统计量。它们不会报错，只会让
 * {@code DEPLOY_REPLAN} 每隔几个动作就来一次。
 */
class MismatchRegressionTest {

    // ------------------------------------------------- 1. 疯狂：整手牌均匀抽 + 抽到能降为止

    /**
     * 真机原文：{@code hand(unordered)[0]: Inflame+(1) vs Inflame+(0)}、
     * {@code hand[4]: Wound(0) vs Wound(-2)}、{@code rng.CARD … 1 vs 2}。
     *
     * <p>原版 {@code MadnessAction.findAndModifyCard} 是「整手牌均匀抽，抽到不能降的就**再抽一次**」，
     * 所以：① 伤口不许被降成 0 费；② 落在伤口上的那一次 RNG 必须被消耗掉（否则后面整条时间线错位）；
     * ③ 降费要同时写 {@code cost} 与 {@code costForTurn}（否则换堆之后又变回原价）。
     */
    @Test
    void madnessNeverCheapensAnUnplayableCard() {
        // RngStream 是 final 且用真实 LCG：不能脚本化，改成**扫种子**。
        // 手上只有 [伤口, 热烈+] 时，`random(1)` 落在伤口上就会触发原版的「重抽」分支 ——
        // 扫到至少一次这种情况，才证明重抽真的实现了。
        int woundFirst = 0;
        for (int seed = 0; seed < 200; seed++) {
            SimState state = combat();
            state.hand.clear();
            state.hand.add(new SimCard("Wound", false, -2, -1, "STATUS"));
            state.hand.add(new SimCard("Inflame", true, 1, 0, "POWER"));
            state.hand.add(new SimCard("Madness", false, 0, 0, "SKILL"));
            state.rng.put(SimState.RngKind.CARD,
                    io.github.youqing.sts1solver.sim.RngStream.from(seed * 31L + 7L, seed * 17L + 3L, 0));

            CardPlayer.play(state, 2, -1);        // 打出疯狂 */


            assertEquals(-2, state.hand.get(0).cost,
                    "打不出的状态牌永远不许被降成 0 费（seed " + seed + "）");
            assertEquals("Wound", state.hand.get(0).id);
            int counter = state.rng.get(SimState.RngKind.CARD).counter();
            assertTrue(counter >= 1, "原版每次尝试都要真的消耗一次 RNG（seed " + seed + "）");

            SimCard inflame = state.hand.get(1);
            if (inflame.cost == 0) {
                assertEquals(0, inflame.costForTurn,
                        "降费必须同时写 cost 与 costForTurn（seed " + seed + "）");
                if (counter >= 2) {
                    woundFirst++;                 // 第一抽落在伤口上，重抽才成功
                }
            }
        }
        assertTrue(woundFirst > 0,
                "200 个种子里必须至少有一次「第一抽落在伤口上」——否则重抽分支根本没被覆盖");
    }

    /** 手上一张能降的牌都没有：原版这一帧直接 return，**一次 RNG 都不掷**。 */
    @Test
    void madnessDrawsNothingWhenNoCardCanGetCheaper() {
        SimState state = combat();
        state.hand.clear();
        state.hand.add(new SimCard("Wound", false, -2, -1, "STATUS"));
        state.hand.add(new SimCard("Madness", false, 0, 0, "SKILL"));
        state.rng.put(SimState.RngKind.CARD,
                io.github.youqing.sts1solver.sim.RngStream.from(99L, 77L, 0));

        CardPlayer.play(state, 1, -1);            // 打出疯狂，手上只剩伤口

        assertEquals(0, state.rng.get(SimState.RngKind.CARD).counter(),
                "无事可做时掷色子 = 与实机错位");
        assertEquals(-2, state.hand.get(0).cost);
    }

    // ------------------------------------------------- 2. 黑暗镣铐：力量要还回去

    /**
     * 真机原文：{@code monsters[0].powers.Shackled: (missing) vs 9}。
     *
     * <p>原版 {@code DarkShackles.use}：{@code StrengthPower(-9)} +（没被神器吃掉时）
     * {@code GainStrengthPower(9)}，而 {@code GainStrengthPower} 的 ID 就是 {@code "Shackled"}，
     * 由 {@code atEndOfRound} 还原。影子旧实现只减力量、不挂 Shackled —— 于是它以为
     * 觉醒者整场都弱 9 点力量，比真实情况乐观一整个战斗。
     */
    @Test
    void darkShacklesLendsTheStrengthBackAtEndOfRound() {
        SimState state = combat();
        state.hand.clear();
        state.hand.add(new SimCard("Dark Shackles", false, 0, 0, "SKILL"));
        SimMonster boss = state.monsters.get(0);
        boss.powers.put(DamageCalc.STRENGTH, Integer.valueOf(10));

        CardPlayer.play(state, 0, 0);

        assertEquals(1, boss.powers.get(DamageCalc.STRENGTH).intValue(), "先减 9 点力量");
        assertEquals(9, boss.powers.get("Shackled").intValue(),
                "同时要挂 Shackled（GainStrengthPower 的 ID），实机会有这一项");

        CombatFlow.endTurn(state, 3, 5);

        assertEquals(10, boss.powers.get(DamageCalc.STRENGTH).intValue(),
                "回合结束时把 9 点力量还回去 —— 镣铐只压这一回合");
        assertFalse(boss.powers.containsKey("Shackled"), "还完就移除能力");
    }

    // ------------------------------------------------- 3. 累计掉血/回血不进对拍

    /**
     * 真机原文：{@code DIFF Bloodletting+ MISMATCH … [cumulativeHpLost: 3 vs 0]}`。
     *
     * <p>原版只有 {@code damagedThisCombat}（被打了几**次**，javap 是 {@code iadd 1}），
     * 没有「整场掉了多少血」的计数 —— 真机侧永远给 0。把它放进对拍的结果是：
     * **任何一次掉血或回血都必然失配**，于是每几个动作就 `DEPLOY_REPLAN` 一次。
     */
    @Test
    void battleHpTotalsAreNotPartOfTheShadowDiff() {
        SimState live = combat();
        SimState predicted = live.copy();
        predicted.cumulativeHpLost = 41;
        predicted.recoveredPlayerHp = 7;

        List<String> differences = predicted.diff(live);
        for (String difference : differences) {
            assertFalse(difference.startsWith("cumulativeHpLost"),
                    "真机侧观测不到，不许当成失配：" + differences);
            assertFalse(difference.startsWith("recoveredPlayerHp"),
                    "真机侧观测不到，不许当成失配：" + differences);
        }
        assertTrue(differences.isEmpty(), "只差这两个统计量时应当判 MATCH：" + differences);
    }

    // ---------------------------------------------------------------- 夹具

    private static SimState combat() {
        SimState state = new SimState();
        state.turn = 1;
        state.hp = 80;
        state.maxHp = 80;
        state.energy = 3;
        state.energyPerTurn = 3;
        SimMonster monster = new SimMonster("TestMonster", 200, 200, 0,
                new TreeMap<String, Integer>());
        state.monsters.add(monster);
        return state;
    }

}
