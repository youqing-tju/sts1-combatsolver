package io.github.youqing.sts1solver.mirror;

import io.github.youqing.sts1solver.sim.SimMonster;
import io.github.youqing.sts1solver.sim.SimState;
import org.junit.jupiter.api.Test;

import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 腐化之心的 {@code Invincible}：**每回合最多掉 amount 点**，且对**所有**伤害来源生效。
 *
 * <p>2026-11-02 真机（心脏战）：`monsters[0].hp: 387 vs 441`、`Invincible: 0 vs 54` ——
 * 影子把上限打穿了 54 点。根因是上限只挂在 `hit()`，而多段 / AoE / 逐张结算走的是
 * `hitAndReturnDealt`，全部绕过。
 */
class InvincibleCapTest {

    private static final int START_HP = 750;

    @Test
    void capAppliesToEveryHitPathNotJustHit() {
        SimState state = combat();
        SimMonster heart = state.monsters.get(0);
        heart.powers.put("Invincible", Integer.valueOf(10));

        // 逐张结算路径（Fiend Fire / Sword Boomerang 用的就是它）：3 次 20 伤
        for (int i = 0; i < 3; i++) {
            DamageCalc.hitAndReturnDealt(state, heart, 20);
        }

        assertEquals(START_HP - 10, heart.hp, "一回合累计只能掉 10 点，多出来的不算");
        assertEquals(0, heart.powers.get("Invincible").intValue());
    }

    @Test
    void capIsSharedBetweenHitAndTheDirectPath() {
        SimState state = combat();
        SimMonster heart = state.monsters.get(0);
        heart.powers.put("Invincible", Integer.valueOf(10));

        DamageCalc.hit(state, heart, 6);            // 走 hit()
        DamageCalc.hitAndReturnDealt(state, heart, 6);   // 走直连

        assertEquals(START_HP - 10, heart.hp, "两条路径共用一个每回合额度");
    }

    /** 上限重置由 CombatFlow 的回合开始负责；重置之后又能掉满一额度。 */
    @Test
    void resetHappensAtStartOfRound() {
        SimState state = combat();
        SimMonster heart = state.monsters.get(0);
        heart.invincibleMax = 10;
        heart.powers.put("Invincible", Integer.valueOf(10));
        DamageCalc.hitAndReturnDealt(state, heart, 99);
        assertEquals(START_HP - 10, heart.hp);

        io.github.youqing.sts1solver.sim.CombatFlow.endTurn(state, 3, 5);

        assertEquals(10, heart.powers.get("Invincible").intValue(),
                "回合开始要把额度重置成 maxAmt");
    }

    private static SimState combat() {
        SimState state = new SimState();
        state.turn = 1;
        state.hp = 80;
        state.maxHp = 80;
        state.energy = 3;
        state.energyPerTurn = 3;
        SimMonster heart = new SimMonster("CorruptHeart", START_HP, START_HP, 0,
                new TreeMap<String, Integer>());
        state.monsters.add(heart);
        return state;
    }
}
