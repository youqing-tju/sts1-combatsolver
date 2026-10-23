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

        // 活动肌肉（Flex）：力量 +2，回合末失去
        // 🔴 能力名必须用原版 ID "Gain Strength"（GainStrengthPower.POWER_ID）——
        //    SimState.capture() 用 pw.ID 作 key，写 "Flex" 会对拍失配。
        //    这个 power 的 atEndOfTurn 会减去 amount 点力量。
        register("Flex", Arrays.asList(
                Effects.powerOnPlayer(DamageCalc.STRENGTH, 2),
                Effects.powerOnPlayer("Gain Strength", 2)));
        register("Flex+", Arrays.asList(
                Effects.powerOnPlayer(DamageCalc.STRENGTH, 4),
                Effects.powerOnPlayer("Gain Strength", 4)));

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
        // 卡面写了 Exhaust，之前误用 register
        registerExhaust("Pummel", Effects.damageTimes(4, 2));
        registerExhaust("Pummel+", Effects.damageTimes(5, 2));

        // 灼烧打击：12 伤（多次升级的成长曲线未建模，只支持 0/1 次升级）
        register("Searing Blow", Effects.damage(12));
        register("Searing Blow+", Effects.damage(16));

        register("Ghostly Armor", Effects.block(10));
        register("Ghostly Armor+", Effects.block(13));

        registerExhaust("Impervious", Effects.block(30));
        registerExhaust("Impervious+", Effects.block(40));

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
        // 升级只加层数，消耗标记不变
        registerExhaust("Intimidate", Effects.powerOnAll(DamageCalc.WEAKENED, 1));
        registerExhaust("Intimidate+", Effects.powerOnAll(DamageCalc.WEAKENED, 2));

        // 冲击波：全体虚弱 + 易伤
        registerExhaust("Shockwave", Arrays.asList(
                Effects.powerOnAll(DamageCalc.WEAKENED, 3),
                Effects.powerOnAll(DamageCalc.VULNERABLE, 3)));
        registerExhaust("Shockwave+", Arrays.asList(
                Effects.powerOnAll(DamageCalc.WEAKENED, 5),
                Effects.powerOnAll(DamageCalc.VULNERABLE, 5)));

        // 缴械：目标失去力量
        registerExhaust("Disarm", Effects.powerOnTarget(DamageCalc.STRENGTH, -2));
        registerExhaust("Disarm+", Effects.powerOnTarget(DamageCalc.STRENGTH, -3));

        // 壁垒 / 重击 / 自伤 / 回能
        //
        // ⚠️ 升级态必须逐个注册：镜像是往表里放「牌名 → 效果」，查不到就是**打不出这张牌**，
        // 搜索会当成手牌里没有它 —— 真机上的表现是「手里有牌却结束回合」。
        // 2026-10-23 真机踩过：Body Slam+ / Dark Embrace+ / Entrench+ 三张全缺，
        // AI 拿着 0 费 Body Slam+ 直接结束回合。
        //
        // 这几张的升级只改**费用**（费用来自 SimCard，不进镜像），或只加 Innate（开局关键字），
        // 所以效果体可以直接复用基础版。文本依据：jar 内 localization/eng/cards.json。
        register("Entrench", Effects.doubleBlock());
        register("Entrench+", Effects.doubleBlock());          // 2 费 -> 1 费
        register("Body Slam", Effects.damageEqualToBlock());
        register("Body Slam+", Effects.damageEqualToBlock());  // 1 费 -> 0 费
        registerExhaust("Limit Break", Effects.doubleStrength());
        register("Limit Break+", Effects.doubleStrength());    // 升级**去掉 Exhaust**（不再是消耗牌）
        register("Heavy Blade", Effects.damageWithStrengthMult(14, 3));
        register("Heavy Blade+", Effects.damageWithStrengthMult(14, 5));

        register("Bloodletting", Arrays.asList(Effects.loseHp(3), Effects.gainEnergy(2)));
        register("Bloodletting+", Arrays.asList(Effects.loseHp(3), Effects.gainEnergy(3)));
        registerExhaust("Seeing Red", Effects.gainEnergy(2));
        registerExhaust("Seeing Red+", Effects.gainEnergy(2));  // 1 费 -> 0 费，仍然消耗

        registerExhaust("Offering", Arrays.asList(
                Effects.loseHp(6), Effects.gainEnergy(2), Effects.draw(3)));
        registerExhaust("Offering+", Arrays.asList(
                Effects.loseHp(6), Effects.gainEnergy(2), Effects.draw(5)));

        // 血液沸腾：15 伤 + 自伤 2
        register("Hemokinesis", Arrays.asList(Effects.damage(15), Effects.loseHp(2)));
        register("Hemokinesis+", Arrays.asList(Effects.damage(20), Effects.loseHp(2)));

        // 收割：全体 4 伤 + 按实际伤害回血
        registerExhaust("Reaper", Effects.damageAllAndHeal(4));
        registerExhaust("Reaper+", Effects.damageAllAndHeal(5));

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
        register("Barricade+", Effects.powerOnPlayer("Barricade", 1));      // 3 费 -> 2 费
        register("Corruption", Effects.powerOnPlayer("Corruption", 3));
        register("Corruption+", Effects.powerOnPlayer("Corruption", 3));    // 3 费 -> 2 费
        register("Dark Embrace", Effects.powerOnPlayer("Dark Embrace", 1));
        register("Dark Embrace+", Effects.powerOnPlayer("Dark Embrace", 1));// 2 费 -> 1 费
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
        register("Brutality+", Effects.powerOnPlayer("Brutality", 1));      // 升级只加 Innate（开局）
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
        registerExhaust("Fiend Fire", Effects.exhaustHandForDamage(7));
        registerExhaust("Fiend Fire+", Effects.exhaustHandForDamage(10));

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

        // ---- 第五批：随机位置 / 随机目标（用游戏自带的种子随机，可复现） --------
        // 野性打击：12/17 伤 + 1 张伤口随机插入抽牌堆
        register("Wild Strike", Arrays.asList(
                Effects.damage(12), Effects.insertIntoDrawPileRandom("Wound", 1)));
        register("Wild Strike+", Arrays.asList(
                Effects.damage(17), Effects.insertIntoDrawPileRandom("Wound", 1)));

        // 鲁莽冲锋：7/10 伤 + 1 张眩晕随机插入抽牌堆
        register("Reckless Charge", Arrays.asList(
                Effects.damage(7), Effects.insertIntoDrawPileRandom("Dazed", 1)));
        register("Reckless Charge+", Arrays.asList(
                Effects.damage(10), Effects.insertIntoDrawPileRandom("Dazed", 1)));

        // 剑刃回旋：3/4 段 3 伤，目标随机
        register("Sword Boomerang", Effects.damageRandomEnemies(3, 3));
        register("Sword Boomerang+", Effects.damageRandomEnemies(4, 3));

        // ---- 第六批：X 费 ---------------------------------------------------
        // 旋风斩：每点能量全体 5/8 伤；X = 出牌时的全部能量
        register("Whirlwind", Effects.damageAllX(5));
        register("Whirlwind+", Effects.damageAllX(8));

        // ---- 第七批：选牌类 ---------------------------------------------------
        // 军备：5 格挡 + 升级一张手牌；升级版升级全部
        register("Armaments", Arrays.asList(Effects.block(5), Effects.upgradeChosen()));
        register("Armaments+", Arrays.asList(Effects.block(5), Effects.upgradeAllHand()));

        // 烧牌契约：消耗一张手牌，然后抽 2/3
        register("Burning Pact", Effects.exhaustChosenThenDraw(2));
        register("Burning Pact+", Effects.exhaustChosenThenDraw(3));

        // 战吼：抽 1/2，然后把一张手牌放回抽牌堆顶，**自身消耗**
        // （字节码：Warcry 构造器里 putfield exhaust:Z = 1；Burning Pact 没有）
        registerExhaust("Warcry", Effects.drawThenPutChosenOnTop(1));
        registerExhaust("Warcry+", Effects.drawThenPutChosenOnTop(2));

        // ---- 第八批：条件/收益类 ---------------------------------------------
        // 啃食：10/12 伤，击杀则最大生命 +3/+4
        registerExhaust("Feed", Effects.damageAndMaxHpOnKill(10, 3));
        registerExhaust("Feed+", Effects.damageAndMaxHpOnKill(12, 4));

        // 观察弱点：目标本回合意图为攻击时 +3/+4 力量
        register("Spot Weakness", Effects.strengthIfEnemyAttacks(3));
        register("Spot Weakness+", Effects.strengthIfEnemyAttacks(4));

        // ---- 第九批：无色牌（数值取自 cards.json，COLORLESS） -------------------
        // 戏剧登场：0 费 8/12 伤，消耗
        registerExhaust("Dramatic Entrance", Effects.damage(8));
        registerExhaust("Dramatic Entrance+", Effects.damage(12));

        // 钢铁闪光：0 费 3/6 伤 + 抽 1
        register("Flash of Steel", Arrays.asList(Effects.damage(3), Effects.draw(1)));
        register("Flash of Steel+", Arrays.asList(Effects.damage(6), Effects.draw(1)));

        // 良好直觉：0 费 6/9 格挡
        register("Good Instincts", Effects.block(6));
        register("Good Instincts+", Effects.block(9));

        // 迅捷打击：0 费 7/10 伤
        register("Swift Strike", Effects.damage(7));
        register("Swift Strike+", Effects.damage(10));

        // 匕首：0 费 4/6 伤，消耗
        registerExhaust("Shiv", Effects.damage(4));
        registerExhaust("Shiv+", Effects.damage(6));

        // 重击：1 费 12/16 伤，消耗
        registerExhaust("Smite", Effects.damage(12));
        registerExhaust("Smite+", Effects.damage(16));

        // 致盲：2 层虚弱（原版单体，升级版全体）
        register("Blind", Effects.powerOnTarget(DamageCalc.WEAKENED, 2));
        register("Blind+", Effects.powerOnAll(DamageCalc.WEAKENED, 2));

        // 绊倒：2 层易伤（原版单体，升级版全体）
        register("Trip", Effects.powerOnTarget(DamageCalc.VULNERABLE, 2));
        register("Trip+", Effects.powerOnAll(DamageCalc.VULNERABLE, 2));

        // 黑暗镣铐：0 费，目标 -9/-15 力量，消耗
        registerExhaust("Dark Shackles", Effects.powerOnTarget(DamageCalc.STRENGTH, -9));
        registerExhaust("Dark Shackles+", Effects.powerOnTarget(DamageCalc.STRENGTH, -15));

        // 技巧：0 费 2/4 格挡 + 抽 1，消耗
        registerExhaust("Finesse", Arrays.asList(Effects.block(2), Effects.draw(1)));
        registerExhaust("Finesse+", Arrays.asList(Effects.block(4), Effects.draw(1)));

        // 包扎：0 费治疗 4/6，消耗
        registerExhaust("Bandage Up", Effects.heal(4));
        registerExhaust("Bandage Up+", Effects.heal(6));

        // 战略大师：0 费抽 3/4，消耗
        registerExhaust("Master of Strategy", Effects.draw(3));
        registerExhaust("Master of Strategy+", Effects.draw(4));

        // 咬：7/8 伤 + 治疗 2/3
        register("Bite", Arrays.asList(Effects.damage(7), Effects.heal(2)));
        register("Bite+", Arrays.asList(Effects.damage(8), Effects.heal(3)));

        // 心灵爆震：伤害 = 抽牌堆张数
        register("Mind Blast", Effects.damageEqualToDrawPile());
        register("Mind Blast+", Effects.damageEqualToDrawPile());

        // 急躁：手牌无攻击牌时抽 2/3
        register("Impatience", Effects.drawIfNoAttackInHand(2));
        register("Impatience+", Effects.drawIfNoAttackInHand(3));

        // 万灵药：获得 1/2 层人工制品，消耗
        registerExhaust("Panacea", Effects.powerOnPlayer("Artifact", 1));
        registerExhaust("Panacea+", Effects.powerOnPlayer("Artifact", 2));

        // ---- 第十批：补齐红卡剩余可做的两张 -----------------------------------
        // 狂战：自身获得 2/1 易伤，每回合额外 1 能量（power 层数固定为 1）
        register("Berserk", Arrays.asList(
                Effects.powerOnPlayer(DamageCalc.VULNERABLE, 2),
                Effects.powerOnPlayer("Berserk", 1)));
        register("Berserk+", Arrays.asList(
                Effects.powerOnPlayer(DamageCalc.VULNERABLE, 1),
                Effects.powerOnPlayer("Berserk", 1)));

        // 真坚毅：7/9 格挡 + 消耗一张手牌（原版随机，升级版自选）
        register("True Grit", Arrays.asList(Effects.block(7), Effects.exhaustRandomHandCard()));
        register("True Grit+", Arrays.asList(Effects.block(9), Effects.exhaustChosen()));

        // 洞见：0 费抽 2/3，消耗
        registerExhaust("Insight", Effects.draw(2));
        registerExhaust("Insight+", Effects.draw(3));

        // ---- 第十一批：补齐 RED 剩余机制 ---------------------------------------
        // 双持：复制一张手牌 1/2 张（原版已升级版复制 2 张）
        register("Dual Wield", Effects.copyChosenToHand(1));
        register("Dual Wield+", Effects.copyChosenToHand(2));

        // 发掘：从消耗堆取回一张牌
        registerDrawFromExhaust("Exhume", java.util.Arrays.<Effect>asList(
                Effects.retrieveFromExhaust()));
        registerExhaust("Exhume+", Arrays.asList(Effects.retrieveFromExhaust()));

        // 头槌：9/12 伤 + 弃牌堆一张放回牌堆顶
        registerDrawFromDiscard("Headbutt", java.util.Arrays.<Effect>asList(
                Effects.damageThenTopFromDiscard(9)));
        registerDrawFromDiscard("Headbutt+", java.util.Arrays.<Effect>asList(
                Effects.damageThenTopFromDiscard(12)));

        // 哨兵：5/8 格挡（被消耗时回 2 能量，见 Effects.exhaustCard）
        register("Sentinel", Effects.block(5));
        register("Sentinel+", Effects.block(8));

        // 浴血奋战：18/22 伤，本场每损失一次 HP 费用 -1（最低 0）
        registerHpCosted("Blood for Blood", Effects.damage(18));
        registerHpCosted("Blood for Blood+", Effects.damage(22));

        // 破灭：打出抽牌堆顶那张，然后消耗它
        register("Havoc", Effects.playTopOfDrawPile());
        register("Havoc+", Effects.playTopOfDrawPile());

        // 暴走：伤害用「卡面伤害」——游戏每次打出都会把 +5 写进 baseDamage，
        // 捕获时读出来即可，不需要我们自己维护成长计数器。
        register("Rampage", Effects.damageEqualToCardBase());
        register("Rampage+", Effects.damageEqualToCardBase());

        // 地狱之刃：随机一张铁甲战士攻击牌加入手牌，本回合 0 费（升级版给升级牌）
        // 地狱之刃：卡面写了 Exhaust，之前误用 register（进弃牌堆而不是消耗堆）
        registerExhaust("Infernal Blade", Effects.addRandomAttackToHand(false));
        registerExhaust("Infernal Blade+", Effects.addRandomAttackToHand(true));

        // ---- 第十二批：无色牌（文本取自游戏内 localization/eng/cards.json） --------
        // 神化：升级所有牌堆里的每一张牌，消耗
        registerExhaust("Apotheosis", Effects.upgradeAllPiles());
        registerExhaust("Apotheosis+", Effects.upgradeAllPiles());

        // 测试版：随机把一张欧米茄洗进抽牌堆，消耗
        registerExhaust("Beta", Effects.insertIntoDrawPileRandom("Omega", 1));
        registerExhaust("Beta+", Effects.insertIntoDrawPileRandom("Omega", 1));

        // 幽灵形态：获得 1 层无实体，消耗（虚灵未建模）
        registerExhaust("Ghostly", Effects.powerOnPlayer("Intangible", 1));
        registerExhaust("Ghostly+", Effects.powerOnPlayer("Intangible", 1));

        // 神迹：回复 1 点能量，消耗
        registerExhaust("Miracle", Effects.gainEnergy(1));

        // 安全：12/16 格挡，消耗（保留未建模）
        registerExhaust("Safety", Effects.block(12));
        registerExhaust("Safety+", Effects.block(16));

        // 以暴制暴：20/30 伤，消耗
        registerExhaust("ThroughViolence", Effects.damage(20));
        registerExhaust("ThroughViolence+", Effects.damage(30));

        // 黏液：没有效果，只消耗
        registerExhaust("Slimed", Effects.nothing());

        // J.A.X.：失去 3 点生命，获得 2/3 力量
        register("J.A.X.", Arrays.asList(Effects.loseHp(3), Effects.powerOnPlayer(DamageCalc.STRENGTH, 2)));
        register("J.A.X.+", Arrays.asList(Effects.loseHp(3), Effects.powerOnPlayer(DamageCalc.STRENGTH, 3)));

        // 提前思考：抽 2 后把一张手牌放回牌堆顶，消耗
        registerExhaust("Thinking Ahead", Effects.drawThenPutChosenOnTop(2));
        registerExhaust("Thinking Ahead+", Effects.drawThenPutChosenOnTop(2));

        // 预谋：把手牌一张放到底部（费用归零未建模）
        register("Forethought", Effects.putChosenOnBottom());
        register("Forethought+", Effects.putChosenOnBottom());

        // 启蒙：手牌费用全部降到 1
        // 启蒙：原版「本回合降到 1」，升级版「本场降到 1」
        register("Enlightenment", Effects.reduceHandCostsTo(1, false));
        register("Enlightenment+", Effects.reduceHandCostsTo(1, true));

        // 疯狂：随机一张手牌费用归零，消耗
        registerExhaust("Madness", Effects.freeRandomHandCard());
        registerExhaust("Madness+", Effects.freeRandomHandCard());

        // 恐慌按钮：30/40 格挡，接下来 2 回合不能用牌获得格挡，消耗
        registerExhaust("PanicButton", Arrays.asList(
                Effects.block(30), Effects.powerOnPlayer("No Block", 2)));
        registerExhaust("PanicButton+", Arrays.asList(
                Effects.block(40), Effects.powerOnPlayer("No Block", 2)));

        // 仪式匕首：15 伤，击杀则永久 +3/+5（用卡面伤害，捕获时自动读到），消耗
        registerExhaust("RitualDagger", Effects.damageEqualToCardBase());
        registerExhaust("RitualDagger+", Effects.damageEqualToCardBase());

        // 贪婪之手：20/25 伤，击杀获得金币
        register("HandOfGreed", Effects.damageAndGoldOnKill(20, 20));
        register("HandOfGreed+", Effects.damageAndGoldOnKill(25, 25));

        // 秘密技巧 / 秘密武器：从抽牌堆取一张技能/攻击，消耗
        registerExhaust("Secret Technique", Effects.retrieveChosenFromDraw(false));
        registerExhaust("Secret Technique+", Effects.retrieveChosenFromDraw(false));
        registerExhaust("Secret Weapon", Effects.retrieveChosenFromDraw(true));
        registerExhaust("Secret Weapon+", Effects.retrieveChosenFromDraw(true));

        // 暴力：从抽牌堆随机取 3/4 张攻击牌，消耗
        registerExhaust("Violence", Effects.randomAttacksFromDrawToHand(3));
        registerExhaust("Violence+", Effects.randomAttacksFromDrawToHand(4));

        // 十八般武艺：随机 1/2 张无色牌进手牌，消耗
        registerExhaust("Jack Of All Trades", Effects.addRandomColorlessToHand(1, false));
        registerExhaust("Jack Of All Trades+", Effects.addRandomColorlessToHand(2, false));

        // 转化：X 费，随机 X 张无色牌进手牌，本回合 0 费，消耗
        registerExhaust("Transmutation", Effects.addRandomColorlessXToHand());

        // 蜕变：洗 3/5 张随机攻击进抽牌堆，本场 0 费，消耗
        registerExhaust("Metamorphosis", Effects.shuffleRandomAttacksIntoDraw(3));
        registerExhaust("Metamorphosis+", Effects.shuffleRandomAttacksIntoDraw(5));

        // 深呼吸：弃牌堆洗回抽牌堆 + 洗抽牌堆 + 抽 1/2 张
        // （两次 randomLong：EmptyDeckShuffleAction 一次、ShuffleAction 一次）
        register("Deep Breath", Effects.deepBreath(1));
        register("Deep Breath+", Effects.deepBreath(2));

        // 结茧：3/5 张随机技能牌洗进抽牌堆，本场 0 费，消耗
        registerExhaust("Chrysalis", Effects.chrysalis(3));
        registerExhaust("Chrysalis+", Effects.chrysalis(5));

        // 发现：3 选 1 进手牌、本回合 0 费；升级版不消耗
        registerExhaust("Discovery", Effects.discovery());
        register("Discovery+", Effects.discovery());

        // 净化：最多 3/5 张手牌消耗，自身消耗
        registerExhaust("Purity", Effects.exhaustUpTo(3));
        registerExhaust("Purity+", Effects.exhaustUpTo(5));

        // ---- 第四批：靠「回合钩子引擎」才能生效的能力/延迟牌 --------------------
        // 数值全部取自 solver-data/cards.json，power ID 取自 powers 包的 POWER_ID 常量。

        // 欧米伽：回合末对全体造成 50/60（层数就是伤害）
        register("Omega", Effects.powerOnPlayer("OmegaPower", 50));
        register("Omega+", Effects.powerOnPlayer("OmegaPower", 60));

        // 炸弹：3 回合后对全体造成 40/50。层数 = 回合数，伤害在私有字段里
        register("The Bomb", Effects.powerOnPlayerAux("TheBomb", 3, "TheBomb.damage", 40));
        register("The Bomb+", Effects.powerOnPlayerAux("TheBomb", 3, "TheBomb.damage", 50));

        // 磁力：每回合初把 amount 张随机无色牌塞进手牌
        register("Magnetism", Effects.powerOnPlayer("Magnetism", 1));
        register("Magnetism+", Effects.powerOnPlayer("Magnetism", 1));

        // 乱战：每回合初自动打出抽牌堆顶
        register("Mayhem", Effects.powerOnPlayer("Mayhem", 1));
        register("Mayhem+", Effects.powerOnPlayer("Mayhem", 1));

        // 神气制胜：每打出 5 张牌对全体造成 10/14。层数 = 剩余张数，伤害在私有字段
        register("Panache", Effects.powerOnPlayerAux("Panache", 5, "Panache.damage", 10));
        register("Panache+", Effects.powerOnPlayerAux("Panache", 5, "Panache.damage", 14));

        // 残虐天性：给敌人挂减益时造成 5/7 伤害
        register("Sadistic Nature", Effects.powerOnPlayer("Sadistic", 5));
        register("Sadistic Nature+", Effects.powerOnPlayer("Sadistic", 7));
    }

    private MirrorRegistry() {
    }

    private static void register(String key, Effect... effects) {
        register(key, Arrays.asList(effects));
    }

    private static void register(String key, java.util.List<Effect> effects) {
        CARDS.put(key, new CardMirror(key, effects));
    }

    /** 选牌来自弃牌堆的牌（头槌）。 */
    private static void registerDrawFromDiscard(String key, java.util.List<Effect> effects) {
        CARDS.put(key, new CardMirror(key, effects, false, CardMirror.ChoicePile.DISCARD, false));
    }

    /** 选牌来自消耗堆的牌（发掘）。 */
    private static void registerDrawFromExhaust(String key, java.util.List<Effect> effects) {
        CARDS.put(key, new CardMirror(key, effects, false, CardMirror.ChoicePile.EXHAUST, false));
    }

    /** 费用随损血下降的牌（浴血奋战）。 */
    private static void registerHpCosted(String key, Effect... effects) {
        CARDS.put(key, new CardMirror(key, Arrays.asList(effects), false,
                CardMirror.ChoicePile.HAND, true));
    }

    /** 打出后进消耗堆的牌。 */
    private static void registerExhaust(String key, Effect... effects) {
        registerExhaust(key, Arrays.asList(effects));
    }

    private static void registerExhaust(String key, java.util.List<Effect> effects) {
        CARDS.put(key, new CardMirror(key, effects, true));
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
