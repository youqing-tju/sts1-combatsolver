package io.github.youqing.sts1solver.mirror;

/**
 * 铁甲战士攻击牌池 —— 地狱之刃（Infernal Blade）专用。
 *
 * <p>为什么可以硬编码：反编译确认它调用
 * {@code AbstractDungeon.returnTrulyRandomCardInCombat(CardType.ATTACK)}，
 * 取的是**当前角色**的攻击牌池（铁甲战士 = 红卡），随机源为 {@code cardRandomRng}。
 * 红卡已 74/75 覆盖，所以这个池子是完整的，不存在"信息缺失"。
 *
 * <p>数值由 {@code tools/gen_attack_pool.py} 从 {@code cards.json} 生成，不要手改。
 */
public final class AttackPool {

    /** {id, cost, dmg, upgradedCost, upgradedDmg} */
    public static final Object[][] CARDS = {
            {"Anger", 0, 6, 0, 8},
            {"Bash", 2, 8, 2, 10},
            {"Blood for Blood", 4, 18, 3, 22},
            {"Bludgeon", 3, 32, 3, 42},
            {"Body Slam", 1, 0, 0, 0},
            {"Carnage", 2, 20, 2, 28},
            {"Clash", 0, 14, 0, 18},
            {"Cleave", 1, 8, 1, 11},
            {"Clothesline", 2, 12, 2, 14},
            {"Dropkick", 1, 5, 1, 8},
            {"Feed", 1, 10, 1, 12},
            {"Fiend Fire", 2, 7, 2, 10},
            {"Headbutt", 1, 9, 1, 12},
            {"Heavy Blade", 2, 14, 2, 14},
            {"Hemokinesis", 1, 15, 1, 20},
            {"Immolate", 2, 21, 2, 28},
            {"Iron Wave", 1, 5, 1, 7},
            {"Perfected Strike", 2, 6, 2, 6},
            {"Pommel Strike", 1, 9, 1, 10},
            {"Pummel", 1, 2, 1, 2},
            {"Rampage", 1, 8, 1, 8},
            {"Reaper", 2, 4, 2, 5},
            {"Reckless Charge", 0, 7, 0, 10},
            {"Searing Blow", 2, 12, 2, 16},
            {"Sever Soul", 2, 16, 2, 22},
            {"Strike_R", 1, 6, 1, 9},
            {"Sword Boomerang", 1, 3, 1, 3},
            {"Thunderclap", 1, 4, 1, 7},
            {"Twin Strike", 1, 5, 1, 7},
            {"Uppercut", 2, 13, 2, 13},
            {"Whirlwind", -1, 5, -1, 8},
            {"Wild Strike", 1, 12, 1, 17},
    };

    private AttackPool() {
    }

    public static int size() {
        return CARDS.length;
    }

    public static String idAt(int index) {
        return (String) CARDS[index][0];
    }

    public static int costAt(int index) {
        return (Integer) CARDS[index][1];
    }

    public static int damageAt(int index) {
        return (Integer) CARDS[index][2];
    }

    public static int upgradedCostAt(int index) {
        return (Integer) CARDS[index][3];
    }

    public static int upgradedDamageAt(int index) {
        return (Integer) CARDS[index][4];
    }
}
