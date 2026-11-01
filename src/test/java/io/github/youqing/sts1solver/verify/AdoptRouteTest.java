package io.github.youqing.sts1solver.verify;

import io.github.youqing.sts1solver.search.ContinuationPlan;
import io.github.youqing.sts1solver.search.RouteStep;
import io.github.youqing.sts1solver.search.SearchNode;
import io.github.youqing.sts1solver.search.PlanAction;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「开局算一次，按序打到底」——蓝本 {@code AdoptRoute} / {@code RouteFrozen} 语义。
 *
 * <p>用户明确要求（2026-11-01）：「开局花时间把全局路线规划好，然后一口气打完」。
 * 之前的「每回合精确校验、对不上就重搜」是蓝本的**严格模式**，在影子还不够准时等于每回合重算，
 * 用户看到的就是「路线一直在变」。
 *
 * <p>这里钉住三件事：
 * <ol>
 *   <li>回合翻页**不丢队列**（后续回合的步骤本来就在队列里）；</li>
 *   <li>只有「用户手工动过手」才丢；</li>
 *   <li>丢不丢、留不留，是纯数据判定，不依赖真机。</li>
 * </ol>
 */
class AdoptRouteTest {

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

    @Test
    void routeSurvivesTurnBoundariesInAdoptMode() {
        List<RouteStep> steps = twoTurnSteps();
        AutoPilot.adoptPlan(steps, 1, ContinuationPlan.empty(), 0);
        assertEquals(4, AutoPilot.pendingSteps().size());

        // 回合翻页：采纳模式下队列原样留着，只把「这份计划属于哪回合」推一格
        AutoPilot.keepRouteAcrossTurns(2);

        assertEquals(4, AutoPilot.pendingSteps().size(),
                "后续回合的步骤本来就在队列里，翻页不该丢");
        assertFalse(AutoPilot.routePreviewStale(), "路线仍在生效，不是「重算中」");
        assertNotNull(AutoPilot.routePreview());
    }

    @Test
    void routeSurvivesSeveralTurnsWithoutRebuilding() {
        AutoPilot.adoptPlan(twoTurnSteps(), 1, ContinuationPlan.empty(), 0);
        for (int turn = 2; turn <= 4; turn++) {
            AutoPilot.keepRouteAcrossTurns(turn);
            assertEquals(4, AutoPilot.pendingSteps().size(),
                    "第 " + turn + " 回合不该重建队列");
        }
    }

    @Test
    void onlyManualInputDropsTheRoute() {
        assertTrue(AutoPilot.keepRouteOnTurnChange(false),
                "没人动过手就接着打（采纳模式默认开）");
        assertFalse(AutoPilot.keepRouteOnTurnChange(true),
                "用户手工出过牌 -> 路线已经不是他要的，必须重算");
    }

    /** 计划跑完 / 换战斗才清：这两条路径必须真的清，否则会拿上一场的路线接着打。 */
    @Test
    void clearingHappensOnlyWhenTheRouteIsReallyDead() {
        AutoPilot.adoptPlan(twoTurnSteps(), 1, ContinuationPlan.empty(), 0);
        AutoPilot.clearPlan();
        assertTrue(AutoPilot.pendingSteps().isEmpty(), "计划作废时队列必须清空");

        AutoPilot.adoptPlan(twoTurnSteps(), 1, ContinuationPlan.empty(), 0);
        AutoPilot.reset();
        assertTrue(AutoPilot.pendingSteps().isEmpty());
        assertTrue(AutoPilot.routePreview() == null, "换战斗要真的清掉预览");
    }

    @Test
    void adoptPlanKeepsPreviewAndStepsInSync() {
        List<RouteStep> steps = twoTurnSteps();
        AutoPilot.adoptPlan(steps, 3, ContinuationPlan.empty(), 0);
        assertNotNull(AutoPilot.routePreview());
        assertEquals(2, AutoPilot.routePreview().turns().size(), "两步 END_TURN = 两个回合");
        assertEquals(3, AutoPilot.routePreview().turns().get(0).absoluteTurn(),
                "行号是绝对回合（从采纳时那一回合起算）");
        assertEquals(steps.get(0), AutoPilot.nextRouteStep());
    }

    /** 只用来确认这条测试自己没跑偏：路线首步是打牌，不是 END_TURN。 */
    @Test
    void fixtureMatchesItsIntent() {
        SearchNode root = new SearchNode(state(1, 50), null, null, 0, 0L);
        SearchNode play = new SearchNode(state(1, 50), PlanAction.play(0, 0), root, 1, 0L);
        assertEquals(PlanAction.Kind.PLAY_CARD, play.action.kind);
        assertTrue(twoTurnSteps().get(0).cardKey != null);
    }
}
