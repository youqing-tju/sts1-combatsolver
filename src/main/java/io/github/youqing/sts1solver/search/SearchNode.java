package io.github.youqing.sts1solver.search;

import io.github.youqing.sts1solver.sim.SimState;

/** 搜索树节点：一个状态 + 走到这里的那一步。 */
public final class SearchNode {

    public final SimState state;
    public final PlanAction action;
    public final SearchNode parent;
    public final int depth;
    public final long score;

    public SearchNode(SimState state, PlanAction action, SearchNode parent, int depth, long score) {
        this.state = state;
        this.action = action;
        this.parent = parent;
        this.depth = depth;
        this.score = score;
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
