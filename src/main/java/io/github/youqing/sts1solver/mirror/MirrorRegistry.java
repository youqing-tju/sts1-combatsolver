package io.github.youqing.sts1solver.mirror;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The coverage registry: card key → mirrored semantics.
 *
 * <p>Hard rule copied from the blueprint: <b>unknown means rejected, not guessed.</b>
 * A card that is not registered makes the action illegal, so a search can never quietly
 * reason on a wrong model.
 *
 * <p>M1 registers only the Ironclad starting deck (Strike / Defend / Bash, both upgrade
 * states). Everything else is expected to be rejected until it is mirrored and covered
 * by the diff harness.
 */
public final class MirrorRegistry {

    private static final Map<String, CardMirror> CARDS = new LinkedHashMap<String, CardMirror>();

    static {
        // ---- Ironclad starting deck -------------------------------------
        register("Strike_R", Effects.damage(6));
        register("Strike_R+", Effects.damage(9));

        register("Defend_R", Effects.block(5));
        register("Defend_R+", Effects.block(8));

        register("Bash", Arrays.asList(
                Effects.damage(8),
                Effects.powerOnTarget(DamageCalc.VULNERABLE, 2)));
        register("Bash+", Arrays.asList(
                Effects.damage(10),
                Effects.powerOnTarget(DamageCalc.VULNERABLE, 3)));
    }

    private MirrorRegistry() {
    }

    private static void register(String key, Effect... effects) {
        register(key, Arrays.asList(effects));
    }

    private static void register(String key, java.util.List<Effect> effects) {
        CARDS.put(key, new CardMirror(key, effects));
    }

    /** @return the mirror, or {@code null} when this card is not supported yet */
    public static CardMirror lookup(String cardKey) {
        return CARDS.get(cardKey);
    }

    public static boolean supports(String cardKey) {
        return CARDS.containsKey(cardKey);
    }

    public static int size() {
        return CARDS.size();
    }

    public static Map<String, CardMirror> all() {
        return Collections.unmodifiableMap(CARDS);
    }
}
