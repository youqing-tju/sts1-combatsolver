package io.github.youqing.sts1solver.mirror;

import io.github.youqing.sts1solver.sim.SimMonster;
import io.github.youqing.sts1solver.sim.SimState;

/**
 * The vanilla damage pipeline, reduced to what a single-player Ironclad fight needs.
 *
 * <p>Order matters and follows the game: base → Strength → Weak (attacker, ×0.75) →
 * Vulnerable (target, ×1.5), flooring at each multiplicative step. Getting the order or
 * the rounding wrong produces plausible-looking but wrong numbers, which is the single
 * hardest class of bug to notice later, so each step is unit-tested.
 */
public final class DamageCalc {

    public static final String STRENGTH = "Strength";
    public static final String DEXTERITY = "Dexterity";
    public static final String VULNERABLE = "Vulnerable";
    public static final String WEAKENED = "Weakened";
    public static final String FRAIL = "Frail";

    private DamageCalc() {
    }

    /** Damage an attack card deals after attacker and target modifiers. */
    public static int attackDamage(SimState state, int base, SimMonster target) {
        int dmg = attackDamageWithoutRelics(state, base, target);
        // 笔尖：计数为 9 时打出的这一击（= 第 10 击）翻倍。原版是 atDamageGive，
        // 作用在力量/虚弱/易伤之后，所以放在最后一步。
        Integer penNib = state.relics.get("Pen Nib");
        if (penNib != null && penNib == 9) {
            dmg = (int) Math.floor(dmg * 2.0f);
        }
        return Math.max(0, dmg);
    }

    private static int attackDamageWithoutRelics(SimState state, int base, SimMonster target) {
        int dmg = base + power(state, STRENGTH);
        if (power(state, WEAKENED) > 0) {
            dmg = (int) Math.floor(dmg * 0.75f);
        }
        if (target != null && powers(target).containsKey(VULNERABLE)
                && powers(target).get(VULNERABLE) > 0) {
            dmg = (int) Math.floor(dmg * 1.5f);
        }
        return Math.max(0, dmg);
    }

    /** Block gained from a skill card. */
    public static int blockAmount(SimState state, int base) {
        int amount = base + power(state, DEXTERITY);
        if (power(state, FRAIL) > 0) {
            amount = (int) Math.floor(amount * 0.75f);
        }
        return Math.max(0, amount);
    }

    /**
     * Applies damage to a monster: block soaks first, the rest hits HP.
     * Dead monsters are removed afterwards (the game keeps them during the death
     * animation, so expect a difference if you diff mid-animation).
     *
     * <p>打完还要结算**荆棘反伤**（{@code ThornsPower.onAttacked}）：
     * 有攻击者、且不是 THORNS/HP_LOSS 类型时，把 {@code amount} 点伤害反弹给攻击者。
     * 反伤走玩家的格挡/血量，会触发死亡豁免。
     */
    public static void hit(SimState state, SimMonster target, int damage) {
        hitAndReturnDealt(target, damage);
        applyThorns(state, target);
    }

    /**
     * 荆棘反伤（{@code ThornsPower.onAttacked}）。
     *
     * <p>原版判据是 {@code info.owner != null && info.owner != owner
     * && type != THORNS && type != HP_LOSS} —— 也就是**只有普通攻击**会触发反伤，
     * 荆棘打荆棘、自伤都不反。我们这条路径只有普通攻击（自伤走 {@code Effects.loseHp}、
     * 药水走 THORNS 类型），所以恒过。
     *
     * <p>反伤也走 {@code hitPlayer}（吃玩家格挡），所以会触发死亡豁免。
     */
    private static void applyThorns(SimState state, SimMonster target) {
        Integer thorns = target.powers.get(THORNS);
        if (thorns == null || thorns <= 0) {
            return;
        }
        io.github.youqing.sts1solver.sim.CombatFlow.hitPlayer(state, thorns);
    }

