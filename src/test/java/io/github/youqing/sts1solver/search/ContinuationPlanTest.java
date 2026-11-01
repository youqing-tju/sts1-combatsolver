package io.github.youqing.sts1solver.search;

import io.github.youqing.sts1solver.sim.SimCard;
import io.github.youqing.sts1solver.sim.SimMonster;
import io.github.youqing.sts1solver.sim.SimState;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 稳定回合边界的资格与续接判定。
 *
 * <p>只测**纯数据**：{@link SearchNode} 链 + {@link ContinuationStamp}，不碰真机、不跑搜索。
 *
 * <p>这里钉的是两件事：
 * <ol>
 *   <li>**哪些边界能缓存**：只有 {@code END_TURN} 跨到下一回合、玩家没死、怪没全灭、
 *       后面确实还有动作的那些；终局/死亡/无后续一律不缓存（缓存了就会把「已经结束的战斗」
 *       当成还能续跑的世界线）；</li>
 *   <li>**续接怎么判**：同回合且状态文本完全相等才算命中，否则必须给出
 *       {@code ContinuationMissing} 或带首个字段差异的 {@code StateMismatch}。</li>
 * </ol>
 */
class ContinuationPlanTest {

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

    private static SearchNode root(int turn, int hp) {
        return new SearchNode(state(turn, hp), null, null, 0, 0L);
    }

    private static SearchNode play(SearchNode parent, int turn, int hp) {
        return new SearchNode(state(turn, hp), PlanAction.play(0, 0), parent, parent.depth + 1, 0L);
    }

    /** 回合末：节点状态已经是**下一个回合**（与 {@code BeamSolver.endTurnOf} 同口径）。 */
    private static SearchNode endTurn(SearchNode parent, int newTurn, int hp) {
        return new SearchNode(state(newTurn, hp), PlanAction.endTurn(), parent,
                parent.depth + 1, 0L);
    }

    /** 一条「打牌 → 结束回合 → 打牌 → …」的线性路线。 */
    private static List<SearchNode> route(SearchNode... nodes) {
        List<SearchNode> out = new ArrayList<SearchNode>();
        Collections.addAll(out, nodes);
        return out;
    }

    // -------------------------------------------------------------------- tests

    @Test
    void onlyStableEndTurnBoundaryWithFutureActionsIsCached() {
        SearchNode r = root(1, 50);
        SearchNode first = play(r, 1, 50);
        SearchNode t2 = endTurn(first, 2, 48);
        SearchNode second = play(t2, 2, 48);
        SearchNode t3 = endTurn(second, 3, 45);
        SearchNode third = play(t3, 3, 45);

        ContinuationPlan plan = ContinuationPlan.fromFinalRoute(
                route(r, first, t2, second, t3, third));

        assertEquals(2, plan.size(), "只有 T2/T3 两个回合边界能缓存，实际：" + plan.turns());
        assertEquals(java.util.Arrays.asList(Integer.valueOf(2), Integer.valueOf(3)), plan.turns());
        // forecastOffset 是「在本回合动作在完整 steps 里的起点」
        // steps = [play, END_TURN, play, END_TURN, play] → T2 从下标 2 开始，T3 从下标 4 开始
        assertEquals(2, plan.continuations().get(0).forecastOffset);
        assertEquals(4, plan.continuations().get(1).forecastOffset);
    }

    @Test
    void terminalDeathAndVictoryBoundariesAreNotCached() {
        // ① 玩家在边界上是死的
        SearchNode r = root(1, 5);
        SearchNode played = play(r, 1, 5);
        SearchNode dead = endTurn(played, 2, 0);
        SearchNode afterDeath = play(dead, 2, 0);
        assertTrue(ContinuationPlan.fromFinalRoute(route(r, played, dead, afterDeath)).isEmpty(),
                "玩家已死不能作为续跑起点");

        // ② 敌人在边界上全灭（战斗已经赢了）
        SearchNode r2 = root(1, 50);
        SearchNode played2 = play(r2, 1, 50);
        SearchNode victory = endTurn(played2, 2, 50);
        for (SimMonster m : victory.state.monsters) {
            m.hp = 0;
        }
        SearchNode afterVictory = play(victory, 2, 50);
        assertTrue(ContinuationPlan.fromFinalRoute(route(r2, played2, victory, afterVictory))
                .isEmpty(), "敌人全灭之后不该再有续跑边界");

        // ③ 回合边界之后没有动作（路线到头了）
        SearchNode r3 = root(1, 50);
        SearchNode played3 = play(r3, 1, 50);
        SearchNode last = endTurn(played3, 2, 50);
        assertTrue(ContinuationPlan.fromFinalRoute(route(r3, played3, last)).isEmpty(),
                "末尾边界没有后续动作，缓存了也执行不了");

        // ④ 过程中死掉：边界存在但那是「死亡回合同」的另一条线
        SearchNode r4 = root(1, 50);
        SearchNode p4 = play(r4, 1, 50);
        SearchNode t2 = endTurn(p4, 2, 50);
        SearchNode p4b = play(t2, 2, 8);
        SearchNode deadLater = endTurn(p4b, 3, 0);
        SearchNode after4 = play(deadLater, 3, 0);
        ContinuationPlan plan = ContinuationPlan.fromFinalRoute(
                route(r4, p4, t2, p4b, deadLater, after4));
        assertEquals(1, plan.size(), "只该留下 T2 那一个边界，实际：" + plan.turns());
        assertEquals(2, plan.turns().get(0).intValue());
    }

