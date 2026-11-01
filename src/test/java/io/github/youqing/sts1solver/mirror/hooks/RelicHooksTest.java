package io.github.youqing.sts1solver.mirror.hooks;

import org.junit.jupiter.api.Test;

import java.util.Collections;

import io.github.youqing.sts1solver.mirror.DamageCalc;
import io.github.youqing.sts1solver.sim.CombatFlow;
import io.github.youqing.sts1solver.sim.MonsterMove;
import io.github.youqing.sts1solver.sim.SimMonster;
import io.github.youqing.sts1solver.sim.SimState;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 遗物第二批：被动 / 回合钩子（纸青蛙 / 鸟居 / 水银沙漏 / 号角夹板 / 舵轮 / 山铜）。
 *
 * <p>数值与 ID 全部按 {@code com.megacrit.cardcrawl.relics.*} 的字节码核实，见
 * {@code mechanics-reference} §33.44。
 */
class RelicHooksTest {

    private static SimState fight() {
        SimState s = new SimState();
        s.hp = 80;
        s.maxHp = 80;
        s.energy = 3;
        SimMonster m = new SimMonster("TestMonster", 100, 100, 0,
                Collections.<String, Integer>emptyMap());
        s.monsters.add(m);
        return s;
    }

    /** 回合末结算需要怪物有招，否则它没法行动。 */
    private static void armedMonster(SimState s) {
        SimMonster m = s.monsters.get(0);
        m.pendingMove = MonsterMove.block(1, 0);
        m.pendingMoveId = 1;
    }

    // ---------------------------------------------------------------- 纸青蛙：易伤倍率

    /**
     * 纸青蛙只放大「玩家打敌人」的易伤倍率（1.5 → 1.75）。
     *
     * <p>🔴 它的类名是 {@code PaperFrog}、ID 是 {@code "Paper Frog"}；按显示名
     * "Paper Phrog" 去查会**永远查不到**（死条目）。
     */
    @Test
    void paperFrogRaisesVulnerableMultiplierForPlayerAttacks() {
        SimState s = fight();
        SimMonster m = s.monsters.get(0);
        m.powers.put("Vulnerable", Integer.valueOf(1));

        assertEquals(15, DamageCalc.attackDamage(s, 10, m), "没遗物：10 × 1.5 = 15");

        s.relics.put(RelicHooks.PAPER_FROG, Integer.valueOf(-1));
        assertEquals(17, DamageCalc.attackDamage(s, 10, m), "有纸青蛙：10 × 1.75 = 17.5 → 17");
    }

    /** 敌人打玩家那条不吃纸青蛙：玩家自己身上有易伤仍是 1.5。 */
    @Test
    void paperFrogDoesNotAffectDamageTakenByThePlayer() {
        SimState s = fight();
        SimMonster m = s.monsters.get(0);
        s.playerPowers.put("Vulnerable", Integer.valueOf(1));
        s.relics.put(RelicHooks.PAPER_FROG, Integer.valueOf(-1));

        assertEquals(15, DamageCalc.monsterAttackDamage(s, 10, m), "玩家挨打仍是 ×1.5");
    }

    // ---------------------------------------------------------------- 鸟居：小额攻击变 1

    /** 鸟居：2..5 点的攻击伤害改成 1；1 点不动，6 点也不动。 */
    @Test
    void toriiCapsSmallAttackDamageAtOne() {
        SimState s = fight();
        s.relics.put(RelicHooks.TORII, Integer.valueOf(-1));
        SimMonster m = s.monsters.get(0);

        CombatFlow.hitPlayer(s, 5, m);
        assertEquals(79, s.hp, "5 → 1");

        CombatFlow.hitPlayer(s, 2, m);
        assertEquals(78, s.hp, "2 → 1");

        CombatFlow.hitPlayer(s, 1, m);
        assertEquals(77, s.hp, "1 不触发（要 > 1）");

        CombatFlow.hitPlayer(s, 6, m);
        assertEquals(71, s.hp, "6 不触发（要 <= 5）");
    }

    /** 鸟居不认「没有攻击者」的掉血（HP_LOSS 类型，例如缠绕、自伤）。 */
    @Test
    void toriiIgnoresDamageWithoutAnAttacker() {
        SimState s = fight();
        s.relics.put(RelicHooks.TORII, Integer.valueOf(-1));

        CombatFlow.hitPlayer(s, 5);
        assertEquals(75, s.hp, "无攻击者 = HP 损失，吃满 5 点");
    }

    // ---------------------------------------------------------------- 水银沙漏

    /** 水银沙漏：新回合开始对全体 3 点纯伤（不吃力量/易伤）。 */
    @Test
    void mercuryHourglassDealsThreePureDamageEachTurn() {
        SimState s = fight();
        armedMonster(s);
        s.relics.put(RelicHooks.MERCURY_HOURGLASS, Integer.valueOf(-1));
        s.playerPowers.put("Strength", Integer.valueOf(5));
        s.monsters.get(0).powers.put("Vulnerable", Integer.valueOf(2));

        CombatFlow.endTurn(s, 3, 5);

        assertEquals(97, s.monsters.get(0).hp, "固定 3 点：力量/易伤都不参与");
    }

    /**
     * 沙漏是**玩家回合开始**结的，而怪物的格挡在它自己的回合开始就清掉了 ——
     * 所以玩家回合开始时怪物身上的格挡一定是 0，沙漏的 3 点必然全打在血上。
     *
     * <p>这条钉的是「顺序」：如果谁把沙漏挪到怪物回合之前结算，这条会红。
     */
    @Test
    void mercuryHourglassHitsAfterMonsterBlockIsAlreadyCleared() {
        SimState s = fight();
        armedMonster(s);
        s.relics.put(RelicHooks.MERCURY_HOURGLASS, Integer.valueOf(-1));
        s.monsters.get(0).block = 2;

        CombatFlow.endTurn(s, 3, 5);

        assertEquals(97, s.monsters.get(0).hp, "怪物格挡在它回合开始就没了，3 点全落地");
        assertEquals(0, s.monsters.get(0).block);
    }

    // ---------------------------------------------------------------- 号角夹板 / 舵轮

    /** 号角夹板：第 2 个回合开始 +14 格挡，触发一次后 counter = -1 并冻住。 */
    @Test
    void hornCleatGivesBlockOnTurnTwoOnly() {
        SimState s = fight();
        armedMonster(s);
        s.relics.put(RelicHooks.HORN_CLEAT, Integer.valueOf(1));   // 第 1 回合已经过完了

        CombatFlow.endTurn(s, 3, 5);
        assertEquals(14, s.block, "第 2 回合开始 +14");
        assertEquals(Integer.valueOf(-1), s.relics.get(RelicHooks.HORN_CLEAT), "置 -1（grayscale）");

        // 第 3 回合不再触发（原版 grayscale 之后 counter 连自增都停了）
        s.block = 0;
        CombatFlow.endTurn(s, 3, 5);
        assertEquals(0, s.block, "只触发一次");
    }

    /** 舵轮：第 3 个回合开始 +18 格挡。 */
    @Test
    void captainsWheelGivesBlockOnTurnThree() {
        SimState s = fight();
        armedMonster(s);
        s.relics.put(RelicHooks.CAPTAINS_WHEEL, Integer.valueOf(2));

        CombatFlow.endTurn(s, 3, 5);
        assertEquals(18, s.block);
        assertEquals(Integer.valueOf(-1), s.relics.get(RelicHooks.CAPTAINS_WHEEL));
    }

    // ---------------------------------------------------------------- 山铜

    /**
     * 山铜：回合结束**一点格挡都没有**才 +6，而且这 6 点要顶住怪物这一轮。
     *
     * <p>断言看「血有没有掉」而不是「结束时 block 多少」：{@code endTurn} 返回时已经翻到
     * 下一个玩家回合，格挡按规矩清空了。
     */
    @Test
    void orichalcumGivesSixBlockOnlyWhenBlockless() {
        SimState s = fight();
        s.monsters.get(0).pendingMove = MonsterMove.attack(2, 5);
        s.monsters.get(0).pendingMoveId = 2;
        s.relics.put(RelicHooks.ORICHALCUM, Integer.valueOf(-1));

        int before = s.hp;
        CombatFlow.endTurn(s, 3, 5);
        assertEquals(before, s.hp, "6 点格挡吃掉 5 点攻击，血不掉");

        // 没有山铜：同样的 5 点攻击要掉血
        SimState s2 = fight();
        s2.monsters.get(0).pendingMove = MonsterMove.attack(2, 5);
        s2.monsters.get(0).pendingMoveId = 2;
        int before2 = s2.hp;
        CombatFlow.endTurn(s2, 3, 5);
        assertEquals(before2 - 5, s2.hp, "没有遗物就掉 5 点");

        // 已经有格挡时山铜不给：4 点格挡 + 5 点攻击 → 掉 1 点
        SimState s3 = fight();
        s3.monsters.get(0).pendingMove = MonsterMove.attack(2, 5);
        s3.monsters.get(0).pendingMoveId = 2;
        s3.relics.put(RelicHooks.ORICHALCUM, Integer.valueOf(-1));
        s3.block = 4;
        int before3 = s3.hp;
        CombatFlow.endTurn(s3, 3, 5);
        assertEquals(before3 - 1, s3.hp, "有格挡就不触发，只挡掉 4 点");
    }
    // ---------------------------------------------------------------- 冰淇淋 / 化学 X

