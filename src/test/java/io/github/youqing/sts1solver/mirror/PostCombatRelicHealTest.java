package io.github.youqing.sts1solver.mirror;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 战斗胜利后的遗物回血（{@code Burning Blood} / {@code Black Blood} / {@code Meat on the Bone}）
 * —— 对应蓝本 {@code ActEndingBossPolicy.PostCombatRelicHealProfile}。
 *
 * <p>核心语义：排序只算**无条件**回血（{@link PostCombatRelicHeal#monotoneHealFor}），
 * 带骨肉的阈值回血只报给玩家（{@link PostCombatRelicHeal#healFor}）。
 */
class PostCombatRelicHealTest {

    private static Map<String, Integer> relics(String... ids) {
        Map<String, Integer> map = new HashMap<String, Integer>();
        for (String id : ids) {
            map.put(id, -1);
        }
        return map;
    }

    @Test
    void noRelicsMeansNoHeal() {
        PostCombatRelicHeal p = PostCombatRelicHeal.of(Collections.<String, Integer>emptyMap());
        assertFalse(p.hasAnyHeal());
        assertEquals(0, p.healFor(30, 80));
        assertEquals(0, p.monotoneHealFor(30, 80));
    }

    @Test
    void burningBloodCountsUnconditionally() {
        PostCombatRelicHeal p = PostCombatRelicHeal.of(relics(PostCombatRelicHeal.BURNING_BLOOD));
        assertTrue(p.hasAnyHeal());
        // 血满血不回（headroom 截断）
        assertEquals(0, p.healFor(80, 80));
        assertEquals(0, p.monotoneHealFor(80, 80));
        // 半血 → +6
        assertEquals(6, p.healFor(40, 80));
        assertEquals(6, p.monotoneHealFor(40, 80));
    }

    @Test
    void blackBloodHealsTwiceAsMuch() {
        PostCombatRelicHeal p = PostCombatRelicHeal.of(relics(PostCombatRelicHeal.BLACK_BLOOD));
        assertEquals(12, p.monotoneHealFor(40, 80));
        // 上限 5：只回到满血
        assertEquals(5, p.monotoneHealFor(75, 80));
    }

    /**
     * 带骨肉：**只有**{@code healFor} 看得见它；{@code monotoneHealFor} 必须看不见 ——
     * 否则「结束在阈值上 1 点血」会显得比「卡在阈值上」更差，破坏「血更多永不更差」。
     */
    @Test
    void meatOnTheBoneOnlyShowsInHealFor() {
        PostCombatRelicHeal p = PostCombatRelicHeal.of(relics(PostCombatRelicHeal.MEAT_ON_THE_BONE));

        // 40 = 80 的一半，触发（原版 float 比较 40 <= 40.5）
        assertEquals(12, p.healFor(40, 80));
        // 41 > 40.5，不触发
        assertEquals(0, p.healFor(41, 80));
        // 但排序侧一律不计
        assertEquals(0, p.monotoneHealFor(40, 80));
        assertEquals(0, p.monotoneHealFor(41, 80));
    }

    /** 两件都带：无条件的燃烧之血排序里会计，带骨肉只在 healFor 里补。 */
    @Test
    void burningBloodPlusMeatSplitsByWhichSideYouLookAt() {
        PostCombatRelicHeal p = PostCombatRelicHeal.of(relics(
                PostCombatRelicHeal.BURNING_BLOOD, PostCombatRelicHeal.MEAT_ON_THE_BONE));

        // healFor：6 + 12 = 18
        assertEquals(18, p.healFor(30, 80));
        // monotoneHealFor：只有 6
        assertEquals(6, p.monotoneHealFor(30, 80));
    }

    /** 阈值边界（奇数 maxHp）：原版是 float 比较 41 <= 40.5 为假，整除截断后同判。 */
    @Test
    void oddMaxHpThresholdMatchesTheFloatComparison() {
        PostCombatRelicHeal p = PostCombatRelicHeal.of(relics(PostCombatRelicHeal.MEAT_ON_THE_BONE));
        // 阈值 = 81 * 50 / 100 = 40
        assertEquals(12, p.healFor(40, 81), "40 <= 40.5 → 触发");
        assertEquals(0, p.healFor(41, 81), "41 > 40.5 → 不触发");
    }

    /** 蓝本 CapturePostCombatRelicHeal 里两件都带是累加的（原版 onVictory 逐个 heal）。 */
    @Test
    void bothBurningBloodAndBlackBloodAccumulate() {
        PostCombatRelicHeal p = PostCombatRelicHeal.of(relics(
                PostCombatRelicHeal.BURNING_BLOOD, PostCombatRelicHeal.BLACK_BLOOD));
        assertEquals(18, p.monotoneHealFor(40, 80));
    }
}
