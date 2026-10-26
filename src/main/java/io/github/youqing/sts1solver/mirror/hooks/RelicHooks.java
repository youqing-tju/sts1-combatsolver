package io.github.youqing.sts1solver.mirror.hooks;

import io.github.youqing.sts1solver.mirror.DamageCalc;
import io.github.youqing.sts1solver.sim.SimCard;
import io.github.youqing.sts1solver.sim.SimMonster;
import io.github.youqing.sts1solver.sim.SimState;

/**
 * 计数类遗物的战斗内触发：修女、装饰扇、手里剑、笔尖、开信刀、墨水瓶。
 *
 * <p>原版都是 {@code AbstractRelic.onUseCard(card, action)} 里对 {@code counter} 自增，
 * 达到阈值就归零并触发。阈值与效果全部来自字节码，不是卡面描述：
 *
 * <pre>
 * Nunchaku        攻击 10 次 → 回 1 能量
 * Ornamental Fan  攻击 3 次  → 4 格挡（GainBlockAction(owner, owner, 4)，不吃敏捷）
 * Shuriken        攻击 3 次  → 力量 +1
 * Pen Nib         攻击 10 次 → 归零；第 10 次（计数为 9 时）那一击伤害翻倍
 * Letter Opener   技能 3 次  → 对全体 5 点伤害（THORNS，不吃力量/易伤）
 * InkBottle       **任意**牌 10 张 → 归零并抽 1 张（不分牌型，见 onUseCard 字节码）
 * </pre>
 *
 * <p>{@code state.relics} 里存的就是原版 {@code AbstractRelic.counter}，所以对拍能直接比。
 */
public final class RelicHooks {

    private RelicHooks() {
    }

    // ------------------------------------------------------------------ 第二批：被动 / 回合钩子

    /**
     * 纸青蛙（{@code PaperFrog}）。🔴 类名是 {@code PaperFrog}、**ID 是 {@code "Paper Frog"}**
     * （和显示的 "Paper Phrog" 都不是一个拼法，按描述写必然查不到）。
     *
     * <p>它没有自己的钩子方法，只是一个 {@code public static final float VULN_EFFECTIVENESS}
     * 被伤害管线读 —— 所以它**只放大「玩家打敌人」的易伤倍率**（1.5 → 1.75），
     * 敌人打玩家的易伤仍然是 1.5。
     */
    public static final String PAPER_FROG = "Paper Frog";

    /** 水银沙漏（{@code MercuryHourglass}）：玩家回合开始对全体 3 点纯伤。 */
    public static final String MERCURY_HOURGLASS = "Mercury Hourglass";
    /** 号角夹板（{@code HornCleat}）：第 2 个回合开始 +14 格挡，只一次。 */
    public static final String HORN_CLEAT = "HornCleat";
    /** 舵轮（{@code CaptainsWheel}）：第 3 个回合开始 +18 格挡，只一次。 */
    public static final String CAPTAINS_WHEEL = "CaptainsWheel";
    /** 山铜（{@code Orichalcum}）：回合结束时若**没有格挡**，+6 格挡。 */
    public static final String ORICHALCUM = "Orichalcum";
    /** 鸟居（{@code Torii}）：受到的攻击伤害若是 2..5，改成 1。 */
    public static final String TORII = "Torii";
    /** 钨棒（{@code TungstenRod}）：每次掉血（含 HP_LOSS）减 1。 */
    public static final String TUNGSTEN_ROD = "TungstenRod";

    // ---- 第三批：受伤触发 / 手牌保留（2026-10-26）----

    /** 符文立方（{@code Runic Cube}）：每次掉血抽 1 张。 */
    public static final String RUNIC_CUBE = "Runic Cube";
    /** 百夫长谜题（{@code Centennial Puzzle}）：每场战斗**第一次**掉血抽 3 张。 */
    public static final String CENTENNIAL_PUZZLE = "Centennial Puzzle";
    /** 自形成黏土（{@code Self Forming Clay}）：每次掉血给**下回合** 3 点格挡。 */
    public static final String SELF_FORMING_CLAY = "Self Forming Clay";
    /** 红骷髅（{@code Red Skull}）：血量掉到一半及以下时 +3 力量，回到一半以上时收回。 */
    public static final String RED_SKULL = "Red Skull";
    /** 符文金字塔（{@code Runic Pyramid}）：回合结束**不弃手牌**。 */
    public static final String RUNIC_PYRAMID = "Runic Pyramid";
    /** {@code NextTurnBlockPower} 的运行时 ID（自形成黏土用）。 */
    public static final String NEXT_TURN_BLOCK = "Next Turn Block";

