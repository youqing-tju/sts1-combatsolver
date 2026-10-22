package io.github.youqing.sts1solver.mirror;

import org.junit.jupiter.api.Test;

import java.util.Collections;

import io.github.youqing.sts1solver.mirror.hooks.Hooks;
import io.github.youqing.sts1solver.sim.RngStream;
import io.github.youqing.sts1solver.sim.SimCard;
import io.github.youqing.sts1solver.sim.SimMonster;
import io.github.youqing.sts1solver.sim.SimState;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 对拍日志里逐条定性的行为差异，全部按字节码对齐。
 *
 * <p>每条用例都写清「原版是哪一行字节码决定的」，以后改动若破坏语义能立刻定位。
 */
class MirrorBehaviourTest {

    private static SimState fight() {
        SimState s = new SimState();
        s.hp = 80;
        s.maxHp = 80;
        s.energy = 3;
        s.monsters.add(new SimMonster("AcidSlime_S", 200, 200, 0,
                Collections.<String, Integer>emptyMap()));
        s.rng.put(SimState.RngKind.CARD, RngStream.from(1L, 2L, 0));
        s.rng.put(SimState.RngKind.SHUFFLE, RngStream.from(7L, 8L, 0));
        return s;
    }

    private static int cardCounter(SimState s) {
        RngStream rng = s.rng.get(SimState.RngKind.CARD);
        return rng == null ? -1 : rng.counter();
    }

    // -------------------------------------------------------------- 深呼吸 / 结茧 / 发现 / 净化

    /** Deep Breath：弃牌堆洗回抽牌堆（1 次 randomLong）+ 洗抽牌堆（1 次）+ 抽 1 张。 */
    @Test
    void deepBreathShufflesDiscardIntoDrawThenDraws() {
        SimState s = fight();
        s.draw.clear();
        s.hand.add(new SimCard("Deep Breath", false, 0));
        for (int i = 0; i < 3; i++) {
            s.discard.add(new SimCard("Defend_R", false, 1));
        }
        int before = shuffleCounter(s);

        CardPlayer.play(s, 0, -1);

        assertEquals(2, shuffleCounter(s) - before, "EmptyDeckShuffleAction + ShuffleAction = 2 次");
        assertEquals(0, s.discard.size() - 1, "弃牌堆只剩深呼吸自己");
        assertEquals(1, s.hand.size(), "抽到的 1 张");
        assertEquals(2, s.draw.size(), "3 张洗回去，抽走 1 张");
    }

    /** 结茧：3 张随机技能牌进抽牌堆、0 费、自身消耗。 */
    @Test
    void chrysalisPutsRandomSkillsInDrawPile() {
        SimState s = fight();
        s.hand.add(new SimCard("Chrysalis", false, 2));

        CardPlayer.play(s, 0, -1);

        assertEquals(3, s.draw.size());
        for (SimCard c : s.draw) {
            assertEquals(0, c.cost, "本场 0 费");
            assertEquals("SKILL", c.type, "只能是技能牌");
        }
        assertEquals(1, s.exhaust.size(), "结茧自身消耗");
    }

    /** 发现：3 选 1，进手牌的那张本回合 0 费。 */
    @Test
    void discoveryAddsTheChosenCardToHand() {
        SimState s = fight();
        s.hand.add(new SimCard("Discovery", false, 1));

        CardPlayer.play(s, 0, -1, 0);

        assertEquals(1, s.hand.size(), "只进手牌 1 张");
        assertEquals(0, s.hand.get(0).cost, "本回合 0 费");
        assertEquals(1, s.exhaust.size(), "发现自身消耗");
    }

    /** 净化：按传入的下标组消耗多张。 */
    @Test
    void purityExhaustsTheChosenCards() {
        SimState s = fight();
        s.hand.add(new SimCard("Strike_R", false, 1));
        s.hand.add(new SimCard("Defend_R", false, 1));
        s.hand.add(new SimCard("Bash", false, 2));
        s.hand.add(new SimCard("Purity", false, 0));

        // 出牌后手牌 = [Strike_R, Defend_R, Bash]，选 0 和 2
        CardPlayer.play(s, 3, -1, new int[]{0, 2});

        assertEquals(1, s.hand.size(), "3 张里消耗 2 张");
        assertEquals("Defend_R", s.hand.get(0).id);
        assertEquals(3, s.exhaust.size(), "2 张 + 净化自身");
    }

    private static int shuffleCounter(SimState s) {
        RngStream rng = s.rng.get(SimState.RngKind.SHUFFLE);
        return rng == null ? -1 : rng.counter();
    }

    // -------------------------------------------------------------- 墨水瓶

