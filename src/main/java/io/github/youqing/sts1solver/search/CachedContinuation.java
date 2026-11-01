package io.github.youqing.sts1solver.search;

/**
 * 一条**已缓存的世界线边界**：从第 {@link #startTurnNumber} 回合开始，状态被预测为 {@link #expectedState}。
 *
 * <p>对应蓝本 {@code src/Search/CombatPlan.cs:258-303} 的
 * {@code ExpectedState + StartTurnNumber + ForecastOffset}。三者缺一不可：
 * <ul>
 *   <li>{@code expectedState} —— 校验依据（精确状态文本，不是哈希）；</li>
 *   <li>{@code startTurnNumber} —— 这条预测属于哪个玩家回合；</li>
 *   <li>{@code forecastOffset} —— 在**完整** {@code RouteStep} 列表里的起点下标。
 *       命中时按它裁出「本回合及以后」的执行步骤；预览（含已走过的回合）不裁。</li>
 * </ul>
 *
 * <p>不可变：后台线程生成、主线程读取，中间不允许再改。
 */
public final class CachedContinuation {

    public final ContinuationStamp expectedState;
    public final int startTurnNumber;
    /**
     * 本回合第一个动作在完整步骤列表里的下标。
     *
     * <p>⚠️ 不是「剩余几步」也不是「第几回合」：命中时执行层要
     * {@code steps.subList(forecastOffset, size)}，算错一格就会把上一回合的 END_TURN 再执行一遍。
     */
    public final int forecastOffset;

    public CachedContinuation(ContinuationStamp expectedState, int startTurnNumber,
                              int forecastOffset) {
        if (expectedState == null) {
            throw new IllegalArgumentException("expectedState must not be null");
        }
        if (startTurnNumber <= 0) {
            throw new IllegalArgumentException("startTurnNumber must be positive");
        }
        if (forecastOffset < 0) {
            throw new IllegalArgumentException("forecastOffset must not be negative");
        }
        this.expectedState = expectedState;
        this.startTurnNumber = startTurnNumber;
        this.forecastOffset = forecastOffset;
    }

    @Override
    public String toString() {
        return "T" + startTurnNumber + "@" + forecastOffset;
    }
}
