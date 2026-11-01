package io.github.youqing.sts1solver.verify;

import io.github.youqing.sts1solver.config.SolverConfig;
import io.github.youqing.sts1solver.search.ContinuationPlan;
import io.github.youqing.sts1solver.search.ContinuationStamp;
import io.github.youqing.sts1solver.search.PlanAction;
import io.github.youqing.sts1solver.search.RouteStep;
import io.github.youqing.sts1solver.search.SearchNode;
import io.github.youqing.sts1solver.search.SolverPotionDirective;
import io.github.youqing.sts1solver.search.SolverPotionPolicy;
import io.github.youqing.sts1solver.sim.SimCard;
import io.github.youqing.sts1solver.sim.SimMonster;
import io.github.youqing.sts1solver.sim.SimState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 策略代际（{@code policyRevision}）与真实控件。
 *
 * <p>为什么代际不能省：改药水策略**不改变局面指纹**。只按指纹判时效的话，
 * 「算完之后用户把某瓶设成 Disabled，结果回来照样执行」—— 用户会看到自己刚设的限制被无视。
 * 所以采纳时同时比指纹**和**代际。
 */
class PotionPolicyRevisionTest {

    private static SimState state(int turn, int hp) {
        SimState s = new SimState();
        s.turn = turn;
        s.hp = hp;
        s.maxHp = 80;
        s.energy = 3;
        s.energyPerTurn = 3;
        s.hand.add(new SimCard("Strike_R", false, 1, 6, "ATTACK"));
        s.potions.add("Fire Potion");
        s.potions.add(null);
        s.monsters.add(new SimMonster("JawWorm", 40, 44, 0,
                Collections.<String, Integer>emptyMap()));
        return s;
    }

    /** 一条带 T2 边界的路线，用来验证「策略一改，旧世界线就作废」。 */
    private static final class FakeRoute {
        final ContinuationPlan plan;
        final List<RouteStep> steps;
        final ContinuationStamp t2;

        FakeRoute() {
            SearchNode root = new SearchNode(state(1, 50), null, null, 0, 0L);
            SearchNode play = new SearchNode(state(1, 50), PlanAction.play(0, 0), root, 1, 0L);
            SearchNode t2 = new SearchNode(state(2, 48), PlanAction.endTurn(), play, 2, 0L);
            SearchNode play2 = new SearchNode(state(2, 48), PlanAction.play(0, 0), t2, 3, 0L);
            List<SearchNode> nodes = new ArrayList<SearchNode>();
            Collections.addAll(nodes, root, play, t2, play2);
            this.plan = ContinuationPlan.fromFinalRoute(nodes);
            this.steps = new ArrayList<RouteStep>();
            steps.add(new RouteStep(false, "Strike_R", 0, -1));
            steps.add(new RouteStep(true, null, -1, -1));
            steps.add(new RouteStep(false, "Strike_R", 0, -1));
            this.t2 = ContinuationStamp.from(state(2, 48));
        }
    }

    @AfterEach
    void tearDown() {
        AutoPilot.disable();
        AutoPilot.reset();
        SolverConfig.applyPotionStrategy(
                io.github.youqing.sts1solver.search.PotionStrategySnapshot.defaults());
    }

    // ------------------------------------------------------------------- tests

    @Test
    void staleRevisionIsRejectedEvenWithSameFingerprint() {
        long live = AutoPilot.policyRevision();

        assertNull(AutoPilot.rejectionReason("abc", live, "abc"),
                "指纹与代际都一致就该采纳");
        assertEquals("policy_revision", AutoPilot.rejectionReason("abc", live - 1, "abc"),
                "指纹一样但代际旧了 -> 拒绝（局面没变，策略变了）");
        assertEquals("fingerprint", AutoPilot.rejectionReason("abc", live, "xyz"));
        assertNull(AutoPilot.rejectionReason(null, live, null),
                "拿不到 live 指纹时不做指纹校验（保持旧行为）");
    }

    @Test
    void directiveChangeClearsContinuationAndRequestsManualSearch() {
        FakeRoute route = new FakeRoute();
        AutoPilot.adoptPlan(route.steps, 1, route.plan, 0);
        assertTrue(AutoPilot.reuseContinuation(2, route.t2, 0));

        AutoPilot.adoptPlan(route.steps, 1, route.plan, 0);
        SolverConfig.toggleAutoStart();            // 打开「自动计算」
        long before = AutoPilot.policyRevision();
        try {
            assertTrue(AutoPilot.cyclePotionDirective(0, "Fire Potion"));
            assertEquals("saved+recompute", AutoPilot.lastPolicyAction());
            assertEquals(before + 1, AutoPilot.policyRevision(), "每改一次代际 +1");
            assertEquals(SolverPotionDirective.DISABLED,
                    SolverConfig.potionStrategy().directiveFor(0, "Fire Potion"),
                    "Smart 之后的第一档是 Disabled");
            assertFalse(AutoPilot.reuseContinuation(2, route.t2, 0),
                    "策略改了，旧世界线必须作废（否则等于绕过刚改的设置）");
        } finally {
            SolverConfig.toggleAutoStart();
        }
    }

