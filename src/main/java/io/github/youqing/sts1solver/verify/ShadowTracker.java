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
    /** 选牌界面实际选中的牌（id+升级态），按选择顺序记录 —— 净化这类多选卡会有多张。 */
    private static final java.util.List<String> pendingChoiceKeys = new java.util.ArrayList<String>();
    /**
     * 选中时这张牌在**实机当前手牌**里的下标，与 {@link #pendingChoiceKeys} 一一对应。
     *
     * <p>选牌窗口打开时打出的那张牌已经离开手牌，所以这个下标正好是
     * {@code ChoiceEffect} 要的「出牌后手牌下标」。以前用「出牌前」的下标去查，
     * 只要被选的牌排在打出的牌后面就会整体左移 1 —— 实测战吼：游戏选 Strike_R，影子搬走 Bash。
     */
    private static final java.util.List<Integer> pendingChoiceIndices = new java.util.ArrayList<Integer>();
    /** 影子算出来的「四堆总张数」。实机达到这个数才算结算完毕。 */
    private static int expectedTotal;
    private static String pendingKey;
    private static int pendingTarget;
    private static boolean pending;
    private static int framesAfterPlay;

    private static int comparisons;
    private static int matches;
    private static int mismatches;
    /**
     * 失配序号（单调递增，跨战斗不清零）。执行层用它判断「计划还是不是从真局面算出来的」——
     * 一旦涨了，说明影子和实机已经分叉，后续步骤不能照着旧计划打（蓝本 {@code DeploymentDrift}）。
     */
    private static int mismatchSeq;
    /** 本场是否已经比过至少一次（决定结束时要不需要打汇总行）。 */
    private static boolean debugActive;

    private static final int SETTLE_FRAMES = 2;
    /**
     * 未决报告阈值：超过它仍不稳定就**定期**打一行诊断，但**绝不出结论**。
     *
     * <p>🔴 2026-11-02：这里以前是 `resolve(true)` —— 1.5 秒到期就强制比对并写 MISMATCH。
     * 真机日志里那些 `forced after 90f` 的 Fiend Fire / Warcry 样本全是这么来的：
     * 多段动作队列本来就要跑 2 秒以上，到期那一刻实机还在第 9 击和第 10 击之间，
     * 影子当然对不上。把它记成「镜像 bug」会让人去改根本没坏的代码。
     *
     * <p>正确语义（蓝本 {@code SolverController} 的稳定帧）：等不到就继续等，
     * 这一张记为未决，不进 comparisons / mismatchSeq / DEPLOY_REPLAN。
     */
    private static final int MAX_PENDING_FRAMES = 90;
    /** 未决诊断的限频间隔（帧）：1.5 秒 → 之后每 1 秒一行，别刷爆日志。 */
    private static final int PENDING_REPORT_INTERVAL = 60;

    /**
     * 「实机状态从哪来」的唯一出口：生产永远指向 {@link SimState#capture()}，
     * 单元测试换成纯数据序列（这样排队纪律不必启动游戏也能验证）。
     */
    static java.util.function.Supplier<SimState> DEFAULT_LIVE_STATE =
            new java.util.function.Supplier<SimState>() {
                @Override
                public SimState get() {
                    return SimState.capture();
                }
            };

    static java.util.function.Supplier<SimState> liveState = DEFAULT_LIVE_STATE;

    /**
     * 「实机是否已经结算完」的唯一探针。
     *
     * <p>为什么要一个探针而不是直接读 {@code AbstractDungeon}：稳定门是这一层最容易改错的
     * 地方（少一道门就出假失配、多一道门就永远不比），它必须有**纯数据单测**。
     * 生产实现读游戏字段，测试实现给一串预先编排的布尔序列 —— 与 {@link #liveState}
     * 同一个套路，不引入第二套状态机。
     */
    interface SettleProbe {
        boolean actionQueueEmpty();

        /** {@code GameActionManager.currentAction == null}。 */
        boolean currentActionDone();

        boolean cardQueueEmpty();

        /** limbo 张数；{@code -1} = 查询不到（不可知，不阻塞）。 */
        int limboSize();

        boolean animationsIdle();

        boolean screenUp();
    }

    static final SettleProbe LIVE_SETTLE_PROBE = new SettleProbe() {
        // ⚠️ 每个门都包 try/catch：单元测试里 AbstractDungeon 类初始化会失败
        // （需要游戏资源），此时「查询不到」必须按「不阻塞」处理 ——
        // 否则纯数据测试会因为一条诊断路径直接崩掉，而生产环境永远不会走这里。
        @Override
        public boolean actionQueueEmpty() {
            try {
                com.megacrit.cardcrawl.actions.GameActionManager am = AbstractDungeon.actionManager;
                return am == null || am.actions == null || am.actions.isEmpty();
            } catch (Throwable t) {
                return true;
            }
        }

        @Override
        public boolean currentActionDone() {
            try {
                com.megacrit.cardcrawl.actions.GameActionManager am = AbstractDungeon.actionManager;
                return am == null || am.currentAction == null;
            } catch (Throwable t) {
                return true;
            }
        }

        @Override
        public boolean cardQueueEmpty() {
            try {
                com.megacrit.cardcrawl.actions.GameActionManager am = AbstractDungeon.actionManager;
                return am == null || am.cardQueue == null || am.cardQueue.isEmpty();
            } catch (Throwable t) {
                return true;
            }
        }

        @Override
        public int limboSize() {
            return liveLimboSize();
        }

        @Override
        public boolean animationsIdle() {
            try {
                return cardAnimationsIdle();
            } catch (Throwable t) {
                return true;
            }
        }

        @Override
        public boolean screenUp() {
            try {
                return AbstractDungeon.isScreenUp;
            } catch (Throwable t) {
                return false;
            }
        }
    };

    static SettleProbe settleProbe = LIVE_SETTLE_PROBE;
    private static final int MAX_LOGGED_DIFFS = 6;
    /** 每场最多落盘多少个失配样本（防刷盘；一场打完一般也就几个）。 */
    private static final int MAX_MISMATCH_DUMPS = 24;
    private static int mismatchDumps;

    private ShadowTracker() {
    }

    public static void reset() {
        baseline = null;
        pre = null;
        pendingSim = null;
        pendingRejection = null;
        pendingDeferred = false;
        pendingChoiceKeys.clear();
        pendingChoiceIndices.clear();
        expectedTotal = -1;
        pending = false;
        pendingKey = null;
        framesAfterPlay = 0;
        mismatchDumps = 0;
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
        pendingChoiceKeys.add(card.cardID + (card.upgraded ? "+" : ""));
        pendingChoiceIndices.add(AbstractDungeon.player == null
                || AbstractDungeon.player.hand == null
                || AbstractDungeon.player.hand.group == null
                ? -1 : AbstractDungeon.player.hand.group.indexOf(card));
    }

    /**
     * 选中的不是手牌、而是**临时生成的候选**（发现 Discovery 的 3 选 1）。
     *
     * <p>这时下标含义是「生成列表里的第几张」，不是手牌下标 —— 直接照抄，
     * 影子侧按同样顺序生成候选，下标天然对齐。
     */
    public static void onGeneratedChoicePicked(int index) {
        pendingChoiceKeys.add(null);
        pendingChoiceIndices.add(index);
    }

    /** 给执行层评估「选哪张更好」用的实机快照（只读副本）。 */
    public static SimState snapshotForEvaluation() {
        if (baseline != null) {
            return baseline.copy();
        }
        return pre == null ? null : pre.copy();
    }

    /**
     * 选牌界面点了确认 —— 此刻才知道「一共选了哪几张」，开始补算影子。
     *
     * <p>这样 {@code expectedTotal} 立刻可知，结算判据不必退化成「动作队列空」，
     * 也就不会比实机早半秒开比（实测战吼 cards=12/11：实机的消耗动画还没落位）。
     * 多选卡（净化）必须等到这一刻，否则第一次点击就会被当成最终选择。
     */
    public static void onChoiceConfirmed() {
        computeDeferredChoice();
    }

    /** 需要选牌的牌：拿到选择之后再算影子。 */
    private static void computeDeferredChoice() {
        if (!pendingDeferred || pre == null) {
            return;
        }
        SimState sim = pre.copy();
        int[] choices = choiceIndices(sim);
        try {
            CardPlayer.playByKey(sim, pendingKey, pendingTarget, choices);
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

    /** 把记录下来的 (牌名, 实机下标) 映射成影子手牌下标。 */
    private static int[] choiceIndices(SimState sim) {
        if (pendingChoiceKeys.isEmpty()) {
            return null;
        }
        int[] out = new int[pendingChoiceKeys.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = choiceIndexOf(sim, pendingChoiceKeys.get(i), pendingChoiceIndices.get(i));
        }
        return out;
    }

    /** True when no comparison is in flight — the auto pilot must wait for this. */
    public static boolean isIdle() {
        return !pending;
    }

    /** Seeds the baseline at combat start so the very first card is already comparable. */
    public static void setBaseline(SimState state) {
        baseline = state == null ? null : state.copy();
        if (baseline != null) {
            debugActive = true;
        }
    }

    /**
     * 喝药之后影子就落后了 —— 把基线作废，等实机动作队列排空再重新捕获。
     *
     * <p>为什么不能直接算：药水的效果（迅捷药水抽 3 张、火药水打 10 伤）是**实机侧**的
     * 一等动作，本台架只跟踪「打牌」这一种（{@link #onCardPlayed}）。以前喝药不作废基线，
     * 于是下一张牌的 {@code pre} 还是喝药前的影子：真机 20:30:55 打 Bash 时就报了
     * {@code hand.size 3 vs 6 / draw.size 11 vs 8} —— 差的那 3 张全是迅捷药水抽的，
     * 跟 Bash 一点关系都没有。作废比瞎算诚实：这一张记为 skipped，不污染统计。
     */
    public static void invalidateBaseline(String reason) {
        if (baseline != null) {
            Trace.info("DIFF baseline dropped: " + reason);
        }
        baseline = null;
        if (pending) {
            Trace.info("DIFF " + pendingKey + " skipped (baseline dropped: " + reason + ")");
            pending = false;
            pendingSim = null;
            pendingRejection = null;
            pendingDeferred = false;
            pre = null;
            expectedTotal = -1;
        }
    }

    public static void resetCounters() {
        comparisons = 0;
        matches = 0;
        mismatches = 0;
    }

    /** Called from the {@code useCard} prefix patch, i.e. before the card resolves. */
    public static void onCardPlayed(AbstractCard card, AbstractMonster target) {
        onCardPlayed(card.cardID + (card.upgraded ? "+" : ""), indexOf(target));
    }

    /**
     * 纯数据入口（生产路径由上面那个重载转进来，单元测试直接用牌名）。
     *
     * <p>🔴 连续出牌不能丢掉后一张（2026-10-31 真机日志 {@code DIFF xxx skipped
     * (previous Warcry still pending)}）：那会让后一张既不进 {@code comparisons}、
     * 也不产生属于自己的 MISMATCH，而 {@code pre} 还停在更早那一手 ——
     * **一条失配顺着往下污染所有条目**。正确做法是先把上一张当场结算（{@link #resolve()}
     * 末尾会把基线重设成结算后的实机状态），再用它当这一张的基线。
     */
    static void onCardPlayed(String key, int targetIndex) {
        if (baseline == null) {
            Trace.info("DIFF " + key + " skipped (no baseline yet)");
            return;
        }
        if (pending) {
            // 下一张牌已经进了 useCard（前缀钩子），但上一张**可能还没结算完**。
            // 稳定 → 当场补上这次比对（等价于 tick 晚了一帧）；不稳定 → 两张都记为未决，
            // 绝不把中间态当结论（2026-11-02 之前的 `resolve(true)` 就在这里造假失配）。
            if (settled()) {
                resolve();
            } else {
                abandonPending("next card arrived before settle");
            }
            if (baseline == null) {
                Trace.info("DIFF " + key + " skipped (baseline reset after unsettled previous play)");
                return;
            }
        }
        pre = baseline.copy();
        pendingKey = key;
        pendingTarget = targetIndex;
        pending = true;
        pendingChoiceKeys.clear();
        pendingChoiceIndices.clear();
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
            // 战斗结束时留一行汇总：朋友报问题时只看这一行就知道「这局到底对得怎么样」，
            // 不用翻几万行 DIFF。
            if (debugActive) {
                Trace.info("DIFF SUMMARY " + stats());
                debugActive = false;
            }
            reset();
            return;
        }
        try {
            // 选牌网格/地图等界面打开时，状态处于「半途」，连帧计数都要暂停，
            // 否则未决帧数会在界面里白涨（界面一关就立刻触发一次无意义的等待报告）。
            if (settleProbe.screenUp()) {
                return;
            }
            if (advancePendingFrame()) {
                return;
            }
            if (actionsDrained() && !AbstractDungeon.player.isEndingTurn) {
                baseline = liveState.get();
            }
        } catch (Throwable t) {
            Trace.error("diff harness tick failed", t);
        }
    }

    /**
     * 推进一帧「等待对拍」；{@code true} 表示这一帧仍由 pending 占用。
     *
     * <p>抽成包内可见的纯函数是为了让「90 帧到期不得出结论」能被**不启动游戏**地测到：
     * 这个分支的错误代价极高（一次假 MISMATCH 会触发 DEPLOY_REPLAN 丢掉整条规划），
     * 不能只靠真机观察。
     */
    static boolean advancePendingFrame() {
        if (!pending) {
            return false;
        }
        framesAfterPlay++;
        if (framesAfterPlay < SETTLE_FRAMES) {
            return true;
        }
        if (settled()) {
            resolve();
        } else if (framesAfterPlay >= MAX_PENDING_FRAMES
                && framesAfterPlay % PENDING_REPORT_INTERVAL == 0) {
            // 只报告，不出结论：这一张仍然是 pending，isIdle 仍为 false。
            Trace.warn("DIFF WAIT " + pendingKey + " unsettled after "
                    + framesAfterPlay + "f: " + unsettledReason());
        }
        return true;
    }

    /**
     * 真正可以出结论了吗：原生动作队列、卡牌队列、limbo、添加动画**和 currentAction**
     * 全部落地，并且实机已经产生影子预期的牌数。
     *
     * <p>🔴 为什么必须看 {@code GameActionManager.currentAction}：
     * {@code getNextAction()} 是「先从 actions 列表里 remove，再执行」，所以执行期间
     * {@code actions} 已经是空的，而 DamageAction / ApplyPowerAction 还没落地。
     * 只看 {@code actions.isEmpty()} 会在这一帧就宣布「结算完了」—— 这正是真机前 7 条
     * `block` 差 1 的来源（Bash+ 的 BeatOfDeath 还在执行，影子已经去比了）。
     * 它不是「多等几帧」的问题：任何固定帧数都会在慢动作上重新踩到。
     */
    private static boolean settled() {
        return actionsDrained() && totalSettled() && settleProbe.animationsIdle();
    }

    /** 未决时明确说出「卡在哪一道门」，不要让人从 forced 标签猜。 */
    private static String unsettledReason() {
        StringBuilder sb = new StringBuilder();
        SettleProbe probe = settleProbe;
        if (!probe.actionQueueEmpty()) {
            sb.append("actions ");
        }
        if (!probe.currentActionDone()) {
            sb.append("currentAction ");
        }
        if (!probe.cardQueueEmpty()) {
            sb.append("cardQueue ");
        }
        int limbo = probe.limboSize();
        if (limbo > 0) {
            sb.append("limbo=").append(limbo).append(' ');
        }
        if (!totalSettled()) {
            sb.append("cards=").append(expectedTotal).append('>').append(liveTotalCards()).append(' ');
        }
        if (!probe.animationsIdle()) {
            sb.append("animation ");
        }
        if (probe.screenUp()) {
            sb.append("screen ");
        }
        return sb.length() == 0 ? "unknown" : sb.toString().trim();
    }

    /**
     * 放弃一次**未决**的对拍：不计数、不涨 mismatchSeq、不写 baseline。
     *
     * <p>为什么 baseline 也要作废：此时实机正停在中间态，拿它当下一张的起点会把
     * 「没结算完」变成一条永久的错基线。作废之后 tick 会在动作真正排空时重新捕获。
     */
    private static void abandonPending(String reason) {
        Trace.warn("DIFF PENDING " + pendingKey + " dropped (" + reason + "): "
                + unsettledReason());
        pending = false;
        pendingSim = null;
        pendingRejection = null;
        pendingDeferred = false;
        pendingChoiceKeys.clear();
        pendingChoiceIndices.clear();
        pre = null;
        expectedTotal = -1;
        baseline = null;
    }

    private static void resolve() {
        pending = false;
        SimState before = pre;
        SimState sim = pendingSim;
        ActionRejected rejection = pendingRejection;
        boolean deferred = pendingDeferred;
        java.util.List<String> choiceKeys = new java.util.ArrayList<String>(pendingChoiceKeys);
        java.util.List<Integer> choiceHints = new java.util.ArrayList<Integer>(pendingChoiceIndices);
        pre = null;
        pendingSim = null;
        pendingRejection = null;
        pendingDeferred = false;
        pendingChoiceKeys.clear();
        pendingChoiceIndices.clear();
        expectedTotal = -1;

        if (sim == null && deferred && before != null) {
            // 兜底：选牌回调没赶上（界面被外部关掉之类），只能在此刻补算
            sim = before.copy();
            int[] choices = new int[choiceKeys.size()];
            for (int i = 0; i < choices.length; i++) {
                choices[i] = choiceIndexOf(sim, choiceKeys.get(i), choiceHints.get(i));
            }
            try {
                CardPlayer.playByKey(sim, pendingKey, pendingTarget,
                        choices.length == 0 ? null : choices);
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

        SimState live = liveState.get();
        // 死亡动画期间游戏仍保留 0 血怪物，两边先归一化再比。
        // 注意：必须比对副本 —— 直接 drop 会把 live 里的死怪删掉，而 live 随后要当 baseline，
        // 那会让下一次出牌的怪物下标对不上（实测报过 "effect needs a valid monster target"）。
        List<String> diffs = sim.copy().dropDeadMonsters().diff(live.copy().dropDeadMonsters());
        comparisons++;
        String tag = "";
        if (diffs.isEmpty()) {
            matches++;
            Trace.info("DIFF " + pendingKey + " MATCH  (" + matches + "/" + comparisons + ")" + tag);
        } else {
            mismatches++;
            mismatchSeq++;
            Trace.info("DIFF " + pendingKey + " MISMATCH " + diffs.size()
                    + " field(s) (" + matches + "/" + comparisons + ")"
                    + " limbo=" + liveLimboSize()
                    + " cards=" + sim.totalCards() + "/" + live.totalCards()
                    + tag + ": " + head(diffs));
            dumpMismatch(before, sim, live, diffs, choiceKeys, choiceHints);
        }
        baseline = live;
    }

    /**
     * 把一次失配完整落盘：**出牌前 / 影子算的 / 实机变成的** 三份快照 + 打的是哪张牌、
     * 目标是谁、选牌选了哪几张、差异字段全表。
     *
     * <p>为什么必须落盘：日志只打前 6 条差异，而且日志里没有「出牌前」的状态 ——
     * 复盘时只能猜「大概是某个加成没算」。落盘之后每一次失配都是一条**可离线重放**的用例：
     * 直接塞进 {@code src/test/resources/golden/} 就能跑，修一处就能当场验证。
     * 目录：{@code <dataDir>/mismatch/}（与 observed.jsonl 同一处）。
     */
    private static void dumpMismatch(SimState before, SimState sim, SimState live,
                                     List<String> diffs,
                                     java.util.List<String> choiceKeys,
                                     java.util.List<Integer> choiceHints) {
        if (before == null || mismatchDumps >= MAX_MISMATCH_DUMPS) {
            return;
        }
        try {
            String dirPath = System.getProperty("sts1solver.snapshotDir", "solver-snapshots");
            java.io.File dir = new java.io.File(dirPath, "mismatch");
            if (!dir.exists() && !dir.mkdirs()) {
                return;
            }
            mismatchDumps++;
            java.io.File file = new java.io.File(dir, String.format("%03d-%s.json",
                    Integer.valueOf(mismatchDumps), safeName(pendingKey)));
            StringBuilder sb = new StringBuilder(4096);
            // forced 字段保留写 false：本版不再存在「到期强判」的样本，读端据此分类。
            sb.append("{\"card\":\"").append(pendingKey).append("\",\"target\":")
              .append(pendingTarget).append(",\"forced\":false")
              .append(",\"settled\":true,\"choices\":[");
            // 🔴 选择必须在 resolve 清空 pending 之前抄下来，否则 dump 里永远是空数组 ——
            // 那样 Headbutt/Warcry/True Grit 的样本就不能当确定性 oracle 用。
            for (int i = 0; i < choiceKeys.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                String key = choiceKeys.get(i);
                Integer hint = choiceHints.get(i);
                sb.append("{\"key\":");
                if (key == null) {
                    sb.append("null");
                } else {
                    sb.append('"').append(key).append('"');
                }
                sb.append(",\"index\":").append(hint.intValue())
                  // 目前只有「从手牌选」与「从生成候选里选」两种来源；
                  // 弃牌堆/抽牌堆/消耗堆的选择在 Task 5B 之前还不存在。
                  .append(",\"pile\":\"").append(key == null ? "GENERATED" : "HAND")
                  .append("\"}");
            }
            sb.append("],\"diffs\":[");
            for (int i = 0; i < diffs.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append('"').append(diffs.get(i).replace("\"", "'").replace("\\", "/"))
                  .append('"');
            }
            sb.append("],\"before\":").append(before.toJson())
              .append(",\"sim\":").append(sim.toJson())
              .append(",\"live\":").append(live.toJson()).append("}\n");
            java.io.OutputStreamWriter w = new java.io.OutputStreamWriter(
                    new java.io.FileOutputStream(file), "UTF-8");
            try {
                w.write(sb.toString());
            } finally {
                w.close();
            }
            Trace.info("MISMATCH DUMP " + file.getPath());
        } catch (Throwable t) {
            Trace.warn("mismatch dump failed: " + t);
        }
    }

    private static String safeName(String key) {
        if (key == null) {
            return "unknown";
        }
        return key.replace('/', '_').replace('\\', '_').replace(' ', '_');
    }

    /**
     * 未镜像的牌不再"跳过就算"——把实机打这张牌前后发生的**全部字段变化**记下来。
     *
     * <p>这就是"补信息集"：每打一张新牌，我们都得到一个真实的 (前状态 → 后状态) 样本，
     * 以后写镜像时有现成的期望值可对照，而不是凭卡面文字猜。
     */
    private static void record(SimState before, ActionRejected e) {
        SimState live = liveState.get();
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

    private static int liveLimboSize() {
        // 诊断量绝不能反过来打断比对：类初始化/不在战斗里时一律记 -1
        try {
            return AbstractDungeon.player == null || AbstractDungeon.player.limbo == null
                    ? -1 : AbstractDungeon.player.limbo.size();
        } catch (Throwable t) {
            return -1;
        }
    }

    private static int liveDiscardSize() {
        return AbstractDungeon.player == null || AbstractDungeon.player.discardPile == null
                ? -1 : AbstractDungeon.player.discardPile.size();
    }
    /** 实机四个牌堆的总张数。-1 = 查询不到（单元测试 / 战斗刚结束），不阻塞结算判定。 */
    private static int liveTotalCards() {
        try {
            AbstractPlayer p = AbstractDungeon.player;
            if (p == null) {
                return -1;
            }
            return p.hand.size() + p.drawPile.size() + p.discardPile.size() + p.exhaustPile.size();
        } catch (Throwable t) {
            return -1;
        }
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
        if (live < 0) {
            // 查询不到实机牌堆（单元测试 / 战斗刚结束）：没证据说「还没好」，不阻塞。
            return true;
        }
        return live >= expectedTotal;
    }

    /**
     * 牌堆添加动画是否播完。
     *
     * <p>{@code ShowCardAndAddTo*} 系列特效播放期间，那张牌不在任何牌堆里
     * （既不在手牌也不在抽牌/弃牌堆），总张数自然偏少。总张数判据 + 这条一起用，
     * 才不会在动画中途就先比对。
     */
    private static boolean cardAnimationsIdle() {
        // 牌在牌堆之间"飞"的时候（SoulGroup.souls 里还有没就绪的 Soul），那张牌
        // 不属于手牌/抽牌堆/弃牌堆/消耗堆中的任何一个 —— 此时比对必然少 1 张。
        // 打**能力牌**最容易踩到：真机 2026-10-30 20:30:53 打暗黑拥抱+ 时就报了
        // `forced after 90f: [discard.size: 12 vs 11] cards=19/18`，其实只是飞行没落地。
        if (com.megacrit.cardcrawl.cards.SoulGroup.isActive()) {
            return false;
        }
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
        SettleProbe probe = settleProbe;
        // 🔴 `getNextAction()` 先把动作移出 actions 再执行，所以 actions 空 **不代表**
        //    当前 DamageAction / ApplyPowerAction 已落地 —— 必须同时看 currentAction。
        //    固定等待帧替代不了它：任何帧数都会在慢动作上重新踩到。
        // limbo = -1 表示查询不到（单元测试无游戏对象），按「不可知即不阻塞」处理。
        return probe.actionQueueEmpty() && probe.currentActionDone()
                && probe.cardQueueEmpty() && probe.limboSize() <= 0
                && !probe.screenUp();
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
            // 「生成候选」型选择（发现）：下标本来就是生成列表里的位置，直接照抄
            return hint;
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

    /** 本场（或本段）已经做出的比对次数；{@code matches + mismatches} 应恒等于它。 */
    public static int comparisons() {
        return comparisons;
    }

    /** 见 {@link #mismatchSeq}：只增不减，调用方记下旧值、下次比对即可。 */
    public static int mismatchSequence() {
        return mismatchSeq;
    }

    /** Kept for the panel and logs. */
    public static int matches() {
        return matches;
    }

    public static int mismatches() {
        return mismatches;
    }
}
