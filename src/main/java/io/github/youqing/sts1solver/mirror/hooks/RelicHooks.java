package io.github.youqing.sts1solver.mirror.hooks;

import io.github.youqing.sts1solver.mirror.DamageCalc;
import io.github.youqing.sts1solver.sim.SimCard;
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
