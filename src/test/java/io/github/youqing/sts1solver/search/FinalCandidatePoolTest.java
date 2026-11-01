package io.github.youqing.sts1solver.search;

import io.github.youqing.sts1solver.sim.SimCard;
import io.github.youqing.sts1solver.sim.SimMonster;
import io.github.youqing.sts1solver.sim.SimState;
import org.junit.jupiter.api.Test;

import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 最终候选池 = **全部**终局 + 最后一代前沿（+ 药水分组的保留泳道）。
 *
 * <p>蓝本 {@code CombatBeamSolver.Phases.cs} 的 {@code finalPool = [..completed, ..frontier]}：
 * 最终选哪条路线是在所有打完的候选里排，而不是「这一趟自己挑出来的那一个」。
 * 以前 STS1 的池子只有 {@code bestTerminal + frontier + best}，
 * 于是「第二名其实在 18 键下更好」永远没机会被选中。
 */
class FinalCandidatePoolTest {

    /** 池子里必须留着**每一个**终局，而不只是本趟的最优终局。 */
    @Test
    void poolKeepsEveryCompletedTerminalNotJustTheBest() {
        SimState root = fight(20, 0);
        BeamSolver solver = narrow();
        solver.bestFirstAction(root.copy());

        int winning = 0;
        for (SearchNode node : solver.lastFinalPool) {
            if (FinalPlanOrdering.isCompleteVictory(node.depth,
                    SearchNode.noAliveMonster(node.state), node.state.hp <= 0,
                    Score.projectedHpOf(node.state))) {
                winning++;
            }
        }
        assertTrue(winning >= 2,
                "这个局面至少有两种打法能赢（一击必杀 / 两回合），池子里都该在，实际 " + winning);
    }

    /** 每个「用了几瓶药」分组都要留一条：否则 RequireAtLeastOne 会连候选都看不到。 */
    @Test
    void poolKeepsOneCandidatePerPotionCount() {
        SimState root = fight(20, 0);
        root.potions.add("Fire Potion");
        root.potions.add(null);

        BeamSolver solver = narrow();
        solver.bestFirstAction(root.copy());

        boolean hasPotion = false;
        boolean hasNoPotion = false;
        for (SearchNode node : solver.lastFinalPool) {
            if (node.potionsUsed > 0) {
                hasPotion = true;
            } else {
                hasNoPotion = true;
            }
        }
        assertTrue(hasNoPotion, "不喝药的候选当然要在池子里");
        assertTrue(hasPotion, "喝药的候选也必须留一条，否则资格链看不到它");
    }

    /** 最终选择必须真的从池子里挑，而不是沿用「本趟最优终局」。 */
    @Test
    void finalSelectionIsNotJustTheInterimBest() {
        SimState root = fight(20, 0);
        BeamSolver solver = narrow();
        solver.bestFirstAction(root.copy());

        assertNotNull(solver.lastSelectedNode);
        boolean inPool = false;
        for (SearchNode node : solver.lastFinalPool) {
            if (node == solver.lastSelectedNode) {
                inPool = true;
                break;
            }
        }
        assertTrue(inPool, "最终选中的节点必须来自最终候选池");
        assertEquals(solver.lastFinalPool.size(), solver.lastFinalCandidatesEvaluated,
                "池子里每个节点都要经过资格链评估过一遍");
    }

    /** 池子里的候选各自带自己的 Boundary，不许用全局 lastStopReason 冒充。 */
    @Test
    void everyCandidateCarriesItsOwnBoundary() {
        SimState root = fight(20, 0);
        BeamSolver solver = narrow();
        solver.bestFirstAction(root.copy());

        for (SearchNode node : solver.lastFinalPool) {
            assertNotNull(node.boundaryReason, "候选必须自带 Boundary");
        }
        assertFalse(solver.lastFinalPool.isEmpty());
    }

    // ---------------------------------------------------------------- 夹具

    private static BeamSolver narrow() {
        return new BeamSolver(3, 5, 8, 6);
    }

    private static SimState fight(int monsterHp, int intentDmg) {
        SimState state = new SimState();
        state.turn = 1;
        state.hp = 60;
        state.maxHp = 80;
        state.energy = 1;
        state.energyPerTurn = 3;
        state.hand.add(new SimCard("Strike_R", false, 1, 6, "ATTACK"));
        for (int i = 0; i < 8; i++) {
            state.draw.add(new SimCard("Strike_R", false, 1, 6, "ATTACK"));
        }
        SimMonster monster = new SimMonster("TestMonster", monsterHp, monsterHp, 0,
                new TreeMap<String, Integer>());
        monster.intentDmg = intentDmg;
        state.monsters.add(monster);
        return state;
    }
}
