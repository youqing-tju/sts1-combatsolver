package io.github.youqing.sts1solver.search;

import io.github.youqing.sts1solver.sim.SimCard;
import io.github.youqing.sts1solver.sim.SimMonster;
import io.github.youqing.sts1solver.sim.SimState;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A4 性能验收：**120 秒预算内 ≥ 10 万节点**（= 约 834 节点/秒）。
 *
 * <p>阈值故意压得很低（只断言 3000 节点/秒）。单测跑在开发机上，真实的余量用打印值看，
 * 不拿它当性能回归的红线 —— 否则换个机器就红。
 *
 * <p>影子是纯数据（没有游戏对象），所以这里的数字基本就是「搜索本身」的成本。
 */
class SearchThroughputTest {

    private static SimState realistic() {
        SimState s = new SimState();
        s.hp = 62;
        s.maxHp = 80;
        s.energy = 3;
        s.turn = 3;
        // 满手 10 张：混合攻击/技能，模拟真实回合
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
    void reachesTheA4NodeBudget() {
        BeamSolver solver = new BeamSolver(3, 5, 6, 4);
        SimState root = realistic();

        // 预热：JIT 之后再计时，否则前几次搜索会被解释执行拖慢
        for (int i = 0; i < 20; i++) {
            solver.bestFirstAction(realistic());
        }

        int runs = 30;
        long nodes = 0;
        long startedAt = System.nanoTime();
        for (int i = 0; i < runs; i++) {
            solver.bestFirstAction(realistic());
            nodes += solver.lastNodes;
        }
        long elapsedNanos = System.nanoTime() - startedAt;
        double perSecond = nodes * 1e9 / Math.max(1, elapsedNanos);
        double nodesPerSearch = (double) nodes / runs;
        double perNodeMicros = elapsedNanos / 1e3 / Math.max(1, nodes);

        System.out.printf(
                "[A4] %.0f 节点/秒，单次 %.0f 节点 / %.2f ms，单节点 %.1f µs，120 秒可搜 %.0f 万节点%n",
                perSecond, nodesPerSearch, elapsedNanos / 1e6 / runs, perNodeMicros,
                perSecond * 120 / 10000);

        assertTrue(perSecond > 3000,
                "A4 要求 120 秒 ≥ 10 万节点（约 834/秒），实测只有 " + perSecond);
    }
}