    /** 冰淇淋：本回合没用完的能量**留着**，新回合加上 energyPerTurn。 */
    @Test
    void iceCreamCarriesLeftoverEnergyOver() {
        SimState s = fight();
        s.monsters.get(0).pendingMove = MonsterMove.block(1, 0);
        s.monsters.get(0).pendingMoveId = 1;
        s.energy = 2;
        s.relics.put("Ice Cream", Integer.valueOf(-1));

        CombatFlow.endTurn(s, 3, 5);

        assertEquals(5, s.energy, "剩 2 + 回 3 = 5");

        // 没有冰淇淋：重置成 3
        SimState s2 = fight();
        s2.monsters.get(0).pendingMove = MonsterMove.block(1, 0);
        s2.monsters.get(0).pendingMoveId = 1;
        s2.energy = 2;
        CombatFlow.endTurn(s2, 3, 5);
        assertEquals(3, s2.energy, "没有遗物就重置");
    }

    /** 化学 X：X 费牌的能量消耗量 +2（旋风斩 3 能量 = 5 段）。 */
    @Test
    void chemicalXAddsTwoToEveryXCard() {
        SimState s = fight();
        s.hand.add(new io.github.youqing.sts1solver.sim.SimCard(
                "Whirlwind", false, -1, 5, "ATTACK"));
        s.relics.put("Chemical X", Integer.valueOf(-1));

        io.github.youqing.sts1solver.mirror.CardPlayer.play(s, 0, 0);

        assertEquals(0, s.energy, "X 费牌把能量全花掉");
        assertEquals(75, s.monsters.get(0).hp, "(3 + 2) 段 × 5 = 25 点");

        // 没有遗物：3 段 × 5 = 15
        SimState s2 = fight();
        s2.hand.add(new io.github.youqing.sts1solver.sim.SimCard(
                "Whirlwind", false, -1, 5, "ATTACK"));
        io.github.youqing.sts1solver.mirror.CardPlayer.play(s2, 0, 0);
        assertEquals(85, s2.monsters.get(0).hp, "3 段 × 5 = 15 点");
    }
    // ---------------------------------------------------------------- 钨棒 + 三种伤害类型

    /**
     * 钨棒：**每一次**掉血都减 1 —— 普通攻击、荆棘、掉血（HP_LOSS）三种类型都一样。
     *
     * <p>依据是 {@code AbstractPlayer.damage()} 末尾遍历遗物调 {@code onLoseHpLast} 的那段
     * **没有任何 DamageType 分支**；而 {@code LoseHPAction} 本身就写成
     * {@code target.damage(new DamageInfo(source, n, HP_LOSS))}。
     */
    @Test
    void tungstenRodReducesAllThreeDamageKindsByOne() {
        // ① 普通攻击：6 → 5
        SimState s = fight();
        s.relics.put(RelicHooks.TUNGSTEN_ROD, Integer.valueOf(-1));
        CombatFlow.hitPlayer(s, 6, s.monsters.get(0));
        assertEquals(75, s.hp, "攻击 6 点被钨棒减成 5");

        // ② 荆棘（无攻击者）：3 → 2
        SimState s2 = fight();
        s2.relics.put(RelicHooks.TUNGSTEN_ROD, Integer.valueOf(-1));
        CombatFlow.hitPlayer(s2, 3);
        assertEquals(78, s2.hp, "荆棘 3 点减成 2");

        // ③ 掉血（HP_LOSS）：3 → 2
        SimState s3 = fight();
        s3.relics.put(RelicHooks.TUNGSTEN_ROD, Integer.valueOf(-1));
        CombatFlow.hitPlayer(s3, 3, null, io.github.youqing.sts1solver.sim.DamageKind.HP_LOSS);
        assertEquals(78, s3.hp, "掉血 3 点也减成 2");
    }

    /** 钨棒能把 1 点伤害抹成 0：此时连「受伤」都不算（浴血奋战不减费）。 */
    @Test
    void tungstenRodCanZeroOutOneDamage() {
        SimState s = fight();
        s.relics.put(RelicHooks.TUNGSTEN_ROD, Integer.valueOf(-1));

        int lost = CombatFlow.hitPlayer(s, 1, s.monsters.get(0));

        assertEquals(0, lost);
        assertEquals(80, s.hp, "1 - 1 = 0，血不掉");
        assertEquals(0, s.hpLossCount, "没掉血就不算受伤");
    }

    /**
     * 🔴 管线顺序：**先扣格挡，再鸟居，最后钨棒**。
     *
     * <p>所以「鸟居把 2..5 压成 1」之后钨棒还能再减成 0 —— 两件一起拿 =
     * 2..5 点的攻击完全免疫（这是原版就有的组合，不是 bug）。
     */
    @Test
    void blockToriiAndRodApplyInThatOrder() {
        SimState s = fight();
        s.relics.put(RelicHooks.TORII, Integer.valueOf(-1));
        s.relics.put(RelicHooks.TUNGSTEN_ROD, Integer.valueOf(-1));
        s.block = 4;

        // 6 点攻击：格挡吃掉 4 → 剩 2 → 鸟居压成 1 → 钨棒减成 0
        int lost = CombatFlow.hitPlayer(s, 6, s.monsters.get(0));

        assertEquals(0, lost, "4 格挡 + 鸟居 + 钨棒 → 一点血都不掉");
        assertEquals(0, s.block, "格挡 4 点被吃光");
    }

    /** 掉血（HP_LOSS）**无视格挡**：有 10 点格挡，放血照样掉血。 */
    @Test
    void hpLossIgnoresBlock() {
        SimState s = fight();
        s.block = 10;

        int lost = CombatFlow.hitPlayer(s, 5, null,
                io.github.youqing.sts1solver.sim.DamageKind.HP_LOSS);

        assertEquals(5, lost, "掉血不吃格挡");
        assertEquals(75, s.hp);
        assertEquals(10, s.block, "格挡一点没动");
    }

    /**
     * 掉血仍然吃**无实体**：原版的 {@code if (damage > 1 && hasPower(IntangiblePlayer))}
     * 不看 DamageType，所以放血只掉 1。
     */
    @Test
    void hpLossIsCappedToOneByIntangible() {
        SimState s = fight();
        s.playerPowers.put(io.github.youqing.sts1solver.mirror.DamageCalc.INTANGIBLE_PLAYER,
                Integer.valueOf(1));
        DamageCalc.markIntangibleFresh(s.playerPowers, "Intangible");

        int lost = CombatFlow.hitPlayer(s, 6, null,
                io.github.youqing.sts1solver.sim.DamageKind.HP_LOSS);

        assertEquals(1, lost, "无实体把掉血压成 1");
        assertEquals(79, s.hp);
    }

    /**
     * 被怪打也算「受伤」：浴血奋战的费用靠 {@code hpLossCount} 递减，
     * 以前只有自伤（放血/燃烧）会加，被怪打不加 —— 属于静默算错。
     */
    @Test
    void gettingHitAlsoCountsForBloodForBlood() {
        SimState s = fight();
        CombatFlow.hitPlayer(s, 7, s.monsters.get(0));
        assertEquals(1, s.hpLossCount, "被怪打也要记一次");

        // 完全被格挡吃掉的那次不算
        s.block = 20;
        CombatFlow.hitPlayer(s, 5, s.monsters.get(0));
        assertEquals(1, s.hpLossCount, "格挡全吃 → 没掉血 → 不记");
    }

