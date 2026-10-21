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

        // ---- 第三批：攻击/技能 -------------------------------------------------
        // 铁斩波：5 伤 + 5 格挡
        register("Iron Wave", Arrays.asList(Effects.damage(5), Effects.block(5)));
        register("Iron Wave+", Arrays.asList(Effects.damage(7), Effects.block(7)));

        // 交锋：14 伤（前置条件由游戏 canUse 判定，模拟侧只管效果）
        register("Clash", Effects.damage(14));
        register("Clash+", Effects.damage(18));

        register("Bludgeon", Effects.damage(32));
        register("Bludgeon+", Effects.damage(42));

        // 屠戮：20 伤（虚灵属性暂未建模，见 §待验）
        register("Carnage", Effects.damage(20));
        register("Carnage+", Effects.damage(28));

        // 连击：2 伤 x4
        register("Pummel", Effects.damageTimes(4, 2));
        register("Pummel+", Effects.damageTimes(5, 2));

        // 灼烧打击：12 伤（多次升级的成长曲线未建模，只支持 0/1 次升级）
        register("Searing Blow", Effects.damage(12));
        register("Searing Blow+", Effects.damage(16));

        register("Ghostly Armor", Effects.block(10));
        register("Ghostly Armor+", Effects.block(13));

        register("Impervious", Effects.block(30));
        register("Impervious+", Effects.block(40));

        // 上勾拳：13 伤 + 虚弱 + 易伤
        register("Uppercut", Arrays.asList(
                Effects.damage(13),
                Effects.powerOnTarget(DamageCalc.WEAKENED, 1),
                Effects.powerOnTarget(DamageCalc.VULNERABLE, 1)));
        register("Uppercut+", Arrays.asList(
                Effects.damage(13),
                Effects.powerOnTarget(DamageCalc.WEAKENED, 2),
                Effects.powerOnTarget(DamageCalc.VULNERABLE, 2)));

        // 威吓：全体虚弱
        register("Intimidate", Effects.powerOnAll(DamageCalc.WEAKENED, 1));
        register("Intimidate+", Effects.powerOnAll(DamageCalc.WEAKENED, 2));

        // 冲击波：全体虚弱 + 易伤
        register("Shockwave", Arrays.asList(
                Effects.powerOnAll(DamageCalc.WEAKENED, 3),
                Effects.powerOnAll(DamageCalc.VULNERABLE, 3)));
        register("Shockwave+", Arrays.asList(
                Effects.powerOnAll(DamageCalc.WEAKENED, 5),
                Effects.powerOnAll(DamageCalc.VULNERABLE, 5)));

        // 缴械：目标失去力量
        register("Disarm", Effects.powerOnTarget(DamageCalc.STRENGTH, -2));
        register("Disarm+", Effects.powerOnTarget(DamageCalc.STRENGTH, -3));

        // 壁垒 / 重击 / 自伤 / 回能
        register("Entrench", Effects.doubleBlock());
        register("Body Slam", Effects.damageEqualToBlock());
        register("Limit Break", Effects.doubleStrength());
        register("Heavy Blade", Effects.damageWithStrengthMult(14, 3));
        register("Heavy Blade+", Effects.damageWithStrengthMult(14, 5));

        register("Bloodletting", Arrays.asList(Effects.loseHp(3), Effects.gainEnergy(2)));
        register("Bloodletting+", Arrays.asList(Effects.loseHp(3), Effects.gainEnergy(3)));
        register("Seeing Red", Effects.gainEnergy(2));

        register("Offering", Arrays.asList(
                Effects.loseHp(6), Effects.gainEnergy(2), Effects.draw(3)));
        register("Offering+", Arrays.asList(
                Effects.loseHp(6), Effects.gainEnergy(2), Effects.draw(5)));

        // 血液沸腾：15 伤 + 自伤 2
        register("Hemokinesis", Arrays.asList(Effects.damage(15), Effects.loseHp(2)));
        register("Hemokinesis+", Arrays.asList(Effects.damage(20), Effects.loseHp(2)));

        // 收割：全体 4 伤 + 按实际伤害回血
        register("Reaper", Effects.damageAllAndHeal(4));
        register("Reaper+", Effects.damageAllAndHeal(5));

        // 硬撑：15 格挡 + 塞 2 张伤口进手牌
        register("Power Through", Arrays.asList(
                Effects.block(15), Effects.addStatusToHand("Wound", 2)));
        register("Power Through+", Arrays.asList(
                Effects.block(20), Effects.addStatusToHand("Wound", 2)));

        // 战斗专注：抽 3，然后本回合禁止再抽
        register("Battle Trance", Arrays.asList(
                Effects.draw(3), Effects.powerOnPlayer("No Draw", -1)));
        register("Battle Trance+", Arrays.asList(
                Effects.draw(4), Effects.powerOnPlayer("No Draw", -1)));

        // ---- 第三批：能力牌 ---------------------------------------------------
        register("Demon Form", Effects.powerOnPlayer("Demon Form", 2));
        register("Demon Form+", Effects.powerOnPlayer("Demon Form", 3));
        register("Barricade", Effects.powerOnPlayer("Barricade", 1));
        register("Corruption", Effects.powerOnPlayer("Corruption", 3));
        register("Dark Embrace", Effects.powerOnPlayer("Dark Embrace", 1));
        register("Evolve", Effects.powerOnPlayer("Evolve", 1));
        register("Evolve+", Effects.powerOnPlayer("Evolve", 2));
        register("Feel No Pain", Effects.powerOnPlayer("Feel No Pain", 3));
        register("Feel No Pain+", Effects.powerOnPlayer("Feel No Pain", 4));
        register("Fire Breathing", Effects.powerOnPlayer("Fire Breathing", 6));
        register("Fire Breathing+", Effects.powerOnPlayer("Fire Breathing", 10));
        register("Juggernaut", Effects.powerOnPlayer("Juggernaut", 5));
        register("Juggernaut+", Effects.powerOnPlayer("Juggernaut", 7));
        register("Rupture", Effects.powerOnPlayer("Rupture", 1));
        register("Rupture+", Effects.powerOnPlayer("Rupture", 2));
        register("Brutality", Effects.powerOnPlayer("Brutality", 1));
        register("Combust", Effects.powerOnPlayer("Combust", 5));
        register("Combust+", Effects.powerOnPlayer("Combust", 7));
        register("Double Tap", Effects.powerOnPlayer("Double Tap", 1));
        register("Double Tap+", Effects.powerOnPlayer("Double Tap", 2));
        register("Rage", Effects.powerOnPlayer("Rage", 3));
        register("Rage+", Effects.powerOnPlayer("Rage", 5));

        // ---- 第四批：消耗/条件/成长类 -----------------------------------------
        // 献祭：全体 21 伤 + 1 张灼伤进弃牌堆（插入位置确定，不需要随机数）
        register("Immolate", Arrays.asList(
                Effects.damageAll(21), Effects.addStatusToDiscard("Burn", 1)));
        register("Immolate+", Arrays.asList(
                Effects.damageAll(28), Effects.addStatusToDiscard("Burn", 1)));

        // 再生：消耗所有非攻击牌，每张 5 格挡
        register("Second Wind", Effects.exhaustNonAttacksForBlock(5));
        register("Second Wind+", Effects.exhaustNonAttacksForBlock(7));

        // 断魂斩：16 伤 + 消耗所有非攻击牌
        register("Sever Soul", Arrays.asList(
                Effects.damage(16), Effects.exhaustNonAttacks()));
        register("Sever Soul+", Arrays.asList(
                Effects.damage(22), Effects.exhaustNonAttacks()));

        // 恶魔之火：消耗整手牌，每张 7 点伤害
        register("Fiend Fire", Effects.exhaustHandForDamage(7));
        register("Fiend Fire+", Effects.exhaustHandForDamage(10));

        // 回旋踢：5 伤 + 抽 1；若目标易伤则 +2 能量 +1 抽牌
        register("Dropkick", Arrays.asList(
                Effects.damage(5), Effects.draw(1),
                Effects.ifTargetVulnerable(Effects.gainEnergy(2), Effects.draw(1))));
        register("Dropkick+", Arrays.asList(
                Effects.damage(8), Effects.draw(1),
                Effects.ifTargetVulnerable(Effects.gainEnergy(2), Effects.draw(1))));

        // 完美打击：6 伤 + 每张 Strike 牌 +2
        register("Perfected Strike", Effects.damagePlusPerStrike(6, 2));
        register("Perfected Strike+", Effects.damagePlusPerStrike(6, 3));
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
