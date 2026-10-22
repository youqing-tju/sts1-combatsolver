package io.github.youqing.sts1solver.sim;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 洗牌必须和游戏位级一致，否则每次洗牌后抽到的牌全错。
 *
 * <p>原版是 {@code Collections.shuffle(list, new java.util.Random(rng.randomLong()))}，
 * 所以这里的判据很硬：拿 JDK 自己的 {@link Random} 和 {@link Collections#shuffle} 做对照，
 * 同一颗种子必须洗出一模一样的排列。
 */
class ShuffleTest {

    private static List<Integer> seq(int n) {
        List<Integer> list = new ArrayList<Integer>();
        for (int i = 0; i < n; i++) {
            list.add(i);
        }
        return list;
    }

    @Test
    void matchesJavaUtilCollectionsShuffleForManySeeds() {
        for (long seed = -50; seed < 200; seed++) {
            for (int size = 0; size <= 20; size++) {
                List<Integer> expected = seq(size);
                Collections.shuffle(expected, new Random(seed + 7919L * size));

                List<Integer> actual = seq(size);
                // 同一个种子：java.util.Random(seed) 在 JDK 里也是 (seed ^ 0x5DEECE66D) & mask
                Shuffle.shuffleWithSeed(actual, seed + 7919L * size);

                assertEquals(expected, actual, "seed=" + seed + " size=" + size);
            }
        }
    }

    @Test
    void consumesExactlyOneShuffleRngDraw() {
        RngStream rng = RngStream.from(11L, 22L, 0);
        List<Integer> list = seq(10);

        Shuffle.shuffle(list, rng);
        assertEquals(1, rng.counter(), "洗牌只消耗一次 randomLong（种子），不是 n 次 random");

        // 顺便确认 randomLong 也会推进底层状态
        long before = rng.state0();
        rng.randomLong();
        assertEquals(2, rng.counter());
        org.junit.jupiter.api.Assertions.assertNotEquals(before, rng.state0());
    }

    /** 空列表 / 单张也要消耗一次 —— 原版是先取种子再交给 Collections.shuffle。 */
    @Test
    void consumesEvenWhenNothingToShuffle() {
        RngStream rng = RngStream.from(3L, 4L, 0);
        Shuffle.shuffle(new ArrayList<Integer>(), rng);
        assertEquals(1, rng.counter());
    }
}
