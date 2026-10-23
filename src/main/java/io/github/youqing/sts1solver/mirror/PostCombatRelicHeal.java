package io.github.youqing.sts1solver.mirror;

import java.util.Map;

/**
 * 「战斗赢了以后，遗物会回多少血」—— 供路线排序用。
 *
 * <p>对应蓝本 {@code ActEndingBossPolicy.PostCombatRelicHealProfile} +
 * {@code CombatRootSnapshot.CapturePostCombatRelicHeal}。
 *
 * <p><b>为什么排序里要看战后回血</b>（蓝本 {@code FinalPlanOrdering.cs:73-83} 的原注释）：
 * 这些遗物在战斗结束后才结算，所以它们回的血**从来不会进 {@code RecoveredPlayerHp}**，
 * 但那笔血会像战斗中回的血一样带进下一场战斗。不计的话，
 * 「打完剩 30 血（配燃烧之血会变 36）」和「打完剩 35 血」两条路线在赤字轴上分不出高下。
 *
 * <h2>只算「无条件回血」进排序</h2>
 *
 * <p>{@link #healFor} 是玩家实际看到的（含带骨肉的阈值回血），
 * {@link #monotoneHealFor} 才是**排序允许计的那部分**。原因在蓝本
 * {@code PostCombatRelicHealProfile.MonotoneHealFor} 的注释里写死了：
 *
 * <pre>
 * min(heal, maxHp - finalHp) 对 finalHp 单调不减，
 * 所以「结束血量更高」永远不更差 —— 支配测试和搜索上界都是靠这个不变式成立的。
 * 带骨肉的阈值回血破坏它：结束在阈值上 1 点血，反而比卡在阈值上更差。
 * </pre>
 *
 * <p>所以阈值回血只报给玩家，不进排序。这样做只会让解算器在阈值附近**略微高估**血量价值，
 * 绝不会反向（不会高估「残血更好」）。
 */
public final class PostCombatRelicHeal {

    /** STS1 遗物 ID。 */
    public static final String BURNING_BLOOD = "Burning Blood";
    public static final String BLACK_BLOOD = "Black Blood";
    public static final String MEAT_ON_THE_BONE = "Meat on the Bone";

    /** 燃烧之血（Ironclad 初始遗物）：每场胜利回 6（{@code onVictory} 字节码 {@code bipush 6}）。 */
    private static final int BURNING_BLOOD_HEAL = 6;

    /** 黑血（升级版）：每场胜利回 12（{@code bipush 12}）。 */
    private static final int BLACK_BLOOD_HEAL = 12;

    /** 带骨肉：战斗结束时血量 ≤ 50% 才回 12（{@code MeatOnTheBone.onTrigger}）。 */
    private static final int MEAT_ON_THE_BONE_HEAL = 12;

    private static final int MEAT_ON_THE_BONE_PERCENT = 50;

    /** 无条件回血总量（{@code UnconditionalHeal}）—— 只有它进排序。 */
    public final int unconditionalHeal;

    /** 阈值回血量（{@code WoundedHeal}）—— 只报给玩家。 */
    public final int woundedHeal;

    /** 阈值百分比（{@code WoundedHpPercent}）。 */
    public final int woundedHpPercent;

    private PostCombatRelicHeal(int unconditionalHeal, int woundedHeal, int woundedHpPercent) {
        this.unconditionalHeal = unconditionalHeal;
        this.woundedHeal = woundedHeal;
        this.woundedHpPercent = woundedHpPercent;
    }

    /** 全无 —— 不是战士/没带这些遗物。 */
    public static PostCombatRelicHeal none() {
        return new PostCombatRelicHeal(0, 0, 0);
    }

