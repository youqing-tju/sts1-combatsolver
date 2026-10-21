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
    private static int expectedDiscardSize;

    private static int comparisons;
    private static int matches;
    private static int mismatches;

    private static final int SETTLE_FRAMES = 2;
    /** 兜底：某张牌让 limbo 一直不空时，等 1.5 秒也要出结论，并标记 forced。 */
    private static final int MAX_PENDING_FRAMES = 90;
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

    /** Seeds the baseline at combat start so the very first card is already comparable. */
    public static void setBaseline(SimState state) {
        baseline = state == null ? null : state.copy();
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
        // 这张牌结算完，实机弃牌堆必然 +1。用它当「真的结算完了」的判据，
        // 比猜固定帧数可靠 —— limbo 空了也不代表牌已经落到弃牌堆。
        expectedDiscardSize = pre.discard.size() + 1;
    }

    public static void tick() {
        if (!inCombat()) {
            reset();
            return;
        }
        try {
            if (pending) {
                framesAfterPlay++;
                if (framesAfterPlay < SETTLE_FRAMES) {
                    return;
                }
                if (actionsDrained() && discardSettled()) {
                    resolve(false);
                } else if (framesAfterPlay >= MAX_PENDING_FRAMES) {
                    resolve(true);
                }
                return;
            }
            if (actionsDrained() && !AbstractDungeon.player.isEndingTurn) {
                baseline = SimState.capture();
            }
        } catch (Throwable t) {
            Trace.error("diff harness tick failed", t);
        }
    }

    private static void resolve(boolean forced) {
        pending = false;
        SimState sim = pre.copy();
        pre = null;

        try {
            CardPlayer.playByKey(sim, pendingKey, pendingTarget);
        } catch (ActionRejected e) {
            Trace.info("DIFF " + pendingKey + " SKIP (" + e.getMessage() + ")");
            // 这张牌没镜像，但实机已经走完了 —— 直接把 baseline 拉回真值。
            // 早期这里置 null，导致紧跟着的下一张牌被判成 no baseline / card not in hand。
            baseline = SimState.capture();
            return;
        }

        SimState live = SimState.capture();
        // 死亡动画期间游戏仍保留 0 血怪物，两边先归一化，否则长度差异与这张牌无关
        List<String> diffs = sim.dropDeadMonsters().diff(live.dropDeadMonsters());
        comparisons++;
        String tag = forced ? " (forced after " + framesAfterPlay + "f)" : "";
        if (diffs.isEmpty()) {
            matches++;
            Trace.info("DIFF " + pendingKey + " MATCH  (" + matches + "/" + comparisons + ")" + tag);
        } else {
            mismatches++;
            Trace.info("DIFF " + pendingKey + " MISMATCH " + diffs.size()
                    + " field(s) (" + matches + "/" + comparisons + ")"
                    + " limbo=" + liveLimboSize()
                    + " cards=" + sim.totalCards() + "/" + live.totalCards()
                    + tag + ": " + head(diffs));
        }
        baseline = live;
    }

    private static int liveLimboSize() {
        return AbstractDungeon.player == null || AbstractDungeon.player.limbo == null
                ? -1 : AbstractDungeon.player.limbo.size();
    }

    private static int liveDiscardSize() {
        return AbstractDungeon.player == null || AbstractDungeon.player.discardPile == null
                ? -1 : AbstractDungeon.player.discardPile.size();
    }

    /** 打出的牌是否已经真正落进弃牌堆。 */
    private static boolean discardSettled() {
        return liveDiscardSize() >= expectedDiscardSize;
    }

    private static String head(List<String> diffs) {
        if (diffs.size() <= MAX_LOGGED_DIFFS) {
            return diffs.toString();
        }
        return diffs.subList(0, MAX_LOGGED_DIFFS) + " ...";
    }

    private static boolean actionsDrained() {
        com.megacrit.cardcrawl.actions.GameActionManager am = AbstractDungeon.actionManager;
        boolean actionsEmpty = am.actions == null || am.actions.isEmpty();
        // 卡牌动画队列未空时，牌还没真正进入弃牌堆
        boolean cardQueueEmpty = am.cardQueue == null || am.cardQueue.isEmpty();
        // limbo 是「打出去但还没落到弃牌堆」的暂存区；这里不空同样说明还没结算完
        boolean limboEmpty = liveLimboSize() == 0;
        return actionsEmpty && cardQueueEmpty && limboEmpty;
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