    /** 同上，但返回**实际扣掉的血量**（吸血类卡牌需要）。 */
    public static int hitAndReturnDealt(SimMonster target, int damage) {
        int hpBefore = target.hp;
        int remaining = intangibleAdjusted(target.powers, flightAdjusted(target, damage));
        if (target.block > 0) {
            int absorbed = Math.min(target.block, remaining);
            target.block -= absorbed;
            remaining -= absorbed;
        }
        target.hp -= remaining;
        if (target.hp < 0) {
            target.hp = 0;
        }
        tickFlight(target);
        tickPlatedArmor(target, damage);
        tickAngry(target, damage);
        tickCurlUp(target, damage, hpBefore);
        return remaining;
    }

    /**
     * 蜷缩（{@code CurlUpPower.onAttacked}）：受伤时加 {@code amount} 格挡，**只触发一次**。
     *
     * <p>原版判据：{@code !triggered && damageAmount < owner.currentHealth
     * && damageAmount > 0 && info.owner != null && info.type == NORMAL}
     * —— 也就是**没打死、且是普通攻击**才蜷缩。虱虫（Louse）有。
     *
     * <p>{@code triggered} 在我们这里是「把 Curl Up 从 powers 里移除」来表达
     * （一次性能力用完即弃，和飞行/装甲的层数制不同）。
     */
    private static void tickCurlUp(SimMonster target, int damage, int hpBefore) {
        if (damage <= 0 || damage >= hpBefore) {
            return;
        }
        Integer curl = target.powers.get("Curl Up");
        if (curl == null || curl <= 0) {
            return;
        }
        target.block += curl;
        target.powers.remove("Curl Up");
    }

    /**
     * 愤怒（{@code AngryPower.onAttacked}）：受击时 +amount 点力量。
     *
     * <p>原版判据：{@code info.owner != null && damageAmount > 0
     * && type != HP_LOSS && type != THORNS} —— 也就是**只有普通攻击**触发。
     * 0 伤不触发（和装甲同一条）。
     */
    private static void tickAngry(SimMonster target, int damage) {
        if (damage <= 0) {
            return;
        }
        Integer angry = target.powers.get("Angry");
        if (angry == null || angry <= 0) {
            return;
        }
        Integer str = target.powers.get("Strength");
        target.powers.put("Strength", (str == null ? 0 : str) + angry);
    }

    /** 飞行（{@code FlightPower}）：普通攻击伤害减半，HP_LOSS / THORNS 不减。 */
    public static final String FLIGHT = "Flight";
    /** 无实体：所有伤害上限 1（{@code IntangiblePower}）。 */
    public static final String INTANGIBLE = "Intangible";

    /** 荆棘（{@code ThornsPower}）：受击反弹 {@code amount} 点给攻击者。 */
    public static final String THORNS = "Thorns";

    /**
     * 装甲（{@code PlatedArmorPower}）：回合末加 {@code amount} 格挡，
     * 每次受伤掉 1 层（{@code wasHPLost}）。
     *
     * <p>原版 {@code wasHPLost} 的判据是 {@code info.owner != null && info.owner != owner
     * && type != HP_LOSS && type != THORNS && damageAmount > 0} ——
     * 也就是**只有普通攻击**掉层。我们这条路径只有普通攻击，所以恒过。
     */
    public static final String PLATED_ARMOR = "Plated Armor";

    /**
     * 飞行减伤（{@code FlightPower.calculateDamageTakenAmount}）：
     * {@code type == HP_LOSS || type == THORNS ? dmg : dmg / 2.0f}，
     * 最后 {@code f2i}（向零截断）。
     *
     * <p>我们只在**普通攻击**这条路径上调用，所以恒走减半分支；
     * 自伤/荆棘那两条不经过这里。
     */
    private static int flightAdjusted(SimMonster target, int damage) {
        Integer flight = target.powers.get(FLIGHT);
        if (flight == null || flight <= 0) {
            return damage;
        }
        return (int) (damage / 2.0f);
    }

