package io.github.youqing.sts1solver.mirror;

import io.github.youqing.sts1solver.sim.SimState;

/**
 * One atomic effect of playing a card, expressed against the shadow state.
 *
 * <p>Cards are modelled as a list of these instead of one class per card: most of the
 * pool is "damage / block / apply power" in different proportions, and a data table is
 * far cheaper to audit than 75 hand-written classes.
 */
public interface Effect {

    /** @param targetIndex index into {@link SimState#monsters}, or -1 when the effect is self-targeted */
    void applyTo(SimState state, int targetIndex);
}
