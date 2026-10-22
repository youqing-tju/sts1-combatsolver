package io.github.youqing.sts1solver.mirror;

/**
 * RedPool —— 由字节码生成。
 *
 * <p>顺序 = {@code CardLibrary} 的声明顺序（= {@code srcXxxCardPool} 的真实排列），
 * 并剔除带 {@code CardTags.HEALING} 的牌 —— 随机取牌用的是
 * {@code list.get(rng.random(size - 1))}，顺序和长度错一个下标就全错。
 *
 * <p>由 {@code tools/gen_card_pools.py} 从 {@code desktop-1.0.jar} 的字节码生成，不要手改。
 */
public final class RedPool {

    /** {id, cost, damage, type, rarity} */
    public static final Object[][] CARDS = {
            {"Anger", 0, 6, "ATTACK", "COMMON"},
            {"Armaments", 1, -1, "SKILL", "COMMON"},
            {"Barricade", 3, -1, "POWER", "RARE"},
            {"Bash", 2, 8, "ATTACK", "BASIC"},
            {"Battle Trance", 0, -1, "SKILL", "UNCOMMON"},
            {"Berserk", 0, -1, "POWER", "RARE"},
            {"Blood for Blood", 4, 18, "ATTACK", "UNCOMMON"},
            {"Bloodletting", 0, -1, "SKILL", "UNCOMMON"},
            {"Bludgeon", 3, 32, "ATTACK", "RARE"},
            {"Body Slam", 1, 0, "ATTACK", "COMMON"},
            {"Brutality", 0, -1, "POWER", "RARE"},
            {"Burning Pact", 1, -1, "SKILL", "UNCOMMON"},
            {"Carnage", 2, 20, "ATTACK", "UNCOMMON"},
            {"Clash", 0, 14, "ATTACK", "COMMON"},
            {"Cleave", 1, 8, "ATTACK", "COMMON"},
            {"Clothesline", 2, 12, "ATTACK", "COMMON"},
            {"Combust", 1, -1, "POWER", "UNCOMMON"},
            {"Corruption", 3, -1, "POWER", "RARE"},
            {"Dark Embrace", 2, -1, "POWER", "UNCOMMON"},
            {"Defend_R", 1, -1, "SKILL", "BASIC"},
            {"Demon Form", 3, -1, "POWER", "RARE"},
            {"Disarm", 1, -1, "SKILL", "UNCOMMON"},
            {"Double Tap", 1, -1, "SKILL", "RARE"},
            {"Dropkick", 1, 5, "ATTACK", "UNCOMMON"},
            {"Dual Wield", 1, -1, "SKILL", "UNCOMMON"},
            {"Entrench", 2, -1, "SKILL", "UNCOMMON"},
            {"Evolve", 1, -1, "POWER", "UNCOMMON"},
            {"Exhume", 1, -1, "SKILL", "RARE"},
            {"Feel No Pain", 1, -1, "POWER", "UNCOMMON"},
            {"Fiend Fire", 2, 7, "ATTACK", "RARE"},
            {"Fire Breathing", 1, -1, "POWER", "UNCOMMON"},
            {"Flame Barrier", 2, -1, "SKILL", "UNCOMMON"},
            {"Flex", 0, -1, "SKILL", "COMMON"},
            {"Ghostly Armor", 1, -1, "SKILL", "UNCOMMON"},
            {"Havoc", 1, -1, "SKILL", "COMMON"},
            {"Headbutt", 1, 9, "ATTACK", "COMMON"},
            {"Heavy Blade", 2, 14, "ATTACK", "COMMON"},
            {"Hemokinesis", 1, 15, "ATTACK", "UNCOMMON"},
            {"Immolate", 2, 21, "ATTACK", "RARE"},
            {"Impervious", 2, -1, "SKILL", "RARE"},
            {"Infernal Blade", 1, -1, "SKILL", "UNCOMMON"},
            {"Inflame", 1, -1, "POWER", "UNCOMMON"},
            {"Intimidate", 0, -1, "SKILL", "UNCOMMON"},
            {"Iron Wave", 1, 5, "ATTACK", "COMMON"},
            {"Juggernaut", 2, -1, "POWER", "RARE"},
            {"Limit Break", 1, -1, "SKILL", "RARE"},
            {"Metallicize", 1, -1, "POWER", "UNCOMMON"},
            {"Offering", 0, -1, "SKILL", "RARE"},
            {"Perfected Strike", 2, 6, "ATTACK", "COMMON"},
            {"Pommel Strike", 1, 9, "ATTACK", "COMMON"},
            {"Power Through", 1, -1, "SKILL", "UNCOMMON"},
            {"Pummel", 1, 2, "ATTACK", "UNCOMMON"},
            {"Rage", 0, -1, "SKILL", "UNCOMMON"},
            {"Rampage", 1, 8, "ATTACK", "UNCOMMON"},
            {"Reckless Charge", 0, 7, "ATTACK", "UNCOMMON"},
            {"Rupture", 1, -1, "POWER", "UNCOMMON"},
            {"Searing Blow", 2, 12, "ATTACK", "UNCOMMON"},
            {"Second Wind", 1, -1, "SKILL", "UNCOMMON"},
            {"Seeing Red", 1, -1, "SKILL", "UNCOMMON"},
            {"Sentinel", 1, -1, "SKILL", "UNCOMMON"},
            {"Sever Soul", 2, 16, "ATTACK", "UNCOMMON"},
            {"Shockwave", 2, -1, "SKILL", "UNCOMMON"},
            {"Shrug It Off", 1, -1, "SKILL", "COMMON"},
            {"Spot Weakness", 1, -1, "SKILL", "UNCOMMON"},
            {"Strike_R", 1, 6, "ATTACK", "BASIC"},
            {"Sword Boomerang", 1, 3, "ATTACK", "COMMON"},
            {"Thunderclap", 1, 4, "ATTACK", "COMMON"},
            {"True Grit", 1, -1, "SKILL", "COMMON"},
            {"Twin Strike", 1, 5, "ATTACK", "COMMON"},
            {"Uppercut", 2, 13, "ATTACK", "UNCOMMON"},
            {"Warcry", 0, -1, "SKILL", "COMMON"},
            {"Whirlwind", -1, 5, "ATTACK", "UNCOMMON"},
            {"Wild Strike", 1, 12, "ATTACK", "COMMON"},
    };

    private RedPool() {
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