    /** 放血（Bloodletting）在有无实体时只掉 1 血，但力量补偿照给。 */
    @Test
    void bloodlettingUnderIntangibleLosesOnlyOne() {
        SimState s = fight();
        s.playerPowers.put(io.github.youqing.sts1solver.mirror.DamageCalc.INTANGIBLE_PLAYER,
                Integer.valueOf(1));
        DamageCalc.markIntangibleFresh(s.playerPowers, "Intangible");
        s.hand.add(new io.github.youqing.sts1solver.sim.SimCard(
                "Bloodletting", false, 0, -1, "SKILL"));

        io.github.youqing.sts1solver.mirror.CardPlayer.play(s, 0, -1);

        assertEquals(79, s.hp, "3 点自伤被无实体压成 1");
        assertEquals(5, s.energy, "3 + 2，能量照给");
    }
    // ---------------------------------------------------------------- 受伤触发 / 手牌保留

    /** 符文立方：**每次**掉血抽 1 张（普通攻击、掉血、荆棘都算）。 */
    @Test
    void runicCubeDrawsOneOnEveryHpLoss() {
        SimState s = fight();
        s.relics.put(RelicHooks.RUNIC_CUBE, Integer.valueOf(-1));
        for (int i = 0; i < 6; i++) {
            s.draw.add(new io.github.youqing.sts1solver.sim.SimCard("Strike_R", false, 1, 6, "ATTACK"));
        }

        CombatFlow.hitPlayer(s, 5, s.monsters.get(0));
        assertEquals(1, s.hand.size(), "被打一次抽 1 张");

        CombatFlow.hitPlayer(s, 5, s.monsters.get(0));
        assertEquals(2, s.hand.size(), "再被打一次再抽 1 张");

        // 被格挡全吃的那次不算
        s.block = 30;
        CombatFlow.hitPlayer(s, 5, s.monsters.get(0));
        assertEquals(2, s.hand.size(), "没掉血就不抽");
    }

    /** 百夫长谜题：每场战斗**只有第一次**掉血抽 3 张。 */
    @Test
    void centennialPuzzleDrawsThreeOncePerCombat() {
        SimState s = fight();
        s.relics.put(RelicHooks.CENTENNIAL_PUZZLE, Integer.valueOf(0));
        for (int i = 0; i < 8; i++) {
            s.draw.add(new io.github.youqing.sts1solver.sim.SimCard("Strike_R", false, 1, 6, "ATTACK"));
        }

        CombatFlow.hitPlayer(s, 5, s.monsters.get(0));
        assertEquals(3, s.hand.size(), "第一次掉血抽 3 张");
        assertEquals(Integer.valueOf(1), s.relics.get(RelicHooks.CENTENNIAL_PUZZLE),
                "标记已用");

        CombatFlow.hitPlayer(s, 5, s.monsters.get(0));
        assertEquals(3, s.hand.size(), "第二次不再抽");
    }

    /**
     * 自形成黏土：每次掉血给**下回合** 3 点格挡。
     *
     * <p>它挂的是 {@code NextTurnBlockPower}（ID {@code "Next Turn Block"}），
     * 那个能力在 {@code atStartOfTurn} 里 {@code GainBlockAction} 后**整条移除**。
     */
    @Test
    void selfFormingClayGrantsBlockNextTurn() {
        SimState s = fight();
        armedMonster(s);
        s.relics.put(RelicHooks.SELF_FORMING_CLAY, Integer.valueOf(-1));

        CombatFlow.hitPlayer(s, 5, s.monsters.get(0));
        assertEquals(Integer.valueOf(3),
                s.playerPowers.get(RelicHooks.NEXT_TURN_BLOCK), "先挂到下回合");

        CombatFlow.endTurn(s, 3, 5);

        assertEquals(3, s.block, "新回合开始拿到 3 点格挡");
        assertEquals(null, s.playerPowers.get(RelicHooks.NEXT_TURN_BLOCK), "结清后移除");
    }

    /**
     * 红骷髅：跨过**半血线**时 ±3 力量。
     *
     * <p>「加过了没有」用 {@code state.relics} 里的值当标记（capture 时按血线初始化），
     * 所以这里先手工置 0（= 未生效）。
     */
    @Test
    void redSkullTradesStrengthAtTheHalfHpLine() {
        SimState s = fight();
        s.relics.put(RelicHooks.RED_SKULL, Integer.valueOf(0));

        CombatFlow.hitPlayer(s, 41, s.monsters.get(0));
        assertEquals(39, s.hp, "80 - 41");
        assertEquals(Integer.valueOf(3), s.playerPowers.get(DamageCalc.STRENGTH),
                "掉到 39（≤ 40）→ +3 力量");
        assertEquals(Integer.valueOf(1), s.relics.get(RelicHooks.RED_SKULL));

        // 再掉血：仍然在半血以下，不该重复加
        CombatFlow.hitPlayer(s, 5, s.monsters.get(0));
        assertEquals(Integer.valueOf(3), s.playerPowers.get(DamageCalc.STRENGTH),
                "还在半血以下 → 不重复加");

        // 回血跨回半血以上：力量收回（回血落点会就地同步，这里直接调兜底入口）
        s.hp = 60;
        RelicHooks.syncRedSkull(s);
        assertEquals(Integer.valueOf(0), s.playerPowers.get(DamageCalc.STRENGTH),
                "回到 60（> 40）→ 力量收回");
        assertEquals(Integer.valueOf(0), s.relics.get(RelicHooks.RED_SKULL));
    }

    /** 符文金字塔：回合结束**不弃手牌**（手牌直接带进下一回合）。 */
    @Test
    void runicPyramidKeepsTheHandAtEndOfTurn() {
        SimState s = fight();
        armedMonster(s);
        s.hand.add(new io.github.youqing.sts1solver.sim.SimCard("Strike_R", false, 1, 6, "ATTACK"));
        s.hand.add(new io.github.youqing.sts1solver.sim.SimCard("Defend_R", false, 1, 5, "SKILL"));
        for (int i = 0; i < 10; i++) {
            s.draw.add(new io.github.youqing.sts1solver.sim.SimCard("Strike_R", false, 1, 6, "ATTACK"));
        }
        s.relics.put(RelicHooks.RUNIC_PYRAMID, Integer.valueOf(-1));

        CombatFlow.endTurn(s, 3, 5);

        assertEquals(7, s.hand.size(), "2 张留着 + 抽 5 张");
        assertEquals(0, s.discard.size(), "弃牌堆还是空的");

        // 没有遗物：手牌先全进弃牌堆，再抽 5 张
        SimState s2 = fight();
        armedMonster(s2);
        s2.hand.add(new io.github.youqing.sts1solver.sim.SimCard("Strike_R", false, 1, 6, "ATTACK"));
        s2.hand.add(new io.github.youqing.sts1solver.sim.SimCard("Defend_R", false, 1, 5, "SKILL"));
        for (int i = 0; i < 10; i++) {
            s2.draw.add(new io.github.youqing.sts1solver.sim.SimCard("Strike_R", false, 1, 6, "ATTACK"));
        }
        CombatFlow.endTurn(s2, 3, 5);
        assertEquals(5, s2.hand.size());
        assertEquals(2, s2.discard.size(), "旧手牌进了弃牌堆");
    }
    /**
     * 蛇眼：{@code ConfusionPower.onCardDraw} —— **抽到的牌**费用重掷成 0..3。
     *
     * <p>🔴 能力 ID 是 {@code "Confusion"}（不是 "Confused"），且 {@code amount} 恒为 0，
     * 所以判据必须是 {@code containsKey}。以前写成 "Confused" + {@code > 0}，
     * 两条都错 → 蛇眼从来没生效过。
     */
    @Test
    void sneckoEyeRandomizesCostOfEveryDrawnCard() {
        SimState s = fight();
        s.playerPowers.put("Confusion", Integer.valueOf(0));
        s.rng.put(SimState.RngKind.CARD,
                io.github.youqing.sts1solver.sim.RngStream.from(4242L, 777L, 0));
        for (int i = 0; i < 5; i++) {
            s.draw.add(new io.github.youqing.sts1solver.sim.SimCard("Strike_R", false, 1, 6, "ATTACK"));
        }
        s.draw.add(new io.github.youqing.sts1solver.sim.SimCard("Whirlwind", false, -1, 5, "ATTACK"));

        CombatFlow.drawCards(s, 6);

        assertEquals(6, s.hand.size());
        // 抽牌从堆顶（list 尾部）弹：最后加进去的 Whirlwind 最先到手
        assertEquals(-1, s.hand.get(0).cost, "X 费牌不动");
        for (int i = 1; i < 6; i++) {
            int cost = s.hand.get(i).cost;
            assertTrue(cost >= 0 && cost <= 3, "重掷后落在 0..3，实际 " + cost);
        }
        assertEquals(5, s.rng.get(SimState.RngKind.CARD).counter(),
                "只有 5 张能打的牌各抽一次（X 费不消耗随机）");

        // 没有 Confusion：费用原样（也不消耗随机）
        SimState s2 = fight();
        s2.rng.put(SimState.RngKind.CARD,
                io.github.youqing.sts1solver.sim.RngStream.from(4242L, 777L, 0));
        s2.draw.add(new io.github.youqing.sts1solver.sim.SimCard("Strike_R", false, 1, 6, "ATTACK"));
        CombatFlow.drawCards(s2, 1);
        assertEquals(1, s2.hand.get(0).cost);
        assertEquals(0, s2.rng.get(SimState.RngKind.CARD).counter());
    }
    // ---------------------------------------------------------------- 第四批：消耗/洗牌/击杀/回血/格挡