    /** {@code CentennialPuzzle.wasHPLost}: {@code DrawCardAction(player, 3)}。 */
    private static final int CENTENNIAL_DRAW = 3;
    /** {@code SelfFormingClay.wasHPLost}: {@code NextTurnBlockPower(player, 3, name)}。 */
    private static final int SELF_FORMING_CLAY_BLOCK = 3;
    /** {@code RedSkull}: {@code StrengthPower(player, 3)} / {@code (player, -3)}。 */
    private static final int RED_SKULL_STRENGTH = 3;

    // ---- 第四批：消耗 / 洗牌 / 击杀 / 回血 / 格挡衰减 / 药水（2026-10-26）----

    /** 灰烬之主（{@code CharonsAshes}）：每消耗一张牌，对全体 3 点纯伤。 */
    public static final String CHARONS_ASHES = "Charon's Ashes";
    /** 日晷（{@code Sundial}）：每洗牌 3 次回 2 能量。 */
    public static final String SUNDIAL = "Sundial";
    /** 小鬼号角（{@code GremlinHorn}）：每击杀一只怪回 1 能量、抽 1 张。 */
    public static final String GREMLIN_HORN = "Gremlin Horn";
    /** 魔法花（{@code MagicFlower}）：战斗中所有回血 ×1.5。 */
    public static final String MAGIC_FLOWER = "Magic Flower";
    /** 卡尺（{@code Calipers}）：回合结束格挡只掉 15，不是全清。 */
    public static final String CALIPERS = "Calipers";
    /** 玩具鸟翼飞机（{@code ToyOrnithopter}）：每喝一瓶药回 5 血。 */
    public static final String TOY_ORNITHOPTER = "Toy Ornithopter";

    /** {@code CharonsAshes.onExhaust}: {@code createDamageMatrix(3, true)}。 */
    private static final int CHARONS_ASHES_DAMAGE = 3;
    /** {@code Sundial.onShuffle}: {@code counter == 3} → {@code counter = 0} + {@code GainEnergyAction(2)}。 */
    private static final int SUNDIAL_SHUFFLES = 3;
    private static final int SUNDIAL_ENERGY = 2;
    /** {@code Calipers}: {@code loseBlock(15)}。 */
    private static final int CALIPERS_DECAY = 15;
    /** {@code ToyOrnithopter.onUsePotion}: {@code HealAction(player, player, 5)}。 */
    private static final int TOY_ORNITHOPTER_HEAL = 5;

    // ---- 第五批：回合计数 / 能力回血 / 减益免疫（2026-10-26）----

    /** 苦无（{@code Kunai}）：**本回合**每 3 张攻击牌 +1 敏捷（`atTurnStart` 清计数）。 */
    public static final String KUNAI = "Kunai";
    /** 快乐花（{@code HappyFlower}）：每 3 个回合开始 +1 能量。 */
    public static final String HAPPY_FLOWER = "Happy Flower";
    /** 鸟面罐（{@code BirdFacedUrn}）：每打出一张**能力牌**回 2 血。 */
    public static final String BIRD_FACED_URN = "Bird Faced Urn";
    /** 姜（{@code Ginger}）：免疫虚弱（{@code ApplyPowerAction} 直接把 Weakened 整条拦掉）。 */
    public static final String GINGER = "Ginger";
    /** 萝卜（{@code Turnip}）：免疫脆弱（同上，拦的是 Frail）。 */
    public static final String TURNIP = "Turnip";

    /** {@code HappyFlower.atTurnStart}: {@code counter == 3} → {@code counter = 0} + {@code GainEnergyAction(1)}。 */
    private static final int HAPPY_FLOWER_TURNS = 3;
    /** {@code BirdFacedUrn.onUseCard}: {@code HealAction(player, player, 2)}。 */
    private static final int BIRD_FACED_URN_HEAL = 2;

