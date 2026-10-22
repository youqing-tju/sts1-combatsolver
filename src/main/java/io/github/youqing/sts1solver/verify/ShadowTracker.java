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
    /** 出牌瞬间就算好的影子结果；null 表示这张牌没镜像（RECORD）或需要选牌（延后补算）。 */
    private static SimState pendingSim;
    private static ActionRejected pendingRejection;
    /** 需要选牌的牌：选哪张要等选牌界面，先把影子留到 resolve 再算。 */
    private static boolean pendingDeferred;
    /** 选牌界面实际选中的牌（id+升级态）。 */
    private static String pendingChoiceKey;
    /**
     * 选牌界面选中时，这张牌在**实机当前手牌**里的下标。
     *
     * <p>选牌窗口打开时打出的那张牌已经离开手牌，所以这个下标正好是
     * {@code ChoiceEffect} 要的「出牌后手牌下标」。以前用「出牌前」的下标去查，
     * 只要被选的牌排在打出的牌后面就会整体左移 1 —— 实测战吼：游戏选 Strike_R，影子搬走 Bash。
     */
    private static int pendingChoiceIndex = -1;
    /** 影子算出来的「四堆总张数」。实机达到这个数才算结算完毕。 */
    private static int expectedTotal;
    private static String pendingKey;
    private static int pendingTarget;
    private static boolean pending;
    private static int framesAfterPlay;

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
        pendingSim = null;
        pendingRejection = null;
        pendingDeferred = false;
        pendingChoiceKey = null;
        pendingChoiceIndex = -1;
        expectedTotal = -1;
        pending = false;
        pendingKey = null;
        framesAfterPlay = 0;
    }

    /**
     * 选牌界面选中了哪张牌（战吼/军备/双持…）。
     *
     * <p>对拍要用「实机选的那张」重放，否则就算游戏和搜索一致，影子也会因为
     * 没应用选择而差一手牌（实测战吼：hand 4 vs 3、discard 2 vs 1）。
     */
    public static void onChoicePicked(AbstractCard card) {
        if (card == null) {
            return;
        }
        pendingChoiceKey = card.cardID + (card.upgraded ? "+" : "");
        pendingChoiceIndex = AbstractDungeon.player == null
                || AbstractDungeon.player.hand == null
                || AbstractDungeon.player.hand.group == null
                ? -1 : AbstractDungeon.player.hand.group.indexOf(card);
        // 选牌一刻就把影子补算掉：这样 expectedTotal 立刻可知，
        // 结算判据不必退化成「动作队列空」，也就不会比实机早半秒开比
        // （实测战吼 cards=12/11：实机的消耗动画还没落位）。
        computeDeferredChoice();
    }

    /** 需要选牌的牌：拿到选择之后再算影子。 */
    private static void computeDeferredChoice() {
        if (!pendingDeferred || pre == null) {
            return;
        }
        SimState sim = pre.copy();
        int choice = choiceIndexOf(sim, pendingChoiceKey, pendingChoiceIndex);
        try {
            CardPlayer.playByKey(sim, pendingKey, pendingTarget, choice);
            pendingSim = sim;
            expectedTotal = sim.totalCards();
            pendingDeferred = false;
        } catch (ActionRejected e) {
            pendingSim = null;
            pendingRejection = e;
            expectedTotal = -1;
            pendingDeferred = false;
        } catch (Throwable t) {
            pendingSim = null;
            pendingRejection = new ActionRejected("simulation failed: " + t);
            expectedTotal = -1;
            pendingDeferred = false;
        }
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
        pendingChoiceKey = null;
        pendingChoiceIndex = -1;
        framesAfterPlay = 0;

        // 在出牌这一刻就把影子算完：后面判定「实机结算好了没有」需要知道
        // 这次出牌到底该产生几张牌（Anger 的复制、野性打击的状态牌都会变总数）。
        pendingSim = pre.copy();
        pendingRejection = null;
        pendingDeferred = false;
        try {
            io.github.youqing.sts1solver.mirror.CardMirror mirror =
                    io.github.youqing.sts1solver.mirror.MirrorRegistry.lookup(pendingKey);
            if (mirror != null && mirror.needsChoice) {
                // 选哪张要等选牌界面才定，只能等 resolve 时补算；
                // 这条路径无法预知牌数，结算判据退回「动画走完」
                pendingSim = null;
                pendingDeferred = true;
                expectedTotal = -1;
            } else {
                CardPlayer.playByKey(pendingSim, pendingKey, pendingTarget);
                expectedTotal = pendingSim.totalCards();
            }
        } catch (ActionRejected e) {
            pendingSim = null;
            pendingRejection = e;
            expectedTotal = -1;
        } catch (Throwable t) {
            pendingSim = null;
            pendingRejection = new ActionRejected("simulation failed: " + t);
            expectedTotal = -1;
        }
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
                if (actionsDrained() && totalSettled() && cardAnimationsIdle()) {
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
        SimState sim = pendingSim;
        ActionRejected rejection = pendingRejection;
        boolean deferred = pendingDeferred;
        String choiceKey = pendingChoiceKey;
        int choiceHint = pendingChoiceIndex;
        pre = null;
        pendingSim = null;
        pendingRejection = null;
        pendingDeferred = false;
        pendingChoiceKey = null;
        pendingChoiceIndex = -1;
        expectedTotal = -1;

        if (sim == null && deferred && before != null) {
            // 兜底：选牌回调没赶上（界面被外部关掉之类），只能在此刻补算
            sim = before.copy();
            int choice = choiceIndexOf(sim, choiceKey, choiceHint);
            try {
                CardPlayer.playByKey(sim, pendingKey, pendingTarget, choice);
            } catch (ActionRejected e) {
                record(before, e);
                return;
            }
        }
        if (sim == null) {
            record(before, rejection == null
                    ? new ActionRejected("no mirrored outcome") : rejection);
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
    /** 实机四个牌堆的总张数。 */
    private static int liveTotalCards() {
        AbstractPlayer p = AbstractDungeon.player;
        if (p == null) {
            return -1;
        }
        return p.hand.size() + p.drawPile.size() + p.discardPile.size() + p.exhaustPile.size();
    }

    /**
     * 实机是否已经产生出影子预期的牌数。
     *
     * <p>这是「结算完毕」的硬判据：Anger 的复制牌、野性打击的状态牌都是在
     * {@code ShowCardAndAddTo*} 动画结束时才落进牌堆的，在那之前实机总张数比影子少，
     * 提前比对就会得到「弃牌堆少 1 张」这种假失配（实测 21:37:24.300 MISMATCH
     * → 21:37:24.950 自己变成 MATCH）。
     */
    private static boolean totalSettled() {
        if (expectedTotal < 0) {
            return true;
        }
        int live = liveTotalCards();
        return live >= 0 && live >= expectedTotal;
    }

    /**
     * 牌堆添加动画是否播完。
     *
     * <p>{@code ShowCardAndAddTo*} 系列特效播放期间，那张牌不在任何牌堆里
     * （既不在手牌也不在抽牌/弃牌堆），总张数自然偏少。总张数判据 + 这条一起用，
     * 才不会在动画中途就先比对。
     */
    private static boolean cardAnimationsIdle() {
        if (AbstractDungeon.effectList == null) {
            return true;
        }
        for (com.megacrit.cardcrawl.vfx.AbstractGameEffect e : AbstractDungeon.effectList) {
            if (e == null) {
                continue;
            }
            if (e.getClass().getSimpleName().startsWith("ShowCardAndAddTo")) {
                return false;
            }
        }
        return true;
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

    /**
     * 选牌界面选中的那张牌，在影子手牌里的下标。
     *
     * <p>只支持「从手牌里选」这类（战吼/军备/双持/烧牌契约）；从弃牌堆/消耗堆里挑的
     * （头槌/发掘）下标指的是另一个牌堆，这里退化成 -1（即不应用选择），
     * 等对拍暴露出来再按牌堆分别处理。
     */
    /**
     * 选中的牌在影子手牌里的下标。
     *
     * <p>优先用「实机选牌瞬间的下标」（hint）：两份手牌的顺序理论上一致，
     * 只要该位置上的牌确实是选中的那张就直接采用。不一致才退回按牌名查找。
     */
    private static int choiceIndexOf(SimState sim, String choiceKey, int hint) {
        if (choiceKey == null) {
            return -1;
        }
        if (hint >= 0 && hint < sim.hand.size()
                && sim.hand.get(hint).key().equals(choiceKey)) {
            return hint;
        }
        for (int i = 0; i < sim.hand.size(); i++) {
            if (sim.hand.get(i).key().equals(choiceKey)) {
                return i;
            }
        }
        return -1;
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
