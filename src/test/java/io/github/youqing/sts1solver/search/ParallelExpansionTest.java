package io.github.youqing.sts1solver.search;

import io.github.youqing.sts1solver.sim.SimCard;
import io.github.youqing.sts1solver.sim.SimMonster;
import io.github.youqing.sts1solver.sim.SimState;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 并行展开的验收（蓝本 {@code ParallelExpansion.cs} 的两条不变式）：
 * **lane 隔离 + 有序提交 ⇒ 结果与串行逐位相同**。
 *
 * <p>为什么这条判据能钉死：并行只改了「谁来展开」，没改「展开出什么」；
 * 只要结果按父节点原序合并、下游剪枝又是稳定排序，整棵树就与串行同构。
 * 所以这里不比「差不多好」，而是逐项比：选中动作、路线每一步、分数、节点数、深度、叶子数。
 *
 * <p>⚠️ 一律用节点上限（不传时间预算），否则两次跑的耗时不同会砍在不同的地方。
 */
class ParallelExpansionTest {

    /** 两个怪、10 张手牌、三堆都非空 —— 每层展开的分支够多，并行才有意义。 */
    private static SimState realistic() {
        SimState s = new SimState();
        s.hp = 62;
        s.maxHp = 80;
        s.energy = 3;
        s.turn = 3;
        String[] hand = {"Strike_R", "Strike_R", "Strike_R", "Defend_R", "Defend_R",
                "Bash", "Pommel Strike", "Shrug It Off", "Anger", "Cleave"};
        for (String id : hand) {
            s.hand.add(new SimCard(id, false, 1));
        }
        for (int i = 0; i < 12; i++) {
            s.draw.add(new SimCard(i % 2 == 0 ? "Strike_R" : "Defend_R", false, 1));
        }
        for (int i = 0; i < 5; i++) {
            s.discard.add(new SimCard("Defend_R", false, 1));
        }
        SimMonster a = new SimMonster("Cultist", 48, 50, 0, Collections.<String, Integer>emptyMap());
        a.intentDmg = 6;
        SimMonster b = new SimMonster("Louse", 15, 16, 0, Collections.<String, Integer>emptyMap());
        b.intentDmg = 5;
        s.monsters.add(a);
        s.monsters.add(b);
        return s;
    }

    /** 带药水的局面：并行展开里药水分支也要一致。 */
    private static SimState withPotions() {
        SimState s = realistic();
        s.potions.add("Fire Potion");
        s.potions.add("Block Potion");
        return s;
    }

    private static String describe(PlanAction action) {
        if (action == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder(action.toString());
        if (action.choiceIndices != null) {
            sb.append(" multi=").append(java.util.Arrays.toString(action.choiceIndices));
        }
        return sb.toString();
    }

    private static void assertSameSearch(String label, SimState base, int[] lanes) {
        BeamSolver serial = new BeamSolver(3, 5, 6, 4, 20_000, 32).withParallelLanes(1);
        PlanAction expected = serial.bestFirstAction(base.copy());
        assertNotNull(expected, label + "：串行应当能给出答案");

        for (int lane : lanes) {
            BeamSolver parallel = new BeamSolver(3, 5, 6, 4, 20_000, 32).withParallelLanes(lane);
            PlanAction actual = parallel.bestFirstAction(base.copy());

            assertEquals(describe(expected), describe(actual), label + " lanes=" + lane + "：选中动作不同");
            assertEquals(serial.lastNodes, parallel.lastNodes, label + "：展开节点数必须相同");
            assertEquals(serial.lastDepth, parallel.lastDepth, label + "：回合层数必须相同");
            assertEquals(serial.lastLeaves, parallel.lastLeaves, label + "：跑通的路线数必须相同");
            assertEquals(serial.lastStaticScore, parallel.lastStaticScore, label + "：终局分必须相同");
            assertEquals(serial.lastTerminalScore, parallel.lastTerminalScore, label + "：选中分必须相同");

            List<PlanAction> left = serial.lastRoute;
            List<PlanAction> right = parallel.lastRoute;
            assertEquals(left.size(), right.size(), label + "：路线长度必须相同");
            for (int i = 0; i < left.size(); i++) {
                assertEquals(describe(left.get(i)), describe(right.get(i)),
                        label + " lanes=" + lane + "：路线第 " + i + " 步不同");
            }
        }
    }

    @Test
    void parallelSearchMatchesSerialStepByStep() {
        assertSameSearch("两怪十牌", realistic(), new int[]{2, 4, 8});
    }

    @Test
    void parallelSearchMatchesSerialWithPotions() {
        assertSameSearch("带两瓶药", withPotions(), new int[]{2, 4});
    }

    /**
     * 默认是**串行**（1），并钉住这个决定 —— 见 {@link BeamSolver#defaultParallelLanes()} 上的实测记录。
     *
     * <p>同时验一下蓝本那条规则还算得出来（1..8），它现在只当「真要开时填几」的参考上限。
     */
    @Test
    void defaultsToSerialBecauseParallelDoesNotPayOffYet() {
        assertEquals(1, BeamSolver.defaultParallelLanes(),
                "默认必须串行：实测 8 lane 只有 1.03×，而单线程剪枝的指纹占掉四成时间");
        int reference = ParallelExpansion.defaultDegree();
        assertTrue(reference >= 1 && reference <= ParallelExpansion.MAX_DEGREE,
                "蓝本规则的参考并行度必须在 1..16，实际 " + reference);
    }

    /**
     * 吞吐对照（默认不跑，{@code gradle test -Pexperiments} 才跑）。
     *
     * <p>只是**测量台**，不设阈值、不做结论 —— A4 的口径本来就看机器。
     * 判据仍是上面两条等式测试：并行只准变快，不准改结果。
     */
    @Test
    @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named = "sts1.experiments",
            matches = "true", disabledReason = "离线实验（gradle test -Pexperiments 才跑）")
    void throughputLanes() {
        int[] laneSet = {1, 2, 4, 8};
        for (int lanes : laneSet) {
            BeamSolver solver = new BeamSolver(3, 5, 45, 5, 120_000, 32)
                    .withParallelLanes(lanes);
            long started = System.nanoTime();
            solver.bestFirstAction(realistic());
            long millis = Math.max(1L, (System.nanoTime() - started) / 1_000_000L);
            System.out.println("[parallel] lanes=" + lanes + " nodes=" + solver.lastNodes
                    + " ms=" + millis + " nodes_per_s=" + (solver.lastNodes * 1000L / millis));
        }
    }
}