    /**
     * `atTurnStart` 里把 `counter` **清零**的四只计数遗物。
     *
     * <p>🔴 它们数的是「**本回合**打了几张」，<b>不是整场累计</b>。以前影子只在 onUseCard 里
     * 自增、从不清零，于是搜索跨回合推演时会「2 回合各打 2 张 → 误判成攒够 3 张」。
     */
    private static final String[] TURN_RESET_COUNTERS = {
            "Shuriken", "Ornamental Fan", "Letter Opener", KUNAI
    };

    /** {@code MercuryHourglass}: {@code createDamageMatrix(3, true)}。 */
    private static final int MERCURY_DAMAGE = 3;
    /** {@code HornCleat}: {@code GainBlockAction(player, player, 14)}，触发回合 2。 */
    private static final int HORN_CLEAT_BLOCK = 14;
    /** {@code CaptainsWheel}: {@code GainBlockAction(player, player, 18)}，触发回合 3。 */
    private static final int CAPTAINS_WHEEL_BLOCK = 18;
    /** {@code Orichalcum.BLOCK_AMT = 6}。 */
    private static final int ORICHALCUM_BLOCK = 6;
    /** {@code Torii}: 门槛 5、下限 1（{@code damage > 1 && damage <= 5 → 1}）。 */
    private static final int TORII_THRESHOLD = 5;

    /**
     * 「玩家打敌人」的易伤倍率：有纸青蛙 1.75，否则 1.5。
     *
     * <p>原版是 {@code 1.0f + PaperFrog.VULN_EFFECTIVENESS}，那是个 float 常量；
     * 我们按「整数百分比 × 伤害 → floor」逐点算，和 {@code (int)Math.floor(dmg * 1.75f)} 等价
     * （1.75 在 float 里是精确值，不会引入误差）。
     */
    public static float vulnerableMultiplier(SimState state) {
        return state != null && state.relics.containsKey(PAPER_FROG) ? 1.75f : 1.5f;
    }

    /**
     * 鸟居（{@code Torii.onAttacked}）：{@code damage > 1 && damage <= 5} → 1。
     *
     * <p>字节码还排除了 {@code HP_LOSS} 与 {@code THORNS} 两种类型和「没有 owner」的伤害
     * （即只认有攻击者的普通攻击）—— 我们只在 {@code CombatFlow.hitPlayer} 的攻击分支调用它。
     */
    public static int torii(SimState state, int damage) {
        if (state == null || !state.relics.containsKey(TORII)) {
            return damage;
        }
        return damage > 1 && damage <= TORII_THRESHOLD ? 1 : damage;
    }

    /**
     * 钨棒（{@code TungstenRod.onLoseHpLast}）：{@code if (amount > 0) amount - 1}。
     *
     * <p>🔴 它挂在 {@code AbstractPlayer.damage()} 的**末尾**，遍历遗物那一段没有任何
     * {@code DamageType} 分支 —— 所以**掉血（HP_LOSS）也减 1**，不只是普通攻击。
     * （先前按「只作用于 damage() 不管 loseHp」记的结论是错的：{@code LoseHPAction}
     * 本身就是 {@code target.damage(new DamageInfo(..., HP_LOSS))}。）
     */
    public static int tungstenRod(SimState state, int amount) {
        if (state == null || !state.relics.containsKey(TUNGSTEN_ROD)) {
            return amount;
        }
        return amount > 0 ? amount - 1 : amount;
    }

