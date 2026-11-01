package io.github.youqing.sts1solver.search;

/**
 * 药水策略预设（蓝本 {@code PotionStrategyPreset}）。
 *
 * <p>{@link #ONLY_FORCED} **不是**全局 {@code RequireAtLeastOne}：它只改「哪些槽允许显式使用」
 * （保留已 Force 的，其余可搜索药水设为 Disabled），不要求最终路线必须喝一瓶。
 */
public enum SolverPotionPreset {
    ALL_SMART,
    ALL_PROTECTED,
    ALL_FORCED,
    ONLY_FORCED;

    /** 面板上的循环顺序：全 Smart → 全保护 → 全强制 → 只强制。 */
    public SolverPotionPreset next() {
        SolverPotionPreset[] all = values();
        return all[(ordinal() + 1) % all.length];
    }
}
