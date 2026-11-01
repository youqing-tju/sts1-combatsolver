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
 * 改药水策略的**蓝本链路**（{@code SolverController.SetPotionDirective}，1899-1942 行）。
 *
 * <p>顺序是：校验 → 保存 → {@code ContinuationSource = null} → 自动计算关着就不重算 →
 * 否则请求一次 Manual 搜索。旧的实现多了一个「策略代际」字段来判时效，
 * 蓝本没有这个东西，已按纠偏计划移除；旧结果由重搜的取消语义处理
 * （{@code CancelSearch} ↔ {@code AsyncSearch} 的搜索代际）。
 */
class PotionPolicyChangeTest {

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
            this.plan = io.github.youqing.sts1solver.search.ContinuationFixtures.capture(nodes);
            this.steps = new ArrayList<RouteStep>();
            steps.add(new RouteStep(false, "Strike_R", 0, -1));
            steps.add(new RouteStep(true, null, -1, -1));
            steps.add(new RouteStep(false, "Strike_R", 0, -1));
            this.t2 = ContinuationStamp.captureLive(state(2, 48));
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
    void onlyTheFingerprintDecidesStaleness() {
        assertNull(AutoPilot.rejectionReason("abc", "abc"), "局面没走就该采纳");
        assertEquals("fingerprint", AutoPilot.rejectionReason("abc", "xyz"),
                "局面已经走了 -> 拒绝");
        assertNull(AutoPilot.rejectionReason(null, null),
                "拿不到 live 指纹时不做指纹校验（保持旧行为）");
    }

    @Test
    void directiveChangeClearsContinuationAndRequestsManualSearch() {
        FakeRoute route = new FakeRoute();
        AutoPilot.installRoute(route.steps, 1, route.plan, 0);
        assertTrue(AutoPilot.reuseContinuation(2, route.t2, 0));

        AutoPilot.installRoute(route.steps, 1, route.plan, 0);
        SolverConfig.toggleAutoStart();            // 打开「自动计算」
        try {
            assertTrue(AutoPilot.cyclePotionDirective(0, "Fire Potion"));
            assertEquals("saved+recompute", AutoPilot.lastPolicyAction());
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
        FakeRoute route = new FakeRoute();
        AutoPilot.installRoute(route.steps, 1, route.plan, 0);
        AutoPilot.toggle();                        // AUTO 接管中，且有排队待执行的路线

        assertFalse(AutoPilot.cyclePotionDirective(0, "Fire Potion"),
                "自动部署/执行中必须拒绝修改");
        assertEquals("rejected:executing", AutoPilot.lastPolicyAction());
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
        AutoPilot.installRoute(route.steps, 1, route.plan, 0);

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

    /**
     * 全局档位**不落盘**（蓝本 {@code SolverSettings.cs:94-96} 上带 {@code [JsonIgnore]}）。
     *
     * <p>以前这里钉的是「全局档位要持久化」，那是自创：蓝本每次新设置都回到 Smart，
     * 只持久化每槽指令。
     */
    @Test
    void globalPolicyIsNotPersisted() {
        io.github.youqing.sts1solver.search.PotionStrategySnapshot disabled =
                io.github.youqing.sts1solver.search.PotionStrategySnapshot.defaults()
                        .withGlobalPolicy(SolverPotionPolicy.DISABLED)
                        .withDirective(0, "Fire Potion", SolverPotionDirective.FORCE);

        String stored = disabled.toStorageString();
        assertFalse(stored.contains("policy="), "落盘串里不许出现全局档位：" + stored);
        assertEquals(SolverPotionPolicy.SMART,
                SolverConfig.parsePotionStrategy(stored).globalPolicy(),
                "重新解析必须回到 Smart");
        assertEquals(SolverPotionDirective.FORCE,
                SolverConfig.parsePotionStrategy(stored).directiveFor(0, "Fire Potion"),
                "每槽指令要保留");
        assertNotNull(SolverConfig.parsePotionStrategy(null));
    }
}
