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

    public SimCard(String id, boolean upgraded, int cost) {
        this(id, upgraded, cost, -1, "");
    }

    public SimCard(String id, boolean upgraded, int cost, int baseDamage) {
        this(id, upgraded, cost, baseDamage, "");
    }

    public SimCard(String id, boolean upgraded, int cost, int baseDamage, String type) {
        this.id = id;
        this.upgraded = upgraded;
        this.cost = cost;
        this.baseDamage = baseDamage;
        this.type = type == null ? "" : type;
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

    public SimCard withCost(int newCost) {
        return new SimCard(id, upgraded, newCost, baseDamage, type);
    }

    @Override
    public String toString() {
        return key() + "(" + cost + ")";
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
