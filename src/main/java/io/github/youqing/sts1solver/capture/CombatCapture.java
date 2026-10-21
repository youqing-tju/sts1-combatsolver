package io.github.youqing.sts1solver.capture;

import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.rooms.AbstractRoom;
import com.megacrit.cardcrawl.ui.panels.EnergyPanel;

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
    private static int framesWaited;

    /** 兜底：地形/遗物让手牌永远发不满时，等 3 秒也要抓一次，但要留下告警。 */
    private static final int MAX_WAIT_FRAMES = 180;

    private CombatCapture() {
    }

    public static void arm() {
        armed = true;
        captured = false;
        framesWaited = 0;
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

            framesWaited++;

            // 发牌是一张一张来的：只判「非空」会在 hand=1 时就抓，必须等发满。
            int handSize = AbstractDungeon.player.hand.size();
            int expected = AbstractDungeon.player.gameHandSize;
            boolean dealt = handSize > 0 && handSize >= expected && EnergyPanel.totalCount > 0;
            boolean timedOut = framesWaited > MAX_WAIT_FRAMES;

            if (!dealt && !timedOut) {
                return;
            }

            captured = true;
            armed = false;

            if (timedOut && !dealt) {
                Trace.warn("capture timeout after " + framesWaited + " frames (hand=" + handSize
                        + " expected=" + expected + " energy=" + EnergyPanel.totalCount + ")");
            }

            Trace.info("ROOT " + RootSummary.describe());
            SolverPanel.onCombatStart(RootSummary.describeShort());
        } catch (Throwable t) {
            Trace.error("combat capture failed", t);
        }
    }
}
