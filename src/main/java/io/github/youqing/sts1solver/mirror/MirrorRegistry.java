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

        // ---- 第二批：数值全部取自 solver-data/cards.json（CardLibrary 导出）----
        // 愤怒：0 费 6 伤，打完后复制一份进弃牌堆
        register("Anger", Arrays.asList(Effects.damage(6), Effects.copySelfToDiscard()));
        register("Anger+", Arrays.asList(Effects.damage(8), Effects.copySelfToDiscard()));

        // 闪电霹雳：全体 4 伤 + 全体易伤 1（magic 字段为 -1，易伤固定 1 层）
        register("Thunderclap", Arrays.asList(
                Effects.damageAll(4),
                Effects.powerOnAll(DamageCalc.VULNERABLE, 1)));
        register("Thunderclap+", Arrays.asList(
                Effects.damageAll(7),
                Effects.powerOnAll(DamageCalc.VULNERABLE, 1)));

        // 双重打击：5 伤 x2
        register("Twin Strike", Effects.damageTimes(2, 5));
        register("Twin Strike+", Effects.damageTimes(2, 7));

        // 剑柄打击：9 伤 + 抽 1（magic 1 / 2）
        register("Pommel Strike", Arrays.asList(Effects.damage(9), Effects.draw(1)));
        register("Pommel Strike+", Arrays.asList(Effects.damage(10), Effects.draw(2)));

        // 耸肩无视：8 格挡 + 抽 1
        register("Shrug It Off", Arrays.asList(Effects.block(8), Effects.draw(1)));
        register("Shrug It Off+", Arrays.asList(Effects.block(11), Effects.draw(1)));

        // 火焰屏障：12 格挡 + 反弹 4（power "Flame Barrier"）
        register("Flame Barrier", Arrays.asList(
                Effects.block(12),
                Effects.powerOnPlayer("Flame Barrier", 4)));
        register("Flame Barrier+", Arrays.asList(
                Effects.block(16),
                Effects.powerOnPlayer("Flame Barrier", 6)));

        // 顺劈斩：全体 8 伤
        register("Cleave", Effects.damageAll(8));
        register("Cleave+", Effects.damageAll(11));

        // 金刚臂：12 伤 + 虚弱 2
        register("Clothesline", Arrays.asList(
                Effects.damage(12),
                Effects.powerOnTarget(DamageCalc.WEAKENED, 2)));
        register("Clothesline+", Arrays.asList(
                Effects.damage(14),
                Effects.powerOnTarget(DamageCalc.WEAKENED, 3)));

        // 燃烧：力量 +2
        register("Inflame", Effects.powerOnPlayer(DamageCalc.STRENGTH, 2));
        register("Inflame+", Effects.powerOnPlayer(DamageCalc.STRENGTH, 3));

        // 金属化：每回合末获得 3 格挡
        register("Metallicize", Effects.powerOnPlayer("Metallicize", 3));
        register("Metallicize+", Effects.powerOnPlayer("Metallicize", 4));

        // 活动肌肉：力量 +2，回合末失去（用两个 power 表达）
        register("Flex", Arrays.asList(
                Effects.powerOnPlayer(DamageCalc.STRENGTH, 2),
                Effects.powerOnPlayer("Flex", 2)));
        register("Flex+", Arrays.asList(
                Effects.powerOnPlayer(DamageCalc.STRENGTH, 4),
                Effects.powerOnPlayer("Flex", 4)));
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
