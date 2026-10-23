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
 *
 * <p>估值见 {@link Score}：已按蓝本 {@code SolverWeights} 改成**线性加权**（单位是血，
 * 1 血 = 3 点敌方血），并像蓝本一样把「剪枝用的排序分」与「选路线用的基础分」分开。
 * 蓝本还有十几项更细的维度（LatentSetup / ReplayPotential / 药水与遗物计数 / 按回合层分配节点），
 * 那些等 STS1 对应机制建模后再补，见 {@code docs/research/mechanics-reference.md} §14.4。
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

    /**
     * 上一次搜索**展开的节点数** —— 这是蓝本「世界线」的口径。
     *
     * <p>依据：{@code SolverController.RecordReviewedWorldlines} 里
     * {@code ReviewedWorldlinesTotal += result.TotalExpandedNodes}，
     * 面板上「已查阅 N 条世界线（M 条/s）」的 N 就是它；节点上限
     * {@code MaxExpandedNodes} 卡的也是这个数。
     */
    public int lastNodes;
    /** 生成的子状态数（我们自己看的诊断指标，一个展开会生成十几个）。 */
    public int lastStates;
    public long lastMillis;
    /** 实际跑完的深度（迭代加深时用来看预算吃到了几层）。 */
    public int lastDepth;
    /** 实际跑完的 beam 宽度（深度爬满后预算还有富余就加宽）。 */
    public int lastWidth;
    /**
     * 从根到最优叶子的**完整动作序列**（蓝本的「世界线」就是这个：一条打完整场战斗的路线）。
     * 对 AUTO/面板来说，前面几步就是「本回合怎么打」。
     */
    public java.util.List<PlanAction> lastRoute = new java.util.ArrayList<PlanAction>();
    /** 搜索中真正走到「战斗结束」的路线条数（对拍蓝本面板的「共查阅 N 条世界线」）。 */
    public int lastLeaves;
    /** {@link #lastRoute} 的可执行版本（带牌名，能校验）。 */
    public java.util.List<RouteStep> lastSteps = new java.util.ArrayList<RouteStep>();
    /** 是否被节点上限/时间预算截断（= 蓝本的「尚未彻底穷尽」）。 */
    public boolean hitNodeLimit;
    /**
     * ⚠️ 非蓝本实现，已默认关闭。
     *
     * <p>蓝本没有 rollout：它用 {@code ProjectedPlayerHp + DeathPenalty} 做一阶前瞻
     * （{@code CombatBeamSolver.BeamRetentionPolicy.Ranking.cs:892 BeamRankScore}）。
     * 那个方案同样能避开「走进死亡」，成本却接近零，所以选择留在蓝本路线上。
     * 这段代码仅保留作为对照实验（A/B），不参与默认决策。
     */
    public boolean useRollout = false;
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

    /** 生成状态数的硬上限（蓝本 MaxExpandedNodes）；0 = 不限。 */
    private final int nodeLimit;
    /** 每个节点最多展开多少个「牌 × 目标」分支（蓝本 MaxCardBranchesPerNode）；0 = 不限。 */
    private final int cardBranchLimit;

    /** 本轮搜索起点的血量 / 最大生命 / 回合数——估值的「已损血量」「最大生命成长」要靠它。 */
    private int rootHp;
    private int rootMaxHp;
    private int rootTurn;

    public BeamSolver(int energyPerTurn, int handSize, int beamWidth, int depth) {
        this(energyPerTurn, handSize, beamWidth, depth, 0, 0);
    }

    public BeamSolver(int energyPerTurn, int handSize, int beamWidth, int depth,
                      int nodeLimit, int cardBranchLimit) {
        this.energyPerTurn = energyPerTurn;
        this.handSize = handSize;
        this.beamWidth = Math.max(1, beamWidth);
        this.depth = Math.max(1, depth);
        this.nodeLimit = Math.max(0, nodeLimit);
        this.cardBranchLimit = Math.max(0, cardBranchLimit);
    }

    /**
     * 根节点的直接子节点（一步之内的所有选项及其估值）。
     * 用途：AUTO 在「同样好」的选项里优先挑未镜像的牌，兼顾数据采集。
     */
    public List<SearchNode> rootChildren(SimState root) {
        List<SearchNode> out = new ArrayList<SearchNode>();
        bindRoot(root);
        expand(nodeOf(root.copy(), null, null, 0, null), out);
        return out;
    }

    /**
     * @return 从当前状态出发的最优第一步；无法判定时返回 {@code null}
     */
    public PlanAction bestFirstAction(SimState root) {
        return bestFirstAction(root, Long.MAX_VALUE);
    }

    /**
     * 迭代加深 + 时间预算。
     *
     * <p>固定「宽度 × 深度」的问题是：预算根本吃不满（实测每次决策只花 2 ms、生成几百个状态），
     * 而蓝本是几百万条世界线。这里改成**在预算内一直加深**：
     * 深度 1、2、3… 逐层跑完整 beam search，超时就停，返回最后**完整跑完**的那一层的结果。
     *
     * <p>为什么返回「最后一层完整结果」而不是「当前最优」：不完整的那一层只探索了一部分，
     * 拿它当结论会引入深度偏置（浅层没跑完的分支永远赢不了）。
     *
     * @param budgetMillis 时间预算，{@code Long.MAX_VALUE} 表示只跑到 {@link #depth}
     */
    public PlanAction bestFirstAction(SimState root, long budgetMillis) {
        long startedAt = System.nanoTime();
        lastNodes = 0;
        lastStates = 0;
        lastDepth = 0;
        lastLeaves = 0;
        lastRoute = new java.util.ArrayList<PlanAction>();
        lastSteps = new java.util.ArrayList<RouteStep>();

        if (budgetMillis == Long.MAX_VALUE) {
            // 无预算（单测/基准）：一次跑到配置深度
            SearchNode best = searchToDepth(root, depth, beamWidth);
            lastDepth = depth;
            lastMillis = (System.nanoTime() - startedAt) / 1_000_000L;
            return best == null ? null : best.firstAction();
        }

        // 预算内「先加深，再加宽」：
        // 先把深度爬满（浅层跑得快），深度到顶后还有时间就加倍宽度再来一轮。
        // 只采用**完整跑完**的那一轮，避免「跑了一半的深层」靠深度偏置赢过跑完的浅层。
        SearchNode best = null;
        long deadline = startedAt + budgetMillis * 1_000_000L;
        // 宽度阶梯：1×/2×/4×/8×/16×。搜索会在某一档把预算吃完或把树搜尽，
        // 所以阶梯给足，让时间/节点上限去决定停在哪一档（实测 4 档时 336ms 就提前收敛了）。
        int[] widths = {beamWidth, beamWidth * 2, beamWidth * 4, beamWidth * 8, beamWidth * 16};
        int maxDepth = Math.max(1, depth);
        outer:
        for (int w : widths) {
            // 🔴 深度按几何级数加深（2/4/8/16/32/60），不是每次 +1。
            // 每轮都从零重跑，+1 式加深在深度 60 时会白跑 60 遍前 59 层（实测 92% 的节点是重复功）。
            for (int d = 2; d < maxDepth; d *= 2) {
                best = searchToDepth(root, d, w);
                lastDepth = d;
                lastWidth = w;
                if (System.nanoTime() >= deadline) {
                    break outer;
                }
            }
            best = searchToDepth(root, maxDepth, w);
            lastDepth = maxDepth;
            lastWidth = w;
            if (System.nanoTime() >= deadline) {
                break outer;
            }
        }
        lastMillis = (System.nanoTime() - startedAt) / 1_000_000L;
        return best == null ? null : best.firstAction();
    }

    /** 从根跑到固定深度，返回选出的一条。 */
    private SearchNode searchToDepth(SimState root, int maxDepth, int width) {
        tt = new TranspositionTable();
        bindRoot(root);
        List<SearchNode> beam = new ArrayList<SearchNode>();
        beam.add(nodeOf(root.copy(), null, null, 0, null));

        List<SearchNode> finished = new ArrayList<SearchNode>();

        for (int d = 0; d < maxDepth; d++) {
            List<SearchNode> next = new ArrayList<SearchNode>();
            int before = next.size();
            for (SearchNode node : beam) {
                if (node.terminal()) {
                    finished.add(node);
                    lastLeaves++;      // 这条路线已经打到战斗结束了
                    continue;
                }
                expand(node, next);
                lastNodes++;           // 蓝本口径：一个展开 = 一条「世界线」
            }
            lastStates += next.size() - before;
            if (next.isEmpty()) {
                break;
            }
            if (nodeLimit > 0 && lastNodes >= nodeLimit) {
                // 硬上限：蓝本会在面板标「尚未彻底穷尽」，这里用返回值体现
                hitNodeLimit = true;
                break;
            }
            beam = prune(next, width);
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
        if (best != null) {
            lastRoute = routeOf(best);
            lastSteps = stepsOf(best);
        }
        return best;
    }

    /** 回溯出可执行的步骤（带牌名，执行时可校验）。 */
    private static java.util.List<RouteStep> stepsOf(SearchNode best) {
        java.util.LinkedList<RouteStep> reversed = new java.util.LinkedList<RouteStep>();
        for (SearchNode n = best; n != null && n.action != null; n = n.parent) {
            reversed.addFirst(new RouteStep(
                    n.action.kind == PlanAction.Kind.END_TURN,
                    n.cardKey, n.action.targetIndex, n.action.choiceIndex));
        }
        return new java.util.ArrayList<RouteStep>(reversed);
    }

    /** 回溯出「从根到该节点」的动作序列。 */
    private static java.util.List<PlanAction> routeOf(SearchNode best) {
        java.util.LinkedList<PlanAction> reversed = new java.util.LinkedList<PlanAction>();
        for (SearchNode n = best; n != null && n.action != null; n = n.parent) {
            reversed.addFirst(n.action);
        }
        return new java.util.ArrayList<PlanAction>(reversed);
    }

    private boolean addPlay(SimState state, int cardIndex, int targetIndex, int choiceIndex,
                            SearchNode node, List<SearchNode> out) {
        SimState copy = state.copy();
        try {
            CardPlayer.play(copy, cardIndex, targetIndex, choiceIndex);
        } catch (ActionRejected rejected) {
            return false;   // 这张牌没镜像/打不出，跳过而不是猜
        } catch (Throwable t) {
            return false;
        }
        out.add(nodeOf(copy, PlanAction.play(cardIndex, targetIndex, choiceIndex), node,
                node.depth + 1, state.hand.get(cardIndex).key()));
        return true;
    }

    /** 记住本轮搜索起点的血量/上限/回合，供 {@link Score} 算「已损血量」与「最大生命成长」。 */
    private void bindRoot(SimState root) {
        rootHp = root.hp;
        rootMaxHp = root.maxHp;
        rootTurn = root.turn;
    }

    /** 建节点：一次算出基础分（定路线）和排序分（定剪枝）。 */
    private SearchNode nodeOf(SimState state, PlanAction action, SearchNode parent, int depth,
                              String cardKey) {
        Score score = Score.of(state, rootHp, rootMaxHp, rootTurn, depth);
        return new SearchNode(state, action, parent, depth, score.baseTotal(), score.total(),
                cardKey);
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

    /**
     * 先比基础分（蓝本 {@code SimulationSnapshot.Score}：血 / 敌方血 / 胜负），
     * 再比 Beam 排序分，最后优先「更早收场」的那条路线。
     */
    private static boolean better(SearchNode candidate, SearchNode incumbent) {
        if (candidate.score != incumbent.score) {
            return candidate.score > incumbent.score;
        }
        if (candidate.rank != incumbent.rank) {
            return candidate.rank > incumbent.rank;
        }
        return candidate.depth < incumbent.depth;
    }

    private void expand(SearchNode node, List<SearchNode> out) {
        SimState state = node.state;

        int produced = 0;
        for (int i = 0; i < state.hand.size(); i++) {
            if (cardBranchLimit > 0 && produced >= cardBranchLimit) {
                break;      // 蓝本的 MaxCardBranchesPerNode：分支太散就截断
            }
            SimCard card = state.hand.get(i);
            io.github.youqing.sts1solver.mirror.CardMirror mirror =
                    io.github.youqing.sts1solver.mirror.MirrorRegistry.lookup(
                            card.id + (card.upgraded ? "+" : ""));

            if (mirror != null && mirror.needsChoice) {
                // 选牌也是动作的一部分：打出后手牌少一张，对剩下的每个位置各展开一条
                int choices;
                if (mirror.choicePile == io.github.youqing.sts1solver.mirror.CardMirror.ChoicePile.DISCARD) {
                    choices = state.discard.size();
                } else if (mirror.choicePile == io.github.youqing.sts1solver.mirror.CardMirror.ChoicePile.EXHAUST) {
                    choices = state.exhaust.size();
                } else {
                    choices = Math.max(0, state.hand.size() - 1);
                }
                for (int c = 0; c < choices; c++) {
                    if (cardBranchLimit > 0 && produced >= cardBranchLimit) {
                        break;
                    }
                    if (addPlay(state, i, -1, c, node, out)) {
                        produced++;
                    }
                }
            } else if (mirror != null && mirror.targeted) {
                // 单目标牌：对每个存活敌人各展开一条（M2-1 多目标）
                for (int t = 0; t < state.monsters.size(); t++) {
                    if (state.monsters.get(t).hp <= 0) {
                        continue;
                    }
                    if (addPlay(state, i, t, -1, node, out)) {
                        produced++;
                    }
                }
            } else {
                // 群伤/自我指向：只需展开一次，否则白白吃掉 beam 宽度
                if (addPlay(state, i, -1, -1, node, out)) {
                    produced++;
                }
            }
        }

        // 「结束回合」永远是合法动作（蓝本 PlanActionKind.EndTurn）
        SimState afterTurn = state.copy();
        CombatFlow.endTurn(afterTurn, energyPerTurn, handSize);
        out.add(nodeOf(afterTurn, PlanAction.endTurn(), node, node.depth + 1, null));
    }

    private List<SearchNode> prune(List<SearchNode> candidates, int width) {
        Map<String, SearchNode> byState = new LinkedHashMap<String, SearchNode>();
        for (SearchNode node : candidates) {
            String key = node.state.fingerprint();
            if (!DEBUG && tt.dominated(key, node.rank) && byState.containsKey(key)) {
                continue;
            }
            SearchNode existing = byState.get(key);
            if (existing == null || node.rank > existing.rank) {
                byState.put(key, node);
            }
        }
        List<SearchNode> unique = new ArrayList<SearchNode>(byState.values());
        // 剪枝按 Beam 排序分（含手头资源），最终选路线才按基础分
        unique.sort((a, b) -> Long.compare(b.rank, a.rank));
        return unique.size() <= width ? unique : new ArrayList<SearchNode>(unique.subList(0, width));
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
