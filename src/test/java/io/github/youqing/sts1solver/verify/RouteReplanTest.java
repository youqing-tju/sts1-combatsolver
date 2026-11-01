package io.github.youqing.sts1solver.verify;

import io.github.youqing.sts1solver.search.ContinuationPlan;
import io.github.youqing.sts1solver.search.ContinuationStamp;
import io.github.youqing.sts1solver.search.PlanAction;
import io.github.youqing.sts1solver.search.RouteStep;
import io.github.youqing.sts1solver.search.SearchNode;
import io.github.youqing.sts1solver.sim.SimCard;
import io.github.youqing.sts1solver.sim.SimMonster;
import io.github.youqing.sts1solver.sim.SimState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 路线**不冻结**：回合开始只认精确 Continuation，失配就从根重算。
 *
 * <p>本文件取代原来的 {@code AdoptRouteTest}。那份测试把三条蓝本没有的行为钉成了合同：
 * <ol>
 *   <li>默认采纳并冻结（「按序打到底」）；</li>
 *   <li>影子/实机失配仍然照打；</li>
 *   <li>单步走不通只剔掉那一步。</li>
 * </ol>
 * 蓝本的回合开始只有一条路（{@code SolverController.cs:1077-1140}）：
 * {@code ContinuationStamp.CaptureLive} 逐字段命中 → 续跑；否则 {@code ContinuationSource = null}
 * 并从根重搜。部署阶段走不通也一样（{@code SearchReason.DeploymentDrift}，同文件 2925-3010）：
 * 清 continuation source、结束这次部署、重新搜索 —— **不是**只剔一步继续走。
 *
 * <p>{@code RouteFrozen} 是另一套机制（{@code SolverController.cs:146-160}）：
 * 默认 false、只在非搜索/非部署且有 retained route 时可设、设 true 会关 FullAuto 并取消
 * deferred search。本轮没有完整复刻它，所以这里也不留半成品的「伪冻结」。
 */
class RouteReplanTest {

    private static SimState state(int turn, int hp) {
        SimState s = new SimState();
        s.turn = turn;
        s.hp = hp;
        s.maxHp = 80;
        s.energy = 3;
        s.hand.add(new SimCard("Strike_R", false, 1, 6, "ATTACK"));
        s.monsters.add(new SimMonster("JawWorm", 40, 44, 0,
                Collections.<String, Integer>emptyMap()));
        return s;
    }

    private static List<RouteStep> twoTurnSteps() {
        List<RouteStep> steps = new ArrayList<RouteStep>();
        steps.add(new RouteStep(false, "Strike_R", 0, -1));
        steps.add(new RouteStep(true, null, -1, -1));
        steps.add(new RouteStep(false, "Strike_R", 0, -1));
        steps.add(new RouteStep(true, null, -1, -1));
        return steps;
    }

    @AfterEach
    void tearDown() {
        AutoPilot.disable();
        AutoPilot.reset();
    }

    /**
     * 默认不冻结：没有这一回合的精确预测，就不许接着打。
     *
     * <p>蓝本 {@code SolverController.cs:1077}：只有 {@code reason == AutoTurnStart &&
     * ContinuationSource != null} 才会去比对；比对不过就落到 1188 行的重搜分支。
     */
    @Test
    void routeIsNotFrozenByDefault() {
        AutoPilot.installRoute(twoTurnSteps(), 1, ContinuationPlan.empty(), 0);
        assertEquals(4, AutoPilot.pendingSteps().size());

        assertFalse(AutoPilot.onTurnStart(2, null, 0),
                "抓不到 live stamp 就没有精确命中，必须重算");
        assertTrue(AutoPilot.pendingSteps().isEmpty(),
                "默认不冻结：路线要作废，等根重算，而不是接着打旧队列");
    }

