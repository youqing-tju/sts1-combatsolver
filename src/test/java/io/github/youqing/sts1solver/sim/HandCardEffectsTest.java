package io.github.youqing.sts1solver.sim;

import org.junit.jupiter.api.Test;

import java.util.Collections;

import io.github.youqing.sts1solver.mirror.DamageCalc;
import io.github.youqing.sts1solver.mirror.hooks.RelicHooks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 手牌的在手被动（{@code AbstractPlayer.applyEndOfTurnPreEndTurnCards} 那一段）。
 *
 * <p>这些牌的数值全在各自的 {@code use()} 里，靠 {@code CardQueueItem(card, true)}
 * 「按触发方式打一遍」才生效 —— 所以**主动打出**（蓝蜡烛/医疗包）时什么都不发生。
 * 数值与伤害类型全部按 {@code com.megacrit.cardcrawl.cards.status|curses.*} 的字节码核实，
 * 见 {@code mechanics-reference} §33.53。
 */
class HandCardEffectsTest {

    private static SimState fight() {
        SimState s = new SimState();
        s.hp = 80;
        s.maxHp = 80;
        s.energy = 3;
        SimMonster m = new SimMonster("TestMonster", 200, 200, 0,
                Collections.<String, Integer>emptyMap());
        m.pendingMove = MonsterMove.block(1, 0);
        m.pendingMoveId = 1;
        s.monsters.add(m);
        return s;
    }

    private static SimCard card(String id, int cost, String type) {
        return new SimCard(id, false, cost, -1, type);
    }

    /** 燃烧：回合结束在手上 → 2 点 THORNS 伤害（吃格挡）。 */
    @Test
    void burnHitsForTwoAtEndOfTurn() {
        SimState s = fight();
        s.hand.add(card("Burn", -2, "STATUS"));

        CombatFlow.endTurn(s, 3, 5);

        assertEquals(78, s.hp, "80 - 2");

        // 有格挡就先吃格挡
        SimState s2 = fight();
        s2.hand.add(card("Burn", -2, "STATUS"));
        s2.block = 5;
        CombatFlow.endTurn(s2, 3, 5);
        // 注意：endTurn 返回时格挡已经被 ⑤ 清掉了，所以只能看「血掉没掉」
        assertEquals(80, s2.hp, "2 点被格挡吃掉，血不掉");
    }

    /** 悔恨：掉血 = 触发那一刻的**手牌数**，且无视图格挡。 */
    @Test
    void regretCostsOneHpPerCardInHand() {
        SimState s = fight();
        s.hand.add(card("Regret", -2, "CURSE"));
        s.hand.add(card("Strike_R", 1, "ATTACK"));
        s.hand.add(card("Defend_R", 1, "SKILL"));
        s.block = 20;

        CombatFlow.endTurn(s, 3, 5);

        // 20 点格挡完全没吸收（HP_LOSS 跳过格挡），所以血实实在在掉了 3 点
        assertEquals(77, s.hp, "3 张手牌 → 掉 3 点血，20 点格挡一点用都没有");
    }

    /** 疑虑 / 羞愧：各 +1 层虚弱 / 脆弱；姜和萝卜能拦掉。 */
    @Test
    void doubtAndShameApplyWeakAndFrail() {
        SimState s = fight();
        s.hand.add(card("Doubt", -2, "CURSE"));
        s.hand.add(card("Shame", -2, "CURSE"));

        CombatFlow.endTurn(s, 3, 5);

        assertEquals(Integer.valueOf(1), s.playerPowers.get("Weakened"));
        assertEquals(Integer.valueOf(1), s.playerPowers.get("Frail"));

        SimState s2 = fight();
        s2.hand.add(card("Doubt", -2, "CURSE"));
        s2.hand.add(card("Shame", -2, "CURSE"));
        s2.relics.put(RelicHooks.GINGER, Integer.valueOf(-1));
        s2.relics.put(RelicHooks.TURNIP, Integer.valueOf(-1));
        CombatFlow.endTurn(s2, 3, 5);
        assertEquals(null, s2.playerPowers.get("Weakened"), "姜拦虚弱");
        assertEquals(null, s2.playerPowers.get("Frail"), "萝卜拦脆弱");
    }

