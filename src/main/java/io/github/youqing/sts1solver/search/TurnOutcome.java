package io.github.youqing.sts1solver.search;

/**
 * 一个回合的结果（蓝本 {@code Search/CombatPlan.cs:197-205} 的 {@code TurnOutcome}）。
 *
 * <p>为什么单独记一份而不是「从相邻两个节点相减」：回合边界上玩家的掉血、回血、
 * 敌方掉血、格挡与剩余能量都只在**这一刻**有定义，事后从节点上反推会把
 * 「回合中途结束的路线上仍在变化的 block/energy」算进来。
 *
 * <p>{@link #soldHp} 是可避免的掉血（同组最优之外的差值），进最终排序第 15 键。
 */
public final class TurnOutcome {

    public final int turn;
    /** 这一回合累计未格挡伤害。 */
    public final int hpLost;
    /** 这一回合累计回血（挣来的那部分）。 */
    public final int hpRecovered;
    /** 这一回合削掉的敌方血量。 */
    public final int enemyHpLost;
    /** 这一回合**可避免**的掉血（蓝本 {@code SoldHp}）。 */
    public final int soldHp;
    /** 同一组里最高的实际格挡（蓝本 {@code MaxBlock}）。 */
    public final int maxBlock;
    public final int actualBlock;
    public final int energyLeft;

    public TurnOutcome(int turn, int hpLost, int hpRecovered, int enemyHpLost, int soldHp,
                       int maxBlock, int actualBlock, int energyLeft) {
        this.turn = turn;
        this.hpLost = hpLost;
        this.hpRecovered = hpRecovered;
        this.enemyHpLost = enemyHpLost;
        this.soldHp = soldHp;
        this.maxBlock = maxBlock;
        this.actualBlock = actualBlock;
        this.energyLeft = energyLeft;
    }

    @Override
    public String toString() {
        return "TurnOutcome{T" + turn + " lost=" + hpLost + " sold=" + soldHp
                + " healed=" + hpRecovered + " enemy=" + enemyHpLost
                + " block=" + actualBlock + "/" + maxBlock + " energy=" + energyLeft + "}";
    }
}
