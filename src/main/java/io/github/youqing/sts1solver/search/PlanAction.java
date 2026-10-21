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

    private PlanAction(Kind kind, int cardIndex, int targetIndex) {
        this.kind = kind;
        this.cardIndex = cardIndex;
        this.targetIndex = targetIndex;
    }

    public static PlanAction play(int cardIndex, int targetIndex) {
        return new PlanAction(Kind.PLAY_CARD, cardIndex, targetIndex);
    }

    public static PlanAction endTurn() {
        return new PlanAction(Kind.END_TURN, -1, -1);
    }

    @Override
    public String toString() {
        return kind == Kind.END_TURN ? "END_TURN" : "PLAY[" + cardIndex + "]->" + targetIndex;
    }
}
