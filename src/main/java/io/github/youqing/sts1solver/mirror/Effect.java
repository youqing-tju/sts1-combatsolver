package io.github.youqing.sts1solver.mirror;

import io.github.youqing.sts1solver.sim.SimCard;
import io.github.youqing.sts1solver.sim.SimState;

/**
 * One atomic effect of playing a card, expressed against the shadow state.
 *
 * <p>Cards are modelled as a list of these instead of one class per card: most of the
 * pool is "damage / block / apply power / draw" in different proportions, and a data table
 * is far cheaper to audit than 75 hand-written classes.
 */
public interface Effect {

    /**
     * @param state       shadow state to mutate
     * @param targetIndex index into {@link SimState#monsters}, or -1 when self-targeted
     * @param played      the card being resolved (needed by cards that copy themselves)
     */
    void applyTo(SimState state, int targetIndex, SimCard played);

    /**
     * 这个效果是否需要指定某个怪物。
     * 搜索用它决定要不要针对每个敌人分别展开——群伤牌展开 N 次纯属浪费 beam 宽度。
     */
    default boolean needsMonsterTarget() {
        return false;
    }
}
