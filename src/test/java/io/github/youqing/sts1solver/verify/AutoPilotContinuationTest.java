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
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 新回合的续跑/重算编排（**纯数据**，不实例化 {@code AbstractDungeon}）。
 *
 * <p>这组测试钉的是「世界线有没有被展开」这件事本身：
 * 上一回合算出来的后续回合，只要真机状态逐字段对得上就必须**接着走**；
 * 对不上时必须说清是哪种对不上，而不是一律「每回合重算」。
 */
class AutoPilotContinuationTest {

    // ------------------------------------------------------------------ fixtures

    private static SimState state(int turn, int hp) {
        SimState s = new SimState();
        s.turn = turn;
        s.hp = hp;
        s.maxHp = 80;
        s.energy = 3;
        s.energyPerTurn = 3;
        s.hand.add(new SimCard("Strike_R", false, 1, 6, "ATTACK"));
        s.draw.add(new SimCard("Defend_R", false, 1, 0, "SKILL"));
        s.monsters.add(new SimMonster("JawWorm", 40, 44, 0,
                Collections.<String, Integer>emptyMap()));
        return s;
    }

    private static SearchNode node(SimState s, PlanAction action, SearchNode parent) {
        return new SearchNode(s, action, parent, parent == null ? 0 : parent.depth + 1, 0L);
    }

    /**
     * 一条四回合的路线：T1 打一张 → END → T2 打 → END → T3 打 → END → T4 打。
     *
     * <p>于是有三个稳定边界（T2/T3/T4），{@code forecastOffset} 分别是 2/4/6。
     */
    private static final class FakeRoute {
        final ContinuationPlan plan;
        final List<RouteStep> steps;
        final ContinuationStamp t2;
        final ContinuationStamp t3;
        final ContinuationStamp t4;

        FakeRoute() {
            List<SearchNode> nodes = new ArrayList<SearchNode>();
            SearchNode root = node(state(1, 50), null, null);
            nodes.add(root);
            SearchNode cur = root;
            int[] hp = {50, 48, 45, 40};
            for (int turn = 1; turn <= 4; turn++) {
                cur = node(state(turn, hp[turn - 1]), PlanAction.play(0, 0), cur);
                nodes.add(cur);
                if (turn < 4) {
                    cur = node(state(turn + 1, hp[turn]), PlanAction.endTurn(), cur);
                    nodes.add(cur);
                }
            }
            this.plan = ContinuationPlan.fromFinalRoute(nodes);
            this.steps = new ArrayList<RouteStep>();
            for (int i = 1; i < nodes.size(); i++) {
                PlanAction a = nodes.get(i).action;
                steps.add(new RouteStep(a.kind == PlanAction.Kind.END_TURN,
                        false, a.kind == PlanAction.Kind.END_TURN ? null : "Strike_R",
                        a.targetIndex, a.choiceIndex));
            }
            this.t2 = ContinuationStamp.from(state(2, 48));
            this.t3 = ContinuationStamp.from(state(3, 45));
            this.t4 = ContinuationStamp.from(state(4, 40));
        }
    }

    @AfterEach
    void tearDown() {
        // 这些是进程级静态状态，不清会把「这次跑出来的路线」带到下一条测试里
        AutoPilot.disable();
        AutoPilot.reset();
    }

    // ------------------------------------------------------------------- tests

    @Test
    void exactNewTurnHitReusesWithoutRequestingSearch() {
        FakeRoute route = new FakeRoute();
        AutoPilot.installRoute(route.steps, 1, route.plan, 0);

        assertTrue(AutoPilot.reuseContinuation(2, route.t2, 0), "状态逐字段相等必须命中");
        assertEquals(route.steps.subList(2, route.steps.size()), AutoPilot.pendingSteps(),
                "命中后执行队列应只剩本回合及以后");
        assertFalse(io.github.youqing.sts1solver.search.AsyncSearch.busy(),
                "命中不许发起新搜索");
    }

    @Test
    void hitKeepsAbsolutePreviewAndAdvancesNextAction() {
        FakeRoute route = new FakeRoute();
        AutoPilot.installRoute(route.steps, 1, route.plan, 0);
        int turnsBefore = AutoPilot.routePreview().turns().size();
        int firstTurnBefore = AutoPilot.routePreview().turns().get(0).absoluteTurn();

        assertTrue(AutoPilot.reuseContinuation(2, route.t2, 0));

        assertEquals(turnsBefore, AutoPilot.routePreview().turns().size(),
                "预览保留整条路线（已打过的回合不该被删掉）");
        assertEquals(firstTurnBefore, AutoPilot.routePreview().turns().get(0).absoluteTurn(),
                "行号是绝对回合，不是「剩余行数」");
        assertEquals(route.steps.get(2), AutoPilot.nextRouteStep(),
                "下一手必须推进到 T2 的第一步");
    }

    @Test
    void missingContinuationLogsReasonAndRequestsSearch() {
        FakeRoute route = new FakeRoute();
        AutoPilot.installRoute(route.steps, 1, route.plan, 0);
        assertTrue(AutoPilot.reuseContinuation(2, route.t2, 0));
        assertTrue(AutoPilot.reuseContinuation(3, route.t3, 0),
                "连着两个回合都该命中 —— 这才叫「世界线被展开」");

        // 路线只覆盖到 T4；T9 没有任何预测
        assertFalse(AutoPilot.reuseContinuation(9, route.t4, 0), "没有这一回合的预测就不能续跑");

        ContinuationDecision decision = new ContinuationDecision();
        decision.adopt(ContinuationPlan.empty(), 0);
        ContinuationDecision.Outcome outcome = decision.evaluate(3, route.t3, 0);
        assertEquals(ContinuationDecision.Reason.CONTINUATION_MISSING, outcome.reason());
        assertFalse(outcome.isHit());
    }

