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
        SimState s = combat("Grand Finale");   // 观察者的牌，铁甲战士池里没有，也不该有镜像
        ActionRejected ex = assertThrows(ActionRejected.class, () -> CardPlayer.play(s, 0, 0));
        assertTrue(ex.getMessage().contains("no mirror for card Grand Finale"), ex.getMessage());
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
        assertNotNull(MirrorRegistry.lookup("Discovery"), "三选一已支持（选哪张由 choiceIndex 传入）");
        assertNotNull(MirrorRegistry.lookup("Deep Breath"), "洗牌原语已落地");
        assertNotNull(MirrorRegistry.lookup("Chrysalis"), "随机技能牌池已落地");
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
        // 原版 DropkickAction：易伤时 DrawCardAction(1) + GainEnergyAction(1)，
        // 没有无条件抽牌，回的是 **1** 点能量（卡面 UPGRADE_DESCRIPTION 为空）。
        assertEquals(4, s.energy, "4 - 1 cost + 1 refund = 4");
        assertEquals(1, s.hand.size(), "易伤时只抽 1 张");
        assertEquals(1, s.draw.size(), "抽牌堆少 1 张");
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
        // 6 + 2 x 3 张 Strike —— **算上打出去的这张完美打击本身**。
        // 旧断言写的是「本身不算」（30 = 6+2x2），已被真机推翻：2026-10-30 小鬼头目那场
        // 完美打击的 diff 是 `monsters[0].hp: 65 vs 63`，实机多打 2 点。
        // javap 依据：AbstractPlayer.useCard 先 calculateCardDamage（offset 16）、
        // 后 hand.removeCard（offset 109），结算时这张牌还在手里，countCards 数得到它。
        // 卡片带 CardTags.STRIKE（PerfectedStrike 类里 tags.add(STRIKE)）。
        assertEquals(28, s.monsters.get(0).hp);
    }

    /**
     * 打击木偶（StrikeDummy）：带 {@code CardTags.STRIKE} 的牌 +3 伤害，且**加在力量之前**。
     *
     * <p>2026-10-31 真机抓到的实证：一次打击实机 9、我们 6；带易伤的打击实机 18、我们 13 ——
     * 差的就是这 3 点。铁甲整套牌组一半是打击，少算 1/3 会让搜索以为「打不死，先防」。
     */
    @Test
    void strikeDummyAddsThreeToStrikeTaggedCards() {
        SimState s = combat("Strike_R", "Pommel Strike", "Bash");
        s.relics.put("StrikeDummy", Integer.valueOf(-1));
        s.monsters.clear();
        s.monsters.add(new SimMonster("AcidSlime_L", 40, 67, 0,
                Collections.<String, Integer>emptyMap()));
        CardPlayer.play(s, 0, 0);       // 打击：6 + 3 = 9
        assertEquals(31, s.monsters.get(0).hp, "打击 6 + 3（打击木偶）= 9");

        // 力量加在 +3 之后：顺劈斩 9 + 3 + 2 = 14（出完打击后下标 0 是顺劈斩）
        s.playerPowers.put("Strength", Integer.valueOf(2));
        CardPlayer.play(s, 0, 0);
        assertEquals(17, s.monsters.get(0).hp, "顺劈斩 9 + 3 + 2 力量 = 14");

        // 非 STRIKE 标记的牌不吃（重击 Bash 8）
        s.playerPowers.clear();
        CardPlayer.play(s, 0, 0);
        assertEquals(9, s.monsters.get(0).hp, "Bash 8 不受打击木偶影响");
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
    void infernalBladeAddsOneFreeAttackAndBurnsExactlyOneRngStep() {
        SimState s = combat("Infernal Blade");
        s.rng.put(SimState.RngKind.CARD, RngStream.from(999L, 12345L, 0));
        int before = s.rng.get(SimState.RngKind.CARD).counter();

        CardPlayer.play(s, 0, 0);

        assertEquals(1, s.hand.size(), "exactly one attack is added");
        SimCard added = s.hand.get(0);
        assertTrue(added.baseDamage >= 0, "the card must come from the attack pool: " + added.id);
        assertEquals(before + 1, s.rng.get(SimState.RngKind.CARD).counter(),
                "one cardRandomRng step is consumed");
    }

    // ---- 第十二批：无色牌 ----

    @Test
    void apotheosisUpgradesEveryPile() {
        SimState s = combat("Apotheosis", "Strike_R");
        s.draw.add(new SimCard("Defend_R", false, 1));
        s.discard.add(new SimCard("Bash", false, 2));

        CardPlayer.play(s, 0, 0);

        assertTrue(s.hand.get(0).upgraded, "hand upgraded");
        assertTrue(s.draw.get(0).upgraded, "draw pile upgraded");
        assertTrue(s.discard.get(0).upgraded, "discard upgraded");
    }

    @Test
    void secretWeaponTakesAnAttackFromTheDrawPile() {
        SimState s = combat("Secret Weapon");
        s.draw.add(new SimCard("Defend_R", false, 1));
        s.draw.add(new SimCard("Strike_R", false, 1));

        CardPlayer.play(s, 0, 0, 1);   // 选抽牌堆第 1 张（Strike_R）

        assertEquals(1, s.hand.size());
        assertEquals("Strike_R", s.hand.get(0).id);
        assertEquals(1, s.draw.size());
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
    // ---------------------------------------------------------------- 蓝蜡烛 / 医疗包

    /**
     * 蓝蜡烛：诅咒牌（默认 cost = -2，打不出）变成可打，费用按 0 算，
     * 打出时**掉 1 血**并进消耗堆。
     *
     * <p>依据：{@code AbstractCard.canUse} 里
     * {@code type == CURSE && costForTurn < -1 && !hasRelic("Blue Candle") → false}；
     * {@code BlueCandle.onUseCard} → {@code LoseHPAction(player, player, 1)} + 强制消耗。
     */
    @Test
    void blueCandlePlaysCursesForOneHp() {
        SimState s = combat("AscendersBane");
        // 手牌里的诅咒是 -2 费（上面 combat() 按 costOf 给的是 0，这里显式改掉）
        s.hand.set(0, new SimCard("AscendersBane", false, -2, -1, "CURSE"));

        // 没遗物：拒绝
        assertThrows(ActionRejected.class, () -> CardPlayer.play(s, 0, -1));

        s.relics.put(io.github.youqing.sts1solver.mirror.hooks.RelicHooks.BLUE_CANDLE,
                Integer.valueOf(-1));
        int hpBefore = s.hp;
        CardPlayer.play(s, 0, -1);

        assertEquals(hpBefore - 1, s.hp, "打出诅咒掉 1 血");
        assertEquals(4, s.energy, "费用按 0 算，能量不变");
        assertEquals(1, s.exhaust.size(), "进消耗堆");
        assertEquals(0, s.hand.size());
    }

    /** 医疗包：状态牌可打出并消耗，**不掉血**。 */
    @Test
    void medicalKitPlaysStatusesWithoutHpCost() {
        SimState s = combat("Wound");
        s.hand.set(0, new SimCard("Wound", false, -2, -1, "STATUS"));

        assertThrows(ActionRejected.class, () -> CardPlayer.play(s, 0, -1));

        s.relics.put(io.github.youqing.sts1solver.mirror.hooks.RelicHooks.MEDICAL_KIT,
                Integer.valueOf(-1));
        int hpBefore = s.hp;
        CardPlayer.play(s, 0, -1);

        assertEquals(hpBefore, s.hp, "医疗包不掉血");
        assertEquals(4, s.energy);
        assertEquals(1, s.exhaust.size(), "状态牌打出后消耗");
    }

    /** 两块遗物各管各的：蓝蜡烛不能让状态牌可打，医疗包也不能让诅咒可打。 */
    @Test
    void theTwoRelicsDoNotCrossOver() {
        SimState s = combat("Wound");
        s.hand.set(0, new SimCard("Wound", false, -2, -1, "STATUS"));
        s.relics.put(io.github.youqing.sts1solver.mirror.hooks.RelicHooks.BLUE_CANDLE,
                Integer.valueOf(-1));
        assertThrows(ActionRejected.class, () -> CardPlayer.play(s, 0, -1));

        SimState s2 = combat("AscendersBane");
        s2.hand.set(0, new SimCard("AscendersBane", false, -2, -1, "CURSE"));
        s2.relics.put(io.github.youqing.sts1solver.mirror.hooks.RelicHooks.MEDICAL_KIT,
                Integer.valueOf(-1));
        assertThrows(ActionRejected.class, () -> CardPlayer.play(s2, 0, -1));
    }

    /** 死灵诅咒：消耗后**回手**（{@code Necronomicurse.onExhaust}），不在消耗堆里。 */
    @Test
    void necronomicurseComesBackToHand() {
        SimState s = combat("Necronomicurse");
        s.hand.set(0, new SimCard("Necronomicurse", false, -2, -1, "CURSE"));
        s.relics.put(io.github.youqing.sts1solver.mirror.hooks.RelicHooks.BLUE_CANDLE,
                Integer.valueOf(-1));

        CardPlayer.play(s, 0, -1);

        assertEquals(1, s.hand.size(), "回到手牌");
        assertEquals("Necronomicurse", s.hand.get(0).id);
        assertEquals(0, s.exhaust.size(), "不在消耗堆");
    }
}
