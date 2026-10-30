package io.github.youqing.sts1solver.search;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import io.github.youqing.sts1solver.sim.SimCard;
import io.github.youqing.sts1solver.sim.SimMonster;
import io.github.youqing.sts1solver.sim.SimState;

/**
 * BFWS 新颖度搜索的三个零件，逐行对齐蓝本：
 * <ul>
 *   <li>{@link Options} ← {@code Search/NoveltySearchOptions.cs}；</li>
 *   <li>{@link PackedNovelty} ← {@code Search/BfwsPackedNovelty.cs}；</li>
 *   <li>{@link BoundedOpen} ← {@code Search/BfwsBoundedOpen.cs}；</li>
 *   <li>{@link EscapeBudget} ← {@code Search/BfwsEscapeBudget.cs}。</li>
 * </ul>
 *
 * <p>为什么需要它：Beam 是「每回合剪到 width」，第二章精英战只走 3 千多个展开就结束了；
 * 蓝本的节点预算主要烧在这条**独立的、无束宽的**最佳优先通道上（见
 * {@code docs/plans/2026-10-30-bfws-novelty.md}）。
 */
final class NoveltySearch {

    private NoveltySearch() {
    }

    /** 蓝本 {@code NoveltySearchOptions}（默认值一字不改）。 */
    static final class Options {
        /** 新颖度阶数 k：1 = 只看一元组，2 = 看二元组（蓝本只能是 1 或 2）。 */
        int width = 2;
        /** 「熟悉」子节点的准入额度；0 = 一个都不放（蓝本默认）。 */
        int familiarAllowance = 0;
        /** open 列表容量上限，满了丢最差的那个。 */
        int maxOpen = 2048;
        /** 新颖度表最多记多少条元组，到这就不再加新条目。 */
        int maxNoveltyEntries = 1_000_000;
        /** 一条路线最多几个动作（蓝本 {@code MaxActions}）。 */
        int maxActions = 128;
        /** 最多推进几个回合（蓝本 {@code MaxTurns}）。 */
        int maxTurns = 32;

        void validate() {
            if (width < 1 || width > 2) {
                throw new IllegalArgumentException("novelty width must be 1 or 2, got " + width);
            }
            if (familiarAllowance < 0 || maxOpen < 1 || maxNoveltyEntries < 1
                    || maxActions < 1 || maxTurns < 1) {
                throw new IllegalArgumentException("invalid novelty options");
            }
        }
    }

    /**
     * 新颖度表：事实 → 原子 id；按**分区**分别记「见过的 1 元组 / 2 元组」。
     *
     * <p>{@link #evaluate} 返回「这个状态有多新」：{@code 1} = 有一元组没见过，
     * {@code 2} = 一元组都见过但有一对没见过，{@code 3} = 全见过（熟悉）。
     * 搜索只收 {@code <= width} 的子节点，所以「熟悉」= 丢掉。
     */
    static final class PackedNovelty {
        private final int width;
        private final int maximumEntries;
        private final Map<String, Integer> atoms = new HashMap<String, Integer>();
        private final Map<Long, Set<Long>> tables = new HashMap<Long, Set<Long>>();
        private final List<Integer> facts = new ArrayList<Integer>();
        private final List<Integer> changed = new ArrayList<Integer>();
        private final Set<Integer> parentFacts = new HashSet<Integer>();
        private long parentPartition = Long.MIN_VALUE;
        private boolean parentComplete;

        long entries;
        long unary;
        long binary;
        long familiar;
        boolean limitReached;

        PackedNovelty(int width, int maximumEntries) {
            this.width = width;
            this.maximumEntries = maximumEntries;
        }

        int atomCount() {
            return atoms.size();
        }

        int partitions() {
            return tables.size();
        }

        /** 蓝本 {@code PrepareParent}：父节点的元组已经全部进过表，扫子节点时可以跳过它们。 */
        void prepareParent(long partition, List<String> parentFactList) {
            parentPartition = partition;
            parentComplete = !limitReached;
            parentFacts.clear();
            for (String fact : parentFactList) {
                Integer id = atoms.get(fact);
                if (id == null) {
                    throw new IllegalStateException("novelty parent fact was never generated: " + fact);
                }
                parentFacts.add(id);
            }
        }