    @Test
    void stateMismatchLogsFirstDifferenceAndRequestsSearch() {
        FakeRoute route = new FakeRoute();
        AutoPilot.installRoute(route.steps, 1, route.plan, 0);

        ContinuationStamp drifted = ContinuationStamp.from(state(2, 47));
        assertFalse(AutoPilot.reuseContinuation(2, drifted, 0), "血量不同就不能续跑");

        ContinuationDecision decision = new ContinuationDecision();
        decision.adopt(route.plan, 0);
        ContinuationDecision.Outcome outcome = decision.evaluate(2, drifted, 0);
        assertEquals(ContinuationDecision.Reason.STATE_MISMATCH, outcome.reason());
        assertTrue(outcome.firstDifference().startsWith("hp:"),
                "必须报首个字段差异，实际：" + outcome.firstDifference());
    }

    @Test
    void manualDivergenceIsNotReportedAsStateMismatch() {
        FakeRoute route = new FakeRoute();
        AutoPilot.installRoute(route.steps, 1, route.plan, 0);
        AutoPilot.toggle();                        // 打开 AUTO（纯标志位，不碰真机）
        AutoPilot.onCardPlayedObserved("Bash");    // 不是我们排的那张 -> 手工输入

        ContinuationDecision decision = new ContinuationDecision();
        decision.adopt(route.plan, 0);
        decision.markManualDivergence();
        ContinuationDecision.Outcome outcome = decision.evaluate(2,
                ContinuationStamp.from(state(2, 47)), 0);
        assertEquals(ContinuationDecision.Reason.MANUAL_DIVERGENCE, outcome.reason(),
                "手工动过手时，就算状态也对不上，也不该报成镜像差异");
    }

    @Test
    void deploymentDriftInvalidatesFutureContinuation() {
        FakeRoute route = new FakeRoute();
        AutoPilot.installRoute(route.steps, 1, route.plan, 0);

        assertFalse(AutoPilot.reuseContinuation(2, route.t2, 3),
                "采纳之后又发生了 3 次镜像失配 = 影子已分叉，不许续跑");

        ContinuationDecision decision = new ContinuationDecision();
        decision.adopt(route.plan, 0);
        ContinuationDecision.Outcome outcome = decision.evaluate(2, route.t2, 3);
        assertEquals(ContinuationDecision.Reason.DEPLOYMENT_DRIFT, outcome.reason());
        assertTrue(outcome.firstDifference().contains("3"),
                "要说明漂移了几次，实际：" + outcome.firstDifference());
    }

    @Test
    void inTurnPotionOrChoiceInvalidatesOnlyCurrentWorldline() {
        FakeRoute route = new FakeRoute();
        AutoPilot.installRoute(route.steps, 1, route.plan, 0);
        assertEquals(route.steps.size(), AutoPilot.pendingSteps().size());

        // 喝药/三选一之后执行层就是走这条路：整条世界线作废（含边界缓存）
        AutoPilot.clearPlan();

        assertTrue(AutoPilot.pendingSteps().isEmpty(), "计划必须清掉");
        assertFalse(AutoPilot.reuseContinuation(2, route.t2, 0),
                "世界线作废后不许再命中");
    }

    @Test
    void combatResetClearsContinuation() {
        FakeRoute route = new FakeRoute();
        AutoPilot.installRoute(route.steps, 1, route.plan, 0);

        AutoPilot.reset();

        assertFalse(AutoPilot.reuseContinuation(2, route.t2, 0), "换战斗后旧世界线必须失效");
        assertTrue(AutoPilot.pendingSteps().isEmpty(), "执行队列也要清空");
    }

    /**
     * 续跑命中之后，队列只剩「本回合及以后」——这是蓝本 {@code TryCreateContinuation}
     * 的语义（保留原 best route，按 StartTurnNumber 切掉本回合之前的动作）。
     */
    @Test
    void reuseKeepsOnlyCurrentAndLaterTurns() {
        FakeRoute route = new FakeRoute();
        AutoPilot.installRoute(route.steps, 1, route.plan, 0);

        assertTrue(AutoPilot.reuseContinuation(2, route.t2, 0));
        assertEquals(route.steps.size() - 2, AutoPilot.pendingSteps().size(),
                "T1 的两步（出牌 + END_TURN）已经执行过，队列里不该再有");

        assertTrue(AutoPilot.reuseContinuation(3, route.t3, 0));
        assertEquals(route.steps.size() - 4, AutoPilot.pendingSteps().size());
    }

    /**
     * 重算之后，旧世界线的缓存必须真的失效 —— 否则下一帧会拿半条缓存去续，
     * 执行出一条搜索从未推荐过的路。
     */
    @Test
    void reuseMissInvalidatesTheWorldlineForGood() {
        FakeRoute route = new FakeRoute();
        AutoPilot.installRoute(route.steps, 1, route.plan, 0);

        assertFalse(AutoPilot.reuseContinuation(2, ContinuationStamp.from(state(2, 47)), 0),
                "状态对不上当然要重搜");

        assertFalse(AutoPilot.reuseContinuation(2, route.t2, 0),
                "miss 已把这条世界线的缓存清掉，不许「再问一次就成了」");
    }
}
