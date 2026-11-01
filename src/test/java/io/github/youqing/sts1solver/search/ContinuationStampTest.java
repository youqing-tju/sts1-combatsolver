package io.github.youqing.sts1solver.search;

import io.github.youqing.sts1solver.sim.MonsterMove;
import io.github.youqing.sts1solver.sim.RngStream;
import io.github.youqing.sts1solver.sim.SimCard;
import io.github.youqing.sts1solver.sim.SimMonster;
import io.github.youqing.sts1solver.sim.SimState;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ContinuationStamp} 的字段覆盖测试。
 *
 * <p>每一个 {@code assertNotEquals} 都在钉住一件事：**这个字段变了，就不能复用上一回合预测的世界线**。
 * 只比 {@code SimState.fingerprint()} 是不够的 —— 那个哈希刻意不含意图、怪物招式和 pendingMove 载荷，
 * 于是「同一份哈希、不同未来」会被当成同一局面复用。
 *
 * <p>为什么不用参数化测试：每一条的**变异手法**都不同（改哪一层、怎么改），写在一起能直接看出
 * 「这条测的到底是哪个字段」，坏了也知道是谁坏了。
 */
class ContinuationStampTest {

    /** 一个「什么都有」的最小战斗局面：四牌堆、药水、Power、遗物、怪物招式、五路 RNG。 */
    private static SimState base() {
        SimState s = new SimState();
        s.act = 1;
        s.floor = 7;
        s.ascension = 0;
        s.boss = false;
        s.turn = 2;
        s.gold = 120;
        s.hp = 64;
        s.maxHp = 80;
        s.block = 5;
        s.hpLossCount = 1;
        s.energy = 3;
        s.energyPerTurn = 3;

        s.playerPowers.put("Strength", 2);
        s.playerPowers.put("Dexterity", 1);
        s.powerAux.put("TheBomb.damage", 40);

        s.hand.add(new SimCard("Strike_R", false, 1, 6, "ATTACK"));
        s.hand.add(new SimCard("Defend_R", false, 1, 0, "SKILL"));
        s.draw.add(new SimCard("Bash", false, 2, 8, "ATTACK"));
        s.draw.add(new SimCard("Strike_R", false, 1, 6, "ATTACK"));
        s.discard.add(new SimCard("Anger", false, 0, 6, "ATTACK"));
        s.exhaust.add(new SimCard("AscendersBane", false, -2, -1, "CURSE"));
        s.relics.put("Burning Blood", -1);
        s.relics.put("Vajra", -1);

        s.potionSlots = 2;
        s.potions.add("Fire Potion");
        s.potions.add(null);

        SimMonster worm = new SimMonster("JawWorm", 40, 44, 0, map("Strength", 3));
        worm.intentDmg = 11;
        worm.intentAttack = true;
        worm.moveHistory.add(Integer.valueOf(2));
        worm.moveHistory.add(Integer.valueOf(1));
        worm.pendingMoveId = 3;
        worm.pendingMove = MonsterMove.attack(3, 11);
        worm.damageBase.add(Integer.valueOf(11));
        s.monsters.add(worm);

        for (SimState.RngKind kind : SimState.RngKind.values()) {
            s.rng.put(kind, RngStream.from(kind.ordinal() + 1L, kind.ordinal() + 101L,
                    kind.ordinal()));
        }
        return s;
    }

    private static Map<String, Integer> map(String key, int value) {
        Map<String, Integer> m = new TreeMap<String, Integer>();
        m.put(key, Integer.valueOf(value));
        return m;
    }

    private static ContinuationStamp stampOf(SimState s) {
        return ContinuationStamp.capturePredicted(s);
    }

    @Test
    void identicalStatesHaveIdenticalCanonicalText() {
        SimState a = base();
        SimState b = base();

        assertEquals(stampOf(a), stampOf(b), "两个等价局面必须相等（否则每回合都会 miss）");
        assertEquals(stampOf(a).hashCode(), stampOf(b).hashCode());
        assertEquals(stampOf(a).canonicalText(), stampOf(b).canonicalText());
        assertFalse(stampOf(a).canonicalText().isEmpty());
    }