    @Test
    void autoComputeOffSavesPolicyWithoutStartingSearch() {
        SolverConfig.toggleAutoStart();            // 先切到 ON
        SolverConfig.toggleAutoStart();            // 再切回 OFF（保证是关闭态）
        assertFalse(SolverConfig.autoStart());

        assertTrue(AutoPilot.cyclePotionDirective(0, "Fire Potion"));
        assertEquals("saved", AutoPilot.lastPolicyAction(), "自动计算关着就只保存");
        assertFalse(io.github.youqing.sts1solver.search.AsyncSearch.busy(),
                "不应发起搜索");
    }

    @Test
    void policyChangeDuringDeploymentIsRejected() {
        AutoPilot.toggle();                        // AUTO 接管中（纯标志位）
        long before = AutoPilot.policyRevision();

        assertFalse(AutoPilot.cyclePotionDirective(0, "Fire Potion"),
                "自动部署/执行中必须拒绝修改");
        assertEquals("rejected:executing", AutoPilot.lastPolicyAction());
        assertEquals(before, AutoPilot.policyRevision(), "被拒绝时不许动代际");
        assertEquals(SolverPotionDirective.SMART,
                SolverConfig.potionStrategy().directiveFor(0, "Fire Potion"),
                "被拒绝时不许改配置");
    }

    @Test
    void directiveCyclesSmartDisabledForceSmart() {
        assertEquals(SolverPotionDirective.SMART,
                SolverConfig.potionStrategy().directiveFor(0, "Fire Potion"));

        assertTrue(AutoPilot.cyclePotionDirective(0, "Fire Potion"));
        assertEquals(SolverPotionDirective.DISABLED,
                SolverConfig.potionStrategy().directiveFor(0, "Fire Potion"));

        assertTrue(AutoPilot.cyclePotionDirective(0, "Fire Potion"));
        assertEquals(SolverPotionDirective.FORCE,
                SolverConfig.potionStrategy().directiveFor(0, "Fire Potion"));

        assertTrue(AutoPilot.cyclePotionDirective(0, "Fire Potion"));
        assertEquals(SolverPotionDirective.SMART,
                SolverConfig.potionStrategy().directiveFor(0, "Fire Potion"));
        assertEquals(0, SolverConfig.potionStrategy().storageDirectiveCount(),
                "转回 Smart 要把覆盖删掉");
    }

    @Test
    void oldPotionStepCannotDrinkSameIdFromDifferentSlot() {
        RouteStep second = RouteStep.potion("Fire Potion", 1, 0, -1);

        assertTrue(second.matchesPotionAt(1, "Fire Potion"));
        assertFalse(second.matchesPotionAt(0, "Fire Potion"),
                "同 ID 换了槽位就不算同一步，绝不能喝另一瓶冒充");
        assertFalse(second.matchesPotionAt(1, "Block Potion"));
        assertFalse(new RouteStep(false, "Strike_R", 0, -1)
                .matchesPotionAt(0, "Fire Potion"), "打牌步骤不是喝药步骤");
    }

    @Test
    void changingPolicyPreventsOldContinuationReuse() {
        FakeRoute route = new FakeRoute();
        AutoPilot.adoptPlan(route.steps, 1, route.plan, 0);

        assertTrue(AutoPilot.cyclePotionDirective(0, "Fire Potion"));

        assertFalse(AutoPilot.reuseContinuation(2, route.t2, 0),
                "改策略之后旧 continuation 不许再用");
    }

    @Test
    void presetRejectsWithoutSnapshot() {
        // 没有策略现状快照（还没抓过局面）时不能瞎猜槽位
        assertFalse(AutoPilot.applyPotionPreset(
                io.github.youqing.sts1solver.search.SolverPotionPreset.ALL_FORCED));
        assertEquals("rejected:no_snapshot", AutoPilot.lastPolicyAction());
    }

    @Test
    void globalPolicyIsPersistedThroughConfig() {
        assertEquals(SolverPotionPolicy.SMART, SolverConfig.potionStrategy().globalPolicy());
        assertNotNull(SolverConfig.parsePotionStrategy(null));
    }
}
