package io.github.youqing.sts1solver.verify;

import io.github.youqing.sts1solver.mirror.CardPlayer;
import io.github.youqing.sts1solver.sim.MonsterMove;
import io.github.youqing.sts1solver.sim.SimCard;
import io.github.youqing.sts1solver.sim.SimMonster;
import io.github.youqing.sts1solver.sim.SimState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 稳定门：**只在实机真正结算完的那一帧出结论**。
 *
 * <p>这一层错了的代价是隐形的：一条假 MISMATCH 会涨 {@code mismatchSeq}，
 * 执行层据此判 {@code DEPLOY_REPLAN} 并丢掉整条规划 —— 用户看到的是「路线每回合乱跳」，
 * 日志里却只是一行 `forced after 90f`。
 *
 * <p>2026-11-02 真机审计确认了两种假失配：
 * <ol>
 *   <li>{@code actions=[]} 但 {@code GameActionManager.currentAction} 还在执行
 *       （BeatOfDeath / 多段 Fiend Fire）—— 固定等 90 帧解决不了；</li>
 *   <li>下一张牌到达时把上一张「当场 flush」—— 撞上合法中间态。</li>
 * </ol>
 * 本类用纯数据探针覆盖这两条，不启动游戏。
 */
class ShadowTrackerSettleTest {

    /** 可编排的探针：每个门都能单独关。 */
    private static final class Probe implements ShadowTracker.SettleProbe {
        boolean actionsEmpty = true;
        boolean currentActionDone = true;
        boolean cardQueueEmpty = true;
        boolean animationsIdle = true;
        boolean screenUp = false;
        int limbo = 0;

        @Override
        public boolean actionQueueEmpty() {
            return actionsEmpty;
        }

        @Override
        public boolean currentActionDone() {
            return currentActionDone;
        }

        @Override
        public boolean cardQueueEmpty() {
            return cardQueueEmpty;
        }

        @Override
        public int limboSize() {
            return limbo;
        }

        @Override
        public boolean animationsIdle() {
            return animationsIdle;
        }

        @Override
        public boolean screenUp() {
            return screenUp;
        }
    }

    private final Probe probe = new Probe();
    private final Deque<SimState> live = new ArrayDeque<SimState>();
    private java.io.File snapshotDir;

    private static SimState threeStrikes() {
        SimState s = new SimState();
        s.hp = 80;
        s.maxHp = 80;
        s.energy = 3;
        s.turn = 1;
        for (int i = 0; i < 3; i++) {
            s.hand.add(new SimCard("Strike_R", false, 1, 6, "ATTACK"));
        }
        s.draw.add(new SimCard("Defend", false, 1, -1, "SKILL"));
        SimMonster m = new SimMonster("TestMonster", 200, 200, 0,
                Collections.<String, Integer>emptyMap());
        m.pendingMove = MonsterMove.block(1, 0);
        m.pendingMoveId = 1;
        s.monsters.add(m);
        return s;
    }

    private static SimState afterPlaying(SimState from, int times) {
        SimState s = from.copy();
        for (int i = 0; i < times; i++) {
            CardPlayer.playByKey(s, "Strike_R", 0);
        }
        return s;
    }

    @BeforeEach
    void setUp() throws Exception {
        snapshotDir = java.nio.file.Files.createTempDirectory("sts1-settle").toFile();
        System.setProperty("sts1solver.snapshotDir", snapshotDir.getAbsolutePath());
        ShadowTracker.reset();
        ShadowTracker.resetCounters();
        ShadowTracker.settleProbe = probe;
        live.clear();
        ShadowTracker.liveState = new java.util.function.Supplier<SimState>() {
            @Override
            public SimState get() {
                return live.isEmpty() ? new SimState() : live.poll();
            }
        };
    }

    @AfterEach
    void tearDown() {
        ShadowTracker.settleProbe = ShadowTracker.LIVE_SETTLE_PROBE;
        ShadowTracker.liveState = ShadowTracker.DEFAULT_LIVE_STATE;
        ShadowTracker.reset();
        System.clearProperty("sts1solver.snapshotDir");
        deleteTree(snapshotDir);
    }

    private static void deleteTree(java.io.File f) {
        java.io.File[] kids = f.listFiles();
        if (kids != null) {
            for (java.io.File kid : kids) {
                deleteTree(kid);
            }
        }
        f.delete();
    }

    // ------------------------------------------------------------------ 1. currentAction

