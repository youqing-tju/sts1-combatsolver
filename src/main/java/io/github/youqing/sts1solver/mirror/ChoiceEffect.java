package io.github.youqing.sts1solver.mirror;

import io.github.youqing.sts1solver.sim.SimCard;
import io.github.youqing.sts1solver.sim.SimState;

/**
 * 需要「从手牌里挑一张」的效果（军备 / 战吼 / 烧牌契约）。
 *
 * <p>蓝本把选择建模成动作的一部分（{@code PlanAction.Choice}，
 * {@code src/Search/CombatPlan.cs:132}）：搜索在决定「打哪张」的同时决定「选哪张」。
 * 这里对应地把 choiceIndex 从动作一路传下来。
 *
 * <p>{@code choiceIndex} 指的是**打出本牌之后**手牌列表里的下标。
 */
public interface ChoiceEffect extends Effect {

    void applyWithChoice(SimState state, int targetIndex, SimCard played, int choiceIndex);

    @Override
    default void applyTo(SimState state, int targetIndex, SimCard played) {
        applyWithChoice(state, targetIndex, played, -1);
    }
}
