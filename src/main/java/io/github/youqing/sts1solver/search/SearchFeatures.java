package io.github.youqing.sts1solver.search;

/**
 * 一条候选路线**在节点上看到的事实**（蓝本 {@code CombatBeamSolver.Models.cs:343-421} 的
 * {@code SearchFeatures}，只保留最终排序与资格判定真正读到的字段）。
 *
 * <p>为什么单独拆出来：蓝本把「节点事实」({@code SearchFeatures}) 与「模拟快照」
 * ({@code SimulationSnapshot}) 分开，最终选择器两样都读。合成一个大对象之后，
 * 「这个数是展平时算的、那个数是终局算的」就再也分不清了 —— 而排名错一位就是换一条路线。
 *
 * <p>纯数据：不带 {@code AbstractCard} / {@code AbstractMonster} 之类的真机对象，
 * 后台搜索线程可以随便读。
 */
public final class SearchFeatures {

    /** 敌方是否全部倒下（{@code Won} 的必要条件之一）。 */
    public final boolean allEnemiesDead;
    /** 投影血量（本回合敌方阶段之后的一阶前瞻），决定 {@code Survives}。 */
    public final int projectedPlayerHp;
    /** 这条路线走完时的真实血量。 */
    public final int playerHp;
    public final int playerMaxHp;
    /** 本场**累计**未格挡伤害（蓝本 {@code CumulativePlayerHpLost}）。 */
    public final int cumulativePlayerHpLost;
    /** 本场累计回血（不含死亡豁免恢复的那部分）。 */
    public final int recoveredPlayerHp;
    /** 死亡豁免（蜥蜴尾巴 / 瓶中仙子）一共回了多少血。 */
    public final int deathSaveHpRestored;
    /** 已兑现的长期资源（{@code Hand of Greed} 实际到手的金币）。 */
    public final int longTermResourceValue;
    /** 这条路线实际生成了几张 {@code Anger} 复制牌。 */
    public final int angerCopiesGenerated;
    /** 还活着的敌人总血（蓝本 {@code EnemyHp}，不是 {@code RawEnemyHp}）。 */
    public final int enemyHp;
    /** 抢钱怪手里还攥着的金币（蓝本 {@code OutstandingStolenResource}）。 */
    public final int outstandingStolenResource;
    /** 路线里用了多少瓶药水（含自动喝的那瓶）。 */
    public final int potionCount;
    /** 本路线**未来**还会卖掉的、可避免的血（蓝本 {@code FutureSoldHp}，Task 3 填）。 */
    public final int futureSoldHp;
    /** 路线动作总数（出牌 / 喝药 / END_TURN 各 +1），**不是**回合层数。 */
    public final int actionCount;
    /** 蓝本 {@code Node.Score}（基础分，与 Beam 保留分分开）。 */
    public final double score;
    /** 这条候选自己的边界原因，**不是**整趟搜索的 {@code lastStopReason}。 */
    public final SearchBoundaryReason boundaryReason;

    private SearchFeatures(Builder b) {
        this.allEnemiesDead = b.allEnemiesDead;
        this.projectedPlayerHp = b.projectedPlayerHp;
        this.playerHp = b.playerHp;
        this.playerMaxHp = b.playerMaxHp;
        this.cumulativePlayerHpLost = b.cumulativePlayerHpLost;
        this.recoveredPlayerHp = b.recoveredPlayerHp;
        this.deathSaveHpRestored = b.deathSaveHpRestored;
        this.longTermResourceValue = b.longTermResourceValue;
        this.angerCopiesGenerated = b.angerCopiesGenerated;
        this.enemyHp = b.enemyHp;
        this.outstandingStolenResource = b.outstandingStolenResource;
        this.potionCount = b.potionCount;
        this.futureSoldHp = b.futureSoldHp;
        this.actionCount = b.actionCount;
        this.score = b.score;
        this.boundaryReason = b.boundaryReason;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** 可变构造器（只用来造一次性对象；构造完的 {@link SearchFeatures} 不可变）。 */
    public static final class Builder {
        private boolean allEnemiesDead;
        private int projectedPlayerHp;
        private int playerHp;
        private int playerMaxHp;
        private int cumulativePlayerHpLost;
        private int recoveredPlayerHp;
        private int deathSaveHpRestored;
        private int longTermResourceValue;
        private int angerCopiesGenerated;
        private int enemyHp;
        private int outstandingStolenResource;
        private int potionCount;
        private int futureSoldHp;
        private int actionCount;
        private double score;
        private SearchBoundaryReason boundaryReason = SearchBoundaryReason.NONE;

        public Builder allEnemiesDead(boolean value) {
            this.allEnemiesDead = value;
            return this;
        }

        public Builder projectedPlayerHp(int value) {
            this.projectedPlayerHp = value;
            return this;
        }

        public Builder playerHp(int value) {
            this.playerHp = value;
            return this;
        }

        public Builder playerMaxHp(int value) {
            this.playerMaxHp = value;
            return this;
        }

        public Builder cumulativePlayerHpLost(int value) {
            this.cumulativePlayerHpLost = value;
            return this;
        }

        public Builder recoveredPlayerHp(int value) {
            this.recoveredPlayerHp = value;
            return this;
        }

        public Builder deathSaveHpRestored(int value) {
            this.deathSaveHpRestored = value;
            return this;
        }

        public Builder longTermResourceValue(int value) {
            this.longTermResourceValue = value;
            return this;
        }

        public Builder angerCopiesGenerated(int value) {
            this.angerCopiesGenerated = value;
            return this;
        }

        public Builder enemyHp(int value) {
            this.enemyHp = value;
            return this;
        }

        public Builder outstandingStolenResource(int value) {
            this.outstandingStolenResource = value;
            return this;
        }

        public Builder potionCount(int value) {
            this.potionCount = value;
            return this;
        }

        public Builder futureSoldHp(int value) {
            this.futureSoldHp = value;
            return this;
        }

        public Builder actionCount(int value) {
            this.actionCount = value;
            return this;
        }

        public Builder score(double value) {
            this.score = value;
            return this;
        }

        public Builder boundaryReason(SearchBoundaryReason value) {
            this.boundaryReason = value == null ? SearchBoundaryReason.NONE : value;
            return this;
        }

        public SearchFeatures build() {
            return new SearchFeatures(this);
        }
    }
}
