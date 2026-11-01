package io.github.youqing.sts1solver.search;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 回合边界上的卖血注记：蓝本 {@code CombatBeamSolver.Terminal.cs:30-145} 的
 * {@code AnnotateTurnOutcomes} 的逐行翻译（纯数据，不碰搜索状态）。
 *
 * <p>算法：
 * <ol>
 *   <li>对每条「离开这一回合」的路线，找到它的**回合起始**节点，算出本回合掉血；</li>
 *   <li>按 {@code (回合号, 回合起始状态指纹, 药水槽位掩码)} 分组 —— 与蓝本
 *       {@code (Turn, TurnStart.StateKey, PotionSlotsUsed)} 一一对应；</li>
 *   <li>组内**可比**候选的最低掉血就是这一回合「不用付的代价」，其余候选按差值记卖血；</li>
 *   <li>{@code futureSold = parent.FutureSoldHp + soldThisTurn}，并把分差补进 Score。</li>
 * </ol>
 *
 * <p>为什么「可避免」这样定义：同一回合、同一局面、同一次喝药选择下，
 * 掉血最少的那个结果就是这一回合真正打了折扣的上限；比它多掉的部分才是路线自己选的。
 * 直接拿总掉血当卖血会把「本来就躲不掉的伤害」也算成决策失误，那样排序会去追不可能的更优。
 */
final class TurnOutcomeAnnotator {

    /** 蓝本 {@code SolverWeights.SoldHpPenalty}：一点可避免掉血在基础分里的代价。 */
    static final double SOLD_HP_PENALTY = -20_000d;

    private TurnOutcomeAnnotator() {
    }

    static List<SearchNode> annotate(List<SearchNode> ended) {
        if (ended.isEmpty()) {
            return ended;
        }
        List<Pending> pending = new ArrayList<Pending>(ended.size());
        for (SearchNode node : ended) {
            if (node.parent == null || node.action == null) {
                continue;                     // 根节点不是「回合结果」
            }
            SearchNode turnStart = findTurnStart(node.parent);
            boolean endedByTurn = node.action.kind == PlanAction.Kind.END_TURN
                    || node.state.turn > node.parent.state.turn;
            int actualBlock = endedByTurn ? node.parent.state.block : node.state.block;
            int energyLeft = endedByTurn ? node.parent.state.energy : node.state.energy;
            boolean comparable = node.boundaryReason != SearchBoundaryReason.UNSUPPORTED_EFFECT
                    && node.boundaryReason != SearchBoundaryReason.PENDING_CHOICE;
            pending.add(new Pending(node, turnStart, node.parent.state.turn,
                    Math.max(0, node.state.cumulativeHpLost
                            - turnStart.state.cumulativeHpLost),
                    actualBlock, energyLeft, potionSlotsUsed(turnStart, node), comparable));
        }

        Map<String, List<Pending>> groups = new LinkedHashMap<String, List<Pending>>();
        for (Pending item : pending) {
            String key = item.turn + "|" + item.turnStart.state.fingerprint()
                    + "|" + item.potionSlots;
            List<Pending> bucket = groups.get(key);
            if (bucket == null) {
                bucket = new ArrayList<Pending>();
                groups.put(key, bucket);
            }
            bucket.add(item);
        }

        List<SearchNode> annotated = new ArrayList<SearchNode>(pending.size());
        for (List<Pending> bucket : groups.values()) {
            int minimumHpLost = Integer.MAX_VALUE;
            int maxBlock = 0;
            for (Pending item : bucket) {
                if (item.comparable) {
                    minimumHpLost = Math.min(minimumHpLost, item.hpLost);
                }
                maxBlock = Math.max(maxBlock, item.actualBlock);
            }
            if (minimumHpLost == Integer.MAX_VALUE) {
                minimumHpLost = 0;
            }
            for (Pending item : bucket) {
                int soldThisTurn = item.comparable
                        ? Math.max(0, item.hpLost - minimumHpLost) : 0;
                int futureSold = item.node.parent.futureSoldHp + soldThisTurn;
                TurnOutcome outcome = new TurnOutcome(item.turn, item.hpLost,
                        Math.max(0, item.node.state.recoveredPlayerHp
                                - item.turnStart.state.recoveredPlayerHp),
                        enemyHpLostBetween(item.turnStart, item.node),
                        soldThisTurn, maxBlock, item.actualBlock, item.energyLeft);
                annotated.add(item.node.withSoldAnnotation(futureSold, outcome));
            }
        }
        return annotated;
    }

    /** 蓝本 {@code FindTurnStart}：沿父链一直走到「回合号变了」为止。 */
    private static SearchNode findTurnStart(SearchNode node) {
        SearchNode current = node;
        while (current.parent != null && current.parent.state.turn == current.state.turn) {
            current = current.parent;
        }
        return current;
    }

    /**
     * 本回合用过哪些药水槽（位掩码）。不同槽组合是**不同的世界线**，
     * 不能共享同一个最低掉血 —— 否则「喝了一瓶药」的路线会被拿去和「没喝」的比。
     */
    private static long potionSlotsUsed(SearchNode turnStart, SearchNode outcome) {
        long slots = 0L;
        for (SearchNode node = outcome; node != null && node != turnStart; node = node.parent) {
            if (node.action == null || node.action.kind != PlanAction.Kind.USE_POTION) {
                continue;
            }
            int slot = node.action.cardIndex;
            if (slot >= 0 && slot < 63) {
                slots |= 1L << slot;
            }
        }
        return slots;
    }

    private static int enemyHpLostBetween(SearchNode turnStart, SearchNode outcome) {
        return Math.max(0, enemyHp(turnStart.state) - enemyHp(outcome.state));
    }

    private static int enemyHp(io.github.youqing.sts1solver.sim.SimState state) {
        int total = 0;
        for (io.github.youqing.sts1solver.sim.SimMonster monster : state.monsters) {
            if (monster.hp > 0) {
                total += monster.hp;
            }
        }
        return total;
    }

    private static final class Pending {
        final SearchNode node;
        final SearchNode turnStart;
        final int turn;
        final int hpLost;
        final int actualBlock;
        final int energyLeft;
        final long potionSlots;
        final boolean comparable;

        Pending(SearchNode node, SearchNode turnStart, int turn, int hpLost, int actualBlock,
                int energyLeft, long potionSlots, boolean comparable) {
            this.node = node;
            this.turnStart = turnStart;
            this.turn = turn;
            this.hpLost = hpLost;
            this.actualBlock = actualBlock;
            this.energyLeft = energyLeft;
            this.potionSlots = potionSlots;
            this.comparable = comparable;
        }
    }
}