    /**
     * 无实体减伤（{@code IntangiblePower.atDamageFinalReceive}）：
     * {@code if (dmg > 1.0f) return 1.0f; return dmg;} —— **所有伤害上限 1**。
     *
     * <p>在**最终收到的伤害**上生效（所有修正之后、扣格挡之前）：
     * 所以 1 点伤害仍会被格挡完全吸收。
     *
     * <p>注意这是「封顶」不是「归零」：0 伤保持 0，1 伤保持 1。
     * 玩家和怪物共用这一条（原版是同一个 power 类）。
     */
    public static int intangibleAdjusted(java.util.Map<String, Integer> powers, int damage) {
        if (powers == null) {
            return damage;
        }
        Integer intangible = powers.get(INTANGIBLE);
        if (intangible == null || intangible <= 0) {
            return damage;
        }
        return Math.min(damage, 1);
    }

    /**
     * 无实体回合末递减（{@code IntangiblePower.atEndOfTurn}）：{@code amount--}，到 0 移除。
     *
     * <p>⚠️ 原版有 {@code justApplied} 保护（刚施加的那回合不递减），我们没记这个标志 ——
     * 施加那回合会多递减一次。偏差方向是**低估防御**（不会让 AI 以为无实体更值钱）。
     */
    public static void tickIntangible(java.util.Map<String, Integer> powers) {
        if (powers == null) {
            return;
        }
        Integer intangible = powers.get(INTANGIBLE);
        if (intangible == null || intangible <= 0) {
            return;
        }
        if (intangible <= 1) {
            powers.remove(INTANGIBLE);
        } else {
            powers.put(INTANGIBLE, intangible - 1);
        }
    }

    /**
     * 打中一次后递减装甲层数（{@code PlatedArmorPower.wasHPLost}）。
     *
     * <p>原版判据里有一个 {@code damageAmount > 0}（0 伤不掉层），
     * 以及「有攻击者、非 THORNS/HP_LOSS」—— 我们这条路径只有普通攻击，恒过。
     */
    private static void tickPlatedArmor(SimMonster target, int damage) {
        if (damage <= 0) {
            return;
        }
        Integer plated = target.powers.get(PLATED_ARMOR);
        if (plated == null || plated <= 0) {
            return;
        }
        int left = plated - 1;
        if (left <= 0) {
            target.powers.remove(PLATED_ARMOR);
        } else {
            target.powers.put(PLATED_ARMOR, left);
        }
    }

    /**
     * 打中一次后递减飞行层数（{@code FlightPower.onAttacked}）。
     *
     * <p>原版只在 {@code info.owner != null}（有攻击者）时递减 —— 自伤/荆棘不掉层。
     * 还有一个「减伤后伤害 < 当前血量」的边界判据（{@code willKill}），
     * 只影响「刚好打死」的那一下；这里简化成每次普通攻击命中掉 1 层，
     * 差异只在边界（打死不打死都掉层），已记进 docs §26。
     */
    private static void tickFlight(SimMonster target) {
        Integer flight = target.powers.get(FLIGHT);
        if (flight == null || flight <= 0) {
            return;
        }
        int left = flight - 1;
        if (left <= 0) {
            target.powers.remove(FLIGHT);
        } else {
            target.powers.put(FLIGHT, left);
        }
    }

    /** 力量按倍率生效（重刃：力量 ×3/×5）。 */
    public static int attackDamageWithStrengthMult(SimState state, int base, int mult,
                                                   SimMonster target) {
        int dmg = base + power(state, STRENGTH) * mult;
        if (power(state, WEAKENED) > 0) {
            dmg = (int) Math.floor(dmg * 0.75f);
        }
        if (target != null && powers(target).containsKey(VULNERABLE)
                && powers(target).get(VULNERABLE) > 0) {
            dmg = (int) Math.floor(dmg * 1.5f);
        }
        return Math.max(0, dmg);
    }

    private static int power(SimState state, String id) {
        Integer v = state.playerPowers.get(id);
        return v == null ? 0 : v;
    }

    private static java.util.Map<String, Integer> powers(SimMonster m) {
        return m.powers;
    }
}
