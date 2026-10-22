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
    /** 要打的牌（{@code id+}）；{@code endTurn} 时为 null。 */
    public final String cardKey;
    public final int targetIndex;
    public final int choiceIndex;

    public RouteStep(boolean endTurn, String cardKey, int targetIndex, int choiceIndex) {
        this.endTurn = endTurn;
        this.cardKey = cardKey;
        this.targetIndex = targetIndex;
        this.choiceIndex = choiceIndex;
    }

    @Override
    public String toString() {
        return endTurn ? "END_TURN" : cardKey;
    }
}
