package io.github.youqing.sts1solver.verify;

import io.github.youqing.sts1solver.search.ContinuationPlan;
import io.github.youqing.sts1solver.search.PlanAction;
import io.github.youqing.sts1solver.search.RouteStep;
import io.github.youqing.sts1solver.search.SearchNode;
import io.github.youqing.sts1solver.sim.SimCard;
import io.github.youqing.sts1solver.sim.SimMonster;
import io.github.youqing.sts1solver.sim.SimState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「没有生还路线」时停手交还控制（蓝本 {@code StopFullAutoOnDeathTurn}，默认开）。
 *
 * <p>蓝本 {@code SolverController.cs:2783}：{@code result.DeathTurn == result.StartTurnNumber}
 * 时**不部署**、关掉全自动、显示「已暂停执行 / 第 N 回合 预计死亡」。
 * 这里钉三件事：判定只认「deathTurn == 当前回合」、停手真的停、面板有话说。
 *
 * <p>另外顺手钉住「路线不再闪」的实现前提：回合翻页只把预览标成陈旧，不清空。
 */
class NoSurvivingRouteTest {

    private static SimState state(int turn, int hp) {
        SimState s = new SimState();
        s.turn = turn;
        s.hp = hp;
        s.maxHp = 80;
        s.energy = 3;
        s.hand.add(new SimCard("Strike_R", false, 1, 6, "ATTACK"));
        s.monsters.add(new SimMonster("JawWorm", 40, 44, 0,
                java.util.Collections.<String, Integer>emptyMap()));
        return s;
    }

    @AfterEach
    void tearDown() {
        AutoPilot.disable();
        AutoPilot.reset();
    }

    @Test
    void stopOnlyWhenDeathIsThisTurn() {
        assertTrue(AutoPilot.shouldStopForDeathTurn(3, 3), "预计本回合死 -> 停手");
        assertFalse(AutoPilot.shouldStopForDeathTurn(4, 3), "预计下一回合才死 -> 继续打");
        assertFalse(AutoPilot.shouldStopForDeathTurn(-1, 3), "不会死 -> 不停");
        assertFalse(AutoPilot.shouldStopForDeathTurn(3, 0), "读不到真实回合时不做决定");
        assertFalse(AutoPilot.shouldStopForDeathTurn(0, 0));
    }

    @Test
    void noticeSaysWhyAndWhichTurn() {
        String text = AutoPilot.deathNotice(5);
        assertTrue(text.contains("预计死亡"), text);
        assertTrue(text.contains("第 5 回合"), "要说清是哪一回合：" + text);
        assertTrue(text.contains("交还"), "要让用户知道操作权回来了：" + text);
    }

    @Test
    void applyingTheStopTurnsAutoOffAndClearsTheTarget() {
        AutoPilot.toggle();                          // 打开 AUTO（纯标志位）
        assertTrue(AutoPilot.armBoundedTarget(6, 1));
        assertTrue(AutoPilot.enabled());

        assertTrue(AutoPilot.applyDeathTurnStop(6, 6));

        assertFalse(AutoPilot.enabled(), "预计本回合死亡必须停手，不能继续自动打");
        assertEquals(-1, AutoPilot.stopAfterTurn(), "停手同时交还停止目标");
    }

    @Test
    void applyingTheStopIsANoOpForLaterDeaths() {
        AutoPilot.toggle();
        assertFalse(AutoPilot.applyDeathTurnStop(7, 6));
        assertTrue(AutoPilot.enabled(), "还没到那一回合，不该停");
    }

    /**
     * 蓝本只在两种情况下停手：{@code DeathTurn == 本回合}（{@code StopFullAutoOnDeathTurn}）
     * 和回合末实时风险投影会死（{@code StopFullAutoOnLiveRiskDeath}）。
     *
     * <p>{@code Survives=false} **不是**停手条件 —— 它只是 {@code FinalPlanOrdering.cs:250-252}
     * 的一个排序键（没赢的话活着的排前面）。以前这里额外造了一道「无生还就禁止出牌」的闸，
     * 蓝本没有，已按纠偏计划移除；这条测试钉住「不许把它加回来」。
     */
    @Test
    void noSurvivingRouteDoesNotInventDeploymentBan() {
        AutoPilot.toggle();
        assertTrue(AutoPilot.enabled());

        // 路线预计第 5 回合死，而现在才第 3 回合：蓝本照样部署（Deploy the plan）
        assertFalse(AutoPilot.applyDeathTurnStop(5, 3),
                "只有「本回合就死」才拦，更晚的死亡不拦");
        assertTrue(AutoPilot.enabled(),
                "Survives=false 不构成停手条件：蓝本没有「无生还路线禁止出牌」这道闸");
    }

    /** 蓝本 LiveEndTurnRiskEvaluator 的等价物：投影会死就不许结束回合。 */
    @Test
    void endTurnRiskUsesProjectedHp() {
        SimState lethal = state(3, 5);
        lethal.monsters.get(0).intentDmg = 10;
        assertTrue(AutoPilot.endTurnWouldKillUs(lethal), "5 血挨 10 -> 会死");

        SimState blocked = state(3, 5);
        blocked.monsters.get(0).intentDmg = 10;
        blocked.block = 8;
        assertFalse(AutoPilot.endTurnWouldKillUs(blocked), "8 格挡之后只掉 2 -> 不会死");

        SimState safe = state(3, 40);
        safe.monsters.get(0).intentDmg = 10;
        assertFalse(AutoPilot.endTurnWouldKillUs(safe));
        assertFalse(AutoPilot.endTurnWouldKillUs(null), "拿不到局面就不拦");
    }

    /** 回合翻页只把预览标成陈旧（用户报的「每回合闪一下」就是以前直接清空）。 */
    @Test
    void clearingThePlanKeepsThePreviewForDisplay() {
        SimState state = state(1, 50);
        SearchNode root = new SearchNode(state, null, null, 0, 0L);
        SearchNode play = new SearchNode(state, PlanAction.play(0, -1), root, 1, 0L);
        List<RouteStep> steps = new ArrayList<RouteStep>();
        steps.add(new RouteStep(false, "Strike_R", 0, -1));

        AutoPilot.installRoute(steps, 1, ContinuationPlan.empty(), 0);
        assertFalse(AutoPilot.routePreviewStale(), "刚采纳的路线是最新的");
        assertNotNull(AutoPilot.routePreview());

        AutoPilot.clearPlan();
        assertTrue(AutoPilot.routePreviewStale(), "重算期间标成陈旧");
        assertNotNull(AutoPilot.routePreview(),
                "预览必须留着 —— 清空会让面板先变「planning…」再变回路线，就是用户说的「闪一下」");
        assertTrue(AutoPilot.pendingSteps().isEmpty(), "执行队列该清空");

        AutoPilot.reset();
        assertNull(AutoPilot.routePreview(), "换战斗才真的清掉");
    }
}
