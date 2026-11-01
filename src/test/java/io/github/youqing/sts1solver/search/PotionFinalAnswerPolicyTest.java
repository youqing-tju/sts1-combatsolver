package io.github.youqing.sts1solver.search;

import io.github.youqing.sts1solver.sim.SimCard;
import io.github.youqing.sts1solver.sim.SimMonster;
import io.github.youqing.sts1solver.sim.SimState;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 策略对**最终答案**的约束（而不是对排序加分）。
 *
 * <p>这是 Force 与「偏好」的分界线：
 * <ul>
 *   <li>Force 是硬约束 —— 最终路线里必须出现 {@code (slot, potionId)} 那一步；</li>
 *   <li>满足不了就**不返回计划**，绝不静默退回一条不满足约束的路线；</li>
 *   <li>RequireAtLeastOne 在药水闸与 0 费补搜**之后**仍然成立。</li>
 * </ul>
 */
class PotionFinalAnswerPolicyTest {

    private static SimState fight(int monsterHp) {
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
        SimMonster m = new SimMonster("TestMonster", monsterHp, monsterHp, 0,
                Collections.<String, Integer>emptyMap());
        m.intentDmg = 0;
        s.monsters.add(m);
        s.potions.add("Fire Potion");
        s.potions.add(null);
        return s;
    }

    private static RouteStep potionStepIn(BeamSolver solver) {
        for (RouteStep step : solver.lastSteps) {
            if (step.potion) {
                return step;
            }
        }
        return null;
    }

    @Test
    void forcedSlotMustAppearInFinalSelectedRoute() {
        SimState root = fight(20);
        PotionStrategySnapshot strategy = PotionStrategySnapshot.defaults()
                .withDirective(0, "Fire Potion", SolverPotionDirective.FORCE);

        BeamSolver solver = new BeamSolver(3, 5, 8, 6).withPotionStrategy(strategy);
        assertNotNull(solver.bestFirstAction(root.copy()));

        RouteStep used = potionStepIn(solver);
        assertNotNull(used, "Force 的药水必须真的出现在最终路线里：" + solver.lastSteps);
        assertEquals(0, used.potionSlot);
        assertEquals("Fire Potion", used.cardKey);
        assertTrue(solver.lastPolicySatisfied, "满足约束时不该报违规：" + solver.lastPolicyViolation);
    }

    @Test
    void forcedSlotBindsSlotAndPotionIdNotJustCardLikeId() {
        SimState root = fight(20);
        root.potions.set(0, "Fire Potion");
        root.potions.set(1, "Fire Potion");        // 两瓶同 ID
        PotionStrategySnapshot strategy = PotionStrategySnapshot.defaults()
                .withDirective(1, "Fire Potion", SolverPotionDirective.FORCE);

        BeamSolver solver = new BeamSolver(3, 5, 8, 6).withPotionStrategy(strategy);
        assertNotNull(solver.bestFirstAction(root.copy()));

        RouteStep used = potionStepIn(solver);
        assertNotNull(used, "Force 的是 1 号槽，最终路线必须喝 1 号槽那瓶：" + solver.lastSteps);
        assertEquals(1, used.potionSlot, "喝 0 号槽那瓶不算满足 Force");
        assertTrue(solver.lastPolicySatisfied);
    }

    @Test
    void failedForcedAnswerNeverFallsBackToUnforcedRoute() {
        SimState root = fight(20);
        root.potions.set(0, "FairyPotion");        // 被动药水：canUse == false，永远喝不了
        PotionStrategySnapshot strategy = PotionStrategySnapshot.defaults()
                .withDirective(0, "FairyPotion", SolverPotionDirective.FORCE);

        BeamSolver solver = new BeamSolver(3, 5, 8, 6).withPotionStrategy(strategy);
        assertNull(solver.bestFirstAction(root.copy()),
                "满足不了 Force 就必须显式表示「没有满足约束的计划」");
        assertFalse(solver.lastPolicySatisfied);
        assertTrue(solver.lastPolicyViolation.contains("0:FairyPotion"),
                "违规原因要说清是哪一格哪瓶：" + solver.lastPolicyViolation);
        assertTrue(solver.lastSteps.isEmpty(), "不许把不满足约束的路线当答案交出去");
    }

    @Test
    void requireAtLeastOneAppliesAfterGateAndRescue() {
        SimState root = fight(20);
        PotionStrategySnapshot strategy = PotionStrategySnapshot.defaults()
                .withGlobalPolicy(SolverPotionPolicy.REQUIRE_AT_LEAST_ONE);

        BeamSolver solver = new BeamSolver(3, 5, 8, 6).withPotionStrategy(strategy);
        assertNotNull(solver.bestFirstAction(root.copy()));
        assertNotNull(potionStepIn(solver),
                "RequireAtLeastOne 必须在药水闸（它会退回不喝药的路线）之后仍然成立："
                        + solver.lastSteps);
        assertTrue(solver.lastPolicySatisfied);
    }

    @Test
    void continuationComesFromPolicyCompliantFinalRoute() {
        SimState root = fight(46);
        PotionStrategySnapshot strategy = PotionStrategySnapshot.defaults()
                .withGlobalPolicy(SolverPotionPolicy.DISABLED);

        BeamSolver solver = new BeamSolver(3, 5, 8, 6).withPotionStrategy(strategy);
        assertNotNull(solver.bestFirstAction(root.copy()));

        assertNull(potionStepIn(solver), "禁用药物水不许出现在最终路线里");
        assertFalse(solver.lastContinuations.isEmpty(),
                "策略合规的路线照样要产出跨回合边界（两者互不影响）");
        for (CachedContinuation cached : solver.lastContinuations.continuations()) {
            assertTrue(cached.forecastOffset < solver.lastSteps.size());
        }
    }
}
