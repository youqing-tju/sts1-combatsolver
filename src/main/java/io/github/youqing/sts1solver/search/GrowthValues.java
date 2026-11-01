package io.github.youqing.sts1solver.search;

/**
 * 一场战斗里**已经兑现**的成长向量（蓝本 {@code Search/GrowthPolicy.cs} 的 {@code GrowthValues}）。
 *
 * <p>同一个类型有两个用法，和蓝本一致：既表示「预算」（每个来源愿意花多少血），
 * 也表示「兑现次数」（实际发生了几次）。{@link #credit} 把两者按来源相乘。
 *
 * <p><b>只有 STS1 核实过的两个来源。</b> 蓝本的 TheHunt / Royalties / Alchemize /
 * GeneticAlgorithm / TheScythe / Goopy / ForbiddenGrimoire / MadScience 在 STS1 没有同名机制
 * （Fruit Juice 之类「看起来也算成长」的也不许自行登记），所以这里的枚举只有两项 ——
 * 多一项就会立刻改变最终排序第 6/7 键的结果。
 *
 * <p>默认预算是**空**：{@link #none()} 的 {@link #credit} 恒为 0，
 * 也就是「免费成长可以比，但不许拿战损换血量信用」。
 */
public final class GrowthValues {

    /** 蓝本 {@code GrowthSource} 在 STS1 的可用子集。顺序即枚举序，进指纹时也按它。 */
    public enum Source {
        /** 啃食：击杀时永久 +最大生命。 */
        FEED,
        /** 贪婪之手：击杀时获得金币。 */
        HAND_OF_GREED
    }

    private static final GrowthValues NONE = new GrowthValues(0, 0);

    private final int feed;
    private final int handOfGreed;

    private GrowthValues(int feed, int handOfGreed) {
        this.feed = feed;
        this.handOfGreed = handOfGreed;
    }

    public static GrowthValues none() {
        return NONE;
    }

    /** 单来源取值；未登记的来源没有入口（枚举里根本没有）。 */
    public int get(Source source) {
        switch (source) {
            case FEED:
                return feed;
            case HAND_OF_GREED:
                return handOfGreed;
            default:
                throw new IllegalArgumentException("unknown growth source " + source);
        }
    }

    /** 换掉某个来源的计数（不可变，返回新值）。 */
    public GrowthValues with(Source source, int value) {
        int v = Math.max(0, value);
        switch (source) {
            case FEED:
                return new GrowthValues(v, handOfGreed);
            case HAND_OF_GREED:
                return new GrowthValues(feed, v);
            default:
                throw new IllegalArgumentException("unknown growth source " + source);
        }
    }

    /** 这条路线一共兑现了几次成长（最终排序第 7 键）。 */
    public int total() {
        return feed + handOfGreed;
    }

    /** 蓝本 {@code GrowthValues.IsEnabled}：全 0 就是「没有预算」，下游可以整段跳过。 */
    public boolean isEnabled() {
        return feed != 0 || handOfGreed != 0;
    }

    /**
     * 蓝本 {@code GrowthValues.Credit}：{@code Σ 预算[来源] × 兑现次数[来源]}。
     *
     * <p>这就是最终排序第 6 键 {@code GrowthHpCredit} 的来源 —— 只有用户真的给了额度，
     * 它才非零；默认空预算下恒为 0。
     */
    public int credit(GrowthValues realized) {
        if (realized == null) {
            return 0;
        }
        return feed * realized.feed + handOfGreed * realized.handOfGreed;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof GrowthValues)) {
            return false;
        }
        GrowthValues that = (GrowthValues) other;
        return feed == that.feed && handOfGreed == that.handOfGreed;
    }

    @Override
    public int hashCode() {
        return feed * 31 + handOfGreed;
    }

    @Override
    public String toString() {
        return "GrowthValues{feed=" + feed + ", handOfGreed=" + handOfGreed + "}";
    }
}
