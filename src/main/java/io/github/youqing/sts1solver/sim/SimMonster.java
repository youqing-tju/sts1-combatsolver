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

    /**
     * 已经执行过的招（原版 {@code AbstractMonster.moveHistory} 的字节值，按时间顺序）。
     *
     * <p>镜像表靠它判断「连续两次同一招」「这是第一招」这类分支；捕获时直接从真机读。
     */
    public final java.util.ArrayList<Integer> moveHistory = new java.util.ArrayList<Integer>();

    /**
     * 本回合要执行的招（{@link MonsterMoves} 算出来的）；{@code null} = 这只怪还没镜像，
     * 走老的「按意图伤害打一下」路径。
     */
    public MonsterMove pendingMove;

    /**
     * 本回合要执行的招的**原版字节值**（{@code AbstractMonster.nextMove}）。
     *
     * <p>捕获时真机只给得出这个字节（含义在怪物类里），所以它单独存一份：
     * 回合结束时用它把「刚打过的招」记进 {@link #moveHistory}，哪怕这一招我们没算过
     * （{@code pendingMove} 为 null，伤害仍用捕获来的意图值）。
     */
    public int pendingMoveId = -1;

    /**
     * 捕获来的**卡面伤害**（原版 {@code AbstractMonster.damage} 里每个 {@code DamageInfo.base}）。
     *
     * <p>为什么要它：不少怪的伤害是**开战时随机**的（虱子 bite 5-7 / A2 起 6-8、红蓝奴隶主、
     * 大颚虫…），那是 {@code MathUtils.random} 在构造器里抽的，影子复现不出来。
     * 捕获时直接读真机的 {@code damage} 列表，机器就有一份**准确**的底数可用。
     *
     * <p>⚠️ 它整场不变，所以不进指纹（与 {@code intentDmg} 同口径）。
     */
    public final java.util.ArrayList<Integer> damageBase = new java.util.ArrayList<Integer>();

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
        c.moveHistory.addAll(moveHistory);
        c.pendingMove = pendingMove;
        c.pendingMoveId = pendingMoveId;
        c.damageBase.addAll(damageBase);
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
