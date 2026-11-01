package io.github.youqing.sts1solver.search;

import io.github.youqing.sts1solver.sim.SimState;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 卖血罚分只能扣**一次**：蓝本 {@code Terminal.cs:147-158} 的
 * {@code scoreWithoutSoldPenalty} 那一步。
 *
 * <p>为什么值得单独钉：罚分在两条路径上都会加 —— 子节点创建时按父节点已累计的值先扣一遍
 * （{@code ExpansionPlan.cs:192}），回合结束时再按本回合真实值修正。
 * 修正时若直接 `score + futureSold * penalty`，同一个数字就被罚了两次，
 * 表现为「路线越往后越不敢打」这种没有任何报错的行为漂移。
 */
class SoldHpAnnotationTest {

    /** 第一次注记：从 0 变成 2 点卖血，正好扣 2 份罚分。 */
    @Test
    void annotationChargesExactlyTheNewSoldAmount() {
        SearchNode root = root();
        SearchNode cheap = endTurn(root, 18);
        SearchNode pricey = endTurn(root, 16);

        List<SearchNode> annotated = annotate(cheap, pricey);
        assertEquals(0L, penaltyOf(annotated.get(0)), "卖 0 点就没有罚分");
        assertEquals(2L, penaltyOf(annotated.get(1)), "卖 2 点扣 2 份");
    }

    /** 用同一份值重复注记不许累加罚分（幂等）。 */
    @Test
    void annotatingTwiceWithTheSameValueDoesNotDoubleCharge() {
        SearchNode root = root();
        List<SearchNode> once = annotate(endTurn(root, 16), endTurn(root, 18));
        SearchNode pricey = once.get(0);
        assertEquals(2L, penaltyOf(pricey));

        // 再注记一次：这一回合的最优没有变，卖血仍然是 2
        List<SearchNode> twice = annotate(pricey, once.get(1));
        assertEquals(2L, penaltyOf(twice.get(0)),
                "同一个 2 点卖血被罚了两遍 = 路线越走越保守");
    }

    /** 累计值变大时，罚分只按**差值**增加。 */
    @Test
    void laterTurnsChargeOnlyTheIncrement() {
        SearchNode root = root();
        List<SearchNode> first = annotate(endTurn(root, 16), endTurn(root, 18));
        SearchNode soldTwo = first.get(0);
        assertEquals(2L, penaltyOf(soldTwo));

        SearchNode nextStart = nextTurn(soldTwo);
        List<SearchNode> second = annotate(endTurn(nextStart, 4), endTurn(nextStart, 18));
        // 上一回合卖 2；这一回合同组最优掉 0（打到 18 血那条），另一条掉 12 → 卖掉 12，累计 14
        assertEquals(14, second.get(0).futureSoldHp);
        assertEquals(14L, penaltyOf(second.get(0)), "总罚分必须正好是 14 份，不是 2+14");
    }

    // ---------------------------------------------------------------- 夹具

    /** 根节点的基准分（夹具里一切分数都从它出发，所以可以直接相减算累计罚分）。 */
    private static final long BASE_SCORE = 1_000_000L;

    /** 累计罚分 = (基准分 − 注记后的分) / 每点代价。 */
    private static long penaltyOf(SearchNode node) {
        return Math.round((BASE_SCORE - node.score) / -TurnOutcomeAnnotator.SOLD_HP_PENALTY);
    }

    private static SearchNode root() {
        SimState state = new SimState();
        state.hp = 20;
        state.maxHp = 80;
        state.turn = 1;
        return new SearchNode(state, null, null, 0, BASE_SCORE);
    }

    private static SearchNode endTurn(SearchNode start, int hpAfter) {
        SimState after = start.state.copy();
        after.hp = hpAfter;
        after.turn = start.state.turn;
        after.cumulativeHpLost = start.state.cumulativeHpLost
                + Math.max(0, start.state.hp - hpAfter);
        SearchNode play = new SearchNode(after, PlanAction.play(0, 0), start, start.depth + 1,
                start.score, start.rank, "Strike_R", 0, 0);

        SimState next = after.copy();
        next.turn = after.turn + 1;
        return new SearchNode(next, PlanAction.endTurn(), play, play.depth + 1, play.score,
                play.rank, null, 0, 0);
    }

    private static SearchNode nextTurn(SearchNode ended) {
        SimState state = ended.state.copy();
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
