package io.github.youqing.sts1solver.sim;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 对拍**账本字段**的口径：哪些能比、哪些不能比。
 *
 * <p>2026-11-02 真机（20:45:44 / 20:46:12）给了两条实证：
 * <pre>
 * DIFF Feed  MISMATCH 1 field(s): [feedGrowth: 1 vs 0]     → DEPLOY_REPLAN
 * DIFF Anger MISMATCH 1 field(s): [angerCopiesGenerated: 1 vs 0]  → DEPLOY_REPLAN
 * </pre>
 *
 * <p>两者处理方式**必须不同**：
 * <ul>
 *   <li>{@code feedGrowth} / {@code handOfGreedGrowth}：原版 STS1 没有这个计数器，
 *       {@code capture()} 只能给 0 —— 放进 diff 就是「每打一张必失配」，属于伪造差异；</li>
 *   <li>{@code angerCopiesGenerated}：可以由「战斗内 Anger 张数 − 牌组 Anger 张数」
 *       精确还原，所以要么 capture 给真值、要么就是真 bug。这里钉住「它仍然参与比较」。</li>
 * </ul>
 */
class LedgerDiffScopeTest {

    private static SimState base() {
        SimState s = new SimState();
        s.hp = 80;
        s.maxHp = 80;
        s.energy = 3;
        s.turn = 2;
        return s;
    }

    /** feedGrowth 变了不算状态差（真机侧没有对应物）。 */
    @Test
    void feedGrowthIsNotPartOfTheDiff() {
        SimState a = base();
        SimState b = base();
        b.feedGrowth = 3;

        assertFalse(contains(a.diff(b), "feedGrowth"),
                "feedGrowth 是蓝本账本，原版 capture 恒 0：不能当成影子算错\n" + a.diff(b));
    }

    /** 手头贪婪同理。 */
    @Test
    void handOfGreedGrowthIsNotPartOfTheDiff() {
        SimState a = base();
        SimState b = base();
        b.handOfGreedGrowth = 2;

        assertFalse(contains(a.diff(b), "handOfGreedGrowth"), a.diff(b).toString());
    }

    /**
     * Anger 复制数**仍然**参与比较：它可以从四堆与牌组的差值还原，
     * 所以「影子多复制了一张」是必须抓到的真错。
     */
    @Test
    void angerCopiesStillAreCompared() {
        SimState a = base();
        SimState b = base();
        b.angerCopiesGenerated = 1;

        assertTrue(contains(a.diff(b), "angerCopiesGenerated"),
                "Anger 复制数可精确捕获，不能被当成账本豁免\n" + a.diff(b));
    }

    /** 长期资源（贪婪之手到手的金币）本来就是「两边都能算」，不能顺带豁免。 */
    @Test
    void longTermResourceStillIsCompared() {
        SimState a = base();
        SimState b = base();
        b.longTermResourceValue = 25;

        assertTrue(contains(a.diff(b), "longTermResourceValue"), a.diff(b).toString());
    }

    /** 豁免只针对这三个账本，别的字段照旧敏感。 */
    @Test
    void ordinaryFieldsStaySensitive() {
        SimState a = base();
        SimState b = base();
        b.hp = 79;

        assertEquals("hp: 80 vs 79", a.diff(b).get(0));
    }

    private static boolean contains(java.util.List<String> diffs, String needle) {
        for (String d : diffs) {
            if (d.contains(needle)) {
                return true;
            }
        }
        return false;
    }
}
