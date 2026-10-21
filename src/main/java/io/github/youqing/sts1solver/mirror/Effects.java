package io.github.youqing.sts1solver.mirror;

import io.github.youqing.sts1solver.sim.SimMonster;
import io.github.youqing.sts1solver.sim.SimState;

/**
 * Factory for the atomic effects cards are built from. Keeping these tiny and explicit
 * means a card definition reads like the card text.
 */
public final class Effects {

    private Effects() {
    }

    public static Effect damage(final int base) {
        return new Effect() {
            @Override
            public void applyTo(SimState state, int targetIndex) {
                SimMonster target = requireTarget(state, targetIndex);
                DamageCalc.hit(state, target, DamageCalc.attackDamage(state, base, target));
            }

            @Override
            public String toString() {
                return "damage(" + base + ")";
            }
        };
    }

    public static Effect block(final int base) {
        return new Effect() {
            @Override
            public void applyTo(SimState state, int targetIndex) {
                state.block += DamageCalc.blockAmount(state, base);
            }

            @Override
            public String toString() {
                return "block(" + base + ")";
            }
        };
    }

    /** Applies stacks of a power to the target monster. */
    public static Effect powerOnTarget(final String id, final int amount) {
        return new Effect() {
            @Override
            public void applyTo(SimState state, int targetIndex) {
                SimMonster target = requireTarget(state, targetIndex);
                add(target.powers, id, amount);
            }

            @Override
            public String toString() {
                return id + "(" + amount + ")";
            }
        };
    }

    /** Applies stacks of a power to the player. */
    public static Effect powerOnPlayer(final String id, final int amount) {
        return new Effect() {
            @Override
            public void applyTo(SimState state, int targetIndex) {
                add(state.playerPowers, id, amount);
            }

            @Override
            public String toString() {
                return id + "(self," + amount + ")";
            }
        };
    }

    private static SimMonster requireTarget(SimState state, int targetIndex) {
        if (targetIndex < 0 || targetIndex >= state.monsters.size()) {
            throw new ActionRejected("effect needs a valid monster target, got index " + targetIndex);
        }
        return state.monsters.get(targetIndex);
    }

    private static void add(java.util.Map<String, Integer> powers, String id, int amount) {
        Integer current = powers.get(id);
        powers.put(id, (current == null ? 0 : current) + amount);
    }
}
