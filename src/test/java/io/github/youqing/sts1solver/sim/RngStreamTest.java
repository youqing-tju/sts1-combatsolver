package io.github.youqing.sts1solver.sim;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * These constants are not invented: they come from a live capture
 * (log 2026-10-21 12:34:54, {@code cardRandomRng} at combat start) and from
 * {@code RngForkProbe}, which drew 8 values from a copy of the live stream.
 *
 * <p>If this test goes red, the snapshot cannot be restored and the whole
 * "omniscient" search loses its footing — treat it as a build blocker.
 */
class RngStreamTest {

    private static final long S0 = 6198833888001070896L;
    private static final long S1 = 5149783851214758911L;
    private static final int[] LIVE_DRAWS = {584, 505, 979, 733, 584, 398, 877, 516};

    @Test
    void restoresTheExactStreamSeenInTheLiveGame() {
        RngStream stream = RngStream.from(S0, S1, 0);
        for (int i = 0; i < LIVE_DRAWS.length; i++) {
            assertEquals(LIVE_DRAWS[i], stream.random(1000),
                    "draw #" + i + " must match the live capture");
        }
        assertEquals(LIVE_DRAWS.length, stream.counter(), "counter tracks the number of draws");
    }

    @Test
    void forkIsIndependentAndReproducible() {
        RngStream live = RngStream.from(S0, S1, 0);
        RngStream a = live.fork();
        RngStream b = live.fork();

        int firstA = a.random(1000);
        int firstB = b.random(1000);
        assertEquals(firstA, firstB, "two forks of the same state must agree");
        assertEquals(LIVE_DRAWS[0], firstA);

        // advancing a fork must not disturb the parent
        a.random(1000);
        a.random(1000);
        assertEquals(0, live.counter(), "parent stream must stay untouched");
        assertEquals(LIVE_DRAWS[1], b.random(1000), "sibling fork continues its own sequence");
    }

    @Test
    void snapshotRoundTripPreservesPosition() {
        RngStream original = RngStream.from(S0, S1, 0);
        original.random(1000);
        original.random(1000);

        RngStream restored = RngStream.from(original.state0(), original.state1(), original.counter());
        assertEquals(original.random(1000), restored.random(1000));
        assertEquals(original.random(1000), restored.random(1000));

        RngStream moved = RngStream.from(S0, S1, 0);
        moved.random(1000);
        assertNotEquals(original.state0(), moved.state0(),
                "drawing must actually move the internal state");
    }
}
