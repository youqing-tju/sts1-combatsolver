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
    /**
     * 喝药步骤对应的**药水槽位**；非喝药步骤为 {@code -1}。
     *
     * <p>🔴 必须和 {@link #cardKey} 一起看：两瓶同 ID 的药水在同一个腰带上时，
     * 「喝 Fire Potion」这条指令是**有歧义**的 —— 只按 ID 找会喝掉另一格，
     * 用户看到的就是「明明锁了这瓶，它喝了那瓶」。Force/Disabled 的校验同理，
     * 所以策略层与执行层都绑定 {@code (slot, potionId)}。
     */
    public final int potionSlot;

    public RouteStep(boolean endTurn, String cardKey, int targetIndex, int choiceIndex) {
        this(endTurn, false, cardKey, targetIndex, choiceIndex);
    }

    public RouteStep(boolean endTurn, boolean potion, String cardKey, int targetIndex,
                     int choiceIndex) {
        this(endTurn, potion, cardKey, -1, targetIndex, choiceIndex);
    }

    public RouteStep(boolean endTurn, boolean potion, String cardKey, int potionSlot,
                     int targetIndex, int choiceIndex) {
        this.endTurn = endTurn;
        this.potion = potion;
        this.cardKey = cardKey;
        this.potionSlot = potion ? potionSlot : -1;
        this.targetIndex = targetIndex;
        this.choiceIndex = choiceIndex;
    }

    /**
     * 这一步喝的是不是**这一格这一瓶**。
     *
     * <p>执行层必须用它校验：两瓶同 ID 的药水时，只按名字找会喝错格，
     * 而用户按 Force/Disabled 锁的正是「哪一格」。
     */
    public boolean matchesPotionAt(int liveSlot, String livePotionId) {
        return potion
                && potionSlot >= 0
                && potionSlot == liveSlot
                && cardKey != null
                && cardKey.equals(livePotionId);
    }

    /** 喝药步骤（带槽位）。 */
    public static RouteStep potion(String potionId, int slot, int targetIndex, int choiceIndex) {
        return new RouteStep(false, true, potionId, slot, targetIndex, choiceIndex);
    }

    @Override
    public String toString() {
        if (endTurn) {
            return "END_TURN";
        }
        // 槽位未知（老构造器 / 老 fixture）时保持旧写法，日志格式不必为了 -1 变丑
        return potion
                ? "POTION" + (potionSlot >= 0 ? "[" + potionSlot + "]" : "") + " " + cardKey
                : cardKey;
    }
}