    /**
     * {@code InkBottle.onUseCard}：{@code counter++}，满 10 归零并 {@code addToBot(DrawCardAction(1))}。
     * 日志里 {@code relics.InkBottle: 9 vs 0} + {@code draw.size: 8 vs 7} 就是这条。
     */
    @Test
    void inkBottleDrawsOnTenthCard() {
        SimState s = fight();
        s.relics.put("InkBottle", 9);
        s.draw.add(new SimCard("Defend_R", false, 1));

        Hooks.onCardUsed(s, new SimCard("Strike_R", false, 1), -1);

        assertEquals(0, s.relics.get("InkBottle").intValue(), "第十张牌后计数归零");
        assertEquals(1, s.hand.size(), "归零的同时抽 1 张");
        assertEquals(0, s.draw.size());
    }

    /** 计数不分牌型：技能牌一样算（墨水瓶不是修女）。 */
    @Test
    void inkBottleCountsSkillsToo() {
        SimState s = fight();
        s.relics.put("InkBottle", 3);

        Hooks.onCardUsed(s, new SimCard("Defend_R", false, 1), -1);

        assertEquals(4, s.relics.get("InkBottle").intValue());
    }

    // -------------------------------------------------------------- 恶魔之火

    /**
     * {@code FiendFireAction}：先排 count 次伤害，再排 count 次 {@code ExhaustAction(1, isRandom=true)}；
     * 随机分支走 {@code hand.getRandomCard(cardRandomRng)} —— 每消耗一张消耗一次 RNG。
     */
    @Test
    void fiendFireExhaustsRandomlyAndConsumesCardRng() {
        SimState s = fight();
        s.hand.add(new SimCard("Strike_R", false, 1));
        s.hand.add(new SimCard("Defend_R", false, 1));
        s.hand.add(new SimCard("Bash", false, 2));
        s.hand.add(new SimCard("Fiend Fire", true, 2));
        int before = cardCounter(s);

        CardPlayer.play(s, 3, 0);

        assertEquals(3, cardCounter(s) - before, "消耗 3 张手牌 = 3 次 cardRandomRng");
        assertEquals(0, s.hand.size());
        assertEquals(4, s.exhaust.size(), "3 张手牌 + 恶魔之火自身");
    }

    // -------------------------------------------------------------- 二次呼吸

    /**
     * {@code BlockPerNonAttackAction}：逐张 {@code GainBlockAction(player, player, this.block)}，
     * {@code this.block} 是卡面算好的（敏捷/虚弱只 floor 一次）→ 总格挡 = N * per。
     */
    @Test
    void secondWindBlockIsPerCard() {
        SimState s = fight();
        s.playerPowers.put(DamageCalc.DEXTERITY, 1);
        s.hand.add(new SimCard("Defend_R", false, 1));
        s.hand.add(new SimCard("Defend_R", false, 1));
        s.hand.add(new SimCard("Second Wind", false, 1));

        CardPlayer.play(s, 2, -1);

        // 逐张：(5 + 1) * 2 = 12；合并算会得到 (5*2 + 1) = 11
        assertEquals(12, s.block);
        assertEquals(2, s.exhaust.size());
    }

    // -------------------------------------------------------------- 战吼

    /**
     * {@code PutOnDeckAction} 的随机分支：手牌数 &lt;= amount 时不弹窗，
     * 直接 {@code hand.getRandomCard(cardRandomRng)} 搬走 —— 消耗一次 RNG。
     */
    @Test
    void warcryAutoPathConsumesCardRng() {
        SimState s = fight();
        s.draw.add(new SimCard("Defend_R", false, 1));
        s.hand.add(new SimCard("Warcry", false, 0));
        int before = cardCounter(s);

        CardPlayer.play(s, 0, -1, -1);

        assertEquals(1, cardCounter(s) - before, "没得选时走随机分支，消耗一次 cardRandomRng");
        assertEquals(0, s.hand.size());
        assertEquals(1, s.draw.size(), "唯一一张手牌被搬回牌堆顶");
        assertEquals(1, s.exhaust.size(), "战吼本身消耗");
    }

    /** 手牌 &gt; amount 时开选择窗：玩家选哪张就搬哪张，不消耗 RNG。 */
    @Test
    void warcryPutsChosenCardOnTopWithoutRng() {
        SimState s = fight();
        s.draw.add(new SimCard("Defend_R", false, 1));
        s.hand.add(new SimCard("Strike_R", false, 1));
        s.hand.add(new SimCard("Bash", false, 2));
        s.hand.add(new SimCard("Warcry", false, 0));
        int before = cardCounter(s);

        // 出牌后手牌 = [Strike_R, Bash, Defend_R]，选下标 0 = Strike_R
        CardPlayer.play(s, 2, -1, 0);

        assertEquals(before, cardCounter(s), "有得选时不消耗 RNG");
        assertEquals(2, s.hand.size());
        assertEquals("Strike_R", s.draw.get(s.draw.size() - 1).id, "被选中的牌落到牌堆顶");
    }
}
