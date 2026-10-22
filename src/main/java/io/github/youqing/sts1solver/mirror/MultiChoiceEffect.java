package io.github.youqing.sts1solver.mirror;

import io.github.youqing.sts1solver.sim.SimCard;
import io.github.youqing.sts1solver.sim.SimState;

/**
 * 「从手牌里挑**若干张**」的效果（净化 Purity：最多 3/5 张）。
 *
 * <p>和 {@link ChoiceEffect} 的区别只有一处：选择是**一组下标**而不是一个。
 * 对拍时这组下标直接来自实机（{@code HandSelectDriver} 每次选牌都会上报），
 * 搜索时传 {@code null} 让效果按自己的策略挑 —— 枚举全部子集对 10 张手牌是 1024 种，
 * 放进 beam search 不划算（蓝本对同类效果也是限定候选后展开，见
 * {@code CardSelectionCardMirrors}）。
 */
public interface MultiChoiceEffect extends ChoiceEffect {

    /**
     * @param choices 手牌下标数组；{@code null} 表示由效果自己按策略决定
     */
    void applyWithChoices(SimState state, int targetIndex, SimCard played, int[] choices);

    @Override
    default void applyWithChoice(SimState state, int targetIndex, SimCard played, int choiceIndex) {
        applyWithChoices(state, targetIndex, played,
                choiceIndex < 0 ? null : new int[]{choiceIndex});
    }

    @Override
    default void applyTo(SimState state, int targetIndex, SimCard played) {
        applyWithChoices(state, targetIndex, played, null);
    }
}
