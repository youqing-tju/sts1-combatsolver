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
        // cost == -1 是 X 费：出牌时消耗全部能量，效果按 X 结算（旋风斩）
        int xValue = 0;
        if (card.cost < 0) {
            if (card.cost != -1) {
                throw new ActionRejected("unsupported cost " + card.cost + " on " + card.key());
            }
            xValue = state.energy;
            state.energy = 0;
        } else {
            if (card.cost > state.energy) {
                throw new ActionRejected("not enough energy for " + card.key()
                        + " (need " + card.cost + ", have " + state.energy + ")");
            }
            state.energy -= card.cost;
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

        if (mirror.exhaustsOnPlay) {
            state.exhaust.add(card);
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
        for (int i = 0; i < state.hand.size(); i++) {
            if (state.hand.get(i).key().equals(cardKey)) {
                play(state, i, targetIndex);
                return;
            }
        }
        throw new ActionRejected("card not in hand: " + cardKey);
    }
}
