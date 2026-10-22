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
        public final int states;
        public final int depth;
        public final long millis;

        Result(String fingerprint, PlanAction action, int states, int depth, long millis) {
            this.fingerprint = fingerprint;
            this.action = action;
            this.states = states;
            this.depth = depth;
            this.millis = millis;
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
                fingerprint = requestFingerprint;
                request = null;
                requestFingerprint = null;
                running = true;
            }
            Result result;
            try {
                BeamSolver solver = new BeamSolver(energy, hand, width, depth);
                PlanAction action = solver.bestFirstAction(root, budget);
                result = new Result(fingerprint, action, solver.lastNodes,
                        solver.lastDepth, solver.lastMillis);
            } catch (Throwable t) {
                Trace.error("background search failed", t);
                result = new Result(fingerprint, null, 0, 0, 0);
            }
            running = false;
            ready = result;
        }
    }

    /** 提交一次搜索（重复提交会覆盖上一次未开始的请求）。 */
    public static void submit(SimState root, String fingerprint, int energy, int hand,
                              int width, int depth, long budgetMillis) {
        start();
        synchronized (LOCK) {
            request = root;
            requestFingerprint = fingerprint;
            requestEnergy = energy;
            requestHand = hand;
            requestWidth = width;
            requestDepth = depth;
            requestBudget = budgetMillis;
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
        }
        ready = null;
    }
}