    /**
     * 受伤类遗物：原版 {@code AbstractPlayer.damage()} 里 {@code if (damage > 0)} 之后
     * 统一遍历 {@code AbstractRelic.wasHPLost(int)} 的那一段。
     *
     * <pre>
     * Runic Cube          → DrawCardAction(player, 1)
     * Centennial Puzzle   → 每场第一次：DrawCardAction(player, 3) + usedThisCombat = true
     * Self Forming Clay   → ApplyPowerAction(player, player, NextTurnBlockPower(player, 3))
     * </pre>
     *
     * <p>🔴 {@code CentennialPuzzle} 的「用过没有」是 {@code private static boolean usedThisCombat}，
     * 快照里没有这个字段 —— 影子借 {@code state.relics} 里那条记录当标记（{@code > 0} = 已用）。
     * 代价：如果在**本场战斗中途**才第一次 capture，标记会退回「没用过」；
     * 实际影响只有「多抽 3 张」这一次，且只发生在一场战斗的中途接入时。
     *
     * @param amount 实际掉的血（格挡与钨棒之后）；{@code <= 0} 时原版整段不执行
     */
    public static void onPlayerHpLost(SimState state, int amount) {
        if (state == null || amount <= 0) {
            return;
        }
        if (state.relics.containsKey(RUNIC_CUBE)) {
            io.github.youqing.sts1solver.sim.CombatFlow.drawCards(state, 1);
        }
        Integer puzzleUsed = state.relics.get(CENTENNIAL_PUZZLE);
        if (puzzleUsed != null && puzzleUsed.intValue() <= 0) {
            state.relics.put(CENTENNIAL_PUZZLE, Integer.valueOf(1));
            io.github.youqing.sts1solver.sim.CombatFlow.drawCards(state, CENTENNIAL_DRAW);
        }
        if (state.relics.containsKey(SELF_FORMING_CLAY)) {
            state.playerPowers.put(NEXT_TURN_BLOCK,
                    intOf(state.playerPowers.get(NEXT_TURN_BLOCK)) + SELF_FORMING_CLAY_BLOCK);
        }
    }

    /**
     * 红骷髅（{@code RedSkull.onBloodied / onNotBloodied}）：跨过「半血」这条线时
     * {@code Strength ±3}。
     *
     * <p>原版用私有 {@code isActive} 布尔记住「+3 加过了没有」，快照里没有 ——
     * 影子把它存在 {@code state.relics} 的值里（{@code > 0} = 已生效），
     * 并在 **capture 时按当时的血线初始化**（下面那段 {@code SimState.capture}）：
     * 否则「开局就已经半血」的局面会把 {@code atBattleStart} 已经加过的 +3 再加一遍。
     *
     * <p>调用点：{@link io.github.youqing.sts1solver.sim.CombatFlow#hitPlayer}（掉血后）、
     * 所有回血落点、以及每个玩家回合开始（兜底）。
     */
    public static void syncRedSkull(SimState state) {
        if (state == null) {
            return;
        }
        Integer flag = state.relics.get(RED_SKULL);
        if (flag == null) {
            return;
        }
        boolean shouldBeActive = state.hp > 0 && state.hp * 2 <= state.maxHp;
        boolean active = flag.intValue() > 0;
        if (shouldBeActive == active) {
            return;
        }
        state.relics.put(RED_SKULL, Integer.valueOf(shouldBeActive ? 1 : 0));
        int delta = shouldBeActive ? RED_SKULL_STRENGTH : -RED_SKULL_STRENGTH;
        state.playerPowers.put(DamageCalc.STRENGTH,
                intOf(state.playerPowers.get(DamageCalc.STRENGTH)) + delta);
    }

    /**
     * 消耗一张牌（{@code AbstractRelic.onExhaust}）：灰烬之主 → 全体 3 点**纯伤**
     * （{@code createDamageMatrix(3, true)}，不吃力量/易伤，但会被格挡吃掉）。
     */
    public static void onExhaust(SimState state) {
        if (state == null || !state.relics.containsKey(CHARONS_ASHES)) {
            return;
        }
        PowerHooks.damageAll(state, CHARONS_ASHES_DAMAGE);
    }

    /**
     * 洗牌（{@code Sundial.onShuffle}）：{@code counter++}，到 3 归零并回 2 能量。
     *
     * <p>{@code onEquip} 会把 counter 置 0，所以捕获到的初值就是「已经洗过几次」。
     */
    public static void onShuffle(SimState state) {
        if (state == null) {
            return;
        }
        Integer counter = state.relics.get(SUNDIAL);
        if (counter == null) {
            return;
        }
        int next = counter.intValue() + 1;
        if (next >= SUNDIAL_SHUFFLES) {
            state.relics.put(SUNDIAL, Integer.valueOf(0));
            state.energy += SUNDIAL_ENERGY;
        } else {
            state.relics.put(SUNDIAL, Integer.valueOf(next));
        }
    }

