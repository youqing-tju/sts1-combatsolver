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

    public SearchNode(SimState state, PlanAction action, SearchNode parent, int depth, long score) {
        this(state, action, parent, depth, score, score, null);
    }

    public SearchNode(SimState state, PlanAction action, SearchNode parent, int depth, long score,
                      long rank) {
        this(state, action, parent, depth, score, rank, null);
    }

    public SearchNode(SimState state, PlanAction action, SearchNode parent, int depth, long score,
                      long rank, String cardKey) {
        this.state = state;
        this.action = action;
        this.parent = parent;
        this.depth = depth;
        this.score = score;
        this.rank = rank;
        this.cardKey = cardKey;
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
