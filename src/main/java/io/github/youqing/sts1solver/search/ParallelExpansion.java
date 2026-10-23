package io.github.youqing.sts1solver.search;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * 并行展开 —— 蓝本 {@code CombatBeamSolver.ParallelExpansion.cs} + {@code ExpansionExecutor.cs}。
 *
 * <p>蓝本那套 1025 行里有很大一块是它自己的负担：批次池（{@code ExpansionBatchPool}）、
 * 模拟器所有权（{@code ReplayForkSeed}）、内存压力闸、取消令牌、per-lane 自建线程。
 * 我们只搬它的**结构骨架**，也就是它注释里点明的两条不变式：
 *
 * <ol>
 *   <li><b>lane 隔离</b>：「每条 lane 有自己的 worker 求解器，候选只在各 lane 内物化」
 *       —— worker 与 coordinator 共享**不可变**的打分上下文，但可变状态各自持有；</li>
 *   <li><b>有序提交</b>：「coordinator 独占按序提交；transposition / dominance 仍按父节点原序提交」
 *       —— 展开结果按父节点下标顺序合并，所以剪枝看到的候选序列与串行**逐位相同**。</li>
 * </ol>
 *
 * <p>这两条合起来保证：**并行的搜索结果与串行完全一致**（不是「差不多」，是同一条路线、
 * 同一个分数）。验收就是这条 —— {@code ParallelExpansionTest} 对同一批局面跑串行与并行，
 * 断言 {@code lastRoute} / 分数 / 节点数逐项相等。
 *
 * <p>⚠️ 2026-10-24 实测（{@code ParallelExpansionTest#throughputLanes}）：**一个父节点一个作业
 * 太细** —— 我们的影子状态 copy 只要几十微秒，跨线程交接的开销跟它同量级，8 lane 只有
 * 1.1–1.2× 甚至更慢。所以这里按**连续切片**派活（每个 lane 领一段父节点，段内循环展开），
 * 交接次数从「父节点数」降到「lane 数」。
 *
 * <p>线程优先级压到 {@code MIN_PRIORITY + 1}：搜索跑在游戏进程里，别跟渲染抢 CPU。
 */
final class ParallelExpansion {

    /** 蓝本 {@code SolverWeights.MaximumSearchMaxDegreeOfParallelism}。 */
    static final int MAX_DEGREE = 16;

    /**
     * 蓝本 {@code ResolveDefaultSearchMaxDegreeOfParallelism}（{@code SolverWeights.cs:123-134}），
     * 注释原话「Leave at least half of a large machine's logical processors for the game」：
     * ≥16 逻辑核 → 8，≥4 → 4，≥2 → 2，否则 1。
     */
    static int defaultDegree() {
        int cpus = Runtime.getRuntime().availableProcessors();
        if (cpus >= 16) {
            return 8;
        }
        if (cpus >= 4) {
            return 4;
        }
        return cpus >= 2 ? 2 : 1;
    }

    /** 系统属性覆盖（调试 / A/B 用，等价于蓝本的 {@code SearchMaxDegreeOfParallelism} 设置）。 */
    static final String DEGREE_PROPERTY = "sts1solver.searchLanes";

    private static ExecutorService pool;

    private static synchronized ExecutorService pool(int degree) {
        if (pool == null || pool.isShutdown()) {
            pool = Executors.newFixedThreadPool(degree, new java.util.concurrent.ThreadFactory() {
                @Override
                public Thread newThread(Runnable runnable) {
                    Thread thread = new Thread(runnable, "sts1-solver-expand");
                    thread.setDaemon(true);                     // 游戏退出不阻塞
                    thread.setPriority(Thread.MIN_PRIORITY + 1); // 让渲染线程先跑
                    return thread;
                }
            });
        }
        return pool;
    }

    /** 一条 lane 的产物：它那一批父节点展开出的两支（本回合继续出牌 / 回合边界）。 */
    private static final class Bucket {
        final List<SearchNode> plays = new ArrayList<SearchNode>();
        final List<SearchNode> ended = new ArrayList<SearchNode>();
    }

    private final List<BeamSolver> lanes;

    private ParallelExpansion(List<BeamSolver> lanes) {
        this.lanes = lanes;
    }

    /** 给一个 coordinator 建 degree 条 lane（每个 lane 一个 worker）。 */
    static ParallelExpansion of(BeamSolver coordinator, int degree) {
        int count = Math.max(1, Math.min(MAX_DEGREE, degree));
        List<BeamSolver> workers = new ArrayList<BeamSolver>(count);
        for (int i = 0; i < count; i++) {
            workers.add(coordinator.expansionWorker());
        }
        return new ParallelExpansion(workers);
    }

    int degree() {
        return lanes.size();
    }

    /**
     * 展开这一层：父节点按连续切片分给各 lane，结果**按父节点原序**追加进
     * {@code playsOut} / {@code endedOut}。
     *
     * <p>顺序必须保持 —— 下游 {@code prune} 用的是稳定排序，同分时依赖候选的原始次序，
     * 次序一变结果就可能不同。
     */
    void expandAll(List<SearchNode> parents, List<SearchNode> playsOut, List<SearchNode> endedOut,
                   int branchLimit) {
        final int total = parents.size();
        final int slices = Math.min(lanes.size(), total);
        List<Callable<Bucket>> jobs = new ArrayList<Callable<Bucket>>(slices);
        for (int slice = 0; slice < slices; slice++) {
            final int from = slice * total / slices;
            final int to = (slice + 1) * total / slices;
            final BeamSolver lane = lanes.get(slice);
            jobs.add(new Callable<Bucket>() {
                @Override
                public Bucket call() {
                    Bucket bucket = new Bucket();
                    for (int index = from; index < to; index++) {
                        lane.expandInto(parents.get(index), bucket.plays, bucket.ended, branchLimit);
                    }
                    return bucket;
                }
            });
        }
        List<Future<Bucket>> done;
        try {
            done = pool(slices).invokeAll(jobs);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("parallel expansion interrupted", interrupted);
        }
        for (int index = 0; index < done.size(); index++) {     // 有序提交
            Bucket bucket = join(done.get(index));
            playsOut.addAll(bucket.plays);
            endedOut.addAll(bucket.ended);
        }
    }

    private static Bucket join(Future<Bucket> future) {
        try {
            return future.get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("parallel expansion interrupted", interrupted);
        } catch (ExecutionException failed) {
            Throwable cause = failed.getCause();
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            if (cause instanceof Error) {
                throw (Error) cause;
            }
            throw new IllegalStateException("parallel expansion failed", cause);
        }
    }
}