    /**
     * 击杀一只怪（{@code GremlinHorn.onMonsterDeath}）：回 1 能量 + 抽 1 张。
     *
     * <p>原版有 {@code if (!monsters.areMonstersBasicallyDead())} 这层判断 ——
     * 也就是「这一下把整场怪都打完了」时**不触发**（战斗都要结束了，给能量没意义）。
     * 影子的等价判据是「还有别的怪活着」。
     */
    public static void onMonsterKilled(SimState state, SimMonster dead, int hpBefore) {
        if (state == null || dead == null || hpBefore <= 0 || dead.hp > 0) {
            return;
        }
        boolean anyLeft = false;
        for (SimMonster m : state.monsters) {
            if (m.hp > 0) {
                anyLeft = true;
                break;
            }
        }
        if (!anyLeft) {
            return;
        }
        if (state.relics.containsKey(GREMLIN_HORN)) {
            state.energy += 1;
            io.github.youqing.sts1solver.sim.CombatFlow.drawCards(state, 1);
        }
    }

    /**
     * 战斗中的回血量（{@code MagicFlower.onPlayerHeal}）：{@code MathUtils.round(amount * 1.5f)}。
     *
     * <p>libgdx 的 {@code MathUtils.round(float)} 是 {@code (int)(x + 0.5f)}（正数），
     * 也就是「四舍五入」而不是截断 —— {@code heal(5)} → 8（不是 7）。
     */
    public static int healAmount(SimState state, int amount) {
        if (state == null || amount <= 0 || !state.relics.containsKey(MAGIC_FLOWER)) {
            return amount;
        }
        return (int) Math.floor(amount * 1.5f + 0.5f);
    }

    /**
     * 回合结束时玩家格挡变成多少（{@code GameActionManager} 的那段）：
     * {@code Barricade / Blur} 不动；{@code Calipers} 只掉 15；否则全清。
     */
    public static int blockAfterTurnEnd(SimState state, int block) {
        if (state != null && state.relics.containsKey(CALIPERS)) {
            return Math.max(0, block - CALIPERS_DECAY);
        }
        return 0;
    }

    /** 喝一瓶药（{@code ToyOrnithopter.onUsePotion}）：回 5 血。 */
    public static void onUsePotion(SimState state) {
        if (state == null || !state.relics.containsKey(TOY_ORNITHOPTER)) {
            return;
        }
        state.hp = Math.min(state.maxHp, state.hp + TOY_ORNITHOPTER_HEAL);
        syncRedSkull(state);
    }

    private static int intOf(Integer value) {
        return value == null ? 0 : value.intValue();
    }

    /**
     * 玩家回合开始的遗物结算（对应 {@code applyStartOfTurnRelics}）：
     * 水银沙漏的 3 点纯伤、号角夹板（第 2 回合）、舵轮（第 3 回合）。
     *
     * <p>号角夹板 / 舵轮的 {@code counter} 就是 {@code state.relics} 里那个值：
     * {@code atTurnStart} 里 {@code counter++}，到阈值就给格挡并把 {@code counter} 置 -1，
     * 同时置 {@code grayscale = true}（= 以后**不再自增**）。影子用「counter == -1 就冻住」
     * 表达 grayscale —— 没有别的状态能区分「已经触发过」。
     */
    public static void onPlayerTurnStart(SimState state) {
        if (state == null) {
            return;
        }
        if (state.relics.containsKey(MERCURY_HOURGLASS)) {
            for (SimMonster m : state.monsters) {
                if (m.hp > 0) {
                    pureDamage(m, MERCURY_DAMAGE);
                }
            }
        }
        counterBlockRelic(state, HORN_CLEAT, 2, HORN_CLEAT_BLOCK);
        counterBlockRelic(state, CAPTAINS_WHEEL, 3, CAPTAINS_WHEEL_BLOCK);
        happyFlower(state);
        // Shuriken / Ornamental Fan / Letter Opener / Kunai：atTurnStart 里 counter = 0
        for (String id : TURN_RESET_COUNTERS) {
            if (state.relics.containsKey(id)) {
                state.relics.put(id, Integer.valueOf(0));
            }
        }
    }

