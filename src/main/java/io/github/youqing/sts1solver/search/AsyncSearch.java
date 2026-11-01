package io.github.youqing.sts1solver.search;

import io.github.youqing.sts1solver.diag.Trace;
import io.github.youqing.sts1solver.sim.SimState;

/**
 * 后台搜索线程（计划书 M2-6 / M4）。
 *
 * <p>为什么要单开一条线程：蓝本是「几百万条世界线」的量级，而我们在主线程上只能给 2 ms。
 * 影子状态是**纯数据**，不碰任何游戏对象，所以可以整段搬到后台去算（这是 D3「方法镜像」
 * 方案换来的最大红利）。
 *
 * <p>三条设计约束：
 * <ol>
 *   <li><b>同一时刻只跑一个搜索</b>：单线程队列，结果天然确定（不存在抢占顺序问题）。</li>
 *   <li><b>结果带指纹</b>：主线程拿到结果时先比对当前状态指纹，对不上就作废重算 ——
 *       否则会把「三帧之前那个局面」的最优解用在现在的局面上。</li>
 *   <li><b>线程是 daemon</b>：游戏退出时不需要收尾，也不会阻塞进程退出。</li>
 * </ol>
 */
public final class AsyncSearch {

    /** 一次搜索的结果。{@code fingerprint} 是提交时根状态的指纹。 */
    public static final class Result {
        public final String fingerprint;
        public final PlanAction action;
        /** 展开节点数 = 蓝本口径的「世界线」。 */
        public final int nodes;
        /** 生成的子状态数（诊断用）。 */
        public final int states;
        public final int depth;
        public final long millis;
        /** 走到战斗结束的路线条数（蓝本面板的「世界线」）。 */
        public final int lines;
        /** 可执行的完整路线（牌名 + 目标），执行层按它一步步走。 */
        public final java.util.List<RouteStep> steps;
        /** 这一次的节点上限（面板档位给的），配合 {@link #nodes} 看预算用了多少。 */
        public final int nodeLimit;
        /**
         * 是不是被节点上限截断的。
         *
         * <p>⚠️ {@code false} **不等于**「树被搜完了，再加预算也没用」——
         * 它也可能是「整个地平线走完了」（{@code stopReason="horizon"}，最常见），
         * 或者「真的找到了完整胜利」（{@code "victory"}）。判读请用 {@link #stopReason}。
         */
        public final boolean hitNodeLimit;
        /**
         * 这一趟为什么停：{@code victory} / {@code horizon} / {@code node_limit} / {@code time_limit}。
         *
         * <p>2026-10-31 的交接文档把 {@code !hitNodeLimit} 读成「open 表过早耗尽、剪枝太狠」，
         * 据此提出「加预算无效、要查剪枝」的结论；2026-11-01 用 123 个真机快照实测：
         * 123 次全是 {@code horizon}，节点用量 2~3% —— 搜索是把整场战斗推演完了，不是被剪死。
         */
        public final String stopReason;
        /**
         * 搜索本身**崩了**（抛异常），不是「没找到路」。
         *
         * <p>🔴 两者必须分开：空计划只说明这一步不该动，主线程会结束回合；
         * 崩了的正确反应是**别相信这次结果**、退回逐张兜底，否则就会出现
         * 「满手牌满能量空过一回合」（2026-10-30 真机实测两次）。
         */
        public final boolean failed;
        /**
         * 新颖度探索通道（蓝本 NoveltyPortfolio）的停止原因；没开就是 {@code "off"}。
         *
         * <p>判读：{@code open_exhausted} = 它把能想到的「没见过的事实组合」都走完了；
         * {@code node_limit}/{@code time_limit} = 被这一趟的帽子截断（正常，蓝本也这样）。
         */
        public final String noveltyStop;
        /** 宽度组合最后采纳的成员（{@code "135"} / {@code "90"} / {@code "135+band"}）；没开就是空串。 */
        public final String portfolioAdopted;
        /**
         * 这条路线上的稳定回合边界（跨回合续跑用）。
         *
         * <p>纯数据，可以安全地跨线程交给主线程；主线程拿它和新捕获的 live 状态
         * 做 {@code lookup}，命中就免搜索续跑。空计划 = 这条路线没有可复用的边界
         * （终局、或只够打这一回合）。
         */
        public final ContinuationPlan continuations;
        /**
         * 这条路线**预计在第几回合死**；{@code -1} = 不会死。
         *
         * <p>主线程据此判断「照这条路走这一回合就死」——那种情况**不自动部署**，
         * 而是停掉 AUTO、把操作权交还玩家（蓝本 {@code StopFullAutoOnDeathTurn}，默认开）。
         */
        public final int deathTurn;

        Result(String fingerprint, PlanAction action, int nodes, int states, int depth, long millis,
               int lines, java.util.List<RouteStep> steps, int nodeLimit, boolean hitNodeLimit) {
            this(fingerprint, action, nodes, states, depth, millis, lines, steps, nodeLimit,
                    hitNodeLimit, false, "off");
        }

