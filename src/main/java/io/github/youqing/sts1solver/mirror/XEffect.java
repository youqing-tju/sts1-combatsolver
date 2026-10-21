package io.github.youqing.sts1solver.mirror;

import io.github.youqing.sts1solver.sim.SimCard;
import io.github.youqing.sts1solver.sim.SimState;

/**
 * X 费卡牌的效果（旋风斩）。
 *
 * <p>X 的取值在**出牌那一刻**才确定：等于当前全部能量。所以效果不能用固定数值表达，
 * 必须把 x 传进来。这与游戏语义一致（{@code AbstractCard.cost == -1} 时，
 * 出牌消耗全部能量，卡牌自己读 {@code energyOnUse}）。
 */
public interface XEffect extends Effect {

    void applyWithX(SimState state, int targetIndex, SimCard played, int x);

    @Override
    default void applyTo(SimState state, int targetIndex, SimCard played) {
        applyWithX(state, targetIndex, played, 0);
    }
}
