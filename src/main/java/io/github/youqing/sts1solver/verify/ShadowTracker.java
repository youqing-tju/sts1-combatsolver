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

    /** True when no comparison is in flight — the auto pilot must wait for this. */
    public static boolean isIdle() {
        return !pending;
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
        if (pending) {
            // 上一张还没结算完就又出牌（例如 Havoc 的自动出牌、或选牌卡拖了很久）。
            // 这时覆盖 pending 会让两张牌的状态互相污染，直接放弃这张的比对更诚实。
            Trace.warn("DIFF " + card.cardID + " skipped (previous " + pendingKey + " still pending)");
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
            // 选牌网格/地图等界面打开时，状态处于「半途」，连帧计数都要暂停，
            // 否则 1.5 秒兜底会在界面里强行比对，产生假差异
            if (AbstractDungeon.isScreenUp) {
                return;
            }
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
        SimState before = pre;
        SimState sim = before.copy();
        pre = null;

        try {
            CardPlayer.playByKey(sim, pendingKey, pendingTarget);
        } catch (ActionRejected e) {
            record(before, e);
            return;
        }

        SimState live = SimState.capture();
        // 死亡动画期间游戏仍保留 0 血怪物，两边先归一化再比。
        // 注意：必须比对副本 —— 直接 drop 会把 live 里的死怪删掉，而 live 随后要当 baseline，
        // 那会让下一次出牌的怪物下标对不上（实测报过 "effect needs a valid monster target"）。
        List<String> diffs = sim.copy().dropDeadMonsters().diff(live.copy().dropDeadMonsters());
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

    /**
     * 未镜像的牌不再"跳过就算"——把实机打这张牌前后发生的**全部字段变化**记下来。
     *
     * <p>这就是"补信息集"：每打一张新牌，我们都得到一个真实的 (前状态 → 后状态) 样本，
     * 以后写镜像时有现成的期望值可对照，而不是凭卡面文字猜。
     */
    private static void record(SimState before, ActionRejected e) {
        SimState live = SimState.capture();
        java.util.List<String> delta = before.copy().dropDeadMonsters()
                .diff(live.copy().dropDeadMonsters());
        Trace.info("RECORD " + pendingKey + " [" + e.getMessage() + "] "
                + delta.size() + " change(s): " + head(delta));
        appendRecord(pendingKey, delta);
        baseline = live;
    }

    private static void appendRecord(String card, java.util.List<String> delta) {
        try {
            String dirPath = System.getProperty("sts1solver.dataDir", "solver-data");
            java.io.File dir = new java.io.File(dirPath);
            if (!dir.exists() && !dir.mkdirs()) {
                return;
            }
            java.io.File file = new java.io.File(dir, "observed.jsonl");
            java.io.OutputStreamWriter w = new java.io.OutputStreamWriter(
                    new java.io.FileOutputStream(file, true), "UTF-8");
            try {
                StringBuilder sb = new StringBuilder(256);
                sb.append("{\"card\":\"").append(card).append("\",\"delta\":[");
                for (int i = 0; i < delta.size(); i++) {
                    if (i > 0) {
                        sb.append(',');
                    }
                    sb.append('"').append(delta.get(i).replace("\"", "'").replace("\\", "/"))
                      .append('"');
                }
                sb.append("]}\n");
                w.write(sb.toString());
            } finally {
                w.close();
            }
        } catch (Throwable t) {
            Trace.warn("record append failed: " + t);
        }
    }

    private static int liveLimboSize() {        return AbstractDungeon.player == null || AbstractDungeon.player.limbo == null
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
        // 选牌网格等界面打开时状态是「半途」的，不能用作判据
        boolean noScreen = !AbstractDungeon.isScreenUp;
        return actionsEmpty && cardQueueEmpty && limboEmpty && noScreen;
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
