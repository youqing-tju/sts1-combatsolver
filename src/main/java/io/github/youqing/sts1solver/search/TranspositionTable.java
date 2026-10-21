package io.github.youqing.sts1solver.search;

import java.util.HashMap;
import java.util.Map;

/**
 * 转置表（计划书 M2-2）：同一状态被不同顺序的动作用到达时，只算一次。
 *
 * <p>键 = 状态指纹（含回合号，见 {@code SimState.fingerprint()}）。
 * 值 = 该状态下见过的最好分数；若新来的分数不更好，这条分支就没有继续展开的价值。
 *
 * <p>会记录命中/未命中次数，用来判断「去重到底有没有用」——
 * 命中率长期接近 0 就说明这层白做了，应该换成更有效的策略。
 */
public final class TranspositionTable {

    private final Map<String, Long> bestByState = new HashMap<String, Long>();
    private int hits;
    private int misses;

    /**
     * @return true 表示这个状态此前见过、且新分数**不更好**（可以丢弃）；
     *         新分数更好时会更新并返回 false，因为更好的分支仍然值得展开
     */
    public boolean dominated(String key, long score) {
        Long previous = bestByState.get(key);
        if (previous == null) {
            misses++;
            bestByState.put(key, score);
            return false;
        }
        hits++;
        if (score > previous) {
            bestByState.put(key, score);
            return false;
        }
        return true;
    }

    public int hits() {
        return hits;
    }

    public int misses() {
        return misses;
    }

    public int size() {
        return bestByState.size();
    }

    public void clear() {
        bestByState.clear();
        hits = 0;
        misses = 0;
    }
}
