package io.github.youqing.sts1solver.capture;

import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.rooms.AbstractRoom;
import com.megacrit.cardcrawl.ui.panels.EnergyPanel;

import java.util.List;

import io.github.youqing.sts1solver.diag.Trace;
import io.github.youqing.sts1solver.mirror.Coverage;
import io.github.youqing.sts1solver.mirror.MirrorRegistry;
import io.github.youqing.sts1solver.sim.SimState;
import io.github.youqing.sts1solver.ui.SolverPanel;
import io.github.youqing.sts1solver.verify.ShadowTracker;

/**
 * The room-entry hook fires before cards are drawn and before energy is set, so the
 * real "combat root" only exists a few frames later. We arm on entry and capture on the
 * first frame where the room is in COMBAT and the hand is fully dealt.
 */
public final class CombatCapture {

    private static boolean armed;
    private static boolean captured;
    private static int framesWaited;

    /** 兜底：遗物/地形让手牌永远发不满时，等 3 秒也要抓一次，但要留下告警。 */
    private static final int MAX_WAIT_FRAMES = 180;

    private CombatCapture() {
    }

    public static void arm() {
        armed = true;
        captured = false;
        framesWaited = 0;
        ShadowTracker.reset();
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

            SimState state = SimState.capture();
            Trace.info("STATE " + state.shortLine() + "  fp=" + state.fingerprint());
            Trace.info("STATE file " + state.save());

            // A1 的第一步：深拷贝必须逐字段等价（这是后续所有 fork 的前提）
            List<String> diffs = state.diff(state.copy());
            Trace.info("COPY diff=" + diffs.size() + (diffs.isEmpty() ? " (identical)" : " " + diffs));

            List<String> missing = Coverage.unsupportedCards(state);
            Trace.info("COVER distinct=" + Coverage.distinctCards(state)
                    + " mirrored=" + MirrorRegistry.size()
                    + " unsupported=" + missing.size()
                    + (missing.isEmpty() ? "" : " " + missing));

            List<String> noisyRelics = Coverage.unmirroredRelics(state);
            if (!noisyRelics.isEmpty()) {
                Trace.warn("RELIC 未镜像的遗物 " + noisyRelics.size() + " " + noisyRelics
                        + " —— 它们的战斗内触发会让后面的 diff 变成噪声，"
                        + "要干净的验证数据请用「只有起手牌组 + 无触发遗物」的开局");
            }

            Trace.info("RNG " + RngForkProbe.run());
            // 一次性把整张卡牌表导出，之后生成镜像用数据，不靠"打出来才知道支不支持"
            CardExporter.exportOnce();
            // 注意：不在这里清零对拍计数。每场清零会让面板刚进战斗就显示 0/1、1/2，
            // 看起来像「命中率永远 1」，实际是统计被重置了。累计值更能说明趋势。
            ShadowTracker.setBaseline(state);
            SolverPanel.onCombatStart(state.shortLine());
        } catch (Throwable t) {
            Trace.error("combat capture failed", t);
        }
    }
}
