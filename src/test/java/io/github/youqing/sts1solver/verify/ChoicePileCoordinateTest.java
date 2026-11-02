package io.github.youqing.sts1solver.verify;

import io.github.youqing.sts1solver.sim.SimCard;
import io.github.youqing.sts1solver.sim.SimState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * 选牌坐标必须按**来源牌堆**解析，不能一律当手牌。
 *
 * <p>真机现场（2026-11-02）：
 * <ul>
 *   <li>True Grit+：出牌前用 {@code [Strike,Strike,TG,Defend,Warcry]} 的出牌前下标去查
 *       出牌后手牌，整体左移 1 → 烧掉的是下一张；</li>
 *   <li>Headbutt：选择来自弃牌堆，{@code choiceIndices} 只查手牌 → 恒 -1，
 *       影子不应用选择，终局报 {@code hand-2 / discard+1}。</li>
 * </ul>
 *
 * <p>本类只钉「坐标解析」这一层：给什么牌堆、用什么下标、拿不到时返回 -1（绝不猜）。
 */
class ChoicePileCoordinateTest {

    private static SimState piles() {
        SimState s = new SimState();
        s.hp = 80;
        s.maxHp = 80;
        s.hand.add(new SimCard("Strike_R", false, 1, 6, "ATTACK"));
        s.hand.add(new SimCard("Bash", true, 2, 10, "ATTACK"));
        s.discard.add(new SimCard("Defend_R", false, 1, -1, "SKILL"));
        s.discard.add(new SimCard("Headbutt", false, 1, 9, "ATTACK"));
        s.discard.add(new SimCard("Strike_R", false, 1, 6, "ATTACK"));
        s.draw.add(new SimCard("Warcry", false, 0, -1, "SKILL"));
        s.exhaust.add(new SimCard("Intimidate", true, 0, -1, "SKILL"));
        return s;
    }

    /** 弃牌堆里的第 2 张（下标 1）= Headbutt：必须解析成 1，而不是在手牌里找。 */
    @Test
    void discardChoiceResolvesAgainstTheDiscardPile() {
        SimState s = piles();
        assertEquals(1, ShadowTracker.choiceIndexOf(s, "Headbutt", 1, "DISCARD"));
    }

    /** 手牌里的 Bash 在 index 1。 */
    @Test
    void handChoiceResolvesAgainstTheHand() {
        SimState s = piles();
        assertEquals(1, ShadowTracker.choiceIndexOf(s, "Bash+", 1, "HAND"));
    }

    /**
     * 同名牌必须按**下标**而不是按名字找：弃牌堆里有两张 Strike_R（下标 2），
     * 选的是第二张时不能返回第一张。
     */
    @Test
    void duplicateNamesAreDisambiguatedByIndex() {
        SimState s = piles();
        assertEquals(2, ShadowTracker.choiceIndexOf(s, "Strike_R", 2, "DISCARD"));
    }

    /** 抽牌堆 / 消耗堆同样支持。 */
    @Test
    void drawAndExhaustPilesAreSupported() {
        SimState s = piles();
        assertEquals(0, ShadowTracker.choiceIndexOf(s, "Warcry", 0, "DRAW"));
        assertEquals(0, ShadowTracker.choiceIndexOf(s, "Intimidate+", 0, "EXHAUST"));
    }

    /**
     * 来源未知（{@code pile == null}）时**不许退回手牌**：宁可返回 -1 让对拍报出来，
     * 也不要猜一个「看起来对」的下标。
     */
    @Test
    void unknownSourceIsRejectedInsteadOfAssumedToBeHand() {
        SimState s = piles();
        assertNull(ShadowTracker.pileOf(s, null));
        assertEquals(-1, ShadowTracker.choiceIndexOf(s, "Bash+", -1, null));
    }

    /** 目标牌堆里确实没有这张牌：返回 -1，不越界、不静默选错。 */
    @Test
    void missingCardReturnsMinusOne() {
        SimState s = piles();
        assertEquals(-1, ShadowTracker.choiceIndexOf(s, "Reaper", -1, "DISCARD"));
    }

    /** 牌堆映射本身要指向同一个列表（不是拷贝）。 */
    @Test
    void pileLookupReturnsTheLiveList() {
        SimState s = piles();
        assertSame(s.discard, ShadowTracker.pileOf(s, "DISCARD"));
        assertSame(s.hand, ShadowTracker.pileOf(s, "HAND"));
    }
}
