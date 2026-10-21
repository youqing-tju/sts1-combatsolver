package io.github.youqing.sts1solver.search;

import io.github.youqing.sts1solver.diag.Trace;
import io.github.youqing.sts1solver.mirror.CardPlayer;
import io.github.youqing.sts1solver.mirror.ActionRejected;
import io.github.youqing.sts1solver.sim.CombatFlow;
import io.github.youqing.sts1solver.sim.SimCard;
import io.github.youqing.sts1solver.sim.SimMonster;
import io.github.youqing.sts1solver.sim.SimState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 最小可用的 beam search。
 *
 * <p>骨架照蓝本：动作是一等公民（含「结束回合」），逐层展开候选、去重、按估值保留前 K 条
 * （蓝本 `CombatBeamSolver.BuildAcceptedEndTurnNodes` / `CombatBeamSolver.Expansion.cs`）。
 * 但**估值函数远没有蓝本复杂** —— 蓝本有几十项（战略血量赤字、回合数、路线特征…），
 * 我们先用最朴素的字典序：存活 > 血量 > 敌方总血 > 格挡。
 *
 * <p>用途：给 AUTO 选「下一步打哪张牌」。不是精确求解器，但比"手牌顺序第一张"强得多。
 */
public final class BeamSolver {

    private final int energyPerTurn;
    private final int handSize;
    private final int beamWidth;
    private final int depth;

    /** 诊断开关（单测里打开）。 */
    public static boolean DEBUG;

    /** 上一次搜索的规模与耗时（A4 指标：120 秒 ≥ 10 万节点）。 */
    public int lastNodes;
    public long lastMillis;

    public BeamSolver(int energyPerTurn, int handSize, int beamWidth, int depth) {
        this.energyPerTurn = energyPerTurn;
        this.handSize = handSize;
        this.beamWidth = Math.max(1, beamWidth);
        this.depth = Math.max(1, depth);
    }

    /**
     * 根节点的直接子节点（一步之内的所有选项及其估值）。
     * 用途：AUTO 在「同样好」的选项里优先挑未镜像的牌，兼顾数据采集。
     */
    public List<SearchNode> rootChildren(SimState root) {
        List<SearchNode> out = new ArrayList<SearchNode>();
        expand(new SearchNode(root.copy(), null, null, 0, evaluate(root)), out);
        return out;
    }

    /**
     * @return 从当前状态出发的最优第一步；无法判定时返回 {@code null}
     */
    public PlanAction bestFirstAction(SimState root) {
        long startedAt = System.nanoTime();
        lastNodes = 0;
        List<SearchNode> beam = new ArrayList<SearchNode>();
        beam.add(new SearchNode(root.copy(), null, null, 0, evaluate(root)));

        List<SearchNode> finished = new ArrayList<SearchNode>();

        for (int d = 0; d < depth; d++) {
            List<SearchNode> next = new ArrayList<SearchNode>();
            for (SearchNode node : beam) {
                if (node.terminal()) {
                    finished.add(node);
                    continue;
                }
                expand(node, next);
                lastNodes++;
            }
            if (next.isEmpty()) {
                break;
            }
            beam = prune(next);
        }
        finished.addAll(beam);

        SearchNode best = null;
        for (SearchNode node : finished) {
            if (DEBUG) {
                System.out.println("FIN depth=" + node.depth + " score=" + node.score
                        + " action=" + node.action + " first=" + node.firstAction());
            }
            if (best == null || better(node, best)) {
                best = node;
            }
        }
        if (DEBUG && best != null) {
            System.out.println("CHOSEN depth=" + best.depth + " score=" + best.score
                    + " action=" + best.action + " first=" + best.firstAction());
        }
        lastMillis = (System.nanoTime() - startedAt) / 1_000_000L;
        return best == null ? null : best.firstAction();
    }

    /** 先比分数，分数相同则优先「更早收场」的那条路线（蓝本的 CombatEndedTurn 次序）。 */
    private static boolean better(SearchNode candidate, SearchNode incumbent) {
        if (candidate.score != incumbent.score) {
            return candidate.score > incumbent.score;
        }
        return candidate.depth < incumbent.depth;
    }

    private void expand(SearchNode node, List<SearchNode> out) {
        SimState state = node.state;
        int target = firstAliveIndex(state);

        for (int i = 0; i < state.hand.size(); i++) {
            SimCard card = state.hand.get(i);
            SimState copy = state.copy();
            try {
                CardPlayer.play(copy, i, target);
            } catch (ActionRejected rejected) {
                continue;   // 这张牌没镜像/打不出，跳过而不是猜
            } catch (Throwable t) {
                continue;
            }
            out.add(new SearchNode(copy, PlanAction.play(i, target), node, node.depth + 1,
                    evaluate(copy)));
        }

        // 「结束回合」永远是合法动作（蓝本 PlanActionKind.EndTurn）
        SimState afterTurn = state.copy();
        CombatFlow.endTurn(afterTurn, energyPerTurn, handSize);
        out.add(new SearchNode(afterTurn, PlanAction.endTurn(), node, node.depth + 1,
                evaluate(afterTurn)));
    }

    private List<SearchNode> prune(List<SearchNode> candidates) {
        Map<String, SearchNode> byState = new LinkedHashMap<String, SearchNode>();
        for (SearchNode node : candidates) {
            String key = node.state.fingerprint();
            SearchNode existing = byState.get(key);
            if (existing == null || node.score > existing.score) {
                byState.put(key, node);
            }
        }
        List<SearchNode> unique = new ArrayList<SearchNode>(byState.values());
        unique.sort((a, b) -> Long.compare(b.score, a.score));
        return unique.size() <= beamWidth ? unique : new ArrayList<SearchNode>(unique.subList(0, beamWidth));
    }

    /**
     * 估值：字典序。存活怪物数 > 玩家血量 > 敌方总血 > 格挡。
     * 越靠前的项权重必须大到"不可能被后面几项反超"。
     */
    public static long evaluate(SimState state) {
        int alive = 0;
        int enemyHp = 0;
        for (SimMonster m : state.monsters) {
            if (m.hp > 0) {
                alive++;
                enemyHp += m.hp;
            }
        }
        long score = 0;
        score -= alive * 1_000_000_000L;
        score += (long) state.hp * 1_000_000L;
        score -= (long) enemyHp * 1_000L;
        if (alive > 0) {
            // 场上还有敌人时格挡才有价值；战斗已结束时剩下的格挡一分不值，
            // 否则「先防再杀」会因为残留格挡而微弱胜出，属于估值噪声。
            score += state.block;
        }
        return score;
    }

    private static int firstAliveIndex(SimState state) {
        for (int i = 0; i < state.monsters.size(); i++) {
            if (state.monsters.get(i).hp > 0) {
                return i;
            }
        }
        return -1;
    }

    /** 供诊断用：把当前状态的一句话摘要写进日志。 */
    public static String describe(SimState state) {
        StringBuilder sb = new StringBuilder();
        sb.append("hp=").append(state.hp).append(" e=").append(state.energy)
          .append(" hand=").append(state.hand.size())
          .append(" score=").append(evaluate(state));
        Trace.info("SEARCH " + sb);
        return sb.toString();
    }
}
