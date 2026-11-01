package io.github.youqing.sts1solver.search;

/**
 * 一条路线的**质量向量**（蓝本 {@code Search/RouteQuality.cs}，逐字段对应）。
 *
 * <p>它不是「最终排序键的列表」，而是**多个比较器共用的一份质量描述**：
 * 最终选择用 {@code Primary} 投影，搜索中临时选择用 {@code Interim}，
 * 药水资格用 {@code PotionPolicy}，Beam 保留用 {@code RetentionCost}。
 * 把它们混成一个比较器，就是 STS1 之前 {@code better()} 一职多能的根源。
 *
 * <p>{@code PotionStrategicCost} 在这里出现是**必须的**（Beam 保留与药水资格都要它），
 * 但它**不是**最终 18 键中的一项 —— 见 {@link FinalPlanOrdering#keyNames()}。
 */
public final class RouteQuality {

    /** 完整胜利（不是「分数高」，也不是「怪物暂时为 0」）。 */
    public final boolean won;
    /** 活着，且投影血量 &gt; 0。 */
    public final boolean survives;
    public final int deathSaveUseCount;
    public final int outstandingStolenResource;
    public final int strategicHpDeficit;
    public final int growthHpCredit;
    public final int growthRewardCount;
    /** 只有完整胜利才有值；其余 {@code null}（排序时当无穷大）。 */
    public final Integer combatEndedTurn;
    /** 本场投影掉的真实血量（蓝本 {@code ProjectedBattleHpLost}）。 */
    public final int projectedBattleHpLost;
    public final int potionStrategicCost;
    public final int projectedBattlePotionCount;
    public final int enemyHp;
    public final double score;
    /** Beam 保留用的健康风险（蓝本 {@code RetentionHealthRisk}）。 */
    public final long retentionHealthRisk;

    public RouteQuality(boolean won, boolean survives, int deathSaveUseCount,
                        int outstandingStolenResource, int strategicHpDeficit,
                        int growthHpCredit, int growthRewardCount, Integer combatEndedTurn,
                        int projectedBattleHpLost, int potionStrategicCost,
                        int projectedBattlePotionCount, int enemyHp, double score,
                        long retentionHealthRisk) {
        this.won = won;
        this.survives = survives;
        this.deathSaveUseCount = deathSaveUseCount;
        this.outstandingStolenResource = outstandingStolenResource;
        this.strategicHpDeficit = strategicHpDeficit;
        this.growthHpCredit = growthHpCredit;
        this.growthRewardCount = growthRewardCount;
        this.combatEndedTurn = combatEndedTurn;
        this.projectedBattleHpLost = projectedBattleHpLost;
        this.potionStrategicCost = potionStrategicCost;
        this.projectedBattlePotionCount = projectedBattlePotionCount;
        this.enemyHp = enemyHp;
        this.score = score;
        this.retentionHealthRisk = retentionHealthRisk;
    }

    /**
     * 只带「主要轴」的质量（蓝本 {@code RouteQuality.Primary}）：无药基线比较时用。
     *
     * <p>其余字段一律 0 / null —— 这不是省略，蓝本就是这么构造的：
     * 基线比较只看胜负、死亡豁免、战略赤字、成长与结束回合。
     */
    public static RouteQuality primary(boolean won, int strategicHpDeficit,
                                       Integer combatEndedTurn, int growthHpCredit,
                                       int growthRewardCount, int deathSaveUseCount) {
        return new RouteQuality(won, false, deathSaveUseCount, 0, strategicHpDeficit,
                growthHpCredit, growthRewardCount, combatEndedTurn, 0, 0, 0, 0, 0d, 0L);
    }

    /** Beam 保留只看这两项（蓝本 {@code RouteQuality.ForRetention}）。 */
    public static RouteQuality forRetention(long healthRisk, int potionStrategicCost) {
        return new RouteQuality(false, false, 0, 0, 0, 0, 0, null, 0,
                potionStrategicCost, 0, 0, 0d, healthRisk);
    }
}
