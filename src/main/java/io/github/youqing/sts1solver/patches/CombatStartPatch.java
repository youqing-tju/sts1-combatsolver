package io.github.youqing.sts1solver.patches;

import com.evacipated.cardcrawl.modthespire.lib.SpirePatch;
import com.evacipated.cardcrawl.modthespire.lib.SpirePostfixPatch;
import com.megacrit.cardcrawl.rooms.MonsterRoom;

import io.github.youqing.sts1solver.capture.CombatCapture;
import io.github.youqing.sts1solver.diag.Trace;

/**
 * Fires when the player enters any combat room (normal, elite and boss all extend
 * MonsterRoom). The hand is not dealt yet at this point, so we only arm the capture and
 * let {@link CombatCapture#tick()} take the real snapshot a few frames later.
 */
public final class CombatStartPatch {

    private CombatStartPatch() {
    }

    @SpirePatch(clz = MonsterRoom.class, method = "onPlayerEntry")
    public static class OnEntry {

        @SpirePostfixPatch
        public static void Postfix(MonsterRoom __instance) {
            try {
                CombatCapture.arm();
            } catch (Throwable t) {
                Trace.error("arm failed", t);
            }
        }
    }
}
