package io.github.youqing.sts1solver.mirror;

import java.util.HashMap;
import java.util.Map;

/**
 * 原版升级后的费用/伤害**机械数据**（由 {@code tools/gen_card_upgrades.py} 生成，请勿手改）。
 *
 * <p>数据来源：{@code capture/CardExporter.java:76-100} 在游戏里对每张卡执行
 * {@code makeCopy() + upgrade()}，把升级前后的 {@code cost} / {@code baseDamage} 一起写进
 * {@code solver-data/cards.json}。本表只是它的静态投影 —— 游戏里没有运行时读取，
 * 所以离线测试与真机用的是**同一份**数字。
 *
 * <p>⚠️ 只登记**有变化**的条目。{@code Rampage} 这类「升级只改 magicNumber、不动 baseDamage」
 * 的卡不在 DAMAGE 表里，正是要保住它的动态成长（用静态 up.dmg 覆盖会把已经打出来的
 * +5/N 次增长抹掉）。
 */
final class CardUpgradeTable {

    private CardUpgradeTable() {
    }

    /** {@code id -> {升级前费用, 升级后费用}}。 */
    private static final Map<String, int[]> COST = new HashMap<String, int[]>();
    /** {@code id -> {升级前 baseDamage, 升级后 baseDamage}}。 */
    private static final Map<String, int[]> DAMAGE = new HashMap<String, int[]>();

