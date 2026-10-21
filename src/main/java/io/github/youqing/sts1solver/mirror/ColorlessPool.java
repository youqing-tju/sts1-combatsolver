package io.github.youqing.sts1solver.mirror;

/**
 * 无色牌池 —— 生成类卡牌专用（Jack Of All Trades / Transmutation / Magnetism）。
 *
 * <p>数值由 {@code tools/coverage_report.py} 同源数据（cards.json）生成，不要手改。
 */
public final class ColorlessPool {

    /** {id, cost, dmg} */
    public static final Object[][] CARDS = {
            {"Apotheosis", 2, -1},
            {"Bandage Up", 0, -1},
            {"Beta", 2, -1},
            {"Bite", 1, 7},
            {"Blind", 0, -1},
            {"Chrysalis", 2, -1},
            {"Dark Shackles", 0, -1},
            {"Deep Breath", 0, -1},
            {"Discovery", 1, -1},
            {"Dramatic Entrance", 0, 8},
            {"Enlightenment", 0, -1},
            {"Expunger", 1, 9},
            {"Finesse", 0, -1},
            {"Flash of Steel", 0, 3},
            {"Forethought", 0, -1},
            {"Ghostly", 1, -1},
            {"Good Instincts", 0, -1},
            {"HandOfGreed", 2, 20},
            {"Impatience", 0, -1},
            {"Insight", 0, -1},
            {"J.A.X.", 0, -1},
            {"Jack Of All Trades", 0, -1},
            {"Madness", 1, -1},
            {"Magnetism", 2, -1},
            {"Master of Strategy", 0, -1},
            {"Mayhem", 2, -1},
            {"Metamorphosis", 2, -1},
            {"Mind Blast", 2, 0},
            {"Miracle", 0, -1},
            {"Omega", 3, -1},
            {"Panacea", 0, -1},
            {"Panache", 0, -1},
            {"PanicButton", 0, -1},
            {"Purity", 0, -1},
            {"RitualDagger", 1, 15},
            {"Sadistic Nature", 0, -1},
            {"Safety", 1, -1},
            {"Secret Technique", 0, -1},
            {"Secret Weapon", 0, -1},
            {"Shiv", 0, 4},
            {"Smite", 1, 12},
            {"Swift Strike", 0, 7},
            {"The Bomb", 2, -1},
            {"Thinking Ahead", 0, -1},
            {"ThroughViolence", 0, 20},
            {"Trip", 0, -1},
            {"Violence", 0, -1},
    };

    private ColorlessPool() {
    }

    public static int size() {
        return CARDS.length;
    }

    public static String idAt(int i) {
        return (String) CARDS[i][0];
    }

    public static int costAt(int i) {
        return (Integer) CARDS[i][1];
    }

    public static int damageAt(int i) {
        return (Integer) CARDS[i][2];
    }
}
