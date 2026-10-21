package io.github.youqing.sts1solver.mirror;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** The mirrored semantics of one card (one upgrade state). */
public final class CardMirror {

    public final String key;
    public final List<Effect> effects;

    public CardMirror(String key, List<Effect> effects) {
        this.key = key;
        this.effects = Collections.unmodifiableList(new ArrayList<Effect>(effects));
    }

    @Override
    public String toString() {
        return key + effects;
    }
}