    @Test
    void exactStampHitsWithoutChangingForecastOffset() {
        SearchNode r = root(1, 50);
        SearchNode first = play(r, 1, 50);
        SearchNode t2 = endTurn(first, 2, 48);
        SearchNode second = play(t2, 2, 48);
        SearchNode t3 = endTurn(second, 3, 45);
        SearchNode third = play(t3, 3, 45);
        ContinuationPlan plan = ContinuationPlan.fromFinalRoute(
                route(r, first, t2, second, t3, third));

        ContinuationPlan.Lookup hit = plan.lookup(2,
                ContinuationStamp.from(state(2, 48)));
        assertEquals(ContinuationPlan.Lookup.Status.HIT, hit.status());
        assertNotNull(hit.matched());
        assertEquals(2, hit.matched().startTurnNumber);
        assertEquals(2, hit.forecastOffset(), "命中不许改动预测偏移");
        assertEquals("none", hit.firstDifference());

        ContinuationPlan.Lookup later = plan.lookup(3,
                ContinuationStamp.from(state(3, 45)));
        assertEquals(ContinuationPlan.Lookup.Status.HIT, later.status());
        assertEquals(4, later.forecastOffset());
    }

    @Test
    void missingTurnIsContinuationMissing() {
        SearchNode r = root(1, 50);
        SearchNode first = play(r, 1, 50);
        SearchNode t2 = endTurn(first, 2, 48);
        SearchNode second = play(t2, 2, 48);
        ContinuationPlan plan = ContinuationPlan.fromFinalRoute(route(r, first, t2, second));

        ContinuationPlan.Lookup miss = plan.lookup(7, ContinuationStamp.from(state(7, 40)));
        assertEquals(ContinuationPlan.Lookup.Status.CONTINUATION_MISSING, miss.status());
        assertEquals("none", miss.firstDifference(), "没缓存过这一回合就谈不上「字段差异」");
        assertFalse(miss.isHit());

        ContinuationPlan empty = ContinuationPlan.empty();
        assertEquals(ContinuationPlan.Lookup.Status.CONTINUATION_MISSING,
                empty.lookup(2, ContinuationStamp.from(state(2, 48))).status());
        assertTrue(empty.isEmpty());
    }

    @Test
    void sameTurnDifferentStampIsStateMismatchWithDifference() {
        SearchNode r = root(1, 50);
        SearchNode first = play(r, 1, 50);
        SearchNode t2 = endTurn(first, 2, 48);
        SearchNode second = play(t2, 2, 48);
        ContinuationPlan plan = ContinuationPlan.fromFinalRoute(route(r, first, t2, second));

        ContinuationPlan.Lookup mismatch = plan.lookup(2, ContinuationStamp.from(state(2, 47)));
        assertEquals(ContinuationPlan.Lookup.Status.STATE_MISMATCH, mismatch.status());
        assertTrue(mismatch.firstDifference().startsWith("hp:"),
                "必须给首个字段差异，实际：" + mismatch.firstDifference());
        assertTrue(mismatch.firstDifference().contains("expected=48")
                        && mismatch.firstDifference().contains("actual=47"),
                "差异要带双方取值，实际：" + mismatch.firstDifference());
        assertEquals(-1, mismatch.forecastOffset(), "没命中就不该给预测偏移");
    }

    @Test
    void reuseDropsCurrentAndOlderContinuations() {
        SearchNode r = root(1, 50);
        SearchNode first = play(r, 1, 50);
        SearchNode t2 = endTurn(first, 2, 48);
        SearchNode second = play(t2, 2, 48);
        SearchNode t3 = endTurn(second, 3, 45);
        SearchNode third = play(t3, 3, 45);
        SearchNode t4 = endTurn(third, 4, 40);
        SearchNode fourth = play(t4, 4, 40);
        ContinuationPlan plan = ContinuationPlan.fromFinalRoute(
                route(r, first, t2, second, t3, third, t4, fourth));

        ContinuationPlan after = plan.onlyAfter(3);
        assertEquals(1, after.size(), "T3 及更早的边界用完就该丢掉，实际：" + after.turns());
        assertEquals(4, after.turns().get(0).intValue());
        assertEquals(ContinuationPlan.Lookup.Status.CONTINUATION_MISSING,
                after.lookup(2, ContinuationStamp.from(state(2, 48))).status(),
                "已消费的回合不能再命中（否则会倒着走世界线）");
        assertEquals(3, plan.size(), "onlyAfter 不能改动原计划");
    }

    @Test
    void brokenParentChainIsRejected() {
        SearchNode r = root(1, 50);
        SearchNode first = play(r, 1, 50);
        SearchNode t2 = endTurn(first, 2, 48);
        SearchNode orphan = play(root(2, 48), 2, 48);      // 父链断了

        assertThrows(IllegalArgumentException.class,
                () -> ContinuationPlan.fromFinalRoute(route(r, first, t2, orphan)),
                "父链不一致必须显式报错，不能静默缓存半条世界线");
        assertThrows(IllegalArgumentException.class,
                () -> ContinuationPlan.fromFinalRoute(route(t2, first)),
                "首个节点必须是根（action 为 null）");
        assertThrows(IllegalArgumentException.class,
                () -> ContinuationPlan.fromFinalRoute(new ArrayList<SearchNode>()),
                "空路线不是合法输入");
    }
}
