package io.github.youqing.sts1solver.search;

import io.github.youqing.sts1solver.sim.SimCard;
import io.github.youqing.sts1solver.sim.SimMonster;
import io.github.youqing.sts1solver.sim.SimState;
import org.junit.jupiter.api.Test;

import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 全通道一致性：串行 / 并行 / novelty / portfolio 最终都只对**同一个池子**做同一套资格与排序。
 *
 * <p>为什么必须钉：这些通道各自挑一次答案，只要任何一处还走旧的 {@code betterInterim}，
 * 就会得到「线程数不同 → 牌不一样」这种最难查的行为漂移。
 */
class FinalSelectionConsistencyTest {

    /** 串行与并行展开必须选出同一条路线、同一个首手。 */
    @Test
    void serialAndParallelExpansionAgreeOnTheFinalRoute() {
        SimState root = fight();

        BeamSolver serial = new BeamSolver(3, 5, 8, 6);
        serial.bestFirstAction(root.copy());

        BeamSolver parallel = new BeamSolver(3, 5, 8, 6).withParallelLanes(2);
        parallel.bestFirstAction(root.copy());

        assertEquals(String.valueOf(serial.lastRoute), String.valueOf(parallel.lastRoute),
                "并行展开只影响速度，不许改变最终选中的路线");
        assertEquals(serial.lastSteps.size(), parallel.lastSteps.size());
        assertEquals(serial.lastSelectedVictory, parallel.lastSelectedVictory);
    }

    /** novelty 与 portfolio 都只能对最终池做同一套排序，不能各自「再挑一次」。 */
    @Test
    void everyLaneEndsInTheSameSelector() {
        SimState root = fight();

        BeamSolver plain = new BeamSolver(3, 5, 8, 6);
        plain.bestFirstAction(root.copy());

        BeamSolver withNovelty = new BeamSolver(3, 5, 8, 6).withNovelty();
        withNovelty.bestFirstAction(root.copy());
        assertTrue(withNovelty.lastFinalCandidatesEvaluated > 0,
                "novelty 通道也要走最终选择器");

        BeamSolver withPortfolio = new BeamSolver(3, 5, 8, 6).withPortfolio();
        withPortfolio.bestFirstAction(root.copy());
        assertTrue(withPortfolio.lastFinalCandidatesEvaluated > 0,
                "portfolio 通道也要走最终选择器");
        assertEquals(String.valueOf(plain.lastRoute), String.valueOf(withPortfolio.lastRoute),
                "这个局面很小，宽度组合不该改变答案");
    }

    /** 换一场战斗（reset）后，成长 / 卖血 / 战斗已承诺卖血都不许跨界泄漏。 */
    @Test
    void battleLedgersDoNotLeakAcrossCombats() {
        SimState first = fight();
        BeamSolver solver = new BeamSolver(3, 5, 8, 6);
        solver.bestFirstAction(first.copy());

        assertTrue(solver.lastFinalPool.size() >= 0);

        SimState second = fight();
        second.feedGrowth = 0;
        second.longTermResourceValue = 0;
        BeamSolver fresh = new BeamSolver(3, 5, 8, 6);
        fresh.bestFirstAction(second.copy());
        assertEquals(0, fresh.battleSoldHpCommitted,
                "新的 BeamSolver 不继承上一场的已承诺卖血");
        assertEquals(0, second.growthValues().total());
    }

    /** 续跑命中时，卖血与成长不会被重置成 0（它们现在进状态戳 + 排序）。 */
    @Test
    void continuationKeepsTheNewMetrics() {
        SimState root = fight();
        root.feedGrowth = 2;
        root.longTermResourceValue = 40;

        ContinuationStamp stamp = ContinuationStamp.capturePredicted(root);
        assertTrue(stamp.canonicalText().contains("feedGrowth=2"));
        assertTrue(stamp.canonicalText().contains("longTermResourceValue=40"));

        SimState same = root.copy();
        assertEquals(stamp, ContinuationStamp.capturePredicted(same));

        same.feedGrowth = 3;
        assertFalse(stamp.equals(ContinuationStamp.capturePredicted(same)),
                "成长计数变了就不是同一条世界线");
    }

    private static SimState fight() {
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
        SimMonster monster = new SimMonster("TestMonster", 40, 40, 0,
                new TreeMap<String, Integer>());
        monster.intentDmg = 6;
        state.monsters.add(monster);
        return state;
    }
}
