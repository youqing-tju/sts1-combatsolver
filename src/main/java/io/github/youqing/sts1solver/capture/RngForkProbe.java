package io.github.youqing.sts1solver.capture;

import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.random.Random;

/**
 * Proves the single assumption the whole "omniscient" (D8) design rests on:
 * {@code Random.copy()} forks an exact, independent copy of the stream.
 *
 * <p>We copy the live combat RNG twice, draw the same number of values from each copy,
 * and compare. The live stream must not move: copying and replaying must be free of
 * side effects, otherwise searching would perturb the real fight.
 */
public final class RngForkProbe {

    private static final int DRAWS = 8;
    private static final int RANGE = 1000;

    private RngForkProbe() {
    }

    public static String run() {
        Random live = AbstractDungeon.cardRandomRng;
        if (live == null) {
            return "fork: no cardRandomRng";
        }
        try {
            int counterBefore = live.counter;

            Random a = live.copy();
            Random b = live.copy();

            StringBuilder sa = new StringBuilder();
            StringBuilder sb = new StringBuilder();
            boolean equal = true;
            for (int i = 0; i < DRAWS; i++) {
                int va = a.random(RANGE);
                int vb = b.random(RANGE);
                if (i > 0) {
                    sa.append(',');
                    sb.append(',');
                }
                sa.append(va);
                sb.append(vb);
                equal &= va == vb;
            }

            int counterAfter = live.counter;
            boolean untouched = counterBefore == counterAfter;

            return "fork: equal=" + equal
                    + " liveUntouched=" + untouched
                    + " counter=" + counterBefore
                    + " a=[" + sa + "]"
                    + (equal ? "" : " b=[" + sb + "]");
        } catch (Throwable t) {
            return "fork: failed " + t;
        }
    }
}
