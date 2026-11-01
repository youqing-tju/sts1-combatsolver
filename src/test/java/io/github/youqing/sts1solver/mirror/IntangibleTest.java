package io.github.youqing.sts1solver.mirror;

import org.junit.jupiter.api.Test;

import java.util.Collections;

import io.github.youqing.sts1solver.sim.CombatFlow;
import io.github.youqing.sts1solver.sim.SimMonster;
import io.github.youqing.sts1solver.sim.SimState;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 无实体（{@code IntangiblePower}）—— 受到的所有伤害封顶 1，回合末递减。
 *
 * <p>这是「能力被 set 但不生效」那类缺口里最大的一个：
 * {@code Ghostly} 卡给玩家上无实体，但之前伤害计算里没有它，
 * 模型照扣满伤 → 估值把防御性能力牌算得一文不值。
 */
class IntangibleTest {

    private static SimState fight() {
        SimState s = new SimState();
        s.hp = 30;
        s.maxHp = 80;
        SimMonster m = new SimMonster("TestMonster", 40, 40, 0,
                Collections.<String, Integer>emptyMap());
        m.intentDmg = 0;
        s.monsters.add(m);
        return s;
    }

    // ------------------------------------------------------------ 伤害封顶

    @Test
    void bigDamageIsCappedToOne() {
        SimState s = fight();
        s.playerPowers.put(DamageCalc.INTANGIBLE_PLAYER, 1);
        CombatFlow.hitPlayer(s, 25);
        assertEquals(29, s.hp, "25 伤被封到 1");
    }

    @Test
    void zeroAndOneDamageStayAsTheyAre() {
        SimState s = fight();
        s.playerPowers.put(DamageCalc.INTANGIBLE_PLAYER, 1);
        CombatFlow.hitPlayer(s, 1);
        assertEquals(29, s.hp);
        CombatFlow.hitPlayer(s, 0);
        assertEquals(29, s.hp, "0 伤保持 0");
    }

    /** 封顶发生在扣格挡**之前**：1 点伤害仍会被格挡完全吸收。 */
    @Test
    void theSinglePointStillGetsAbsorbedByBlock() {
        SimState s = fight();
        s.block = 5;
        s.playerPowers.put(DamageCalc.INTANGIBLE_PLAYER, 1);
        CombatFlow.hitPlayer(s, 99);
        assertEquals(30, s.hp, "血没掉");
        assertEquals(4, s.block, "只吸收了封顶后的 1 点");
    }

    @Test
    void noIntangibleMeansFullDamage() {
        SimState s = fight();
        CombatFlow.hitPlayer(s, 25);
        assertEquals(5, s.hp);
    }

    /** 怪物身上的无实体同理（原版是同一个 power 类）。 */
    @Test
    void monsterIntangibleAlsoCapsIncomingDamage() {
        SimState s = fight();
        SimMonster m = s.monsters.get(0);
        m.powers.put("Intangible", 1);

        DamageCalc.hit(s, m, 20);

        assertEquals(39, m.hp, "20 伤封到 1");
    }

    // -------------------------------------------------------------- 递减

    @Test
    void intangibleTicksDownAtEndOfTurnAndDisappears() {
        SimState s = fight();
        s.playerPowers.put(DamageCalc.INTANGIBLE_PLAYER, 2);

        CombatFlow.endTurn(s, 3, 5);
        assertEquals(1, s.playerPowers.get(DamageCalc.INTANGIBLE_PLAYER), "2 -> 1");

        CombatFlow.endTurn(s, 3, 5);
        assertNull(s.playerPowers.get(DamageCalc.INTANGIBLE_PLAYER), "1 -> 0 时整个移除");
    }

    @Test
    void monsterIntangibleTicksDownToo() {
        SimState s = fight();
        SimMonster m = s.monsters.get(0);
        m.powers.put("Intangible", 1);

        CombatFlow.endTurn(s, 3, 5);

        assertFalse(m.powers.containsKey("Intangible"));
    }
}
