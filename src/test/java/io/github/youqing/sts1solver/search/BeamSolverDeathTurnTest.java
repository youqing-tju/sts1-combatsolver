package io.github.youqing.sts1solver.search;

import io.github.youqing.sts1solver.sim.SimCard;
import io.github.youqing.sts1solver.sim.SimMonster;
import io.github.youqing.sts1solver.sim.SimState;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「这条路线第几回合把我打死」。
 *
 * <p>蓝本拿它和当前回合比：相等就**不部署**、交还控制（{@code StopFullAutoOnDeathTurn}）。
 * 算错的后果很具体 —— 要么该停的时候继续自动送死，要么明明还能打却突然停手。
 */
class BeamSolverDeathTurnTest {

    private static SimState state(int playerHp, int monsterHp, int intentDmg) {
        SimState s = new SimState();
        s.turn = 1;
        s.hp = playerHp;
        s.maxHp = 80;
        s.energy = 1;
        s.energyPerTurn = 3;
        s.hand.add(new SimCard("Strike_R", false, 1, 6, "ATTACK"));
        for (int i = 0; i < 8; i++) {
            s.draw.add(new SimCard("Strike_R", false, 1, 6, "ATTACK"));
        }
        SimMonster m = new SimMonster("TestMonster", monsterHp, monsterHp, 0,
                Collections.<String, Integer>emptyMap());
        m.intentDmg = intentDmg;
        m.intentAttack = intentDmg > 0;
        s.monsters.add(m);
        return s;
    }

    @Test
    void deathThisTurnIsReported() {
        // 3 血、对面 30 意图、一刀砍不死 -> 怎么走都是这一回合死
        SimState root = state(3, 100, 30);
        BeamSolver solver = new BeamSolver(3, 5, 8, 5);
        assertNotNull(solver.bestFirstAction(root.copy()));

        assertEquals(1, solver.lastDeathTurn,
                "预计死亡回合必须等于当前回合（否则主线程不会停手），实际："
                        + solver.lastDeathTurn + " steps=" + solver.lastSteps);
    }

    @Test
    void winningRouteHasNoDeathTurn() {
        SimState root = state(40, 6, 0);
        BeamSolver solver = new BeamSolver(3, 5, 8, 5);
        assertNotNull(solver.bestFirstAction(root.copy()));

        assertTrue(solver.lastSelectedVictory, "前提：这局面确实能赢");
        assertEquals(-1, solver.lastDeathTurn, "打赢了就没有死亡回合");
    }

    @Test
    void laterDeathIsNotThisTurn() {
        // 20 血、对面 8 意图、怪 100 血：能撑好几回合，死亡回合不是 1
        SimState root = state(20, 100, 8);
        BeamSolver solver = new BeamSolver(3, 5, 8, 5);
        assertNotNull(solver.bestFirstAction(root.copy()));

        assertTrue(solver.lastDeathTurn != 1,
                "这一回合不会死，不该报 1，实际：" + solver.lastDeathTurn);
    }

    @Test
    void nullSelectionHasNoDeathTurn() {
        assertEquals(-1, BeamSolver.deathTurnOf(null));
    }
}