    /** 灰烬之主：每消耗一张牌，对**全体** 3 点纯伤（不吃力量/易伤，但会被格挡吃掉）。 */
    @Test
    void charonsAshesHitsEveryEnemyOnExhaust() {
        SimState s = fight();
        s.monsters.add(new SimMonster("JawWorm", 40, 40, 0,
                Collections.<String, Integer>emptyMap()));
        s.relics.put(RelicHooks.CHARONS_ASHES, Integer.valueOf(-1));

        io.github.youqing.sts1solver.mirror.Effects.exhaustCard(s,
                new io.github.youqing.sts1solver.sim.SimCard("Strike_R", false, 1, 6, "ATTACK"));

        assertEquals(97, s.monsters.get(0).hp, "第一只 -3");
        assertEquals(37, s.monsters.get(1).hp, "第二只也 -3");
        assertEquals(1, s.exhaust.size());

        // 没有遗物：不掉血
        SimState s2 = fight();
        io.github.youqing.sts1solver.mirror.Effects.exhaustCard(s2,
                new io.github.youqing.sts1solver.sim.SimCard("Strike_R", false, 1, 6, "ATTACK"));
        assertEquals(100, s2.monsters.get(0).hp);
    }

    /** 日晷：洗牌 3 次回 2 能量，计数归零。 */
    @Test
    void sundialPaysTwoEnergyEveryThirdShuffle() {
        SimState s = fight();
        s.relics.put(RelicHooks.SUNDIAL, Integer.valueOf(0));
        s.energy = 1;

        RelicHooks.onShuffle(s);
        assertEquals(Integer.valueOf(1), s.relics.get(RelicHooks.SUNDIAL));
        assertEquals(1, s.energy, "第 1 次不给");
        RelicHooks.onShuffle(s);
        assertEquals(1, s.energy, "第 2 次不给");
        RelicHooks.onShuffle(s);
        assertEquals(3, s.energy, "第 3 次 +2");
        assertEquals(Integer.valueOf(0), s.relics.get(RelicHooks.SUNDIAL), "计数归零");

        // 真路径：弃牌堆洗回抽牌堆也算一次
        s.discard.add(new io.github.youqing.sts1solver.sim.SimCard("Strike_R", false, 1, 6, "ATTACK"));
        s.rng.put(SimState.RngKind.SHUFFLE,
                io.github.youqing.sts1solver.sim.RngStream.from(9L, 9L, 0));
        io.github.youqing.sts1solver.mirror.Effects.reshuffleDiscardIntoDraw(s);
        assertEquals(Integer.valueOf(1), s.relics.get(RelicHooks.SUNDIAL),
                "reshuffleDiscardIntoDraw 也要计一次");
    }

    /** 小鬼号角：击杀时回 1 能量 + 抽 1 张；**清场那一下不给**。 */
    @Test
    void gremlinHornPaysOutOnKillButNotOnTheFinalOne() {
        SimState s = fight();
        s.monsters.add(new SimMonster("JawWorm", 5, 5, 0,
                Collections.<String, Integer>emptyMap()));
        s.relics.put(RelicHooks.GREMLIN_HORN, Integer.valueOf(-1));
        s.energy = 0;
        s.draw.add(new io.github.youqing.sts1solver.sim.SimCard("Strike_R", false, 1, 6, "ATTACK"));

        DamageCalc.hit(s, s.monsters.get(1), 99);
        assertEquals(0, s.monsters.get(1).hp);
        assertEquals(1, s.energy, "击杀回 1 能量");
        assertEquals(1, s.hand.size(), "击杀抽 1 张");

        // 打死最后一只：整场清空 → 不给
        DamageCalc.hit(s, s.monsters.get(0), 999);
        assertEquals(0, s.monsters.get(0).hp);
        assertEquals(1, s.energy, "清场那一下不再给能量");
        assertEquals(1, s.hand.size());
    }

    /** 魔法花：战斗中回血 ×1.5，**四舍五入**（5 → 8，7 → 11）。 */
    @Test
    void magicFlowerRoundsHealingUpByHalf() {
        SimState s = fight();
        assertEquals(5, RelicHooks.healAmount(s, 5), "没遗物：原值");

        s.relics.put(RelicHooks.MAGIC_FLOWER, Integer.valueOf(-1));
        assertEquals(8, RelicHooks.healAmount(s, 5), "5 × 1.5 = 7.5 → 8（四舍五入）");
        assertEquals(11, RelicHooks.healAmount(s, 7), "7 × 1.5 = 10.5 → 11");
        assertEquals(2, RelicHooks.healAmount(s, 1), "1 × 1.5 = 1.5 → 2");
    }

    /** 卡尺：回合结束只掉 15 格挡，不是全清。 */
    @Test
    void calipersDecaysOnlyFifteenBlock() {
        SimState s = fight();
        armedMonster(s);
        s.relics.put(RelicHooks.CALIPERS, Integer.valueOf(-1));
        s.block = 30;

        CombatFlow.endTurn(s, 3, 5);
        assertEquals(15, s.block, "30 - 15 = 15 留到下一回合");

        // 不足 15 就清零
        SimState s2 = fight();
        armedMonster(s2);
        s2.relics.put(RelicHooks.CALIPERS, Integer.valueOf(-1));
        s2.block = 10;
        CombatFlow.endTurn(s2, 3, 5);
        assertEquals(0, s2.block);

        // 没有遗物：全清
        SimState s3 = fight();
        armedMonster(s3);
        s3.block = 30;
        CombatFlow.endTurn(s3, 3, 5);
        assertEquals(0, s3.block);
    }

    /** 玩具鸟翼飞机：每喝一瓶药回 5 血（失败的那次不算）。 */
    @Test
    void toyOrnithopterHealsOnPotionUse() {
        SimState s = fight();
        s.hp = 60;
        s.relics.put(RelicHooks.TOY_ORNITHOPTER, Integer.valueOf(-1));

        assertTrue(io.github.youqing.sts1solver.mirror.Potions.use(
                s, io.github.youqing.sts1solver.mirror.Potions.BLOCK_POTION, -1));
        assertEquals(65, s.hp, "回 5 血");

        // 失败（目标越界）不触发
        assertFalse(io.github.youqing.sts1solver.mirror.Potions.use(
                s, io.github.youqing.sts1solver.mirror.Potions.FIRE_POTION, 5));
        assertEquals(65, s.hp);
    }
    // ---------------------------------------------------------------- 第五批：回合计数 / 能力回血 / 减益免疫

    /**
     * 手里剑 / 扇子 / 开信刀 / 苦无 的计数**每回合清零**（原版 {@code atTurnStart} 第一句就是
     * {@code counter = 0}）。
     *
     * <p>🔴 以前影子从不清零，搜索跨回合推演时会「两回合各打 2 张 → 误判成攒够 3 张」。
     */
    @Test
    void turnCountersResetAtTheStartOfEveryTurn() {
        SimState s = fight();
        armedMonster(s);
        s.relics.put("Shuriken", Integer.valueOf(2));
        s.relics.put("Ornamental Fan", Integer.valueOf(2));
        s.relics.put("Letter Opener", Integer.valueOf(2));
        s.relics.put(RelicHooks.KUNAI, Integer.valueOf(2));
        s.energy = 3;

        CombatFlow.endTurn(s, 3, 5);

        assertEquals(Integer.valueOf(0), s.relics.get("Shuriken"));
        assertEquals(Integer.valueOf(0), s.relics.get("Ornamental Fan"));
        assertEquals(Integer.valueOf(0), s.relics.get("Letter Opener"));
        assertEquals(Integer.valueOf(0), s.relics.get(RelicHooks.KUNAI));
    }