    /**
     * 手牌和另外三个牌堆一样**保序**。
     *
     * <p>蓝本 {@code ContinuationStamp.AppendLivePile} 就是 {@code foreach (card in pile.Cards)}，
     * 手牌顺序会改变「先打哪张、抽到什么、连招成不成立」，是真实状态的一部分。
     * 旧实现按 key 排序（无序多重集），于是「手上 A·B」和「手上 B·A」被当成同一局面放行 ——
     * 那是静默复用错误世界线。
     */
    @Test
    void handOrderMattersExactlyLikeDrawDiscardAndExhaust() {
        SimState a = base();
        SimState b = base();
        Collections.reverse(b.hand);
        assertNotEquals(stampOf(a), stampOf(b), "手牌换序 = 换未来，必须不等");
        assertTrue(stampOf(a).describeFirstDifference(stampOf(b)).contains("hand["),
                "差异必须指名 hand 下标，实际：" + stampOf(a).describeFirstDifference(stampOf(b)));

        // 张数当然也不能丢：多一张同名牌必须不等
        SimState c = base();
        c.hand.add(new SimCard("Strike_R", false, 1, 6, "ATTACK"));
        assertNotEquals(stampOf(a), stampOf(c), "同名牌的重复计数必须进戳");
    }

    /**
     * 两侧入口产出的文本必须一致 —— 真机侧与预测侧比的是同一套字段。
     *
     * <p>蓝本两侧是两套对象模型，各写一份 {@code CaptureLive}/{@code CapturePredicted}；
     * 我们两侧都是 {@code SimState}，所以共用字段 writer，但仍然保留两个入口，
     * 让调用点无法含糊。这条测试钉的是「两个入口不许写出两种口径」。
     */
    @Test
    void liveAndPredictedCaptureAgreeOnTheSameState() {
        SimState s = base();
        ContinuationStamp live = ContinuationStamp.captureLive(s);
        ContinuationStamp predicted = ContinuationStamp.capturePredicted(s);

        assertEquals(live, predicted, "同一个局面的真机戳与预测戳必须相等，否则永远命中不了");
        assertEquals(live.canonicalText(), predicted.canonicalText());
        assertNotEquals(ContinuationStamp.captureLive(base()),
                ContinuationStamp.capturePredicted(altered()));
    }

    private static SimState altered() {
        SimState s = base();
        s.hp -= 1;
        return s;
    }

    @Test
    void drawPileOrderMatters() {
        SimState a = base();
        SimState b = base();
        Collections.swap(b.draw, 0, 1);

        assertNotEquals(stampOf(a), stampOf(b), "抽牌堆是有序堆，换序 = 换未来");
        assertTrue(stampOf(a).describeFirstDifference(stampOf(b)).contains("drawPile["),
                "差异必须指名抽牌堆下标");
    }

    @Test
    void cardFutureFieldsMatterEvenWhenEqualsWouldMatch() {
        // SimCard.equals 只看 id/upgraded/cost —— 这几个字段它**看不见**
        SimCard plain = new SimCard("Strike_R", false, 1, 6, "ATTACK");
        SimCard stronger = new SimCard("Strike_R", false, 1, 9, "ATTACK");
        assertEquals(plain, stronger, "前提：SimCard.equals 认为它们相同（否则这条测试没意义）");

        SimState a = base();
        SimState b = base();
        a.draw.set(0, plain);
        b.draw.set(0, stronger);
        assertNotEquals(stampOf(a), stampOf(b), "卡面伤害不同 → 未来伤害不同，必须 miss");

        SimState c = base();
        SimState d = base();
        c.hand.set(0, new SimCard("Strike_R", false, 1, 6, "SKILL"));
        d.hand.set(0, new SimCard("Strike_R", false, 1, 6, "ATTACK"));
        assertNotEquals(stampOf(c), stampOf(d), "牌类型不同 → 遗物计数不同，必须 miss");

        SimState e = base();
        SimState f = base();
        e.hand.set(0, new SimCard("Strike_R", false, 1, 6, "ATTACK"));
        f.hand.set(0, new SimCard("Strike_R", false, 1, 6, "ATTACK", 0));
        assertNotEquals(stampOf(e), stampOf(f), "本回合费用不同 → 这回合能打几张不同，必须 miss");
    }

