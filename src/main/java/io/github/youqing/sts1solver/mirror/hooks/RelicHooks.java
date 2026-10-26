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
            penNib(state);
        }
        if (card.isSkill()) {
            letterOpener(state);
        }
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
