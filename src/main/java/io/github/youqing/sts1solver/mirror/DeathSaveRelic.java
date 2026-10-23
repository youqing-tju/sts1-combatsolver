package io.github.youqing.sts1solver.mirror;

import io.github.youqing.sts1solver.sim.SimState;

/**
 * 一次性「死亡豁免」遗物 —— 目前只做**蜥蜴尾巴**（Lizard Tail）。
 *
 * <p>对应蓝本 {@code ActEndingBossPolicy.DeathSavePremium} / {@code DeathSaveBeamCost}
 * 与 {@code ProjectedDeathPrevention.TryRevive}。蓝本注释里点名的两个来源是
 * <b>Lizard Tail</b> 与 <b>Fairy in a Bottle</b>；STS1 两张都有 —— 蜥蜴尾巴是遗物（本类），
 * 瓶中仙子是药水（药水整体还没建模，见 {@code docs/research/blueprint-parity.md}）。
 *
 * <p>为什么要在仿真里真的拦一次，而不能只在估值里减分：原地复活会改变**后续每一条路线**的
 * 可行性（本来会死的线变成能走）。蓝本的做法也是先真的记上血量，再在打分里把这笔血扣回去
 * 并加收 {@code DeathSavePremium}（溢价 9 倍）。
 *
 * <p>原版语义逐条来自字节码（{@code LizardTail.onTrigger()} +
 * {@code AbstractPlayer.damage()} 的死亡分支），细节见 {@code mechanics-reference} §23。
 */
public final class DeathSaveRelic {

    /** STS1 遗物 ID。 */
    public static final String LIZARD_TAIL = "Lizard Tail";

    /** 蜥蜴尾巴：回到最大生命的 50%（原版 {@code maxHealth / 2}，整除截断）。 */
    private static final int LIZARD_TAIL_PERCENT = 50;

    /** 绽放之印：**完全禁用**救命分支 —— 原版是 {@code if (hasRelic(...)) goto 真死}。 */
    public static final String MARK_OF_THE_BLOOM = "Mark of the Bloom";

    /** 魔法花：战斗中受到的治疗 +50%（{@code AbstractRelic.onPlayerHeal} 链里唯一放大治疗的遗物）。 */
    public static final String MAGIC_FLOWER = "Magic Flower";

    private DeathSaveRelic() {
    }

    /**
     * 玩家血量被打到 0 时调用：按原版顺序**依次**问瓶中仙子、蜥蜴尾巴。
     *
     * <p>调用点必须覆盖**所有**把玩家血量扣到 0 的地方：怪物攻击（{@code CombatFlow.hitPlayer}）、
     * 自伤（{@code Effects.loseHp}）、回合末能力自伤（{@code PowerHooks} 的燃烧）。
     *
     * <p>原版顺序（{@code AbstractPlayer.damage}，字节码 {@code 952-1102}）：
     * <pre>
     * if (currentHealth &lt; 1) {
     *     if (hasRelic("Mark of the Bloom"))        goto 真死;   // 连消耗都不做
     *     if (hasPotion("FairyPotion"))             { 用掉那一瓶; return; }   // ← 优先
     *     else if (hasRelic("Lizard Tail") &amp;&amp; counter == -1) { currentHealth = 0; onTrigger(); return; }
     *     真死;
     * }
     * </pre>
     *
     * <p>⚠️ 是 {@code else if} 而不是「先试仙子再试尾巴」：**有仙子就一定用仙子**，
     * 不会降级到尾巴；用完一瓶才会在下一次致死时考虑尾巴。
     */
    public static void applyIfLethal(SimState state) {
        if (state.hp > 0 || state.deathSaveUsed) {
            return;
        }
        // 绽放之印：原版直接跳过整个救命分支，尾巴既不触发也不消耗
        if (state.relics.containsKey(MARK_OF_THE_BLOOM)) {
            return;
        }
        // ① 瓶中仙子：优先，且是 else-if（不在这里 fall through 到尾巴）
        int fairySlot = fairySlotIn(state);
        if (fairySlot >= 0) {
            // 原版先把 currentHealth 置 0 再 use()，所以回血从 0 起算
            int restored = healedAmount(state, Potions.fairyHealAmount(state.maxHp));
            state.hp = Math.min(state.maxHp, restored);
            state.deathSaveHpRestored += state.hp;
            state.deathSaveUseCount++;
            state.potions.set(fairySlot, null);      // TopPanel.destroyPotion(slot)
            return;
        }
        // ② 蜥蜴尾巴：一次性
        if (!state.relics.containsKey(LIZARD_TAIL)) {
            return;
        }
        int restored = healedAmount(state, Math.max(1, state.maxHp * LIZARD_TAIL_PERCENT / 100));
        // 原版先把 currentHealth 置 0 再 heal，所以复活结果就是这次治疗量（受上限截断）
        state.hp = Math.min(state.maxHp, restored);
        state.deathSaveUsed = true;
        state.deathSaveUseCount++;
        state.deathSaveHpRestored += state.hp;
    }

    /** 找第一瓶瓶中仙子所在的槽位（原版 {@code hasPotion("FairyPotion")} 后遍历找那一瓶）。 */
    private static int fairySlotIn(SimState state) {
        for (int i = 0; i < state.potions.size(); i++) {
            if (Potions.FAIRY_POTION.equals(state.potions.get(i))) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 复刻 {@code AbstractCreature.heal} 里的遗物结算链 —— 只有魔法花会放大治疗量。
     *
     * <p>原版 {@code MagicFlower.onPlayerHeal}：仅在 {@code RoomPhase.COMBAT} 期间
     * {@code MathUtils.round(amount * 1.5f)}，其余场合原样返回。这里只被战斗内路径调用。
     */
    private static int healedAmount(SimState state, int amount) {
        if (state.relics.containsKey(MAGIC_FLOWER)) {
            // libGDX MathUtils.round(float) = (int) floor(x + 0.5f)
            return (int) Math.floor(amount * 1.5f + 0.5f);
        }
        return amount;
    }
}
