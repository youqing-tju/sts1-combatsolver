package io.github.youqing.sts1solver.search;

/**
 * 全局药水档位（蓝本 {@code SolverPotionPolicy}，{@code PotionUsePolicy.cs:26-49}）。
 *
 * <p>三档的差别是**约束强度**，不是「偏好」：
 * <ul>
 *   <li>{@link #DISABLED} —— 最终路线的显式用药数必须为 0，Force 覆盖也压不过；</li>
 *   <li>{@link #SMART} —— 可以不喝；喝就必须买到命（或买到胜利），成本闸同
 *       {@link PotionUsePolicy}；</li>
 *   <li>{@link #REQUIRE_AT_LEAST_ONE} —— 至少一瓶，且仍然要满足胜负与额外成本条件。</li>
 * </ul>
 *
 * <p>⚠️ {@code REQUIRE_AT_LEAST_ONE} **不等于**「所有槽都 Force」：它只要求「至少用一瓶」，
 * 用哪一瓶交给估值决定。
 */
public enum SolverPotionPolicy {
    DISABLED,
    SMART,
    REQUIRE_AT_LEAST_ONE;

    /** 解析配置里的名字；认不出返回 {@code null}（调用方决定回退）。 */
    public static SolverPotionPolicy parse(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        for (SolverPotionPolicy policy : values()) {
            if (policy.name().equalsIgnoreCase(trimmed)) {
                return policy;
            }
        }
        return null;
    }
}
