package io.github.youqing.sts1solver.sim;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 空药水槽 {@code PotionSlot} 必须在**捕获那一刻**就变成 {@code null}。
 *
 * <p>原版 {@code AbstractPlayer.potions} 是一张定长列表，空格不是 {@code null} 而是
 * {@code PotionSlot} 实例，它的 {@code ID} 字面量就是 {@code "Potion Slot"}（javap 核实）。
 * 直接抄 {@code potion.ID} 会让搜索以为槽里有一瓶叫 "Potion Slot" 的药水 ——
 * 面板显示侧已在 {@code 42a0791} 修过，这里补的是**输入侧**（capture / fingerprint / diff）。
 */
class PotionSlotCaptureTest {

    @Test
    void potionSlotIdNormalizesToNull() {
        assertNull(SimState.normalizePotionId("Potion Slot"));
        assertNull(SimState.normalizePotionId(null));
    }

    @Test
    void realPotionsKeepTheirId() {
        assertEquals("Fire Potion", SimState.normalizePotionId("Fire Potion"));
        assertEquals("PowerPotion", SimState.normalizePotionId("PowerPotion"));
    }
}
