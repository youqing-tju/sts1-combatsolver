package io.github.youqing.sts1solver.mirror;

import org.junit.jupiter.api.Test;

import java.util.Collections;

import io.github.youqing.sts1solver.sim.CombatFlow;
import io.github.youqing.sts1solver.sim.SimMonster;
import io.github.youqing.sts1solver.sim.SimState;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 「卡牌设置了能力，但效果没建模」那一批里的三个。
 *
 * <p>这类缺口的危险在于**不报错**：{@code powerOnPlayer("No Block", 1)} 成功上状态，
 * 但格挡照加 → 模型高估防御 → 估值把「不能格挡」的负面效果当没有。
 */
class MissingPowerEffectsTest {

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

    /** 不能格挡：{@code NoBlockPower.modifyBlockLast} 直接 {@code return 0.0f}。 */
    @Test
    void noBlockZeroesOutAllBlockGain() {
        SimState s = fight();
        s.playerPowers.put("No Block", 1);

        assertEquals(0, DamageCalc.blockAmount(s, 12), "基础 12 也变 0");
    }

    @Test
    void noBlockIgnoresDexterityToo() {
        SimState s = fight();
        s.playerPowers.put("No Block", 1);
        s.playerPowers.put("Dexterity", 5);

        assertEquals(0, DamageCalc.blockAmount(s, 12));
    }

    @Test
    void withoutNoBlockThePipelineStillWorks() {
        SimState s = fight();
        s.playerPowers.put("Dexterity", 5);
        assertEquals(17, DamageCalc.blockAmount(s, 12));
    }

    /** 狂暴：{@code BerserkPower.atStartOfTurn} → {@code GainEnergyAction(amount)}。 */
    @Test
    void berserkGrantsEnergyAtStartOfTurn() {
        SimState s = fight();
        s.playerPowers.put("Berserk", 1);
        s.energy = 0;

        CombatFlow.endTurn(s, 3, 5);     // 结束回合 → 进入下一回合初

        assertEquals(4, s.energy, "3 回能 + 1 狂暴");
    }

    /** 烈焰屏障：玩家受击时反弹 {@code amount} 点给攻击者（{@code FlameBarrierPower.onAttacked}）。 */
    @Test
    void flameBarrierReflectsDamageBackToTheAttacker() {
        SimState s = fight();
        s.playerPowers.put("Flame Barrier", 6);
        s.monsters.get(0).intentDmg = 10;

        CombatFlow.endTurn(s, 3, 5);

        assertEquals(20, s.hp, "玩家掉 10");
        assertEquals(34, s.monsters.get(0).hp, "怪被反弹 6");
    }

    /** 反弹走怪物的格挡。 */
    @Test
    void flameBarrierReflectionGoesThroughMonsterBlock() {
        SimState s = fight();
        s.playerPowers.put("Flame Barrier", 6);
        s.monsters.get(0).block = 3;
        s.monsters.get(0).intentDmg = 5;

        CombatFlow.endTurn(s, 3, 5);

        assertEquals(37, s.monsters.get(0).hp, "6 反弹 - 3 格挡 = 3 点掉血");
        assertEquals(0, s.monsters.get(0).block);
    }
}