        int evaluate(long partition, List<String> featureList) {
            facts.clear();
            for (String feature : featureList) {
                Integer id = atoms.get(feature);
                if (id == null) {
                    id = atoms.size() + 1;
                    atoms.put(feature, id);
                }
                facts.add(id);
            }
            java.util.Collections.sort(facts);
            int distinct = 0;
            for (int i = 0; i < facts.size(); i++) {
                if (distinct == 0 || !facts.get(i).equals(facts.get(distinct - 1))) {
                    facts.set(distinct++, facts.get(i));
                }
            }
            if (distinct < facts.size()) {
                facts.subList(distinct, facts.size()).clear();
            }

            Set<Long> seen = tables.get(Long.valueOf(partition));
            if (seen == null) {
                seen = new HashSet<Long>();
                tables.put(Long.valueOf(partition), seen);
            }

            boolean reuseParent = parentComplete && parentPartition == partition;
            changed.clear();
            int novelty = width + 1;
            for (int i = 0; i < facts.size(); i++) {
                if (reuseParent && parentFacts.contains(facts.get(i))) {
                    continue;
                }
                changed.add(Integer.valueOf(i));
                if (record(seen, facts.get(i).longValue())) {
                    novelty = 1;
                }
            }
            if (width == 2 && novelty > 1) {
                // 只要一对里有一个是「变的」，就要查这对；全都不变的对父节点已经查过
                int changedCursor = 0;
                for (int i = 0; i < facts.size(); i++) {
                    boolean isChanged = changedCursor < changed.size()
                            && changed.get(changedCursor).intValue() == i;
                    if (isChanged) {
                        changedCursor++;
                        for (int j = i + 1; j < facts.size(); j++) {
                            if (recordPair(seen, facts.get(i), facts.get(j))) {
                                novelty = 2;
                            }
                        }
                    } else {
                        for (int c = changedCursor; c < changed.size(); c++) {
                            int j = changed.get(c).intValue();
                            if (j > i && recordPair(seen, facts.get(i), facts.get(j))) {
                                novelty = 2;
                            }
                        }
                    }
                }
            }
            if (novelty == 1) {
                unary++;
            } else if (novelty == 2 && width == 2) {
                binary++;
            } else {
                familiar++;
            }
            return novelty;
        }

        private boolean recordPair(Set<Long> seen, Integer a, Integer b) {
            long tuple = (((long) a.intValue()) << 32) | (b.intValue() & 0xffffffffL);
            return record(seen, tuple);
        }

        private boolean record(Set<Long> seen, long tuple) {
            Long key = Long.valueOf(tuple);
            if (seen.contains(key)) {
                return false;
            }
            if (entries >= maximumEntries) {
                limitReached = true;
                return true;
            }
            seen.add(key);
            entries++;
            return true;
        }
    }

    /**
     * 容量有界的双端优先队列：入队时若已满，**丢掉最差**的那条。
     *
     * <p>优先级 = (新颖度升序, 分数降序, 序号升序)。新颖度 1 是最新的，所以升序 = 最新的先出。
     * 序号保证比较是全序（{@link TreeSet} 需要）。
     */
    static final class BoundedOpen {
        private final int capacity;
        private final TreeSet<Entry> entries = new TreeSet<Entry>();

        BoundedOpen(int capacity) {
            this.capacity = capacity;
        }

        int size() {
            return entries.size();
        }

        boolean isEmpty() {
            return entries.isEmpty();
        }

        /** @return 被挤掉的那个（没有就是 null） */
        SearchNode enqueue(SearchNode node, int novelty, long score, long sequence) {
            entries.add(new Entry(node, novelty, score, sequence));
            if (entries.size() <= capacity) {
                return null;
            }
            Entry worst = entries.last();
            entries.remove(worst);
            return worst.node;
        }

        SearchNode dequeue() {
            Entry best = entries.pollFirst();
            return best == null ? null : best.node;
        }

