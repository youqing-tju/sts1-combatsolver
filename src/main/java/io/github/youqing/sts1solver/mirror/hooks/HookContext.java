package io.github.youqing.sts1solver.mirror.hooks;

import io.github.youqing.sts1solver.sim.SimCard;
import io.github.youqing.sts1solver.sim.SimMonster;

/**
 * 钩子回调的入参。字段按阶段取用，用不到的保持默认值。
 *
 * <p>一个上下文对象而不是七个回调接口：阶段之间共享的东西（当前出的牌、目标）
 * 本来就一样，拆成七个接口只会让注册表写七遍。
 */
public final class HookContext {

    /** USE_CARD：正在打出的牌。 */
    public SimCard card;
    /** USE_CARD：目标怪物下标，-1 表示无目标。 */
    public int targetIndex = -1;

    /** APPLY_POWER：被施加的状态 ID 与层数。 */
    public String appliedPower;
    public int appliedAmount;
    /** APPLY_POWER：状态落到了哪只怪物身上。 */
    public SimMonster appliedTo;

    /** GAINED_BLOCK：这一次实际获得了多少格挡。 */
    public int gainedBlock;

    public static HookContext none() {
        return new HookContext();
    }
}
