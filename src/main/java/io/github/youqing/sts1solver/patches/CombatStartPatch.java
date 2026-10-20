package io.github.youqing.sts1solver.patches;

import com.evacipated.cardcrawl.modthespire.lib.SpirePatch;
import com.evacipated.cardcrawl.modthespire.lib.SpirePostfixPatch;
import com.megacrit.cardcrawl.rooms.MonsterRoom;

import io.github.youqing.sts1solver.capture.RootSummary;
import io.github.youqing.sts1solver.diag.Trace;
import io.github.youqing.sts1solver.ui.SolverPanel;

/**
 * Fires once when the player enters any combat room (normal, elite and boss all
 * extend MonsterRoom). M0 only reads and logs; M1 will capture a full root snapshot here.
 */
public final class CombatStartPatch {

    private CombatStartPatch() {
    }

    @SpirePatch(clz = MonsterRoom.class, method = "onPlayerEntry")
    public static class OnEntry {

        @SpirePostfixPatch
        public static void Postfix(MonsterRoom __instance) {
            String summary;
            try {
                summary = RootSummary.describe();
            } catch (Throwable t) {
                Trace.error("root summary failed", t);
                return;
            }
            Trace.info("ROOT " + summary);
            SolverPanel.onCombatStart(summary);
        }
    }
}
