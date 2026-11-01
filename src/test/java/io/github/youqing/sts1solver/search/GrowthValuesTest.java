package io.github.youqing.sts1solver.search;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 成长向量：蓝本 {@code Search/GrowthPolicy.cs} 的 {@code GrowthValues}，只保留 STS1 有的来源。
 *
 * <p>为什么只留两个：STS1 里同名且语义已核实的「打完这场能永久变强」的卡只有
 * {@code Feed}（+最大生命）与 {@code HandOfGreed}（+金币）。蓝本另外八个来源
 * （TheHunt / Royalties / Alchemize / GeneticAlgorithm / TheScythe / Goopy /
 * ForbiddenGrimoire / MadScience）在 STS1 没有对应物，不许自行登记。
 *
 * <p>默认预算是**空**（蓝本 {@code GrowthValues} 默认全 0、{@code IsEnabled == false}）：
 * 免费成长仍然可以靠「兑现次数」这一键胜出，但没有额度时不许拿战损换血量信用。
 */
class GrowthValuesTest {

    @Test
    void emptyVectorIsDisabledAndWorthNothing() {
        GrowthValues none = GrowthValues.none();

        assertEquals(0, none.total());
        assertFalse(none.isEnabled());
        assertEquals(0, none.credit(GrowthValues.none()), "空账本 × 空预算 = 0");
    }

    @Test
    void countersAccumulatePerSource() {
        GrowthValues values = GrowthValues.none()
                .with(GrowthValues.Source.FEED, 2)
                .with(GrowthValues.Source.HAND_OF_GREED, 1);

        assertEquals(2, values.get(GrowthValues.Source.FEED));
        assertEquals(1, values.get(GrowthValues.Source.HAND_OF_GREED));
        assertEquals(3, values.total());
        assertTrue(values.isEnabled());
    }

    /** 蓝本 {@code GrowthValues.Credit}：Σ 预算 × 兑现次数。 */
    @Test
    void creditMultipliesBudgetByRealizedCount() {
        GrowthValues budgets = GrowthValues.none()
                .with(GrowthValues.Source.FEED, 5)
                .with(GrowthValues.Source.HAND_OF_GREED, 2);
        GrowthValues realized = GrowthValues.none()
                .with(GrowthValues.Source.FEED, 3)
                .with(GrowthValues.Source.HAND_OF_GREED, 2);

        assertEquals(5 * 3 + 2 * 2, budgets.credit(realized));
        assertEquals(0, GrowthValues.none().credit(realized),
                "默认空预算：兑现次数照样可比较，但一分血量信用都不给");
    }

    /**
     * 来源集合必须**精确**是 STS1 核实过的那两个。
     *
     * <p>这条测试的作用是拦住「顺手把 Fruit Juice / 神化 也登记成成长」这类扩展：
     * 蓝本没有那些来源，登记了就是自创，而且会立刻改变第 6/7 键的排序结果。
     */
    @Test
    void onlyTheTwoVerifiedSts1SourcesExist() {
        GrowthValues.Source[] sources = GrowthValues.Source.values();
        assertEquals(2, sources.length);
        assertEquals(GrowthValues.Source.FEED, sources[0]);
        assertEquals(GrowthValues.Source.HAND_OF_GREED, sources[1]);
    }
}
