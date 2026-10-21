package io.github.youqing.sts1solver.patches;

import com.evacipated.cardcrawl.modthespire.lib.SpirePatch;
import com.evacipated.cardcrawl.modthespire.lib.SpirePostfixPatch;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;

import io.github.youqing.sts1solver.capture.CombatCapture;
import io.github.youqing.sts1solver.ui.SolverPanel;

/**
 * Main-thread tick: capture (once per combat) and panel input polling.
 * Runs right after the dungeon update, so the panel never sees a half-updated state.
 */
public final class UpdatePatch {

    private UpdatePatch() {
    }

    @SpirePatch(clz = AbstractDungeon.class, method = "update")
    public static class AfterUpdate {

        @SpirePostfixPatch
        public static void Postfix(AbstractDungeon __instance) {
            CombatCapture.tick();
            SolverPanel.update();
        }
    }
}