    static {
        COST.put("Adaptation", new int[]{1, 0});
        COST.put("Apotheosis", new int[]{2, 1});
        COST.put("Barricade", new int[]{3, 2});
        COST.put("Beta", new int[]{2, 1});
        COST.put("Blood for Blood", new int[]{4, 3});
        COST.put("Body Slam", new int[]{1, 0});
        COST.put("Bullet Time", new int[]{3, 2});
        COST.put("ClearTheMind", new int[]{1, 0});
        COST.put("Corruption", new int[]{3, 2});
        COST.put("Creative AI", new int[]{3, 2});
        COST.put("Crescendo", new int[]{1, 0});
        COST.put("Dark Embrace", new int[]{2, 1});
        COST.put("Distraction", new int[]{1, 0});
        COST.put("Double Energy", new int[]{1, 0});
        COST.put("Dualcast", new int[]{1, 0});
        COST.put("Entrench", new int[]{2, 1});
        COST.put("Envenom", new int[]{2, 1});
        COST.put("Eruption", new int[]{2, 1});
        COST.put("Exhume", new int[]{1, 0});
        COST.put("Fusion", new int[]{2, 1});
        COST.put("Havoc", new int[]{1, 0});
        COST.put("Infernal Blade", new int[]{1, 0});
        COST.put("Madness", new int[]{1, 0});
        COST.put("Magnetism", new int[]{2, 1});
        COST.put("MasterReality", new int[]{1, 0});
        COST.put("Mayhem", new int[]{2, 1});
        COST.put("Mind Blast", new int[]{2, 1});
        COST.put("Night Terror", new int[]{3, 2});
        COST.put("Omniscience", new int[]{4, 3});
        COST.put("Phantasmal Killer", new int[]{1, 0});
        COST.put("Recycle", new int[]{1, 0});
        COST.put("Redo", new int[]{1, 0});
        COST.put("Scrawl", new int[]{1, 0});
        COST.put("Seeing Red", new int[]{1, 0});
        COST.put("Setup", new int[]{1, 0});
        COST.put("Study", new int[]{2, 1});
        COST.put("Terror", new int[]{1, 0});
        COST.put("Tools of the Trade", new int[]{1, 0});
        COST.put("Vault", new int[]{3, 2});
        COST.put("Venomology", new int[]{1, 0});
        COST.put("White Noise", new int[]{1, 0});
        COST.put("Zap", new int[]{1, 0});

        DAMAGE.put("All For One", new int[]{10, 14});
        DAMAGE.put("All Out Attack", new int[]{10, 14});
        DAMAGE.put("Anger", new int[]{6, 8});
        DAMAGE.put("Backstab", new int[]{11, 15});
        DAMAGE.put("Ball Lightning", new int[]{7, 10});
        DAMAGE.put("Bane", new int[]{7, 10});
        DAMAGE.put("Barrage", new int[]{4, 6});
        DAMAGE.put("Bash", new int[]{8, 10});
        DAMAGE.put("Beam Cell", new int[]{3, 4});
        DAMAGE.put("Bite", new int[]{7, 8});
        DAMAGE.put("Blood for Blood", new int[]{18, 22});
        DAMAGE.put("Bludgeon", new int[]{32, 42});
        DAMAGE.put("BowlingBash", new int[]{7, 10});
        DAMAGE.put("Brilliance", new int[]{12, 16});
        DAMAGE.put("Carnage", new int[]{20, 28});
        DAMAGE.put("CarveReality", new int[]{6, 10});
        DAMAGE.put("Clash", new int[]{14, 18});
        DAMAGE.put("Cleave", new int[]{8, 11});
        DAMAGE.put("Clothesline", new int[]{12, 14});
        DAMAGE.put("Cold Snap", new int[]{6, 9});
        DAMAGE.put("Compile Driver", new int[]{7, 10});
        DAMAGE.put("Conclude", new int[]{12, 16});
        DAMAGE.put("Consecrate", new int[]{5, 8});
        DAMAGE.put("Core Surge", new int[]{11, 15});
        DAMAGE.put("CrushJoints", new int[]{8, 10});
        DAMAGE.put("CutThroughFate", new int[]{7, 9});
        DAMAGE.put("Dagger Spray", new int[]{4, 6});
        DAMAGE.put("Dagger Throw", new int[]{9, 12});
        DAMAGE.put("Dash", new int[]{10, 13});
        DAMAGE.put("Die Die Die", new int[]{13, 17});
        DAMAGE.put("Doom and Gloom", new int[]{10, 14});
        DAMAGE.put("Dramatic Entrance", new int[]{8, 12});
        DAMAGE.put("Dropkick", new int[]{5, 8});
        DAMAGE.put("EmptyFist", new int[]{9, 14});
        DAMAGE.put("Endless Agony", new int[]{4, 6});
        DAMAGE.put("Eviscerate", new int[]{7, 9});
        DAMAGE.put("Expunger", new int[]{9, 15});
        DAMAGE.put("FTL", new int[]{5, 6});
        DAMAGE.put("FearNoEvil", new int[]{8, 11});
        DAMAGE.put("Feed", new int[]{10, 12});
        DAMAGE.put("Fiend Fire", new int[]{7, 10});
        DAMAGE.put("Finisher", new int[]{6, 8});
        DAMAGE.put("Flash of Steel", new int[]{3, 6});
        DAMAGE.put("Flechettes", new int[]{4, 6});
        DAMAGE.put("FlurryOfBlows", new int[]{4, 6});
        DAMAGE.put("Flying Knee", new int[]{8, 11});
        DAMAGE.put("FlyingSleeves", new int[]{4, 6});
        DAMAGE.put("FollowUp", new int[]{7, 11});
        DAMAGE.put("Gash", new int[]{3, 5});
        DAMAGE.put("Glass Knife", new int[]{8, 12});
        DAMAGE.put("Go for the Eyes", new int[]{3, 4});
        DAMAGE.put("Grand Finale", new int[]{50, 60});
        DAMAGE.put("HandOfGreed", new int[]{20, 25});
        DAMAGE.put("Headbutt", new int[]{9, 12});
        DAMAGE.put("Heel Hook", new int[]{5, 8});
        DAMAGE.put("Hemokinesis", new int[]{15, 20});
        DAMAGE.put("Hyperbeam", new int[]{26, 34});
        DAMAGE.put("Immolate", new int[]{21, 28});
        DAMAGE.put("Iron Wave", new int[]{5, 7});
        DAMAGE.put("JustLucky", new int[]{3, 4});
        DAMAGE.put("LessonLearned", new int[]{10, 13});
        DAMAGE.put("Lockon", new int[]{8, 11});
        DAMAGE.put("Masterful Stab", new int[]{12, 16});
        DAMAGE.put("Melter", new int[]{10, 14});
        DAMAGE.put("Meteor Strike", new int[]{24, 30});
        DAMAGE.put("Neutralize", new int[]{3, 4});
        DAMAGE.put("Poisoned Stab", new int[]{6, 8});
        DAMAGE.put("Pommel Strike", new int[]{9, 10});
        DAMAGE.put("Predator", new int[]{15, 20});
        DAMAGE.put("Quick Slash", new int[]{8, 12});
        DAMAGE.put("Ragnarok", new int[]{5, 6});
        DAMAGE.put("ReachHeaven", new int[]{10, 15});
        DAMAGE.put("Reaper", new int[]{4, 5});
        DAMAGE.put("Rebound", new int[]{9, 12});
        DAMAGE.put("Reckless Charge", new int[]{7, 10});
        DAMAGE.put("Riddle With Holes", new int[]{3, 4});
        DAMAGE.put("Rip and Tear", new int[]{7, 9});
        DAMAGE.put("SandsOfTime", new int[]{20, 26});
        DAMAGE.put("SashWhip", new int[]{8, 10});
        DAMAGE.put("Scrape", new int[]{7, 10});
        DAMAGE.put("Searing Blow", new int[]{12, 16});
        DAMAGE.put("Sever Soul", new int[]{16, 22});
        DAMAGE.put("Shiv", new int[]{4, 6});
        DAMAGE.put("SignatureMove", new int[]{30, 40});
        DAMAGE.put("Skewer", new int[]{7, 10});
        DAMAGE.put("Slice", new int[]{6, 9});
        DAMAGE.put("Smite", new int[]{12, 16});
        DAMAGE.put("Streamline", new int[]{15, 20});
        DAMAGE.put("Strike_B", new int[]{6, 9});
        DAMAGE.put("Strike_G", new int[]{6, 9});
        DAMAGE.put("Strike_P", new int[]{6, 9});
        DAMAGE.put("Strike_R", new int[]{6, 9});
        DAMAGE.put("Sucker Punch", new int[]{7, 9});
        DAMAGE.put("Sunder", new int[]{24, 32});
        DAMAGE.put("Sweeping Beam", new int[]{6, 9});
        DAMAGE.put("Swift Strike", new int[]{7, 10});
        DAMAGE.put("TalkToTheHand", new int[]{5, 7});
        DAMAGE.put("ThroughViolence", new int[]{20, 30});
        DAMAGE.put("Thunder Strike", new int[]{7, 9});
        DAMAGE.put("Thunderclap", new int[]{4, 7});
        DAMAGE.put("Twin Strike", new int[]{5, 7});
        DAMAGE.put("Underhanded Strike", new int[]{12, 16});
        DAMAGE.put("Unload", new int[]{14, 18});
        DAMAGE.put("Wallop", new int[]{9, 12});
        DAMAGE.put("Weave", new int[]{4, 6});
        DAMAGE.put("WheelKick", new int[]{15, 20});
        DAMAGE.put("Whirlwind", new int[]{5, 8});
        DAMAGE.put("Wild Strike", new int[]{12, 17});
        DAMAGE.put("WindmillStrike", new int[]{7, 10});
        DAMAGE.put("Wish", new int[]{3, 4});
    }

    /** 升级后的费用；表里没有或未变化返回 {@code null}（调用方保留原值）。 */
    static int[] costChange(String id) {
        return COST.get(id);
    }

    /** 升级后的 baseDamage 变化；表里没有返回 {@code null}。 */
    static int[] damageChange(String id) {
        return DAMAGE.get(id);
    }

    static int costEntries() {
        return COST.size();
    }

    static int damageEntries() {
        return DAMAGE.size();
    }
}
