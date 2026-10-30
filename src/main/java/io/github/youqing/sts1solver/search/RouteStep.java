package io.github.youqing.sts1solver.search;

/**
 * 计划里的一步：可执行、且**能被校验**。
 *
 * <p>为什么不是直接用 {@link PlanAction}：计划算完到真正执行之间隔着好几帧，手牌下标可能已经变了
 * （敌人塞了状态牌、能力牌改写了手牌）。所以这里记的是**牌名**，执行时按名字去手上找，
 * 找不到就说明局面偏离了计划，应该重算而不是照着旧下标乱打。
 */
public final class RouteStep {

    public final boolean endTurn;
    /**
     * 这一步是**喝药**（{@link PlanAction.Kind#USE_POTION}），此时 {@link #cardKey} 是药水 ID。
     *
     * <p>🔴 缺这个标记时执行层只会「按牌名去手里找」，喝药步骤永远找不到 → 返回
     * REPLAN → 重算得到同一条路线 → 再拒，实测在 Boss 战原地空转 100 秒一张牌没出。
     */
    public final boolean potion;
    /** 要打的牌（{@code id+}）/ 药水 ID；{@code endTurn} 时为 null。 */
    public final String cardKey;
    public final int targetIndex;
    public final int choiceIndex;

    public RouteStep(boolean endTurn, String cardKey, int targetIndex, int choiceIndex) {
        this(endTurn, false, cardKey, targetIndex, choiceIndex);
    }

    public RouteStep(boolean endTurn, boolean potion, String cardKey, int targetIndex,
                     int choiceIndex) {
        this.endTurn = endTurn;
        this.potion = potion;
        this.cardKey = cardKey;
        this.targetIndex = targetIndex;
        this.choiceIndex = choiceIndex;
    }

    @Override
    public String toString() {
        if (endTurn) {
            return "END_TURN";
        }
        return potion ? "POTION " + cardKey : cardKey;
    }
}
