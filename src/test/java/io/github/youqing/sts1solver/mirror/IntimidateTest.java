package io.github.youqing.sts1solver.mirror;

import org.junit.jupiter.api.Test;

import java.util.Collections;

import io.github.youqing.sts1solver.sim.SimCard;
import io.github.youqing.sts1solver.sim.SimMonster;
import io.github.youqing.sts1solver.sim.SimState;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 威吓（Intimidate，0 费技能：全体 1 层虚弱，消耗）。
 *
 * <p>真机 2026-10-30 20:35~20:36 三次「手里有威吓却结束回合」，对手全是**哨卫**——
 * 哨卫（{@code Sentry.usePreBattleAction}）自带 1 层人工制品，虚弱会被整层吃掉，
 * 打与不打血量完全一样。所以那三次不是镜像算错，而是排序把「平手」判给了结束回合
 * （见 {@code BeamSolver.better} 最后一条键）。
 *
 * <p>这里钉住镜像本身的两条：虚弱层数对得上；人工制品照规矩吃减益、牌照样消耗。
 */
class IntimidateTest {

    private static SimState combat(int artifact) {
        SimState s = new SimState();
        s.hp = 80;
        s.maxHp = 80;
        s.energy = 3;
        SimMonster sentry = new SimMonster("Sentry", 40, 40, 0,
                Collections.<String, Integer>emptyMap());
        if (artifact > 0) {
            sentry.powers.put("Artifact", Integer.valueOf(artifact));
        }
        s.monsters.add(sentry);
        s.hand.add(new SimCard("Intimidate", false, 0, 0, "SKILL"));
        return s;
    }

    /** 普通敌人：全体 1 层虚弱，自己进消耗堆，0 费不扣能量。 */
    @Test
    void intimidateWeakensAllAndExhausts() {
        SimState s = combat(0);
        CardPlayer.play(s, 0, -1);

        assertEquals(Integer.valueOf(1), s.monsters.get(0).powers.get(DamageCalc.WEAKENED),
                "威吓给 1 层虚弱");
        assertEquals(1, s.exhaust.size(), "威吓自带消耗");
        assertTrue(s.discard.isEmpty(), "不能进弃牌堆");
        assertEquals(3, s.energy, "0 费不扣能量");
    }

    /** 升级版给 2 层（卡面 UPGRADE_DESCRIPTION 为空 → 只改层数）。 */
    @Test
    void intimidatePlusWeakensTwo() {
        SimState s = combat(0);
        s.hand.set(0, new SimCard("Intimidate", true, 0, 0, "SKILL"));
        CardPlayer.play(s, 0, -1);

        assertEquals(Integer.valueOf(2), s.monsters.get(0).powers.get(DamageCalc.WEAKENED),
                "威吓+ 给 2 层虚弱");
    }

    /** 哨卫带 1 层人工制品：虚弱被吃掉，牌照样消耗（打出去真的没有收益）。 */
    @Test
    void artifactEatsTheWeak() {
        SimState s = combat(1);
        CardPlayer.play(s, 0, -1);

        assertFalse(s.monsters.get(0).powers.containsKey(DamageCalc.WEAKENED),
                "人工制品吃掉虚弱");
        assertFalse(s.monsters.get(0).powers.containsKey("Artifact"),
                "人工制品用掉一层就移除");
        assertEquals(1, s.exhaust.size(), "牌照样消耗");
    }
}