    /** 快乐花：{@code counter++}，到 3 归零并 +1 能量（装备时 counter = 0，所以第 3/6/9 回合）。 */
    private static void happyFlower(SimState state) {
        Integer counter = state.relics.get(HAPPY_FLOWER);
        if (counter == null) {
            return;
        }
        int next = counter.intValue() + 1;
        if (next >= HAPPY_FLOWER_TURNS) {
            state.relics.put(HAPPY_FLOWER, Integer.valueOf(0));
            state.energy += 1;
        } else {
            state.relics.put(HAPPY_FLOWER, Integer.valueOf(next));
        }
    }

    /**
     * 玩家回合结束的遗物结算：{@code Orichalcum.onPlayerEndTurn} ——
     * 回合结束时**一点格挡都没有**才给 6 点。
     *
     * <p>⚠️ 顺序：放在所有回合末能力（金属化 / 装甲）之后，所以那类先给格挡的能力会让
     * 山铜当回合失效。原版的调用点在 {@code AbstractRoom} 里，与能力钩子的先后**未在真机核对**，
     * 这里按「先算能力、再算遗物」写（对拍时若出现山铜差异，先查这一条）。
     */
    public static void onPlayerEndTurn(SimState state) {
        if (state == null || !state.relics.containsKey(ORICHALCUM)) {
            return;
        }
        if (state.block == 0) {
            state.block += ORICHALCUM_BLOCK;
        }
    }

    private static void counterBlockRelic(SimState state, String id, int triggerTurn, int block) {
        Integer counter = state.relics.get(id);
        if (counter == null || counter.intValue() < 0) {
            return;                 // 没有 / 已经触发过（grayscale），不再自增
        }
        int next = counter.intValue() + 1;
        state.relics.put(id, Integer.valueOf(next));
        if (next == triggerTurn) {
            state.block += block;
            state.relics.put(id, Integer.valueOf(-1));
        }
    }

    /**
     * 纯伤害（{@code DamageInfo.createDamageMatrix(n, true)} + {@code DamageType.THORNS}）：
     * 不吃力量 / 虚弱 / 易伤，也不触发荆棘、韧性（Malleable）、装甲（Plated Armor）这类
     * 「挨打才动」的能力 —— 只过无实体上限和格挡。
     */
    private static void pureDamage(SimMonster m, int amount) {
        int remaining = DamageCalc.intangibleAdjusted(m.powers, amount);
        if (m.block > 0) {
            int absorbed = Math.min(m.block, remaining);
            m.block -= absorbed;
            remaining -= absorbed;
        }
        m.hp = Math.max(0, m.hp - remaining);
    }

    /** 出牌之后的遗物结算。顺序对计数没有影响，按表逐条走。 */
    public static void onCardUsed(SimState state, SimCard card) {
        if (card == null) {
            return;
        }
        // 墨水瓶数的是「打出的牌」，攻击/技能/能力都算 —— 所以放在牌型判断之外
        inkBottle(state);
        if (card.isAttack()) {
            nunchaku(state);
            ornamentalFan(state);
            shuriken(state);
            kunai(state);
            penNib(state);
        }
        if (card.isSkill()) {
            letterOpener(state);
        }
        if (card.isPower()) {
            birdFacedUrn(state);
        }
    }

    /** 苦无：本回合第 3 张攻击牌 → 敏捷 +1（和手里剑同构，只是给的是敏捷）。 */
    private static void kunai(SimState state) {
        Integer counter = state.relics.get(KUNAI);
        if (counter == null) {
            return;
        }
        int next = counter.intValue() + 1;
        if (next >= 3) {
            state.relics.put(KUNAI, Integer.valueOf(0));
            state.playerPowers.put(DamageCalc.DEXTERITY,
                    intOf(state.playerPowers.get(DamageCalc.DEXTERITY)) + 1);
        } else {
            state.relics.put(KUNAI, Integer.valueOf(next));
        }
    }