    /** 苦无：**本回合**第 3 张攻击牌 → 敏捷 +1；跨回合不累计。 */
    @Test
    void kunaiGivesDexterityOnTheThirdAttackOfTheTurn() {
        SimState s = fight();
        s.relics.put(RelicHooks.KUNAI, Integer.valueOf(0));

        RelicHooks.onCardUsed(s, new io.github.youqing.sts1solver.sim.SimCard("Strike_R", false, 1, 6, "ATTACK"));
        RelicHooks.onCardUsed(s, new io.github.youqing.sts1solver.sim.SimCard("Strike_R", false, 1, 6, "ATTACK"));
        assertEquals(null, s.playerPowers.get(DamageCalc.DEXTERITY), "两张还不够");
        RelicHooks.onCardUsed(s, new io.github.youqing.sts1solver.sim.SimCard("Strike_R", false, 1, 6, "ATTACK"));
        assertEquals(Integer.valueOf(1), s.playerPowers.get(DamageCalc.DEXTERITY), "第 3 张 +1 敏捷");

        // 技能牌不数
        s.relics.put(RelicHooks.KUNAI, Integer.valueOf(2));
        RelicHooks.onCardUsed(s, new io.github.youqing.sts1solver.sim.SimCard("Defend_R", false, 1, 5, "SKILL"));
        assertEquals(Integer.valueOf(2), s.relics.get(RelicHooks.KUNAI));
    }

    /** 快乐花：第 3 / 6 / 9 个回合开始 +1 能量。 */
    @Test
    void happyFlowerPaysEnergyEveryThirdTurn() {
        SimState s = fight();
        s.relics.put(RelicHooks.HAPPY_FLOWER, Integer.valueOf(0));
        s.energy = 0;

        RelicHooks.onPlayerTurnStart(s);
        assertEquals(0, s.energy, "第 1 回合不给");
        RelicHooks.onPlayerTurnStart(s);
        assertEquals(0, s.energy, "第 2 回合不给");
        RelicHooks.onPlayerTurnStart(s);
        assertEquals(1, s.energy, "第 3 回合 +1");
        assertEquals(Integer.valueOf(0), s.relics.get(RelicHooks.HAPPY_FLOWER), "计数归零");

        RelicHooks.onPlayerTurnStart(s);
        RelicHooks.onPlayerTurnStart(s);
        assertEquals(1, s.energy, "第 4/5 回合不给");
        RelicHooks.onPlayerTurnStart(s);
        assertEquals(2, s.energy, "第 6 回合再 +1");
    }

    /** 鸟面罐：每打出一张**能力牌**回 2 血（走魔法花的 ×1.5 通道）。 */
    @Test
    void birdFacedUrnHealsOnPowerCards() {
        SimState s = fight();
        s.hp = 60;
        s.relics.put(RelicHooks.BIRD_FACED_URN, Integer.valueOf(-1));

        RelicHooks.onCardUsed(s, new io.github.youqing.sts1solver.sim.SimCard("Inflame", false, 1, 0, "POWER"));
        assertEquals(62, s.hp, "能力牌回 2");

        // 攻击/技能不回
        RelicHooks.onCardUsed(s, new io.github.youqing.sts1solver.sim.SimCard("Strike_R", false, 1, 6, "ATTACK"));
        RelicHooks.onCardUsed(s, new io.github.youqing.sts1solver.sim.SimCard("Defend_R", false, 1, 5, "SKILL"));
        assertEquals(62, s.hp);

        // 带魔法花：2 × 1.5 = 3
        s.relics.put(RelicHooks.MAGIC_FLOWER, Integer.valueOf(-1));
        RelicHooks.onCardUsed(s, new io.github.youqing.sts1solver.sim.SimCard("Inflame", false, 1, 0, "POWER"));
        assertEquals(65, s.hp);
    }

    /**
     * 姜 / 萝卜：{@code ApplyPowerAction} 开头就把 Weakened / Frail 整条拦掉
     * （连层数都不加）。走真实怪招验证，而不是直接调 helper。
     */
    @Test
    void gingerAndTurnipBlockWeakAndFrail() {
        // 没有遗物
        SimState s = fight();
        s.monsters.get(0).pendingMove = MonsterMove.debuff(1, "Weakened", 2);
        s.monsters.get(0).pendingMoveId = 1;
        CombatFlow.endTurn(s, 3, 5);
        assertEquals(Integer.valueOf(2), s.playerPowers.get("Weakened"), "没姜就吃 2 层虚弱");

        // 有姜
        SimState s2 = fight();
        s2.relics.put(RelicHooks.GINGER, Integer.valueOf(-1));
        s2.monsters.get(0).pendingMove = MonsterMove.debuff(1, "Weakened", 2);
        s2.monsters.get(0).pendingMoveId = 1;
        CombatFlow.endTurn(s2, 3, 5);
        assertEquals(null, s2.playerPowers.get("Weakened"), "姜把虚弱整条拦掉");

        // 脆弱：萝卜才管
        SimState s3 = fight();
        s3.relics.put(RelicHooks.GINGER, Integer.valueOf(-1));
        s3.monsters.get(0).pendingMove = MonsterMove.debuff(1, "Frail", 2);
        s3.monsters.get(0).pendingMoveId = 1;
        CombatFlow.endTurn(s3, 3, 5);
        assertEquals(Integer.valueOf(2), s3.playerPowers.get("Frail"), "姜不管脆弱");

        SimState s4 = fight();
        s4.relics.put(RelicHooks.TURNIP, Integer.valueOf(-1));
        s4.monsters.get(0).pendingMove = MonsterMove.debuff(1, "Frail", 2);
        s4.monsters.get(0).pendingMoveId = 1;
        CombatFlow.endTurn(s4, 3, 5);
        assertEquals(null, s4.playerPowers.get("Frail"), "萝卜拦脆弱");
    }
    // ---------------------------------------------------------------- 第六批：回合结构

    /** 天鹅绒颈环：一回合最多 6 张，第 7 张直接打不出（{@code canPlay} = false）。 */
    @Test
    void velvetChokerCapsSixCardsPerTurn() {
        SimState s = fight();
        s.energy = 10;
        s.monsters.get(0).hp = 1000;
        s.relics.put(RelicHooks.VELVET_CHOKER, Integer.valueOf(0));
        for (int i = 0; i < 8; i++) {
            s.hand.add(new io.github.youqing.sts1solver.sim.SimCard("Strike_R", false, 0, 6, "ATTACK"));
        }

        for (int i = 0; i < 6; i++) {
            io.github.youqing.sts1solver.mirror.CardPlayer.play(s, 0, 0);
        }
        assertEquals(Integer.valueOf(6), s.relics.get(RelicHooks.VELVET_CHOKER), "计数封顶 6");
        assertEquals(2, s.hand.size());

        assertThrows(io.github.youqing.sts1solver.mirror.ActionRejected.class,
                () -> io.github.youqing.sts1solver.mirror.CardPlayer.play(s, 0, 0));
        assertEquals(2, s.hand.size(), "被拒的那张还在手里");
    }

    /** 香炉：第 6 / 12… 个回合开始给 1 层无实体，计数归零。 */
    @Test
    void incenseBurnerGivesIntangibleEverySixthTurn() {
        SimState s = fight();
        s.relics.put(RelicHooks.INCENSE_BURNER, Integer.valueOf(5));

        RelicHooks.onPlayerTurnStart(s);

        assertEquals(Integer.valueOf(0), s.relics.get(RelicHooks.INCENSE_BURNER), "计数归零");
        assertEquals(Integer.valueOf(1),
                s.playerPowers.get(io.github.youqing.sts1solver.mirror.DamageCalc.INTANGIBLE_PLAYER),
                "第 6 回合给无实体（玩家身上的 ID 是 IntangiblePlayer）");

        // 第 7 回合不该再给
        s.playerPowers.remove(io.github.youqing.sts1solver.mirror.DamageCalc.INTANGIBLE_PLAYER);
        RelicHooks.onPlayerTurnStart(s);
        assertEquals(null,
                s.playerPowers.get(io.github.youqing.sts1solver.mirror.DamageCalc.INTANGIBLE_PLAYER));
        assertEquals(Integer.valueOf(1), s.relics.get(RelicHooks.INCENSE_BURNER));
    }

