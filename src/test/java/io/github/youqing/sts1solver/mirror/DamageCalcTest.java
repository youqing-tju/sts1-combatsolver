package io.github.youqing.sts1solver.mirror;

import io.github.youqing.sts1solver.sim.SimMonster;
import io.github.youqing.sts1solver.sim.SimState;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 重刃（Heavy Blade）的「力量 ×倍率」入口必须和普通攻击入口共用同一条尾部链：
 * 笔尖翻倍 → floor → 靴子下限。
 *
 * <p>原版依据（{@code com/megacrit/cardcrawl/cards/red/HeavyBlade.use} 字节码）：
 * {@code baseDamage += strength * magicNumber} 之后，仍然走
 * {@code AbstractCard.calculateCardDamage} 的正常流程 —— 遗物 {@code atDamageModify} →
 * 玩家 powers 的 {@code atDamageGive}（力量 / Vigor / 虚弱）。笔尖是
 * {@code PenNibPower.atDamageGive}，因此翻倍发生在**力量乘完之后**；靴子是
 * {@code AbstractMonster.damage()} 里的抬升，发生在算完伤害之后、落地之前。
 * 也就是 {@code (14 + 18*5) × 2 = 208}，不是 104。
 */
class DamageCalcTest {

    /** 力量 18、笔尖计数 9、目标无易伤/虚弱。 */
    private static SimState penNibTurn() {
        SimState s = new SimState();
        s.hp = 80;
        s.maxHp = 80;
        s.energy = 3;
        s.turn = 1;
        s.relics.put("Pen Nib", 9);
        s.playerPowers.put(DamageCalc.STRENGTH, 18);
        s.monsters.add(new SimMonster("Cultist", 999, 999, 0,
                Collections.<String, Integer>emptyMap()));
        return s;
    }

    /** 重刃+：base 14、力量倍率 ×5，笔尖那一下要翻倍：(14 + 18*5) × 2 = 208。 */
    @Test
    void heavyBladePlusDoublesAfterStrengthOnPenNibTurn() {
        SimState s = penNibTurn();
        assertEquals(208, DamageCalc.attackDamageWithStrengthMult(s, 14, 5, s.monsters.get(0)));
    }

    /** 同一状态下普通攻击入口（打击 base 6）：(6 + 18) × 2 = 48，两个入口必须一致。 */
    @Test
    void normalAttackEntryDoublesOnTheSamePenNibTurn() {
        SimState s = penNibTurn();
        assertEquals(48, DamageCalc.attackDamage(s, 6, s.monsters.get(0)));
    }

    /** 回归：笔尖不为 9 时重刃+ 不翻倍，就是 14 + 18*5 = 104。 */
    @Test
    void heavyBladePlusWithoutPenNibStaysAtStrengthValue() {
        SimState s = penNibTurn();
        s.relics.put("Pen Nib", 8);
        assertEquals(104, DamageCalc.attackDamageWithStrengthMult(s, 14, 5, s.monsters.get(0)));
    }
}
