package io.github.youqing.sts1solver.search;

/**
 * 搜索里的一个动作。对应蓝本 {@code src/Search/CombatPlan.cs:8} 的 {@code PlanActionKind}
 * —— 那边把「打牌 / 喝药 / 结束回合」都当成一等动作，而不是"有牌就打、没牌才结束"。
 */
public final class PlanAction {

    public enum Kind {
        PLAY_CARD,
        USE_POTION,
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

    /**
     * 多选（{@code PlanCardChoice.Cards} 是一组）：选中的下标数组；{@code null} 表示单选/不选。
     *
     * <p>蓝本 {@code CardChoiceSpec} 的 {@code MinCount/MaxCount} 允许一次选多张
     * （赌徒酿造弃多张、灵药消耗多张）。单选时仍用 {@link #choiceIndex}，两边不混用。
     */
    public final int[] choiceIndices;

    private PlanAction(Kind kind, int cardIndex, int targetIndex, int choiceIndex) {
        this(kind, cardIndex, targetIndex, choiceIndex, null);
    }

    private PlanAction(Kind kind, int cardIndex, int targetIndex, int choiceIndex,
                       int[] choiceIndices) {
        this.kind = kind;
        this.cardIndex = cardIndex;
        this.targetIndex = targetIndex;
        this.choiceIndex = choiceIndex;
        this.choiceIndices = choiceIndices;
    }

    public static PlanAction play(int cardIndex, int targetIndex) {
        return new PlanAction(Kind.PLAY_CARD, cardIndex, targetIndex, -1);
    }

    public static PlanAction play(int cardIndex, int targetIndex, int choiceIndex) {
        return new PlanAction(Kind.PLAY_CARD, cardIndex, targetIndex, choiceIndex);
    }

    /** 用掉一格药水（{@code cardIndex} 复用为**药水槽位**，对应蓝本的 {@code UsePotion}）。 */
    public static PlanAction potion(int slotIndex, int targetIndex) {
        return new PlanAction(Kind.USE_POTION, slotIndex, targetIndex, -1);
    }

    /** 同上，但带三选一的选项（药水开的选牌界面）。 */
    public static PlanAction potion(int slotIndex, int targetIndex, int choiceIndex) {
        return new PlanAction(Kind.USE_POTION, slotIndex, targetIndex, choiceIndex);
    }

    /** 药水引发的**多选**（赌徒酿造弃多张 / 灵药消耗多张）。 */
    public static PlanAction potionMulti(int slotIndex, int targetIndex, int[] choiceIndices) {
        return new PlanAction(Kind.USE_POTION, slotIndex, targetIndex, -1, choiceIndices);
    }

    public static PlanAction endTurn() {
        return new PlanAction(Kind.END_TURN, -1, -1, -1);
    }

    @Override
    public String toString() {
        if (kind == Kind.END_TURN) {
            return "END_TURN";
        }
        if (kind == Kind.USE_POTION) {
            if (choiceIndices != null) {
                StringBuilder sb = new StringBuilder("POTION[").append(cardIndex).append("]->")
                        .append(targetIndex).append(" pick[");
                for (int i = 0; i < choiceIndices.length; i++) {
                    if (i > 0) {
                        sb.append(',');
                    }
                    sb.append(choiceIndices[i]);
                }
                return sb.append(']').toString();
            }
            return "POTION[" + cardIndex + "]->" + targetIndex
                    + (choiceIndex >= 0 ? " pick[" + choiceIndex + "]" : "");
        }
        return "PLAY[" + cardIndex + "]->" + targetIndex
                + (choiceIndex >= 0 ? " pick[" + choiceIndex + "]" : "");
    }
}
