package io.github.youqing.sts1solver.search;

import io.github.youqing.sts1solver.sim.SimCard;
import io.github.youqing.sts1solver.sim.SimMonster;
import io.github.youqing.sts1solver.sim.SimState;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 搜索吞吐（A4）。
 *
 * <p>⚠️ 节点数的口径改过：以前记的是「展开了几个父节点」，一个父节点会生成十几二十个子状态
 * （每张牌 × 每个目标 × 选牌分支），所以旧数字比真实搜索量小一个数量级。
 * 现在 {@code lastNodes} = **真正生成出来的子状态数**。
 *
 * <p>两条路径分别测：
 * <ul>
 *   <li>固定深度（单测用的无预算路径）——看纯吞吐；</li>
 *   <li>时间预算（AUTO 实际走的路径）——看一个预算能吃出多深、多少状态。</li>
 * </ul>
 * 阈值故意压得很低，别让它成为换机器的红点。
 */
class SearchThroughputTest {

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

    @Test
    void fixedDepthThroughput() {
        BeamSolver solver = new BeamSolver(3, 5, 6, 4);
        for (int i = 0; i < 20; i++) {
            solver.bestFirstAction(realistic());   // 预热 JIT
        }

        int runs = 30;
        long states = 0;
        long startedAt = System.nanoTime();
        for (int i = 0; i < runs; i++) {
            solver.bestFirstAction(realistic());
            states += solver.lastNodes;
        }
        long elapsed = System.nanoTime() - startedAt;
        double perSecond = states * 1e9 / Math.max(1, elapsed);

        System.out.printf("[A4-fixed] %d 状态/次，%.0f 状态/秒，单次 %.2f ms%n",
                states / runs, perSecond, elapsed / 1e6 / runs);

        assertTrue(perSecond > 30_000,
                "固定深度吞吐太低：" + perSecond + " 状态/秒");
    }

    /** AUTO 真正走的路径：给 50 ms 预算，迭代加深看能吃多深。 */
    @Test
    void timeBudgetedDeepening() {
        BeamSolver solver = new BeamSolver(3, 5, 6, 12);
        for (int i = 0; i < 5; i++) {
            solver.bestFirstAction(realistic(), 10);
        }

        solver.bestFirstAction(realistic(), 50);

        System.out.printf("[A4-budget] 50ms 预算：深度 %d、生成 %d 个状态、实际耗时 %d ms%n",
                solver.lastDepth, solver.lastNodes, solver.lastMillis);

        assertTrue(solver.lastDepth >= 1, "预算内至少要跑完一层");
        assertTrue(solver.lastNodes > 0);
    }
}
