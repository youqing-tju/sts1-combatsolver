package io.github.youqing.sts1solver.capture;

import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.rooms.AbstractRoom;

import io.github.youqing.sts1solver.diag.Trace;
import io.github.youqing.sts1solver.ui.SolverPanel;

/**
 * The room-entry hook fires before cards are drawn and before energy is set, so the
 * real "combat root" only exists a few frames later. We arm on entry and capture on the
 * first frame where the room is in COMBAT and the hand is non-empty.
 */
public final class CombatCapture {

    private static boolean armed;
    private static boolean captured;

    private CombatCapture() {
    }

    public static void arm() {
        armed = true;
        captured = false;
        Trace.info("combat entry detected, waiting for deal");
    }

    public static void tick() {
        if (!armed || captured) {
            return;
        }
        try {
            AbstractRoom room = AbstractDungeon.getCurrRoom();
            if (room == null || room.phase != AbstractRoom.RoomPhase.COMBAT) {
                return;
            }
            if (AbstractDungeon.player == null || AbstractDungeon.player.hand == null) {
                return;
            }
            if (AbstractDungeon.player.hand.size() == 0) {
                return;
            }

            captured = true;
            armed = false;

            Trace.info("ROOT " + RootSummary.describe());
            SolverPanel.onCombatStart(RootSummary.describeShort());
        } catch (Throwable t) {
            Trace.error("combat capture failed", t);
        }
    }
}
