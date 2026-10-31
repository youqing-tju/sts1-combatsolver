package io.github.youqing.sts1solver.verify;

/**
 * 「AUTO 打到目标回合末就停」的**纯数据**边界。
 *
 * <p>拆出来单独存在的理由：{@code AutoPilot} 一被实例化就会碰 {@code AbstractDungeon}
 * 这类真机静态对象，单元测试里没法安全构造。而停止边界恰恰是最需要测试的部分 ——
 * 「停晚一手」和「停早半回合」都是真机上很难复现、代价却很高的 bug：
 *
 * <ul>
 *   <li>停晚一手：目标 Tn 的敌人已经打完了，我们还在 Tn+1 又出了一张牌，用户看到的是「没停」；</li>
 *   <li>停早半回合：Tn 的 END_TURN 一入队就关 AUTO，敌方阶段还没跑完就交还控制，
 *       后面的行动归属说不清。</li>
 * </ul>
 *
 * <p>语义：目标回合 {@code T} 表示「打完 T 这一回合（含它的 END_TURN 与随后的敌方阶段）」，
 * 只有在进入 {@code T+1} 及以后的玩家回合时才允许停。所以
 * {@link #shouldStop(int)} 用的是<b>严格大于</b>，不是大于等于。
 *
 * <p>⚠️ 这不是蓝本的 {@code AdoptRoute}/{@code ApplyRoute}：目标只约束「打到哪」，
 * 不冻结路线。每回合照旧按真局面重搜，重算/漂移都不会清掉目标。
 */
final class BoundedAutoTarget {

    /** 无目标（普通无界 AUTO）。 */
    private static final int NONE = -1;

    private int targetTurn = NONE;

    /**
     * 设置/替换目标。
     *
     * @param target  目标绝对回合（1 起）
     * @param current 当前绝对回合（1 起）；未知时传 0 或负数，一律拒绝
     * @return true = 已接受；false = 过期/非法，且<b>旧目标保持不变</b>
     */
    boolean request(int target, int current) {
        // 过期目标必须拒绝而不是「设了就立刻停」：那样点一下旧路线会瞬间关闭 AUTO，
        // 用户完全无法预期。非法值（0 / 负数）同理。
        if (target < 1 || current < 1 || target < current) {
            return false;
        }
        targetTurn = target;
        return true;
    }

    /** @return true 表示已经越过目标回合，可以交还控制（仅当有目标时） */
    boolean shouldStop(int current) {
        return isActive() && current > targetTurn;
    }

    /** 恢复无界状态。 */
    void clear() {
        targetTurn = NONE;
    }

    boolean isActive() {
        return targetTurn >= 1;
    }

    /** 目标回合；无目标返回 -1。 */
    int targetTurn() {
        return targetTurn;
    }
}
