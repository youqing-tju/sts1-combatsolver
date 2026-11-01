package io.github.youqing.sts1solver.search;

import io.github.youqing.sts1solver.sim.SimState;

/**
 * 搜索树节点：一个状态 + 走到这里的那一步。
 *
 * <p>两个分数，对应蓝本的两套口径：
 * <ul>
 *   <li>{@link #score} = 蓝本 {@code SimulationSnapshot.Score}（基础分），**最终选哪条路线只看它**；</li>
 *   <li>{@link #rank} = 蓝本 {@code BeamRetentionPolicy.BeamRankScore}，多算了「手上还剩多少资源」，
 *       **只决定哪些半成品路线能活到下一层**。</li>
 * </ul>
 * 合成一个数会让「留着能量不打」比「打一张只值 2 点血的牌」更划算，从而出现「站着不动」。
 */
public final class SearchNode {

    public final SimState state;
    public final PlanAction action;
    public final SearchNode parent;
    public final int depth;
    /** 基础分：只算「血 / 敌方血 / 胜负 / 牌库杂质」，决定最终路线。 */
    public final long score;
    /** Beam 排序分：基础分 + 手头资源（能量/成长/易伤…），只影响剪枝保留顺序。 */
    public final long rank;
    /** 这一步打的牌（{@code id+} 形式）；{@code END_TURN} 时为 null。 */
    public final String cardKey;
    /**
     * 蓝本 {@code Snapshot.OffensiveProgressValue}（{@code StateEvaluation.cs:448-451}）：
     * 当前状态里**已兑现的攻击推进量**（力量潜力 + 全身撞击的格挡），单位「点敌方血」。
     * 只做 beam 排序第三键（{@code CompareBeamRankOrder}）和边界多样化的分组键，不进分数。
     */
    public final int offensiveProgress;
    /**
     * 蓝本 {@code SearchNode.PotionCount}：走到本节点为止路线中**已使用的药水数**。
     * 边界多样化按它分桶（防 beam 被同质路线占满）。
     */
    public final int potionsUsed;
    /**
     * 走到本节点为止**可避免**掉血（蓝本 {@code SearchNode.FutureSoldHp}），最终排序第 15 键。
     *
     * <p>子节点继承父节点的值；回合结束时由 {@link TurnOutcomeAnnotator} 补上本回合新增的那部分。
     */
    public final int futureSoldHp;
    /** 本节点作为一个**回合结果**时的注记；其它节点为 {@code null}。 */
    public final TurnOutcome outcome;
    /**
     * 这条候选自己的边界原因（蓝本 {@code Snapshot.BoundaryReason}），最终排序第 13 键。
     *
     * <p>⚠️ 不是整趟搜索的 {@code lastStopReason}：用全局值冒充会让所有候选共享同一档，
     * 这一键等于不存在。
     */
    public final SearchBoundaryReason boundaryReason;

    public SearchNode(SimState state, PlanAction action, SearchNode parent, int depth, long score) {
        this(state, action, parent, depth, score, score, null);
    }

    public SearchNode(SimState state, PlanAction action, SearchNode parent, int depth, long score,
                      long rank) {
        this(state, action, parent, depth, score, rank, null);
    }

    public SearchNode(SimState state, PlanAction action, SearchNode parent, int depth, long score,
                      long rank, String cardKey) {
        this(state, action, parent, depth, score, rank, cardKey, 0, 0);
    }

    public SearchNode(SimState state, PlanAction action, SearchNode parent, int depth, long score,
                      long rank, String cardKey, int offensiveProgress, int potionsUsed) {
        this(state, action, parent, depth, score, rank, cardKey, offensiveProgress, potionsUsed,
                SearchBoundaryReason.NONE);
    }

    public SearchNode(SimState state, PlanAction action, SearchNode parent, int depth, long score,
                      long rank, String cardKey, int offensiveProgress, int potionsUsed,
                      SearchBoundaryReason boundaryReason) {
        this.state = state;
        this.action = action;
        this.parent = parent;
        this.depth = depth;
        this.score = score;
        this.rank = rank;
        this.cardKey = cardKey;
        this.offensiveProgress = offensiveProgress;
        this.potionsUsed = potionsUsed;
        this.futureSoldHp = parent == null ? 0 : parent.futureSoldHp;
        this.outcome = null;
        this.boundaryReason = boundaryReason == null ? SearchBoundaryReason.NONE : boundaryReason;
    }

    private SearchNode(SimState state, PlanAction action, SearchNode parent, int depth, long score,
                       long rank, String cardKey, int offensiveProgress, int potionsUsed,
                       SearchBoundaryReason boundaryReason, int futureSoldHp,
                       TurnOutcome outcome) {
        this.state = state;
        this.action = action;
        this.parent = parent;
        this.depth = depth;
        this.score = score;
        this.rank = rank;
        this.cardKey = cardKey;
        this.offensiveProgress = offensiveProgress;
        this.potionsUsed = potionsUsed;
        this.boundaryReason = boundaryReason == null ? SearchBoundaryReason.NONE : boundaryReason;
        this.futureSoldHp = futureSoldHp;
        this.outcome = outcome;
    }

    /**
     * 回合边界注记：换上新的累计卖血与本回合结果，并把分差补进 {@link #score}。
     *
     * <p>先**扣掉**节点里已经计入的那份罚分（它来自父节点继承的 {@code futureSoldHp}），
     * 再加上本回合的真实值 —— 直接累加会把同一个数字罚两次，正是蓝本
     * {@code AnnotateTurnOutcome} 里 {@code scoreWithoutSoldPenalty} 那一步在防的事。
     */
    SearchNode withSoldAnnotation(int futureSoldHp, TurnOutcome outcome) {
        long scoreWithoutPenalty = score
                - (long) ((double) this.futureSoldHp * TurnOutcomeAnnotator.SOLD_HP_PENALTY);
        long annotated = scoreWithoutPenalty
                + (long) ((double) futureSoldHp * TurnOutcomeAnnotator.SOLD_HP_PENALTY);
        return new SearchNode(state, action, parent, depth, annotated, rank, cardKey,
                offensiveProgress, potionsUsed, boundaryReason, futureSoldHp, outcome);
    }

    /** 从根到本节点的第一个动作（执行层只有这一步是真的要做的）。 */
    public PlanAction firstAction() {
        SearchNode node = this;
        while (node.parent != null && node.parent.parent != null) {
            node = node.parent;
        }
        return node.action;
    }

    public boolean terminal() {
        return state.hp <= 0 || noAliveMonster(state);
    }

    public static boolean noAliveMonster(SimState state) {
        for (io.github.youqing.sts1solver.sim.SimMonster m : state.monsters) {
            if (m.hp > 0) {
                return false;
            }
        }
        return true;
    }
}
