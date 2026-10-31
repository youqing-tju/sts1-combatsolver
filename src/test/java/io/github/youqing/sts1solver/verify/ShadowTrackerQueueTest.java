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

/**
 * 逐张对拍的**排队纪律**：连续出牌时，两张牌必须各得一次比对，不能把后一张丢掉。
 *
 * <p>2026-10-31 真机日志里的 {@code DIFF xxx skipped (previous Warcry still pending)}
 * 就是这条规则破了：前一张还没结算完，后一张直接被 skip —— 它既不进 {@code comparisons}，
 * 也不会产生属于自己的 MISMATCH；而 {@code pre} 还停在更早那一手，于是**一条失配会顺着
 * 往下污染后续所有条目**（这也是 P1/P2 的验收数据不可信的直接原因）。
 *
 * <p>本测试用纯数据跑：{@link ShadowTracker#liveState} 是「实机状态从哪来」的唯一出口，
 * 生产永远指向 {@code SimState.capture()}，测试里换成预先算好的序列。
 */
class ShadowTrackerQueueTest {

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

    private final Deque<SimState> live = new ArrayDeque<SimState>();

    @BeforeEach
    void setUp() {
        ShadowTracker.reset();
        ShadowTracker.resetCounters();
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
        ShadowTracker.liveState = ShadowTracker.DEFAULT_LIVE_STATE;
        ShadowTracker.reset();
    }

    @Test
    void secondCardPlayedBeforeTheFirstSettlesStillGetsCompared() {
        SimState base = threeStrikes();
        live.add(afterPlaying(base, 1));        // 第一张结算后的实机状态
        live.add(afterPlaying(base, 2));        // 第二张结算后的实机状态

        ShadowTracker.setBaseline(base);
        ShadowTracker.onCardPlayed("Strike_R", 0);
        // 第一张还没结算（pending 仍为真）就打第二张 —— 以前这里整张被 skip
        ShadowTracker.onCardPlayed("Strike_R", 0);
        // 第三张：把第二张也推出去结算（真实环境里是主循环 tick 干的活）
        ShadowTracker.onCardPlayed("Strike_R", 0);

        assertEquals(2, ShadowTracker.comparisons(),
                "前两张必须各得一次比对（以前第二张被 skip，只会有 1 次）");
        assertEquals(2, ShadowTracker.matches(), "两条都应按实机状态对上");
        assertEquals(0, ShadowTracker.mismatches());
    }

    @Test
    void flushKeepsBaselineInSyncSoTheNextCardIsNotBlamedForTheOldOne() {
        SimState base = threeStrikes();
        // 第一张实机结果「多了一张牌」：故意与影子不同 → 必须记在 Strike 头上
        SimState drifted = afterPlaying(base, 1);
        drifted.hand.add(new SimCard("Bash", false, 2, 8, "ATTACK"));
        live.add(drifted);
        // 第二张：基线已刷新成 drifted，影子从 drifted 打出 Strike 的结果就是实机这一刻的状态
        live.add(afterPlaying(drifted, 1));

        ShadowTracker.setBaseline(base);
        ShadowTracker.onCardPlayed("Strike_R", 0);
        ShadowTracker.onCardPlayed("Strike_R", 0);
        ShadowTracker.onCardPlayed("Strike_R", 0);

        assertEquals(2, ShadowTracker.comparisons());
        assertEquals(1, ShadowTracker.mismatches(),
                "只有第一张该失配；第二张的基线已刷新，不该被旧差异连坐");
        assertEquals(1, ShadowTracker.mismatchSequence(), "失配序号只涨 1，说明第二张没被连坐");
    }
}
