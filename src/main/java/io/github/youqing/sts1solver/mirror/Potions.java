package io.github.youqing.sts1solver.mirror;

import io.github.youqing.sts1solver.sim.SimMonster;
import io.github.youqing.sts1solver.sim.SimState;

/**
 * 战斗中药水的语义 —— 对应蓝本 {@code SimulatedCombatState.Potions} +
 * {@code PotionChoiceMirrors} 的第一块（**镜像层**，不含估值策略）。
 *
 * <p>药水在 STS1 是**槽位制**：{@code AbstractPlayer.potions} 是一张固定长度的列表，
 * 用掉一格就空出来，捡到新的才填进去。药水可以被丢弃，所以「槽位空」本身也是状态。
 *
 * <h2>为什么先只做 4 张</h2>
 *
 * <p>STS1 有 40+ 张药水，语义差别很大（生成类、选择类、被动类……）。
 * 这 4 张覆盖了三种基本形态，且**瓶中仙子正好是死亡豁免的另一半**（见
 * {@code DeathSaveRelic}）—— 接完它，死亡豁免才完整：
 *
 * <ul>
 *   <li>{@link #FIRE_POTION} 火药水：指向性伤害（要选目标）</li>
 *   <li>{@link #BLOCK_POTION} 格挡药水：自身增益（走格挡管线）</li>
 *   <li>{@link #ENERGY_POTION} 能量药水：资源增益</li>
 *   <li>{@link #FAIRY_POTION} 瓶中仙子：被动触发（{@code canUse() == false}，不能主动用）</li>
 * </ul>
 *
 * <p>语义全部按字节码核实（{@code com.megacrit.cardcrawl.potions.*}），见
 * {@code mechanics-reference} §25。
 */
public final class Potions {

    /**
     * STS1 药水的运行时 ID（{@code AbstractPotion.POTION_ID}，javap 逐个核实）。
     *
     * <p>⚠️ 格式**不统一**：火/格挡/能量药水带空格，瓶中仙子<b>不带</b>。
     * 记错的话对拍会一直失配，而且看不出原因。
     */
    public static final String FIRE_POTION = "Fire Potion";
    public static final String BLOCK_POTION = "Block Potion";
    public static final String ENERGY_POTION = "Energy Potion";
    public static final String FAIRY_POTION = "FairyPotion";

    /**
     * 火药水伤害（{@code FirePotion.getPotency} → {@code bipush 20}）。
     * 不随升级变，也不吃玩家的力量。
     */
    private static final int FIRE_POTION_DAMAGE = 20;

    /** 格挡药水格挡量（{@code BlockPotion.getPotency} → {@code bipush 12}）。 */
    private static final int BLOCK_POTION_BLOCK = 12;

    /** 能量药水（{@code EnergyPotion.getPotency} → {@code iconst_2}）。 */
    private static final int ENERGY_POTION_ENERGY = 2;

    /** 瓶中仙子回血百分比（{@code FairyPotion.getPotency} → {@code bipush 30}）。 */
    private static final int FAIRY_POTION_PERCENT = 30;

    /** 战斗中的默认药水槽上限（{@code PotionSlot}：铁甲战士 2 格）。 */
    public static final int DEFAULT_POTION_SLOTS = 2;

    private Potions() {
    }

    /**
     * 这张药水能不能被主动使用。
     *
     * <p>{@code FairyPotion.canUse()} 直接 {@code return false}：
     * 瓶中仙子**只能**在死亡分支里自动触发（见 {@code DeathSaveRelic}），
     * 面板上看到它但点不动。
     */
    public static boolean canUse(String potionId) {
        return !FAIRY_POTION.equals(potionId);
    }

    /** 这张药水要选目标吗（火药水）。 */
    public static boolean needsTarget(String potionId) {
        return FIRE_POTION.equals(potionId);
    }

    /**
     * 打出一张主动药水。返回 {@code true} 表示成功消耗。
     *
     * <p>顺序照原版 {@code AbstractPotion.use(target)}：
     * 先算效果，再 {@code TopPanel.destroyPotion(slot)} 清槽位。
     * 这里由调用方负责清槽（{@code BeamSolver} 在动作里做）。
     *
     * <h2>火药水的伤害管线</h2>
     *
     * <p>原版是 {@code new DamageInfo(player, potency, DamageType.THORNS)
     * + applyEnemyPowersOnly(target)}：
     * 只应用**受击方**能力（易伤 ×1.5，向下取整），**不吃玩家的力量**，
     * 也不被玩家的虚弱打折（虚弱挂在攻击者身上，这里不走攻击流程）。
     */
    public static boolean use(SimState state, String potionId, int targetIndex) {
        if (FAIRY_POTION.equals(potionId)) {
            return false;                       // 被动药水，不能主动用
        }
        if (FIRE_POTION.equals(potionId)) {
            SimMonster target = monsterAt(state, targetIndex);
            if (target == null || target.hp <= 0) {
                return false;
            }
            int dmg = FIRE_POTION_DAMAGE;
            Integer vuln = target.powers.get(DamageCalc.VULNERABLE);
            if (vuln != null && vuln > 0) {
                // 1.5 倍，向下取整（原版 applyEnemyPowersOnly 里是 f2i）
                dmg = (int) Math.floor(dmg * 1.5f);
            }
            DamageCalc.hit(state, target, dmg);
            return true;
        }
        if (BLOCK_POTION.equals(potionId)) {
            state.block += DamageCalc.blockAmount(state, BLOCK_POTION_BLOCK);
            return true;
        }
        if (ENERGY_POTION.equals(potionId)) {
            state.energy += ENERGY_POTION_ENERGY;
            return true;
        }
        // 未知药水：显式拒绝，不静默算错（D2 风险点）
        throw new ActionRejected("Potion " + potionId + " -> no mirror");
    }

    /**
     * 瓶中仙子的回血量（{@code FairyPotion.use}）：
     * {@code max(1, (int)(maxHp * 30 / 100.0f))} —— <b>向零截断</b>，不是四舍五入。
     *
     * <p>调用时机是死亡分支里 {@code currentHealth = 0} **之后**（见 {@code DeathSaveRelic}），
     * 所以从 0 起算，实际血量就是这个值。
     */
    public static int fairyHealAmount(int maxHp) {
        return Math.max(1, (int) (maxHp * FAIRY_POTION_PERCENT / 100.0f));
    }

    private static SimMonster monsterAt(SimState state, int targetIndex) {
        if (targetIndex < 0 || targetIndex >= state.monsters.size()) {
            return null;
        }
        return state.monsters.get(targetIndex);
    }
}