    @Test
    void powerAmountAndStepsMatter() {
        SimState a = base();
        SimState b = base();
        b.playerPowers.put("Strength", 3);
        assertNotEquals(stampOf(a), stampOf(b), "层数进戳");

        SimState c = base();
        c.powerAux.put("TheBomb.damage", 50);
        assertNotEquals(stampOf(a), stampOf(c), "能力附属数值（炸弹伤害）进戳");

        SimState d = base();
        d.playerPowers.put("Metallicize", 3);
        assertNotEquals(stampOf(a), stampOf(d), "新增能力必须进戳");
    }

    @Test
    void relicCounterMatters() {
        SimState a = base();
        SimState b = base();
        b.relics.put("Vajra", 2);
        assertNotEquals(stampOf(a), stampOf(b), "遗物 counter（蜥蜴尾巴已用 / 死灵之书就绪）进戳");

        SimState c = base();
        c.relics.put("Lantern", 1);
        assertNotEquals(stampOf(a), stampOf(c), "多一件遗物必须进戳");
    }

    @Test
    void monsterHistoryAndPendingMovePayloadMatter() {
        SimState a = base();

        SimState history = base();
        history.monsters.get(0).moveHistory.add(Integer.valueOf(3));
        assertNotEquals(stampOf(a), stampOf(history), "招式历史决定分支，必须进戳");

        SimState pendingId = base();
        pendingId.monsters.get(0).pendingMoveId = 4;
        assertNotEquals(stampOf(a), stampOf(pendingId), "本回合招式字节必须进戳");

        // 同一个招式 id，但载荷（伤害）不同 = 语义不同
        SimState payload = base();
        payload.monsters.get(0).pendingMove = MonsterMove.attack(3, 9);
        assertNotEquals(stampOf(payload), stampOf(a), "pendingMove 载荷必须比较语义，不能只看 id");

        SimState multi = base();
        multi.monsters.get(0).pendingMove = MonsterMove.multiAttack(3, 11, 2);
        assertNotEquals(stampOf(multi), stampOf(a), "段数属于载荷");

        SimState none = base();
        none.monsters.get(0).pendingMove = null;
        assertNotEquals(stampOf(none), stampOf(a), "「没镜像的怪」与「镜像的怪」不能混为一谈");

        SimState intent = base();
        intent.monsters.get(0).intentDmg = 14;
        assertNotEquals(stampOf(a), stampOf(intent), "意图伤害是搜索估值的输入，必须进戳");

        SimState invincible = base();
        invincible.monsters.get(0).invincibleMax = 300;
        assertNotEquals(stampOf(a), stampOf(invincible), "无敌上限决定每回合最多掉多少血");
    }

    @Test
    void everyCombatRngCounterAndInternalStateMatters() {
        for (SimState.RngKind kind : SimState.RngKind.values()) {
            SimState baseState = base();
            RngStream stream = baseState.rng.get(kind);

            SimState counter = base();
            counter.rng.put(kind, RngStream.from(stream.state0(), stream.state1(),
                    stream.counter() + 1));
            assertNotEquals(stampOf(baseState), stampOf(counter),
                    kind + " 的 counter 必须进戳");

            SimState state0 = base();
            state0.rng.put(kind, RngStream.from(stream.state0() + 1L, stream.state1(),
                    stream.counter()));
            assertNotEquals(stampOf(baseState), stampOf(state0),
                    kind + " 的内部状态 state0 必须进戳");

            SimState state1 = base();
            state1.rng.put(kind, RngStream.from(stream.state0(), stream.state1() + 1L,
                    stream.counter()));
            assertNotEquals(stampOf(baseState), stampOf(state1),
                    kind + " 的内部状态 state1 必须进戳");

            SimState missing = base();
            missing.rng.remove(kind);
            assertNotEquals(stampOf(baseState), stampOf(missing),
                    kind + " 缺失与存在必须可区分");
        }
    }