        Result(String fingerprint, PlanAction action, int nodes, int states, int depth, long millis,
               int lines, java.util.List<RouteStep> steps, int nodeLimit, boolean hitNodeLimit,
               boolean failed) {
            this(fingerprint, action, nodes, states, depth, millis, lines, steps, nodeLimit,
                    hitNodeLimit, failed, "off");
        }

        Result(String fingerprint, PlanAction action, int nodes, int states, int depth, long millis,
               int lines, java.util.List<RouteStep> steps, int nodeLimit, boolean hitNodeLimit,
               String noveltyStop) {
            this(fingerprint, action, nodes, states, depth, millis, lines, steps, nodeLimit,
                    hitNodeLimit, false, noveltyStop == null ? "off" : noveltyStop,
                    "", "", ContinuationPlan.empty(), -1);
        }

        Result(String fingerprint, PlanAction action, int nodes, int states, int depth, long millis,
               int lines, java.util.List<RouteStep> steps, int nodeLimit, boolean hitNodeLimit,
               boolean failed, String noveltyStop) {
            this(fingerprint, action, nodes, states, depth, millis, lines, steps, nodeLimit,
                    hitNodeLimit, failed, noveltyStop, "", "",
                    ContinuationPlan.empty(), -1);
        }

        Result(String fingerprint, PlanAction action, int nodes, int states, int depth, long millis,
               int lines, java.util.List<RouteStep> steps, int nodeLimit, boolean hitNodeLimit,
               boolean failed, String noveltyStop, String portfolioAdopted) {
            this(fingerprint, action, nodes, states, depth, millis, lines, steps, nodeLimit,
                    hitNodeLimit, failed, noveltyStop, portfolioAdopted, "",
                    ContinuationPlan.empty(), -1);
        }

        Result(String fingerprint, PlanAction action, int nodes, int states, int depth, long millis,
               int lines, java.util.List<RouteStep> steps, int nodeLimit, boolean hitNodeLimit,
               boolean failed, String noveltyStop, String portfolioAdopted, String stopReason,
               ContinuationPlan continuations, int deathTurn) {
            this.fingerprint = fingerprint;
            this.stopReason = stopReason == null ? "" : stopReason;
            this.action = action;
            this.nodes = nodes;
            this.states = states;
            this.depth = depth;
            this.millis = millis;
            this.lines = lines;
            this.steps = steps;
            this.nodeLimit = nodeLimit;
            this.hitNodeLimit = hitNodeLimit;
            this.failed = failed;
            this.noveltyStop = noveltyStop;
            this.portfolioAdopted = portfolioAdopted == null ? "" : portfolioAdopted;
            this.continuations = continuations == null
                    ? ContinuationPlan.empty() : continuations;
            this.deathTurn = deathTurn;
        }
    }

    private static final Object LOCK = new Object();
    private static Thread worker;

    private static SimState request;
    private static String requestFingerprint;
    private static int requestEnergy;
    private static int requestHand;
    private static long requestBudget;
    private static int requestWidth;
    private static int requestDepth;
    private static int requestNodeLimit;
    private static int requestCardBranches;
    private static boolean requestNovelty;
    private static boolean requestPortfolio;
    /**
     * 这次请求用的药水策略**快照**。
     *
     * <p>后台只读它，不读 {@code SolverConfig} 的可变字段 —— 否则用户在算到一半时改策略，
     * 会得到「一半旧一半新」的约束（而且是在另一条线程上读，没有任何同步保证）。
     */
    private static PotionStrategySnapshot requestPotionStrategy;
    /**
     * 搜索代际（蓝本 {@code SolverController._nextSearchGeneration} / {@code search.Generation}）。
     *
     * <p>作用只有一个：**取消**。提交新请求或 {@link #reset()} 时递增，于是还在跑的旧请求
     * 算完之后结果不会被发布 —— 那就是蓝本 {@code CancelSearch()} 的语义。
     * 它不是「策略代际」（策略改动走保存 + 清 continuation + Manual 重搜）。
     */
    private static long generation;
    private static boolean running;

    private static volatile Result ready;

    private AsyncSearch() {
    }

    public static void start() {
        if (worker != null) {
            return;
        }
        worker = new Thread(new Runnable() {
            @Override
            public void run() {
                loop();
            }
        }, "sts1-solver-search");
        worker.setDaemon(true);
        worker.start();
        Trace.info("search worker started");
    }

