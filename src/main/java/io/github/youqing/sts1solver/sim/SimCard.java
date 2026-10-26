package io.github.youqing.sts1solver.sim;

/**
 * A card as the shadow simulation sees it: identity plus the numbers that change
 * during a fight. Deliberately immutable — a fork copies the list, never the card.
 */
public final class SimCard {

    public final String id;
    public final boolean upgraded;
    /** Current cost: 0..3 normal, -1 = X cost, -2 = unplayable. */
    public final int cost;

    /**
     * 卡面当前伤害（{@code AbstractCard.baseDamage}）。
     * 暴走每次打出 +5、灼烧打击多次升级，都会体现到这个值上，
     * 所以镜像直接用「卡面伤害」比用固定常数更准。
     */
    public final int baseDamage;

    /**
     * 牌的类型：{@code ATTACK / SKILL / POWER / STATUS / CURSE}，未知时为空串。
     *
     * <p>只有「从手牌捕获」的牌保证有值，效果生成的牌可能不带。类型唯一的用途是
     * 遗物计数（修女/手里剑数攻击、开信刀数技能），而被打出的牌一定来自手牌，
     * 所以缺省不会影响判定。
     */
    public final String type;

    /**
     * 「本回合」费用（对应原版 {@code AbstractCard.costForTurn}）；{@code -1} = 没有临时改动。
     *
     * <p>🔴 必须和 {@link #cost} 分开：地狱之刃 / 发现 / 转化 给的是「**本回合** 0 费」，
     * 回合结束就恢复；而蛹 / 蜕变 / 疯狂 是「**本场** 0 费」，那才是改 {@code cost}。
     * 早先一律写进 {@code cost}，模型里那张牌就永久免费了（2026-10-23 报的问题）。
     */
    public final int costForTurn;

    public SimCard(String id, boolean upgraded, int cost) {
        this(id, upgraded, cost, -1, "");
    }

    public SimCard(String id, boolean upgraded, int cost, int baseDamage) {
        this(id, upgraded, cost, baseDamage, "");
    }

    public SimCard(String id, boolean upgraded, int cost, int baseDamage, String type) {
        this(id, upgraded, cost, baseDamage, type, -1);
    }

    public SimCard(String id, boolean upgraded, int cost, int baseDamage, String type,
                   int costForTurn) {
        this.id = id;
        this.upgraded = upgraded;
        this.cost = cost;
        this.baseDamage = baseDamage;
        this.type = type == null ? "" : type;
        this.costForTurn = costForTurn;
    }

    /** Stable key used by fingerprints and the mirror registry: {@code Strike_R+}. */
    public String key() {
        return id + (upgraded ? "+" : "");
    }

    public boolean isAttack() {
        return "ATTACK".equals(type);
    }

    public boolean isSkill() {
        return "SKILL".equals(type);
    }

    public boolean isPower() {
        return "POWER".equals(type);
    }

    /**
     * 虚体（{@code AbstractCard.isEthereal}）：回合结束**在手上就直接消耗**，不进弃牌堆。
     *
     * <p>从字节码逐类核实（类文件里有没有 {@code isEthereal} 字段写入）：
     * {@code Dazed} / {@code VoidCard} / {@code AscendersBane} / {@code Clumsy} 有，
     * {@code Burn} / {@code Slimed} / {@code Wound} / {@code Decay} / {@code Regret} /
     * {@code Doubt} / {@code Shame} / {@code Pain} 没有。
     *
     * <p>用 ID 判而不是加字段：虚体是「这张牌本身」的属性，捕获时也不需要额外信息。
     */
    public boolean isEthereal() {
        return "Dazed".equals(id) || "Void".equals(id)
                || "AscendersBane".equals(id) || "Clumsy".equals(id);
    }

    /** 改**永久**费用（本场 0 费 / 永久降费）。 */
    public SimCard withCost(int newCost) {
        return new SimCard(id, upgraded, newCost, baseDamage, type, costForTurn);
    }

    /** 只改**本回合**费用（{@code AbstractCard.setCostForTurn}）。 */
    public SimCard withCostForTurn(int newCostForTurn) {
        return new SimCard(id, upgraded, cost, baseDamage, type, newCostForTurn);
    }

    /** 回合结束：临时费用失效。 */
    public SimCard clearCostForTurn() {
        return costForTurn < 0 ? this
                : new SimCard(id, upgraded, cost, baseDamage, type, -1);
    }

    /** 真正出这张牌要付多少能量。-1 = X 费，-2 = 打不出。 */
    public int effectiveCost() {
        return costForTurn >= 0 ? costForTurn : cost;
    }

    @Override
    public String toString() {
        return key() + "(" + effectiveCost() + ")";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof SimCard)) {
            return false;
        }
        SimCard other = (SimCard) o;
        return upgraded == other.upgraded && cost == other.cost && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        int result = id.hashCode();
        result = 31 * result + (upgraded ? 1 : 0);
        result = 31 * result + cost;
        return result;
    }
}
