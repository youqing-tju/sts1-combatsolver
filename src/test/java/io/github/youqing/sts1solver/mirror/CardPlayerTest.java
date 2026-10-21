package io.github.youqing.sts1solver.mirror;

import io.github.youqing.sts1solver.sim.SimCard;
import io.github.youqing.sts1solver.sim.SimMonster;
import io.github.youqing.sts1solver.sim.RngStream;
import io.github.youqing.sts1solver.sim.SimState;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
        if (key.startsWith("Bash") || key.startsWith("Flame Barrier") || key.startsWith("Clothesline")
                || key.startsWith("Bludgeon") || key.startsWith("Uppercut") || key.startsWith("Reaper")
                || key.startsWith("Heavy Blade") || key.startsWith("Entrench")
                || key.startsWith("Carnage") || key.startsWith("Searing Blow")
                || key.startsWith("Impervious")) {
            return 2;
        }
        if (key.startsWith("Bludgeon")) {
            return 3;
        }
        if (key.startsWith("Anger") || key.startsWith("Flex") || key.startsWith("Warcry")
                || key.startsWith("Clash") || key.startsWith("Bloodletting")
                || key.startsWith("Offering") || key.startsWith("Battle Trance")
                || key.startsWith("Seeing Red")) {
            return 0;
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

        // (6 + 2) * 1.5 = 12 -> lethal; the monster stays at 0 HP until the game removes it
        assertEquals(0, s.monsters.get(0).hp, "lethal damage must land");
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
        SimState s = combat("Havoc");
        ActionRejected ex = assertThrows(ActionRejected.class, () -> CardPlayer.play(s, 0, 0));
        assertTrue(ex.getMessage().contains("no mirror for card Havoc"), ex.getMessage());
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
    void registryCoversTheMirroredBatch() {
        assertNotNull(MirrorRegistry.lookup("Strike_R"));
        assertNotNull(MirrorRegistry.lookup("Bash+"));
        assertNotNull(MirrorRegistry.lookup("Anger"));
        assertNotNull(MirrorRegistry.lookup("Thunderclap+"));
        assertNull(MirrorRegistry.lookup("Havoc"), "Havoc needs recursion, still unsupported");
        assertTrue(MirrorRegistry.size() >= 28, "batch 2 should be registered: " + MirrorRegistry.size());
    }

    // ---- 第二批效果 ----

    @Test
    void multiHitResolvesEachHitSeparately() {
        SimState s = combat("Twin Strike");
        CardPlayer.play(s, 0, 0);
        assertEquals(2, s.monsters.get(0).hp, "5 + 5 = 10 damage on a 12 HP slime");
    }

    @Test
    void aoeHitsEveryMonster() {
        SimState s = combat("Cleave");
        s.monsters.add(new SimMonster("SpikeSlime_M", 30, 30, 0, Collections.<String, Integer>emptyMap()));
        CardPlayer.play(s, 0, 0);
        assertEquals(4, s.monsters.get(0).hp, "12 - 8");
        assertEquals(22, s.monsters.get(1).hp, "30 - 8");
    }

    @Test
    void thunderclapAppliesVulnerableToAll() {
        SimState s = combat("Thunderclap");
        s.monsters.add(new SimMonster("SpikeSlime_M", 30, 30, 0, Collections.<String, Integer>emptyMap()));
        CardPlayer.play(s, 0, 0);
        assertEquals(8, s.monsters.get(0).hp, "12 - 4");
        assertEquals(1, (int) s.monsters.get(0).powers.get(DamageCalc.VULNERABLE));
        assertEquals(1, (int) s.monsters.get(1).powers.get(DamageCalc.VULNERABLE));
    }

    @Test
    void angerCopiesItselfIntoTheDiscardPile() {
        SimState s = combat("Anger");
        CardPlayer.play(s, 0, 0);
        assertEquals(2, s.discard.size(), "played copy + duplicated copy");
        assertEquals("Anger", s.discard.get(1).id);
        assertEquals(4, s.energy, "Anger costs 0");
    }

    @Test
    void drawTakesFromTheTopOfTheDrawPile() {
        SimState s = combat("Pommel Strike");
        s.draw.add(new SimCard("Defend_R", false, 1));   // 堆底
        s.draw.add(new SimCard("Bash", false, 2));       // 堆顶
        CardPlayer.play(s, 0, 0);
        assertEquals(1, s.hand.size(), "drew one card");
        assertEquals("Bash", s.hand.get(0).id, "must take the top of the pile (last element)");
        assertEquals(1, s.draw.size());
    }

    // ---- 第三批：新原语 ----

    @Test
    void bodySlamDealsDamageEqualToCurrentBlock() {
        SimState s = combat("Body Slam");
        s.block = 12;
        s.monsters.clear();
        s.monsters.add(new SimMonster("AcidSlime_S", 30, 30, 0,
                Collections.<String, Integer>emptyMap()));
        CardPlayer.play(s, 0, 0);
        assertEquals(18, s.monsters.get(0).hp, "12 block -> 12 damage");
    }

    @Test
    void entrenchDoublesBlock() {
        SimState s = combat("Entrench");
        s.block = 9;
        CardPlayer.play(s, 0, 0);
        assertEquals(18, s.block);
    }

    @Test
    void limitBreakDoublesStrength() {
        SimState s = combat("Limit Break");
        s.playerPowers.put(DamageCalc.STRENGTH, 3);
        CardPlayer.play(s, 0, 0);
        assertEquals(6, (int) s.playerPowers.get(DamageCalc.STRENGTH));
    }

    @Test
    void heavyBladeCountsStrengthThreeTimes() {
        SimState s = combat("Heavy Blade");
        s.playerPowers.put(DamageCalc.STRENGTH, 2);
        s.monsters.clear();
        s.monsters.add(new SimMonster("AcidSlime_S", 60, 60, 0,
                Collections.<String, Integer>emptyMap()));
        CardPlayer.play(s, 0, 0);
        // 14 + 2*3 = 20
        assertEquals(40, s.monsters.get(0).hp);
    }

    @Test
    void offeringPaysHpForEnergyAndCards() {
        SimState s = combat("Offering");
        s.draw.add(new SimCard("Strike_R", false, 1));
        s.draw.add(new SimCard("Defend_R", false, 1));
        s.draw.add(new SimCard("Bash", false, 2));
        CardPlayer.play(s, 0, 0);
        assertEquals(74, s.hp, "6 HP paid");
        assertEquals(6, s.energy, "0 cost + 2 energy gained");
        assertEquals(3, s.hand.size());
    }

    @Test
    void powerThroughAddsTwoWoundsToHand() {
        SimState s = combat("Power Through");
        CardPlayer.play(s, 0, 0);
        assertEquals(15, s.block, "no dexterity");
        assertEquals(2, s.hand.size());
        assertEquals("Wound", s.hand.get(0).id);
    }

    @Test
    void reaperHealsForUnblockedDamage() {
        SimState s = combat("Reaper");
        s.hp = 60;
        s.monsters.clear();
        s.monsters.add(new SimMonster("AcidSlime_S", 30, 30, 0,
                Collections.<String, Integer>emptyMap()));
        CardPlayer.play(s, 0, 0);
        assertEquals(26, s.monsters.get(0).hp, "4 damage");
        assertEquals(64, s.hp, "healed by the 4 dealt");
    }

    @Test
    void battleTranceAppliesNoDraw() {
        SimState s = combat("Battle Trance");
        CardPlayer.play(s, 0, 0);
        assertEquals(-1, (int) s.playerPowers.get("No Draw"));
    }

    // ---- 第四批：消耗 / 条件 / 成长 ----

    @Test
    void secondWindExhaustsOnlyNonAttacksAndBlocksPerCard() {
        SimState s = combat("Second Wind", "Defend_R", "Strike_R");
        CardPlayer.play(s, 0, 0);
        assertEquals(1, s.exhaust.size(), "only the skill gets exhausted");
        assertEquals("Defend_R", s.exhaust.get(0).id);
        assertEquals(1, s.hand.size(), "only Strike_R is left in hand");
        assertEquals(5, s.block, "5 block per exhausted card");
    }

    @Test
    void fiendFireExhaustsTheWholeHandAndScalesWithItsSize() {
        SimState s = combat("Fiend Fire", "Strike_R", "Defend_R");
        s.monsters.clear();
        s.monsters.add(new SimMonster("AcidSlime_S", 40, 40, 0,
                Collections.<String, Integer>emptyMap()));
        CardPlayer.play(s, 0, 0);
        assertEquals(0, s.hand.size());
        // 2 张手牌 + 恶魔之火自身（打出后消耗）
        assertEquals(3, s.exhaust.size());
        assertEquals(26, s.monsters.get(0).hp, "7 damage x 2 cards");
    }

    @Test
    void immolateAddsABurnToTheDiscardPile() {
        SimState s = combat("Immolate");
        CardPlayer.play(s, 0, 0);
        assertEquals(8 - 21 < 0 ? 0 : 8 - 21, s.monsters.get(0).hp);
        assertTrue(s.discard.size() >= 1);
        boolean hasBurn = false;
        for (SimCard c : s.discard) {
            if ("Burn".equals(c.id)) {
                hasBurn = true;
            }
        }
        assertTrue(hasBurn, "Immolate must shuffle a Burn into the discard pile");
    }

    @Test
    void dropkickPaysExtraWhenTheTargetIsVulnerable() {
        SimState s = combat("Dropkick");
        s.monsters.get(0).powers.put(DamageCalc.VULNERABLE, 1);
        s.draw.add(new SimCard("Defend_R", false, 1));
        s.draw.add(new SimCard("Defend_R", false, 1));
        CardPlayer.play(s, 0, 0);
        assertEquals(5, s.energy, "4 - 1 cost + 2 refund = 5");
        assertEquals(2, s.hand.size(), "one draw + one extra draw");
    }

    @Test
    void perfectedStrikeCountsStrikeCardsInTheDeck() {
        SimState s = combat("Perfected Strike");
        s.monsters.clear();
        s.monsters.add(new SimMonster("AcidSlime_S", 40, 40, 0,
                Collections.<String, Integer>emptyMap()));
        s.draw.add(new SimCard("Strike_R", false, 1));
        s.draw.add(new SimCard("Strike_R", false, 1));
        CardPlayer.play(s, 0, 0);
        // 6 + 2 x 2 张 Strike（手牌里的 Perfected Strike 本身不算）
        assertEquals(30, s.monsters.get(0).hp);
    }

    // ---- 第五批：随机位置 / 随机目标 ----

    @Test
    void wildStrikeInsertsAWoundIntoTheDrawPileAndBurnsRng() {
        SimState s = combat("Wild Strike");
        s.draw.add(new SimCard("Defend_R", false, 1));
        s.rng.put(SimState.RngKind.CARD, RngStream.from(12345L, 67890L, 0));
        int before = s.rng.get(SimState.RngKind.CARD).counter();

        CardPlayer.play(s, 0, 0);

        assertEquals(2, s.draw.size(), "the Wound was inserted, nothing was drawn");
        assertEquals(before + 1, s.rng.get(SimState.RngKind.CARD).counter(),
                "placement must consume exactly one card-rng step");
        boolean hasWound = false;
        for (SimCard c : s.draw) {
            if ("Wound".equals(c.id)) {
                hasWound = true;
            }
        }
        assertTrue(hasWound);
    }

    @Test
    void swordBoomerangSpreadsThreeHitsAcrossEnemies() {
        SimState s = combat("Sword Boomerang");
        s.monsters.clear();
        s.monsters.add(new SimMonster("A", 40, 40, 0, Collections.<String, Integer>emptyMap()));
        s.monsters.add(new SimMonster("B", 40, 40, 0, Collections.<String, Integer>emptyMap()));

        CardPlayer.play(s, 0, 0);

        int lost = (40 - s.monsters.get(0).hp) + (40 - s.monsters.get(1).hp);
        assertEquals(9, lost, "3 hits x 3 damage, target chosen at random");
    }

    // ---- 第六批：X 费 ----

    @Test
    void whirlwindSpendsAllEnergyAndScalesWithIt() {
        SimState s = combat("Whirlwind");
        s.hand.clear();
        s.hand.add(new SimCard("Whirlwind", false, -1));   // -1 = X 费
        s.energy = 3;
        s.monsters.clear();
        s.monsters.add(new SimMonster("A", 40, 40, 0, Collections.<String, Integer>emptyMap()));

        CardPlayer.play(s, 0, 0);

        assertEquals(0, s.energy, "X spends every point of energy");
        assertEquals(25, s.monsters.get(0).hp, "3 energy x 5 damage");
    }

    @Test
    void whirlwindWithNoEnergyStillResolvesForZero() {
        SimState s = combat("Whirlwind");
        s.hand.clear();
        s.hand.add(new SimCard("Whirlwind", false, -1));
        s.energy = 0;
        s.monsters.clear();
        s.monsters.add(new SimMonster("A", 40, 40, 0, Collections.<String, Integer>emptyMap()));

        CardPlayer.play(s, 0, 0);

        assertEquals(40, s.monsters.get(0).hp);
        assertEquals(0, s.energy);
    }

    // ---- 第七批：选牌类 ----

    @Test
    void armamentsUpgradesTheChosenCard() {
        SimState s = combat("Armaments", "Strike_R");
        CardPlayer.play(s, 0, 0, 0);   // 打出后手牌只剩 Strike_R，选第 0 张
        assertEquals(5, s.block);
        assertTrue(s.hand.get(0).upgraded, "the chosen card must be upgraded");
    }

    @Test
    void burningPactExhaustsTheChosenCardThenDraws() {
        SimState s = combat("Burning Pact", "Strike_R", "Defend_R");
        s.draw.add(new SimCard("Bash", false, 2));
        s.draw.add(new SimCard("Bash", false, 2));

        CardPlayer.play(s, 0, 0, 1);   // 打出后手牌 [Strike_R, Defend_R]，选第 1 张

        assertEquals(1, s.exhaust.size());
        assertEquals("Defend_R", s.exhaust.get(0).id);
        assertEquals(3, s.hand.size(), "Strike_R + 2 drawn");
    }

    // ---- 第八批：条件 / 收益 ----

    @Test
    void feedRaisesMaxHpOnlyWhenItGetsTheKill() {
        SimState s = combat("Feed");
        s.monsters.clear();
        s.monsters.add(new SimMonster("A", 5, 5, 0, Collections.<String, Integer>emptyMap()));

        CardPlayer.play(s, 0, 0);

        assertEquals(83, s.maxHp, "max HP +3 on kill");
        assertEquals(83, s.hp, "current HP rises with it");
    }

    @Test
    void feedGivesNothingWhenTheTargetSurvives() {
        SimState s = combat("Feed");
        s.monsters.clear();
        s.monsters.add(new SimMonster("A", 40, 40, 0, Collections.<String, Integer>emptyMap()));

        CardPlayer.play(s, 0, 0);

        assertEquals(80, s.maxHp);
    }

    @Test
    void spotWeaknessOnlyPaysOutAgainstAnAttackingEnemy() {
        SimState attacking = combat("Spot Weakness");
        attacking.monsters.get(0).intentAttack = true;
        CardPlayer.play(attacking, 0, 0);
        assertEquals(3, (int) attacking.playerPowers.get(DamageCalc.STRENGTH));

        SimState idle = combat("Spot Weakness");
        idle.monsters.get(0).intentAttack = false;
        CardPlayer.play(idle, 0, 0);
        assertNull(idle.playerPowers.get(DamageCalc.STRENGTH), "no strength against a non-attacking enemy");
    }

    @Test
    void warcryDrawsThenPutsTheChosenCardBackOnTop() {
        SimState s = combat("Warcry", "Strike_R", "Defend_R");
        s.hand.clear();
        s.hand.add(new SimCard("Warcry", false, 1));
        s.hand.add(new SimCard("Strike_R", false, 1));
        s.hand.add(new SimCard("Defend_R", false, 1));
        s.draw.add(new SimCard("Bash", false, 2));
        s.draw.add(new SimCard("Bash", false, 2));

        CardPlayer.play(s, 0, 0, 1);   // 抽 1 张后，把手牌第 1 张（Defend_R）放回牌堆顶

        assertEquals(2, s.hand.size(), "Strike_R + 1 drawn");
        assertEquals("Defend_R", s.draw.get(s.draw.size() - 1).id, "top of the draw pile");
    }

    @Test
    void drawRespectsTheTenCardHandLimit() {
        SimState s = combat("Pommel Strike");
        for (int i = 0; i < 6; i++) {
            s.hand.add(new SimCard("Defend_R", false, 1));
        }
        s.draw.add(new SimCard("Strike_R", false, 1));
        // 手牌 7 张：打出剑柄打击后剩 6，抽 1 张到 7，未触顶
        CardPlayer.play(s, 0, 0);
        assertEquals(7, s.hand.size());

        SimState full = combat("Pommel Strike");
        for (int i = 0; i < 9; i++) {
            full.hand.add(new SimCard("Defend_R", false, 1));
        }
        full.draw.add(new SimCard("Strike_R", false, 1));
        // 手牌 10 张：打出后剩 9，抽 1 张到 10；再抽就会撞上限
        CardPlayer.play(full, 0, 0);
        assertEquals(10, full.hand.size(), "hand stops at 10");
    }
}