    private static void loop() {
        while (true) {
            SimState root;
            long budget;
            int energy;
            int hand;
            int width;
            int depth;
            int nodeLimit;
            int cardBranches;
            boolean novelty;
            boolean portfolio;
            PotionStrategySnapshot potionStrategy;
            long requestGeneration;
            String fingerprint;
            synchronized (LOCK) {
                while (request == null) {
                    try {
                        LOCK.wait();
                    } catch (InterruptedException e) {
                        return;
                    }
                }
                root = request;
                budget = requestBudget;
                energy = requestEnergy;
                hand = requestHand;
                width = requestWidth;
                depth = requestDepth;
                nodeLimit = requestNodeLimit;
                cardBranches = requestCardBranches;
                novelty = requestNovelty;
                portfolio = requestPortfolio;
                potionStrategy = requestPotionStrategy;
                requestGeneration = generation;
                fingerprint = requestFingerprint;
                request = null;
                requestFingerprint = null;
                running = true;
            }
            Result result;
            try {
                BeamSolver solver = new BeamSolver(energy, hand, width, depth,
                        nodeLimit, cardBranches);
                if (novelty) {
                    solver.withNovelty();
                }
                if (portfolio) {
                    solver.withPortfolio();
                }
                if (potionStrategy != null) {
                    solver.withPotionStrategy(potionStrategy);
                }
                PlanAction action = solver.bestFirstAction(root, budget);
                result = new Result(fingerprint, action, solver.lastNodes, solver.lastStates,
                        solver.lastDepth, solver.lastMillis, solver.lastLeaves,
                        solver.lastSteps, nodeLimit, solver.hitNodeLimit, false,
                        solver.lastNoveltyStop, solver.lastPortfolioAdopted, solver.lastStopReason,
                        solver.lastContinuations, solver.lastDeathTurn);
            } catch (Throwable t) {
                Trace.error("background search failed", t);
                result = new Result(fingerprint, null, 0, 0, 0, 0, 0,
                        java.util.Collections.<RouteStep>emptyList(), nodeLimit, false, true);
            }
            running = false;
            synchronized (LOCK) {
                if (requestGeneration != generation) {
                    // 这次请求已经被新的提交或 reset() 取消：结果作废，不发布。
                    Trace.info("SEARCH_CANCELLED generation " + requestGeneration
                            + " != " + generation);
                    continue;
                }
                ready = result;
            }
        }
    }

    /** 提交一次搜索（重复提交会覆盖上一次未开始的请求）。 */
    public static void submit(SimState root, String fingerprint, int energy, int hand,
                              int width, int depth, long budgetMillis,
                              int nodeLimit, int cardBranches) {
        submit(root, fingerprint, energy, hand, width, depth, budgetMillis,
                nodeLimit, cardBranches, true, true);
    }

    /**
     * 同上，额外指定要不要开新颖度探索通道（蓝本 NoveltyPortfolio）。
     *
     * <p>默认开：它对 Beam 是「多跑一趟、二选一」，不会把结果改差；关掉只用于对照实验。
     */
    public static void submit(SimState root, String fingerprint, int energy, int hand,
                              int width, int depth, long budgetMillis,
                              int nodeLimit, int cardBranches, boolean novelty) {
        submit(root, fingerprint, energy, hand, width, depth, budgetMillis,
                nodeLimit, cardBranches, novelty, true);
    }

    /**
     * 同上，额外指定要不要开 Beam 宽度组合（蓝本 {@code UseBeamWidthPortfolio}，默认开）。
     *
     * <p>组合 = 五个成员共享一份预算取最好的那条，同分保留基线，所以结果不会比单趟差。
     */
    public static void submit(SimState root, String fingerprint, int energy, int hand,
                              int width, int depth, long budgetMillis,
                              int nodeLimit, int cardBranches, boolean novelty,
                              boolean portfolio) {
        submit(root, fingerprint, energy, hand, width, depth, budgetMillis,
                nodeLimit, cardBranches, novelty, portfolio, null);
    }

    /**
     * 同上，额外带上**不可变药水策略快照**。
     *
     * <p>传 {@code null} = 不加约束（对照实验/老调用点）。生产路径必须传，
     * 否则用户在面板上设的 Disabled / Force 不会影响搜索结果。
     */
    public static void submit(SimState root, String fingerprint, int energy, int hand,
                              int width, int depth, long budgetMillis,
                              int nodeLimit, int cardBranches, boolean novelty,
                              boolean portfolio, PotionStrategySnapshot potionStrategy) {
        start();
        synchronized (LOCK) {
            request = root;
            requestFingerprint = fingerprint;
            requestEnergy = energy;
            requestHand = hand;
            requestWidth = width;
            requestDepth = depth;
            requestBudget = budgetMillis;
            requestNodeLimit = nodeLimit;
            requestCardBranches = cardBranches;
            requestNovelty = novelty;
            requestPortfolio = portfolio;
            requestPotionStrategy = potionStrategy;
            generation++;
            LOCK.notifyAll();
        }
    }

    /** 取走结果（没有就返回 null）。 */
    public static Result poll() {
        Result r = ready;
        ready = null;
        return r;
    }

    /** 有请求排着或正在算。 */
    public static boolean busy() {
        synchronized (LOCK) {
            return request != null || running;
        }
    }

    /** 丢弃结果与排队中的请求（战斗切换、状态大改时调用）。 */
    public static void reset() {
        synchronized (LOCK) {
            request = null;
            requestFingerprint = null;
            requestPotionStrategy = null;
            generation++;              // 取消还在跑的那一趟（蓝本 CancelSearch）
        }
        ready = null;
    }
}
