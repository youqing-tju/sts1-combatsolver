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
import io.github.youqing.sts1solver.verify.AutoPilot;
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
    /** 战斗开始时的能量值 = 每回合基础能量（搜索模拟回合开始要用）。 */
    private static int baseEnergy = 3;

    /** 兜底：遗物/地形让手牌永远发不满时，等 3 秒也要抓一次，但要留下告警。 */
    private static final int MAX_WAIT_FRAMES = 180;

    /** 本场战斗开始时的血量，收尾时用来报「这一场到底掉了多少」。 */
    private static int hpAtStart = -1;

    private CombatCapture() {
    }

    public static void arm() {
        if (armed) {
            return;
        }
        armed = true;
        captured = false;
        framesWaited = 0;
        hpAtStart = AbstractDungeon.player == null ? -1 : AbstractDungeon.player.currentHealth;
        ShadowTracker.reset();
        AutoPilot.reset();
        Trace.info("combat entry detected, waiting for deal");
    }

    /** 本场战斗的根状态是否已经抓过（即首轮发牌已完成）。 */
    public static boolean isCaptured() {
        return captured;
    }

    /** 每回合基础能量（战斗开始时抓到的值，用于搜索里模拟回合开始）。 */
    public static int baseEnergy() {
        return baseEnergy;
    }

    public static void tick() {
        try {
            AbstractRoom room = AbstractDungeon.getCurrRoom();
            if (room == null) {
                return;
            }
            // 离开战斗就复位，下一场重新捕获
            if (room.phase != AbstractRoom.RoomPhase.COMBAT) {
                if (armed) {
                    // 复刻蓝本的 CombatEndedEvent -> SolverController.Reset("combat_ended")
                    // 一场战斗一行「掉血账」：用户关心的「白吃多少战损」就是这一行
                    Trace.info("COMBAT END  hp " + (AbstractDungeon.player == null ? "?"
                            : AbstractDungeon.player.currentHealth + "/" + AbstractDungeon.player.maxHealth)
                            + "  (started " + hpAtStart + ")"
                            + "  lost " + (hpAtStart < 0 || AbstractDungeon.player == null
                                    ? "?" : (hpAtStart - AbstractDungeon.player.currentHealth))
                            + "  diff " + ShadowTracker.stats());
                    Trace.info("combat ended, state cleared");
                    AutoPilot.reset();
                    io.github.youqing.sts1solver.ui.SolverPanel.onCombatEnd();
                }
                armed = false;
                captured = false;
                return;
            }
            // 🔴 不依赖房间钩子：精英房/首领房重写了 onPlayerEntry，父类的补丁不会执行
            // （实测 Act 2 精英战全程 combat: (none)）。直接用「房间阶段进入 COMBAT」判定。
            if (!armed) {
                arm();
            }
            if (captured) {
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
            // 注意：这里不能把 armed 复位，否则下一帧会重新 arm -> captured 又变 false -> 每帧重复捕获。
            // armed/captured 只在「离开战斗」时统一复位。
            // 设置里勾了「开局自动」就自动接管（M4-5）
            if (io.github.youqing.sts1solver.config.SolverConfig.autoStart()
                    && !AutoPilot.enabled()) {
                AutoPilot.toggle();
            }

            if (timedOut && !dealt) {
                Trace.warn("capture timeout after " + framesWaited + " frames (hand=" + handSize
                        + " expected=" + expected + " energy=" + EnergyPanel.totalCount + ")");
            }

            SimState state = SimState.capture();
            baseEnergy = Math.max(1, state.energy);   // 首回合能量 = 每回合基础能量
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
