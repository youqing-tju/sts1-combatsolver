package io.github.youqing.sts1solver.mirror;

import io.github.youqing.sts1solver.sim.SimCard;
import io.github.youqing.sts1solver.sim.SimMonster;
import io.github.youqing.sts1solver.sim.SimState;

/**
 * Factory for the atomic effects cards are built from. Keeping these tiny and explicit
 * means a card definition reads like the card text.
 */
public final class Effects {

    /** 手牌上限，见 docs/research/mechanics-reference.md §6（A｜字节码 bipush 10）。 */
    public static final int HAND_LIMIT = 10;

    private Effects() {
    }

    public static Effect damage(final int base) {
        return new Effect() {
            @Override
            public void applyTo(SimState state, int targetIndex, SimCard played) {
                SimMonster target = requireTarget(state, targetIndex);
                DamageCalc.hit(state, target, DamageCalc.attackDamage(state, base, target));
            }

            @Override
            public String toString() {
                return "damage(" + base + ")";
            }
        };
    }

    /** 多段攻击：每段单独结算，力量与易伤逐段生效。 */
    public static Effect damageTimes(final int times, final int base) {
        return new Effect() {
            @Override
            public void applyTo(SimState state, int targetIndex, SimCard played) {
                SimMonster target = requireTarget(state, targetIndex);
                for (int i = 0; i < times; i++) {
                    DamageCalc.hit(state, target, DamageCalc.attackDamage(state, base, target));
                }
            }

            @Override
            public String toString() {
                return "damage(" + base + "x" + times + ")";
            }
        };
    }

    /** 群体攻击：对每个存活怪物各结算一次。 */
    public static Effect damageAll(final int base) {
        return new Effect() {
            @Override
            public void applyTo(SimState state, int targetIndex, SimCard played) {
                for (SimMonster m : state.monsters) {
                    if (m.hp > 0) {
                        DamageCalc.hit(state, m, DamageCalc.attackDamage(state, base, m));
                    }
                }
            }

            @Override
            public String toString() {
                return "damageAll(" + base + ")";
            }
        };
    }

    public static Effect block(final int base) {
        return new Effect() {
            @Override
            public void applyTo(SimState state, int targetIndex, SimCard played) {
                state.block += DamageCalc.blockAmount(state, base);
            }

            @Override
            public String toString() {
                return "block(" + base + ")";
            }
        };
    }

    /** 抽牌。手牌满 10 张不再抽；需要洗牌时显式拒绝（洗牌算法尚未镜像）。 */
    public static Effect draw(final int count) {
        return new Effect() {
            @Override
            public void applyTo(SimState state, int targetIndex, SimCard played) {
                for (int i = 0; i < count; i++) {
                    if (state.hand.size() >= HAND_LIMIT) {
                        return;
                    }
                    if (state.draw.isEmpty()) {
                        if (state.discard.isEmpty()) {
                            return;
                        }
                        throw new ActionRejected("draw needs a reshuffle (shuffle RNG not mirrored yet)");
                    }
                    state.hand.add(state.draw.remove(0));
                }
            }

            @Override
            public String toString() {
                return "draw(" + count + ")";
            }
        };
    }

    /** 把这张牌的一份复制放进弃牌堆（愤怒）。 */
    public static Effect copySelfToDiscard() {
        return new Effect() {
            @Override
            public void applyTo(SimState state, int targetIndex, SimCard played) {
                if (played == null) {
                    return;
                }
                state.discard.add(new SimCard(played.id, played.upgraded, played.cost));
            }

            @Override
            public String toString() {
                return "copySelfToDiscard()";
            }
        };
    }

    /** 对指定怪物施加状态。 */
    public static Effect powerOnTarget(final String id, final int amount) {
        return new Effect() {
            @Override
            public void applyTo(SimState state, int targetIndex, SimCard played) {
                SimMonster target = requireTarget(state, targetIndex);
                add(target.powers, id, amount);
            }

            @Override
            public String toString() {
                return id + "(" + amount + ")";
            }
        };
    }

    /** 对所有存活怪物施加状态（闪电霹雳）。 */
    public static Effect powerOnAll(final String id, final int amount) {
        return new Effect() {
            @Override
            public void applyTo(SimState state, int targetIndex, SimCard played) {
                for (SimMonster m : state.monsters) {
                    if (m.hp > 0) {
                        add(m.powers, id, amount);
                    }
                }
            }

            @Override
            public String toString() {
                return id + "(all," + amount + ")";
            }
        };
    }

    /** 对玩家自己施加状态。 */
    public static Effect powerOnPlayer(final String id, final int amount) {
        return new Effect() {
            @Override
            public void applyTo(SimState state, int targetIndex, SimCard played) {
                add(state.playerPowers, id, amount);
            }

            @Override
            public String toString() {
                return id + "(self," + amount + ")";
            }
        };
    }

