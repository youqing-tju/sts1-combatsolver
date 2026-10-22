package io.github.youqing.sts1solver.sim;

import java.util.List;

/**
 * 洗牌 —— 逐条复刻 {@code CardGroup.shuffle()} 的字节码，不是自己编一个 Fisher-Yates。
 *
 * <pre>
 * // CardGroup.shuffle()  （无参版本 = 用 shuffleRng）
 * Collections.shuffle(group, new java.util.Random(AbstractDungeon.shuffleRng.randomLong()));
 * </pre>
 *
 * <p>两个容易写错的地方：
 * <ol>
 *   <li><b>不是</b> 用 {@code rng.random(n)} 洗牌 —— 是**取一个 long 当种子**，
 *       再交给 {@code java.util.Random} 洗。所以整个洗牌只消耗 <b>1 次</b> {@code shuffleRng}。</li>
 *   <li>{@code java.util.Random} 的 LCG 与 {@code RandomXS128} 完全不同，必须按 JDK 的
 *       48 位线性同余实现；{@code Collections.shuffle} 的循环是
 *       {@code for (i = size; i > 1; i--) swap(i-1, rnd.nextInt(i))}。</li>
 * </ol>
 *
 * <p>⚠️ 即使列表只有 0/1 张，原版也照样先调 {@code randomLong()} —— 计数器一样会 +1。
 */
public final class Shuffle {

    private static final long MULTIPLIER = 0x5DEECE66DL;
    private static final long ADDEND = 0xBL;
    private static final long MASK = (1L << 48) - 1;

    private Shuffle() {
    }

    /**
     * 就地洗牌，消耗 {@code rng} 的一个 long。
     *
     * @param rng 洗牌用的流（原版是 {@code AbstractDungeon.shuffleRng}）
     */
    public static void shuffle(List<?> list, RngStream rng) {
        long seed = rng == null ? 0L : rng.randomLong();
        shuffleWithSeed(list, seed);
    }

    /**
     * 用已经取到的种子洗牌 —— 抽出来单独暴露是为了能和真 {@code Collections.shuffle}
     * 做逐元素对拍（见 {@code ShuffleTest}），避免"看起来对"。
     */
    public static void shuffleWithSeed(List<?> list, long seed) {
        if (list == null || list.size() < 2) {
            return;      // Collections.shuffle 对 size<2 直接返回，但种子已经消耗掉了
        }
        JavaRandom random = new JavaRandom(seed);
        for (int i = list.size(); i > 1; i--) {
            swap(list, i - 1, random.nextInt(i));
        }
    }

    private static void swap(List<?> list, int i, int j) {
        if (i == j) {
            return;
        }
        @SuppressWarnings("unchecked")
        List<Object> raw = (List<Object>) list;
        Object tmp = raw.get(i);
        raw.set(i, raw.get(j));
        raw.set(j, tmp);
    }

    /** {@code java.util.Random} 的最小复刻：48 位 LCG + {@code nextInt(bound)}。 */
    private static final class JavaRandom {

        private long seed;

        JavaRandom(long seed) {
            this.seed = (seed ^ MULTIPLIER) & MASK;
        }

        private int next(int bits) {
            seed = (seed * MULTIPLIER + ADDEND) & MASK;
            return (int) (seed >>> (48 - bits));
        }

        /** 与 JDK 的 {@code Random.nextInt(int)} 位级一致（含拒绝采样）。 */
        int nextInt(int bound) {
            if (bound <= 0) {
                throw new IllegalArgumentException("bound must be positive");
            }
            int r = next(31);
            int m = bound - 1;
            if ((bound & m) == 0) {
                r = (int) ((bound * (long) r) >> 31);
            } else {
                for (int u = r; u - (r = u % bound) + m < 0; u = next(31)) {
                    // 拒绝采样，最多两次
                }
            }
            return r;
        }
    }
}
