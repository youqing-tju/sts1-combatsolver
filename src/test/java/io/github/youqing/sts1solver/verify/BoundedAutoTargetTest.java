package io.github.youqing.sts1solver.verify;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoundedAutoTargetTest {
    @Test
    void acceptsCurrentOrFutureTurnAndStopsOnlyAfterTarget() {
        BoundedAutoTarget target = new BoundedAutoTarget();
        assertTrue(target.request(6, 4));
        assertEquals(6, target.targetTurn());
        assertFalse(target.shouldStop(6));
        assertTrue(target.shouldStop(7));
    }

    @Test
    void currentTurnIsAValidTarget() {
        BoundedAutoTarget target = new BoundedAutoTarget();
        assertTrue(target.request(4, 4));
        assertFalse(target.shouldStop(4));
        assertTrue(target.shouldStop(5));
    }

    @Test
    void expiredTargetIsRejectedWithoutChangingOldTarget() {
        BoundedAutoTarget target = new BoundedAutoTarget();
        assertTrue(target.request(6, 4));
        assertFalse(target.request(5, 6));
        assertEquals(6, target.targetTurn());
    }

    @Test
    void clearRestoresUnboundedState() {
        BoundedAutoTarget target = new BoundedAutoTarget();
        target.request(3, 1);
        target.clear();
        assertFalse(target.isActive());
        assertEquals(-1, target.targetTurn());
        assertFalse(target.shouldStop(99));
    }

    @Test
    void invalidTurnNumbersAreRejected() {
        BoundedAutoTarget target = new BoundedAutoTarget();
        assertFalse(target.request(0, 1));
        assertFalse(target.request(2, 0));
        assertFalse(target.isActive());
    }
}
