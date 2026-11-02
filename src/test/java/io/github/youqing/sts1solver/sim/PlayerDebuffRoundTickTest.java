package io.github.youqing.sts1solver.sim;

import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 玩家减益的**轮末递减**（原版 {@code WeakPower / VulnerablePower / FrailPower.atEndOfRound}）。
 *
 * <p>javap 核实的三条分支：
 * <pre>
 * if (justApplied) { justApplied = false; return; }     // 刚挂上的这一轮不减
 * if (amount == 0) RemoveSpecificPowerAction
 * else             ReducePowerAction(1)
 * </pre>
 *
 * <p>以前整个玩家侧轮末递减都不存在：易伤挂 3 层就永远是 3 层，影子会一直认为
 * 所有攻击都吃 1.5 倍 —— 那是系统性地高估伤害，而且每一回合都错。
 */
class PlayerDebuffRoundTickTest {

    private static SimState fight() {
        SimState s = new SimState();
        s.hp = 80;
        s.maxHp = 80;
        s.turn = 1;
        s.energy = 3;
        s.energyPerTurn = 3;
        s.draw.add(new SimCard("Strike_R", false, 1, 6, "ATTACK"));
        s.monsters.add(new SimMonster("AcidSlime_S", 40, 40, 0,
                Collections.<String, Integer>emptyMap()));
        s.rng.put(SimState.RngKind.CARD, RngStream.from(7L, 11L, 0));
        return s;
    }

    /** 3 层易伤：这一轮结束掉到 2 层。 */
    @Test
    void vulnerableTicksDownAtEndOfRound() {
        SimState s = fight();
        s.playerPowers.put("Vulnerable", Integer.valueOf(3));

        CombatFlow.endTurn(s, 3, 5);

        assertEquals(2, intOf(s.playerPowers.get("Vulnerable")));
    }

    /** 1 层：减到 0 就整条移除（原版 RemoveSpecificPowerAction）。 */
    @Test
    void lastStackRemovesThePowerEntirely() {
        SimState s = fight();
        s.playerPowers.put("Frail", Integer.valueOf(1));

        CombatFlow.endTurn(s, 3, 5);

        assertFalse(s.playerPowers.containsKey("Frail"), "0 层必须移除，不能留一个 0");
    }

    /** 刚挂上的那一轮只清标志、不减层（原版 justApplied 保护）。 */
    @Test
    void justAppliedStackSurvivesTheFirstRound() {
        SimState s = fight();
        s.playerPowers.put("Vulnerable", Integer.valueOf(3));
        s.powerAux.put(CombatFlow.JUST_APPLIED_PREFIX + "Vulnerable", Integer.valueOf(1));

        CombatFlow.endTurn(s, 3, 5);

        assertEquals(3, intOf(s.playerPowers.get("Vulnerable")),
                "刚挂上的一轮不递减");
        assertFalse(s.powerAux.containsKey(CombatFlow.JUST_APPLIED_PREFIX + "Vulnerable"),
                "标志必须被消费掉，否则下一轮又不减");
    }

    /** 真正施加减益时应当自动打上 justApplied（走 addPlayerDebuff 的唯一入口）。 */
    @Test
    void applyingADebuffMarksItJustApplied() {
        SimState s = fight();

        CombatFlow.addPlayerDebuff(s, "Weakened", 2);

        assertEquals(2, intOf(s.playerPowers.get("Weakened")));
        assertTrue(s.powerAux.containsKey(CombatFlow.JUST_APPLIED_PREFIX + "Weakened"));
    }

    /** 力量/敏捷这类**永久**修正不该被轮末递减（它们不在三条名单里）。 */
    @Test
    void strengthIsNotTickedAtEndOfRound() {
        SimState s = fight();
        s.playerPowers.put("Strength", Integer.valueOf(5));

        CombatFlow.endTurn(s, 3, 5);

        assertEquals(5, intOf(s.playerPowers.get("Strength")));
    }

    private static int intOf(Integer value) {
        return value == null ? 0 : value.intValue();
    }
}
