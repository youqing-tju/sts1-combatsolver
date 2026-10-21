package io.github.youqing.sts1solver.search;

/**
 * 搜索里的一个动作。对应蓝本 {@code src/Search/CombatPlan.cs:8} 的 {@code PlanActionKind}
 * —— 那边把「打牌 / 喝药 / 结束回合」都当成一等动作，而不是"有牌就打、没牌才结束"。
 */
public final class PlanAction {

    public enum Kind {
        PLAY_CARD,
        END_TURN
    }

    public final Kind kind;
    /** 手牌下标（PLAY_CARD 时有效）。 */
    public final int cardIndex;
    /** 目标怪物下标，-1 表示不指定。 */
    public final int targetIndex;

    /**
     * 需要选牌的手牌下标（打出本牌**之后**的手牌列表），-1 表示这张牌不需要选。
     * 对应蓝本的 {@code PlanAction.Choice}（{@code src/Search/CombatPlan.cs:132}）。
     */
    public final int choiceIndex;

    private PlanAction(Kind kind, int cardIndex, int targetIndex, int choiceIndex) {
        this.kind = kind;
        this.cardIndex = cardIndex;
        this.targetIndex = targetIndex;
        this.choiceIndex = choiceIndex;
    }

    public static PlanAction play(int cardIndex, int targetIndex) {
        return new PlanAction(Kind.PLAY_CARD, cardIndex, targetIndex, -1);
    }

    public static PlanAction play(int cardIndex, int targetIndex, int choiceIndex) {
        return new PlanAction(Kind.PLAY_CARD, cardIndex, targetIndex, choiceIndex);
    }

    public static PlanAction endTurn() {
        return new PlanAction(Kind.END_TURN, -1, -1, -1);
    }

    @Override
    public String toString() {
        if (kind == Kind.END_TURN) {
            return "END_TURN";
        }
        return "PLAY[" + cardIndex + "]->" + targetIndex
                + (choiceIndex >= 0 ? " pick[" + choiceIndex + "]" : "");
    }
}
