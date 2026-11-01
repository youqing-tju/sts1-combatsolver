package io.github.youqing.sts1solver.search;

import io.github.youqing.sts1solver.mirror.PostCombatRelicHeal;
import io.github.youqing.sts1solver.sim.SimState;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * 成长账本 → 最终排序的第 6/7 键。
 *
 * <p>这两键的意义：**光赢不够，还要赢得有成长**。以前 STS1 连「这条路线吃了几次 Feed」都不知道，
 * 于是「多花两回合吃一口」和「直接砍死了」在排序里完全等价 —— 用户看到的就是「不留着喂」。
 *
 * <p>本文件测的是**接入**：{@link SimState} 里的实战计数如何变成
 * {@code RouteSnapshot.strategyGoalCount} / {@code strategyGoalHpCredit}，再如何改变选路。
 */
class GrowthOrderingIntegrationTest {

    /** 默认预算为空：免费成长靠「兑现次数」这一键胜出。 */
    @Test
    void freeGrowthWinsOnTheRewardCountKey() {
        SimState plain = fight();
        SimState fed = fight();
        fed.feedGrowth = 1;

        assertSame("fed", select(plain, fed, GrowthValues.none(), "plain", "fed"),
                "都赢、战损相同 → 多吃一口的那条优先");
    }

    /** 给了额度才谈得上「用血量信用换成长」，且乘的是蓝本的 {@code Credit}。 */
    @Test
    void budgetedGrowthWinsOnTheCreditKey() {
        SimState plain = fight();
        SimState fed = fight();
        fed.feedGrowth = 2;
        GrowthValues budget = GrowthValues.none().with(GrowthValues.Source.FEED, 5);

        assertEquals(10, budget.credit(fed.growthValues()), "5 × 2 = 10");
        assertSame("fed", select(plain, fed, budget, "plain", "fed"));
    }

    /** 兑现次数一样时，空预算下两条完全等价 —— 不许凭「谁看起来更成长」偷偷加权重。 */
    @Test
    void equalGrowthTiesAndKeepsInputOrder() {
        SimState a = fight();
        SimState b = fight();
        a.feedGrowth = 1;
        b.feedGrowth = 1;

        assertEquals("a", select(a, b, GrowthValues.none(), "a", "b"));
        assertEquals("b", select(b, a, GrowthValues.none(), "b", "a"));
    }

    /** 金币只记**实际到手**的那部分（长期资源键），Feed 的涨血不冒充长期资源。 */
    @Test
    void onlyRealizedGoldBecomesLongTermResource() {
        SimState state = fight();
        state.feedGrowth = 3;
        state.handOfGreedGrowth = 1;
        state.longTermResourceValue = 20;

        SearchFeatures features = featuresOf(state, 1);
        assertEquals(20, features.longTermResourceValue, "Feed 涨的血不是长期资源");
        assertEquals(4, state.growthValues().total(), "两条来源各记各的");
    }

    /** fork（{@link SimState#copy()}）之后两份账本必须**互不干扰**，且复制时带着已有值。 */
    @Test
    void forkedLedgersAreIndependent() {
        SimState original = fight();
        original.feedGrowth = 2;
        original.angerCopiesGenerated = 1;

        SimState fork = original.copy();
        assertEquals(2, fork.growthValues().get(GrowthValues.Source.FEED), "复制要带上已发生的事件");
        assertEquals(1, fork.angerCopiesGenerated);

        fork.feedGrowth++;
        fork.angerCopiesGenerated++;
        assertEquals(2, original.feedGrowth, "改副本不许动到原状态");
        assertEquals(1, original.angerCopiesGenerated);
        assertEquals(3, fork.feedGrowth);
    }

    /** 新战斗是一份新状态：账本自然从零开始，不许跨战斗继承。 */
    @Test
    void freshCombatStartsWithAnEmptyLedger() {
        SimState previous = fight();
        previous.feedGrowth = 5;
        previous.longTermResourceValue = 120;

        SimState next = fight();
        assertEquals(0, next.growthValues().total());
        assertEquals(0, next.longTermResourceValue);
        assertEquals(0, next.angerCopiesGenerated);
    }

    // ---------------------------------------------------------------- 夹具

    private static SimState fight() {
        SimState state = new SimState();
        state.hp = 60;
        state.maxHp = 80;
        state.turn = 3;
        return state;
    }

    /** 用同一份「已经打赢」的形态包成候选，只让成长/收益不同。 */
    private static String select(SimState left, SimState right, GrowthValues budget,
                                 String leftLabel, String rightLabel) {
        List<FinalPlanOrdering.Candidate> candidates = new ArrayList<FinalPlanOrdering.Candidate>();
        candidates.add(candidate(left, budget, leftLabel));
        candidates.add(candidate(right, budget, rightLabel));
        FinalPlanOrdering.Selection selection = FinalPlanOrdering.select(candidates, params());
        return selection.selected().label;
    }

    private static FinalPlanOrdering.Candidate candidate(SimState state, GrowthValues budget,
                                                         String label) {
        GrowthValues realized = state.growthValues();
        SearchFeatures features = featuresOf(state, 1);
        RouteSnapshot snapshot = RouteSnapshot.builder()
                .combatEndedTurn(Integer.valueOf(state.turn))
                .strategyGoalCount(realized.total())
                .strategyGoalHpCredit(budget.credit(realized))
                .build();
        return new FinalPlanOrdering.Candidate(label, features, snapshot, 0, true);
    }

    private static SearchFeatures featuresOf(SimState state, int actionCount) {
        return SearchFeatures.builder()
                .allEnemiesDead(true)
                .projectedPlayerHp(state.hp)
                .playerHp(state.hp)
                .playerMaxHp(state.maxHp)
                .actionCount(actionCount)
                .longTermResourceValue(state.longTermResourceValue)
                .angerCopiesGenerated(state.angerCopiesGenerated)
                .build();
    }

    private static FinalPlanOrdering.Params params() {
        return new FinalPlanOrdering.Params(80, 80, 0, 0, SolverPotionPolicy.SMART, true,
                FinalPlanOrdering.TheftPolicy.PRESERVE_RESOURCES, 0,
                PostCombatRelicHeal.none(), null);
    }
}
