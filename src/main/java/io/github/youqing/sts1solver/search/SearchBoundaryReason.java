package io.github.youqing.sts1solver.search;

/**
 * 这一条候选路线**为什么停在这里**（蓝本 {@code Search/SearchBoundaryReason}，{@code CombatPlan.cs:51-65}）。
 *
 * <p>为什么要有它：最终排序的第 13 键是 {@code PolicyBoundaryRank(features.BoundaryReason)}，
 * 也就是「这条路线是算完了、还是被预算/未知效果截断的」。用整趟搜索唯一的
 * {@code lastStopReason} 去冒充它，会让**所有**候选共享同一个排名，这一键等于不存在。
 *
 * <p>枚举顺序不影响排序：排名由 {@link FinalPlanOrdering#policyBoundaryRank} 显式给出，
 * 且蓝本对 {@link #DYNAMIC_RESOLUTION} / {@link #MEMORY_NO_PROGRESS} 是**抛异常**而不是给档位。
 */
public enum SearchBoundaryReason {
    /** 正常收敛（打完了 / 走到终局）。 */
    NONE,
    /** 抽牌堆空了要洗牌，这条世界线在这里断开。 */
    SHUFFLE,
    /** 没有能出的牌。 */
    NO_CARDS,
    /** 遇到镜像不支持的效果。 */
    UNSUPPORTED_EFFECT,
    /** 需要额外上下文才能结算的延迟效果（蓝本 {@code DynamicResolution}）。 */
    DYNAMIC_RESOLUTION,
    /** 停在「等玩家选牌」上。 */
    PENDING_CHOICE,
    /** 事件层面失败（蓝本 {@code EventDefeat}）。 */
    EVENT_DEFEAT,
    /** 回合层上限。 */
    TURN_LIMIT,
    /** 节点上限。 */
    NODE_LIMIT,
    /** 时间预算。 */
    TIME_LIMIT,
    /** 回收连续无进展（蓝本 {@code MemoryNoProgress}）。 */
    MEMORY_NO_PROGRESS
}
