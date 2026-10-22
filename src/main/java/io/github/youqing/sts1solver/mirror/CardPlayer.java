package io.github.youqing.sts1solver.mirror;

import io.github.youqing.sts1solver.sim.SimCard;
import io.github.youqing.sts1solver.sim.SimState;

/**
 * Applies one card play to a shadow state.
 *
 * <p>Layering: {@code sim} knows what a state <i>is</i>; {@code mirror} knows how the
 * game <i>behaves</i>. So the rule machine lives here, and {@code mirror → sim} is the
 * only dependency direction (no cycle).
 *
 * <p>Anything that cannot be simulated faithfully throws {@link ActionRejected}. Callers
 * are expected to treat that as "this action is illegal for the search", never as "skip
 * the check".
 */
public final class CardPlayer {

    private CardPlayer() {
    }

    /**
     * 强制打出一张**不在手牌里**的牌（破灭 Havoc：打出抽牌堆顶那张）。
     * 不检查也不扣能量 —— 游戏里这类效果是免费的。
     */
    public static void playFromPile(SimState state, SimCard card, int targetIndex) {
        CardMirror mirror = MirrorRegistry.lookup(card.key());
        if (mirror == null) {
            throw new ActionRejected("no mirror for card " + card.key());
        }
        for (Effect effect : mirror.effects) {
            if (effect instanceof XEffect) {
                ((XEffect) effect).applyWithX(state, targetIndex, card, 0);
            } else if (effect instanceof ChoiceEffect) {
                ((ChoiceEffect) effect).applyWithChoice(state, targetIndex, card, -1);
            } else {
                effect.applyTo(state, targetIndex, card);
            }
        }
        // 原版 UseCardAction 的末尾：能力与遗物的 onUseCard（神气制胜计数、修女/手里剑…）
        io.github.youqing.sts1solver.mirror.hooks.Hooks.onCardUsed(state, card, targetIndex);
        if (mirror.exhaustsOnPlay) {
            Effects.exhaustCard(state, card);
        } else {
            state.discard.add(card);
        }
    }

    public static void play(SimState state, int handIndex, int targetIndex) {
        play(state, handIndex, targetIndex, -1);
    }

    public static void play(SimState state, int handIndex, int targetIndex, int choiceIndex) {
        if (handIndex < 0 || handIndex >= state.hand.size()) {
            throw new ActionRejected("hand index out of range: " + handIndex);
        }
        SimCard card = state.hand.get(handIndex);
        CardMirror mirror = MirrorRegistry.lookup(card.key());
        if (mirror == null) {
            throw new ActionRejected("no mirror for card " + card.key());
        }
        // 缠身（Entangled）：本回合不能打出攻击牌。
        // 原版在 AbstractCard 的可打出判定里：hasPower("Entangled") && type == ATTACK → false。
        if (isAttack(card) && power(state, "Entangled") > 0) {
            throw new ActionRejected("attack blocked by Entangled: " + card.key());
        }
        // 动态费用（浴血奋战：本场每损失一次 HP，费用 -1，最低 0）
        int effectiveCost = card.cost;
        if (mirror.costDropsWithHpLoss && effectiveCost > 0) {
            effectiveCost = Math.max(0, effectiveCost - state.hpLossCount);
        }

        // cost == -1 是 X 费：出牌时消耗全部能量，效果按 X 结算（旋风斩）
        int xValue = 0;
        if (card.cost < 0) {
            if (card.cost != -1) {
                throw new ActionRejected("unsupported cost " + card.cost + " on " + card.key());
            }
            xValue = state.energy;
            state.energy = 0;
        } else {
            if (effectiveCost > state.energy) {
                throw new ActionRejected("not enough energy for " + card.key()
                        + " (need " + effectiveCost + ", have " + state.energy + ")");
            }
            state.energy -= effectiveCost;
        }
        state.hand.remove(handIndex);

        for (Effect effect : mirror.effects) {
            if (effect instanceof XEffect) {
                ((XEffect) effect).applyWithX(state, targetIndex, card, xValue);
            } else if (effect instanceof ChoiceEffect) {
                ((ChoiceEffect) effect).applyWithChoice(state, targetIndex, card, choiceIndex);
            } else {
                effect.applyTo(state, targetIndex, card);
            }
        }

        // 同样要触发 onUseCard：自动打出的牌也会数进遗物/能力计数
        io.github.youqing.sts1solver.mirror.hooks.Hooks.onCardUsed(state, card, targetIndex);

        if (mirror.exhaustsOnPlay) {
            Effects.exhaustCard(state, card);
        } else {
            state.discard.add(card);
        }
        // 不在这里移除死亡怪物：游戏会保留 0 血单位直到死亡动画播完。
        // 影子若立刻删除，怪物列表长度就和实机对不上，核对前统一做归一化。
    }

    /**
     * Plays the first card in hand matching {@code cardKey}. The diff harness uses this
     * instead of an index, because the live game and the shadow may disagree on indices
     * while still holding the same cards.
     */
    public static void playByKey(SimState state, String cardKey, int targetIndex) {
        playByKey(state, cardKey, targetIndex, -1);
    }

    public static void playByKey(SimState state, String cardKey, int targetIndex, int choiceIndex) {
        for (int i = 0; i < state.hand.size(); i++) {
            if (state.hand.get(i).key().equals(cardKey)) {
                play(state, i, targetIndex, choiceIndex);
                return;
            }
        }
        throw new ActionRejected("card not in hand: " + cardKey);
    }

    private static int power(SimState state, String id) {
        Integer v = state.playerPowers.get(id);
        return v == null ? 0 : v;
    }

    /**
     * 这张牌算不算攻击牌。
     *
     * <p>影子只在从手牌捕获时带上 {@code type}；效果生成的牌可能没有类型，
     * 这时退回注册表维护的攻击牌名单。判断错了会漏掉「缠身」的拦截，所以两条路都留着。
     */
    private static boolean isAttack(SimCard card) {
        if (card.isAttack()) {
            return true;
        }
        return card.type.isEmpty() && Effects.isAttackId(card.id);
    }
}
