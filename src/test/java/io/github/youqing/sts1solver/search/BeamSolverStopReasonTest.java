package io.github.youqing.sts1solver.search;

import io.github.youqing.sts1solver.sim.SimCard;
import io.github.youqing.sts1solver.sim.SimMonster;
import io.github.youqing.sts1solver.sim.SimState;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 搜索「为什么停」必须是可判读的三态，而不是一个 {@code hitNodeLimit} 布尔。
 *
 * <p>2026-10-31 的交接文档把 {@code !hitNodeLimit} 印成 {@code tree exhausted}，
 * 于是「把整个地平线推演完了」被解读成「open 表过早耗尽 / 剪枝太狠、加预算也没用」，
 * 据此排出了「查剪枝」的任务。2026-11-01 用 123 个真机快照实测：**全是 {@code horizon}**，
 * 节点用量 2~3% —— 搜索本来就是把整场战斗走完了。
 *
 * <p>所以这里把三态钉死：真打赢了 = {@code victory}，被预算掐断 = {@code node_limit}，
 * 地平线走完 = {@code horizon}。
 */
class BeamSolverStopReasonTest {

    private static SimState state(int monsterHp, int intentDmg, String... hand) {
        SimState s = new SimState();
        s.hp = 30;
        s.maxHp = 80;
        s.energy = 3;
        for (String id : hand) {
            boolean attack = id.startsWith("Strike") || id.startsWith("Bash");
            s.hand.add(new SimCard(id, false, 1, attack ? 6 : -1, attack ? "ATTACK" : "SKILL"));
        }
        SimMonster m = new SimMonster("TestMonster", monsterHp, monsterHp, 0,
                Collections.<String, Integer>emptyMap());
        m.intentDmg = intentDmg;
        s.monsters.add(m);
        for (int i = 0; i < 8; i++) {
            s.draw.add(new SimCard("Strike_R", false, 1));
        }
        return s;
    }

    @Test
    void killingTheOnlyMonsterIsReportedAsVictory() {
        BeamSolver solver = new BeamSolver(3, 5, 8, 4);

        solver.bestFirstAction(state(6, 0, "Strike_R"));

        assertTrue(solver.lastSelectedVictory, "6 点伤害打 6 血 = 真的打完了");
        assertEquals("victory", solver.lastStopReason);
    }

    @Test
    void aTinyNodeBudgetIsReportedAsNodeLimit() {
        BeamSolver solver = new BeamSolver(3, 5, 8, 4, 1, 8);

        solver.bestFirstAction(state(60, 12, "Strike_R", "Strike_R"));

        assertEquals("node_limit", solver.lastStopReason);
        assertTrue(solver.hitNodeLimit);
    }

    @Test
    void finishingTheWholeFightWithoutAWinnerIsReportedAsHorizon() {
        BeamSolver solver = new BeamSolver(3, 5, 8, 4);

        // 30 血打 60 血、每回合挨 12：几回合内必死，但没被预算掐断
        solver.bestFirstAction(state(60, 12, "Strike_R"));

        assertEquals("horizon", solver.lastStopReason,
                "「地平线走完」不能再说成 tree exhausted（那是「剪枝太狠」的含义）");
        assertEquals(false, solver.hitNodeLimit);
    }
}