    /**
     * 虚体：回合结束在手上**直接消耗**，不进弃牌堆。
     *
     * <p>带符文金字塔也一样（原版的虚体处理不在 {@code DiscardAtEndOfTurnAction} 里）。
     */
    @Test
    void etherealCardsExhaustInsteadOfBeingDiscarded() {
        SimState s = fight();
        s.hand.add(card("Dazed", -2, "STATUS"));

        CombatFlow.endTurn(s, 3, 5);

        assertEquals(1, s.exhaust.size(), "Dazed 进消耗堆");
        for (SimCard c : s.discard) {
            assertFalse("Dazed".equals(c.id), "弃牌堆里不该有 Dazed");
        }
        for (SimCard c : s.hand) {
            assertFalse("Dazed".equals(c.id), "手上也不该留着");
        }

        // 符文金字塔：手牌保留，但虚体照样消耗
        SimState s2 = fight();
        s2.relics.put(RelicHooks.RUNIC_PYRAMID, Integer.valueOf(-1));
        s2.hand.add(card("Dazed", -2, "STATUS"));
        s2.hand.add(card("Strike_R", 1, "ATTACK"));
        CombatFlow.endTurn(s2, 3, 5);
        assertEquals(1, s2.exhaust.size(), "金字塔救不了虚体");
        boolean strikeKept = false;
        for (SimCard c : s2.hand) {
            if ("Strike_R".equals(c.id)) {
                strikeKept = true;
            }
        }
        assertTrue(strikeKept, "普通牌还是留在手上");
    }

    /** 虚空：抽到时 -1 能量（夹在 0）。 */
    @Test
    void voidCostsOneEnergyWhenDrawn() {
        SimState s = fight();
        s.draw.add(card("Void", -2, "STATUS"));

        CombatFlow.drawCards(s, 1);

        assertEquals(2, s.energy, "3 - 1 = 2");
        assertEquals(1, s.hand.size(), "牌还是进了手牌");

        // 能量 0 时不会变负
        SimState s2 = fight();
        s2.energy = 0;
        s2.draw.add(card("Void", -2, "STATUS"));
        CombatFlow.drawCards(s2, 1);
        assertEquals(0, s2.energy);
    }

    /** 痛楚：手里有 Pain 时，每打出**别的**一张牌就掉 1 血（无视图格挡）。 */
    @Test
    void painHurtsWheneverAnotherCardIsPlayed() {
        SimState s = fight();
        s.hand.add(card("Pain", -2, "CURSE"));
        s.hand.add(card("Strike_R", 0, "ATTACK"));
        s.block = 10;

        io.github.youqing.sts1solver.mirror.CardPlayer.play(s, 1, 0);

        assertEquals(79, s.hp, "掉 1 点血");
        assertEquals(10, s.block, "格挡没动");

        // 没有 Pain 就不掉
        SimState s2 = fight();
        s2.hand.add(card("Strike_R", 0, "ATTACK"));
        io.github.youqing.sts1solver.mirror.CardPlayer.play(s2, 0, 0);
        assertEquals(80, s2.hp);
    }

    /** 虚体判定按 ID：Burn / Wound / Decay 这些**不是**虚体。 */
    @Test
    void etherealSetMatchesTheBytecode() {
        assertTrue(card("Dazed", -2, "STATUS").isEthereal());
        assertTrue(card("Void", -2, "STATUS").isEthereal());
        assertTrue(card("AscendersBane", -2, "CURSE").isEthereal());
        assertTrue(card("Clumsy", -2, "CURSE").isEthereal());
        assertFalse(card("Burn", -2, "STATUS").isEthereal());
        assertFalse(card("Wound", -2, "STATUS").isEthereal());
        assertFalse(card("Decay", -2, "CURSE").isEthereal());
    }

    /** 主动打出 Burn（医疗包）**什么都不发生** —— 它的 2 点伤害只在「触发方式」下才生效。 */
    @Test
    void playingBurnProactivelyDoesNothing() {
        SimState s = fight();
        s.hand.add(card("Burn", -2, "STATUS"));
        s.relics.put(RelicHooks.MEDICAL_KIT, Integer.valueOf(-1));

        io.github.youqing.sts1solver.mirror.CardPlayer.play(s, 0, -1);

        assertEquals(80, s.hp, "不掉血");
        assertEquals(1, s.exhaust.size(), "只是被消耗掉");
        assertEquals(3, s.energy);
    }

    /** 顺手确认 DamageCalc 没被这些改动影响：普通攻击仍是 6 点。 */
    @Test
    void plainAttackStillDealsSix() {
        SimState s = fight();
        assertEquals(6, DamageCalc.attackDamage(s, 6, s.monsters.get(0)));
    }
}