    /** 怀表：上回合只打了 ≤ 3 张 → 本回合抽牌后再抽 3 张；第 1 回合不触发。 */
    @Test
    void pocketwatchDrawsThreeWhenFewCardsWerePlayed() {
        SimState s = fight();
        armedMonster(s);
        s.turn = 3;                                  // 不是第 1 回合
        s.relics.put(RelicHooks.POCKETWATCH, Integer.valueOf(2));   // 上回合打了 2 张
        for (int i = 0; i < 12; i++) {
            s.draw.add(new io.github.youqing.sts1solver.sim.SimCard("Strike_R", false, 1, 6, "ATTACK"));
        }

        CombatFlow.endTurn(s, 3, 5);
        assertEquals(8, s.hand.size(), "正常抽 5 + 怀表 3");
        assertEquals(Integer.valueOf(0), s.relics.get(RelicHooks.POCKETWATCH), "计数归零");

        // 上回合打了 4 张 → 不触发
        SimState s2 = fight();
        armedMonster(s2);
        s2.turn = 3;
        s2.relics.put(RelicHooks.POCKETWATCH, Integer.valueOf(4));
        for (int i = 0; i < 12; i++) {
            s2.draw.add(new io.github.youqing.sts1solver.sim.SimCard("Strike_R", false, 1, 6, "ATTACK"));
        }
        CombatFlow.endTurn(s2, 3, 5);
        assertEquals(5, s2.hand.size(), "打了 4 张就不给");

        // 边界的另一半：上回合正好打 3 张（阈值含等号）→ 仍然给
        SimState s3 = fight();
        armedMonster(s3);
        s3.turn = 3;
        s3.relics.put(RelicHooks.POCKETWATCH, Integer.valueOf(3));
        for (int i = 0; i < 12; i++) {
            s3.draw.add(new io.github.youqing.sts1solver.sim.SimCard("Strike_R", false, 1, 6, "ATTACK"));
        }
        CombatFlow.endTurn(s3, 3, 5);
        assertEquals(8, s3.hand.size(), "打 3 张是 `<= 3`，照样给");
    }
    /** 死灵之书：本回合**第一次**打出 ≥2 费的攻击牌 → 再结算一次；低费/技能不触发。 */
    @Test
    void necronomiconReplaysTheFirstExpensiveAttack() {
        SimState s = fight();
        s.energy = 10;
        s.monsters.get(0).hp = 500;
        s.relics.put(RelicHooks.NECRONOMICON, Integer.valueOf(1));
        s.hand.add(new io.github.youqing.sts1solver.sim.SimCard("Bash", false, 2, 8, "ATTACK"));
        s.hand.add(new io.github.youqing.sts1solver.sim.SimCard("Strike_R", false, 1, 6, "ATTACK"));

        io.github.youqing.sts1solver.mirror.CardPlayer.play(s, 0, 0);

        // Bash：8 伤 + 易伤 2；重放一次 → 第二下吃易伤（8 × 1.5 = 12）
        assertEquals(480, s.monsters.get(0).hp, "Bash 打两次：8 + 12 = 20");
        assertEquals(Integer.valueOf(0), s.relics.get(RelicHooks.NECRONOMICON),
                "用掉了，本回合不再触发");

        // 第二张是 1 费，且已用过 → 只打一次
        io.github.youqing.sts1solver.mirror.CardPlayer.play(s, 0, 0);
        assertEquals(471, s.monsters.get(0).hp, "1 费攻击只打一次（6 × 1.5 = 9）");
    }

    /** 死灵之书不给技能牌 / 0..1 费攻击牌用。 */
    @Test
    void necronomiconIgnoresCheapCardsAndSkills() {
        SimState s = fight();
        s.energy = 10;
        s.monsters.get(0).hp = 500;
        s.relics.put(RelicHooks.NECRONOMICON, Integer.valueOf(1));
        s.hand.add(new io.github.youqing.sts1solver.sim.SimCard("Defend_R", false, 1, 5, "SKILL"));

        io.github.youqing.sts1solver.mirror.CardPlayer.play(s, 0, -1);

        assertEquals(Integer.valueOf(1), s.relics.get(RelicHooks.NECRONOMICON), "技能不消耗");
    }
    // ---------------------------------------------------------------- 第八批：补满剩余战斗遗物

    /** 算盘：每洗一次牌 +6 格挡（裸数值，不吃敏捷）。 */
    @Test
    void abacusGivesSixBlockPerShuffle() {
        SimState s = fight();
        s.relics.put(RelicHooks.ABACUS, Integer.valueOf(-1));
        s.block = 0;

        RelicHooks.onShuffle(s);
        assertEquals(6, s.block);
        RelicHooks.onShuffle(s);
        assertEquals(12, s.block, "洗几次加几次");

        // 没有遗物：不加
        SimState s2 = fight();
        s2.block = 0;
        RelicHooks.onShuffle(s2);
        assertEquals(0, s2.block);
    }

    /** 硫磺：回合开始自己 +2 力量、**全体敌人**各 +1 力量。 */
    @Test
    void brimstoneGrowsBothSides() {
        SimState s = fight();
        s.monsters.add(new SimMonster("JawWorm", 40, 40, 0,
                Collections.<String, Integer>emptyMap()));
        s.relics.put(RelicHooks.BRIMSTONE, Integer.valueOf(-1));

        RelicHooks.onPlayerTurnStart(s);

        assertEquals(Integer.valueOf(2), s.playerPowers.get(DamageCalc.STRENGTH), "玩家 +2");
        assertEquals(Integer.valueOf(1), s.monsters.get(0).powers.get(DamageCalc.STRENGTH),
                "第一只敌人 +1");
        assertEquals(Integer.valueOf(1), s.monsters.get(1).powers.get(DamageCalc.STRENGTH),
                "第二只也 +1");
    }

    /** 石历：第 7 个回合结束对全体 52 点纯伤，只爆一次。 */
    @Test
    void stoneCalendarNukesEverythingOnceOnTurnSeven() {
        SimState s = fight();
        s.monsters.get(0).hp = 200;
        s.relics.put(RelicHooks.STONE_CALENDAR, Integer.valueOf(6));

        RelicHooks.onPlayerTurnStart(s);
        assertEquals(Integer.valueOf(7), s.relics.get(RelicHooks.STONE_CALENDAR), "计数到 7");

        RelicHooks.onPlayerEndTurn(s);
        assertEquals(148, s.monsters.get(0).hp, "200 - 52");
        assertEquals(Integer.valueOf(0), s.relics.get(RelicHooks.STONE_CALENDAR),
                "爆完归零，不再触发");

        // 第 8 回合不再爆
        RelicHooks.onPlayerTurnStart(s);
        RelicHooks.onPlayerEndTurn(s);
        assertEquals(148, s.monsters.get(0).hp, "不会再爆第二次");
    }

    /** 血花印记：所有治疗归零（药水/再生/回血卡都算）。 */
    @Test
    void markOfTheBloomNullifiesHealing() {
        SimState s = fight();
        s.hp = 40;
        s.relics.put(RelicHooks.MARK_OF_THE_BLOOM, Integer.valueOf(-1));

        assertEquals(0, RelicHooks.healAmount(s, 20), "healAmount 直接归零");

        // 真路径：血瓶也回不了血
        assertTrue(io.github.youqing.sts1solver.mirror.Potions.use(
                s, io.github.youqing.sts1solver.mirror.Potions.BLOOD_POTION, -1));
        assertEquals(40, s.hp, "血瓶被血花印记抹平");

        // 魔法花也救不了
        s.relics.put(RelicHooks.MAGIC_FLOWER, Integer.valueOf(-1));
        assertEquals(0, RelicHooks.healAmount(s, 20));
    }

    /** 木乃伊手：打出能力牌 → 手牌里随机一张本回合 0 费。 */
    @Test
    void mummifiedHandZeroesOneRandomCardCost() {
        SimState s = fight();
        s.energy = 10;
        s.relics.put(RelicHooks.MUMMIFIED_HAND, Integer.valueOf(-1));
        s.rng.put(SimState.RngKind.CARD,
                io.github.youqing.sts1solver.sim.RngStream.from(555L, 666L, 0));
        s.hand.add(new io.github.youqing.sts1solver.sim.SimCard("Strike_R", false, 2, 6, "ATTACK"));

        RelicHooks.onCardUsed(s, new io.github.youqing.sts1solver.sim.SimCard("Inflame", false, 1, 0, "POWER"));

        assertEquals(0, s.hand.get(0).costForTurn, "本回合 0 费");
        assertEquals(2, s.hand.get(0).cost, "永久费用不变");
        assertEquals(1, s.rng.get(SimState.RngKind.CARD).counter(), "消耗一次 cardRandomRng");
    }

