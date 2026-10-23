package io.github.youqing.sts1solver.sim;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 怪物意图的「观测学习表」。
 *
 * <p>为什么不直接复刻怪物 AI：那需要为每个怪物重写一套行动状态机（蓝本为此单独开了一整层
 * {@code Engine/InCombat/Mirrors}），成本极高且容易写错。这里换一条更稳的路——
 * **先观测、后预测**：每回合把「怪物类型 + 回合序号 + 当回合意图伤害」记下来，
 * 搜索推进到下一回合时查表；查不到就退回「沿用当前意图」。
 *
 * <p>这符合「未知即拒绝」的变体：不知道就明确用保守假设，而不是编一个算法。
 * 好处是随着对局增多，表会自动变准（同一怪物同一回合的行为通常是固定的）。
 */
public final class IntentLearning {

    /** type -> (turn -> intent damage) */
    private static final ConcurrentMap<String, ConcurrentMap<Integer, Integer>> TABLE =
            new ConcurrentHashMap<String, ConcurrentMap<Integer, Integer>>();

    private static final AtomicInteger hits = new AtomicInteger();
    private static final AtomicInteger misses = new AtomicInteger();
    private static final AtomicInteger approxHits = new AtomicInteger();

    private IntentLearning() {
    }

    /** 记录一次观测（幂等：同 type+turn 只写一次）。 */
    // 主线程写、搜索线程（现在还分多条 lane）读 —— 用并发容器，读路径不加锁。
    // 以前是 synchronized 方法：并行展开后它会变成全局串行点（每个 endTurn 都要查一次）。
    public static void observe(String type, int turn, int intentDmg) {
        if (type == null || turn <= 0) {
            return;
        }
        ConcurrentMap<Integer, Integer> byTurn = TABLE.get(type);
        if (byTurn == null) {
            ConcurrentMap<Integer, Integer> created = new ConcurrentHashMap<Integer, Integer>();
            ConcurrentMap<Integer, Integer> prior = TABLE.putIfAbsent(type, created);
            byTurn = prior != null ? prior : created;
        }
        byTurn.putIfAbsent(turn, Math.max(0, intentDmg));
    }

    /**
     * 预测某怪物在指定回合的意图伤害。
     *
     * @param fallback 查不到时使用（通常是当前意图）
     */
    public static int predict(String type, int turn, int fallback) {
        ConcurrentMap<Integer, Integer> byTurn = TABLE.get(type);
        if (byTurn != null && !byTurn.isEmpty()) {
            Integer known = byTurn.get(turn);
            if (known != null) {
                hits.incrementAndGet();
                return known;
            }
            // 精确回合没观测到，用「已观测到的最大回合」作近似 ——
            // 比「沿用当前意图」更接近真实（怪物行为通常按回合循环）。
            int latestTurn = java.util.Collections.max(byTurn.keySet());
            approxHits.incrementAndGet();
            return byTurn.get(latestTurn);
        }
        misses.incrementAndGet();
        return fallback;
    }

    public static int hits() {
        return hits.get();
    }

    public static int misses() {
        return misses.get();
    }

    public static int approxHits() {
        return approxHits.get();
    }

    /** 已学习到的怪物数（用于日志）。 */
    public static int learnedMonsters() {
        return TABLE.size();
    }

    public static void resetStats() {
        hits.set(0);
        misses.set(0);
        approxHits.set(0);
    }

    /** 清空学习表（换局或调试用）。 */
    public static void clear() {
        TABLE.clear();
        resetStats();
    }
}