    @Test
    void potionSlotIdentityAndDiscardMatter() {
        SimState a = base();
        SimState b = base();
        // 同一瓶药，换到另一格 —— 空格位置不同，捡到新药水的落点不同
        b.potions.set(0, null);
        b.potions.set(1, "Fire Potion");
        assertNotEquals(stampOf(a), stampOf(b), "药水按槽位编码，不是集合");

        SimState c = base();
        c.potions.set(0, "Block Potion");
        assertNotEquals(stampOf(a), stampOf(c), "药水 ID 进戳");

        SimState d = base();
        d.potions.add("Energy Potion");
        d.potionSlots = 3;
        assertNotEquals(stampOf(a), stampOf(d), "槽数与药水数量都进戳");
    }

    @Test
    void firstDifferenceNamesExactFieldPath() {
        SimState expected = base();
        SimState actual = base();
        actual.monsters.get(0).pendingMove = MonsterMove.attack(3, 9);

        String first = stampOf(expected).describeFirstDifference(stampOf(actual));
        assertTrue(first.contains("monsters[0].pendingMove.damage"),
                "首个差异必须指名到 pendingMove 的字段，实际是：" + first);
        assertTrue(first.contains("expected=11"), "要给出预测侧的值，实际是：" + first);
        assertTrue(first.contains("actual=9"), "要给出实机侧的值，实际是：" + first);

        SimState cards = base();
        cards.draw.set(1, new SimCard("Strike_R", true, 1, 6, "ATTACK"));
        String cardDiff = stampOf(expected).describeFirstDifference(stampOf(cards));
        assertTrue(cardDiff.contains("drawPile[1].upgraded"),
                "抽牌堆要指名下标与字段，实际是：" + cardDiff);
        assertTrue(cardDiff.contains("expected=false") && cardDiff.contains("actual=true"),
                "布尔值要能读，实际是：" + cardDiff);

        SimState rng = base();
        RngStream ai = base().rng.get(SimState.RngKind.AI);
        rng.rng.put(SimState.RngKind.AI,
                RngStream.from(ai.state0(), ai.state1(), ai.counter() + 1));
        String rngDiff = stampOf(expected).describeFirstDifference(stampOf(rng));
        assertTrue(rngDiff.contains("aiRng.counter"),
                "RNG 要指名到子字段，实际是：" + rngDiff);

        List<String> all = stampOf(expected).describeDifferences(stampOf(cards), 8);
        assertEquals(1, all.size(), "只差一张牌时只该报一条，实际是：" + all);
        List<String> limited = stampOf(expected).describeDifferences(stampOf(base()), 8);
        assertTrue(limited.isEmpty(), "相同局面没有差异");
    }

    @Test
    void differenceListIsBoundedAndOrderedDeterministically() {
        SimState expected = base();
        SimState actual = base();
        actual.hp = 50;
        actual.energy = 1;
        actual.draw.set(0, new SimCard("Bash", true, 2, 12, "ATTACK"));

        List<String> first = stampOf(expected).describeDifferences(stampOf(actual), 2);
        List<String> again = stampOf(expected).describeDifferences(stampOf(actual), 2);
        assertEquals(first, again, "同一对状态的差异列表必须稳定");
        assertEquals(2, first.size(), "limit 必须生效");
        assertTrue(first.get(0).startsWith("hp:"),
                "差异按字段表顺序输出（回合/资源在最前），实际是：" + first);
    }
}
