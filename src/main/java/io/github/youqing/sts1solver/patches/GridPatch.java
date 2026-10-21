package io.github.youqing.sts1solver.patches;

import com.evacipated.cardcrawl.modthespire.lib.SpirePatch;
import com.evacipated.cardcrawl.modthespire.lib.SpirePostfixPatch;
import com.evacipated.cardcrawl.modthespire.lib.SpirePrefixPatch;
import com.megacrit.cardcrawl.cards.AbstractCard;
import com.megacrit.cardcrawl.screens.select.HandCardSelectScreen;
import com.megacrit.cardcrawl.ui.buttons.GridSelectConfirmButton;

import io.github.youqing.sts1solver.exec.GridSelector;
import io.github.youqing.sts1solver.exec.HandSelectDriver;

/**
 * 选牌网格的驱动钩子。两个都很讲究时序，改之前先读 {@link GridSelector} 的注释。
 */
public final class GridPatch {

    private GridPatch() {
    }

    /**
     * 卡片 update 之后：此时 {@code hb.clicked} 刚被 {@code Hitbox.update()} 重写，
     * 界面在同一帧的循环里马上会读它 —— 只有这个窗口有效。
     */
    @SpirePatch(clz = AbstractCard.class, method = "update")
    public static class ClickCardInGrid {

        @SpirePostfixPatch
        public static void Postfix(AbstractCard __instance) {
            GridSelector.markClick(__instance);
        }
    }

    /** 确认按钮：update 前伪造鼠标，update 后还原。 */
    @SpirePatch(clz = GridSelectConfirmButton.class, method = "update")
    public static class ClickConfirmButton {

        @SpirePrefixPatch
        public static void Prefix(GridSelectConfirmButton __instance) {
            GridSelector.prepareConfirm(__instance);
        }

        @SpirePostfixPatch
        public static void Postfix(GridSelectConfirmButton __instance) {
            GridSelector.restoreInput();
        }
    }

    /**
     * 手牌选择界面（双持、军备、战吼…）。这是**另一套**界面，之前一直漏了它。
     */
    @SpirePatch(clz = HandCardSelectScreen.class, method = "update")
    public static class DriveHandSelect {

        @SpirePostfixPatch
        public static void Postfix(HandCardSelectScreen __instance) {
            HandSelectDriver.drive(__instance);
        }
    }
}