        java.util.List<SearchNode> values() {
            java.util.List<SearchNode> out = new ArrayList<SearchNode>(entries.size());
            for (Entry e : entries) {
                out.add(e.node);
            }
            return out;
        }

        void clear() {
            entries.clear();
        }

        private static final class Entry implements Comparable<Entry> {
            final SearchNode node;
            final int novelty;
            final long score;
            final long sequence;

            Entry(SearchNode node, int novelty, long score, long sequence) {
                this.node = node;
                this.novelty = novelty;
                this.score = score;
                this.sequence = sequence;
            }

            @Override
            public int compareTo(Entry other) {
                int c = Integer.compare(novelty, other.novelty);
                if (c != 0) {
                    return c;
                }
                c = Long.compare(other.score, score);          // 分高的优先
                return c != 0 ? c : Long.compare(sequence, other.sequence);
            }
        }
    }

    /** 蓝本 {@code BfwsEscapeBudget}：同一个「最近的新颖祖先」下，熟悉子节点共用一个额度。 */
    static final class EscapeBudget {
        private final int maximum;
        private int admitted;

        EscapeBudget(int maximum) {
            this.maximum = maximum;
        }

        boolean tryAdmit() {
            if (admitted >= maximum) {
                return false;
            }
            admitted++;
            return true;
        }
    }

    /**
     * 一个状态的事实集（蓝本 {@code CaptureNoveltyFacts} 的 STS1 版）。
     *
     * <p>只搬 {@link SimState} 里真有的维度；蓝本那些我们没有对应字段的战略标量
     * （{@code ReachableHandValue} / {@code StrategicEffects.*} …）不硬凑。
     */
    static List<String> factsOf(SimState state) {
        List<String> facts = new ArrayList<String>(48);
        facts.add("E" + state.energy);
        facts.add("B" + (state.block / 5));
        facts.add("H" + (state.hp / 5));
        facts.add("HN" + state.hand.size());
        addZone(facts, 'h', state.hand);
        addZone(facts, 'd', state.draw);
        addZone(facts, 'x', state.discard);
        addZone(facts, 'e', state.exhaust);
        for (int i = 0; i < Math.min(3, state.draw.size()); i++) {
            facts.add("D" + i + state.draw.get(state.draw.size() - 1 - i).key());
        }
        for (Map.Entry<String, Integer> e : state.playerPowers.entrySet()) {
            facts.add("P0" + e.getKey() + e.getValue().intValue());
        }
        int alive = 0;
        for (int i = 0; i < state.monsters.size(); i++) {
            SimMonster m = state.monsters.get(i);
            if (m.hp > 0) {
                alive++;
            }
            facts.add("MT" + i + (m.hp / 10));
            for (Map.Entry<String, Integer> e : m.powers.entrySet()) {
                facts.add("P1" + e.getKey() + e.getValue().intValue());
            }
        }
        facts.add("AM" + alive);
        return facts;
    }

    private static void addZone(List<String> facts, char zone, List<SimCard> cards) {
        Map<String, Integer> counts = new HashMap<String, Integer>();
        List<String> order = new ArrayList<String>();
        for (SimCard card : cards) {
            String key = card.key();
            Integer current = counts.get(key);
            if (current == null) {
                counts.put(key, Integer.valueOf(1));
                order.add(key);
            } else {
                counts.put(key, Integer.valueOf(current.intValue() + 1));
            }
        }
        for (String key : order) {
            int count = Math.min(3, counts.get(key).intValue());
            facts.add("Z" + zone + key + count);
        }
    }

    /** 分区键：敌人总血 / 10 与药水数（蓝本 {@code (EnemyHp / 10, PotionCount)}）。 */
    static long partitionOf(SimState state, int potionsUsed) {
        int enemyHp = 0;
        for (SimMonster m : state.monsters) {
            if (m.hp > 0) {
                enemyHp += m.hp;
            }
        }
        return (((long) (enemyHp / 10)) << 32) | (potionsUsed & 0xffffffffL);
    }
}
