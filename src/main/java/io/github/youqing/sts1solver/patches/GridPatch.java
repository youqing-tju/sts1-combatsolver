package io.github.youqing.sts1solver.patches;

import com.evacipated.cardcrawl.modthespire.lib.SpirePatch;
import com.evacipated.cardcrawl.modthespire.lib.SpirePostfixPatch;
import com.megacrit.cardcrawl.screens.select.GridCardSelectScreen;

import io.github.youqing.sts1solver.exec.GridSelector;

/**
 * 选牌网格的驱动钩子。
 *
 * <p>必须挂在 {@link GridCardSelectScreen#update()} 上，而不是 {@code AbstractDungeon.update()}：
 * 界面打开期间后者不再被调用，挂在那儿等于没挂（实测卡在选牌界面 80 秒，一次都没触发）。
 */
public final class GridPatch {

    private GridPatch() {
    }

    @SpirePatch(clz = GridCardSelectScreen.class, method = "update")
    public static class AfterGridUpdate {

        @SpirePostfixPatch
        public static void Postfix(GridCardSelectScreen __instance) {
            GridSelector.drive(__instance);
        }
    }
}
