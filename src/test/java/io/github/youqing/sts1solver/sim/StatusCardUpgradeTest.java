package io.github.youqing.sts1solver.sim;

import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 回合末状态牌的**升级态**数值 —— 全部按 {@code lib/desktop-1.0.jar} 的字节码核实。
 *
 * <pre>
 * Burn  构造器 magicNumber = 2；upgrade() → upgradeMagicNumber(2) → 4
 *       use() 走 magicNumber（DamageInfo THORNS，吃格挡）
 * Decay 构造器**没有** magicNumber；use() 里 iconst_2 写死 2；
 *       upgrade() 是 `0: return` 空实现 → 原版不存在 Decay+，数值恒为 2
 * </pre>
 *
 * <p>所以「升级态」只有 Burn 需要读，Decay 必须继续是 2 —— 不能把一组写死常量
 * 换成另一组（例如统一 {@code upgraded ? 3 : 2}）。
 */
class StatusCardUpgradeTest {

    private static SimState fight() {
        SimState s = new SimState();
        s.hp = 80;
        s.maxHp = 80;
        s.energy = 3;
        SimMonster m = new SimMonster("TestMonster", 200, 200, 0,
                Collections.<String, Integer>emptyMap());
        m.pendingMove = MonsterMove.block(1, 0);
        m.pendingMoveId = 1;
        s.monsters.add(m);
        return s;
    }

    private static SimCard card(String id, boolean upgraded, String type) {
        return new SimCard(id, upgraded, -2, -1, type);
    }

    @Test
    void upgradedBurnHitsForFour() {
        SimState s = fight();
        s.hand.add(card("Burn", true, "STATUS"));

        CombatFlow.endTurn(s, 3, 5);

        assertEquals(76, s.hp, "Burn+ = magicNumber 4（80 - 4）");
    }

    @Test
    void plainBurnStillHitsForTwo() {
        SimState s = fight();
        s.hand.add(card("Burn", false, "STATUS"));

        CombatFlow.endTurn(s, 3, 5);

        assertEquals(78, s.hp, "Burn = 2（不可回归成升级值）");
    }

    @Test
    void decayIsAlwaysTwoBecauseItsUpgradeIsANoOp() {
        SimState s = fight();
        s.hand.add(card("Decay", true, "CURSE"));

        CombatFlow.endTurn(s, 3, 5);

        assertEquals(78, s.hp,
                "Decay.upgrade() 是空实现（原版没有 Decay+），伤害恒为 2");
    }
}
