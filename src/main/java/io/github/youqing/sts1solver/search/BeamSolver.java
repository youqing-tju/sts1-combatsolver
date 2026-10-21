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
    /**
     * M2-7 终端估值：把候选路线用贪心策略一路打到战斗结束再打分。
     * 静态估值只看「这一帧」，会高估「现在多打 6 点血」而低估「下回合会被打死」。
     */
    public boolean useRollout = true;
    /** 回放最多推进几个回合（防止无限对局把每帧预算吃光）。 */
    private static final int ROLLOUT_MAX_TURNS = 6;
    /** 上一次搜索里终端估值与静态估值给出的选择是否一致（A/B 数据）。 */
    public boolean lastAbDisagreed;
    public long lastStaticScore;
    public long lastTerminalScore;

    /** 转置表（每次搜索重建，避免跨回合脏数据）。 */
    private TranspositionTable tt = new TranspositionTable();

    public int ttHits() {
        return tt.hits();
    }

    public int ttMisses() {
        return tt.misses();
    }

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
        tt = new TranspositionTable();
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

        SearchNode bestStatic = null;
        for (SearchNode node : finished) {
            if (bestStatic == null || better(node, bestStatic)) {
                bestStatic = node;
            }
        }
        SearchNode bestRollout = null;
        if (useRollout) {
            long bestValue = Long.MIN_VALUE;
            for (SearchNode node : finished) {
                if (node.action == null) {
                    continue;   // 根节点，没有可执行动作
                }
                long value = terminalValue(node.state);
                if (value > bestValue) {
                    bestValue = value;
                    bestRollout = node;
                }
            }
        }

        SearchNode best = bestStatic;
        if (bestRollout != null) {
            lastAbDisagreed = bestStatic != null && bestStatic.action != null
                    && !sameAction(bestStatic, bestRollout);
            best = bestRollout;
        }
        lastStaticScore = bestStatic == null ? 0 : bestStatic.score;
        lastTerminalScore = best == null ? 0 : best.score;

        if (DEBUG) {
            for (SearchNode node : finished) {
                System.out.println("FIN depth=" + node.depth + " score=" + node.score
                        + " action=" + node.action + " first=" + node.firstAction());
            }
        }
        if (DEBUG && best != null) {
            System.out.println("CHOSEN depth=" + best.depth + " score=" + best.score
                    + " action=" + best.action + " first=" + best.firstAction());
        }
        lastMillis = (System.nanoTime() - startedAt) / 1_000_000L;
        return best == null ? null : best.firstAction();
    }

    private void addPlay(SimState state, int cardIndex, int targetIndex, SearchNode node,
                         List<SearchNode> out) {
        SimState copy = state.copy();
        try {
            CardPlayer.play(copy, cardIndex, targetIndex);
        } catch (ActionRejected rejected) {
            return;   // 这张牌没镜像/打不出，跳过而不是猜
        } catch (Throwable t) {
            return;
        }
        out.add(new SearchNode(copy, PlanAction.play(cardIndex, targetIndex), node,
                node.depth + 1, evaluate(copy)));
    }

    private static boolean sameAction(SearchNode a, SearchNode b) {
        PlanAction x = a.firstAction();
        PlanAction y = b.firstAction();
        if (x == null || y == null) {
            return x == y;
        }
        return x.kind == y.kind && x.cardIndex == y.cardIndex && x.targetIndex == y.targetIndex;
    }

    /**
     * 从给定状态出发，用贪心策略打到战斗结束（或回合上限），返回终局估值。
     * 贪心规则：只打「立刻让分变高」的牌，打完再结束回合。
     */
    public long terminalValue(SimState start) {
        SimState s = start.copy();
        for (int turn = 0; turn < ROLLOUT_MAX_TURNS; turn++) {
            if (s.hp <= 0 || SearchNode.noAliveMonster(s)) {
                break;
            }
            boolean played = true;
            while (played) {
                played = false;
                long base = evaluate(s);
                SimState bestState = null;
                long bestGain = 0;
                for (int i = 0; i < s.hand.size(); i++) {
                    int targets = s.monsters.size();
                    for (int t = -1; t < targets; t++) {
                        if (t >= 0 && s.monsters.get(t).hp <= 0) {
                            continue;
                        }
                        SimState copy = s.copy();
                        try {
                            CardPlayer.play(copy, i, t);
                        } catch (Throwable rejected) {
                            continue;
                        }
                        long gain = evaluate(copy) - base;
                        if (gain > bestGain) {
                            bestGain = gain;
                            bestState = copy;
                        }
                    }
                }
                if (bestState != null) {
                    s = bestState;
                    played = true;
                }
            }
            CombatFlow.endTurn(s, energyPerTurn, handSize);
        }
        return evaluate(s);
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

        for (int i = 0; i < state.hand.size(); i++) {
            SimCard card = state.hand.get(i);
            io.github.youqing.sts1solver.mirror.CardMirror mirror =
                    io.github.youqing.sts1solver.mirror.MirrorRegistry.lookup(
                            card.id + (card.upgraded ? "+" : ""));

            if (mirror != null && mirror.targeted) {
                // 单目标牌：对每个存活敌人各展开一条（M2-1 多目标）
                for (int t = 0; t < state.monsters.size(); t++) {
                    if (state.monsters.get(t).hp <= 0) {
                        continue;
                    }
                    addPlay(state, i, t, node, out);
                }
            } else {
                // 群伤/自我指向：只需展开一次，否则白白吃掉 beam 宽度
                addPlay(state, i, -1, node, out);
            }
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
            if (!DEBUG && tt.dominated(key, node.score) && byState.containsKey(key)) {
                continue;
            }
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
        return Score.of(state).total();
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