    /**
     * 从快照的遗物表里读出这份画像。
     *
     * <p>蓝本是从**活的遗物对象**上读数值（数据层改平衡能自动跟），我们从
     * {@code SimState.relics} 的 ID 表判断 + 用原版常量（javap 核实）。
     * 取值口径见 {@code mechanics-reference} §24。
     */
    public static PostCombatRelicHeal of(Map<String, Integer> relics) {
        if (relics == null || relics.isEmpty()) {
            return none();
        }
        int unconditional = 0;
        int wounded = 0;
        int woundedPercent = 0;
        // 燃烧之血 / 黑血是「拿到黑血就顶掉燃烧之血」的替换关系（BlackBlood.canSpawn 要求有前者），
        // 正常流程里不会同时在场。但累加而不是 else-if：原版 onVictory 是逐个遗物各 heal 一次，
        // 两个都在就真会回两次。蓝本 CapturePostCombatRelicHeal 也是 +=。
        if (relics.containsKey(BURNING_BLOOD)) {
            unconditional += BURNING_BLOOD_HEAL;
        }
        if (relics.containsKey(BLACK_BLOOD)) {
            unconditional += BLACK_BLOOD_HEAL;
        }
        if (relics.containsKey(MEAT_ON_THE_BONE)) {
            wounded += MEAT_ON_THE_BONE_HEAL;
            woundedPercent = MEAT_ON_THE_BONE_PERCENT;
        }
        return new PostCombatRelicHeal(unconditional, wounded, woundedPercent);
    }

    /** 画像是不是空的（便于直接短路）。 */
    public boolean hasAnyHeal() {
        return unconditionalHeal > 0 || woundedHeal > 0;
    }

    /**
     * 玩家实际会看到的回血量（含带骨肉）—— 只用于汇报，不进排序。
     *
     * <pre>
     * capacity = unconditionalHeal
     * if (woundedHeal &gt; 0 &amp;&amp; finalHp &lt;= 阈值) capacity += woundedHeal
     * return clamp(capacity, 0, maxHp - finalHp)   // 回不满就只回到满血
     * </pre>
     *
     * <p>阈值是 {@code finalMaxHp * 50 / 100}（整除）。原版是 float 比较
     * {@code currentHealth &lt;= maxHealth / 2.0f}，整除截断后边界上结果一致
     * （奇数 maxHp 时 41 ≤ 40.5 为假、40 ≤ 40.5 为真，和 {@code hp ≤ 40} 同判）。
     */
    public int healFor(int finalHp, int finalMaxHp) {
        int capacity = Math.max(0, unconditionalHeal);
        if (woundedHeal > 0 && finalHp <= woundedThreshold(finalMaxHp)) {
            capacity += woundedHeal;
        }
        return clampToHeadroom(capacity, finalHp, finalMaxHp);
    }

    /**
     * 排序允许计的那部分 —— **只有无条件回血**。
     *
     * <p>带骨肉的阈值回血刻意排除（见类注释）。蓝本的
     * {@code ActEndingBossPolicy.RankedPostCombatRelicHeal} 还叠了两个前置：
     * {@code completeVictory &amp;&amp; finalHp &gt; 0}（打输了什么都拿不到），由调用方保证。
     */
    public int monotoneHealFor(int finalHp, int finalMaxHp) {
        return clampToHeadroom(Math.max(0, unconditionalHeal), finalHp, finalMaxHp);
    }

    /** 原版 {@code MeatOnTheBone.onTrigger} 的阈值（整除，与 float 比较边界同判）。 */
    private int woundedThreshold(int finalMaxHp) {
        return Math.max(0, finalMaxHp) * Math.max(0, woundedHpPercent) / 100;
    }

    private static int clampToHeadroom(int capacity, int finalHp, int finalMaxHp) {
        return Math.max(0, Math.min(capacity, Math.max(0, finalMaxHp - finalHp)));
    }

    @Override
    public String toString() {
        return "PostCombatRelicHeal(unconditional=" + unconditionalHeal
                + ", wounded=" + woundedHeal + "@" + woundedHpPercent + "%)";
    }
}
