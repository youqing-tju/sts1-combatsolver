package io.github.youqing.sts1solver.mirror;

import io.github.youqing.sts1solver.sim.SimCard;
import io.github.youqing.sts1solver.sim.SimMonster;
import io.github.youqing.sts1solver.sim.SimState;
import org.junit.jupiter.api.Test;

import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 消耗牌要触发的能力（2026-11-02 真机：`hand.size: 5 vs 9`、`draw.size: 13 vs 9`）。
 *
 * <p>{@code Dark Embrace} / {@code Feel No Pain} 以前只有「写进 powers 表」这一半，
 * 没有任何人在消耗时读它们 —— 对拍里 powers 一致，手牌与抽牌堆却一路漂，
 * 于是每次「消耗完再比」都判失配 → 重算。
 */
class ExhaustPowerTest {

    @Test
    void darkEmbraceDrawsOnEveryExhaust() {
        SimState state = combat();
        state.playerPowers.put("Dark Embrace", Integer.valueOf(1));
        for (int i = 0; i < 5; i++) {
            state.draw.add(new SimCard("Strike_R", false, 1, 6, "ATTACK"));
        }

        Effects.exhaustCard(state, new SimCard("Defend_R", false, 1, 0, "SKILL"));

        assertEquals(1, state.hand.size(), "每消耗一张牌抽 1（DarkEmbracePower.onExhaust）");
        assertEquals(4, state.draw.size());
    }

    @Test
    void feelNoPainGainsBlockOnEveryExhaust() {
        SimState state = combat();
        state.playerPowers.put("Feel No Pain", Integer.valueOf(3));

        Effects.exhaustCard(state, new SimCard("Defend_R", false, 1, 0, "SKILL"));

        assertEquals(3, state.block, "每消耗一张牌 +3 格挡（FeelNoPainPower.onExhaust）");
    }

    /** 两个能力都满级时同时生效，互不吞掉。 */
    @Test
    void bothExhaustPowersFireTogether() {
        SimState state = combat();
        state.playerPowers.put("Dark Embrace", Integer.valueOf(1));
        state.playerPowers.put("Feel No Pain", Integer.valueOf(4));
        state.draw.add(new SimCard("Strike_R", false, 1, 6, "ATTACK"));

        Effects.exhaustCard(state, new SimCard("Defend_R", false, 1, 0, "SKILL"));

        assertEquals(1, state.hand.size());
        assertEquals(4, state.block);
    }

    private static SimState combat() {
        SimState state = new SimState();
        state.turn = 1;
        state.hp = 80;
        state.maxHp = 80;
        state.energy = 3;
        state.energyPerTurn = 3;
        state.monsters.add(new SimMonster("TestMonster", 100, 100, 0,
                new TreeMap<String, Integer>()));
        return state;
    }
}
