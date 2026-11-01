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
     * 回合层的**安全上界**（不是搜索深度预算）。
     *
     * <p>⚠️ 2026-10-23 第二次改含义，这次才对齐蓝本：蓝本的回合层循环**没有上界**
     * （{@code while (frontier.Count > 0 && ... && _run.Expanded < MaxExpandedNodes
     * && !timeBudgetReached)}，{@code CombatBeamSolver.Phases.cs:1479-1485}），
     * 收敛靠「节点上限 / 时间预算 / 前沿走空」三件事。
     *
     * <p>我们先把它当「最多 16 回合」传下去，真机日志于是出现
     * {@code PLAN ready: 56 steps, depth 16} —— 每场战斗都在第 16 个回合层被硬截断，
     * 预算根本没用完。现在传 {@link #SAFETY_MAX_TURN_LAYERS}，正常不会咬住。
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
    /**
     * 新颖度探索通道（蓝本 {@code NoveltyPortfolio}）的诊断口径。
     *
     * <p>这些数只用于「这一趟到底算了什么」，不参与选路 —— 选路还是 {@link #better}。
     */
    public int lastNoveltyNodes;
    public int lastNoveltyOpen;
    public long lastNoveltyEntries;
    public int lastNoveltyTurns;
    public long lastNoveltyMillis;
    public boolean lastNoveltyAdopted;
    /** {@code open_exhausted} / {@code node_limit} / {@code time_limit} / {@code novelty_limit} / {@code off}。 */
    public String lastNoveltyStop = "off";
    private boolean noveltyEnabled;
    private NoveltySearch.Options noveltyOptions = new NoveltySearch.Options();

    /** Beam 宽度组合（蓝本 {@code UseBeamWidthPortfolio}，默认开）。 */
    private boolean portfolioEnabled;
    /** 这一个成员是不是「次段席位」（蓝本 {@code SecondRankBand}）。 */
    private boolean secondRankBand;
    /** 这一个成员是不是「只用基础分排序」（蓝本 {@code BaseScoreOnly}）。 */
    private boolean baseScoreOnly;

    /** 组合里每个成员一行：{@code 成员=节点/毫秒/采纳}，面板与日志用。 */
    public final java.util.List<String> lastPortfolioMembers = new ArrayList<String>();
    /** 最后采纳的是哪个成员（{@code "135"} / {@code "90"} / {@code "135+band"} …）。 */
    public String lastPortfolioAdopted = "";

    /**
     * 本趟搜索留下的**最终候选池**：打完的终局 + 最后一代前沿 + 本趟挑出来的那一个。
     *
     * <p>蓝本 {@code CombatBeamSolver.Phases.cs:2222}：
     * {@code finalPool = [..completed, ..frontier]} —— 最终选哪条路线是在
     * **所有终局 + 所有前沿节点**里排，而不是只看本趟自己挑出来的那一个。
     */
    final java.util.List<SearchNode> lastFinalPool = new ArrayList<SearchNode>();

    /**
     * 诊断用：这一趟最终采纳的节点。
     *
     * <p>只给探针读「收尾血量 / 最大生命 / 敌方剩血 / 动作数 / 药水数」——
     * 这些数字以前只能从 {@link #lastSteps} 反推，反推不出真实收尾状态。
     * **不参与任何决策**，也不许被搜索逻辑读。
     */
    SearchNode lastSelectedNode;

    /** 蓝本 {@code NoveltyPortfolioBudget}：探索的时间帽 = min(5s, 总预算 / 2)，Boss 再除以 4。 */
    static long noveltyBudgetMillis(long totalMillis, boolean boss) {
        if (totalMillis == Long.MAX_VALUE) {
            return NOVELTY_MAX_MILLIS;
        }
        long divisor = boss ? 4 : 2;
        return Math.min(NOVELTY_MAX_MILLIS, totalMillis / divisor);
    }

    /** 蓝本 {@code NoveltyPortfolioBudget}：探索的节点帽 = min(2500, 总节点 / 4)。 */
    static int noveltyBudgetNodes(int totalNodes) {
        if (totalNodes <= 0) {
            return NOVELTY_MAX_NODES;
        }
        return Math.min(NOVELTY_MAX_NODES, Math.max(1, totalNodes / 4));
    }
    /** Beam 宽度（蓝本按档位固定；我们不再按预算加宽）。 */
    /**
     * 被采纳那条路线的**基础分**（诊断用）。
     *
     * <p>用途：回答「为什么不走另一条明显更好的线」—— 把候选首手逐个钉成固定前缀各跑一趟，
     * 比这个分数，就能分清是「搜索没看到」（根本搜不到，前缀会被拒）还是「估值觉得更差」。
     */
    public long lastSelectedScore;
    /**
     * 这一次采纳的路线是不是**真的打完了这场战斗**（{@code isCompleteVictory}）。
     *
     * <p>⚠️ 不能用 {@code lastSelectedScore >= VICTORY_BONUS} 判断：胜利分里还要扣掉
     * 掉血与本回合惩罚，掉血多时总分会被压到 {@code VICTORY_BONUS} 以下，
     * 于是「赢了」被误读成「没赢」（2026-11-01 批量探针踩过这个坑）。
     */
    public boolean lastSelectedVictory;
    /**
     * 这一趟**为什么停**：{@code victory} / {@code horizon} / {@code node_limit} / {@code time_limit}。
     *
     * <p>为什么要有它：以前日志只打 {@code hitNodeLimit ? "node limit hit" : "tree exhausted"}，
     * 于是「把整个地平线走完了」被读成「树被剪得太狠、再加预算也没用」（2026-10-31 交接文档
     * 就是这么写的）。实际正好相反 —— 真机 123 个快照里 **123 次都是 horizon**，
     * 也就是搜索把整场战斗推演到了终局，节点预算只用掉 2~3%。
     */
    public String lastStopReason = "";
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
    /**
     * 这条最终路线上的**稳定回合边界**（跨回合续跑用）。
     *
     * <p>必须来自所有后处理（novelty 二选一 → 药水闸 → 0 费补搜）之后的 `selected`：
     * 那两个后处理都会**换掉**答案，从旧候选上摘边界等于缓存一条搜索并不推荐的世界线。
     */
    public ContinuationPlan lastContinuations = ContinuationPlan.empty();
    /** 是否被节点上限/时间预算截断（= 蓝本的「尚未彻底穷尽」）。 */
    public boolean hitNodeLimit;
    /** 最后一轮是不是被时间掐断的（蓝本 {@code ResultScope != SearchCompletion}：掐断就不再加宽）。 */
    public boolean lastPassCutShort;

    // ===== 0 费开局补搜的遥测（蓝本 PostSearch.cs 的 NO_COST_OPENING 日志那几项） =====
    /** 这一趟枚举出几条免费前缀（蓝本 {@code BuildOpeningNoCostPrefixes} 的返回长度）。 */
    public int lastZeroCostPrefixes;
    /** 真正派发了几趟前缀续搜（蓝本 {@code attempts}，上限 8）。 */
    public int lastZeroCostAttempts;
    /** 有没有哪条前缀真的把答案换了（蓝本 {@code selected=}）。 */
    public boolean lastZeroCostImproved;

    // ===== 药水战略成本对账的遥测（蓝本 PotionUsePolicy + FinalPlanOrdering） =====
    /** 选中的路线里这瓶药水「至少要买回多少血」合计（蓝本 {@code ExplicitPotionStrategicCost}）。 */
    public int lastPotionStrategicCost;
    /**
     * 不喝药的那条基线比它多掉多少血（蓝本 {@code PotionHpSaved}）。
     * 若「不喝就输、喝了能赢」，这里记的是 {@link Integer#MAX_VALUE} 这个哨兵 ——
     * 那时药水买到的不是血量，而是整场胜利。
     */
    public int lastPotionHpSaved;
    /** 因为「买不回成本」而退回不喝药路线（蓝本 {@code potionBranchesRejected} 那一档）。 */
    public boolean lastPotionSpendRejected;

    // ===== 药水策略对最终答案的约束（蓝本 PotionStrategySnapshot + FinalPlanOrdering） =====
    /**
     * 不可变策略快照；{@code null} = 不加约束（等价于「全 Smart、无覆盖」，用于对照实验）。
     *
     * <p>为什么存快照而不是每次读 {@code SolverConfig}：后台线程算到一半用户改了策略，
     * 读全局状态会得到「一半旧一半新」的约束；快照 + 请求代际才能判时效。
     */
    private PotionStrategySnapshot potionStrategy;
    /**
     * 这条最终路线**预计在第几回合死**；{@code -1} = 不会死（或已经打赢）。
     *
     * <p>蓝本 {@code SolverResult.DeathTurn}：自动部署前会拿它和当前回合比 ——
     * 相等就说明「照着这条路走，这一回合就死」，此时**不部署**、把操作权交还玩家
     * （{@code SolverController.cs:2783}，默认设置 {@code StopFullAutoOnDeathTurn = true}）。
     */
    public int lastDeathTurn = -1;

    /**
     * 这条最终路线**能不能活下来**（蓝本 {@code FinalPlanOrdering} 的第一键 {@code Survives}：
     * {@code !PlayerDead && ProjectedPlayerHp > 0}）。
     *
     * <p>判据与 {@link #better} 里的 {@code alive} **完全一致**，否则会出现
     * 「排序时认为 A 更好、资格检查却说 A 会死」这种自相矛盾。
     *
     * <p>⚠️ 它只是**排序键**（{@code FinalPlanOrdering.cs:250-252}）：没赢的话活着的排前面。
     * 蓝本没有任何「没有生还路线就禁止出牌」的停手机制 —— 别把它当执行侧硬门。
     */
    public boolean lastSelectedSurvives;

    /** 最终路线是否满足策略（{@code false} 且 {@link #lastPolicyViolation} 非空 = 显式无解）。 */
    public boolean lastPolicySatisfied = true;
    /** 违规明细（人类可读，进日志与面板）。 */
    public String lastPolicyViolation = "";

    /**
     * 上一次搜索里已经报过的「语义缺失」拒绝（每次搜索清空，避免刷屏）。
     *
     * <p>并行展开时各 lane 共享同一个集合（{@link java.util.Collections#synchronizedSet}），
     * 这样「每张牌每原因只报一次」的口径与串行一致，不会每个 lane 各刷一遍。
     */
    private java.util.Set<String> reportedRejects =
            java.util.Collections.synchronizedSet(new java.util.HashSet<String>());
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
     * 固定搜索前缀（蓝本 {@code CombatBeamSolver._fixedPrefixActions}，ctor 参数
     * {@code CombatBeamSolver.cs:38}）。
     *
     * <p>语义照抄 {@code ApplyFixedPrefix}（{@code CombatBeamSolver.Phases.cs:2296-2400}）：
     * 从根状态开始把这串动作**先照着走一遍**，走完的节点当新的搜索起点；中间任何一步打不出
     * （能量不够 / 没镜像 / 目标非法 / 药水为空）→ 整条前缀作废，返回 null，**不猜、不换一张牌**。
     * 这是「0 费开局补搜」的前置件：补搜要对每条候选前缀各重搜一轮。
     *
     * <p>⚠️ 蓝本还校验 {@code action.Turn != node.Turn}（我们 {@link PlanAction} 不带回合号，
     * 前缀一律「从当前局面立刻回放」，下标天然有效）和「这张牌打完会不会强制结束回合」
     * （STS1 铁甲没有这种牌），所以这两条检查在 STS1 无对应，不做。
     *
     * <p>空 = 普通搜索（默认，行为与引入前缀之前逐字节一致）。
     */
    private java.util.List<PlanAction> fixedPrefix = java.util.Collections.emptyList();

    /**
     * 这条路线最多喝几瓶药水（蓝本 {@code maximumPotionUses}）。
     *
     * <p>闸门照抄 {@code EnumeratePlannedPotionActions}（{@code ExpansionPlan.cs:146-147}）：
     * {@code _maximumPotionUses != null && ExplicitPotionUseCount(node) >= 上限} → 整段不枚举药水。
     * {@code -1} = 不限（默认）。0 = 这趟搜索一杯都不喝（0 费补搜用的就是 0）。
     */
    private int maxPotionUses = -1;

    /**
     * 并行展开的 lane 数；{@code -1} = 用 {@link #defaultParallelLanes()}，1 = 串行。
     *
     * <p>照蓝本 {@code CombatBeamSolver.ParallelExpansion.cs}：lane 之间互不共享可变状态，
     * 结果按父节点下标顺序提交 —— 所以**并行的结果与串行逐位相同**，只是快。
     */
    private int parallelLanes = -1;

    /** 药水对账闸（蓝本 PotionUsePolicy 的最小切片）；测试里可以关掉做对照。 */
    private boolean potionCostGate = true;

    /**
     * 一个回合层至少分到这么多展开节点（蓝本 {@code SolverWeights.MinimumTurnLayerExpandedNodes}）。
     *
     * <p>作用和「按回合层分时间预算」一样：保留回合多、剩余节点少的时候，
     * 不至于把某一层挤到几乎搜不动。
     */
    public static final int MIN_TURN_LAYER_NODES = 500;

    /**
     * 「保留回合层数」—— 把剩余节点预算按「还要给几层留额度」均分时用的分母。
     *
     * <p>蓝本 {@code CombatBeamSolver.Phases.cs:1428-1432}：
     * {@code reservedTurnLayers = _earlyTurnScoutDepth > 0 ? _earlyTurnScoutDepth
     *   : root.EncounterRoomType == RoomType.Boss ? BossEnemyStrengthSuppressionHorizon(8)
     *   : StandardEnemyStrengthSuppressionHorizon(4)}，
     * 之后 {@code remainingReservedLayers = max(1, reservedTurnLayers - searchedTurnLayers)}。
     *
     * <p>也就是说：前 4（Boss 战 8）个回合每层分 {@code 剩余/剩余层数}，
     * 超过之后分母兜底为 1 —— 剩下的节点全给后面的回合。我们照抄，
     * 但**循环上界**仍由 {@link #depth}（最多几个回合层）兜着，蓝本没有这个上界。
     */
    public static final int STANDARD_RESERVED_TURN_LAYERS = 4;
    public static final int BOSS_RESERVED_TURN_LAYERS = 8;

    /**
     * 单个回合最多展开多少层「出牌」。蓝本没有这个常量，它靠回合层节点预算兜住；
     * 我们留一个硬上限，防止 0 费引擎在**不限节点**的场景（单测）里把内存跑爆。
     */
    public static final int MAX_PLAY_DEPTH_PER_TURN = 64;

    /**
     * 回合层的**安全上界**（不是预算）。
     *
     * <p>蓝本的回合层循环没有上界：{@code while (frontier.Count > 0 && ... && _run.Expanded <
     * MaxExpandedNodes && !timeBudgetReached)}（{@code CombatBeamSolver.Phases.cs:1479-1485}）——
     * 收敛全靠「节点上限 / 时间预算 / 前沿走空」。
     *
     * <p>我们之前把 {@code depth} 直接当上界传 16（取自蓝本 {@code SetupValueHorizonTurns} 的用例，
     * 那是 UI 投影用的，不是搜索上界），结果真机上日志出现 {@code PLAN ready: 56 steps, depth 16}
     * —— **是被这个 16 截断的，不是把树搜完了**，白扔了预算。
     * 现在 {#depth} 只作安全上界，正常情况下永远不会咬住。
     */
    public static final int SAFETY_MAX_TURN_LAYERS = 500;

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

    /** 蓝本 {@code NoveltyPortfolioBudget.Default.MaximumMilliseconds}。 */
    public static final long NOVELTY_MAX_MILLIS = 5_000L;
    /** 蓝本 {@code NoveltyPortfolioBudget.Default.MaximumExpandedNodes}。 */
    public static final int NOVELTY_MAX_NODES = 2_500;

    /** 本轮搜索起点的血量 / 最大生命 / 回合数——估值的「已损血量」「最大生命成长」要靠它。 */
    private int rootHp;
    private int rootMaxHp;
    private int rootTurn;
    /** 估值的「路线起点」快照（血量/上限/回合/开局攻击总量/开局敌人数）。 */
    private Score.Root scoreRoot;
    /** 战斗打赢后，遗物会回多少血（只有无条件回血进最终排序）。 */
    private io.github.youqing.sts1solver.mirror.PostCombatRelicHeal postCombatRelicHeal =
            io.github.youqing.sts1solver.mirror.PostCombatRelicHeal.none();

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

    /** 设固定搜索前缀（蓝本 {@code fixedPrefixActions}）；传空集合即恢复普通搜索。 */
    public BeamSolver withFixedPrefix(java.util.List<PlanAction> prefix) {
        this.fixedPrefix = prefix == null || prefix.isEmpty()
                ? java.util.Collections.<PlanAction>emptyList()
                : new ArrayList<PlanAction>(prefix);
        return this;
    }

    /** 设「这条路线最多喝几瓶药水」（蓝本 {@code maximumPotionUses}）；{@code -1} = 不限。 */
    public BeamSolver withMaxPotionUses(int maximum) {
        this.maxPotionUses = maximum < 0 ? -1 : maximum;
        return this;
    }

    /**
     * 打开 BFWS 新颖度探索通道（蓝本 {@code NoveltyPortfolio}）。
     *
     * <p>打开后每次搜索先花**独立预算**跑一趟新颖度搜索，剩下的预算再交给 Beam，
     * 最后二选一。失败/收益不明确都不影响 Beam 的结果 —— 所以它对第一章是安全的。
     */
    public BeamSolver withNovelty() {
        this.noveltyEnabled = true;
        return this;
    }

    /** 打开 Beam 宽度组合（蓝本 {@code UseBeamWidthPortfolio} 默认就是开的）。 */
    public BeamSolver withPortfolio() {
        this.portfolioEnabled = true;
        return this;
    }

    /** 设定这一个成员的剪枝策略（组合里每个成员各设一次）。 */
    BeamSolver withBeamPolicy(boolean secondRankBand, boolean baseScoreOnly) {
        this.secondRankBand = secondRankBand;
        this.baseScoreOnly = baseScoreOnly;
        return this;
    }

    /** 蓝本 {@code NoveltySearchOptions}（测试要调参时用）。 */
    public BeamSolver withNoveltyOptions(NoveltySearch.Options options) {
        this.noveltyOptions = options == null ? new NoveltySearch.Options() : options;
        return this;
    }

    /** 设并行展开的 lane 数；1 = 串行（测试用来做逐位对照）。 */
    public BeamSolver withParallelLanes(int lanes) {
        this.parallelLanes = Math.max(1, lanes);
        return this;
    }

    /** 药水对账闸开关（默认开）；测试里用 {@code false} 看「不设闸会怎么喝」。 */
    /** 带上药水策略快照；子 solver（worker / 基线 / 补搜）必须继承同一份。 */
    public BeamSolver withPotionStrategy(PotionStrategySnapshot strategy) {
        this.potionStrategy = strategy;
        return this;
    }

    public PotionStrategySnapshot potionStrategy() {
        return potionStrategy;
    }

    public BeamSolver withPotionCostGate(boolean enabled) {
        this.potionCostGate = enabled;
        return this;
    }

    /**
     * 默认并行度：**1（串行）**，`-Dsts1solver.searchLanes=N` 才开。
     *
     * <p>🔴 2026-10-24 同一场景 A/B（beam 135 / depth 40 / 40 万节点上限，各跑 3 遍取分布）：
     * <pre>
     * 旧指纹 + 1 lane ：1507 ms      ← 换算法之前
     * 新指纹 + 1 lane ： 524 / 562 / 536 ms   （≈2.8× 于旧串行，全靠指纹）
     * 新指纹 + 2 lane ： 655 / 680 / 649 ms   ← 比串行慢
     * 新指纹 + 4 lane ： 614 / 619 / 635 ms
     * 新指纹 + 8 lane ： 572 / 578 / 571 ms
     * </pre>
     * 原先的假设「先修指纹、并行就能用」**被实测否掉了**：修完指纹之后并行反而更亏。
     * 原因是层太小 —— 每层只有 beam 宽（45–135）个父节点、每个约 30µs，
     * 而每层派发一次的线程唤醒 + 切片合并就要几百 µs，开销盖过收益。
     * 蓝本每层的工作比这重得多（要 fork 整套预测模拟器），所以它开 8 lane 划算。
     *
     * <p>结论：**留着代码和开关，默认关**。等哪天单节点展开变重（比如把怪物 AI 状态机
     * 也镜像进来）再复测，别凭「应该更快」开。
     */
    public static int defaultParallelLanes() {
        Integer override = Integer.getInteger(ParallelExpansion.DEGREE_PROPERTY);
        if (override != null) {
            return Math.max(1, Math.min(ParallelExpansion.MAX_DEGREE, override.intValue()));
        }
        return 1;
    }

    /**
     * 给并行展开造一条 lane 的 worker。
     *
     * <p>照蓝本「每条 lane 有自己的求解器，候选只在 lane 内物化」：worker 只带**不可变**的东西
     * （配置 + 打分起点 {@link Score.Root} + 遗物回血），可变状态里唯一要一致的是日志去重集合，
     * 所以把它共享出去；其余（转置表、计数器）各 lane 各自一份，互不干扰。
     */
    BeamSolver expansionWorker() {
        BeamSolver worker = new BeamSolver(energyPerTurn, handSize, beamWidth, depth,
                nodeLimit, cardBranchLimit);
        worker.maxPotionUses = maxPotionUses;
        worker.rootHp = rootHp;
        worker.rootMaxHp = rootMaxHp;
        worker.rootTurn = rootTurn;
        worker.scoreRoot = scoreRoot;
        worker.postCombatRelicHeal = postCombatRelicHeal;
        worker.reportedRejects = reportedRejects;
        worker.parallelLanes = 1;               // worker 只做单节点展开，不再往下并行
        worker.potionStrategy = potionStrategy; // 并行通道必须用同一份策略
        return worker;
    }

    /** 并行展开时一条 lane 的入口（就是普通展开，只是换了个 worker 实例）。 */
    void expandInto(SearchNode node, List<SearchNode> plays, List<SearchNode> ended, int branchLimit) {
        expandTurnLayer(node, plays, ended, branchLimit);
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
        lastContinuations = ContinuationPlan.empty();
        lastZeroCostPrefixes = 0;
        lastZeroCostAttempts = 0;
        lastZeroCostImproved = false;
        lastPotionStrategicCost = 0;
        lastPotionHpSaved = 0;
        lastPotionSpendRejected = false;
        lastPolicySatisfied = true;
        lastPolicyViolation = "";
        lastDeathTurn = -1;
        lastSelectedSurvives = false;

        // 固定前缀先单独验一遍：验不过就整条作废（蓝本 ApplyFixedPrefix 的 null 分支）。
        // 放在这里是为了**别让搜索跑完整趟才发现前缀是废的**，也避免下面的加宽循环误判成「没打赢」。
        if (!fixedPrefix.isEmpty()) {
            bindRoot(root);             // 前缀回放要算分，先绑估值起点
            if (applyFixedPrefix(nodeOf(root.copy(), null, null, 0, null), fixedPrefix) == null) {
                Trace.warn("SEARCH PREFIX REJECTED: " + describePrefix(fixedPrefix));
                return null;
            }
        }

        // 蓝本 RunNoveltyPortfolioPass：先用**独立预算**跑一趟新颖度探索，再把剩下的预算交给 Beam。
        // 顺序与蓝本一致（探索在前、Beam 在后），选哪条由 better() 决定 —— 所以第一章那条
        // 「几毫秒算完」的结果不会被这趟探索改差，第二章则会真的把预算算掉。
        SearchNode noveltySelected = null;
        long beamBudgetMillis = budgetMillis;
        int beamNodeLimit = nodeLimit;
        if (noveltyEnabled && fixedPrefix.isEmpty()) {
            long noveltyMillis = noveltyBudgetMillis(budgetMillis, root.boss);
            int noveltyNodes = noveltyBudgetNodes(nodeLimit);
            if (noveltyMillis > 0 && noveltyNodes > 0) {
                noveltySelected = searchNovelty(root, noveltyMillis, noveltyNodes, startedAt);
                lastNodes += lastNoveltyNodes;
            }
        }
        if (beamNodeLimit > 0) {
            beamNodeLimit = Math.max(1, beamNodeLimit - lastNoveltyNodes);
        }
        if (beamBudgetMillis != Long.MAX_VALUE) {
            long spent = (System.nanoTime() - startedAt) / 1_000_000L;
            beamBudgetMillis = Math.max(1L, beamBudgetMillis - spent);
        }

        int maxTurns = Math.max(1, depth);
        // 后处理（药水对账 / 0 费补搜）共用这条截止线：Beam 那份预算剩下的时间
        long deadline = beamBudgetMillis == Long.MAX_VALUE
                ? Long.MAX_VALUE
                : System.nanoTime() + beamBudgetMillis * 1_000_000L;
        SearchNode selected;
        if (portfolioEnabled && fixedPrefix.isEmpty()) {
            // 蓝本 RunBeamWidthPortfolioPass：五个成员共享同一份节点/时间预算，取最好的一条
            selected = runPortfolio(root, maxTurns, beamNodeLimit, beamBudgetMillis, startedAt);
        } else {
            selected = beamPass(root, maxTurns, Math.max(1, beamWidth), cardBranchLimit,
                    beamNodeLimit, deadline);
        }

        // 蓝本 RunNoveltyPortfolioPass 的最后一步：探索 vs Beam 二选一。
        // 判据直接用已有的 better()（胜利 > 存活 > 战略掉血少 > …），不另造一套。
        //
        // 🔴 这一步必须在后处理**之前**（2026-11-01 真机）：新颖度那条候选是在闸门之前算的，
        // 它可能带着喝药步骤。以前把二选一放在闸门后面，闸门否掉的药水会被它靠 better()
        // 又捞回来 —— 日志里 7 次喝药有 6 次是 `POTION_GATE rejected=true` 之后照喝不误。
        // 蓝本 post-search 链处理的也是「已经定下来的答案」，所以二选一先做才是对的顺序。
        if (noveltySelected != null && (selected == null || better(noveltySelected, selected))) {
            selected = noveltySelected;
            lastNoveltyAdopted = true;
            // 面板的「depth N turns」是「这条计划铺到第几回合」，只许变大不许变小：
            // 新颖度那条常常是「本回合内的节点」（turn 不变），直接赋值会显示成 0 回合。
            lastDepth = Math.max(lastDepth, lastNoveltyTurns);
        }

        // 蓝本 RunZeroCostOpeningRescue（PostSearch.cs:352-392，后处理第 4 趟）：
        // 主搜索的答案是「这回合直接结束」时，再补一趟「先把免费牌打出去会怎样」。
        // 蓝本放在 coordinator 的 post-search 链里，我们这里没有 coordinator，
        // 所以放在同一份 deadline 内、紧跟主搜索 —— 顺序与蓝本一致（rescue 在 mid-combat 之前，
        // 我们还没搬 mid-combat）。前缀搜索自己就是 sub-solver，不能递归触发补搜。
        if (fixedPrefix.isEmpty()) {
            // 药水对账在前（蓝本 post-search 也是先药水后免费牌）：它可能把路线换成不喝药的，
            // 换完之后「第一步是结束回合」的补搜条件才有机会成立。
            selected = gatePotionSpending(root, selected, deadline);
            selected = zeroCostOpeningRescue(root, selected, deadline);
            // 最终复核必须放在这两个后处理**之后**：它们都可能换掉答案，
            // 换完之后是否仍满足 Force / RequireAtLeastOne 要重新判一次。
            selected = verifyPotionStrategy(selected);
        }

        lastSelectedScore = selected == null ? Long.MIN_VALUE : selected.score;
        lastSelectedVictory = isCompleteVictory(selected);
        lastStopReason = hitNodeLimit ? "node_limit"
                : (lastPassCutShort ? "time_limit"
                : (lastSelectedVictory ? "victory" : "horizon"));
        if (selected != null) {
            // 补搜可能会换掉主搜索的答案，路线要跟着换（AUTO 执行的是 lastSteps）。
            // 这里还会把最终路线整条回放一遍来采续用边界，所以计时放在它**之后**。
            publishFinalRoute(selected);
        }
        // 回放也算进这次搜索的耗时：面板上的「N ms」必须是真花掉的时间
        lastMillis = (System.nanoTime() - startedAt) / 1_000_000L;
        return selected == null ? null : selected.firstAction();
    }

    /**
     * BFWS 新颖度搜索（蓝本 {@code CombatBeamSolver.RunNoveltyOpen}，{@code NoveltySearch.cs:57-200}）。
     *
     * <p>和 Beam 的区别只有两点，但正是「算不动」与「算得动」的区别：
     * <ol>
     *   <li>**不按束宽剪枝**：每个展开出来的子状态都按「新颖度」排队，只要它还带来没见过的事实组合
     *       就留在 open 里（容量 {@code MaxOpen=2048}，满了丢最差）；</li>
     *   <li>**按动作数/回合数限深**（{@code MaxActions=128 / MaxTurns=32}），而不是「打完这一回合就收」。</li>
     * </ol>
     *
     * @param millis    这一趟的时间帽（{@link #noveltyBudgetMillis}）
     * @param nodeLimit 这一趟最多展开几个父节点（{@link #noveltyBudgetNodes}）
     * @param startedAt 整次请求的起点（用来算诊断耗时）
     * @return 这一趟找到的最好节点；什么都没找到就是 null
     */
    private SearchNode searchNovelty(SimState root, long millis, int nodeLimit, long startedAt) {
        NoveltySearch.Options options = noveltyOptions;
        options.validate();
        long deadline = millis == Long.MAX_VALUE
                ? Long.MAX_VALUE
                : System.nanoTime() + millis * 1_000_000L;
        bindRoot(root);
        reportedRejects.clear();

        NoveltySearch.BoundedOpen open = new NoveltySearch.BoundedOpen(options.maxOpen);
        NoveltySearch.PackedNovelty novelty = new NoveltySearch.PackedNovelty(
                options.width, options.maxNoveltyEntries);
        long sequence = 0;
        int expanded = 0;
        int horizonLeaves = 0;
        int nonNovelPruned = 0;
        int openDropped = 0;
        int peakOpen = 0;
        int startTurn = root.turn;
        int maxTurnSeen = startTurn;
        String stop = "open_exhausted";

        SearchNode fallback = null;
        SearchNode bestTerminal = null;
        SearchNode currentTurnCandidate = null;
        int bestTurnSeen = startTurn;

        SearchNode seed = nodeOf(root.copy(), null, null, 0, null);
        if (seed.terminal()) {
            lastNoveltyStop = "root_terminal";
            return null;
        }
        // 种子不吃「熟悉」判据（蓝本 initialSeed: true）
        int seedNovelty = novelty.evaluate(
                NoveltySearch.partitionOf(seed.state, seed.potionsUsed),
                NoveltySearch.factsOf(seed.state));
        open.enqueue(seed, seedNovelty, seed.score, sequence++);
        peakOpen = open.size();

        while (!open.isEmpty()) {
            if (nodeLimit > 0 && expanded >= nodeLimit) {
                stop = "node_limit";
                break;
            }
            if (expired(deadline)) {
                stop = "time_limit";
                break;
            }
            SearchNode parent = open.dequeue();
            // 蓝本：动作数 / 回合数超界 = 地平线上的叶子，不再展开
            if (parent.depth >= options.maxActions
                    || parent.state.turn >= startTurn + options.maxTurns) {
                horizonLeaves++;
                continue;
            }
            novelty.prepareParent(
                    NoveltySearch.partitionOf(parent.state, parent.potionsUsed),
                    NoveltySearch.factsOf(parent.state));

            // 展开：和 Beam 完全同一套（每张牌 × 每个目标 × 选牌分支，外加结束回合）
            List<SearchNode> plays = new ArrayList<SearchNode>();
            List<SearchNode> ended = new ArrayList<SearchNode>();
            expandTurnLayer(parent, plays, ended, cardBranchLimit);
            expanded++;
            lastStates += plays.size() + ended.size();

            List<SearchNode> children = new ArrayList<SearchNode>(plays.size() + ended.size());
            children.addAll(plays);
            children.addAll(ended);
            for (SearchNode child : children) {
                maxTurnSeen = Math.max(maxTurnSeen, child.state.turn);
                if (fallback == null || child.score > fallback.score) {
                    fallback = child;
                }
                if (child.terminal()) {
                    // 终局不进 open：直接进 completed（蓝本 completed.Add(child)）
                    if (bestTerminal == null || better(child, bestTerminal)) {
                        bestTerminal = child;
                    }
                    continue;
                }
                int width = novelty.evaluate(
                        NoveltySearch.partitionOf(child.state, child.potionsUsed),
                        NoveltySearch.factsOf(child.state));
                if (width > options.width) {
                    // 「熟悉」= 所有一元组/二元组都见过。蓝本默认额度 0，一个都不放。
                    nonNovelPruned++;
                    continue;
                }
                SearchNode evicted = open.enqueue(child, width, child.score, sequence++);
                if (evicted != null) {
                    openDropped++;
                }
                if (open.size() > peakOpen) {
                    peakOpen = open.size();
                }
            }
            // 蓝本 ConsiderCurrentTurnCandidate：只在**起点回合**登记「走到回合边界」的最好那条
            for (SearchNode endedNode : ended) {
                SearchNode boundary = endedNode.parent;
                if (boundary == null || boundary.depth == 0) {
                    continue;
                }
                if (boundary.state.turn != startTurn) {
                    continue;
                }
                if (boundary.state.hp <= 0 || Score.projectedHpOf(boundary.state) <= 0) {
                    continue;
                }
                if (currentTurnCandidate == null || better(boundary, currentTurnCandidate)) {
                    currentTurnCandidate = boundary;
                }
            }
            if (novelty.limitReached) {
                stop = "novelty_limit";
                break;
            }
            if (bestTerminal != null && isCompleteVictory(bestTerminal)
                    && rootHp - bestTerminal.state.hp <= 0) {
                stop = "hp_target";          // 零伤完胜，不用再找了
                break;
            }
        }

        lastNoveltyNodes = expanded;
        lastNoveltyOpen = Math.max(peakOpen, open.size());
        lastNoveltyEntries = novelty.entries;
        // 选中的那条路线走到第几回合（不是「见到的最远回合」—— 那个只说明搜索铺得开）
        lastNoveltyTurns = Math.max(0, bestTurnSeen - startTurn);
        lastNoveltyMillis = (System.nanoTime() - startedAt) / 1_000_000L;
        lastNoveltyStop = stop;
        Trace.info("NOVELTY stop=" + stop + " expanded=" + expanded + " states=" + lastStates
                + " open_peak=" + lastNoveltyOpen + " open_dropped=" + openDropped
                + " familiar_pruned=" + nonNovelPruned + " horizon_leaves=" + horizonLeaves
                + " entries=" + novelty.entries + " atoms=" + novelty.atomCount()
                + " unary=" + novelty.unary + " binary=" + novelty.binary
                + " chosen_turn=" + lastNoveltyTurns + " max_turn=" + (maxTurnSeen - startTurn)
                + " ms=" + (System.nanoTime() - startedAt) / 1_000_000L);

        // 蓝本 adoptedNode = currentBestNode ?? currentTurnCandidateNode
        SearchNode best = bestTerminal;
        if (currentTurnCandidate != null && (best == null || better(currentTurnCandidate, best))) {
            best = currentTurnCandidate;
        }
        if (best == null) {
            best = fallback;
        }
        if (best != null) {
            bestTurnSeen = best.state.turn;
            lastNoveltyTurns = Math.max(0, bestTurnSeen - startTurn);
        }
        return best;
    }

    /**
     * 一趟 Beam（含蓝本 {@code EscalateSearchWhenNoVictory} 的「没打赢就加宽」补搜）。
     *
     * <p>组合里每个成员各跑一次；单成员模式下就是原来那条唯一的路。
     */
    private SearchNode beamPass(SimState root, int maxTurns, int width, int branchLimit,
                                int nodeLimit, long deadline) {
        long passStart = System.nanoTime();
        SearchNode selected = searchToDepth(root, maxTurns, Math.max(1, width), branchLimit,
                nodeLimit, deadline);
        long lastPassMillis = (System.nanoTime() - passStart) / 1_000_000L;

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
            long remaining = deadline == Long.MAX_VALUE
                    ? Long.MAX_VALUE
                    : (deadline - System.nanoTime()) / 1_000_000L;
            // 按扩展倍数估下一轮耗时；估出来超过剩余预算就不启动（蓝本同款防超时）
            long projected = Math.max(1, lastPassMillis) * ESCALATION_FACTOR;
            if (remaining != Long.MAX_VALUE && (remaining <= 0 || remaining < projected)) {
                break;
            }
            int multiple = 1 << (completed + 1);        // 2倍、4倍
            SearchNode candidate = searchToDepth(root, maxTurns,
                    scale(width, multiple, MAX_ESCALATED_WIDTH),
                    scale(branchLimit, multiple, MAX_ESCALATED_BRANCHES),
                    nodeLimit <= 0 ? 0 : nodeLimit * multiple,
                    deadline);
            lastPassMillis = (System.nanoTime() - passStart) / 1_000_000L;
            if (!isStrictlyBetter(candidate, selected)) {
                break;                                  // 没严格改善就停，继续扩预算没意义
            }
            selected = candidate;
        }
        return selected;
    }

    /**
     * Beam 宽度组合（蓝本 {@code RunBeamWidthPortfolioPass} + {@code BeamWidthPortfolioGate}）。
     *
     * <p>成员清单见 {@link BeamWidthPortfolio#productionMembers}。规则照抄蓝本：
     * <ul>
     *   <li>首个成员永远是基线宽度，且**逐位等于单成员搜索**；</li>
     *   <li>其余成员先过准入门（{@link BeamWidthPortfolio#rejectRefinement}）：
     *       基线没搜干净 / 基线已经证明零伤 / 基线自己吃掉超过 1/4 时间 / 节点或时间余量不够，
     *       就不启动，原因记进诊断；</li>
     *   <li>节点与时间都是**共享**的：每个成员只拿剩余的那一份；</li>
     *   <li>比较用既有的 {@link #better}，同分保留先出现的（= 基线），所以「组合不会比单趟差」；</li>
     *   <li>撞节点帽且没打到终局的成员不参与比较（蓝本 {@code SkippedNodeLimitNotTerminal}）。</li>
     * </ul>
     */
    private SearchNode runPortfolio(SimState root, int maxTurns, int nodeLimit,
                                    long budgetMillis, long startedAt) {
        java.util.List<BeamWidthPortfolio.Member> members =
                BeamWidthPortfolio.productionMembers(Math.max(1, beamWidth));
        lastPortfolioMembers.clear();
        lastPortfolioAdopted = "";

        // 全局候选池：每个成员把自己那一趟的 finalPool 交上来，最后统一排序
        java.util.List<SearchNode> pool = new ArrayList<SearchNode>();
        java.util.List<String> poolLabels = new ArrayList<String>();

        SearchNode best = null;
        String bestLabel = "";
        boolean baselineObserved = false;
        boolean baselineExhausted = false;
        boolean baselineZeroDamage = false;
        long baselineMillis = 0L;
        int baselineExpanded = 0;
        int baselineWidth = members.get(0).beamWidth;
        int expandedShared = 0;

        for (int i = 0; i < members.size(); i++) {
            BeamWidthPortfolio.Member member = members.get(i);
            long remainingMillis = budgetMillis == Long.MAX_VALUE
                    ? Long.MAX_VALUE
                    : budgetMillis - (System.nanoTime() - startedAt) / 1_000_000L;
            long remainingNodes = nodeLimit <= 0 ? 0 : Math.max(0, nodeLimit - expandedShared);

            String label = member.toString();
            String skip = null;
            if (remainingMillis != Long.MAX_VALUE && remainingMillis <= 0) {
                skip = "BudgetExhausted";
            } else if (i > 0) {
                skip = BeamWidthPortfolio.rejectRefinement(baselineExhausted, baselineZeroDamage,
                        baselineMillis, baselineWidth, member.beamWidth,
                        nodeLimit <= 0 ? -1 : remainingNodes, remainingMillis, budgetMillis,
                        baselineExpanded);
            }
            if (skip != null) {
                lastPortfolioMembers.add(label + "=skip(" + skip + ")");
                continue;
            }

            long memberDeadline = remainingMillis == Long.MAX_VALUE
                    ? Long.MAX_VALUE
                    : System.nanoTime() + remainingMillis * 1_000_000L;
            int memberNodeLimit = nodeLimit <= 0 ? 0
                    : (i == 0 ? nodeLimit : (int) Math.max(1, remainingNodes));

            long memberStart = System.nanoTime();
            int nodesBefore = lastNodes;
            boolean hitBefore = hitNodeLimit;
            SearchNode candidate = withBeamPolicy(member.secondRankBand, member.baseScoreOnly)
                    .beamPass(root, maxTurns, member.beamWidth, cardBranchLimit,
                            memberNodeLimit, memberDeadline);
            long memberMillis = (System.nanoTime() - memberStart) / 1_000_000L;
            int memberExpanded = lastNodes - nodesBefore;
            expandedShared += memberExpanded;

            // 成员之间互不影响：把「这一趟被截断了吗」换成这一趟自己的口径
            boolean memberHitLimit = hitNodeLimit;
            hitNodeLimit = hitBefore || memberHitLimit;

            if (i == 0) {
                baselineObserved = true;
                baselineMillis = memberMillis;
                baselineExpanded = memberExpanded;
                baselineWidth = member.beamWidth;
                baselineExhausted = !memberHitLimit && !lastPassCutShort;
                baselineZeroDamage = candidate != null && isCompleteVictory(candidate)
                        && candidate.state.hp >= rootHp && candidate.potionsUsed == 0;
                if (candidate != null) {
                    best = candidate;
                    bestLabel = label;
                }
                for (SearchNode node : lastFinalPool) {
                    pool.add(node);
                    poolLabels.add(label);
                }
            } else if (candidate != null) {
                boolean terminal = isCompleteVictory(candidate) || SearchNode.noAliveMonster(candidate.state);
                boolean comparable = !memberHitLimit || terminal;
                if (comparable && (best == null || better(candidate, best))) {
                    best = candidate;
                    bestLabel = label;
                }
                // 被节点上限截断的那一趟，前沿是半成品，只在它真的打完时才算数
                if (comparable) {
                    for (SearchNode node : lastFinalPool) {
                        if (!memberHitLimit || node.terminal()) {
                            pool.add(node);
                            poolLabels.add(label);
                        }
                    }
                }
            }
            lastPortfolioMembers.add(label + "=" + memberExpanded + "n/" + memberMillis + "ms");
        }

        // 全局重排：在「所有成员 × 所有终局/前沿」里挑一条（蓝本 finalPool 口径）
        for (int i = 0; i < pool.size(); i++) {
            SearchNode node = pool.get(i);
            if (node == null || node.action == null) {
                continue;                   // 根节点没有可执行动作
            }
            if (best == null || better(node, best)) {
                best = node;
                bestLabel = poolLabels.get(i);
            }
        }

        lastPortfolioAdopted = bestLabel;
        Trace.info("PORTFOLIO baseline_w=" + baselineWidth + " members=" + lastPortfolioMembers
                + " adopted=" + (bestLabel.isEmpty() ? "none" : bestLabel));
        return best;
    }

    /** 蓝本的 {@code Scale}：按倍数放大并封顶。 */
    private static int scale(int value, int multiple, int cap) {        if (value <= 0) {
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
        reportedRejects.clear();    // 每次搜索把「打不出的原因」重新报一遍
        int turnsDone = 0;
        int expanded = 0;           // 本轮展开数（节点帽按轮算，蓝本每一趟都是新的 _run）

        List<SearchNode> frontier = new ArrayList<SearchNode>();
        SearchNode seed = nodeOf(root.copy(), null, null, 0, null);
        if (!fixedPrefix.isEmpty()) {
            // 蓝本 ApplyFixedPrefix（Phases.cs:1106）：把固定前缀回放完的节点当新的搜索根。
            // 验不过就整趟作废 —— 调用方（补搜）把 null 当成「这条前缀不能用」，不会静默换成别的路线。
            seed = applyFixedPrefix(seed, fixedPrefix);
            if (seed == null) {
                lastPassCutShort = true;    // 让加宽循环别再重试（前缀不会因预算变化而变合法）
                return null;
            }
        }
        frontier.add(seed);
        // 并行展开的 lane（每趟搜索一套）：必须在 bindRoot 之后建，worker 要拿到打分起点。
        int lanes = parallelLanes > 0 ? parallelLanes : defaultParallelLanes();
        ParallelExpansion expander = lanes > 1 ? ParallelExpansion.of(this, lanes) : null;
        // 打到战斗结束的路线只留**最好的一条**：每个叶子都攥着一份完整状态，不能无脑攒
        SearchNode bestTerminal = null;
        // 蓝本 ConsiderCurrentTurnCandidate：**本回合能安全走完**的路线里最好的那条。
        // ApplyCurrentTurn 的答案优先级是 `currentBestNode ?? currentTurnCandidateNode`
        // （Phases.cs:2218 `adoptedNode = currentBestNode ?? currentTurnCandidateNode`）。
        SearchNode currentTurnCandidate = null;
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
            // 剩下的额度按「还要给几个保留回合层留」均分 —— 一层吃不掉整份预算。
            // 保留层数固定 4（Boss 房 8），超过之后分母兜底为 1（蓝本 remainingReservedLayers 同口径）。
            int reservedLayers = root.boss ? BOSS_RESERVED_TURN_LAYERS : STANDARD_RESERVED_TURN_LAYERS;
            int remainingTurns = Math.max(1, reservedLayers - turn);
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
                if (expander != null && active.size() > 1) {
                    // 并行展开：lane 之间无共享可变状态，结果按父节点原序合并（蓝本有序提交）
                    expander.expandAll(active, plays, ended, branchLimit);
                    expanded += active.size();      // 蓝本口径：一个展开 = 一条「世界线」
                } else {
                    for (SearchNode node : active) {
                        expandTurnLayer(node, plays, ended, branchLimit);
                        expanded++;                     // 蓝本口径：一个展开 = 一条「世界线」
                    }
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
            // 蓝本 ConsiderCurrentTurnCandidate（Phases.cs:372）：
            // 只在**本回合**（= 起点回合）登记，判据是
            // `actionCount > 0 && 走到回合边界 && !playerDead && projectedPlayerHp > 0`，
            // 边界节点就是「结束回合前的那一步」。
            if (turn == 0) {
                for (SearchNode endedNode : ended) {
                    SearchNode boundary = endedNode.parent;
                    if (boundary == null || boundary.depth == 0) {
                        continue;
                    }
                    if (boundary.state.hp <= 0 || Score.projectedHpOf(boundary.state) <= 0) {
                        continue;
                    }
                    if (currentTurnCandidate == null || better(boundary, currentTurnCandidate)) {
                        currentTurnCandidate = boundary;
                    }
                }
            }
            frontier = prune(ended, width);
            turnsDone = turn + 1;
        }
        lastNodes += expanded;              // 面板口径：累计查阅的世界线（多轮加宽会累加）
        lastPassCutShort = outOfTime;
        // 加宽会跑多轮，深度取「跑得最深的那一轮」——没跑完的那轮不该把面板数字拉低
        lastDepth = Math.max(lastDepth, turnsDone);

        // 蓝本 adoptedNode = currentBestNode ?? currentTurnCandidateNode：
        // ① 找到完整胜利就交它；② 否则交「本回合能安全走完」里最好的那条；
        // ③ 两者都没有（例如第一回合就走死了）才退回前沿里最好的叶子。
        SearchNode best = bestTerminal != null ? bestTerminal
                : (currentTurnCandidate != null ? currentTurnCandidate : bestOf(frontier));

        // 蓝本 finalPool = [..completed, ..frontier]：把这一趟找到的**所有**候选留下来，
        // 交给宽度组合在全局范围里排，而不是「每趟只交一个节点」。
        lastFinalPool.clear();
        if (bestTerminal != null) {
            lastFinalPool.add(bestTerminal);
        }
        lastFinalPool.addAll(frontier);
        if (best != null) {
            lastFinalPool.add(best);
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

    /** 前沿里按最终排序最好的一个。 */
    private SearchNode bestOf(List<SearchNode> nodes) {
        SearchNode best = null;
        for (SearchNode node : nodes) {
            if (best == null || better(node, best)) {
                best = node;
            }
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
            boolean usePotion = n.action.kind == PlanAction.Kind.USE_POTION;
            reversed.addFirst(new RouteStep(
                    n.action.kind == PlanAction.Kind.END_TURN,
                    usePotion,
                    n.cardKey,
                    // 喝药步骤带上槽位：两瓶同 ID 时只有它能把「喝哪一格」说清
                    usePotion ? n.action.cardIndex : -1,
                    n.action.targetIndex, n.action.choiceIndex));
        }
        return new java.util.ArrayList<RouteStep>(reversed);
    }

    /**
     * 把「最终采纳的那条路线」一次性发布成执行层要的三件东西：
     * 动作序列、可执行步骤、以及这条路线上的稳定回合边界。
     *
     * <p>三个字段必须**同源**。药水闸 / 0 费补搜 / novelty 都会换掉 `selected`，
     * 任何一处单独更新都会留下「步骤来自新路线、边界来自旧路线」的错配 ——
     * 那正是续跑能命中却执行出另一条世界线的原因。
     *
     * <p>🔴 边界**不是**从节点链上抄下来的：{@link ContinuationPlan#capture} 只挑下标，
     * 真正的状态由下面这次**回放**在预期边界处采集（蓝本 {@code Phases.cs:520-620}）。
     * 回放顺带验证了「发布出去的这条路线能从根走通」；缺一个预期边界、或者某一步走不通，
     * 都抛 {@link IllegalStateException} 显式失败 —— 静默发布半条世界线，后果是执行层照着
     * 一份搜索从未推荐过的剩余序列出牌。
     */
    void publishFinalRoute(SearchNode selected) {
        lastSelectedNode = selected;
        lastRoute = routeOf(selected);
        lastSteps = stepsOf(selected);
        lastDeathTurn = deathTurnOf(selected);
        lastSelectedSurvives = selected.state.hp > 0
                && Score.projectedHpOf(selected.state) > 0;
        lastContinuations = captureContinuations(selected);
    }

    /**
     * 回放最终路线并采集续用边界（蓝本 {@code CombatBeamSolver.Phases.cs:520-620}）。
     *
     * <p>为什么值得再跑一遍：节点链上的 {@code state} 是**展开时**的快照，
     * 而执行层真正要复用的是「这条路线走到那一步时会是什么状态」。
     * 两者只有在回放成功时才证明是同一个东西。
     */
    private ContinuationPlan captureContinuations(SearchNode selected) {
        java.util.List<SearchNode> chain = routeNodesOf(selected);
        ContinuationPlan.Capture capture = ContinuationPlan.capture(chain);
        if (capture.boundaryCount() == 0) {
            return capture.complete();          // 没有跨回合边界：空计划，不必回放
        }
        SimState rootState = chain.get(0).state;
        bindRoot(rootState);
        SearchNode node = nodeOf(rootState.copy(), null, null, 0, null);
        int actionIndex = 0;
        for (PlanAction action : lastRoute) {
            if (node.terminal()) {
                break;
            }
            SearchNode next = replayPrefixAction(node, action);
            if (next == null) {
                throw new IllegalStateException(
                        "最终路线回放第 " + actionIndex + " 步走不通：" + action);
            }
            node = next;
            capture.observe(actionIndex, node.state);
            actionIndex++;
        }
        return capture.complete();
    }

    /**
     * 这条路线在第几回合把玩家打死了（{@code -1} = 没死）。
     *
     * <p>取链上**第一个** {@code hp <= 0} 的节点：那一步就是致命的。
     * 注意不能只看终局节点 —— 死亡之后搜索仍可能继续展开（不会，但别依赖这一点）。
     */
    static int deathTurnOf(SearchNode selected) {
        if (selected == null) {
            return -1;
        }
        java.util.List<SearchNode> chain = routeNodesOf(selected);
        for (SearchNode node : chain) {
            if (node.state == null || node.state.hp <= 0) {
                if (node.state == null) {
                    continue;
                }
                // 🔴 END_TURN 节点的状态是「敌方阶段之后」：`CombatFlow.endTurn` 先让敌人打完（④）
                // 再把 `turn` 推到下一回合（⑥）。所以死在这一回合的敌方阶段，节点里的 turn
                // 已经 +1 了 —— 不减这一下，「本回合就死」会被报成下一回合，
                // 主线程于是不会停手（蓝本 DeathTurn == StartTurnNumber 判等的就是这一格）。
                boolean diedAtEnemyPhase = node.action != null
                        && node.action.kind == PlanAction.Kind.END_TURN;
                return diedAtEnemyPhase ? Math.max(1, node.state.turn - 1) : node.state.turn;
            }
        }
        return -1;
    }

    /** 回溯出「从根（action 为 null）到该节点」的**节点**序列，边界提取要连根一起看。 */
    private static java.util.List<SearchNode> routeNodesOf(SearchNode best) {
        java.util.LinkedList<SearchNode> reversed = new java.util.LinkedList<SearchNode>();
        for (SearchNode n = best; n != null; n = n.parent) {
            reversed.addFirst(n);
        }
        return new java.util.ArrayList<SearchNode>(reversed);
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
            reportReject(state, cardIndex, rejected.getMessage());
            return null;
        } catch (Throwable t) {
            reportReject(state, cardIndex, String.valueOf(t));
            return null;
        }
        return nodeOf(copy, PlanAction.play(cardIndex, targetIndex, choiceIndex), node,
                node.depth + 1, state.hand.get(cardIndex).key());
    }

    /**
     * 用一格药水，返回新节点；用不出时返回 {@code null}。
     *
     * <p>对应蓝本 {@code PlanActionKind.UsePotion}：药水是和打牌同级的一等动作，
     * 不是「没牌可打才喝药」。被 {@link io.github.youqing.sts1solver.mirror.Potions#canUse}
     * 挡下的（瓶中仙子）自动跳过 —— 那是被动药水，玩家点不动。
     *
     * @param choiceIndex 三选一药水选第几张（0..2），其余药水传 {@code -1}
     */
    private SearchNode potionNode(SimState state, int slotIndex, int targetIndex,
                                  int choiceIndex, SearchNode node) {
        String potionId = state.potions.get(slotIndex);
        if (potionId == null
                || !io.github.youqing.sts1solver.mirror.Potions.canUse(potionId)) {
            return null;
        }
        SimState copy = state.copy();
        try {
            boolean used = io.github.youqing.sts1solver.mirror.Potions.use(
                    copy, potionId, targetIndex, choiceIndex);
            if (!used) {
                return null;
            }
        } catch (ActionRejected rejected) {
            reportReject(state, -1, rejected.getMessage());
            return null;
        } catch (Throwable t) {
            reportReject(state, -1, String.valueOf(t));
            return null;
        }
        copy.potions.set(slotIndex, null);       // 原版 TopPanel.destroyPotion(slot)
        return nodeOf(copy, PlanAction.potion(slotIndex, targetIndex, choiceIndex), node,
                node.depth + 1, potionId);
    }

    /** 多选形态的药水（赌徒酿造 / 灵药）。 */
    private SearchNode potionMultiNode(SimState state, int slotIndex, int[] choices,
                                       SearchNode node) {
        String potionId = state.potions.get(slotIndex);
        if (potionId == null
                || !io.github.youqing.sts1solver.mirror.Potions.canUse(potionId)) {
            return null;
        }
        SimState copy = state.copy();
        try {
            boolean used = io.github.youqing.sts1solver.mirror.Potions.useMulti(
                    copy, potionId, choices);
            if (!used) {
                return null;
            }
        } catch (ActionRejected rejected) {
            reportReject(state, -1, rejected.getMessage());
            return null;
        } catch (Throwable t) {
            reportReject(state, -1, String.valueOf(t));
            return null;
        }
        copy.potions.set(slotIndex, null);
        return nodeOf(copy, PlanAction.potionMulti(slotIndex, -1, choices), node,
                node.depth + 1, potionId);
    }

    /**
     * 把「这张牌为什么打不出」里**属于语义缺失**的那几种报一次（每次搜索每张牌每原因一条）。
     *
     * <p>为什么要专门报：搜索里「打不出」是静默跳过的 —— 分支直接不生成。
     * 如果没有日志，覆盖缺口的表现就是「AI 手里有牌却结束回合」，只能靠人肉对日志。
     * 2026-10-23 就是这样埋了一整天（升级态镜像缺失）。
     *
     * <p>只报语义类：没有镜像 / 不支持的费用 / 目标非法。
     * 「能量不够」「被缠绕」「手牌下标越界」是正常拒绝，不报。
     */
    private void reportReject(SimState state, int cardIndex, String message) {
        if (message == null) {
            return;
        }
        boolean semantic = message.startsWith("no mirror")
                || message.startsWith("unsupported cost")
                || message.startsWith("effect needs");
        if (!semantic) {
            return;
        }
        String cardKey = cardIndex >= 0 && cardIndex < state.hand.size()
                ? state.hand.get(cardIndex).key() : "?";
        String line = cardKey + " -> " + message;
        if (reportedRejects.add(line)) {
            Trace.warn("SEARCH REJECT: " + line);
        }
    }

    /**
     * 结束回合并推进到下一回合（蓝本 {@code BuildAcceptedEndTurnNodes} 的简化版）。
     *
     * <p>🔴 回合末/回合初的钩子（Mayhem 自动打牌、能力结算）也可能抛
     * {@link ActionRejected}。那时候异常会从 {@code CombatFlow.endTurn} 一路穿透到后台线程，
     * 整个搜索直接死掉 —— 主线程只看到「空计划」，于是满手牌满能量空过一回合
     * （2026-10-30 真机实测两次）。所以这里把「这条世界线走不通」降级成「不产出这个子节点」。
     *
     * @return 新节点；这条路线走不通时返回 {@code null}
     */
    private SearchNode endTurnOf(SearchNode node) {
        SimState afterTurn = node.state.copy();
        try {
            CombatFlow.endTurn(afterTurn, energyPerTurn, handSize);
        } catch (ActionRejected rejected) {
            String message = rejected.getMessage();
            if (message != null && reportedRejects.add("END_TURN -> " + message)) {
                Trace.warn("SEARCH END_TURN REJECT: " + message);
            }
            return null;
        }
        return nodeOf(afterTurn, PlanAction.endTurn(), node, node.depth + 1, null);
    }

    /** 记住本轮搜索起点的血量/上限/回合/攻击总量/敌人数，供 {@link Score} 算各路「成长」。 */
    void bindRoot(SimState root) {
        rootHp = root.hp;
        rootMaxHp = root.maxHp;
        rootTurn = root.turn;
        scoreRoot = new Score.Root(root);
        postCombatRelicHeal = io.github.youqing.sts1solver.mirror.PostCombatRelicHeal.of(root.relics);
    }

    /** 建节点：一次算出基础分（定路线）、排序分（定剪枝）和攻击推进量 / 用药数。 */
    private SearchNode nodeOf(SimState state, PlanAction action, SearchNode parent, int depth,
                              String cardKey) {
        Score score = Score.of(state, scoreRoot, depth);
        int potionsUsed = (parent != null ? parent.potionsUsed : 0)
                + (action != null && action.kind == PlanAction.Kind.USE_POTION ? 1 : 0);
        return new SearchNode(state, action, parent, depth, score.baseTotal(), score.total(),
                cardKey, offensiveProgressOf(state), potionsUsed);
    }

    /**
     * 蓝本 {@code Snapshot.OffensiveProgressValue}（{@code CombatBeamSolver.StateEvaluation.cs:448-451}）：
     * <pre>
     * offensiveProgress = 力量潜力 + 延迟伤害 + 反应伤害 + (活牌里有全身撞击 ? max(0, 格挡) : 0)
     * </pre>
     * 逐项的 STS1 Ironclad 对应：
     * <ul>
     *   <li>**力量潜力**（{@code StrategicEffectModel.cs:499}）：
     *       {@code 力量 × (AttackHits ?? AttackPlays)}，单位「点敌方血」。
     *       {@code AttackHits} = {@code EstimatedAttackHits}（{@code CardMechanismFacts.cs:44-55}），
     *       无飞刀项退化成 {@code ceil(命中段数 × 可达牌数 / 活牌数)}；
     *       命中段数查表（{@code CardMechanismFacts.cs:14-19}）对应 STS1：
     *       双持打击 = 2 段、回旋飞剑 = 3 段，其余 1 段。</li>
     *   <li>**延迟伤害**（敌人身上的 poison / demise / doom，:427-443）：铁甲战士没有施加源，恒 0。</li>
     *   <li>**反应伤害**（{@code SleightOfFlesh}，:444-446）：STS2 特有能力，恒 0。</li>
     *   <li>**全身撞击**（:447,451）：活牌（手牌 + 抽牌堆 + 弃牌堆，:124-128）里有 Body Slam
     *       就把当前格挡计入 —— 打出去伤害 = 格挡，是已兑现的攻击推进。</li>
     * </ul>
     * 只做 beam 排序第三键（{@code CompareBeamRankOrder}）与边界多样化的分组键，**不进分数**。
     */
    static int offensiveProgressOf(SimState s) {
        int liveCards = s.hand.size() + s.draw.size() + s.discard.size();
        int deckSize = Math.max(1, liveCards);
        int attackHitCount = 0;
        int totalAttackValue = 0;
        boolean hasBodySlam = false;
        // liveCards = 弃牌堆 + 抽牌堆 + 手牌（StateEvaluation.cs:124-128 的固定顺序）
        for (int pile = 0; pile < 3; pile++) {
            List<SimCard> cards = pile == 0 ? s.discard : pile == 1 ? s.draw : s.hand;
            for (SimCard c : cards) {
                if (!c.isAttack()) {
                    continue;
                }
                attackHitCount += attackHitsPerCard(c);
                totalAttackValue += Math.max(0, c.baseDamage);
                if (c.id.equals("Body Slam") || c.id.equals("Body Slam+")) {
                    hasBodySlam = true;
                }
            }
        }
        // 可达牌数与剩余回合（StrategicEffectModel.cs:306-322 的投影，SetupValueHorizonTurns=16）
        Integer strengthBoxed = s.playerPowers.get(io.github.youqing.sts1solver.mirror.DamageCalc.STRENGTH);
        int strength = strengthBoxed == null ? 0 : strengthBoxed;
        if (attackHitCount == 0 && !hasBodySlam) {
            return 0;   // 没有攻击推进来源，直接 0（省掉下面的投影算术）
        }
        int enemyHp = 0;
        for (SimMonster m : s.monsters) {
            enemyHp += Math.max(0, m.hp);
        }
        int cardsPerTurn = Math.min(10, Math.max(1, Math.min(5, deckSize)));
        int estimatedCycles = (int) Math.ceil((double) Math.max(1, enemyHp)
                / Math.max(1, totalAttackValue));
        int remainingTurns = Math.max(1, Math.min(16,
                estimatedCycles * Math.max(1, (int) Math.ceil(deckSize / 5.0))));
        int reachableCards = liveCards == 0
                ? 0
                : Math.min(deckSize * 2, remainingTurns * cardsPerTurn);
        // EstimatedAttackHits 的无飞刀主干（CardMechanismFacts.cs:48-54）：
        // actions = reachableCards，ordinaryDeckSize = deckSize
        int attackHits = reachableCards <= 0
                ? 0
                : (int) Math.ceil((double) attackHitCount * reachableCards / deckSize);
        // 蓝本 Damage(amount × (AttackHits ?? AttackPlays), enemyHp) 无 clamp —— 负力量保留负值
        int strengthPotential = strength * attackHits;
        int bodySlamBlock = hasBodySlam ? Math.max(0, s.block) : 0;
        return strengthPotential + bodySlamBlock;
    }

    /**
     * 每张攻击牌的命中段数（蓝本 {@code CardMechanismFacts.AttackHits} 查表的 STS1 对应，
     * {@code CardMechanismFacts.cs:14-19}）。STS1 铁甲战士的固定多段牌只有两张；
     * X 费多段（旋风斩/穿刺）段数动态，蓝本对动态段数读 {@code Repeat} 变量，
     * 我们没有静态段数，按 1 段保守估算（低估，不虚高）。
     */
    private static int attackHitsPerCard(SimCard c) {
        if (c.id.equals("Twin Strike") || c.id.equals("Twin Strike+")) {
            return 2;
        }
        if (c.id.equals("Sword Boomerang") || c.id.equals("Sword Boomerang+")) {
            return 3;
        }
        return 1;
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
    boolean better(SearchNode candidate, SearchNode incumbent) {
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
        // 蓝本 FinalPlanOrdering 的 DeathSaveUseCount 键：一次性救命用得越少越好
        int candidateSaves = candidate.state.deathSaveUseCount;
        int incumbentSaves = incumbent.state.deathSaveUseCount;
        if (candidateSaves != incumbentSaves) {
            return candidateSaves < incumbentSaves;
        }
        // 🔴 被抢走的金币（蓝本 FinalPlanOrdering.cs:253-256 的第 4 个键）：
        //   .ThenBy(candidate => theftPolicy == PreserveResources
        //            ? candidate.Quality.OutstandingStolenResource : 0)
        // 它排在**战略血量赤字之前** —— 也就是说「钱能不能追回来」比「少掉几滴血」更优先。
        // 这正是用户要的手感：宁愿挨一点，也不能让抢錢的跑了。
        // 没有抢錢怪时两边都是 0，这一键自然不生效。
        int candidateLoot = candidate.state.outstandingStolenGold();
        int incumbentLoot = incumbent.state.outstandingStolenGold();
        if (candidateLoot != incumbentLoot) {
            return candidateLoot < incumbentLoot;
        }
        // 掉血优先于回合数：2026-10-30 试过把两者换序（先比几回合结束），
        // 第一章连环战 37 → 45 血，更差，所以顺序维持蓝本原样。
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
    /** 包内可见：供测试直接断言赤字公式（含战后回血与死亡豁免的两项修正）。 */
    int strategicHpDeficit(SearchNode node) {
        int netLost = rootHp - node.state.hp;
        int floor = rootHp - rootMaxHp;          // 开局本来就缺的血，回血最多补到这里
        int maxHpDeficit = Math.max(0, rootMaxHp - node.state.maxHp);
        // 蓝本 StrategicHpDeficit 末两项：
        //   - PersistentValueOfRecoveredHp(recoveredHp - deathSaveHpRestored)
        //   + DeathSavePremium(deathSaveHpRestored)
        // 前一项把「复活回的血」从有效回血里剔出去（它不算这条路线挣的），
        // 后一项再收 9 倍溢价 —— 合起来等价于「净掉血 + DeathSaveBeamCost」。
        int deathSave = Score.deathSaveBeamCost(node.state.deathSaveHpRestored);
        // 蓝本 FinalPlanOrdering.cs:73-83：战后遗物回的血不会进 RecoveredPlayerHp，
        // 但它会像战斗中回的血一样带进下一场战斗，所以赤字里要单独补上。
        // 只有打赢才有（RankedPostCombatRelicHeal），且只算无条件回血
        // （带骨肉的阈值回血破坏「血更多永不更差」的不变式，蓝本刻意不计）。
        int relicHeal = isCompleteVictory(node)
                ? postCombatRelicHeal.monotoneHealFor(node.state.hp, node.state.maxHp)
                : 0;
        // 回不满就只回到满血：relicHeal 已按 headroom 截过，所以 max 里的 floor 不会被击穿
        return Math.max(netLost - relicHeal, floor) + maxHpDeficit + deathSave;
    }

    /** 蓝本 {@code CombatEndedTurn}：这场战斗在第几回合结束；没结束 = ∞。 */
    private static int combatEndedTurn(SearchNode node) {
        return node.terminal() ? node.state.turn : Integer.MAX_VALUE;
    }

    /** 还活着的敌人下标（展开「需要指定目标」的分支时用）。 */
    private static int[] aliveMonsterIndices(SimState state) {
        int n = 0;
        for (SimMonster m : state.monsters) {
            if (m.hp > 0) {
                n++;
            }
        }
        int[] out = new int[n];
        int k = 0;
        for (int i = 0; i < state.monsters.size(); i++) {
            if (state.monsters.get(i).hp > 0) {
                out[k++] = i;
            }
        }
        return out;
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
            // 打不出的诅咒/状态（cost = -2，且没有蓝蜡烛/医疗包）不占分支：
            // 以前每条都开一条分支再被 ActionRejected 丢掉，日志刷 "no mirror for card Pain+"。
            if (!io.github.youqing.sts1solver.mirror.CardPlayer.playableAtAll(state, card)) {
                continue;
            }
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
                // 「既要选牌、又要指定敌人」的牌（头槌 Headbutt = 打 9 伤 + 从弃牌堆捡一张回牌堆顶）
                // 也必须把目标枚举上。以前这里一律传 -1，效果层 requireTarget 直接抛，
                // 整张牌一条分支都不产出 —— 于是「1 能量 + 只有头槌能打」被判成无事可做，
                // 真机 20:31:01 就这么结束回合白挨了一刀。
                int[] targets = mirror.targeted
                        ? aliveMonsterIndices(state) : new int[]{-1};
                outer:
                for (int t : targets) {
                    for (int c = 0; c < choices; c++) {
                        if (branchLimit > 0 && produced >= branchLimit) {
                            break outer;
                        }
                        if (route(playNode(state, i, t, c, node), plays, ended)) {
                            produced++;
                        }
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

        // 药水：和打牌同级的一等动作（蓝本 PlanActionKind.UsePotion）
        // 蓝本 EnumeratePlannedPotionActions（ExpansionPlan.cs:146-147）：用过上限就整段不枚举。
        // 0 费补搜传 0 —— 蓝本 OpeningNoCostContinuationSource 的
        // potionPolicyOverride: Disabled, maximumPotionUses: 0 就是这个意思。
        boolean potionsClosed = maxPotionUses >= 0 && node.potionsUsed >= maxPotionUses;
        for (int slot = 0; !potionsClosed && slot < state.potions.size(); slot++) {
            if (branchLimit > 0 && produced >= branchLimit) {
                break;
            }
            String potionId = state.potions.get(slot);
            if (potionId == null
                    || !io.github.youqing.sts1solver.mirror.Potions.canUse(potionId)) {
                continue;
            }
            // 策略在这**最底层**就拦掉：任何通道（Beam/novelty/portfolio/补搜）都走这里，
            // 于是「禁止的药水」不会成为任何候选，也就不可能靠 better() 被捞回来。
            if (potionStrategy != null && !potionStrategy.allowsExplicitUse(slot, potionId)) {
                continue;
            }
            produced += expandPotion(state, slot, node, plays, ended, produced, branchLimit);
        }

        // 「结束回合」永远是合法动作（蓝本 PlanActionKind.EndTurn）；
        // 只有钩子在这条世界线上抛拒绝时才会缺这个子节点（见 endTurnOf）。
        SearchNode endedNode = endTurnOf(node);
        if (endedNode != null) {
            ended.add(endedNode);
        }
    }

    /**
     * 展开一格药水的全部分支，返回这一格产出的子节点数。
     *
     * <p>三种形态对应蓝本 {@code CardChoiceSpec} 的 {@code MinCount/MaxCount}：
     * <ul>
     *   <li>{@code NONE}：一条</li>
     *   <li>{@code SINGLE}：每个候选一条（三选一固定 3 条；液态记忆 = 弃牌堆大小条）</li>
     *   <li>{@code MULTI}：赌徒酿造 / 灵药，选 0~N 张 —— 按蓝本 {@code BuildChoices} 的
     *       「按基数保代表 + 按优先级补足到上限」枚举，不枚举全部 2^N 子集</li>
     * </ul>
     */
    private int expandPotion(SimState state, int slot, SearchNode node,
                             List<SearchNode> plays, List<SearchNode> ended,
                             int produced, int branchLimit) {
        String potionId = state.potions.get(slot);
        io.github.youqing.sts1solver.mirror.Potions.ChoiceMode mode =
                io.github.youqing.sts1solver.mirror.Potions.choiceModeOf(potionId);
        boolean targeted = io.github.youqing.sts1solver.mirror.Potions.needsTarget(potionId);
        int made = 0;

        if (mode == io.github.youqing.sts1solver.mirror.Potions.ChoiceMode.MULTI) {
            for (int[] choice : multiChoiceBranches(state, potionId, branchLimit)) {
                if (branchLimit > 0 && produced + made >= branchLimit) {
                    break;
                }
                if (route(potionMultiNode(state, slot, choice, node), plays, ended)) {
                    made++;
                }
            }
            return made;
        }

        int choiceCount;
        if (mode == io.github.youqing.sts1solver.mirror.Potions.ChoiceMode.SINGLE) {
            choiceCount = io.github.youqing.sts1solver.mirror.Potions.choiceFromDiscard(potionId)
                    ? state.discard.size() : 3;
        } else {
            choiceCount = 1;
        }
        for (int c = 0; c < choiceCount; c++) {
            if (targeted) {
                for (int t = 0; t < state.monsters.size(); t++) {
                    if (branchLimit > 0 && produced + made >= branchLimit) {
                        break;
                    }
                    if (state.monsters.get(t).hp <= 0) {
                        continue;
                    }
                    if (route(potionNode(state, slot, t, c, node), plays, ended)) {
                        made++;
                    }
                }
            } else {
                if (branchLimit > 0 && produced + made >= branchLimit) {
                    break;
                }
                if (route(potionNode(state, slot, -1, c, node), plays, ended)) {
                    made++;
                }
            }
        }
        return made;
    }

    /**
     * 多选分支的最小枚举：空集 + 每个单张 + 全选，受 {@code branchLimit} 约束。
     *
     * <p>蓝本 {@code BuildChoices} 是「每种选几张保留一个代表 + 按优先级补足到上限」，
     * 这里取它的**基数代表**那一半（空集/单张/全选），优先级补足那半留待
     * 需要更好的多选质量时再加（现在分支上限小，基数代表已够）。
     *
     * <p>下标按手牌顺序；「先弃哪张」不给估值（避免自创量化），按费用升序取
     * （低费牌先弃）—— 客观量，可复现。
     */
    private List<int[]> multiChoiceBranches(SimState state, String potionId, int branchLimit) {
        java.util.List<Integer> order = new java.util.ArrayList<Integer>();
        for (int i = 0; i < state.hand.size(); i++) {
            order.add(i);
        }
        java.util.Collections.sort(order, new java.util.Comparator<Integer>() {
            @Override
            public int compare(Integer a, Integer b) {
                int c = Integer.compare(state.hand.get(a).effectiveCost(),
                        state.hand.get(b).effectiveCost());
                return c != 0 ? c : Integer.compare(a, b);
            }
        });
        List<int[]> out = new ArrayList<int[]>();
        out.add(new int[0]);                                   // 一张不选（canPickZero）
        for (int i = 0; i < order.size(); i++) {
            out.add(new int[]{order.get(i)});                  // 每个单张
        }
        if (state.hand.size() > 1) {
            int[] all = new int[order.size()];
            for (int i = 0; i < order.size(); i++) {
                all[i] = order.get(i);
            }
            out.add(all);                                      // 全选
        }
        return out;
    }

    /** 按「这一步之后还在不在本回合」把子节点放进不同的桶。 */
    private boolean route(SearchNode child, List<SearchNode> plays, List<SearchNode> ended) {
        if (child == null) {
            return false;
        }
        if (child.terminal()) {
            ended.add(child);       // 打死了 = 这条路线结束了，和结束回合同一档
        } else if (child.state.turnForcedEnd) {
            // Time Warp（TimeEater）：打出第 12 张牌时**全场强制结束这个回合**。
            // 这一手打完就不能再出牌了，所以直接推过回合末（和「主动结束回合」同一档），
            // 计划里仍然保留「打这张牌」这一步（它是 endTurnOf 的父节点）。
            ended.add(endTurnOf(child));
        } else {
            plays.add(child);
        }
        return true;
    }

    /** 兼容旧调用（{@link #rootChildren}）：两个桶合成一个。 */
    private void expand(SearchNode node, List<SearchNode> out) {
        expandTurnLayer(node, out, out, cardBranchLimit);
    }

    List<SearchNode> prune(List<SearchNode> candidates, int width) {
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
        // 剪枝排序 = 蓝本 CompareBeamRankOrder（BeamRetentionPolicy.cs:20-41）：
        //   ① beamRankScore 降序（含手头资源，只决定谁能活到下一层）
        //   ② actionCount 升序（同样好时，少走一步更好）
        //   ③ offensiveProgressValue 降序（已兑现的攻击推进量，2026-10-23 补齐）
        // ①相同才看 ②，所以合成一个 Comparator 而不是两次 sort。
        unique.sort(new java.util.Comparator<SearchNode>() {
            @Override
            public int compare(SearchNode a, SearchNode b) {
                // 基础分成员（蓝本 BaseScoreOnly）：中途排序只看 score，不看带资源项的 rank
                int c = baseScoreOnly
                        ? Long.compare(b.score, a.score)
                        : Long.compare(b.rank, a.rank);
                if (c != 0) {
                    return c;
                }
                c = Integer.compare(a.depth, b.depth);
                return c != 0 ? c : Integer.compare(b.offensiveProgress, a.offensiveProgress);
            }
        });
        // 蓝本 DiversifyOrdinaryBeamBoundary（BeamRetentionPolicy.cs:47-165，调用点 :1704）：
        // 截断之后，在「主键（rank+actionCount）并列」的边界段里按药水数分桶、
        // 按攻击推进量分组轮转占位 —— 防止 beam 尾部被同质路线占满（多样性塌掉搜索会卡死）。
        // required（策略强制保留）与 finalQualityFirst 我们还没有，传空集 / false。
        // 次段成员（蓝本 SecondRankBand）：普通席位取「第 W+1 至 2W 位」，
        // 专挑那些在第一轮裁掉、但其实还不错的中段路线。
        List<SearchNode> selected;
        if (unique.size() <= width) {
            selected = unique;
        } else if (secondRankBand && unique.size() > 2 * width) {
            selected = new ArrayList<SearchNode>(unique.subList(width, 2 * width));
        } else if (secondRankBand) {
            selected = new ArrayList<SearchNode>(unique.subList(width, unique.size()));
        } else {
            selected = new ArrayList<SearchNode>(unique.subList(0, width));
        }
        diversifyOrdinaryBeamBoundary(unique, selected,
                java.util.Collections.<SearchNode>emptySet(), false);
        return selected;
    }

    /**
     * 蓝本 {@code DiversifyOrdinaryBeamBoundary}（{@code BeamRetentionPolicy.cs:47-165}）。
     *
     * <p>前提：{@code rankedPool} 是全量按分数排好序的候选（截断前），{@code selected} 是
     * 截断后的 beam。在 rankedPool 的**尾部**找最后一个已入选的普通席位（boundary），
     * 以它的主键（rank + actionCount）向两边扩出并列段 [start, end)，然后：
     *
     * <ol>
     *   <li>段内候选按 {@link SearchNode#potionsUsed} 分桶；</li>
     *   <li>每桶若「部分入选」（1 &lt; 入选数 &lt; 桶大小），按 {@link SearchNode#offensiveProgress}
     *       降序分组、**轮转**（round-robin）取代表回填入选席位 ——
     *       让不同攻击推进状态的路线都能在 beam 尾部占一席，而不是被单一推进值垄断；</li>
     *   <li>段内出现「已打赢」的节点 → 整体放弃（终局路线完全交给最终排序策略管，蓝本 :106-108）。</li>
     * </ol>
     *
     * <p>刻意不搬（依赖的量我们还没有，见 {@code mechanics-reference} 对应小节）：
     * {@code OrderOrdinaryBeamTacticalCohorts} 的战术重排（需要 ZeroCostPlayableCount /
     * ReachableHandValue / HandCount 三个快照量）与 {@code includeSingleProgressGroup}
     * 实验开关（BaseScoreOnly 专用）。蓝本的 required 保留分支也不在本函数职责内。
     */
    static void diversifyOrdinaryBeamBoundary(List<SearchNode> rankedPool,
                                                      List<SearchNode> selected,
                                                      java.util.Set<SearchNode> required,
                                                      boolean finalQualityFirst) {
        if (finalQualityFirst || selected.size() >= rankedPool.size()) {
            return;
        }
        java.util.Set<SearchNode> requiredSet =
                java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<SearchNode, Boolean>());
        requiredSet.addAll(required);
        // 按**引用**定位入选席位（蓝本 ReferenceEqualityComparer）
        Map<SearchNode, Integer> selectedPositions =
                new java.util.IdentityHashMap<SearchNode, Integer>();
        for (int index = 0; index < selected.size(); index++) {
            selectedPositions.put(selected.get(index), index);
        }

        // 定位「原排序里最后一个普通幸存者」，不是最后一个入选槽位（蓝本 :62-72 注释）
        int boundary = rankedPool.size() - 1;
        while (boundary >= 0
                && (requiredSet.contains(rankedPool.get(boundary))
                || !selectedPositions.containsKey(rankedPool.get(boundary)))) {
            boundary--;
        }
        if (boundary < 0) {
            return;
        }
        SearchNode boundaryValue = rankedPool.get(boundary);
        // 主键 = beamRankScore + actionCount（蓝本 SamePrimary，:74-78）
        java.util.function.BiPredicate<SearchNode, SearchNode> samePrimary = (a, b) ->
                a.rank == b.rank && a.depth == b.depth;

        int end = boundary + 1;
        while (end < rankedPool.size() && samePrimary.test(rankedPool.get(end), boundaryValue)) {
            end++;
        }
        if (end == boundary + 1) {
            return;     // 边界右侧没有同主键候选：没有「被截掉的同分路线」需要轮转
        }
        int start = boundary;
        while (start > 0 && samePrimary.test(rankedPool.get(start - 1), boundaryValue)) {
            start--;
        }

        // 段内按药水使用数分桶（蓝本 :101-117）
        Map<Integer, List<SearchNode>> byPotionCount = new LinkedHashMap<Integer, List<SearchNode>>();
        for (int index = start; index < end; index++) {
            SearchNode candidate = rankedPool.get(index);
            // 蓝本 describe 的 Victory = IsCompleteVictory（actionCount>0 && 怪全灭
            // && !playerDead && 投影血>0）；段内出现 → 整体放弃（蓝本 :106-108）
            if (candidate.depth > 0
                    && SearchNode.noAliveMonster(candidate.state)
                    && candidate.state.hp > 0
                    && Score.projectedHpOf(candidate.state) > 0) {
                return;
            }
            if (requiredSet.contains(candidate)) {
                continue;
            }
            List<SearchNode> bucket = byPotionCount.get(candidate.potionsUsed);
            if (bucket == null) {
                bucket = new ArrayList<SearchNode>();
                byPotionCount.put(candidate.potionsUsed, bucket);
            }
            bucket.add(candidate);
        }

        for (List<SearchNode> bucket : byPotionCount.values()) {
            List<Integer> slots = new ArrayList<Integer>();
            for (SearchNode candidate : bucket) {
                Integer slot = selectedPositions.get(candidate);
                if (slot != null) {
                    slots.add(slot);
                }
            }
            // 只有「部分入选」的桶才需要再平衡（全没进 / 全进了都不用动）
            if (slots.size() <= 1 || slots.size() == bucket.size()) {
                continue;
            }

            // 按攻击推进量降序分组（蓝本 :130-134）；单一分组直接跳过
            Map<Integer, List<SearchNode>> progressGroups = new LinkedHashMap<Integer, List<SearchNode>>();
            for (SearchNode candidate : bucket) {
                List<SearchNode> group = progressGroups.get(candidate.offensiveProgress);
                if (group == null) {
                    group = new ArrayList<SearchNode>();
                    progressGroups.put(candidate.offensiveProgress, group);
                }
                group.add(candidate);
            }
            if (progressGroups.size() <= 1) {
                continue;
            }
            List<List<SearchNode>> groups = new ArrayList<List<SearchNode>>(progressGroups.values());
            groups.sort(new java.util.Comparator<List<SearchNode>>() {
                @Override
                public int compare(List<SearchNode> a, List<SearchNode> b) {
                    return Integer.compare(b.get(0).offensiveProgress, a.get(0).offensiveProgress);
                }
            });

            // 轮转取代表回填席位（蓝本 :151-163）：进度不同的路线轮流占位
            List<SearchNode> replacements = new ArrayList<SearchNode>(slots.size());
            for (int round = 0; replacements.size() < slots.size(); round++) {
                for (List<SearchNode> group : groups) {
                    if (round < group.size()) {
                        replacements.add(group.get(round));
                    }
                    if (replacements.size() == slots.size()) {
                        break;
                    }
                }
            }
            for (int index = 0; index < slots.size(); index++) {
                selected.set(slots.get(index), replacements.get(index));
            }
        }
    }

    // ================================================================================
    // 药水战略成本对账 —— 蓝本 PotionUsePolicy.StrategicHpCost + FinalPlanOrdering 的
    // `PolicyHpDeficit` / `HealthResourceCost` 那两条轴的最小可搬切片
    // ================================================================================

    /** 跑一趟「不喝药」基线至少要剩这么多毫秒（蓝本药水救援也是这个量级）。 */
    static final int POTION_GATE_MIN_REMAINING_MILLIS = 5_000;

    /** 这条路线里所有「显式用药」的战略成本合计（蓝本 {@code ExplicitPotionStrategicCost}）。 */
    int potionStrategicCost(SearchNode node) {
        int total = 0;
        for (SearchNode cursor = node; cursor != null && cursor.action != null;
             cursor = cursor.parent) {
            if (cursor.action.kind == PlanAction.Kind.USE_POTION && cursor.cardKey != null) {
                total += io.github.youqing.sts1solver.mirror.PotionValuation
                        .strategicHpCost(cursor.cardKey);
            }
        }
        return total;
    }

    /**
     * 喝药必须**买回成本**，否则退回不喝药的那条路线。
     *
     * <p>蓝本 {@code PotionUsePolicy}：一条路线里每瓶药水都有 {@code StrategicHpCost}
     * （9 / 14 / 18 血，见 {@code PotionValuation}），整条路线要满足
     * {@code Σ cost ≤ PotionHpSaved = (不喝药的赤字 - 这条的赤字)} 才算合格；
     * 达不到就换成不喝药的结果（{@code potionBranchesRejected}）。
     *
     * <p>为什么要这一层：搜索自己能喝药之后，会拿药水去换「早一回合结束」这种不省血的好处
     * （最终排序里 {@code CombatEndedTurn} 排在敌方血之前）。药水是稀缺资源，
     * 蓝本用这道闸把「用掉了但没买到血」的路线挡在外面。
     *
     * <p>我们没搬的部分：{@code SolverPotionPolicy} 的档位（Smart / RequireAtLeastOne / Disabled）、
     * 强制用药指令、琥珀（Ambergris）限制、{@code PotionRewardOutlook}（腾出槽位能换一份奖励）。
     * 这几个都依赖 UI 侧栏额度输入，记在 {@code blueprint-parity} 药水那行。
     */
    /**
     * 最终答案的策略复核（蓝本 {@code CombatBeamSolver.FinalPlanOrdering.cs:246-282}）。
     *
     * <p>Force **不是排序加分**：路线里没有那一步就是不合格，哪怕它分更高。
     * 不合格就抛 {@link PotionPolicyUnsatisfiedException}，文案与蓝本三选一一致。
     *
     * <p>⚠️ 不做「把这瓶钉在第一步再搜一趟」的补搜：蓝本没有这条通道，
     * 它在**统一候选池**里做资格过滤（`policyEligibleCandidates`），池子空了才抛。
     * 我们这边的对应物是展开阶段就按 {@code allowsExplicitUse} 拦掉不合规的用药分支
     * （{@code expandPotionActions}），所以走到这里仍不满足 = 这条约束在当前搜索空间里无解。
     */
    private SearchNode verifyPotionStrategy(SearchNode selected) {
        if (potionStrategy == null) {
            return selected;
        }
        java.util.List<RouteStep> steps = selected == null
                ? java.util.Collections.<RouteStep>emptyList() : stepsOf(selected);
        PotionStrategySnapshot.FinalRouteVerdict verdict = potionStrategy.evaluateFinalRoute(steps);
        if (verdict.satisfied) {
            lastPolicySatisfied = true;
            lastPolicyViolation = "";
            return selected;
        }
        lastPolicySatisfied = false;
        lastPolicyViolation = verdict.describe();
        Trace.warn("POTION_POLICY unsatisfied: " + lastPolicyViolation);
        throw new PotionPolicyUnsatisfiedException(describePolicyFailure());
    }

    /** 蓝本 {@code FinalPlanOrdering.cs:274-282} 的三段文案。 */
    private String describePolicyFailure() {
        if (potionStrategy.hasForcedDirectives()) {
            return "指定药水必须使用，但搜索没有找到可执行路线："
                    + potionStrategy.describeForcedUses() + "。";
        }
        if (potionStrategy.globalPolicy() == SolverPotionPolicy.REQUIRE_AT_LEAST_ONE) {
            return "本场药水策略要求至少使用一瓶，但搜索没有找到可执行的用药路线。";
        }
        return "本场药水策略没有可执行路线。";
    }

    private SearchNode gatePotionSpending(SimState root, SearchNode selected, long deadline) {
        if (!potionCostGate || selected == null) {
            return selected;
        }
        int cost = potionStrategicCost(selected);
        lastPotionStrategicCost = cost;
        if (cost <= 0) {
            return selected;                 // 一滴药没喝，不用对账（省掉一整趟基线搜索）
        }
        long remaining = remainingMillis(deadline);
        if (remaining != Long.MAX_VALUE && remaining <= POTION_GATE_MIN_REMAINING_MILLIS) {
            return selected;                 // 没预算跑基线就别硬跑，保持原答案
        }
        // 蓝本 CombatSearchCoordinator.cs:252：基线的策略是 ForForcedBaseline ——
        // 强制用药的那几瓶**仍然可以喝**，只排除可选药水。这样比出来的差值衡量的才是
        // 「可选药水买到了多少血」，而不是把用户明确要求的那几瓶也算进去。
        BeamSolver baseline = new BeamSolver(energyPerTurn, handSize, beamWidth, depth,
                nodeLimit, cardBranchLimit)
                // 没有策略（对照实验）时用「全 Smart 的强制基线」：它同样只放行 Force，
                // 也就是「一瓶都不许喝」—— 基线的语义不能因为上层没传策略就变成「随便喝」。
                .withPotionStrategy(potionStrategy == null
                        ? PotionStrategySnapshot.defaults().forForcedBaseline()
                        : potionStrategy.forForcedBaseline());
        long budget = remaining == Long.MAX_VALUE ? Long.MAX_VALUE : remaining - 1_000;
        long subDeadline = budget == Long.MAX_VALUE
                ? Long.MAX_VALUE
                : System.nanoTime() + budget * 1_000_000L;
        SearchNode free = baseline.searchToDepth(root, Math.max(1, depth), beamWidth,
                cardBranchLimit, nodeLimit, subDeadline);
        lastNodes += baseline.lastNodes;
        if (free == null || baseline.hitNodeLimit || baseline.lastPassCutShort) {
            return selected;                 // 基线没跑完，不算成绩（蓝本同口径）
        }
        // 蓝本 PotionUsePolicy.IsEligible：RequireAtLeastOne 下「一瓶都没喝」的基线不合格。
        // 拿一条不合格的基线去替换掉本来满足策略的路线，会让最终答案反而违反策略 ——
        // 那种情况下基线根本不能当「不喝药的替代品」。
        if (potionStrategy != null
                && potionStrategy.globalPolicy() == SolverPotionPolicy.REQUIRE_AT_LEAST_ONE
                && free.potionsUsed == 0) {
            return selected;
        }
        if (isCompleteVictory(free) != isCompleteVictory(selected)) {
            // 胜负不同就没法比赤字：不喝药会输、喝药能赢 → 药水买到的是「这场胜利」，当然留。
            if (isCompleteVictory(selected)) {
                lastPotionHpSaved = Integer.MAX_VALUE;      // 哨兵：整场胜利，不是血量算术
            }
            return selected;
        }
        int saved = strategicHpDeficit(free) - strategicHpDeficit(selected);
        lastPotionHpSaved = saved;
        boolean rejected = saved < cost;
        Trace.info("POTION_GATE cost=" + cost + " hp_saved=" + saved
                + " potions=" + selected.potionsUsed + " rejected=" + rejected);
        if (rejected) {
            lastPotionSpendRejected = true;
            return free;
        }
        return selected;
    }

    // ================================================================================
    // 0 费开局补搜 —— 蓝本 CombatSearchCoordinator.PostSearch.cs:352-392 RunZeroCostOpeningRescue
    //   + CombatBeamSolver.Expansion.Opening.cs:1028-1064 BuildOpeningNoCostPrefixes
    //   + CombatBeamSolver.Phases.cs:2296-2400 ApplyFixedPrefix
    // ================================================================================

    /**
     * 补搜的预算与上限，逐值照抄蓝本 {@code PostSearch.cs:363-371}：
     * {@code minimumRemainingMilliseconds: 5_000} / {@code maximumNodes: 40_000} /
     * {@code maximumMilliseconds: 12_000} / {@code reserveMilliseconds: 2_000} /
     * {@code ++attempts >= 8}。
     */
    public static final int ZERO_COST_MAX_NODES = 40_000;
    public static final int ZERO_COST_MAX_MILLIS = 12_000;
    public static final int ZERO_COST_RESERVE_MILLIS = 2_000;
    public static final int ZERO_COST_MIN_REMAINING_MILLIS = 5_000;
    public static final int ZERO_COST_MAX_ATTEMPTS = 8;

    /** 蓝本 {@code BuildOpeningNoCostPrefixes}：BFS 深度 4、每层留 24、最后取 12 条。 */
    public static final int NO_COST_PREFIX_MAX_DEPTH = 4;
    public static final int NO_COST_PREFIX_FRONTIER_LIMIT = 24;
    public static final int NO_COST_PREFIX_LIMIT = 12;

    /**
     * 蓝本 {@code RunZeroCostOpeningRescue} 的触发条件（{@code PostSearch.cs:355-362}），逐条对：
     *
     * <pre>
     * ResultScope == SearchCompletion        → !hitNodeLimit &amp;&amp; !lastPassCutShort（没被预算截断）
     * IsCompleteVictory(selected)            → 真打赢了
     * ExplicitPotionCount == 0               → 这条路线一瓶药都没喝
     * ProjectedBattleHpLost &gt; 0              → 还是掉了血（一点没掉就不用救了）
     * BestNode.Actions.First().Kind == EndTurn → **最优路线的第一步就是「结束回合」**
     * </pre>
     *
     * <p>蓝本还有两个前提我们没有对应物：{@code PotionPolicy == Smart} 与
     * {@code !PotionStrategy.HasForcedDirectives} —— 那是它那套药水策略族的状态，
     * STS1 侧还没有药水策略（记在 {@code blueprint-parity} 药水估值那一行）。
     * 这里用「这条路线的药水用量」代替「策略档位」，是当前能落到实处的等价判定。
     */
    boolean shouldRescueZeroCostOpening(SearchNode selected) {
        if (selected == null || hitNodeLimit || lastPassCutShort) {
            return false;
        }
        if (!isCompleteVictory(selected)) {
            return false;
        }
        if (selected.potionsUsed != 0) {
            return false;
        }
        if (rootHp - selected.state.hp <= 0) {
            return false;
        }
        // 蓝本还有两条前提我们以前没有对应物，现在有了：
        // PotionPolicy == Smart 且 !HasForcedDirectives —— 策略不是 Smart、或有强制用药时
        // 不做免费牌补搜（补搜是「这回合什么都不做」的补救，与用药策略冲突）。
        if (potionStrategy != null) {
            if (potionStrategy.globalPolicy() != SolverPotionPolicy.SMART) {
                return false;
            }
            if (potionStrategy.hasForcedDirectives()) {
                return false;
            }
        }
        PlanAction first = selected.firstAction();
        return first != null && first.kind == PlanAction.Kind.END_TURN;
    }

    /**
     * 蓝本 {@code RunZeroCostOpeningRescue}（{@code PostSearch.cs:366-389}）：
     * 枚举免费出牌前缀，每条各重搜一轮（不喝药），**严格更好才采纳**，最多 8 趟。
     *
     * <p>包内可见是为了让测试能直接喂一个「假的劣解」验证采纳逻辑，不必构造一个
     * 真会让主搜索误判的局面（那种局面依赖 beam 剪枝的具体宽度，不可复现）。
     */
    SearchNode zeroCostOpeningRescue(SimState root, SearchNode selected, long deadline) {
        if (!shouldRescueZeroCostOpening(selected)) {
            return selected;
        }
        java.util.List<java.util.List<PlanAction>> prefixes = openingNoCostPrefixes(root);
        lastZeroCostPrefixes = prefixes.size();
        if (prefixes.isEmpty()) {
            return selected;        // 这回合根本没有免费牌可打，救不了
        }
        int attempts = 0;
        for (java.util.List<PlanAction> prefix : prefixes) {
            long remaining = remainingMillis(deadline);
            if (remaining != Long.MAX_VALUE && remaining <= ZERO_COST_MIN_REMAINING_MILLIS) {
                break;              // 蓝本 ledger.RemainingRequestMillisecondsLong > 5_000 那道门
            }
            long budget = remaining == Long.MAX_VALUE
                    ? Long.MAX_VALUE
                    : Math.min(ZERO_COST_MAX_MILLIS, remaining - ZERO_COST_RESERVE_MILLIS);
            long subDeadline = budget == Long.MAX_VALUE
                    ? Long.MAX_VALUE
                    : System.nanoTime() + budget * 1_000_000L;

            // 蓝本 OpeningNoCostContinuationSource：potionPolicyOverride Disabled + 最多 0 瓶
            BeamSolver sub = new BeamSolver(energyPerTurn, handSize, beamWidth, depth,
                    ZERO_COST_MAX_NODES, cardBranchLimit)
                    .withFixedPrefix(prefix)
                    .withMaxPotionUses(0)
                    .withPotionStrategy(potionStrategy);
            SearchNode candidate = sub.searchToDepth(root, Math.max(1, depth), beamWidth,
                    cardBranchLimit, ZERO_COST_MAX_NODES, subDeadline);
            lastNodes += sub.lastNodes;
            attempts++;
            // 蓝本要求 candidate.ResultScope == SearchCompletion 才算数（被截断的搜索不算成绩）
            boolean complete = candidate != null && !sub.hitNodeLimit && !sub.lastPassCutShort;
            // 平手也采纳：这条补搜存在的意义就是「这回合什么都不做看着像死了」。
            // 0 费牌打出去最坏是没效果（威吓撞上人工制品），但消耗掉能瘦牌组、
            // 能推动遗物计数；而「结束回合」在平手时是纯粹的空过。
            // 蓝本的严格改善判据会把这种平手判给「结束回合」，真机 2026-10-30 20:35:57
            // 就这么空过了一回合（3 能量、手里只有眩晕和威吓）。
            boolean tie = complete && selected != null && !better(selected, candidate);
            boolean improved = complete && (better(candidate, selected) || tie);
            Trace.info("ZERO_COST_OPENING prefix=" + describePrefix(prefix)
                    + " hp_lost=" + (candidate == null ? -1 : rootHp - candidate.state.hp)
                    + " nodes=" + sub.lastNodes + " selected=" + improved
                    + (tie && !better(candidate, selected) ? " (tie)" : ""));
            if (improved) {
                selected = candidate;
                lastZeroCostImproved = true;
            }
            // 蓝本 `if (selected.ProjectedBattleHpLost == 0 || ++attempts >= 8) break;`
            if (rootHp - selected.state.hp <= 0 || attempts >= ZERO_COST_MAX_ATTEMPTS) {
                break;
            }
        }
        lastZeroCostAttempts = attempts;
        return selected;
    }

    /**
     * 蓝本 {@code BuildOpeningNoCostPrefixes}（{@code Expansion.Opening.cs:1028-1064}）逐行翻译：
     *
     * <ol>
     *   <li>从根开始 BFS，最多 4 层；</li>
     *   <li>每层只留「出牌 + 还在本回合 + 能量没被打掉」的子节点（= 免费牌）；</li>
     *   <li>按状态指纹去重（蓝本 {@code DistinctBy(StateKey)}，保留**先出现**的那条），
     *       再按基础分降序取前 24 条当前沿；</li>
     *   <li>所有层的入选节点汇总后，按「动作多优先、分高其次」取 12 条，各自回溯成完整前缀。</li>
     * </ol>
     *
     * <p>只用到了「动作种类 / 回合 / 能量 / 状态指纹 / 基础分 / 动作数」六个量 —— 全是我们已有的，
     * 所以这条能整条搬（对照表 §4 第 4 项里可搬的那一半）。
     */
    public java.util.List<java.util.List<PlanAction>> openingNoCostPrefixes(SimState root) {
        bindRoot(root);
        reportedRejects.clear();
        SearchNode seed = nodeOf(root.copy(), null, null, 0, null);
        java.util.List<SearchNode> frontier = new ArrayList<SearchNode>();
        frontier.add(seed);
        java.util.List<SearchNode> candidates = new ArrayList<SearchNode>();
        for (int layer = 0; layer < NO_COST_PREFIX_MAX_DEPTH && !frontier.isEmpty(); layer++) {
            java.util.List<SearchNode> next = new ArrayList<SearchNode>();
            for (SearchNode parent : frontier) {
                if (parent.terminal()) {
                    continue;           // 蓝本 ExpandOpeningSeed 的守卫
                }
                java.util.List<SearchNode> plays = new ArrayList<SearchNode>();
                java.util.List<SearchNode> ended = new ArrayList<SearchNode>();
                expandTurnLayer(parent, plays, ended, cardBranchLimit);
                collectNoCostPlays(plays, parent, next);
                collectNoCostPlays(ended, parent, next);   // 打死怪的免费牌也算（蓝本不过滤终局）
            }
            frontier = noCostFrontier(next);
            candidates.addAll(frontier);
        }
        candidates.sort(new java.util.Comparator<SearchNode>() {
            @Override
            public int compare(SearchNode a, SearchNode b) {
                int c = Integer.compare(b.depth, a.depth);      // 动作多的优先
                return c != 0 ? c : Long.compare(b.score, a.score);
            }
        });
        int limit = Math.min(NO_COST_PREFIX_LIMIT, candidates.size());
        java.util.List<java.util.List<PlanAction>> prefixes =
                new ArrayList<java.util.List<PlanAction>>(limit);
        for (int i = 0; i < limit; i++) {
            prefixes.add(routeOf(candidates.get(i)));
        }
        return prefixes;
    }

    /** 只收「免费出牌」：{@code PlayCard && 还在本回合 && 能量没变}（{@code Expansion.Opening.cs:1046-1048}）。 */
    private static void collectNoCostPlays(java.util.List<SearchNode> children, SearchNode parent,
                                           java.util.List<SearchNode> out) {
        for (SearchNode child : children) {
            if (child == null || child.action == null
                    || child.action.kind != PlanAction.Kind.PLAY_CARD) {
                continue;
            }
            if (child.state.turn != parent.state.turn) {
                continue;
            }
            if (child.state.energy != parent.state.energy) {
                continue;
            }
            out.add(child);
        }
    }

    /** 蓝本 {@code DistinctBy(StateKey).OrderByDescending(Score).Take(24)}。 */
    private static java.util.List<SearchNode> noCostFrontier(java.util.List<SearchNode> nodes) {
        Map<String, SearchNode> byState = new LinkedHashMap<String, SearchNode>();
        for (SearchNode node : nodes) {
            String key = node.state.fingerprint();
            if (!byState.containsKey(key)) {
                byState.put(key, node);         // DistinctBy 保留先出现的那条
            }
        }
        java.util.List<SearchNode> unique = new ArrayList<SearchNode>(byState.values());
        unique.sort(new java.util.Comparator<SearchNode>() {
            @Override
            public int compare(SearchNode a, SearchNode b) {
                return Long.compare(b.score, a.score);
            }
        });
        return unique.size() <= NO_COST_PREFIX_FRONTIER_LIMIT
                ? unique
                : new ArrayList<SearchNode>(unique.subList(0, NO_COST_PREFIX_FRONTIER_LIMIT));
    }

    /**
     * 蓝本 {@code ApplyFixedPrefix}（{@code Phases.cs:2296-2400}）：
     * 从 {@code seed} 开始按顺序回放前缀，返回走完的节点；任何一步不合法 → 返回 null（整条作废）。
     *
     * <p>与蓝本一致的收尾：前缀中途把战斗打完了就 {@code break}（剩下的动作不可达）。
     * 蓝本的 {@code resetFixedPrefixSchedulingBaseline} 会重置「持续增益 / 力量压制 / 虚弱回合 /
     * 保留攻击价值」四个排程基线 —— 这四个量我们都没建模，所以无对应代码，不是漏搬。
     */
    private SearchNode applyFixedPrefix(SearchNode seed, java.util.List<PlanAction> prefix) {
        SearchNode node = seed;
        for (PlanAction action : prefix) {
            if (node.terminal()) {
                break;
            }
            SearchNode next = replayPrefixAction(node, action);
            if (next == null) {
                return null;
            }
            node = next;
        }
        return node;
    }

    /** 回放前缀里的一步；打不出/用不了 → null（蓝本 {@code CanApplyFixedPrefixAction} 为假）。 */
    private SearchNode replayPrefixAction(SearchNode node, PlanAction action) {
        if (action == null) {
            return null;
        }
        SimState state = node.state;
        SimState copy;
        String cardKey = null;
        try {
            copy = state.copy();
            if (action.kind == PlanAction.Kind.END_TURN) {
                CombatFlow.endTurn(copy, energyPerTurn, handSize);
            } else if (action.kind == PlanAction.Kind.PLAY_CARD) {
                if (action.cardIndex < 0 || action.cardIndex >= state.hand.size()) {
                    return null;
                }
                cardKey = state.hand.get(action.cardIndex).key();
                CardPlayer.play(copy, action.cardIndex, action.targetIndex, action.choiceIndex);
            } else if (action.kind == PlanAction.Kind.USE_POTION) {
                if (action.cardIndex < 0 || action.cardIndex >= state.potions.size()) {
                    return null;
                }
                String potionId = state.potions.get(action.cardIndex);
                if (potionId == null
                        || !io.github.youqing.sts1solver.mirror.Potions.canUse(potionId)) {
                    return null;
                }
                boolean used = action.choiceIndices != null
                        ? io.github.youqing.sts1solver.mirror.Potions.useMulti(copy, potionId,
                                action.choiceIndices)
                        : io.github.youqing.sts1solver.mirror.Potions.use(copy, potionId,
                                action.targetIndex, action.choiceIndex);
                if (!used) {
                    return null;
                }
                copy.potions.set(action.cardIndex, null);
                cardKey = potionId;
            } else {
                return null;
            }
        } catch (Throwable rejected) {
            return null;                    // 打不出就是打不出，不换一张牌顶上
        }
        return nodeOf(copy, action, node, node.depth + 1, cardKey);
    }

    /** 日志用：{@code PLAY[0]->1,PLAY[1]}。 */
    private static String describePrefix(java.util.List<PlanAction> prefix) {
        StringBuilder sb = new StringBuilder();
        for (PlanAction action : prefix) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(action);
        }
        return sb.length() == 0 ? "-" : sb.toString();
    }

    /** 到 deadline 还剩多少毫秒；{@code Long.MAX_VALUE} 截止 = 不限。 */
    private static long remainingMillis(long deadline) {
        return deadline == Long.MAX_VALUE
                ? Long.MAX_VALUE
                : Math.max(0, (deadline - System.nanoTime()) / 1_000_000L);
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
