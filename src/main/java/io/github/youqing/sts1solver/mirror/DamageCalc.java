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
     */
    public static void hit(SimState state, SimMonster target, int damage) {
        hitAndReturnDealt(target, damage);
    }

    /** 同上，但返回**实际扣掉的血量**（吸血类卡牌需要）。 */
    public static int hitAndReturnDealt(SimMonster target, int damage) {
        int remaining = damage;
        if (target.block > 0) {
            int absorbed = Math.min(target.block, remaining);
            target.block -= absorbed;
            remaining -= absorbed;
        }
        target.hp -= remaining;
        if (target.hp < 0) {
            target.hp = 0;
        }
        return remaining;
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
