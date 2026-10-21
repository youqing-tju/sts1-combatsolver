package io.github.youqing.sts1solver.sim;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/** A monster in the shadow simulation. Powers use a sorted map so diffs are stable. */
public final class SimMonster {

    /** Simple class name of the vanilla monster, e.g. {@code AcidSlime_S}. */
    public final String type;
    public final int maxHp;

    public int hp;
    public int block;

    /**
     * 本回合意图伤害（{@code AbstractMonster.getIntentDmg()}）。
     * 只用于搜索时估算「如果现在结束回合会掉多少血」，**不参与对拍比较**——
     * 它每回合都会变，而影子并不预测意图。
     */
    public int intentDmg;

    /** 本回合意图是否为攻击类（观察弱点需要）。同样不参与对拍比较。 */
    public boolean intentAttack;

    public final TreeMap<String, Integer> powers;

    public SimMonster(String type, int hp, int maxHp, int block, Map<String, Integer> powers) {
        this.type = type;
        this.hp = hp;
        this.maxHp = maxHp;
        this.block = block;
        this.powers = new TreeMap<String, Integer>(powers);
    }

    public SimMonster copy() {
        SimMonster c = new SimMonster(type, hp, maxHp, block, powers);
        c.intentDmg = intentDmg;
        c.intentAttack = intentAttack;
        return c;
    }

    public Map<String, Integer> powersView() {
        return Collections.unmodifiableMap(powers);
    }

    @Override
    public String toString() {
        return type + " " + hp + "/" + maxHp + " b" + block + (powers.isEmpty() ? "" : " " + powers);
    }
}
