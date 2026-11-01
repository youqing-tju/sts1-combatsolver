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
import static org.junit.jupiter.api.Assertions.assertThrows;
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
        assertNotNull(potionStepIn(solver), "Force 命中时路线里必须有那一步：" + solver.lastSteps);
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

    /**
     * 满足不了 Force 时抛**专用异常**（蓝本 {@code PotionPolicyUnsatisfiedException}）。
     *
     * <p>旧实现是 `null` + 一次「把这瓶钉在第一步」的补搜。两者蓝本都没有：
     * 它在统一候选池里做资格过滤，池子空了就抛；补搜通道是自创。
     */
    @Test
    void unsatisfiedPolicyThrowsDedicatedExceptionInsteadOfPinnedRescueOrNull() {
        SimState root = fight(20);
        root.potions.set(0, "FairyPotion");        // 被动药水：canUse == false，永远喝不了
        PotionStrategySnapshot strategy = PotionStrategySnapshot.defaults()
                .withDirective(0, "FairyPotion", SolverPotionDirective.FORCE);

        BeamSolver solver = new BeamSolver(3, 5, 8, 6).withPotionStrategy(strategy);
        PotionPolicyUnsatisfiedException thrown = assertThrows(
                PotionPolicyUnsatisfiedException.class,
                () -> solver.bestFirstAction(root.copy()),
                "满足不了 Force 必须抛专用异常，不是返回 null");
        assertTrue(thrown.getMessage().contains("FairyPotion@0"),
                "文案要说清是哪一格哪瓶：" + thrown.getMessage());
        assertTrue(thrown.getMessage().contains("指定药水必须使用"),
                "文案要对应蓝本第一档：" + thrown.getMessage());
        // 旧的 lastPolicySatisfied/lastPolicyViolation 随「先选一条再复核」一起删掉了：
        // 现在没合格候选就在 FinalPlanOrdering 里直接抛，文案本身就带强制清单。
    }

    /**
     * 要求「至少一瓶」，但池子里的用药路线一条都没赢 → 必须显式失败。
     *
     * <p>蓝本 {@code PotionUsePolicy.IsEligible} 的 Require 分支写的是
     * {@code (!anyRouteWon || potionRouteWon)}：能赢的路线里一瓶都不喝时，
     * 喝药但不赢的路线**不合格**。所以这个局面抛异常是正确行为，不是缺陷 ——
     * 以前它靠「闸门替换答案」蒙混过关。
     */
    @Test
    void requireAtLeastOneWithoutAWinningPotionRouteFailsLoudly() {
        SimState root = fight(20);
        PotionStrategySnapshot strategy = PotionStrategySnapshot.defaults()
                .withGlobalPolicy(SolverPotionPolicy.REQUIRE_AT_LEAST_ONE);

        BeamSolver solver = new BeamSolver(3, 5, 8, 6).withPotionStrategy(strategy);
        PotionPolicyUnsatisfiedException thrown = assertThrows(
                PotionPolicyUnsatisfiedException.class,
                () -> solver.bestFirstAction(root.copy()));
        assertTrue(thrown.getMessage().contains("至少使用一瓶"), thrown.getMessage());
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