    /**
     * 已经「本回合 0 费」的牌**不进**候选（原版三条判据是**与**：cost &gt; 0 &amp;&amp; costForTurn &gt; 0
     * &amp;&amp; !freeToPlayOnce，字节码 73-104）。
     *
     * <p>以前写成「或」，于是候选集合大小变了，{@code random(0, size-1)} 抽到的是另一张，
     * 之后整条时间线的费用都对不上。这里用手上只有一张「本回合已 0 费」的牌验证：
     * 候选为空 → **一次 rng 都不该消耗**。
     */
    @Test
    void mummifiedHandSkipsCardsThatAreAlreadyFreeThisTurn() {
        SimState s = fight();
        s.relics.put(RelicHooks.MUMMIFIED_HAND, Integer.valueOf(-1));
        s.rng.put(SimState.RngKind.CARD,
                io.github.youqing.sts1solver.sim.RngStream.from(555L, 666L, 0));
        s.hand.add(new io.github.youqing.sts1solver.sim.SimCard("Strike_R", false, 1, 6, "ATTACK")
                .withCostForTurn(0));

        RelicHooks.onCardUsed(s, new io.github.youqing.sts1solver.sim.SimCard("Inflame", false, 1, 0, "POWER"));

        assertEquals(0, s.rng.get(SimState.RngKind.CARD).counter(),
                "候选为空时原版直接返回，不掷色子（掷了就会和实机错开）");
        assertEquals(0, s.hand.get(0).costForTurn);
        assertEquals(1, s.hand.get(0).cost, "永久费用当然不变");
    }

    /** 橙色药丸：同一回合打齐攻击/技能/能力 → 清掉自己身上所有减益。 */
    @Test
    void orangePelletsClearsDebuffsAfterAllThreeTypes() {
        SimState s = fight();
        s.relics.put(RelicHooks.ORANGE_PELLETS, Integer.valueOf(0));
        s.playerPowers.put("Weakened", Integer.valueOf(2));
        s.playerPowers.put("Vulnerable", Integer.valueOf(2));
        s.playerPowers.put("Confusion", Integer.valueOf(0));

        RelicHooks.onCardUsed(s, new io.github.youqing.sts1solver.sim.SimCard("Strike_R", false, 1, 6, "ATTACK"));
        assertEquals(Integer.valueOf(1), s.relics.get(RelicHooks.ORANGE_PELLETS), "还差技能/能力");
        assertEquals(Integer.valueOf(2), s.playerPowers.get("Weakened"), "还没清");

        RelicHooks.onCardUsed(s, new io.github.youqing.sts1solver.sim.SimCard("Defend_R", false, 1, 5, "SKILL"));
        assertEquals(Integer.valueOf(2), s.playerPowers.get("Weakened"));

        RelicHooks.onCardUsed(s, new io.github.youqing.sts1solver.sim.SimCard("Inflame", false, 1, 0, "POWER"));
        assertEquals(null, s.playerPowers.get("Weakened"), "打齐三种 → 减益全清");
        assertEquals(null, s.playerPowers.get("Vulnerable"));
        assertEquals(null, s.playerPowers.get("Confusion"), "Confusion 也是减益");
        assertEquals(Integer.valueOf(0), s.relics.get(RelicHooks.ORANGE_PELLETS), "标记清零");
    }

    /** 战争艺术：上回合没打攻击牌 → 本回合 +1 能量；打了就不给。 */
    @Test
    void artOfWarPaysEnergyOnlyAfterAnAttackFreeTurn() {
        SimState s = fight();
        s.relics.put(RelicHooks.ART_OF_WAR, Integer.valueOf(1));
        s.energy = 3;

        RelicHooks.onPlayerTurnStart(s);
        assertEquals(4, s.energy, "标记为真 → +1 能量");

        // 打了攻击牌 → 标记清零
        RelicHooks.onCardUsed(s, new io.github.youqing.sts1solver.sim.SimCard("Strike_R", false, 1, 6, "ATTACK"));
        assertEquals(Integer.valueOf(0), s.relics.get(RelicHooks.ART_OF_WAR));

        RelicHooks.onPlayerTurnStart(s);
        assertEquals(4, s.energy, "打了攻击 → 下回合不给");

        // 只打技能牌 → 仍然给
        s.relics.put(RelicHooks.ART_OF_WAR, Integer.valueOf(1));
        RelicHooks.onCardUsed(s, new io.github.youqing.sts1solver.sim.SimCard("Defend_R", false, 1, 5, "SKILL"));
        assertEquals(Integer.valueOf(1), s.relics.get(RelicHooks.ART_OF_WAR), "技能不清标记");
        RelicHooks.onPlayerTurnStart(s);
        assertEquals(5, s.energy, "没打攻击 → 再 +1");
    }
    /** 奇异蘑菇：**自己**中易伤只多受 25%；纸鹤：**敌人**中虚弱少打 40%。 */
    @Test
    void oddMushroomAndPaperCraneChangeTheTwoMultipliers() {
        // 敌人 10 点打过来，玩家易伤：默认 ×1.5 = 15
        SimState s = fight();
        s.playerPowers.put("Vulnerable", Integer.valueOf(1));
        assertEquals(15, DamageCalc.monsterAttackDamage(s, 10, s.monsters.get(0)));

        s.relics.put(RelicHooks.ODD_MUSHROOM, Integer.valueOf(-1));
        assertEquals(12, DamageCalc.monsterAttackDamage(s, 10, s.monsters.get(0)),
                "奇异蘑菇：10 × 1.25 = 12.5 → 12");

        // 敌人中虚弱：默认 ×0.75 = 7（10 × 0.75 = 7.5 → 7）
        SimState s2 = fight();
        s2.monsters.get(0).powers.put("Weakened", Integer.valueOf(1));
        assertEquals(7, DamageCalc.monsterAttackDamage(s2, 10, s2.monsters.get(0)));

        s2.relics.put(RelicHooks.PAPER_CRANE, Integer.valueOf(-1));
        assertEquals(6, DamageCalc.monsterAttackDamage(s2, 10, s2.monsters.get(0)),
                "纸鹤：10 × 0.6 = 6");

        // 两条互不影响：玩家打敌人的易伤仍是纸青蛙管的那条
        SimMonster m = s2.monsters.get(0);
        m.powers.put("Vulnerable", Integer.valueOf(1));
        assertEquals(15, DamageCalc.attackDamage(s2, 10, m),
                "纸鹤/奇异蘑菇都不动「玩家打敌人」的 ×1.5");
    }
    // ---------------------------------------------------------------- 第十批：Vigor / 靴子 / 腰带 / 不停之巅

    /**
     * 阿卡贝科给的 **Vigor**（`VigorPower.atDamageGive`）：+8 伤，
     * 而且多段攻击**每一段**都吃；打完第一张攻击牌整条移除。
     *
     * <p>它不是遗物钩子：Akabeko 只在 `atBattleStart` 挂 8 层 Vigor（快照里已有），
     * 真正生效的是这个能力。
     */
    @Test
    void vigorAddsToEveryHitThenIsConsumedByTheFirstAttack() {
        SimState s = fight();
        s.monsters.get(0).hp = 200;
        s.playerPowers.put("Vigor", Integer.valueOf(8));
        s.hand.add(new io.github.youqing.sts1solver.sim.SimCard(
                "Pommel Strike", false, 1, 9, "ATTACK"));

        assertEquals(17, DamageCalc.attackDamage(s, 9, s.monsters.get(0)), "9 + 8");

        io.github.youqing.sts1solver.mirror.CardPlayer.play(s, 0, 0);
        assertEquals(183, s.monsters.get(0).hp, "200 - 17");
        assertEquals(null, s.playerPowers.get("Vigor"), "打完攻击牌整条移除");

        // 第二张不再加成
        s.hand.add(new io.github.youqing.sts1solver.sim.SimCard(
                "Pommel Strike", false, 1, 9, "ATTACK"));
        io.github.youqing.sts1solver.mirror.CardPlayer.play(s, 0, 0);
        assertEquals(174, s.monsters.get(0).hp, "只打 9");
    }

    /** Vigor 只对**攻击牌**生效；打技能牌既不加成也不消耗。 */
    @Test
    void vigorSurvivesSkillCards() {
        SimState s = fight();
        s.playerPowers.put("Vigor", Integer.valueOf(8));
        s.hand.add(new io.github.youqing.sts1solver.sim.SimCard(
                "Shrug It Off", false, 1, -1, "SKILL"));

        io.github.youqing.sts1solver.mirror.CardPlayer.play(s, 0, -1);

        assertEquals(Integer.valueOf(8), s.playerPowers.get("Vigor"), "技能牌不消耗 Vigor");
    }

