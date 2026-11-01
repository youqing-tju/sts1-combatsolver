package io.github.youqing.sts1solver.search;

/**
 * 药水策略**没有可执行路线**（蓝本 {@code PotionPolicyUnsatisfiedException}，
 * {@code CombatBeamSolver.FinalPlanOrdering.cs:274-282}）。
 *
 * <p>为什么是异常而不是 {@code null}：{@code null} 会被上层当成「这回合没什么可做的」，
 * 于是结束回合、白挨一轮；而这条路径的真实含义是「搜索空间里没有一条同时满足
 * 强制用药 / 至少一瓶 / 禁用槽 的路线」。两者必须能被区分为不同事件。
 *
 * <p>文案与蓝本一致，按优先级三选一：
 * <ol>
 *   <li>有 Force 且没满足：{@code 指定药水必须使用，但搜索没有找到可执行路线：<清单>。}</li>
 *   <li>RequireAtLeastOne 且一瓶都没用：{@code 本场药水策略要求至少使用一瓶，但搜索没有找到可执行的用药路线。}</li>
 *   <li>其余：{@code 本场药水策略没有可执行路线。}</li>
 * </ol>
 */
public class PotionPolicyUnsatisfiedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public PotionPolicyUnsatisfiedException(String message) {
        super(message);
    }
}
