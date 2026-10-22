package io.github.youqing.sts1solver.mirror.hooks;

/**
 * 战斗中的「钩子点」——与 STS1 的 {@code AbstractPower} 回调一一对应。
 *
 * <p>每一个枚举值都能在字节码里找到确切的调用方，不是凭卡面文字猜的：
 *
 * <pre>
 * START_OF_TURN             AbstractCreature.applyStartOfTurnPowers()
 *                           → 所有 power 依次 atStartOfTurn()
 * START_OF_TURN_POST_DRAW   AbstractCreature.applyStartOfTurnPostDrawPowers()
 *                           → 所有 power 依次 atStartOfTurnPostDraw()
 * END_OF_TURN_PRE_CARDS     AbstractRoom.applyEndOfTurnPreCardPowers()
 *                           → 所有 power 依次 atEndOfTurnPreEndTurnCards(true)
 * END_OF_TURN               AbstractRoom.endTurn() → AbstractCreature.applyEndOfTurnTriggers()
 *                           → 只对玩家调用 atEndOfTurn(true)
 * USE_CARD                  UseCardAction → power.onUseCard(card, action)
 * APPLY_POWER               ApplyPowerAction → power.onApplyPower(power, target, source)
 * GAINED_BLOCK              AbstractCreature.addBlock(float) → power.onGainedBlock(float)
 * </pre>
 *
 * <p>注意 {@code atEndOfTurnPreEndTurnCards} 与 {@code atEndOfTurn} 是两个**不同时刻**：
 * 前者在手牌弃置之前（金属化），后者在弃置之后（燃烧）。实测字节码里
 * {@code applyEndOfTurnTriggers} 对玩家**只**调用 {@code atEndOfTurn}，
 * 把两者混在一起会让金属化晚一步、让燃烧早一步。
 */
public enum HookPhase {
    START_OF_TURN,
    START_OF_TURN_POST_DRAW,
    END_OF_TURN_PRE_CARDS,
    END_OF_TURN,
    USE_CARD,
    APPLY_POWER,
    GAINED_BLOCK
}
