package io.github.youqing.sts1solver.patches;

import com.evacipated.cardcrawl.modthespire.lib.SpirePatch;
import com.evacipated.cardcrawl.modthespire.lib.SpirePostfixPatch;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;

import io.github.youqing.sts1solver.ui.SolverPanel;

/**
 * Input polling for the panel. Runs on the main thread right after the dungeon update,
 * so the panel can never observe a half-updated state.
 */
public final class UpdatePatch {

    private UpdatePatch() {
    }

    @SpirePatch(clz = AbstractDungeon.class, method = "update")
    public static class AfterUpdate {

        @SpirePostfixPatch
        public static void Postfix(AbstractDungeon __instance) {
            SolverPanel.update();
        }
    }
}
