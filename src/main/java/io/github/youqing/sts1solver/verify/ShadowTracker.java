package io.github.youqing.sts1solver.verify;

import com.megacrit.cardcrawl.actions.AbstractGameAction;
import com.megacrit.cardcrawl.cards.AbstractCard;
import com.megacrit.cardcrawl.characters.AbstractPlayer;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.monsters.AbstractMonster;
import com.megacrit.cardcrawl.rooms.AbstractRoom;

import java.util.List;

import io.github.youqing.sts1solver.diag.Trace;
import io.github.youqing.sts1solver.mirror.ActionRejected;
import io.github.youqing.sts1solver.mirror.CardPlayer;
import io.github.youqing.sts1solver.sim.SimState;

/**
 * The A2 differential harness: every card the player plays becomes one experiment.
 *
 * <p>Protocol per experiment:
 * <ol>
 *   <li>keep a {@code baseline} that is re-captured on every stable frame, so it always
 *       equals the live state just before the play;</li>
 *   <li>on {@code useCard}, remember the card and its target (do not touch the state);</li>
 *   <li>wait until the action queue drains, then rebuild the outcome with the shadow
 *       simulation and diff it against the live game;</li>
 *   <li>re-baseline on the live state, so the next card starts from ground truth again.</li>
 * </ol>
 *
 * <p>Re-baselining after every card is deliberate: unmirrored mechanics (relic triggers,
 * end-of-turn effects) would otherwise accumulate and turn every later diff into noise.
 * One card, one clean comparison.
 */
public final class ShadowTracker {

    private static SimState baseline;
    private static SimState pre;
    private static String pendingKey;
    private static int pendingTarget;
    private static boolean pending;
    private static int framesAfterPlay;

    private static int comparisons;
    private static int matches;
    private static int mismatches;

    private static final int SETTLE_FRAMES = 2;
    private static final int MAX_LOGGED_DIFFS = 6;

    private ShadowTracker() {
    }

    public static void reset() {
        baseline = null;
        pre = null;
        pending = false;
        pendingKey = null;
        framesAfterPlay = 0;
    }

    public static void resetCounters() {
        comparisons = 0;
        matches = 0;
        mismatches = 0;
    }

    /** Called from the {@code useCard} prefix patch, i.e. before the card resolves. */
    public static void onCardPlayed(AbstractCard card, AbstractMonster target) {
        if (baseline == null) {
            Trace.info("DIFF " + card.cardID + " skipped (no baseline yet)");
            return;
        }
        pre = baseline.copy();
        pendingKey = card.cardID + (card.upgraded ? "+" : "");
        pendingTarget = indexOf(target);
        pending = true;
        framesAfterPlay = 0;
    }

    public static void tick() {
        if (!inCombat()) {
            reset();
            return;
        }
        try {
            if (pending) {
                framesAfterPlay++;
                if (framesAfterPlay < SETTLE_FRAMES || !actionsDrained()) {
                    return;
                }
                resolve();
                return;
            }
            if (actionsDrained() && !AbstractDungeon.player.isEndingTurn) {
                baseline = SimState.capture();
            }
        } catch (Throwable t) {
            Trace.error("diff harness tick failed", t);
        }
    }

    private static void resolve() {
        pending = false;
        SimState sim = pre.copy();
        pre = null;

        try {
            CardPlayer.playByKey(sim, pendingKey, pendingTarget);
        } catch (ActionRejected e) {
            Trace.info("DIFF " + pendingKey + " SKIP (" + e.getMessage() + ")");
            baseline = null;
            return;
        }

        SimState live = SimState.capture();
        List<String> diffs = sim.diff(live);
        comparisons++;
        if (diffs.isEmpty()) {
            matches++;
            Trace.info("DIFF " + pendingKey + " MATCH  (" + matches + "/" + comparisons + ")");
        } else {
            mismatches++;
            Trace.info("DIFF " + pendingKey + " MISMATCH " + diffs.size()
                    + " field(s) (" + matches + "/" + comparisons + "): " + head(diffs));
        }
        baseline = live;
    }

    private static String head(List<String> diffs) {
        if (diffs.size() <= MAX_LOGGED_DIFFS) {
            return diffs.toString();
        }
        return diffs.subList(0, MAX_LOGGED_DIFFS) + " ...";
    }

    private static boolean actionsDrained() {
        java.util.ArrayList<AbstractGameAction> actions = AbstractDungeon.actionManager.actions;
        return actions == null || actions.isEmpty();
    }

    private static boolean inCombat() {
        AbstractRoom room = AbstractDungeon.getCurrRoom();
        return room != null && room.phase == AbstractRoom.RoomPhase.COMBAT
                && AbstractDungeon.player != null;
    }

    private static int indexOf(AbstractMonster target) {
        if (target == null || AbstractDungeon.getMonsters() == null) {
            return -1;
        }
        return AbstractDungeon.getMonsters().monsters.indexOf(target);
    }

    public static String stats() {
        return matches + " match / " + mismatches + " mismatch / " + comparisons + " total";
    }

    /** Kept for the panel and logs. */
    public static int matches() {
        return matches;
    }

    public static int mismatches() {
        return mismatches;
    }
}
