package io.github.youqing.sts1solver.search;

/**
 * 全局药水档位的**资格判定**（蓝本 {@code src/Search/PotionUsePolicy.cs:96-113}）。
 *
 * <p>逐行翻译，只去掉 STS1 没有的两个概念：
 * <ul>
 *   <li>{@code BossHpRelief}：STS1 没有「幕末回 80% 血」，所以
 *       {@code SmartRequiredHpSaved == strategicHpCost}（蓝本
 *       {@code ActEndingBossPolicy.RawHpRequiredForPersistentValue} 的 {@code None} 分支）；</li>
 *   <li>{@code AmbergrisPower}（琥珀）：STS1 没有这张药水，相关分支不实现。</li>
 * </ul>
 *
 * <p>⚠️ 这里的 {@code hpSaved} 是「喝药路线比不喝药路线少掉多少血」，由调用方从两条真实路线算出来，
 * 不是估值。差值小于 0 时按 0 处理（蓝本 {@code HpSaved} 同口径）—— 负数意味着多掉了血，
 * 那更不该算成「省了负的血」。
 */
public final class PotionUsePolicy {

    /** 蓝本 {@code SolverWeights.PotionMinimumHpSaved}（{@code SolverWeights.cs:99}）。 */
    public static final int POTION_MINIMUM_HP_SAVED = 9;

    private PotionUsePolicy() {
    }

    /** 一瓶的基准门槛 × 瓶数。 */
    public static int requiredHpSaved(int potionCount) {
        return Math.max(0, potionCount) * POTION_MINIMUM_HP_SAVED;
    }

    /** 第二瓶起还要额外买到多少血（蓝本 {@code AdditionalRequiredUseStrategicHpCost}）。 */
    public static int additionalRequiredUseStrategicHpCost(int strategicHpCost) {
        return Math.max(0, strategicHpCost - POTION_MINIMUM_HP_SAVED);
    }

    /** 蓝本 {@code HpSaved}：负数归零。 */
    public static int hpSaved(int potionFreeDeficit, int potionRouteDeficit) {
        return Math.max(0, potionFreeDeficit - potionRouteDeficit);
    }

    /**
     * 这条用药路线在给定档位下是否成立。
     *
     * @param policy          全局档位
     * @param explicitUses    路线里的显式用药数
     * @param anyRouteWon     搜索里是否存在任何获胜路线
     * @param potionFreeWon   不喝药的那条基线是否获胜
     * @param potionRouteWon  这条用药路线是否获胜
     * @param hpSaved         用药路线比不喝药少掉多少血（{@link #hpSaved} 已归零）
     * @param strategicHpCost 这瓶药水的战略成本（{@link io.github.youqing.sts1solver.mirror.PotionValuation#strategicHpCost}）
     */
    public static boolean isEligible(SolverPotionPolicy policy, int explicitUses,
                                     boolean anyRouteWon, boolean potionFreeWon,
                                     boolean potionRouteWon, int hpSaved, int strategicHpCost) {
        if (policy == null) {
            return true;                     // 没设策略 = 不约束（防御性）
        }
        switch (policy) {
            case DISABLED:
                return explicitUses == 0;
            case REQUIRE_AT_LEAST_ONE:
                return explicitUses > 0
                        && (!anyRouteWon || potionRouteWon)
                        // 第一瓶不额外要求：档位本身就是「至少一瓶」
                        && (explicitUses == 1
                            || !potionFreeWon
                            || hpSaved >= additionalRequiredUseStrategicHpCost(strategicHpCost));
            case SMART:
                return explicitUses == 0
                        || (potionRouteWon && !potionFreeWon)
                        || hpSaved >= Math.max(0, strategicHpCost);
            default:
                return true;
        }
    }
}
