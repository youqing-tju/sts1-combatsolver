package io.github.youqing.sts1solver.mirror;

/**
 * ColorlessPool —— 由字节码生成。
 *
 * <p>顺序 = {@code CardLibrary} 的声明顺序（= {@code srcXxxCardPool} 的真实排列），
 * 并剔除带 {@code CardTags.HEALING} 的牌 —— 随机取牌用的是
 * {@code list.get(rng.random(size - 1))}，顺序和长度错一个下标就全错。
 *
 * <p>由 {@code tools/gen_card_pools.py} 从 {@code desktop-1.0.jar} 的字节码生成，不要手改。
 */
public final class ColorlessPool {

    /** {id, cost, damage, type, rarity} */
    public static final Object[][] CARDS = {
            {"Apotheosis", 2, -1, "SKILL", "RARE"},
            {"Blind", 0, -1, "SKILL", "UNCOMMON"},
            {"Chrysalis", 2, -1, "SKILL", "RARE"},
            {"Dark Shackles", 0, -1, "SKILL", "UNCOMMON"},
            {"Deep Breath", 0, -1, "SKILL", "UNCOMMON"},
            {"Discovery", 1, -1, "SKILL", "UNCOMMON"},
            {"Dramatic Entrance", 0, 8, "ATTACK", "UNCOMMON"},
            {"Enlightenment", 0, -1, "SKILL", "UNCOMMON"},
            {"Finesse", 0, -1, "SKILL", "UNCOMMON"},
            {"Flash of Steel", 0, 3, "ATTACK", "UNCOMMON"},
            {"Forethought", 0, -1, "SKILL", "UNCOMMON"},
            {"Good Instincts", 0, -1, "SKILL", "UNCOMMON"},
            {"HandOfGreed", 2, 20, "ATTACK", "RARE"},
            {"Impatience", 0, -1, "SKILL", "UNCOMMON"},
            {"Jack Of All Trades", 0, -1, "SKILL", "UNCOMMON"},
            {"Madness", 1, -1, "SKILL", "UNCOMMON"},
            {"Magnetism", 2, -1, "POWER", "RARE"},
            {"Master of Strategy", 0, -1, "SKILL", "RARE"},
            {"Mayhem", 2, -1, "POWER", "RARE"},
            {"Metamorphosis", 2, -1, "SKILL", "RARE"},
            {"Mind Blast", 2, 0, "ATTACK", "UNCOMMON"},
            {"Panacea", 0, -1, "SKILL", "UNCOMMON"},
            {"Panache", 0, -1, "POWER", "RARE"},
            {"PanicButton", 0, -1, "SKILL", "UNCOMMON"},
            {"Purity", 0, -1, "SKILL", "UNCOMMON"},
            {"Sadistic Nature", 0, -1, "POWER", "RARE"},
            {"Secret Technique", 0, -1, "SKILL", "RARE"},
            {"Secret Weapon", 0, -1, "SKILL", "RARE"},
            {"Swift Strike", 0, 7, "ATTACK", "UNCOMMON"},
            {"The Bomb", 2, -1, "SKILL", "RARE"},
            {"Thinking Ahead", 0, -1, "SKILL", "RARE"},
            {"Transmutation", -1, -1, "SKILL", "RARE"},
            {"Trip", 0, -1, "SKILL", "UNCOMMON"},
            {"Violence", 0, -1, "SKILL", "RARE"},
            {"Ghostly", 1, -1, "SKILL", "SPECIAL"},
            {"J.A.X.", 0, -1, "SKILL", "SPECIAL"},
            {"RitualDagger", 1, -1, "ATTACK", "SPECIAL"},
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

    public static String typeAt(int i) {
        return (String) CARDS[i][3];
    }

    public static String rarityAt(int i) {
        return (String) CARDS[i][4];
    }
}