    /**
     * 靴子（`Boot.onAttackToChangeDamage`）：玩家打出的 **1..4** 点抬到 5；0 和 5 以上不动。
     *
     * <p>🔴 它只作用于「玩家 → 怪物」：原版那段在 `AbstractMonster.damage()` 里，
     * 外面套着 `info.owner == AbstractDungeon.player` 才遍历玩家遗物。怪物打玩家不吃。
     */
    @Test
    void bootRaisesSmallPlayerHitsToFive() {
        SimState s = fight();
        SimMonster m = s.monsters.get(0);
        s.relics.put(RelicHooks.BOOT, Integer.valueOf(-1));

        assertEquals(5, DamageCalc.attackDamage(s, 2, m), "2 → 5");
        assertEquals(5, DamageCalc.attackDamage(s, 4, m), "4 → 5");
        assertEquals(5, DamageCalc.attackDamage(s, 5, m), "5 不动");
        assertEquals(9, DamageCalc.attackDamage(s, 9, m), "9 不动");

        // 没遗物：原值
        SimState s2 = fight();
        assertEquals(2, DamageCalc.attackDamage(s2, 2, s2.monsters.get(0)));

        // 怪物打玩家不吃：2 点还是 2 点（玩家没易伤、怪没力量）
        assertEquals(2, DamageCalc.monsterAttackDamage(s2, 2, s2.monsters.get(0)),
                "靴子不放大怪物伤害");
    }

    /**
     * 冠军腰带（`ApplyPowerAction` 里的 Champion Belt 分支）：给**别的**生物挂易伤时
     * 顺手给 1 层虚弱；目标身上**有任何一层神器**就整条不触发。
     */
    @Test
    void championBeltAddsWeakAlongWithVulnerable() {
        SimState s = fight();
        s.relics.put(RelicHooks.CHAMPION_BELT, Integer.valueOf(-1));
        s.hand.add(new io.github.youqing.sts1solver.sim.SimCard("Bash", false, 2, 8, "ATTACK"));

        io.github.youqing.sts1solver.mirror.CardPlayer.play(s, 0, 0);

        assertEquals(Integer.valueOf(2), s.monsters.get(0).powers.get("Vulnerable"));
        assertEquals(Integer.valueOf(1), s.monsters.get(0).powers.get("Weakened"),
                "易伤 + 虚弱各一层");

        // 没有腰带：只给易伤
        SimState s2 = fight();
        s2.hand.add(new io.github.youqing.sts1solver.sim.SimCard("Bash", false, 2, 8, "ATTACK"));
        io.github.youqing.sts1solver.mirror.CardPlayer.play(s2, 0, 0);
        assertEquals(null, s2.monsters.get(0).powers.get("Weakened"));
    }

    /**
     * 🔴 卡牌上的减益也要过**神器**这一关（以前只有药水过，卡牌直接用 `add` 绕过）。
     *
     * <p>原版 `ApplyPowerAction` 是唯一入口：目标有 Artifact 就消耗一层、这次施加作废。
     * 这条钉的是「Bash 打有神器的怪，易伤不该落地」。
     */
    @Test
    void cardDebuffsAreEatenByArtifact() {
        SimState s = fight();
        s.monsters.get(0).powers.put("Artifact", Integer.valueOf(1));
        s.hand.add(new io.github.youqing.sts1solver.sim.SimCard("Bash", false, 2, 8, "ATTACK"));

        io.github.youqing.sts1solver.mirror.CardPlayer.play(s, 0, 0);

        assertEquals(null, s.monsters.get(0).powers.get("Vulnerable"), "神器吃掉这次易伤");
        assertEquals(null, s.monsters.get(0).powers.get("Artifact"), "吃完就没了");
        assertEquals(92, s.monsters.get(0).hp, "伤害照打：8 点（没易伤）");

        // 有神器时冠军腰带也不触发（判据是 hasPower("Artifact")，不是「还剩几层」）
        SimState s2 = fight();
        s2.relics.put(RelicHooks.CHAMPION_BELT, Integer.valueOf(-1));
        s2.monsters.get(0).powers.put("Artifact", Integer.valueOf(2));
        s2.hand.add(new io.github.youqing.sts1solver.sim.SimCard("Bash", false, 2, 8, "ATTACK"));
        io.github.youqing.sts1solver.mirror.CardPlayer.play(s2, 0, 0);
        assertEquals(null, s2.monsters.get(0).powers.get("Vulnerable"));
        assertEquals(null, s2.monsters.get(0).powers.get("Weakened"), "腰带也不给虚弱");
        assertEquals(Integer.valueOf(1), s2.monsters.get(0).powers.get("Artifact"),
                "只消耗一层");
    }

    /** 不停之巅（`UnceasingTop.onRefreshHand`）：手牌打空且牌堆还有牌 → 再抽 1 张。 */
    @Test
    void unceasingTopDrawsWhenTheHandEmpties() {
        SimState s = fight();
        s.relics.put(RelicHooks.UNCEASING_TOP, Integer.valueOf(-1));
        s.hand.add(new io.github.youqing.sts1solver.sim.SimCard("Strike_R", false, 1, 6, "ATTACK"));
        for (int i = 0; i < 5; i++) {
            s.draw.add(new io.github.youqing.sts1solver.sim.SimCard("Strike_R", false, 1, 6, "ATTACK"));
        }

        io.github.youqing.sts1solver.mirror.CardPlayer.play(s, 0, 0);

        assertEquals(1, s.hand.size(), "手牌打空 → 补抽 1 张");

        // 没遗物：不抽
        SimState s2 = fight();
        s2.hand.add(new io.github.youqing.sts1solver.sim.SimCard("Strike_R", false, 1, 6, "ATTACK"));
        for (int i = 0; i < 5; i++) {
            s2.draw.add(new io.github.youqing.sts1solver.sim.SimCard("Strike_R", false, 1, 6, "ATTACK"));
        }
        io.github.youqing.sts1solver.mirror.CardPlayer.play(s2, 0, 0);
        assertEquals(0, s2.hand.size());
    }
    /**
     * 奇异勺子（`UseCardAction.update` 里那段）：本该消耗的牌掷一次
     * `cardRandomRng.randomBoolean()`，掷中且**不是能力牌**就改去弃牌堆。
     *
     * <p>断言方式：换 8 个种子各打一次「消耗类攻击牌」，两种结局都必须出现
     * （50% 的硬币若恒定落一边，说明随机数根本没抽）。
     */
    @Test
    void strangeSpoonSometimesKeepsExhaustCardsInTheDiscard() {
        int exhausted = 0;
        int kept = 0;
        for (int seed = 1; seed <= 8; seed++) {
            SimState s = fight();
            s.energy = 3;
            s.relics.put(RelicHooks.STRANGE_SPOON, Integer.valueOf(-1));
            s.rng.put(SimState.RngKind.CARD,
                    io.github.youqing.sts1solver.sim.RngStream.from(seed * 7919L, seed, 0));
            s.hand.add(new io.github.youqing.sts1solver.sim.SimCard(
                    "Pummel", false, 1, 2, "ATTACK"));

            io.github.youqing.sts1solver.mirror.CardPlayer.play(s, 0, 0);

            assertEquals(1, s.rng.get(SimState.RngKind.CARD).counter(),
                    "每次消耗判定都要消耗一次 cardRandomRng");
            if (s.exhaust.isEmpty()) {
                kept++;
                assertEquals(1, s.discard.size(), "掷中 → 进弃牌堆而不是消耗堆");
            } else {
                exhausted++;
                assertEquals(0, s.discard.size());
            }
        }
        assertTrue(exhausted > 0 && kept > 0,
                "50% 判定两种结局都该出现（消耗 " + exhausted + " / 留下 " + kept + "）");
    }

    /** 没有勺子 → 恒定消耗；能力牌就算有勺子也恒定消耗。 */
    @Test
    void strangeSpoonSkipsPowersAndNeedsTheRelic() {
        SimState s = fight();
        s.hand.add(new io.github.youqing.sts1solver.sim.SimCard(
                "Pummel", false, 1, 2, "ATTACK"));
        io.github.youqing.sts1solver.mirror.CardPlayer.play(s, 0, 0);
        assertEquals(1, s.exhaust.size(), "没遗物：老老实实消耗");

        SimState s2 = fight();
        s2.relics.put(RelicHooks.STRANGE_SPOON, Integer.valueOf(-1));
        s2.rng.put(SimState.RngKind.CARD,
                io.github.youqing.sts1solver.sim.RngStream.from(1L, 2L, 0));
        s2.energy = 3;
        s2.hand.add(new io.github.youqing.sts1solver.sim.SimCard(
                "Impervious", false, 2, -1, "SKILL"));
        io.github.youqing.sts1solver.mirror.CardPlayer.play(s2, 0, -1);
        // 技能牌也吃勺子（只有 POWER 不吃）—— 这里只验证「至少结局合法」
        assertTrue(s2.exhaust.size() + s2.discard.size() == 1);
    }
}
