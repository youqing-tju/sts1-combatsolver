package io.github.youqing.sts1solver.search;

import io.github.youqing.sts1solver.sim.SimState;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 逐回合卖血注记：蓝本 {@code CombatBeamSolver.Terminal.cs:30-145} 的 {@code AnnotateTurnOutcomes}。
 *
 * <p>「卖血」在这里不是「掉了多少血」，而是**可避免的掉血**：
 * 同一回合、同一回合起始局面、同一组药水槽位，最好的那条路线掉了 X 血，
 * 另一条掉了 X+Y —— 那 Y 就是它自己愿意卖的。这条量进最终排序第 15 键（升序），
 * 也是「同样的胜利下少挨几下」这句话唯一真正的实现。
 *
 * <p>分组键是 {@code (回合号, 回合起始状态指纹, 药水槽位掩码)}，与蓝本
 * {@code (Turn, TurnStart.StateKey, PotionSlotsUsed)} 一一对应 —— 是**状态**而不是节点身份：
 * 两条不同路径走到同一个回合起始局面时，本来就该共用同一把尺子。
 *
 * <p>纯数据：整条动作链都是 {@link SearchNode}，不启动搜索、不碰真机对象。
 */
class TurnOutcomeTest {

    /** 同组里掉血最少的那条 sold = 0，其余按差值。 */
    @Test
    void soldIsTheExcessOverTheCheapestRouteInTheSameGroup() {
        SearchNode cheap = endTurn(root(20), hp(18));        // 掉 2
        SearchNode pricey = endTurn(root(20), hp(16));       // 掉 4
        List<SearchNode> annotated = annotate(cheap, pricey);

        assertEquals(0, annotated.get(0).outcome.soldHp, "掉得最少的那条没有「可避免」的血");
        assertEquals(2, annotated.get(1).outcome.soldHp, "4 - 2 = 2");
        assertEquals(0, annotated.get(0).futureSoldHp);
        assertEquals(2, annotated.get(1).futureSoldHp);
    }

    /** 回合起始局面不同（这里：起始血量不同）就不共享最低值。 */
    @Test
    void differentTurnStartStatesDoNotShareAMinimum() {
        SearchNode cheapFromOther = endTurn(root(20), hp(18));    // 那条世界线掉 2
        SearchNode pricey = endTurn(root(30), hp(26));            // 另一个起点掉 4

        List<SearchNode> annotated = annotate(cheapFromOther, pricey);
        assertEquals(0, annotated.get(0).outcome.soldHp);
        assertEquals(0, annotated.get(1).outcome.soldHp,
                "各组只有自己一条 -> 自己就是自己的最优，谁都没有可避免的掉血");
    }

    /** 药水槽位不同也不共享最低值（喝药本身就是另一条世界线）。 */
    @Test
    void differentPotionSlotsDoNotShareAMinimum() {
        SearchNode noPotion = endTurn(root(20), hp(16));               // 掉 4
        SearchNode withPotion = endTurn(withPotion(root(20), 0), hp(18));  // 掉 2

        List<SearchNode> annotated = annotate(noPotion, withPotion);
        assertEquals(0, annotated.get(0).outcome.soldHp);
        assertEquals(0, annotated.get(1).outcome.soldHp);
    }

    /** 同一组内两条路线都掉同样的血 -> 都卖 0。 */
    @Test
    void equalLossesSellNothing() {
        List<SearchNode> annotated = annotate(endTurn(root(20), hp(18)), endTurn(root(20), hp(18)));
        assertEquals(0, annotated.get(0).outcome.soldHp);
        assertEquals(0, annotated.get(1).outcome.soldHp);
    }

    /** 跨回合累计：第二回合的 futureSold 必须接着第一回合继续加。 */
    @Test
    void futureSoldAccumulatesAcrossTurns() {
        SearchNode firstTurn = root(20);
        List<SearchNode> first = annotate(endTurn(firstTurn, hp(18)), endTurn(firstTurn, hp(16)));
        assertEquals(2, first.get(1).futureSoldHp);

        SearchNode secondStart = nextTurn(first.get(1));
        List<SearchNode> second = annotate(endTurn(secondStart, hp(16)),
                endTurn(secondStart, hp(13)));
        assertEquals(2, second.get(0).futureSoldHp, "第一回合卖 2，这一回合没再卖");
        assertEquals(5, second.get(1).futureSoldHp, "2 + 3 = 5");
    }