    /** 鸟面罐：每打出一张能力牌回 2 血（走 Magic Flower 的 ×1.5 通道）。 */
    private static void birdFacedUrn(SimState state) {
        if (!state.relics.containsKey(BIRD_FACED_URN)) {
            return;
        }
        state.hp = Math.min(state.maxHp, state.hp
                + healAmount(state, BIRD_FACED_URN_HEAL));
        syncRedSkull(state);
    }

    /**
     * 这个减益能不能挂到玩家身上（{@code ApplyPowerAction.update()} 开头那两段）：
     * 姜免疫 {@code Weakened}、萝卜免疫 {@code Frail}，**整条动作直接 return**（连层数都不加）。
     */
    public static boolean blocksPlayerDebuff(SimState state, String powerId) {
        if (state == null || powerId == null) {
            return false;
        }
        if ("Weakened".equals(powerId) && state.relics.containsKey(GINGER)) {
            return true;
        }
        return "Frail".equals(powerId) && state.relics.containsKey(TURNIP);
    }

    /**
     * 笔尖的翻倍只在**伤害计算时**生效（原版是 {@code atDamageGive}，不是 onUseCard）。
     * 计数为 9 表示这是第 10 击。
     */
    public static boolean penNibDoubles(SimState state) {
        Integer counter = state.relics.get("Pen Nib");
        return counter != null && counter == 9;
    }

    private static void nunchaku(SimState state) {
        Integer counter = state.relics.get("Nunchaku");
        if (counter == null) {
            return;
        }
        int next = counter + 1;
        if (next >= 10) {
            state.relics.put("Nunchaku", 0);
            state.energy += 1;
        } else {
            state.relics.put("Nunchaku", next);
        }
    }

    private static void ornamentalFan(SimState state) {
        Integer counter = state.relics.get("Ornamental Fan");
        if (counter == null) {
            return;
        }
        int next = counter + 1;
        if (next >= 3) {
            state.relics.put("Ornamental Fan", 0);
            Hooks.gainBlock(state, 4);
        } else {
            state.relics.put("Ornamental Fan", next);
        }
    }

    private static void shuriken(SimState state) {
        Integer counter = state.relics.get("Shuriken");
        if (counter == null) {
            return;
        }
        int next = counter + 1;
        if (next >= 3) {
            state.relics.put("Shuriken", 0);
            Integer strength = state.playerPowers.get(DamageCalc.STRENGTH);
            state.playerPowers.put(DamageCalc.STRENGTH, (strength == null ? 0 : strength) + 1);
        } else {
            state.relics.put("Shuriken", next);
        }
    }

    private static void penNib(SimState state) {
        Integer counter = state.relics.get("Pen Nib");
        if (counter == null) {
            return;
        }
        int next = counter + 1;
        state.relics.put("Pen Nib", next >= 10 ? 0 : next);
    }

    /**
     * 墨水瓶：每打出 10 张牌抽 1 张。
     *
     * <p>字节码 {@code InkBottle.onUseCard}：{@code counter++; if (counter == 10) { counter = 0;
     * addToBot(new DrawCardAction(1)); }}。不加牌型判断 —— 技能/能力一样计数。
     */
    private static void inkBottle(SimState state) {
        Integer counter = state.relics.get("InkBottle");
        if (counter == null) {
            return;
        }
        int next = counter + 1;
        if (next >= 10) {
            state.relics.put("InkBottle", 0);
            io.github.youqing.sts1solver.mirror.Effects.draw(1).applyTo(state, -1, null);
        } else {
            state.relics.put("InkBottle", next);
        }
    }

    private static void letterOpener(SimState state) {
        Integer counter = state.relics.get("Letter Opener");
        if (counter == null) {
            return;
        }
        int next = counter + 1;
        if (next >= 3) {
            state.relics.put("Letter Opener", 0);
            for (io.github.youqing.sts1solver.sim.SimMonster m : state.monsters) {
                if (m.hp > 0) {
                    DamageCalc.hit(state, m, 5);
                }
            }
        } else {
            state.relics.put("Letter Opener", next);
        }
    }
}
