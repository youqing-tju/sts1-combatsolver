package io.github.youqing.sts1solver.mirror;

import org.junit.jupiter.api.Test;

import java.util.Collections;

import io.github.youqing.sts1solver.sim.SimMonster;
import io.github.youqing.sts1solver.sim.SimState;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 战斗中药水的第一批镜像（火药水 / 格挡药水 / 能量药水 / 瓶中仙子）。
 *
 * <p>语义按字节码逐条核实（{@code com.megacrit.cardcrawl.potions.*}），
 * 见 {@code mechanics-reference} §25。
 */
class PotionsTest {

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

    // ------------------------------------------------------------ 主动使用

    @Test
    void firePotionDeals20Damage() {
        SimState s = fight();
        assertTrue(Potions.use(s, Potions.FIRE_POTION, 0));
        assertEquals(20, s.monsters.get(0).hp);
    }

    /** 原版 {@code applyEnemyPowersOnly} 只吃受击方的易伤（×1.5，向下取整），不吃力量。 */
    @Test
    void firePotionRespectsVulnerableButNotStrength() {
        SimState s = fight();
        s.playerPowers.put("Strength", 5);
        s.monsters.get(0).powers.put("Vulnerable", 1);

        Potions.use(s, Potions.FIRE_POTION, 0);

        // 20 × 1.5 = 30；力量不参与
        assertEquals(10, s.monsters.get(0).hp, "40 - 30 = 10");
    }

    @Test
    void blockPotionGoesThroughTheBlockPipeline() {
        SimState s = fight();
        assertTrue(Potions.use(s, Potions.BLOCK_POTION, -1));
        assertEquals(12, s.block, "12 格挡，无敏捷时原值");

        // 敏捷加成：+5 → 17
        SimState dex = fight();
        dex.playerPowers.put("Dexterity", 5);
        Potions.use(dex, Potions.BLOCK_POTION, -1);
        assertEquals(17, dex.block);
    }

    @Test
    void energyPotionGrantsTwoEnergy() {
        SimState s = fight();
        s.energy = 1;
        assertTrue(Potions.use(s, Potions.ENERGY_POTION, -1));
        assertEquals(3, s.energy);
    }

    /** 瓶中仙子的 {@code canUse() == false}：不能主动喝。 */
    @Test
    void fairyPotionCannotBeUsedManually() {
        SimState s = fight();
        assertFalse(Potions.canUse(Potions.FAIRY_POTION), "canUse() 原版直接 return false");
        assertFalse(Potions.use(s, Potions.FAIRY_POTION, -1));
    }

    /** 未登记的药水显式拒绝，不静默算错（D2）。 */
    @Test
    void unknownPotionIsRejectedNotIgnored() {
        SimState s = fight();
        assertThrows(ActionRejected.class,
                () -> Potions.use(s, "Some Unknown Potion", 0));
    }

    // --------------------------------------------------------- 被动：瓶中仙子

    @Test
    void fairyPotionRevivesToThirtyPercent() {
        SimState s = fight();
        s.hp = 0;
        s.potions.add(Potions.FAIRY_POTION);

        DeathSaveRelic.applyIfLethal(s);

        assertEquals(24, s.hp, "80 × 30% = 24");
        assertEquals(1, s.deathSaveUseCount);
        assertNull(s.potions.get(0), "那一格被 destroyPotion 清掉");
    }

    /** 回血量是 {@code (int)(maxHp * 0.3f)}，**向零截断**不是四舍五入。 */
    @Test
    void fairyHealTruncatesTowardZero() {
        // 75 × 0.3 = 22.5 → 22
        assertEquals(22, Potions.fairyHealAmount(75));
        // 1 血也不会归零
        assertEquals(1, Potions.fairyHealAmount(1));
    }

    /**
     * 原版 {@code if (hasPotion) {...} else if (hasLizardTail) {...}}：
     * **有仙子就一定用仙子**，不会降级到尾巴。
     */
    @Test
    void fairyPotionTakesPriorityOverLizardTail() {
        SimState s = fight();
        s.hp = 0;
        s.potions.add(Potions.FAIRY_POTION);
        s.relics.put(DeathSaveRelic.LIZARD_TAIL, -1);

        DeathSaveRelic.applyIfLethal(s);

        assertEquals(24, s.hp, "走的是仙子 30%，不是尾巴 50%");
        assertFalse(s.deathSaveUsed, "尾巴没被消耗");
        assertNull(s.potions.get(0), "仙子被用掉了");
    }

    /** 用完仙子，下一次致死才轮到尾巴。 */
    @Test
    void lizardTailKicksInAfterTheFairyIsSpent() {
        SimState s = fight();
        s.hp = 0;
        s.potions.add(Potions.FAIRY_POTION);
        s.relics.put(DeathSaveRelic.LIZARD_TAIL, -1);

        DeathSaveRelic.applyIfLethal(s);
        assertEquals(24, s.hp);

        s.hp = 0;
        DeathSaveRelic.applyIfLethal(s);
        assertEquals(40, s.hp, "尾巴回 50% = 40");
        assertTrue(s.deathSaveUsed);
        assertEquals(2, s.deathSaveUseCount);
    }
}
