package io.github.youqing.sts1solver.mirror;

import io.github.youqing.sts1solver.sim.SimCard;
import io.github.youqing.sts1solver.sim.SimMonster;
import io.github.youqing.sts1solver.sim.SimState;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CardPlayerTest {

    /** A1 F1 combat: 4 energy (Ectoplasm), one Acid Slime at 12 HP, a 5-card hand. */
    private static SimState combat(String... handKeys) {
        SimState s = new SimState();
        s.act = 1;
        s.floor = 1;
        s.hp = 80;
        s.maxHp = 80;
        s.energy = 4;
        for (String key : handKeys) {
            boolean up = key.endsWith("+");
            s.hand.add(new SimCard(up ? key.substring(0, key.length() - 1) : key, up, costOf(key)));
        }
        s.monsters.add(new SimMonster("AcidSlime_S", 12, 12, 0, Collections.<String, Integer>emptyMap()));
        return s;
    }

    private static int costOf(String key) {
        if (key.startsWith("Bash")) {
            return 2;
        }
        return 1;
    }

    @Test
    void strikeDealsItsBaseDamageAndCostsOneEnergy() {
        SimState s = combat("Strike_R");
        CardPlayer.play(s, 0, 0);

        assertEquals(6, s.monsters.get(0).hp);
        assertEquals(3, s.energy);
        assertEquals(0, s.hand.size(), "the card leaves the hand");
        assertEquals(1, s.discard.size(), "and lands in the discard pile");
        assertEquals("Strike_R", s.discard.get(0).id);
    }

    @Test
    void upgradedStrikeHitsForNine() {
        SimState s = combat("Strike_R+");
        CardPlayer.play(s, 0, 0);
        assertEquals(3, s.monsters.get(0).hp);
    }

    @Test
    void bashDealsDamageAndAppliesVulnerable() {
        SimState s = combat("Bash");
        CardPlayer.play(s, 0, 0);

        assertEquals(4, s.monsters.get(0).hp, "12 - 8");
        assertEquals(2, (int) s.monsters.get(0).powers.get(DamageCalc.VULNERABLE));
        assertEquals(2, s.energy, "Bash costs 2");
    }

    @Test
    void strengthAddsAndVulnerableMultiplies() {
        SimState s = combat("Strike_R");
        s.playerPowers.put(DamageCalc.STRENGTH, 2);
        s.monsters.get(0).powers.put(DamageCalc.VULNERABLE, 1);

        CardPlayer.play(s, 0, 0);

        // (6 + 2) * 1.5 = 12 -> the slime dies and is removed
        assertTrue(s.monsters.isEmpty(), "lethal damage removes the monster");
    }

    @Test
    void weakenedReducesAttackBeforeVulnerable() {
        SimState s = combat("Strike_R");
        s.playerPowers.put(DamageCalc.WEAKENED, 1);
        CardPlayer.play(s, 0, 0);

        // floor(6 * 0.75) = 4
        assertEquals(8, s.monsters.get(0).hp);
    }

    @Test
    void blockSoaksDamageFirst() {
        SimState s = combat("Strike_R");
        s.monsters.get(0).block = 5;
        CardPlayer.play(s, 0, 0);

        assertEquals(0, s.monsters.get(0).block);
        assertEquals(11, s.monsters.get(0).hp, "6 damage, 5 absorbed");
    }

    @Test
    void defendGrantsBlockAndDexterityCounts() {
        SimState s = combat("Defend_R");
        s.playerPowers.put(DamageCalc.DEXTERITY, 2);
        CardPlayer.play(s, 0, -1);

        assertEquals(7, s.block, "5 + 2 dexterity");
        assertEquals(12, s.monsters.get(0).hp, "a skill does not damage anything");
    }

    @Test
    void frailReducesBlockGain() {
        SimState s = combat("Defend_R");
        s.playerPowers.put(DamageCalc.FRAIL, 1);
        CardPlayer.play(s, 0, -1);

        assertEquals(3, s.block, "floor(5 * 0.75)");
    }

    @Test
    void unknownCardIsRejectedNotGuessed() {
        SimState s = combat("Anger");
        ActionRejected ex = assertThrows(ActionRejected.class, () -> CardPlayer.play(s, 0, 0));
        assertTrue(ex.getMessage().contains("no mirror for card Anger"), ex.getMessage());
        assertEquals(4, s.energy, "a rejected action must not spend anything");
    }

    @Test
    void unaffordableCardIsRejected() {
        SimState s = combat("Bash");
        s.energy = 1;
        ActionRejected ex = assertThrows(ActionRejected.class, () -> CardPlayer.play(s, 0, 0));
        assertTrue(ex.getMessage().contains("not enough energy"), ex.getMessage());
        assertEquals(1, s.energy);
        assertEquals(12, s.monsters.get(0).hp, "no damage was dealt");
    }

    @Test
    void registryKnowsTheStarterDeckAndNothingElse() {
        assertNotNull(MirrorRegistry.lookup("Strike_R"));
        assertNotNull(MirrorRegistry.lookup("Strike_R+"));
        assertNotNull(MirrorRegistry.lookup("Defend_R"));
        assertNotNull(MirrorRegistry.lookup("Bash+"));
        assertNull(MirrorRegistry.lookup("Anger"));
        assertEquals(6, MirrorRegistry.size());
    }
}