    /** 有预测但逐字段对不上 → 同样从根重算（蓝本 replanCause=StateMismatch）。 */
    @Test
    void stateMismatchReplansInsteadOfKeepingAdoptedRoute() {
        List<RouteStep> steps = twoTurnSteps();
        AutoPilot.installRoute(steps, 1, planWithTurnTwo(), 0);

        assertFalse(AutoPilot.onTurnStart(2, ContinuationStamp.from(state(2, 47)), 0),
                "血量不同 = 状态失配");
        assertTrue(AutoPilot.pendingSteps().isEmpty(),
                "失配不许「保留路线继续打」——那正是自创的失配照打");
        assertTrue(AutoPilot.routePreviewStale(), "面板要显示这份路线已作废（重算中）");
    }

    /** 一条只带 T2 边界的续跑缓存（边界状态 = hp 48）。 */
    private static ContinuationPlan planWithTurnTwo() {
        SearchNode root = new SearchNode(state(1, 50), null, null, 0, 0L);
        SearchNode end = new SearchNode(state(2, 48), PlanAction.endTurn(), root, 1, 0L);
        SearchNode next = new SearchNode(state(2, 48), PlanAction.play(0, 0), end, 2, 0L);
        return ContinuationPlan.fromFinalRoute(Arrays.asList(root, end, next));
    }

    /**
     * 部署阶段走不通（牌不在手上 / 现在打不出 / 药水槽对不上）→ 从根重算。
     *
     * <p>蓝本的 {@code DEPLOY_REPLAN} 只有一个动作：{@code _combat.ContinuationSource = null}
     * + {@code RequestSearch(DeploymentDrift)}。它**不会**把这一步从计划里剔掉继续走 ——
     * 剔一步等于用「一份搜索从未推荐过的剩余序列」去执行。
     */
    @Test
    void unplayableStepReplansFromRootInsteadOfDroppingOnlyThatStep() {
        List<RouteStep> steps = twoTurnSteps();
        AutoPilot.installRoute(steps, 1, ContinuationPlan.empty(), 0);

        RouteStep step = AutoPilot.nextRouteStep();
        assertNotNull(step);
        for (int i = 0; i < 3; i++) {
            assertEquals(AutoPilot.OUTCOME_REPLAN,
                    AutoPilot.deploymentDrift(step, "not_playable"),
                    "每一次部署漂移都必须回 REPLAN");
        }
        assertTrue(AutoPilot.pendingSteps().isEmpty(),
                "重算 = 整条队列作废；剔一步会剩 3 步继续打，那是自创行为");
    }

    /** 计划跑完 / 换战斗才清：这两条路径必须真的清，否则会拿上一场的路线接着打。 */
    @Test
    void clearingHappensOnlyWhenTheRouteIsReallyDead() {
        AutoPilot.installRoute(twoTurnSteps(), 1, ContinuationPlan.empty(), 0);
        AutoPilot.clearPlan();
        assertTrue(AutoPilot.pendingSteps().isEmpty(), "计划作废时队列必须清空");

        AutoPilot.installRoute(twoTurnSteps(), 1, ContinuationPlan.empty(), 0);
        AutoPilot.reset();
        assertTrue(AutoPilot.pendingSteps().isEmpty());
        assertTrue(AutoPilot.routePreview() == null, "换战斗要真的清掉预览");
    }

    @Test
    void installRouteKeepsPreviewAndStepsInSync() {
        List<RouteStep> steps = twoTurnSteps();
        AutoPilot.installRoute(steps, 3, ContinuationPlan.empty(), 0);
        assertNotNull(AutoPilot.routePreview());
        assertEquals(2, AutoPilot.routePreview().turns().size(), "两步 END_TURN = 两个回合");
        assertEquals(3, AutoPilot.routePreview().turns().get(0).absoluteTurn(),
                "行号是绝对回合（从装配时那一回合起算）");
        assertEquals(steps.get(0), AutoPilot.nextRouteStep());
    }
}