    // ---- 第三批需要的原语 ------------------------------------------------

    /** 自伤：无视格挡，直接扣血（放血、 Offering、血液沸腾）。 */
    public static Effect loseHp(final int amount) {
        return new Effect() {
            @Override
            public void applyTo(SimState state, int targetIndex, SimCard played) {
                state.hp = Math.max(0, state.hp - amount);
            }

            @Override
            public String toString() {
                return "loseHp(" + amount + ")";
            }
        };
    }

    public static Effect heal(final int amount) {
        return new Effect() {
            @Override
            public void applyTo(SimState state, int targetIndex, SimCard played) {
                state.hp = Math.min(state.maxHp, state.hp + amount);
            }

            @Override
            public String toString() {
                return "heal(" + amount + ")";
            }
        };
    }

    public static Effect gainEnergy(final int amount) {
        return new Effect() {
            @Override
            public void applyTo(SimState state, int targetIndex, SimCard played) {
                state.energy += amount;
            }

            @Override
            public String toString() {
                return "energy(+" + amount + ")";
            }
        };
    }

    /** 把状态牌塞进手牌（硬撑 Power Through：2 张伤口）。手牌插入位置是确定的。 */
    public static Effect addStatusToHand(final String id, final int count) {
        return new Effect() {
            @Override
            public void applyTo(SimState state, int targetIndex, SimCard played) {
                for (int i = 0; i < count; i++) {
                    state.hand.add(new SimCard(id, false, -2));
                }
            }

            @Override
            public String toString() {
                return "hand+=" + id + "x" + count;
            }
        };
    }

    /** 壁垒：格挡翻倍。 */
    public static Effect doubleBlock() {
        return new Effect() {
            @Override
            public void applyTo(SimState state, int targetIndex, SimCard played) {
                state.block *= 2;
            }

            @Override
            public String toString() {
                return "block*2";
            }
        };
    }

    /** 全身撞击：伤害等于当前格挡（力量/虚弱/易伤照常生效）。 */
    public static Effect damageEqualToBlock() {
        return new Effect() {
            @Override
            public void applyTo(SimState state, int targetIndex, SimCard played) {
                SimMonster target = requireTarget(state, targetIndex);
                DamageCalc.hit(state, target, DamageCalc.attackDamage(state, state.block, target));
            }

            @Override
            public String toString() {
                return "damage=block";
            }
        };
    }

    /** 突破极限：力量翻倍。 */
    public static Effect doubleStrength() {
        return new Effect() {
            @Override
            public void applyTo(SimState state, int targetIndex, SimCard played) {
                Integer strength = state.playerPowers.get(DamageCalc.STRENGTH);
                if (strength != null && strength > 0) {
                    state.playerPowers.put(DamageCalc.STRENGTH, strength * 2);
                }
            }

            @Override
            public String toString() {
                return "strength*2";
            }
        };
    }

    /** 重刃：力量按倍率计入。 */
    public static Effect damageWithStrengthMult(final int base, final int mult) {
        return new Effect() {
            @Override
            public void applyTo(SimState state, int targetIndex, SimCard played) {
                SimMonster target = requireTarget(state, targetIndex);
                DamageCalc.hit(state, target,
                        DamageCalc.attackDamageWithStrengthMult(state, base, mult, target));
            }

            @Override
            public String toString() {
                return "damage(" + base + "+str*" + mult + ")";
            }
        };
    }

    /** 收割：全体伤害，按实际扣血总量回复。 */
    public static Effect damageAllAndHeal(final int base) {
        return new Effect() {
            @Override
            public void applyTo(SimState state, int targetIndex, SimCard played) {
                int total = 0;
                for (SimMonster m : state.monsters) {
                    if (m.hp > 0) {
                        total += DamageCalc.hitAndReturnDealt(m,
                                DamageCalc.attackDamage(state, base, m));
                    }
                }
                state.hp = Math.min(state.maxHp, state.hp + total);
            }

            @Override
            public String toString() {
                return "damageAll(" + base + ")+heal(dealt)";
            }
        };
    }

    private static SimMonster requireTarget(SimState state, int targetIndex) {        if (targetIndex < 0 || targetIndex >= state.monsters.size()) {
            throw new ActionRejected("effect needs a valid monster target, got index " + targetIndex);
        }
        return state.monsters.get(targetIndex);
    }

    private static void add(java.util.Map<String, Integer> powers, String id, int amount) {
        Integer current = powers.get(id);
        powers.put(id, (current == null ? 0 : current) + amount);
    }
}
