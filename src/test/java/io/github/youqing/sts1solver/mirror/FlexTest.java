package io.github.youqing.sts1solver.mirror;

import io.github.youqing.sts1solver.sim.CombatFlow;
import io.github.youqing.sts1solver.sim.SimCard;
import io.github.youqing.sts1solver.sim.SimMonster;
import io.github.youqing.sts1solver.sim.SimState;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 活动肌肉（Flex）：+2 力量，**回合末必须还回去**。
 *
 * <p>2026-10-30 真机日志抓到：`playerPowers.Gain Strength: 2 vs (missing)`
 * 与 `playerPowers.Flex: (missing) vs 2`（sim vs live）。根因是能力 ID 写错：
 * <ul>
 *   <li>`Flex.use` 挂的是 `StrengthPower` + **`LoseStrengthPower`**，后者 {@code ID = "Flex"}`（javap 核实）；</li>
 *   <li>`GainStrengthPower.ID` 其实是 **"Shackled"**，跟 Flex 无关。</li>
 * </ul>
 * 写错的后果不是差一个字段，而是「回合末扣力量」的钩子永远匹配不到 →
 * **+2 力量变成永久**，这一整场战斗里每张攻击牌都多算 2 点伤害。
 */
class FlexTest {

    private static SimState combat() {
        SimState s = new SimState();
        s.hp = 80;
        s.maxHp = 80;
        s.energy = 3;
        s.turn = 1;
        s.hand.add(new SimCard("Flex", false, 0, -1, "SKILL"));
        s.hand.add(new SimCard("Strike_R", false, 1, 6, "ATTACK"));
        s.draw.add(new SimCard("Defend_R", false, 1, -1, "SKILL"));
        s.monsters.add(new SimMonster("Cultist", 60, 60, 0,
                Collections.<String, Integer>emptyMap()));
        return s;
    }

    @Test
    void flexGivesStrengthAndRemovesItAtEndOfTurn() {
        SimState s = combat();
        CardPlayer.play(s, 0, -1);

        assertEquals(Integer.valueOf(2), s.playerPowers.get(DamageCalc.STRENGTH), "力量 +2");
        assertEquals(Integer.valueOf(2), s.playerPowers.get("Flex"),
                "还债的能力 ID 必须是 Flex（LoseStrengthPower.POWER_ID）");
        assertFalse(s.playerPowers.containsKey("Gain Strength"),
                "原版没有这个 ID（GainStrengthPower 的 ID 是 Shackled）");

        // Flex 之后打一张打击：6 + 2 = 8
        int before = s.monsters.get(0).hp;
        CardPlayer.play(s, 0, 0);
        assertEquals(before - 8, s.monsters.get(0).hp, "力量要算进这一击");

        CombatFlow.endTurn(s, 3, 5);
        Integer strengthLeft = s.playerPowers.get(DamageCalc.STRENGTH);
        assertEquals(0, strengthLeft == null ? 0 : strengthLeft.intValue(),
                "回合末力量必须还回去（剩 0 或没有这个 key 都算对）");
        assertFalse(s.playerPowers.containsKey("Flex"), "还完债这个能力要消失");
    }
}