    /**
     * 边界不可比的候选（{@code PendingChoice} / {@code UnsupportedEffect}）不参与最低值，
     * 自己也不背卖血 —— 它根本没走完这一回合，拿它当基准会把所有正常路线都算成「卖血」。
     */
    @Test
    void incomparableBoundariesNeitherSetNorPayTheMinimum() {
        SearchNode truncated = endTurn(root(20), hp(10), SearchBoundaryReason.PENDING_CHOICE);
        SearchNode normal = endTurn(root(20), hp(16));       // 掉 4

        List<SearchNode> annotated = annotate(truncated, normal);
        assertEquals(0, annotated.get(0).outcome.soldHp, "被截断的路线 sold 恒为 0");
        assertEquals(0, annotated.get(1).outcome.soldHp,
                "最低值只看可比的候选，所以这 4 点掉血就是它自己的最优");
    }

    /** 卖血只增不减。 */
    @Test
    void futureSoldIsMonotone() {
        SearchNode turnStart = root(20);
        List<SearchNode> first = annotate(endTurn(turnStart, hp(16)), endTurn(turnStart, hp(18)));
        assertEquals(2, first.get(0).futureSoldHp);

        List<SearchNode> second = annotate(endTurn(nextTurn(first.get(0)), hp(16)));
        assertTrue(second.get(0).futureSoldHp >= first.get(0).futureSoldHp);
    }

    // ---------------------------------------------------------------- 夹具

    private static SearchNode root(int hp) {
        SimState state = new SimState();
        state.hp = hp;
        state.maxHp = 80;
        state.turn = 1;
        return new SearchNode(state, null, null, 0, 0L);
    }

    /** 一个「回合起始」节点：和父节点同回合、同状态内容（指纹相同 = 同一把尺子）。 */
    private static SearchNode turnStart(int hp) {
        return root(hp);
    }

    private static SimState hp(int hp) {
        SimState state = new SimState();
        state.hp = hp;
        state.maxHp = 80;
        state.turn = 1;
        return state;
    }

    /** 造一条「本回合打到 {@code ended} 这个局面，然后结束回合」的路线。 */
    private static SearchNode endTurn(SearchNode start, SimState ended) {
        return endTurn(start, ended, SearchBoundaryReason.NONE);
    }

    private static SearchNode endTurn(SearchNode start, SimState ended,
                                      SearchBoundaryReason boundary) {
        SimState after = ended.copy();
        after.turn = start.state.turn;                 // 还在同一回合
        // 累计掉血是**整场**单调累计（蓝本 CumulativePlayerHpLost 从不按回合清零）
        after.cumulativeHpLost = start.state.cumulativeHpLost
                + Math.max(0, start.state.hp - ended.hp);
        SearchNode play = new SearchNode(after, PlanAction.play(0, 0), start, start.depth + 1,
                start.score, start.rank, "Strike_R", 0, 0, boundary);

        SimState next = ended.copy();
        next.turn = start.state.turn + 1;              // 结束回合 -> 回合号 +1
        next.cumulativeHpLost = after.cumulativeHpLost;
        return new SearchNode(next, PlanAction.endTurn(), play, play.depth + 1, play.score,
                play.rank, null, 0, 0, boundary);
    }

    /** 喝过药的版本：在回合中间插入一步用掉 0 号槽。 */
    private static SearchNode withPotion(SearchNode start, int slot) {
        SimState after = start.state.copy();
        return new SearchNode(after, PlanAction.potion(slot, -1), start, start.depth + 1,
                start.score, start.rank, "Fire Potion", 0, 1);
    }

    /** 下一回合的起点：状态与上一条路线结束时一致。 */
    private static SearchNode nextTurn(SearchNode ended) {
        SimState state = ended.state.copy();
        state.turn = ended.state.turn;
        return new SearchNode(state, null, ended, ended.depth, ended.score);
    }

    private static List<SearchNode> annotate(SearchNode... nodes) {
        List<SearchNode> ended = new ArrayList<SearchNode>();
        for (SearchNode node : nodes) {
            ended.add(node);
        }
        return TurnOutcomeAnnotator.annotate(ended);
    }
}
