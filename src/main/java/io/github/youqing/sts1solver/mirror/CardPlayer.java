package io.github.youqing.sts1solver.mirror;

import io.github.youqing.sts1solver.sim.SimCard;
import io.github.youqing.sts1solver.sim.SimState;

/**
 * Applies one card play to a shadow state.
 *
 * <p>Layering: {@code sim} knows what a state <i>is</i>; {@code mirror} knows how the
 * game <i>behaves</i>. So the rule machine lives here, and {@code mirror → sim} is the
 * only dependency direction (no cycle).
 *
 * <p>Anything that cannot be simulated faithfully throws {@link ActionRejected}. Callers
 * are expected to treat that as "this action is illegal for the search", never as "skip
 * the check".
 */
public final class CardPlayer {

    private CardPlayer() {
    }

    public static void play(SimState state, int handIndex, int targetIndex) {
        if (handIndex < 0 || handIndex >= state.hand.size()) {
            throw new ActionRejected("hand index out of range: " + handIndex);
        }
        SimCard card = state.hand.get(handIndex);
        CardMirror mirror = MirrorRegistry.lookup(card.key());
        if (mirror == null) {
            throw new ActionRejected("no mirror for card " + card.key());
        }
        if (card.cost < 0) {
            throw new ActionRejected("unsupported cost " + card.cost + " on " + card.key());
        }
        if (card.cost > state.energy) {
            throw new ActionRejected("not enough energy for " + card.key()
                    + " (need " + card.cost + ", have " + state.energy + ")");
        }

        state.energy -= card.cost;
        state.hand.remove(handIndex);

        for (Effect effect : mirror.effects) {
            effect.applyTo(state, targetIndex);
        }

        state.discard.add(card);
        removeDeadMonsters(state);
    }

    /** The game removes a monster once its death animation ends; the shadow state removes it at once. */
    private static void removeDeadMonsters(SimState state) {
        for (int i = state.monsters.size() - 1; i >= 0; i--) {
            if (state.monsters.get(i).hp <= 0) {
                state.monsters.remove(i);
            }
        }
    }
}