    /**
     * `actions=[]` 但 currentAction 非空时**不得**出结论；等它落地后只得一次正确结论。
     *
     * <p>这正是 Bash+ 致死心脏那条：Bash 的伤害动作已经出队，BeatOfDeath 的伤害动作
     * 正在 currentAction 上执行。此刻比，影子一定少一次扣格挡。
     */
    @Test
    void currentActionBlocksTheComparisonEvenWhenActionListIsEmpty() {
        SimState base = threeStrikes();
        live.add(afterPlaying(base, 1));

        int seqBefore = ShadowTracker.mismatchSequence();
        ShadowTracker.setBaseline(base);
        ShadowTracker.onCardPlayed("Strike_R", 0);

        probe.actionsEmpty = true;
        probe.currentActionDone = false;
        for (int i = 0; i < 200; i++) {
            ShadowTracker.advancePendingFrame();
        }
        assertEquals(0, ShadowTracker.comparisons(),
                "currentAction 未落地时不许出结论（200 帧也不行）");
        assertEquals(0, ShadowTracker.mismatches());
        assertEquals(seqBefore, ShadowTracker.mismatchSequence(), "不许触发部署重算");
        assertFalse(ShadowTracker.isIdle(), "未决期间执行层必须继续等");

        probe.currentActionDone = true;
        ShadowTracker.advancePendingFrame();
        assertEquals(1, ShadowTracker.comparisons());
        assertEquals(1, ShadowTracker.matches(), "稳定后应当对上");
        assertTrue(ShadowTracker.isIdle());
    }

    // ------------------------------------------------------------------ 2. 90 帧到期

    /**
     * 到期只报未决，**不写 baseline、不计数**；之后稳定帧仍完成同一张的比对。
     */
    @Test
    void pendingNeverBecomesAMismatchNoMatterHowLongItTakes() {
        SimState base = threeStrikes();
        live.add(afterPlaying(base, 1));

        int seqBefore = ShadowTracker.mismatchSequence();
        ShadowTracker.setBaseline(base);
        ShadowTracker.onCardPlayed("Strike_R", 0);

        probe.cardQueueEmpty = false;       // 多段队列还没跑完
        for (int i = 0; i < 500; i++) {
            ShadowTracker.advancePendingFrame();
        }
        assertEquals(0, ShadowTracker.comparisons(), "未决样本不进分母");
        assertEquals(0, ShadowTracker.mismatches());
        assertEquals(seqBefore, ShadowTracker.mismatchSequence());
        assertFalse(ShadowTracker.isIdle());

        probe.cardQueueEmpty = true;
        ShadowTracker.advancePendingFrame();
        assertEquals(1, ShadowTracker.comparisons(), "后续稳定帧必须补上这次比对");
        assertEquals(1, ShadowTracker.matches());
    }

    // ------------------------------------------------------------------ 3. 跨卡 flush

    /**
     * 下一张牌在上一张未稳定时到达：两张都记为未决，不许把中间态写成失配。
     *
     * <p>真机现场是 Fiend Fire / Warcry+ 之后的连打：第 9 击挂上 PenNib、第 10 击队列移除
     * 之间是一个**合法**窗口，此刻比出来的 `Pen Nib: missing vs 1` 不是模型错。
     */
    @Test
    void nextCardArrivingBeforeSettleDoesNotFabricateAMismatch() {
        SimState base = threeStrikes();
        live.add(afterPlaying(base, 1));

        int seqBefore = ShadowTracker.mismatchSequence();
        ShadowTracker.setBaseline(base);
        ShadowTracker.onCardPlayed("Strike_R", 0);
        probe.currentActionDone = false;
        ShadowTracker.onCardPlayed("Strike_R", 0);      // 上一张还没落地

        assertEquals(0, ShadowTracker.comparisons(),
                "未稳定时到达的下一张不得触发对拍（以前这里是 resolve(true)）");
        assertEquals(0, ShadowTracker.mismatches());
        assertEquals(seqBefore, ShadowTracker.mismatchSequence());
    }

    /**
     * 反过来：真正稳定后的差异**仍然必须报**。稳定门不能变成「什么都吞」。
     */
    @Test
    void aSettledDifferenceIsStillReported() {
        SimState base = threeStrikes();
        SimState drifted = afterPlaying(base, 1);
        drifted.hand.add(new SimCard("Bash", false, 2, 8, "ATTACK"));
        live.add(drifted);

        int seqBefore = ShadowTracker.mismatchSequence();
        ShadowTracker.setBaseline(base);
        ShadowTracker.onCardPlayed("Strike_R", 0);
        ShadowTracker.advancePendingFrame();
        ShadowTracker.advancePendingFrame();

        assertEquals(1, ShadowTracker.comparisons());
        assertEquals(1, ShadowTracker.mismatches(), "稳定后的真差异不许被稳定门吞掉");
        assertEquals(seqBefore + 1, ShadowTracker.mismatchSequence());
    }
}
