package io.github.youqing.sts1solver.mirror;

import java.util.HashMap;
import java.util.Map;

/**
 * 药水的**战略成本** —— 照蓝本 {@code PotionValuationRegistry} + {@code PotionUsePolicy.StrategicHpCost}
 * （{@code src/Search/PotionValuationRegistry.cs:58-70}、{@code PotionUsePolicy.cs:22-37}）。
 *
 * <p>它回答一个问题：**这瓶药水值多少血？** 喝掉它，至少要在本场少掉这么多血才划算。
 * 蓝本把它写成「三档 + 每档一个血量门槛」，数值来自 {@code SolverWeights}：
 *
 * <pre>
 * PotionMinimumHpSaved        = 9                       // 基准档
 * PotionElevatedValueHpSaved  = (9 * 3 + 1) / 2 = 14    // 溢价档
 * PotionHighValueHpSaved      = 9 * 2 = 18              // 高价值档
 * </pre>
 *
 * <p><b>为什么要有这个</b>：搜索一旦能自由喝药，就会拿药水去换一两点血（比如「喝火药早一回合结束」），
 * 而药水是稀缺资源。蓝本的做法是让「喝药」必须**自付门槛**：这条路线相比不喝药的路线
 * 至少少掉 {@code Σ strategicHpCost} 血，否则退回不喝药的路线。
 *
 * <h2>STS1 的三档映射（⚠️ 待核对）</h2>
 *
 * <p>蓝本是按 STS2 药水 ID 写死的档位表。STS1 这边只把**效果同形**的三瓶对上，
 * 其余一律按基准档（宁可低估价值、不轻易喝）：
 *
 * <table>
 *   <tr><th>STS1 药水</th><th>档</th><th>门槛</th><th>蓝本对应</th></tr>
 *   <tr><td>Fruit Juice（+5 最大生命）</td><td>HIGH</td><td>18</td>
 *       <td>{@code GLOWWATER_POTION}（永久最大生命）</td></tr>
 *   <tr><td>GamblersBrew（弃任意张再抽等量）</td><td>HIGH</td><td>18</td>
 *       <td>{@code GAMBLERS_BREW}（同名）</td></tr>
 *   <tr><td>LiquidMemories（弃牌堆取回一张）</td><td>ELEVATED</td><td>14</td>
 *       <td>{@code LIQUID_MEMORIES}（同名）</td></tr>
 *   <tr><td>其余（火/格挡/能量/血药/毒/恐惧/爆炸/力量/敏捷/铁心/液态青铜/古法/
 *       灵药/三选一…）</td><td>BASELINE</td><td>9</td><td>默认档</td></tr>
 * </table>
 *
 * <p>瓶中仙子（{@code FairyPotion}）是**被动**死亡豁免，玩家点不动，不进这张表
 * （{@code Potions.canUse} 会挡掉它）。
 */
public final class PotionValuation {

    /** 蓝本 {@code SolverWeights.PotionMinimumHpSaved}。 */
    public static final int BASELINE_HP_COST = 9;
    /** 蓝本 {@code PotionElevatedValueHpSaved = (Minimum * 3 + 1) / 2}。 */
    public static final int ELEVATED_HP_COST = (BASELINE_HP_COST * 3 + 1) / 2;
    /** 蓝本 {@code PotionHighValueHpSaved = Minimum * 2}。 */
    public static final int HIGH_HP_COST = BASELINE_HP_COST * 2;

    /** 蓝本 {@code PotionValueTier}。 */
    public enum Tier {
        BASELINE,
        ELEVATED,
        HIGH
    }

    private static final Map<String, Tier> TIERS = new HashMap<String, Tier>();

    static {
        TIERS.put("Fruit Juice", Tier.HIGH);
        TIERS.put("GamblersBrew", Tier.HIGH);
        TIERS.put("LiquidMemories", Tier.ELEVATED);
    }

    private PotionValuation() {
    }

    /** 这瓶药水的档位；没登记的按基准档（蓝本 {@code GetValueOrDefault(potion.Id) → default}）。 */
    public static Tier tierOf(String potionId) {
        Tier tier = potionId == null ? null : TIERS.get(potionId);
        return tier == null ? Tier.BASELINE : tier;
    }

    /** 蓝本 {@code PotionValuationRegistry.StrategicHpCost}：喝掉它至少要买回多少血。 */
    public static int strategicHpCost(String potionId) {
        switch (tierOf(potionId)) {
            case HIGH:
                return HIGH_HP_COST;
            case ELEVATED:
                return ELEVATED_HP_COST;
            default:
                return BASELINE_HP_COST;
        }
    }
}
