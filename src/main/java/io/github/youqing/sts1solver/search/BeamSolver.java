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
    /**
     * 最多推进几个**回合层**（不是动作步数）。
     *
     * <p>⚠️ 2026-10-23 改了含义：以前是「动作层数」，现在外层循环一次就是一个完整回合，
     * 搜索的「深度」由回合数定义（蓝本同理，它连深度参数都没有，靠节点/时间预算收敛）。
     * 传 16 ≈ 蓝本的 {@code SetupValueHorizonTurns}。
     */
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
    /** 实际完整推进的**回合层数**（蓝本面板的 turn_layer）。 */
    public int lastDepth;
    /** Beam 宽度（蓝本按档位固定；我们不再按预算加宽）。 */
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
    /** 最后一轮是不是被时间掐断的（蓝本 {@code ResultScope != SearchCompletion}：掐断就不再加宽）。 */
    public boolean lastPassCutShort;
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

    /**
     * 一个回合层至少分到这么多展开节点（蓝本 {@code SolverWeights.MinimumTurnLayerExpandedNodes}）。
     *
     * <p>作用和「按回合层分时间预算」一样：保留回合多、剩余节点少的时候，
     * 不至于把某一层挤到几乎搜不动。
     */
    public static final int MIN_TURN_LAYER_NODES = 500;

    /**
     * 单个回合最多展开多少层「出牌」。蓝本没有这个常量，它靠回合层节点预算兜住；
     * 我们留一个硬上限，防止 0 费引擎在**不限节点**的场景（单测）里把内存跑爆。
     */
    public static final int MAX_PLAY_DEPTH_PER_TURN = 64;

    // ===== 加宽补搜（照抄蓝本 SolverWeights.NoVictoryEscalation* + EscalateSearchWhenNoVictory） =====
    /**
     * 「一场都没打赢」时的补搜：Beam 与节点帽一起按 ×2 放大，最多 2 次（上限 4 倍 / Beam 512）。
     *
     * <p>触发条件、倍数、上限、耗时预估、以及「没严格改善就停」全部照抄蓝本
     * {@code CombatSearchCoordinator.EscalateSearchWhenNoVictory}（FailureRecovery.cs:40-140）。
     * 蓝本注释特别强调「只翻节点没用、只翻 Beam 也没用，两者是乘的关系」，所以两边一起翻。
     */
    public static final int ESCALATION_FACTOR = 2;
    public static final int MAX_ESCALATIONS = 2;
    public static final int MAX_ESCALATED_WIDTH = 512;
    /** 分支上限也要一起放大（蓝本 {@code MaximumEscalatedBranchesPerAction}）。 */
    public static final int MAX_ESCALATED_BRANCHES = 100;

    /** 本轮搜索起点的血量 / 最大生命 / 回合数——估值的「已损血量」「最大生命成长」要靠它。 */
    private int rootHp;
    private int rootMaxHp;
    private int rootTurn;
    /** 估值的「路线起点」快照（血量/上限/回合/开局攻击总量/开局敌人数）。 */
    private Score.Root scoreRoot;

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
     * 单次搜索 + 时间预算。
     *
     * <p>🔴 2026-10-23 结构改动：从「扁平 beam + 迭代加深」改成蓝本的**回合层**结构
     * （{@code CombatBeamSolver.Phases.cs:1479-1600}）。旧结构有两个毛病：
     *
     * <ol>
     *   <li>「结束回合」和「出牌」在同一个 beam 里竞争 —— 两类节点根本不可比：
     *       结束回合的节点能量刚回满、手牌刚抽满，出牌的节点能量少一张手牌少，</li>
     *   <li>一个回合可以合法地吃掉整份节点预算（STS1 的 0 费引擎：腐化 + 无痛 + 黑暗拥抱），
     *       于是搜索永远推进不到后面几个回合 —— 蓝本注释里记的正是这个事故
     *       （{@code play_depth 173 → 248}、{@code ended} 逼近 10 万、整份预算烧完还停在回合 2）。</li>
     * </ol>
     *
     * <p>现在：外层按**回合**推进，内层只展开「本回合出牌」，结束回合单独收进下一层；
     * 每层节点预算 = {@code max(500, 剩余节点 / 剩余回合数)}，用完就强制把前沿推过回合末。
     * 回合层本身就是「加深」，所以不再需要迭代加深（也顺手去掉了「后一轮被前一伦吃空的预算饿死」的毛病）。
     *
     * @param budgetMillis 时间预算，{@code Long.MAX_VALUE} 表示只受节点上限约束
     */
    public PlanAction bestFirstAction(SimState root, long budgetMillis) {
        long startedAt = System.nanoTime();
        lastNodes = 0;
        lastStates = 0;
        lastDepth = 0;
        lastLeaves = 0;
        hitNodeLimit = false;
        lastPassCutShort = false;
        lastRoute = new java.util.ArrayList<PlanAction>();
        lastSteps = new java.util.ArrayList<RouteStep>();

        // 每次搜索独立计时：长时间预算（面板「标准」档 120 秒）只在这里生效
        long deadline = budgetMillis == Long.MAX_VALUE
                ? Long.MAX_VALUE
                : startedAt + budgetMillis * 1_000_000L;

        int maxTurns = Math.max(1, depth);
        SearchNode selected = searchToDepth(root, maxTurns, Math.max(1, beamWidth),
                cardBranchLimit, nodeLimit, deadline);
        long lastPassMillis = (System.nanoTime() - startedAt) / 1_000_000L;

        // 蓝本 CombatSearchCoordinator.EscalateSearchWhenNoVictory：
        // **只在一场都没打赢时**才加宽补搜，并且「Beam 与节点帽一起翻倍」。
        // 触发条件、倍数、上限、耗时预估、以及「没严格改善就停」全部照抄。
        for (int completed = 0; ; completed++) {
            if (isCompleteVictory(selected) || lastPassCutShort || expired(deadline)) {
                break;
            }
            if (completed >= MAX_ESCALATIONS) {
                break;
            }
            long remaining = budgetMillis == Long.MAX_VALUE
                    ? Long.MAX_VALUE
                    : budgetMillis - (System.nanoTime() - startedAt) / 1_000_000L;
            // 按扩展倍数估下一轮耗时；估出来超过剩余预算就不启动（蓝本同款防超时）
            long projected = Math.max(1, lastPassMillis) * ESCALATION_FACTOR;
            if (remaining != Long.MAX_VALUE && (remaining <= 0 || remaining < projected)) {
                break;
            }
            int multiple = 1 << (completed + 1);        // 2倍、4倍
            SearchNode candidate = searchToDepth(root, maxTurns,
                    scale(beamWidth, multiple, MAX_ESCALATED_WIDTH),
                    scale(cardBranchLimit, multiple, MAX_ESCALATED_BRANCHES),
                    nodeLimit <= 0 ? 0 : nodeLimit * multiple,
                    deadline);
            lastPassMillis = (System.nanoTime() - startedAt) / 1_000_000L;
            if (!isStrictlyBetter(candidate, selected)) {
                break;                                  // 没严格改善就停，继续扩预算没意义
            }
            selected = candidate;
        }

        lastMillis = (System.nanoTime() - startedAt) / 1_000_000L;
        return selected == null ? null : selected.firstAction();
    }

    /** 蓝本的 {@code Scale}：按倍数放大并封顶。 */
    private static int scale(int value, int multiple, int cap) {
        if (value <= 0) {
            return value;               // 0 = 不限，不参与放大
        }
        long scaled = (long) value * multiple;
        return (int) Math.min(cap, scaled);
    }

    /** 蓝本的 {@code IsCompleteVictory}：真的打赢了（不是「可能会赢」）。 */
    private static boolean isCompleteVictory(SearchNode node) {
        return node != null && node.state.hp > 0 && SearchNode.noAliveMonster(node.state);
    }

    private boolean isStrictlyBetter(SearchNode candidate, SearchNode selected) {
        return candidate != null && (selected == null || better(candidate, selected));
    }

    /**
     * 按回合层推进，返回选出的一条路线。
     *
     * @param maxTurns 最多推进几个回合层（蓝本靠节点/时间预算自然收敛，我们留个上界兜底）
     * @param deadline {@link System#nanoTime()} 口径的截止时刻；{@code Long.MAX_VALUE} = 不限时
     */
    private SearchNode searchToDepth(SimState root, int maxTurns, int width, int branchLimit,
                                     int limit, long deadline) {
        tt = new TranspositionTable();
        bindRoot(root);
        lastWidth = width;
        hitNodeLimit = false;       // 每一轮单独判断「这一轮是不是被节点上限截断」
        lastPassCutShort = false;
        int turnsDone = 0;
        int expanded = 0;           // 本轮展开数（节点帽按轮算，蓝本每一趟都是新的 _run）

        List<SearchNode> frontier = new ArrayList<SearchNode>();
        frontier.add(nodeOf(root.copy(), null, null, 0, null));
        // 打到战斗结束的路线只留**最好的一条**：每个叶子都攥着一份完整状态，不能无脑攒
        SearchNode bestTerminal = null;
        boolean outOfTime = false;

        for (int turn = 0; turn < maxTurns && !frontier.isEmpty(); turn++) {
            if (expired(deadline)) {
                outOfTime = true;
                break;
            }

            // 分拣：已经分出胜负的直接进终局，其余进入本回合的活跃前沿
            List<SearchNode> active = new ArrayList<SearchNode>();
            for (SearchNode node : frontier) {
                if (node.terminal()) {
                    lastLeaves++;                       // 这条路线已经打到战斗结束
                    if (bestTerminal == null || better(node, bestTerminal)) {
                        bestTerminal = node;
                    }
                } else {
                    active.add(node);
                }
            }
            if (active.isEmpty()) {
                break;
            }

            // 本层节点预算：蓝本 MinimumTurnLayerExpandedNodes 兜底，
            // 剩下的额度按「还要搜几个回合」均分 —— 一层吃不掉整份预算
            int remainingTurns = Math.max(1, maxTurns - turn);
            int layerBudget = Integer.MAX_VALUE;
            if (limit > 0) {
                int remainingNodes = Math.max(1, limit - expanded);
                layerBudget = Math.max(MIN_TURN_LAYER_NODES, remainingNodes / remainingTurns);
            }
            int layerStart = expanded;

            // 结束回合的节点单开一档：它们和「还在出牌」的节点不可比（蓝本 ended vs nextPlays）
            List<SearchNode> ended = new ArrayList<SearchNode>();
            for (int playDepth = 0; !active.isEmpty(); playDepth++) {
                if (playDepth >= MAX_PLAY_DEPTH_PER_TURN || expired(deadline)) {
                    outOfTime = expired(deadline);
                    break;
                }
                if (limit > 0 && expanded >= limit) {
                    hitNodeLimit = true;
                    break;
                }
                List<SearchNode> plays = new ArrayList<SearchNode>();
                for (SearchNode node : active) {
                    expandTurnLayer(node, plays, ended, branchLimit);
                    expanded++;                         // 蓝本口径：一个展开 = 一条「世界线」
                }
                lastStates += plays.size();
                if (plays.isEmpty()) {
                    break;                              // 这回合真的没牌可打了
                }
                active = prune(plays, width);
                // 本回合额度用光：把还在场上的路线全部推过回合末，交给下一回合
                if (expanded - layerStart >= layerBudget && playDepth > 0 && !ended.isEmpty()) {
                    for (SearchNode node : active) {
                        ended.add(endTurnOf(node));
                    }
                    active = new ArrayList<SearchNode>();
                    break;
                }
            }
            // 正常收尾：还没结束回合的一律推过回合末
            for (SearchNode node : active) {
                ended.add(endTurnOf(node));
            }
            frontier = prune(ended, width);
            turnsDone = turn + 1;
        }
        lastNodes += expanded;              // 面板口径：累计查阅的世界线（多轮加宽会累加）
        lastPassCutShort = outOfTime;
        // 加宽会跑多轮，深度取「跑得最深的那一轮」——没跑完的那轮不该把面板数字拉低
        lastDepth = Math.max(lastDepth, turnsDone);

        SearchNode best = bestTerminal;
        for (SearchNode node : frontier) {
            if (best == null || better(node, best)) {
                best = node;
            }
        }

        SearchNode bestRollout = null;
        if (useRollout) {
            List<SearchNode> candidates = new ArrayList<SearchNode>(frontier);
            if (bestTerminal != null) {
                candidates.add(bestTerminal);
            }
            long bestValue = Long.MIN_VALUE;
            for (SearchNode node : candidates) {
                if (node.action == null) {
                    continue;   // 根节点，没有可执行动作
                }
                long value = terminalValue(node.state);
                if (value > bestValue) {
                    bestValue = value;
                    bestRollout = node;
                }
            }
            if (bestRollout != null) {
                lastAbDisagreed = best != null && best.action != null
                        && !sameAction(best, bestRollout);
                best = bestRollout;
            }
        }
        lastStaticScore = bestTerminal == null ? 0 : bestTerminal.score;
        lastTerminalScore = best == null ? 0 : best.score;

        if (DEBUG && best != null) {
            System.out.println("CHOSEN depth=" + best.depth + " turns=" + lastDepth
                    + " score=" + best.score + " rank=" + best.rank
                    + " action=" + best.action + " first=" + best.firstAction());
        }
        if (DEBUG && outOfTime) {
            System.out.println("SEARCH stopped by time at turn layer " + lastDepth);
        }
        if (best != null) {
            lastRoute = routeOf(best);
            lastSteps = stepsOf(best);
        }
        return best;
    }

    /** 时间到没到（{@code Long.MAX_VALUE} = 不限时）。 */
    private static boolean expired(long deadline) {
        return deadline != Long.MAX_VALUE && System.nanoTime() >= deadline;
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

    /**
     * 打一张牌，返回新节点；打不出/没镜像时返回 {@code null}（跳过而不是猜）。
     *
     * <p>调用方按「这张牌打完是否还在本回合」决定放进 {@code plays} 还是 {@code ended}：
     * 打最后一张牌把怪打死了就属于后者（蓝本 {@code child.IsTerminal} 的口径）。
     */
    private SearchNode playNode(SimState state, int cardIndex, int targetIndex, int choiceIndex,
                                SearchNode node) {
        SimState copy = state.copy();
        try {
            CardPlayer.play(copy, cardIndex, targetIndex, choiceIndex);
        } catch (ActionRejected rejected) {
            return null;
        } catch (Throwable t) {
            return null;
        }
        return nodeOf(copy, PlanAction.play(cardIndex, targetIndex, choiceIndex), node,
                node.depth + 1, state.hand.get(cardIndex).key());
    }

    /** 结束回合并推进到下一回合（蓝本 {@code BuildAcceptedEndTurnNodes} 的简化版）。 */
    private SearchNode endTurnOf(SearchNode node) {
        SimState afterTurn = node.state.copy();
        CombatFlow.endTurn(afterTurn, energyPerTurn, handSize);
        return nodeOf(afterTurn, PlanAction.endTurn(), node, node.depth + 1, null);
    }

    /** 记住本轮搜索起点的血量/上限/回合/攻击总量/敌人数，供 {@link Score} 算各路「成长」。 */
    private void bindRoot(SimState root) {
        rootHp = root.hp;
        rootMaxHp = root.maxHp;
        rootTurn = root.turn;
        scoreRoot = new Score.Root(root);
    }

    /** 建节点：一次算出基础分（定路线）和排序分（定剪枝）。 */
    private SearchNode nodeOf(SimState state, PlanAction action, SearchNode parent, int depth,
                              String cardKey) {
        Score score = Score.of(state, scoreRoot, depth);
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
     * 最终路线排序 —— 照蓝本 {@code CombatBeamSolver.FinalPlanOrdering.cs:253-271} 的词典序。
     *
     * <p>蓝本那一串比较键有 17 条，我们只搬得动其中有 STS1 对应物的前几条（顺序不变）：
     *
     * <pre>
     * Won 降序                        → 打赢的优先
     * (!Won &amp;&amp; !Survives) 升序        → 没赢的话，活着的优先（Survives = 活着**且投影血量 &gt; 0**）
     * DeathSaveUseCount 升序          → 未建模（无复活遗物）
     * 被偷资源 升序                    → 未建模
     * StrategicHpDeficit 升序          → ★ 主键：战略血量赤字越小越好
     * GrowthHpCredit / GrowthReward   → 未建模（局外收益）
     * CombatEndedTurn 升序             → ★ 结束得越早越好（没结束 = ∞）
     * PolicyHpDeficit / 药水 / …       → 未建模
     * EnemyHp 升序                     → ★ 敌方剩血越少越好
     * Score 降序                       → ★ 基础分
     * ActionCount 升序                 → ★ 动作越少越好
     * </pre>
     *
     * <p>为什么不再直接用基础分当主键：基础分里的血量项是**投影血量**（一阶前瞻），
     * 而最终路线该看**真实结果**；`StrategicHpDeficit` 才是蓝本用来衡量「这条路线赔了多少血」的轴，
     * 它把「掉的血」和「最大生命亏空」加在一起，且回血已经抵掉。
     */
    private boolean better(SearchNode candidate, SearchNode incumbent) {
        boolean candidateWon = isCompleteVictory(candidate);
        boolean incumbentWon = isCompleteVictory(incumbent);
        if (candidateWon != incumbentWon) {
            return candidateWon;
        }
        boolean candidateAlive = candidate.state.hp > 0 && Score.projectedHpOf(candidate.state) > 0;
        boolean incumbentAlive = incumbent.state.hp > 0 && Score.projectedHpOf(incumbent.state) > 0;
        if (candidateAlive != incumbentAlive) {
            return candidateAlive;
        }
        int candidateDeficit = strategicHpDeficit(candidate);
        int incumbentDeficit = strategicHpDeficit(incumbent);
        if (candidateDeficit != incumbentDeficit) {
            return candidateDeficit < incumbentDeficit;
        }
        int candidateEnded = combatEndedTurn(candidate);
        int incumbentEnded = combatEndedTurn(incumbent);
        if (candidateEnded != incumbentEnded) {
            return candidateEnded < incumbentEnded;
        }
        int candidateEnemyHp = aliveEnemyHp(candidate.state);
        int incumbentEnemyHp = aliveEnemyHp(incumbent.state);
        if (candidateEnemyHp != incumbentEnemyHp) {
            return candidateEnemyHp < incumbentEnemyHp;
        }
        if (candidate.score != incumbent.score) {
            return candidate.score > incumbent.score;
        }
        return candidate.depth < incumbent.depth;
    }

    /**
     * 蓝本 {@code ActEndingBossPolicy.StrategicHpDeficit}（{@code BossHpRelief.None} 档）：
     * {@code 累计掉血 + 最大生命亏空 - 回血}。
     *
     * <p>蓝本记的是「累计未格挡伤害」和「累计回血」，两者相减就是**净掉血**，
     * 所以我们直接用 {@code 起点血量 - 当前血量}，不必逐步累计。
     * 下界取「开局就缺的血」：血最多回满，不可能靠多打几回合把这一项刷成负的。
     */
    private int strategicHpDeficit(SearchNode node) {
        int netLost = rootHp - node.state.hp;
        int floor = rootHp - rootMaxHp;          // 开局本来就缺的血，回血最多补到这里
        int maxHpDeficit = Math.max(0, rootMaxHp - node.state.maxHp);
        return Math.max(netLost, floor) + maxHpDeficit;
    }

    /** 蓝本 {@code CombatEndedTurn}：这场战斗在第几回合结束；没结束 = ∞。 */
    private static int combatEndedTurn(SearchNode node) {
        return node.terminal() ? node.state.turn : Integer.MAX_VALUE;
    }

    /** 还活着的敌人的有效血量合计（蓝本 {@code Features.EnemyHp}）。 */
    private static int aliveEnemyHp(SimState state) {
        int total = 0;
        for (SimMonster m : state.monsters) {
            if (m.hp > 0) {
                total += m.hp + Math.max(0, m.block);
            }
        }
        return total;
    }

    /**
     * 展开一个节点：**本回合还能打的牌**进 {@code plays}，
     * **打完这一步就离开本回合**的（结束回合、以及把最后一只怪打死）进 {@code ended}。
     *
     * <p>这个分流是蓝本 {@code AcceptExpandedChild} 的核心（{@code child.Turn > node.Turn}）：
     * 两类节点不可比 —— 结束回合的节点能量回满、手牌重抽，出牌的节点两样都少，
     * 混在同一个 beam 里排序等于拿苹果比橘子。
     */
    private void expandTurnLayer(SearchNode node, List<SearchNode> plays, List<SearchNode> ended,
                                 int branchLimit) {
        SimState state = node.state;

        int produced = 0;
        for (int i = 0; i < state.hand.size(); i++) {
            if (branchLimit > 0 && produced >= branchLimit) {
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
                    if (branchLimit > 0 && produced >= branchLimit) {
                        break;
                    }
                    if (route(playNode(state, i, -1, c, node), plays, ended)) {
                        produced++;
                    }
                }
            } else if (mirror != null && mirror.targeted) {
                // 单目标牌：对每个存活敌人各展开一条（M2-1 多目标）
                for (int t = 0; t < state.monsters.size(); t++) {
                    if (state.monsters.get(t).hp <= 0) {
                        continue;
                    }
                    if (route(playNode(state, i, t, -1, node), plays, ended)) {
                        produced++;
                    }
                }
            } else {
                // 群伤/自我指向：只需展开一次，否则白白吃掉 beam 宽度
                if (route(playNode(state, i, -1, -1, node), plays, ended)) {
                    produced++;
                }
            }
        }

        // 「结束回合」永远是合法动作（蓝本 PlanActionKind.EndTurn）
        ended.add(endTurnOf(node));
    }

    /** 按「这一步之后还在不在本回合」把子节点放进不同的桶。 */
    private static boolean route(SearchNode child, List<SearchNode> plays, List<SearchNode> ended) {
        if (child == null) {
            return false;
        }
        if (child.terminal()) {
            ended.add(child);       // 打死了 = 这条路线结束了，和结束回合同一档
        } else {
            plays.add(child);
        }
        return true;
    }

    /** 兼容旧调用（{@link #rootChildren}）：两个桶合成一个。 */
    private void expand(SearchNode node, List<SearchNode> out) {
        expandTurnLayer(node, out, out, cardBranchLimit);
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
