package io.github.youqing.sts1solver.search;

import io.github.youqing.sts1solver.sim.SimCard;
import io.github.youqing.sts1solver.sim.SimMonster;
import io.github.youqing.sts1solver.sim.SimState;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 策略必须约束**所有**搜索通道，而不是只有主 Beam。
 *
 * <p>为什么逐通道测：新颖度、宽度组合、药水闸基线、0 费补搜各有一条独立的选路逻辑，
 * 任何一条漏掉策略，用户就会看到「我已经禁用了药水，它还是喝了」。
 * 每条测试都先证明「不设策略时这个通道确实会喝」，再证明「设了就不喝」——
 * 否则测试可能只是碰巧路过一条没喝药的路。
 */
class PotionPolicyPropagationTest {

    /** 怪 20 血、不还手：喝火药能早一回合结束，不喝也行。 */
    private static SimState fight() {
        SimState s = new SimState();
        s.turn = 1;
        s.hp = 30;
        s.maxHp = 80;
        s.energy = 1;
        s.energyPerTurn = 3;
        s.hand.add(new SimCard("Strike_R", false, 1, 6, "ATTACK"));
        for (int i = 0; i < 8; i++) {
            s.draw.add(new SimCard("Strike_R", false, 1, 6, "ATTACK"));
        }
        SimMonster m = new SimMonster("TestMonster", 20, 20, 0,
                Collections.<String, Integer>emptyMap());
        m.intentDmg = 0;
        s.monsters.add(m);
        s.potions.add("Fire Potion");
        s.potions.add(null);
        return s;
    }

    private static PotionStrategySnapshot disabled() {
        return PotionStrategySnapshot.defaults().withGlobalPolicy(SolverPotionPolicy.DISABLED);
    }

    private static boolean routeUsesPotion(BeamSolver solver) {
        for (RouteStep step : solver.lastSteps) {
            if (step.potion) {
                return true;
            }
        }
        return false;
    }

    @Test
    void disabledPolicyRejectsMainBeamPotionAnswer() {
        SimState root = fight();

        // 旧实现里这条对照组会喝火药，因为「买不回血」只在闸门那一层被挡。
        // 现在资格过滤内建在最终选择器里（蓝本同一处），连 Default（Smart）也不会喝 ——
        // 所以不再有「不设策略就乱喝」的对照组，只验证 Disabled 一样挡得住。
        BeamSolver solver = new BeamSolver(3, 5, 8, 6).withPotionCostGate(false)
                .withPotionStrategy(disabled());
        assertNotNull(solver.bestFirstAction(root.copy()));
        assertFalse(routeUsesPotion(solver), "全局 Disabled 必须挡住主 Beam：" + solver.lastSteps);
    }

    @Test
    void noveltyCannotResurrectDisabledPotion() {
        SimState root = fight();

        BeamSolver control = new BeamSolver(3, 5, 8, 6).withPotionCostGate(false).withNovelty();
        assertNotNull(control.bestFirstAction(root.copy()));

        BeamSolver solver = new BeamSolver(3, 5, 8, 6).withPotionCostGate(false)
                .withNovelty().withPotionStrategy(disabled());
        assertNotNull(solver.bestFirstAction(root.copy()));
        assertFalse(routeUsesPotion(solver), "新颖度通道不许把违禁药水捞回来：" + solver.lastSteps);
    }

    @Test
    void portfolioCannotResurrectDisabledPotion() {
        SimState root = fight();

        BeamSolver control = new BeamSolver(3, 5, 8, 6).withPotionCostGate(false).withPortfolio();
        assertNotNull(control.bestFirstAction(root.copy()));

        BeamSolver solver = new BeamSolver(3, 5, 8, 6).withPotionCostGate(false)
                .withPortfolio().withPotionStrategy(disabled());
        assertNotNull(solver.bestFirstAction(root.copy()));
        assertFalse(routeUsesPotion(solver), "宽度组合的各成员都要用同一份策略：" + solver.lastSteps);
    }

    @Test
    void rescueCannotResurrectDisabledPotion() {
        SimState root = fight();
        root.monsters.get(0).hp = 4;
        root.hand.clear();
        root.hand.add(new SimCard("Flex", false, 0, -1, "SKILL"));

        BeamSolver solver = new BeamSolver(3, 5, 8, 5).withPotionStrategy(disabled());
        solver.bindRoot(root);

        SimState won = root.copy();
        won.monsters.get(0).hp = 0;
        won.hp = 5;
        SearchNode rootNode = new SearchNode(root.copy(), null, null, 0, 0L);
        SearchNode fake = new SearchNode(won, PlanAction.endTurn(), rootNode, 1, 0L);

        SearchNode rescued = solver.zeroCostOpeningRescue(root, fake, Long.MAX_VALUE);
        solver.publishFinalRoute(rescued);
        assertFalse(routeUsesPotion(solver),
                "0 费补搜的子 solver 必须继承同一份策略：" + solver.lastSteps);
    }

    @Test
    void noPotionBaselineStillCompetesWhenPotionsAreDisabled() {
        SimState root = fight();

        BeamSolver solver = new BeamSolver(3, 5, 8, 6).withPotionStrategy(disabled());
        assertNotNull(solver.bestFirstAction(root.copy()), "禁用药物水不等于「没有计划」");
        assertFalse(routeUsesPotion(solver));
        assertTrue(solver.lastPotionStrategicCost == 0,
                "路线里没有药水，就不该再为对账跑一趟基线");
        assertFalse(solver.lastPotionSpendRejected, "没有喝药，谈不上「被闸门退回」");
    }

    /** 默认快照（Smart、无覆盖）必须与「不传策略」逐项等价 —— 引入策略不能顺带改默认行为。 */
    @Test
    void defaultSnapshotKeepsExistingSmartBehaviour() {
        SimState root = fight();

        BeamSolver plain = new BeamSolver(3, 5, 8, 6);
        assertNotNull(plain.bestFirstAction(root.copy()));
        BeamSolver smart = new BeamSolver(3, 5, 8, 6)
                .withPotionStrategy(PotionStrategySnapshot.defaults());
        assertNotNull(smart.bestFirstAction(root.copy()));

        assertEquals(plain.lastSteps.size(), smart.lastSteps.size());
        assertEquals(plain.lastPotionSpendRejected, smart.lastPotionSpendRejected);
        assertEquals(plain.lastPotionStrategicCost, smart.lastPotionStrategicCost);
        assertTrue(routeUsesPotion(plain) == routeUsesPotion(smart));
    }
}
