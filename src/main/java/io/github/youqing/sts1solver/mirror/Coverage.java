package io.github.youqing.sts1solver.mirror;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import io.github.youqing.sts1solver.sim.SimCard;
import io.github.youqing.sts1solver.sim.SimState;

/**
 * Coverage probe (the seed of A6). Reports which cards in the current combat have no
 * mirror yet — those cards make an action illegal, so knowing them up front tells us
 * exactly how much of the deck the solver can already reason about.
 */
public final class Coverage {

    private Coverage() {
    }

    public static List<String> unsupportedCards(SimState state) {
        Set<String> missing = new LinkedHashSet<String>();
        collect(state.hand, missing);
        collect(state.draw, missing);
        collect(state.discard, missing);
        collect(state.exhaust, missing);
        return new ArrayList<String>(missing);
    }

    public static int distinctCards(SimState state) {
        Set<String> keys = new LinkedHashSet<String>();
        add(state.hand, keys);
        add(state.draw, keys);
        add(state.discard, keys);
        add(state.exhaust, keys);
        return keys.size();
    }

    private static void collect(List<SimCard> cards, Set<String> missing) {
        for (SimCard c : cards) {
            if (!MirrorRegistry.supports(c.key())) {
                missing.add(c.key());
            }
        }
    }

    private static void add(List<SimCard> cards, Set<String> keys) {
        for (SimCard c : cards) {
            keys.add(c.key());
        }
    }
}
