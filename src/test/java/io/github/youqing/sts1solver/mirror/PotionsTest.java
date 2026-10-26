package io.github.youqing.sts1solver.mirror;

import org.junit.jupiter.api.Test;

import java.util.Collections;

import io.github.youqing.sts1solver.sim.CombatFlow;
import io.github.youqing.sts1solver.sim.SimCard;
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

    // ------------------------------------------------- 第二批：直接效果类

    @Test
    void strengthPotionAddsTwoStrength() {
        SimState s = fight();
        s.playerPowers.put("Strength", 3);
        assertTrue(Potions.use(s, Potions.STRENGTH_POTION, -1));
        assertEquals(5, s.playerPowers.get("Strength"));
    }

    @Test
    void dexterityPotionAddsTwoDexterity() {
        SimState s = fight();
        assertTrue(Potions.use(s, Potions.DEXTERITY_POTION, -1));
        assertEquals(2, s.playerPowers.get("Dexterity"));
        // 敏捷立刻影响后续格挡量
        assertEquals(14, DamageCalc.blockAmount(s, 12));
    }

    /** 恐惧药水是 {@code ApplyPowerAction(player, target, VulnerablePower, 3)}，要选目标。 */
    @Test
    void fearPotionAppliesThreeVulnerableToTheTarget() {
        SimState s = fight();
        SimMonster second = new SimMonster("Second", 40, 40, 0,
                Collections.<String, Integer>emptyMap());
        s.monsters.add(second);

        assertTrue(Potions.use(s, Potions.FEAR_POTION, 1));

        assertEquals(3, s.monsters.get(1).powers.get("Vulnerable"));
        assertFalse(s.monsters.get(0).powers.containsKey("Vulnerable"), "只打选中的那只");
    }

    /** 爆炸药水对**所有活着的怪**各打 10（走易伤修正）。 */
    @Test
    void explosivePotionHitsEveryLivingMonster() {
        SimState s = fight();
        SimMonster second = new SimMonster("Second", 40, 40, 0,
                Collections.<String, Integer>emptyMap());
        second.powers.put("Vulnerable", 1);
        s.monsters.add(second);
        SimMonster dead = new SimMonster("Dead", 0, 40, 0,
                Collections.<String, Integer>emptyMap());
        s.monsters.add(dead);

        assertTrue(Potions.use(s, Potions.EXPLOSIVE_POTION, -1));

        assertEquals(30, s.monsters.get(0).hp, "40 - 10");
        assertEquals(25, s.monsters.get(1).hp, "40 - 15（易伤 ×1.5）");
        assertEquals(0, s.monsters.get(2).hp, "死掉的怪不再吃伤害");
    }

    /** 血药 = {@code (int)(maxHp × 0.2f)}，**向零截断**。 */
    @Test
    void bloodPotionHealsTwentyPercentTruncated() {
        SimState s = fight();
        s.maxHp = 75;       // 75 × 0.2 = 15.0
        s.hp = 30;
        assertTrue(Potions.use(s, Potions.BLOOD_POTION, -1));
        assertEquals(45, s.hp);

        SimState odd = fight();
        odd.maxHp = 77;     // 77 × 0.2 = 15.4 → 15
        odd.hp = 10;
        Potions.use(odd, Potions.BLOOD_POTION, -1);
        assertEquals(25, odd.hp);

        // 溢出截到满血
        SimState nearlyFull = fight();
        nearlyFull.maxHp = 80;
        nearlyFull.hp = 75;
        Potions.use(nearlyFull, Potions.BLOOD_POTION, -1);
        assertEquals(80, nearlyFull.hp);
    }

    @Test
    void ancientPotionGrantsOneArtifact() {
        SimState s = fight();
        assertTrue(Potions.use(s, Potions.ANCIENT_POTION, -1));
        assertEquals(1, s.playerPowers.get("Artifact"));
    }

    @Test
    void poisonPotionAppliesSixPoisonToTheTarget() {
        SimState s = fight();
        assertTrue(Potions.use(s, Potions.POISON_POTION, 0));
        assertEquals(6, s.monsters.get(0).powers.get("Poison"));
    }

    /** 果汁是永久成长：{@code increaseMaxHp(5, true)}，血量跟着一起抬。 */
    @Test
    void fruitJuiceRaisesMaxHpPermanently() {
        SimState s = fight();
        s.maxHp = 80;
        s.hp = 30;
        assertTrue(Potions.use(s, Potions.FRUIT_JUICE, -1));
        assertEquals(85, s.maxHp);
        assertEquals(35, s.hp);
    }

    @Test
    void heartOfIronGrantsSixMetallicize() {
        SimState s = fight();
        assertTrue(Potions.use(s, Potions.HEART_OF_IRON, -1));
        assertEquals(6, s.playerPowers.get("Metallicize"));
    }

    @Test
    void liquidBronzeGrantsThreeThorns() {
        SimState s = fight();
        assertTrue(Potions.use(s, Potions.LIQUID_BRONZE, -1));
        assertEquals(3, s.playerPowers.get("Thorns"));
    }

    // -------------------------------------------- 第三批：三选一发现类

    @Test
    void discoveryPotionsRequireAChoice() {
        assertTrue(Potions.needsChoice(Potions.POWER_POTION));
        assertTrue(Potions.needsChoice(Potions.ATTACK_POTION));
        assertTrue(Potions.needsChoice(Potions.SKILL_POTION));
        assertTrue(Potions.needsChoice(Potions.COLORLESS_POTION));
        assertFalse(Potions.needsChoice(Potions.FIRE_POTION), "火药水不选牌");
    }

    /** 四张三选一的 ID **都无空格**（javap 核实），和 Fire Potion 那批不同。 */
    @Test
    void discoveryPotionIdsHaveNoSpace() {
        assertEquals("PowerPotion", Potions.POWER_POTION);
        assertEquals("AttackPotion", Potions.ATTACK_POTION);
        assertEquals("SkillPotion", Potions.SKILL_POTION);
        assertEquals("ColorlessPotion", Potions.COLORLESS_POTION);
    }

    /** 能力药水走 {@code DiscoveryAction(CardType.POWER, 1)}：三张候选选一张进手牌。 */
    @Test
    void powerPotionPutsOnePowerCardInHand() {
        SimState s = fight();
        int before = s.hand.size();

        assertTrue(Potions.use(s, Potions.POWER_POTION, -1, 1));

        assertEquals(before + 1, s.hand.size(), "只进手牌 1 张");
        assertEquals("POWER", s.hand.get(s.hand.size() - 1).type, "能力牌");
    }

    @Test
    void attackPotionPutsOneAttackCardInHand() {
        SimState s = fight();
        assertTrue(Potions.use(s, Potions.ATTACK_POTION, -1, 0));
        assertEquals(1, s.hand.size());
        assertEquals("ATTACK", s.hand.get(0).type);
    }

    /** 选第几张候选由 {@code choiceIndex} 决定，越界时退回 0（与「发现」卡同语义）。 */
    @Test
    void discoveryChoiceIndexIsHonoured() {
        SimState s1 = fight();
        Potions.use(s1, Potions.ATTACK_POTION, -1, 0);
        SimState s2 = fight();
        Potions.use(s2, Potions.ATTACK_POTION, -1, 2);
        SimState s3 = fight();
        Potions.use(s3, Potions.ATTACK_POTION, -1, 99);   // 越界 → 0

        assertEquals(s1.hand.get(0).id, s3.hand.get(0).id,
                "越界的 choiceIndex 退回 0，和第 0 张一致");
    }

    // -------------------------------------------- 第四批：选牌类（多选/单选）

    /** 灵药的 ID 带 Potion 后缀，不是 "Elixir"。 */
    @Test
    void elixirIdIsElixirPotion() {
        assertEquals("ElixirPotion", Potions.ELIXIR);
        assertEquals("GamblersBrew", Potions.GAMBLERS_BREW);
        assertEquals("LiquidMemories", Potions.LIQUID_MEMORIES);
    }

    @Test
    void choiceModesAreClassified() {
        assertEquals(Potions.ChoiceMode.MULTI, Potions.choiceModeOf(Potions.GAMBLERS_BREW));
        assertEquals(Potions.ChoiceMode.MULTI, Potions.choiceModeOf(Potions.ELIXIR));
        assertEquals(Potions.ChoiceMode.SINGLE, Potions.choiceModeOf(Potions.LIQUID_MEMORIES));
        assertEquals(Potions.ChoiceMode.SINGLE, Potions.choiceModeOf(Potions.POWER_POTION));
        assertEquals(Potions.ChoiceMode.NONE, Potions.choiceModeOf(Potions.FIRE_POTION));
        assertTrue(Potions.choiceFromDiscard(Potions.LIQUID_MEMORIES),
                "液态记忆从弃牌堆取");
    }

    /** 赌徒酿造：弃掉选中的手牌，然后**抽等量**。 */
    @Test
    void gamblersBrewDiscardsSelectedAndDrawsAsMany() {
        SimState s = fight();
        s.hand.add(new SimCard("Strike_R", false, 1));
        s.hand.add(new SimCard("Defend_R", false, 1));
        s.hand.add(new SimCard("Bash", false, 2));
        s.draw.add(new SimCard("Strike_R", false, 1));
        s.draw.add(new SimCard("Defend_R", false, 1));

        assertTrue(Potions.useMulti(s, Potions.GAMBLERS_BREW, new int[]{0, 2}));

        // 手牌剩 1 张（Defend_R），抽了 2 张回来 → 3 张
        assertEquals(3, s.hand.size(), "弃 2 张后抽 2 张");
        assertEquals(2, s.discard.size(), "弃掉的 2 张进了弃牌堆");
    }

    /** 灵药：消耗选中的手牌（不动抽牌堆）。 */
    @Test
    void elixirExhaustsSelectedCards() {
        SimState s = fight();
        s.hand.add(new SimCard("Strike_R", false, 1));
        s.hand.add(new SimCard("Defend_R", false, 1));
        s.hand.add(new SimCard("Bash", false, 2));

        assertTrue(Potions.useMulti(s, Potions.ELIXIR, new int[]{0, 1}));

        assertEquals(1, s.hand.size(), "剩 1 张");
        assertEquals("Bash", s.hand.get(0).id);
        assertEquals(2, s.exhaust.size(), "选中的 2 张进了消耗堆");
    }

    /** 灵药可以一张不选（原版 {@code canPickZero}）。 */
    @Test
    void elixirCanPickZero() {
        SimState s = fight();
        s.hand.add(new SimCard("Strike_R", false, 1));
        assertTrue(Potions.useMulti(s, Potions.ELIXIR, new int[0]));
        assertEquals(1, s.hand.size());
        assertEquals(0, s.exhaust.size());
    }

    /** 液态记忆：从**弃牌堆**选 1 张拿回手牌。 */
    @Test
    void liquidMemoriesTakesOneFromDiscard() {
        SimState s = fight();
        s.discard.add(new SimCard("Bash", false, 2));
        s.discard.add(new SimCard("Defend_R", false, 1));

        assertTrue(Potions.use(s, Potions.LIQUID_MEMORIES, -1, 1));

        assertEquals(1, s.hand.size());
        assertEquals("Defend_R", s.hand.get(0).id);
        assertEquals(1, s.discard.size());
    }

    /** 弃牌堆空时用不出（原版 do-while 也走不到）。 */
    @Test
    void liquidMemoriesFailsOnEmptyDiscard() {
        SimState s = fight();
        assertFalse(Potions.use(s, Potions.LIQUID_MEMORIES, -1, 0));
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
    // ------------------------------------------------------------ 怪物能力

    /**
     * 飞行（{@code FlightPower}）：普通攻击伤害减半（向零截断），每被命中一次掉 1 层。
     *
     * <p>Act 2 的 Byrd / Darkling 都有，是**我们打它**的伤害修正 —— 不建模的话
     * 搜索会高估自己的输出，以为能一回合打死结果打不死。
     */
    @Test
    void flightHalvesAttackDamageAndTicksDown() {
        SimState s = fight();
        SimMonster byrd = s.monsters.get(0);
        byrd.hp = 40;
        byrd.powers.put("Flight", 2);

        DamageCalc.hitAndReturnDealt(s, byrd, 21);   // 21 / 2 = 10.5 -> 10

        assertEquals(30, byrd.hp, "40 - 10");
        assertEquals(1, byrd.powers.get("Flight").intValue(), "掉 1 层");

        DamageCalc.hitAndReturnDealt(s, byrd, 10);
        assertEquals(25, byrd.hp, "5 / 2 = 2.5 -> 2");
        assertFalse(byrd.powers.containsKey("Flight"), "第 2 层掉完就没了");

        DamageCalc.hitAndReturnDealt(s, byrd, 10);
        assertEquals(15, byrd.hp, "没飞行了，全额 10");
    }


    /** 荆棘（{@code ThornsPower}）：打它一下，它反 amount 点给我们。 */
    @Test
    void thornsReflectsDamageToTheAttacker() {
        SimState s = fight();
        s.hp = 30;
        s.monsters.get(0).powers.put("Thorns", 3);

        DamageCalc.hit(s, s.monsters.get(0), 20);

        assertEquals(20, s.monsters.get(0).hp, "怪掉 20");
        assertEquals(27, s.hp, "玩家被反 3 点");
    }

    /** 荆棘反伤走玩家格挡。 */
    @Test
    void thornsIsSoakedByPlayerBlock() {
        SimState s = fight();
        s.hp = 30;
        s.block = 5;
        s.monsters.get(0).powers.put("Thorns", 3);

        DamageCalc.hit(s, s.monsters.get(0), 20);

        assertEquals(2, s.block, "5 - 3");
        assertEquals(30, s.hp, "格挡吃满，不掉血");
    }


    /**
     * 装甲（{@code PlatedArmorPower}）：回合末加 N 格挡，每次受伤掉 1 层。
     * Spheric Guardian 等 Act 2 怪有。
     */
    @Test
    void platedArmorGainsBlockAtEndOfTurnAndTicksDownOnHit() {
        SimState s = fight();
        s.monsters.get(0).powers.put("Plated Armor", 3);
        s.monsters.get(0).intentDmg = 0;

        CombatFlow.endTurn(s, 3, 5);

        assertEquals(3, s.monsters.get(0).block, "回合末加 3 格挡");

        DamageCalc.hitAndReturnDealt(s, s.monsters.get(0), 5);
        assertEquals(2, s.monsters.get(0).powers.get("Plated Armor").intValue(), "受伤掉 1 层");
    }


    /**
     * 神器（{@code ArtifactPower}）：施加负面效果时抵消一次并消耗一层。
     * Spheric Guardian 等有，不建模的话搜索以为易伤生效、实际被吃。
     */
    @Test
    void artifactBlocksOneDebuffAndTicksDown() {
        SimState s = fight();
        SimMonster m = s.monsters.get(0);
        m.powers.put("Artifact", 2);

        assertFalse(Effects.applyDebuff(s, m, "Vulnerable", 3), "被神器抵消");
        assertEquals(1, m.powers.get("Artifact").intValue(), "消耗一层");
        assertFalse(m.powers.containsKey("Vulnerable"));

        assertFalse(Effects.applyDebuff(s, m, "Vulnerable", 3), "第 2 次也被抵消");
        assertFalse(m.powers.containsKey("Artifact"), "第 2 层吃完就没了");

        assertTrue(Effects.applyDebuff(s, m, "Vulnerable", 3), "没神器了，正常施加");
        assertEquals(3, m.powers.get("Vulnerable").intValue());
    }


    /** 仪式（{@code RitualPower}）：**玩家回合末**给怪加 N 点力量。 */
    @Test
    void ritualGivesStrengthAtEndOfPlayerTurn() {
        SimState s = fight();
        s.monsters.get(0).powers.put("Ritual", 2);
        s.monsters.get(0).intentDmg = 0;

        CombatFlow.endTurn(s, 3, 5);

        assertEquals(2, s.monsters.get(0).powers.get("Strength").intValue());
    }


    /** 愤怒（{@code AngryPower}）：受击时 +N 点力量。 */
    @Test
    void angryGivesStrengthWhenAttacked() {
        SimState s = fight();
        SimMonster m = s.monsters.get(0);
        m.powers.put("Angry", 1);

        DamageCalc.hitAndReturnDealt(s, m, 5);
        assertEquals(1, m.powers.get("Strength").intValue(), "受击 +1 力量");

        DamageCalc.hitAndReturnDealt(s, m, 5);
        assertEquals(2, m.powers.get("Strength").intValue(), "再受击再 +1");
    }


    /** 蜷缩（{@code CurlUpPower}）：受伤加格挡，**只一次**，打死不触发。 */
    @Test
    void curlUpGrantsBlockOnceAndNotWhenKilled() {
        SimState s = fight();
        SimMonster m = s.monsters.get(0);
        m.hp = 40;
        m.powers.put("Curl Up", 6);

        DamageCalc.hitAndReturnDealt(s, m, 5);
        assertEquals(6, m.block, "蜷缩加 6 格挡");
        assertFalse(m.powers.containsKey("Curl Up"), "一次性，用完即弃");

        DamageCalc.hitAndReturnDealt(s, m, 5);
        assertEquals(1, m.block, "第二次打 5 伤，格挡吃掉 5 → 剩 1；不再触发蜷缩");
    }

    // ------------------------------------------------------------ 第二批（直接效果类）

    /** 精钢精华：4 层装甲（Plated Armor）。 */
    @Test
    void essenceOfSteelGrantsPlatedArmor() {
        SimState s = fight();
        assertTrue(Potions.use(s, Potions.ESSENCE_OF_STEEL, -1));
        assertEquals(Integer.valueOf(4), s.playerPowers.get("Plated Armor"));
        assertTrue(Potions.use(s, Potions.ESSENCE_OF_STEEL, -1));
        assertEquals(Integer.valueOf(8), s.playerPowers.get("Plated Armor"), "可以叠加");
    }

    /** 瓶中幽灵：1 层无实体，且刚挂上那回合不掉层（justApplied）。 */
    @Test
    void ghostInAJarGrantsFreshIntangible() {
        SimState s = fight();
        assertTrue(Potions.use(s, Potions.GHOST_IN_A_JAR, -1));
        assertEquals(Integer.valueOf(1), s.playerPowers.get("Intangible"));
        assertTrue(s.playerPowers.containsKey("Intangible#fresh"),
                "刚挂上要打 fresh 标，否则同一回合末就掉光了");

        int hpBefore = s.hp;
        io.github.youqing.sts1solver.sim.CombatFlow.hitPlayer(s, 30);
        assertEquals(hpBefore - 1, s.hp, "无实体把 30 点压成 1 点");
    }

    /** 迅捷药水：抽 3 张。 */
    @Test
    void swiftPotionDrawsThree() {
        SimState s = fight();
        for (int i = 0; i < 6; i++) {
            s.draw.add(new SimCard("Strike_R", false, 1, 6, "ATTACK"));
        }
        assertTrue(Potions.use(s, Potions.SWIFT_POTION, -1));
        assertEquals(3, s.hand.size());
    }

    /** 弱化药水：单体 3 层虚弱，且必须指定目标。 */
    @Test
    void weakPotionAppliesThreeWeakToItsTarget() {
        SimState s = fight();
        assertTrue(Potions.needsTarget(Potions.WEAK_POTION), "单体药水要选目标");
        assertTrue(Potions.use(s, Potions.WEAK_POTION, 0));
        assertEquals(Integer.valueOf(3), s.monsters.get(0).powers.get("Weakened"));
        assertFalse(Potions.use(s, Potions.WEAK_POTION, 5), "目标越界要拒绝");
    }
    // ------------------------------------------------------------ 第三批（回合末结算 / 随机化费用）

    /** 邪教徒药水：1 层仪式，回合末 +1 力量（玩家版走 atEndOfTurn，不是 atEndOfRound）。 */
    @Test
    void cultistPotionGrantsRitualThatPaysOutEachTurn() {
        SimState s = fight();
        assertTrue(Potions.use(s, Potions.CULTIST_POTION, -1));
        assertEquals(Integer.valueOf(1), s.playerPowers.get("Ritual"));

        s.monsters.get(0).pendingMove = io.github.youqing.sts1solver.sim.MonsterMove.block(1, 0);
        s.monsters.get(0).pendingMoveId = 1;
        CombatFlow.endTurn(s, 3, 5);

        assertEquals(Integer.valueOf(1), s.playerPowers.get("Strength"), "回合末 +1 力量");
    }

    /** 再生药水：每回合回 5 血，然后自身 -1 层（Regeaction 的 isPlayer 衰减）。 */
    @Test
    void regenPotionHealsEveryTurnAndDecays() {
        SimState s = fight();
        assertTrue(Potions.use(s, Potions.REGEN_POTION, -1));
        assertEquals(Integer.valueOf(5), s.playerPowers.get("Regeneration"),
                "ID 必须是 Regeneration（不是 Regen）");

        s.monsters.get(0).pendingMove = io.github.youqing.sts1solver.sim.MonsterMove.block(1, 0);
        s.monsters.get(0).pendingMoveId = 1;
        CombatFlow.endTurn(s, 3, 5);

        assertEquals(35, s.hp, "回合末回 5");
        assertEquals(Integer.valueOf(4), s.playerPowers.get("Regeneration"), "层数 -1");
    }

    /** 速度药水：+5 敏捷但回合末还回去（DexLoss）。 */
    @Test
    void speedPotionGivesDexterityBackAtEndOfTurn() {
        SimState s = fight();
        assertTrue(Potions.use(s, Potions.SPEED_POTION, -1));
        assertEquals(Integer.valueOf(5), s.playerPowers.get("Dexterity"));
        assertEquals(Integer.valueOf(5), s.playerPowers.get("DexLoss"));

        s.monsters.get(0).pendingMove = io.github.youqing.sts1solver.sim.MonsterMove.block(1, 0);
        s.monsters.get(0).pendingMoveId = 1;
        CombatFlow.endTurn(s, 3, 5);

        assertEquals(Integer.valueOf(0), s.playerPowers.get("Dexterity"), "敏捷被收回");
        assertNull(s.playerPowers.get("DexLoss"), "还完债自己消失");
    }

    /** 兴奋剂药水：+5 力量但回合末还回去（Flex）。 */
    @Test
    void steroidPotionGivesStrengthBackAtEndOfTurn() {
        SimState s = fight();
        assertTrue(Potions.use(s, Potions.STEROID_POTION, -1));
        assertEquals(Integer.valueOf(5), s.playerPowers.get("Strength"));
        assertEquals(Integer.valueOf(5), s.playerPowers.get("Flex"),
                "ID 必须是 Flex（LoseStrengthPower.POWER_ID）");

        s.monsters.get(0).pendingMove = io.github.youqing.sts1solver.sim.MonsterMove.block(1, 0);
        s.monsters.get(0).pendingMoveId = 1;
        CombatFlow.endTurn(s, 3, 5);

        assertEquals(Integer.valueOf(0), s.playerPowers.get("Strength"));
        assertNull(s.playerPowers.get("Flex"));
    }

    /** 蛇油：抽 5 张，然后把手里每张**能打的**牌的费用重掷成 0..3（打不出/X 费不动）。 */
    @Test
    void sneckoOilDrawsFiveAndRerollsPlayableCosts() {
        SimState s = fight();
        s.rng.put(SimState.RngKind.CARD, io.github.youqing.sts1solver.sim.RngStream.from(12345L, 67890L, 0));
        for (int i = 0; i < 5; i++) {
            s.draw.add(new SimCard("Strike_R", false, 1, 6, "ATTACK"));
        }
        s.hand.add(new SimCard("Strike_R", false, 2, 6, "ATTACK"));
        s.hand.add(new SimCard("Defend_R", false, 1, 0, "SKILL"));
        s.hand.add(new SimCard("AscendersBane", false, -2, -1, "CURSE"));
        s.hand.add(new SimCard("Whirlwind", false, -1, 5, "ATTACK"));

        assertTrue(Potions.use(s, Potions.SNECKO_OIL, -1));

        assertEquals(9, s.hand.size(), "手牌 4 张 + 蛇油抽 5 张");
        for (SimCard c : s.hand) {
            if ("AscendersBane".equals(c.id)) {
                assertEquals(-2, c.cost, "打不出的牌费用不动");
            } else if ("Whirlwind".equals(c.id)) {
                assertEquals(-1, c.cost, "X 费不动");
            } else {
                assertTrue(c.cost >= 0 && c.cost <= 3,
                        "重掷后的费用必须落在 0..3，实际 " + c.cost);
                // 字节码：只有**掷出的值与原费用不同**时才同时写 cost 与 costForTurn；
                // 掷到一样的就保持 costForTurn = -1（没有临时改动）。
                assertTrue(c.costForTurn == -1 || c.costForTurn == c.cost,
                        "costForTurn 要么等于新费用，要么还是 -1（掷到原值）");
            }
        }
        assertEquals(7, s.rng.get(SimState.RngKind.CARD).counter(),
                "只有 7 张能打的牌各抽一次（4 张原有 + 5 张新抽 - 1 打不出 - 1 X 费）");
    }

    /** 复制药水：下一张牌结算两次（能量只付一次，牌只进一次弃牌堆）。 */
    @Test
    void duplicationPotionReplaysTheNextCardOnce() {
        SimState s = fight();
        s.hand.add(new SimCard("Strike_R", false, 1, 6, "ATTACK"));

        assertTrue(Potions.use(s, Potions.DUPLICATION_POTION, -1));
        assertEquals(Integer.valueOf(1), s.playerPowers.get("DuplicationPower"));

        CardPlayer.play(s, 0, 0);

        assertEquals(28, s.monsters.get(0).hp, "40 - 6 - 6 = 28（打了两次）");
        assertEquals(2, s.energy, "能量只付一次");
        assertNull(s.playerPowers.get("DuplicationPower"), "一层用完就没了");
        assertEquals(1, s.discard.size(), "牌只进一次弃牌堆");

        // 第二次出牌不再复制
        s.hand.add(new SimCard("Strike_R", false, 1, 6, "ATTACK"));
        CardPlayer.play(s, 0, 0);
        assertEquals(22, s.monsters.get(0).hp, "只打一次：28 - 6");
    }
}
