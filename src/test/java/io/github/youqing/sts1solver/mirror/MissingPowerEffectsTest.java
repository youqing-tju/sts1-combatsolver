package io.github.youqing.sts1solver.mirror;

import org.junit.jupiter.api.Test;

import java.util.Collections;

import io.github.youqing.sts1solver.sim.CombatFlow;
import io.github.youqing.sts1solver.sim.SimCard;
import io.github.youqing.sts1solver.sim.SimMonster;
import io.github.youqing.sts1solver.sim.SimState;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「卡牌设置了能力，但效果没建模」那一批里的三个。
 *
 * <p>这类缺口的危险在于**不报错**：{@code powerOnPlayer("NoBlockPower", 1)} 成功上状态，
 * 但格挡照加 → 模型高估防御 → 估值把「不能格挡」的负面效果当没有。
 */
class MissingPowerEffectsTest {

    private static SimState fight() {
        SimState s = new SimState();
        s.hp = 30;
        s.maxHp = 80;
        s.energy = 3;
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
        s.playerPowers.put("NoBlockPower", 1);   // 原版 ID 就是这个（NoBlockPower.ID）

        assertEquals(0, DamageCalc.blockAmount(s, 12), "基础 12 也变 0");
    }

    @Test
    void noBlockIgnoresDexterityToo() {
        SimState s = fight();
        s.playerPowers.put("NoBlockPower", 1);   // 原版 ID 就是这个（NoBlockPower.ID）
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

        // 🔴 怪物格挡在**它自己回合开始时就已经清掉**（原版 MonsterGroup.applyPreTurnLogic），
        // 所以轮到它出手时身上没有格挡，烈焰屏障的 6 点全额进血。
        // 这条断言原来写的是 37（以为格挡还在）—— 那是把「怪物格挡跨回合累加」的 bug 当成了正确行为。
        assertEquals(34, s.monsters.get(0).hp, "6 反弹全额进血：怪物出手前格挡已被清");
        assertEquals(0, s.monsters.get(0).block);
    }

    /**
     * 怪物格挡**不跨回合累加**：回合开始先清、再行动。
     *
     * <p>原版 {@code MonsterGroup.applyPreTurnLogic()}：
     * {@code if (!hasPower("Barricade")) loseBlock();}。这条要是漏了，
     * 球体守卫那种「每两回合 +25 格挡」的怪会越打越硬，搜索会误判成杀不掉。
     */
    @Test
    void monsterBlockDoesNotAccumulateAcrossTurns() {
        SimState s = fight();
        SimMonster monster = s.monsters.get(0);
        monster.intentDmg = 0;
        monster.block = 30;

        CombatFlow.endTurn(s, 3, 5);
        assertEquals(0, monster.block, "回合开始清空格挡（它这回合没加块）");
    }

    // ------------------------------------------ 第二批：Flex / Rupture / Evolve

    /**
     * Flex「活动肌肉」：立即 +N 力量，回合末 −N。
     *
     * <p>🔴 能力名必须是 {@code "Flex"}（{@code LoseStrengthPower.POWER_ID}，javap 核实）。
     * 以前写 {@code "Gain Strength"} 是错的：那是 {@code GainStrengthPower} 的**名字**，
     * 而它的 ID 其实是 {@code "Shackled"}。写错的后果是回合末扣力量的钩子永远匹配不到，
     * Flex 的 +2 力量会变成永久的。
     */
    @Test
    void flexAddsStrengthNowAndLosesItAtEndOfTurn() {
        SimState s = fight();
        Effects.powerOnPlayer("Flex", 2).applyTo(s, -1, null);
        Effects.powerOnPlayer(io.github.youqing.sts1solver.mirror.DamageCalc.STRENGTH, 2)
                .applyTo(s, -1, null);
        assertEquals(2, s.playerPowers.get("Strength"));
        assertEquals(2, s.playerPowers.get("Flex"));

        CombatFlow.endTurn(s, 3, 5);

        assertEquals(0, s.playerPowers.get("Strength"), "回合末被减掉");
    }

    /** 破裂：自己掉血（卡牌自伤）时，+N 力量。 */
    @Test
    void ruptureGrantsStrengthOnSelfDamage() {
        SimState s = fight();
        s.playerPowers.put("Rupture", 3);

        Effects.loseHp(2).applyTo(s, -1, null);

        assertEquals(3, s.playerPowers.get("Strength"));
        assertEquals(28, s.hp);
    }

    /** 没有掉血（0 伤）不触发。 */
    @Test
    void ruptureIgnoresZeroDamage() {
        SimState s = fight();
        s.playerPowers.put("Rupture", 3);
        Effects.loseHp(0).applyTo(s, -1, null);
        assertEquals(0, s.playerPowers.get("Strength") == null ? 0 : s.playerPowers.get("Strength"));
    }

    /** 进化：抽到状态牌时，抽 amount 张。 */
    @Test
    void evolveDrawsExtraWhenAStatusIsDrawn() {
        SimState s = fight();
        s.playerPowers.put("Evolve", 2);
        s.hand.clear();
        s.draw.clear();
        // 抽牌从**堆顶**（列表末尾）取，所以把状态牌放末尾
        for (int i = 0; i < 6; i++) {
            s.draw.add(new SimCard("Strike_R", false, 1, 6, "ATTACK"));
        }
        s.draw.add(new SimCard("Wound", false, -1, 0, "STATUS"));   // 堆顶，最先被抽到

        CombatFlow.endTurn(s, 3, 5);

        // 抽 5 张里有 1 张状态 → 进化补 2 张
        assertTrue(s.hand.size() > 5, "手牌超过 5 张 = 进化补的：" + s.hand.size());
    }

    /** 双击：打出攻击牌时，复制一张再打一遍（{@code DoubleTapPower.onUseCard}）。 */
    @Test
    void doubleTapReplaysTheAttack() {
        SimState s = fight();
        s.playerPowers.put("Double Tap", 1);
        s.hand.add(new SimCard("Strike_R", false, 1, 6, "ATTACK"));

        CardPlayer.play(s, 0, 0);

        assertEquals(28, s.monsters.get(0).hp, "40 - 6×2 = 28");
        assertEquals(2, s.discard.size(), "本体 + 复制各进弃牌堆");
    }

    /** 打完一次双击就消耗掉一层（{@code atEndOfTurn} 递减）。 */
    @Test
    void doubleTapIsConsumedByOneUse() {
        SimState s = fight();
        s.playerPowers.put("Double Tap", 1);
        s.hand.add(new SimCard("Strike_R", false, 1, 6, "ATTACK"));

        CardPlayer.play(s, 0, 0);

        assertFalse(s.playerPowers.containsKey("Double Tap"));
    }

    /** 非攻击牌不触发。 */
    @Test
    void doubleTapIgnoresNonAttackCards() {
        SimState s = fight();
        s.playerPowers.put("Double Tap", 1);
        s.hand.add(new SimCard("Defend_R", false, 1, -1, "SKILL"));

        CardPlayer.play(s, 0, -1);

        assertEquals(5, s.block, "格挡只加一次");
        assertTrue(s.playerPowers.containsKey("Double Tap"), "没消耗");
    }
}
