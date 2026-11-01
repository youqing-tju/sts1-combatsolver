package io.github.youqing.sts1solver.search;

/**
 * 一条候选路线对应的**模拟快照**里、最终排序真正读到的那几个量
 * （蓝本 {@code SimulationSnapshot} 的子集）。
 *
 * <p>为什么不并进 {@link SearchFeatures}：蓝本刻意把「节点事实」和「快照结论」分开 ——
 * `PlayerDead` / `ProjectedDeathSaveUseCount` / `StrategicHpCredit` 是**整条路线跑完**才有的结论，
 * 而 `CumulativePlayerHpLost` 之类是沿途累计。混在一起以后，
 * 「这个数是展平时算的还是终局算的」就分不清，而排名错一位就是换一条路线。
 */
public final class RouteSnapshot {

    /** 这条路线跑完时玩家已经死了。 */
    public final boolean playerDead;
    /** 投影口径下这条路线会用掉几次死亡豁免。 */
    public final int deathSaveUseCount;
    /** 战略目标换来的血量信用（蓝本 {@code StrategicHpCredit}，默认 0）。 */
    public final int strategicHpCredit;
    /** 成长目标兑现出来的血量信用（蓝本 {@code StrategyGoalHpCredit}，Task 2 填）。 */
    public final int strategyGoalHpCredit;
    /** 成长目标兑现的次数（蓝本 {@code StrategyGoalCount}，Task 2 填）。 */
    public final int strategyGoalCount;
    /** 这条路线在第几回合结束战斗；**只有完整胜利才有意义**（由排序器裁决）。 */
    public final Integer combatEndedTurn;
    /** 其中自动喝掉（非玩家决策）的药水数，用来算「显式用药」。 */
    public final int automaticPotionUseCount;
    /** 显式用药累计的战略成本（蓝本 {@code ExplicitPotionStrategicCost}）。 */
    public final int explicitPotionStrategicCost;
    /** 蓝本 {@code Node.PotionStrategicCost}（只在 RouteQuality 里带着，**不是**排序键）。 */
    public final int potionStrategicCost;

    private RouteSnapshot(Builder b) {
        this.playerDead = b.playerDead;
        this.deathSaveUseCount = b.deathSaveUseCount;
        this.strategicHpCredit = b.strategicHpCredit;
        this.strategyGoalHpCredit = b.strategyGoalHpCredit;
        this.strategyGoalCount = b.strategyGoalCount;
        this.combatEndedTurn = b.combatEndedTurn;
        this.automaticPotionUseCount = b.automaticPotionUseCount;
        this.explicitPotionStrategicCost = b.explicitPotionStrategicCost;
        this.potionStrategicCost = b.potionStrategicCost;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private boolean playerDead;
        private int deathSaveUseCount;
        private int strategicHpCredit;
        private int strategyGoalHpCredit;
        private int strategyGoalCount;
        private Integer combatEndedTurn;
        private int automaticPotionUseCount;
        private int explicitPotionStrategicCost;
        private int potionStrategicCost;

        public Builder playerDead(boolean value) {
            this.playerDead = value;
            return this;
        }

        public Builder deathSaveUseCount(int value) {
            this.deathSaveUseCount = value;
            return this;
        }

        public Builder strategicHpCredit(int value) {
            this.strategicHpCredit = value;
            return this;
        }

        public Builder strategyGoalHpCredit(int value) {
            this.strategyGoalHpCredit = value;
            return this;
        }

        public Builder strategyGoalCount(int value) {
            this.strategyGoalCount = value;
            return this;
        }

        public Builder combatEndedTurn(Integer value) {
            this.combatEndedTurn = value;
            return this;
        }

        public Builder automaticPotionUseCount(int value) {
            this.automaticPotionUseCount = value;
            return this;
        }

        public Builder explicitPotionStrategicCost(int value) {
            this.explicitPotionStrategicCost = value;
            return this;
        }

        public Builder potionStrategicCost(int value) {
            this.potionStrategicCost = value;
            return this;
        }

        public RouteSnapshot build() {
            return new RouteSnapshot(this);
        }
    }
}
