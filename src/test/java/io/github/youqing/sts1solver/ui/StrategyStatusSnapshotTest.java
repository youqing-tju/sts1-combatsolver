package io.github.youqing.sts1solver.ui;

import io.github.youqing.sts1solver.sim.SimState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StrategyStatusSnapshotTest {
    @Test
    void potionRowsPreserveSlotsCostAndActionShape() {
        SimState s = new SimState();
        s.potions.add("Fire Potion");
        s.potions.add("Fruit Juice");
        s.potions.add(null);
        s.potionSlots = 3;

        StrategyStatusSnapshot view = StrategyStatusSnapshot.from(s);

        assertTrue(view.lines().contains(
                "药[1] Fire Potion · 主动/目标 · 门槛 9 HP"));
        assertTrue(view.lines().contains(
                "药[2] Fruit Juice · 主动 · 门槛 18 HP"));
        assertTrue(view.lines().contains("药空槽 ×1"));
    }

    @Test
    void passivePotionDoesNotShowFakeActiveSpendingThreshold() {
        SimState s = new SimState();
        s.potions.add("FairyPotion");

        StrategyStatusSnapshot view = StrategyStatusSnapshot.from(s);

        assertTrue(view.lines().contains(
                "药[1] FairyPotion · 被动 · 无主动使用门槛"));
        assertFalse(view.lines().get(0).contains("门槛 9 HP"));
    }

    @Test
    void choicePotionIsLabelledAsChoice() {
        SimState s = new SimState();
        s.potions.add("PowerPotion");

        assertTrue(StrategyStatusSnapshot.from(s).lines().contains(
                "药[1] PowerPotion · 主动/选牌 · 门槛 9 HP"));
    }

    @Test
    void relicWarningAndPositiveGrowthAreReadOnlyFacts() {
        SimState s = new SimState();
        s.hp = 63;
        s.maxHp = 83;
        s.relics.put("Shuriken", 2);
        s.relics.put("Test Relic", -1);
        s.playerPowers.put("Strength", 2);
        s.playerPowers.put("Dexterity", -3);
        s.playerPowers.put("Demon Form", 1);

        StrategyStatusSnapshot view = StrategyStatusSnapshot.from(s);
        String all = String.join("\n", view.lines());

        assertTrue(all.contains("Shuriken=2"));
        assertTrue(all.contains("Test Relic=-1"));
        assertTrue(all.contains("未镜像遗物 1: Test Relic"));
        assertTrue(all.contains("成长 hp/max 63/83"));
        assertTrue(all.contains("Strength=2"));
        assertTrue(all.contains("Demon Form=1"));
        assertFalse(all.contains("Dexterity="));
        assertFalse(all.contains("额度"));
        assertFalse(all.contains("忽略局外收益"));
    }
}
