package io.github.youqing.sts1solver.search;

import io.github.youqing.sts1solver.sim.MonsterMove;
import io.github.youqing.sts1solver.sim.MonsterMoves;
import io.github.youqing.sts1solver.sim.RngStream;
import io.github.youqing.sts1solver.sim.SimCard;
import io.github.youqing.sts1solver.sim.SimMonster;
import io.github.youqing.sts1solver.sim.SimState;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 「还有能量 + 手里有能打的牌，却结束回合」的回归网。
 *
 * <p>为什么单开一条：这是用户反复报的同一个症状（2026-10-30 两次），而且它**不是**世界模型错 ——
 * 真机日志里这一场的每一次 diff 都是 MATCH，纯粹是选路把「空过」判成了最优。
 *
 * <p>判据很简单：0 费/1 费的伤害牌打出去**没有任何副作用**（不消耗、不给人回血），
 * 所以「打它」在排序上必须严格优于「结束回合」。如果这里红了，说明排序键或剪枝把
 * 一条本来更好的路线丢了。
 */
class EndTurnWithEnergyLeftTest {

    /** 一副开局牌组（15 张），和真机 F1 那个局面同源。 */
    private static void addStarterDeck(SimState s) {
        for (int i = 0; i < 5; i++) {
            s.draw.add(new SimCard("Strike_R", false, 1, 6, "ATTACK"));
        }
        for (int i = 0; i < 4; i++) {
            s.draw.add(new SimCard("Defend_R", false, 1, -1, "SKILL"));
        }
        s.draw.add(new SimCard("Bash", false, 2, 8, "ATTACK"));
        s.draw.add(new SimCard("Pommel Strike", false, 1, 9, "ATTACK"));
        s.draw.add(new SimCard("Shrug It Off", false, 1, -1, "SKILL"));
        s.draw.add(new SimCard("Iron Wave", false, 1, 5, "ATTACK"));
        s.draw.add(new SimCard("Uppercut", false, 2, 13, "ATTACK"));
    }

    private static SimState state(int energy, int handStrikes, int enemyHp) {
        SimState s = new SimState();
        s.hp = 74;
        s.maxHp = 80;
        s.energy = energy;
        s.turn = 4;
        s.rng.put(SimState.RngKind.AI, RngStream.from(20261030L, 11L, 0));
        s.rng.put(SimState.RngKind.CARD, RngStream.from(20261030L, 22L, 0));
        s.rng.put(SimState.RngKind.SHUFFLE, RngStream.from(20261030L, 33L, 0));
        s.rng.put(SimState.RngKind.MISC, RngStream.from(20261030L, 44L, 0));
        s.rng.put(SimState.RngKind.MONSTER, RngStream.from(20261030L, 55L, 0));
        addStarterDeck(s);
        for (int i = 0; i < handStrikes; i++) {
            s.hand.add(new SimCard("Strike_R", false, 1, 6, "ATTACK"));
        }
        SimMonster jaw = new SimMonster("JawWorm", enemyHp, 44, 0,
                Collections.<String, Integer>emptyMap());
        MonsterMove chomp = MonsterMoves.next(s, jaw, 4, 0);
        jaw.pendingMove = chomp;
        jaw.pendingMoveId = chomp.id;
        s.monsters.add(jaw);
        return s;
    }

    @Test
    void playsAPlayableStrikeInsteadOfEndingTheTurn() {
        // 1 能量 + 3 张打击 + 大颚虫还有 30 血：打一张是 6 点白赚伤害
        SimState s = state(1, 3, 30);
        BeamSolver solver = new BeamSolver(3, 5, 135, 500, 500_000, 72)
                .withNovelty().withPortfolio();
        PlanAction action = solver.bestFirstAction(s, 10_000L);

        assertNotNull(action, "必须给出动作");
        assertEquals(PlanAction.Kind.PLAY_CARD, action.kind,
                "还有 1 能量、手里 3 张打击可打，不该结束回合（novelty=" + solver.lastNoveltyStop
                        + " port=" + solver.lastPortfolioAdopted + "）");
    }

    /**
     * 能量已经打光，但手里有一张**本回合 0 费**的攻击牌（药水/地狱之刃/发现给的）。
     *
     * <p>2026-10-30 真机实测踩到：小鬼头目那场，攻击药水给的「顺劈斩 Pommel Strike」
     * 带着 {@code costForTurn = 0} 留在手里，搜索却排了「结束回合」——
     * 白丢 9 点伤害 + 一张抽牌，紧接着就被反打 26。
     *
     * <p>判据与上面两条一致：0 费伤害牌打出去没有任何副作用，必须严格优于空过。
     */
    @Test
    void playsAZeroCostCardEvenWithNoEnergyLeft() {
        SimState s = state(0, 1, 30);
        s.hand.add(new SimCard("Pommel Strike", false, 1, 9, "ATTACK", 0));
        BeamSolver solver = new BeamSolver(3, 5, 135, 500, 500_000, 72)
                .withNovelty().withPortfolio();
        PlanAction action = solver.bestFirstAction(s, 10_000L);

        assertNotNull(action, "必须给出动作");
        assertEquals(PlanAction.Kind.PLAY_CARD, action.kind,
                "能量 0 但手里有本回合 0 费的攻击牌，不该结束回合（novelty="
                        + solver.lastNoveltyStop + " port=" + solver.lastPortfolioAdopted + "）");
    }

    /** 同样的局面，但敌人只剩 3 血：必须补刀，不能空过。 */
    @Test
    void finishesALowHpEnemyInsteadOfEndingTheTurn() {
        SimState s = state(1, 3, 3);
        BeamSolver solver = new BeamSolver(3, 5, 135, 500, 500_000, 72)
                .withNovelty().withPortfolio();
        PlanAction action = solver.bestFirstAction(s, 10_000L);

        assertNotNull(action);
        assertEquals(PlanAction.Kind.PLAY_CARD, action.kind, "能补刀就必须补刀");
    }
}
