package io.github.youqing.sts1solver.search;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RouteStep} 的「这一步是喝药」标记。
 *
 * <p>🔴 这个标记是 2026-10-30 Boss 战卡死的根因修复：计划的第一步是
 * {@code Explosive Potion}，执行层只会「按牌名去手里找」，找不到就重算；
 * 搜索是确定性的，重算必然得到同一条路线 —— 于是「拒绝 → 重算 → 再拒绝」
 * 原地空转 100 秒、548 次，一张牌都没出。
 */
class RouteStepTest {

    @Test
    void potionFlagMarksPotionSteps() {
        RouteStep potion = new RouteStep(false, true, "Explosive Potion", 0, -1);
        assertTrue(potion.potion);
        assertFalse(potion.endTurn);
        assertEquals("Explosive Potion", potion.cardKey);
        assertTrue(potion.toString().startsWith("POTION "), "日志里要能一眼看出是喝药");
    }

    /** 同 ID 两瓶药水靠槽位区分：日志与执行都必须带上它。 */
    @Test
    void potionStepBindsItsSlot() {
        RouteStep second = RouteStep.potion("Fire Potion", 1, 0, -1);
        assertEquals(1, second.potionSlot);
        assertEquals("POTION[1] Fire Potion", second.toString());
        assertFalse(new RouteStep(false, "Strike_R", 0, -1).potion);
        assertEquals(-1, new RouteStep(false, "Strike_R", 0, -1).potionSlot,
                "打牌的步骤没有药水槽位");
    }

    @Test
    void cardStepsAreNotPotionSteps() {
        RouteStep card = new RouteStep(false, "Strike_R", 0, -1);
        assertFalse(card.potion, "老构造器默认是打牌，不能把牌当成药水");
        assertEquals("Strike_R", card.toString());
    }

    @Test
    void endTurnStepWinsOverPotionFlag() {
        RouteStep end = new RouteStep(true, "whatever", -1, -1);
        assertTrue(end.endTurn);
        assertEquals("END_TURN", end.toString());
    }
}
