package io.github.youqing.sts1solver.verify;

import com.megacrit.cardcrawl.actions.GameActionManager;
import com.megacrit.cardcrawl.cards.AbstractCard;
import com.megacrit.cardcrawl.cards.CardQueueItem;
import com.megacrit.cardcrawl.characters.AbstractPlayer;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.monsters.AbstractMonster;
import com.megacrit.cardcrawl.rooms.AbstractRoom;
import com.megacrit.cardcrawl.ui.panels.EnergyPanel;

import java.util.ArrayList;

import io.github.youqing.sts1solver.capture.CombatCapture;
import io.github.youqing.sts1solver.diag.Trace;
import io.github.youqing.sts1solver.mirror.MirrorRegistry;
import io.github.youqing.sts1solver.search.BeamSolver;
import io.github.youqing.sts1solver.search.PlanAction;
import io.github.youqing.sts1solver.search.RouteStep;
import io.github.youqing.sts1solver.search.SearchNode;
import io.github.youqing.sts1solver.sim.SimState;

/**
 * Auto-play driver used to generate validation data without a human: it plays any
 * mirrored card it can afford, then ends the turn.
 *
 * <p>This exists because waiting for a person to hit the right situations is far too slow
 * (the blueprint solves the same problem with a scenario builder + unattended runner).
 * It is also the seed of the M4 executor, so the work is not thrown away.
 *
 * <p>It only ever enqueues cards through {@link CardQueueItem}, the same path the game's
 * own UI uses — no synthetic state edits, so a diff failure still points at the mirror.
 */
public final class AutoPilot {

    private static boolean enabled;
    private static int cooldown;

    /**
     * 防重复入队：记住**刚入队的那个卡牌对象本身**。
     *
     * <p>⚠️ 不能用牌名判断。实测：烧牌契约打完后，枯木树枝又会塞回一张同名牌，
     * 按名字判断会一直以为「它还在手上没打出去」，于是反复误报卡死并关掉 AUTO。
     * 牌名不唯一，对象才唯一。
     */
    private static AbstractCard queuedCard;
    private static int queuedFrames;

    /**
     * 连续「空手牌结束回合」的次数。对应蓝本的 {@code ShouldPruneCrossTurnNoProgress}：
     * 跨回合无进展就不该无限推进，否则会像实测那样每 0.5 秒空转一个回合（能量涨到 400+）。
     */
    private static int noProgressEndTurns;
    private static final int NO_PROGRESS_LIMIT = 12;

    /**
     * 本回合被游戏拒绝过的牌（「入队了但一直留在手上」）。
     *
     * <p>以前一卡住就 {@code enabled = false}，等于因为一张牌废掉整场自动战斗 ——
     * 实测缠身那次就是这样。现在改成：拉黑这一张，换别的牌继续；同回合连续撞墙 3 次
     * 才判定真卡死并关掉 AUTO。
     */
    private static final java.util.Set<String> blockedThisTurn =
            new java.util.HashSet<String>();
    private static int blockedTurn = Integer.MIN_VALUE;
    private static int stuckStreak;
    private static final int STUCK_STREAK_LIMIT = 3;

    /**
     * 本回合从根重算的次数 —— **失败路径保护**，不是玩法机制（见 {@link #deploymentDrift}）。
     *
     * <p>每打出一张牌就归零：有进展说明漂移只是偶发，不是「重算→同一步→再重算」的死循环。
     */
    private static int deployDriftsThisTurn;
    private static final int DEPLOY_DRIFT_LIMIT = 12;

    /** 回合切换的两段式等待：1=等敌方阶段开始，2=等它结束并回到玩家回合。 */
    private static int endPhase;
    private static int endPhaseFrames;
    private static final int MAX_END_PHASE_FRAMES = 900;

    /** 搜索宽度与深度：先给保守值，能用后再调（每帧都要跑，太大会掉帧）。 */
    /** 搜索给出的目标怪物下标；-1 表示沿用第一个存活怪物。 */
    private static int chosenTargetIndex = -1;

    /** 搜索为「需要选牌」的卡挑定的手牌下标（打出后手牌列表），-1 表示没有。 */
    private static int pendingChoiceIndex = -1;

    private static int searchCount;
    private static long searchNodesTotal;

    /**
     * 搜索预算（宽度 × 深度）由设置页决定，不再写死。
     * 档位定义见 {@link io.github.youqing.sts1solver.config.SolverConfig.Budget}。
     */

    private AutoPilot() {
    }

    public static boolean enabled() {
        return enabled;
    }

    /**
     * 每场战斗开始时清掉上一场的残留状态。
     *
     * <p>复刻蓝本 {@code SolverController.BeginCombat} 的第一行：{@code ResetCore("combat_starting")}——
     * 它也是先清空再从零开始（`reference/sts2-combatsolver/src/Runtime/SolverController.cs:588`）。
     * 不清的话，上一场留下的「无进展计数」「回合切换阶段」会让新战斗一开局就结束回合或原地卡住。
     *
     * <p>注意：{@code enabled} 属于玩家开关，跨战斗保留；清的是每场战斗的运行时状态。
     */
    public static void reset() {
        cooldown = 0;
        queuedCard = null;
        queuedFrames = 0;
        noProgressEndTurns = 0;
        endPhase = 0;
        endPhaseFrames = 0;
        blockedThisTurn.clear();
        blockedTurn = Integer.MIN_VALUE;
        stuckStreak = 0;
        searchStartedAt = 0;
        deployDriftsThisTurn = 0;
        io.github.youqing.sts1solver.search.AsyncSearch.reset();
        clearPlan();
        // 换战斗才真的清空预览：这时保留上一场的路线是误导
        preview = null;
        previewStale = false;
        planSummary = "";
        framesWaitingForPlan = 0;
        reportedMissingMirrors.clear();
        strategySnapshotDirty = false;
        screenWasUp = false;
        potionPolicyLogged = false;
        strategySnapshot = null;
        Trace.info("AUTO state reset (combat boundary)");
    }

    public static void toggle() {
        if (enabled) {
            // 手动关闭 = 立即交还控制。已经入队的游戏动作仍由游戏自然结算，不强杀。
            stopAuto("toggled off");
            return;
        }
        prepareEnable();
        Trace.info("AUTO ON");
    }

    /**
     * 开启 AUTO 前的统一清理。
     *
     * <p>不清的话上一轮残留的「刚入队的那张牌 / 连续无进展计数」会让新一次开启
     * 立刻误判卡死（实测：一开就自己关掉）。
     */
    private static void prepareEnable() {
        enabled = true;
        cooldown = 0;
        queuedCard = null;
        queuedFrames = 0;
        noProgressEndTurns = 0;
    }

    /**
     * **唯一**关闭 AUTO 的出口。
     *
     * <p>所有路径（手动按钮、异常、连续卡死、无进展、预计死亡、部署漂移）都走这里。
     */
    private static void stopAuto(String reason) {
        boolean wasEnabled = enabled;
        enabled = false;
        if (wasEnabled) {
            Trace.info("AUTO OFF (" + reason + ")");
        }
    }

    public static void disable() {
        stopAuto("disabled");
    }

    // ------------------------------------- 蓝本的两道停手闸（DeathTurn / LiveRisk）

    /**
     * 这条路线的**预计死亡回合**是否就是当前回合。
     *
     * <p>蓝本 {@code SolverController.cs:2783}：{@code _stopFullAutoOnDeathTurn}
     * （默认开）下，`result.DeathTurn == result.StartTurnNumber` 就**不部署**，
     * 关掉全自动并显示「已暂停执行 / 第 N 回合 预计死亡」。
     * 语义是「照着这条路走，这一回合就死」—— 那就不该继续自动打，
     * 让玩家自己决定（也许他有药水/别的想法）。
     */
    static boolean shouldStopForDeathTurn(int deathTurn, int turnNow) {
        return deathTurn > 0 && turnNow > 0 && deathTurn == turnNow;
    }

    /** 面板：回合末实时风险投影会死（蓝本 {@code StopFullAutoOnLiveRiskDeath}）。 */
    static String liveRiskNotice(int hp, int incoming) {
        return "预计本回合会死 · 已交还控制（hp " + hp + " 对来袭 " + incoming + "）";
    }

    /**
     * 回合末实时风险：现在就结束回合会不会死（蓝本 {@code LiveEndTurnRiskEvaluator}）。
     *
     * <p>蓝本在**结束回合之前**用实机局面重算一次来袭伤害；会死就关掉全自动、交还控制。
     * 我们不需要重跑搜索 —— {@link io.github.youqing.sts1solver.search.Score#projectedHpOf}
     * 就是「hp - max(0, 来袭 - 格挡)」，和蓝本 {@code ProjectedPlayerHp} 同一口径。
     */
    static boolean endTurnWouldKillUs(io.github.youqing.sts1solver.sim.SimState live) {
        if (live == null) {
            return false;
        }
        return io.github.youqing.sts1solver.search.Score.projectedHpOf(live) <= 0;
    }

    /** 面板上的交还控制提示（与蓝本措辞对齐：{@code 已暂停执行 / 第 N 回合 预计死亡}）。 */
    static String deathNotice(int deathTurn) {
        return "已暂停执行 · 第 " + deathTurn + " 回合 预计死亡（当前方案预计本回合死亡，操作权已交还）";
    }

    /**
     * 执行「预计本回合死亡 → 交还控制」：停 AUTO、清计划、写面板提示。
     *
     * @return true = 已停手（调用方不要再执行任何步骤）
     */
    static boolean applyDeathTurnStop(int deathTurn, int turnNow) {
        if (!shouldStopForDeathTurn(deathTurn, turnNow)) {
            return false;
        }
        io.github.youqing.sts1solver.ui.SolverPanel.setNotice(deathNotice(deathTurn));
        clearPlan();
        Trace.warn("FULL_AUTO_STOP reason=death_turn turn=" + deathTurn
                + "  (预计本回合死亡，操作权已交还)");
        stopAuto("death turn T" + deathTurn);
        return true;
    }

    // ---------------------------------------------------------- 药水策略（D5）

    /**
     * 正在**部署**（有一条排好队的路线正在执行）时锁定药水控件。
     *
     * <p>蓝本 {@code SolverController.SetPotionDirective} 只拒绝 {@code _deployment != null}
     * 的修改，其余情况一律走「保存 + 清 continuation + Manual 重搜」。
     * 所以这里锁的是「有执行队列」，不是「AUTO 开关被打开」—— 只开 AUTO 还在等首次搜索时
     * 改策略是合法的（那次搜索会被新的 Manual 搜索取消）。
     */
    public static boolean potionControlsLocked() {
        return enabled && plan != null && !plan.isEmpty();
    }

    /** 上一次策略改动的结果（诊断用）。 */
    public static String lastPolicyAction() {
        return lastPolicyAction;
    }

    /**
     * 一个后台结果能不能采纳：{@code null} = 可以，否则是不采纳的原因。
     *
     * <p>只有一条判据：**局面有没有走**（{@code fingerprint}）。蓝本没有「策略代际」这种东西
     * —— 改策略走的是「保存 → 清 ContinuationSource → Manual 重搜」，而重搜会取消旧搜索
     * （{@code CancelSearch}，对应 {@code AsyncSearch} 的搜索代际），旧答案根本到不了这里。
     */
    static String rejectionReason(String fingerprint, String liveFingerprint) {
        if (liveFingerprint != null && !liveFingerprint.equals(fingerprint)) {
            return "fingerprint";
        }
        return null;
    }

    /**
     * 点一下某一格药水：Smart → Disabled → Force → Smart。
     *
     * <p>越界/空槽/被动药水一律拒绝（面板本就不该给出可点控件，这里是第二道闸）。
     */
    public static boolean cyclePotionDirective(int slot, String potionId) {
        if (potionId == null || potionId.isEmpty()) {
            return false;
        }
        if (potionControlsLocked()) {
            lastPolicyAction = "rejected:executing";
            Trace.info("POTION_POLICY rejected: AUTO is deploying (slot " + slot + ")");
            return false;
        }
        io.github.youqing.sts1solver.search.PotionStrategySnapshot current =
                io.github.youqing.sts1solver.config.SolverConfig.potionStrategy();
        io.github.youqing.sts1solver.search.SolverPotionDirective next =
                io.github.youqing.sts1solver.ui.PotionStrategyRows.cycle(
                        current.directiveFor(slot, potionId));
        return applyPotionPolicy(current.withDirective(slot, potionId, next),
                "slot " + slot + ":" + potionId + " -> " + next);
    }

    /** 套一个预设（只作用于当前存在且可搜索的药水）。 */
    public static boolean applyPotionPreset(
            io.github.youqing.sts1solver.search.SolverPotionPreset preset) {
        if (potionControlsLocked()) {
            lastPolicyAction = "rejected:executing";
            Trace.info("POTION_POLICY rejected preset " + preset + ": AUTO is deploying");
            return false;
        }
        if (preset == null || strategySnapshot == null) {
            lastPolicyAction = "rejected:no_snapshot";
            return false;
        }
        java.util.List<io.github.youqing.sts1solver.search.PotionStrategySnapshot.SlotStatus> slots =
                new java.util.ArrayList<
                        io.github.youqing.sts1solver.search.PotionStrategySnapshot.SlotStatus>();
        for (io.github.youqing.sts1solver.ui.StrategyStatusSnapshot.PotionSlotView view
                : strategySnapshot.potionSlots()) {
            slots.add(new io.github.youqing.sts1solver.search.PotionStrategySnapshot.SlotStatus(
                    view.slot - 1, view.potionId, view.searchable));
        }
        io.github.youqing.sts1solver.search.PotionStrategySnapshot current =
                io.github.youqing.sts1solver.config.SolverConfig.potionStrategy();
        return applyPotionPolicy(current.withPreset(preset, slots), "preset " + preset);
    }

    /**
     * 落一份新策略（蓝本 {@code SolverController.SetPotionDirective}，1899-1942 行）。
     *
     * <p>顺序逐条对应：
     * <ol>
     *   <li>保存设置；</li>
     *   <li>{@code ContinuationSource = null}（旧世界线是按旧策略排的，继续复用等于绕过刚改的设置）；</li>
     *   <li>自动计算关着 → 只记一行 {@code RECALCULATION_SKIPPED}，**不**发起搜索；</li>
     *   <li>否则请求一次 {@code SearchReason.Manual}。</li>
     * </ol>
     *
     * <p>⚠️ 没有「策略代际」：蓝本没有这个字段。旧搜索由重搜本身取消
     * （{@code CancelSearch} → {@code AsyncSearch} 的搜索代际）。
     */
    private static boolean applyPotionPolicy(
            io.github.youqing.sts1solver.search.PotionStrategySnapshot snapshot, String why) {
        io.github.youqing.sts1solver.config.SolverConfig.applyPotionStrategy(snapshot);
        clearContinuationSource();
        if (!io.github.youqing.sts1solver.config.SolverConfig.autoStart()) {
            lastPolicyAction = "saved";
            Trace.info("POTION_DIRECTIVE_RECALCULATION_SKIPPED reason=automatic_calculation_inactive"
                    + " (" + why + ")");
            panelRevision++;
            return true;
        }
        lastPolicyAction = "saved+recompute";
        try {
            if (AbstractDungeon.player != null) {
                submitSearch(AbstractDungeon.player);   // SearchReason.Manual
            }
        } catch (Throwable t) {
            // 不在战斗里（或单测环境）时 AbstractDungeon 还没初始化：只记录，不影响保存
            Trace.warn("potion policy recompute skipped: " + t);
        }
        Trace.info("POTION_DIRECTIVE_CHANGED " + why + " -> manual recompute");
        panelRevision++;
        return true;
    }

    /** 主线程读取真实绝对玩家回合；拿不到返回 0（调用方据此拒绝设置目标）。 */
    public static int currentTurn() {
        try {
            return AbstractDungeon.actionManager == null ? 0 : AbstractDungeon.actionManager.turn;
        } catch (Throwable t) {
            return 0;
        }
    }

    public static void tick() {
        // ① 只展示：把动作落地后的策略现状刷新一遍。即使 AUTO 被手动关闭也要刷完，
        //    否则关掉 AUTO 之后打的牌都不会反映到面板上。它绝不能触发搜索或执行。
        trackScreenTransitions();
        refreshStrategySnapshotIfSettled();
        // ② 执行：AUTO 关着就到此为止
        if (!enabled) {
            return;
        }
        // 回合翻页 = 上一轮的拒绝记录作废（缠身/No Draw 这类限制本来就是按回合的）
        int turnNow = AbstractDungeon.actionManager == null
                ? Integer.MIN_VALUE : AbstractDungeon.actionManager.turn;
        if (turnNow != blockedTurn) {
            blockedTurn = turnNow;
            blockedThisTurn.clear();
            stuckStreak = 0;
            deployDriftsThisTurn = 0;
        }
        if (cooldown > 0) {
            cooldown--;
            return;
        }
        // 已经请求过结束回合：等到回合号翻页，期间什么都不做，
        // 否则会在敌方行动还没走完时再点一次结束，怪物一回合动两次。
        if (waitingForNextTurn()) {
            return;
        }
        if (queuedCard != null) {
            queuedFrames++;
            AbstractPlayer player = AbstractDungeon.player;
            boolean stillInHand = player != null && player.hand != null
                    && player.hand.group.contains(queuedCard);
            if (!stillInHand) {
                queuedCard = null;
                queuedFrames = 0;
            } else if (queuedFrames > 180 && drained()) {
                // 入队三秒还留在手上、且动作队列已空 = 这一手游戏压根没执行。
                // 先只拉黑这一张 —— 因为一张牌打不出去就关掉整场 AUTO 太粗暴（实测缠身那次）。
                String blocked = key(queuedCard);
                blockedThisTurn.add(blocked);
                stuckStreak++;
                Trace.warn("AUTO stuck on " + blocked + " (" + queuedFrames
                        + " frames, same card object still in hand), blocked for this turn"
                        + " [" + stuckStreak + "/" + STUCK_STREAK_LIMIT + "]");
                queuedCard = null;
                queuedFrames = 0;
                if (stuckStreak >= STUCK_STREAK_LIMIT) {
                    Trace.warn("AUTO stopped: " + stuckStreak
                            + " cards in a row were refused by the game this turn");
                    stopAuto("stuck: " + stuckStreak + " refused cards in a row");
                }
                return;
            }
            // 🔴 这张牌还在手上 = 游戏还没消费掉这次入队请求。
            // 少了这个 return，下一帧会再入队**同一个卡牌对象**，实机于是打两张、
            // 影子只记一张，对拍就出现 cards=N/N-1、energy 2 vs 1 那一整片假失配。
            if (queuedCard != null) {
                return;
            }
        }
        try {
            AbstractPlayer player = AbstractDungeon.player;
            AbstractRoom room = AbstractDungeon.getCurrRoom();
            if (player == null || room == null || room.phase != AbstractRoom.RoomPhase.COMBAT) {
                return;
            }
            // 关键：一次只打一张。差分台架还在核对上一张时就出下一张，
            // 会让 baseline 与实机错位（实测 hand 9 vs 8、energy 2 vs 1 这类连环差异）。
            if (!ShadowTracker.isIdle()) {
                return;
            }
            if (player.isEndingTurn || player.endTurnQueued
                    || AbstractDungeon.actionManager.turnHasEnded || !drained()) {
                return;
            }
            // 任何界面（选牌网格、牌堆浏览、地图…）打开时都不插手
            if (AbstractDungeon.isScreenUp) {
                return;
            }
            // 手牌为空有两种情况：① 首轮发牌还没完成；② 真的没牌了（全消耗光/牌堆空）。
            // 前者要等，后者必须结束回合，否则直接卡死（实测：4 能量 0 手牌）。
            if (player.hand == null || player.hand.size() == 0) {
                if (!CombatCapture.isCaptured()) {
                    return;
                }
                noProgressEndTurns++;
                if (noProgressEndTurns > NO_PROGRESS_LIMIT) {
                    Trace.warn("AUTO stopped: " + noProgressEndTurns + " turns in a row with an empty hand"
                            + " (draw=" + player.drawPile.size()
                            + " discard=" + player.discardPile.size()
                            + " exhaust=" + player.exhaustPile.size()
                            + ") — the fight cannot progress, automatic play disabled");
                    stopAuto("no progress: empty hand for " + noProgressEndTurns + " turns");
                    return;
                }
                Trace.info("AUTO end turn (empty hand #" + noProgressEndTurns
                        + ", energy=" + EnergyPanel.totalCount
                        + ", draw=" + player.drawPile.size()
                        + ", discard=" + player.discardPile.size()
                        + ", handSize=" + player.gameHandSize
                        + ", noDraw=" + player.hasPower("No Draw")
                        + ", turn=" + AbstractDungeon.actionManager.turn + ")");
                requestEndTurn(player);
                cooldown = 45;
                return;
            }
            // 玩家正在手动选牌/拖动时让位，避免两边同时入队
            if (player.hoveredCard != null || player.isDraggingCard) {
                return;
            }

            AbstractMonster target = firstAliveMonster();
            if (target == null) {
                return;
            }
            int outcome = playOneCard(player, target);
            if (outcome == OUTCOME_WAITING) {
                return;      // 后台还在算，这一帧什么都不做
            }
            if (outcome == OUTCOME_END_TURN) {
                // 留了能量还结束回合 = 「白白挨打」的直接来源。正常收尾（能量打光）不报，
                // 只有还剩能量才报，方便下次拿日志判断是搜索判断失误还是又被拒了。
                int leftover = EnergyPanel.totalCount;
                if (leftover > 0) {
                    Trace.warn("AUTO end turn with " + leftover + " energy left  "
                            + describeHand(player));
                } else {
                    Trace.info("AUTO end turn (search says end)  " + describeHand(player));
                }
                if (stopIfEndTurnWouldKill()) {
                    return;
                }
                requestEndTurn(player);
                cooldown = 30;
                return;
            }
            if (outcome == OUTCOME_NOTHING) {
                noProgressEndTurns++;
                if (noProgressEndTurns > NO_PROGRESS_LIMIT) {
                    Trace.warn("AUTO stopped: " + noProgressEndTurns
                            + " turns in a row with nothing playable — automatic play disabled");
                    stopAuto("no progress: nothing playable for " + noProgressEndTurns + " turns");
                    return;
                }
                Trace.info("AUTO end turn (nothing playable)  " + describeHand(player));
                if (stopIfEndTurnWouldKill()) {
                    return;
                }
                requestEndTurn(player);
                cooldown = 30;
            }
        } catch (Throwable t) {
            Trace.error("auto pilot failed", t);
            stopAuto("exception: " + t);
        }
    }

    /**
     * 结束回合前的最后一道闸（蓝本 {@code LiveEndTurnRiskEvaluator}）：
     * 实机局面投影会死就**不结束回合**，直接交还控制。
     *
     * <p>为什么要拦：搜索的「结束回合」是在推演里判的，实机此刻的来袭伤害可能已经不同
     * （意图、力量、虚弱都会改口）。蓝本因此在**真的结束回合之前**用实机局面重算一次。
     * 这一枪是**不花搜索预算**的安全网。
     */
    private static boolean stopIfEndTurnWouldKill() {
        try {
            io.github.youqing.sts1solver.sim.SimState live =
                    io.github.youqing.sts1solver.sim.SimState.capture();
            if (!endTurnWouldKillUs(live)) {
                return false;
            }
            int incoming = 0;
            for (io.github.youqing.sts1solver.sim.SimMonster m : live.monsters) {
                if (m.hp > 0 && m.intentDmg > 0) {
                    incoming += m.intentDmg;
                }
            }
            io.github.youqing.sts1solver.ui.SolverPanel.setNotice(
                    liveRiskNotice(live.hp, incoming));
            Trace.warn("FULL_AUTO_STOP reason=live_risk_death hp=" + live.hp
                    + " block=" + live.block + " incoming=" + incoming
                    + "  (结束回合会死，交还控制)");
            stopAuto("live end-turn risk: death");
            return true;
        } catch (Throwable t) {
            Trace.warn("live end-turn risk check failed: " + t);
            return false;                 // 判不了就别拦，保持原行为
        }
    }

    /** 出牌决定的三态。 */
    static final int OUTCOME_PLAYED = 0;
    static final int OUTCOME_WAITING = 1;
    static final int OUTCOME_NOTHING = 2;
    static final int OUTCOME_END_TURN = 3;
    /** 计划里的这一步执行不了（手牌对不上/被禁）→ 丢掉计划重算。 */
    static final int OUTCOME_REPLAN = 4;

    /**
     * 当前正在执行的计划（蓝本的「路线」）。
     *
     * <p>⚠️ **只信任本回合的那部分**：路线是跨回合算出来的，但影子对「下一回合抽到什么」
     * 只是预测。实测 2026-10-23：抽牌堆空了模型不知道洗回，第 3 回合起手牌永远为空，
     * 路线后半段全是「结束回合」，执行层就跟着空过，AI 站着被打死。
     * 所以回合翻页就丢掉旧计划重算 —— 蓝本同样是在回合开始时（PlayerTurnSetup）重新规划。
     */
    private static java.util.ArrayDeque<io.github.youqing.sts1solver.search.RouteStep> plan;
    /** 计划是按哪一回合的局面算的；回合变了就作废。 */
    private static int planTurn = -1;
    /**
     * 采纳这份计划时的失配序号。执行每一步前比一下：序号涨了 = 影子已经和实机分叉，
     * 计划里的「下一手」不再是从当前真局面算出来的 → 丢掉重算。
     *
     * <p>实例（2026-10-30 大块头那一场）：计划是在「腐化让技能免费」的影子局面下排的，
     * 实机连着几个失配之后能量/手牌都对不上，但计划里还剩一个 END_TURN ——
     * 自动驾驶照打，于是**留着 1 点能量和一张顺劈斩结束回合**，白挨一轮打。
     */
    private static int planMismatchSeq = -1;
    /** 上一次规划的统计，给面板显示（蓝本也有「已查阅 N 条世界线 / 耗时」这一行）。 */
    private static String planSummary = "";
    /**
     * 这份计划的**完整**预览（含已经打过的步骤），显示用的绝对回合号从这里来。
     *
     * <p>🔴 与 {@link #plan} 是两件事：{@code plan} 是被执行的剩余队列（会 {@code poll} 到空），
     * 预览是「这份路线本来长什么样」，出牌时<b>不重建</b>，只在整份计划被替换或作废时清空。
     * 否则每出一手后续回合都会重排编号，用户看到的 T2 下一秒就变成另一件事。
     */
    private static RoutePreview preview;
    /**
     * 预览是不是**上一份路线**（正在重算）。
     *
     * <p>🔴 回合翻页时以前把 {@code preview} 直接置空，面板于是先变成「planning…」再变回路线 ——
     * 用户看到的就是「每回合路线闪一下」。现在保留旧预览并标成陈旧，等新结果原地替换。
     */
    private static boolean previewStale;
    /**
     * 采纳这条路线时的**完整**步骤（含已经打过的回合）。
     *
     * <p>续跑命中时要从这里按 {@code forecastOffset} 重新裁执行队列 —— 只留 {@code plan}
     * 那个被 poll 过的队列是裁不出来的（它已经不包含本回合之前的步骤）。
     */
    private static java.util.List<io.github.youqing.sts1solver.search.RouteStep> planSteps =
            java.util.Collections.emptyList();

    /**
     * 跨回合续跑判定（纯数据）。命中 = 这个回合不搜索，直接按上一回合算出的世界线打。
     *
     * <p>缓存的生命周期跟着 {@link #clearPlan()} 走：本回合计划一失效，那条世界线就不再可信。
     */
    private static final ContinuationDecision continuation = new ContinuationDecision();
    /**
     * 面板内容版本号：任何会改变行集合/行文本的变化都递增。
     *
     * <p>面板每帧比一个 {@code long} 就知道要不要重排，不用去解析计划队列
     * （旧实现每帧调 {@code planLines()} 新建一堆字符串）。
     */
    private static long panelRevision;
    /** 等规划结果的帧数；超时就退回逐张兜底，避免整场卡在等搜索上。 */
    private static int framesWaitingForPlan;
    private static final int PLAN_WAIT_LIMIT = 1800;   // 30 秒
    /**
     * 本次搜索是什么时候提交的（{@code System.currentTimeMillis()}）。
     *
     * <p>用途只有一个：面板上把「正在算 3.2 秒」显示出来。开了宽度组合 + 新颖度之后
     * 单步要算 1~5 秒，只写 "planning…" 会被当成卡死（用户 2026-10-30 就报过一次「是不是卡住了」）。
     */
    private static long searchStartedAt;
    /** 上一次搜索的形状，给面板和日志用：{@code 135+band / 26.7k / 642ms}。 */
    private static String lastSearchShape = "";
    /** 药水策略只在第一次提交时打一行，避免每次搜索刷屏。 */
    private static boolean potionPolicyLogged;
    /** 上一次策略改动的结果：{@code saved} / {@code saved+recompute} / {@code rejected:executing}。 */
    private static String lastPolicyAction = "";
    /** 最近一次策略现状快照（预设要按当前槽位算，不能靠渲染路径现抓）。 */
    private static io.github.youqing.sts1solver.ui.StrategyStatusSnapshot strategySnapshot;

    /**
     * 「策略现状」缓存是否已经过时（打牌 / 喝药 / 选牌完成后置 true）。
     *
     * <p>渲染路径不捕获 —— {@code SimState.capture()} 要扫四个牌堆 + 能力 + 遗物，
     * 每帧调用直接吃帧率。所以用脏标记把刷新收敛到「动作稳定后」一次。
     */
    private static boolean strategySnapshotDirty;
    /** 上一帧是否开着界面：用于在「界面关闭」那一刻把策略快照标脏。 */
    private static boolean screenWasUp;

    /**
     * 已经报过「没有镜像」的牌名（每场战斗清空）。
     *
     * <p>没镜像的牌在搜索里等于**不存在**：{@code CardPlayer.play} 会抛 {@code ActionRejected}，
     * 分支被静默跳过。结果就是「手里明明有牌，AI 却结束回合」。
     * 2026-10-23 真机踩过（Body Slam+ / Dark Embrace+ / Entrench+ 三张升级态没注册），
     * 当时只能靠翻日志猜。现在每场战斗把这类牌报一次，直接暴露覆盖缺口。
     */
    private static final java.util.Set<String> reportedMissingMirrors =
            new java.util.HashSet<String>();

    /**
     * 先打未镜像的牌（探索盲区），没有可探索的再打已镜像的（产出对拍）。
     *
     * <p>搜索现在跑在后台线程（{@link io.github.youqing.sts1solver.search.AsyncSearch}）：
     * 提交之后这一帧返回 {@link #OUTCOME_WAITING}，等结果回来再执行。
     * 这是为了把「时间预算」真正用起来 —— 蓝本是几百万条世界线，主线程给不了那个时间。
     */
    /** 手牌里没有镜像的牌，每场战斗各报一次（这类牌搜索根本看不见）。 */
    private static void reportMissingMirrors(AbstractPlayer player) {
        if (player == null || player.hand == null || player.hand.group == null) {
            return;
        }
        for (AbstractCard card : player.hand.group) {
            if (card == null) {
                continue;
            }
            String k = key(card);
            if (io.github.youqing.sts1solver.mirror.MirrorRegistry.supports(k)) {
                continue;
            }
            if (reportedMissingMirrors.add(k)) {
                Trace.warn("MIRROR MISS: " + k + " 没有镜像 -> 搜索会把这张牌当成打不出");
            }
        }
    }

    /**
     * 每回合开头把真局面落一份快照，供离线复现。
     *
     * <p>为什么值得写盘：每次「这把打臭了」的复盘都卡在同一件事 —— 日志只说「打了什么」，
     * 说不出「当时怪物多少血、意图多少伤害、手里还有哪些牌」。有了这份快照，
     * 同一个回合可以反复重跑搜索、改完评估再跑一遍，不用再开一局游戏。
     */
    private static void dumpTurnSnapshot(int turn) {
        try {
            io.github.youqing.sts1solver.sim.SimState snap =
                    io.github.youqing.sts1solver.sim.SimState.capture();
            String path = snap.saveAs("turn-A" + snap.act + "F" + snap.floor
                    + "T" + turn + ".json");
            Trace.info("TURN snapshot " + path);
            publishTurnSnapshot(snap, turn);
        } catch (Throwable t) {
            Trace.warn("turn snapshot failed: " + t);
        }
    }

    /**
     * **一次捕获，两处展示**：状态条 + 策略现状区都读同一份 {@code snap}。
     *
     * <p>以前这两行各自 {@code SimState.capture()} 一次，等于每回合开头白扫两遍全场
     * （四个牌堆 + 能力 + 遗物 + 六个 RNG）。合并之后回合边界只捕获一次；
     * 面板渲染路径<b>永远</b>不捕获（{@code SolverPanel.render} 只读缓存行）。
     */
    private static void publishTurnSnapshot(io.github.youqing.sts1solver.sim.SimState snap,
                                            int turn) {
        io.github.youqing.sts1solver.ui.SolverPanel.setTurnLine(turnLineOf(snap, turn));
        strategySnapshot = io.github.youqing.sts1solver.ui.StrategyStatusSnapshot.from(snap);
        io.github.youqing.sts1solver.ui.SolverPanel.setStrategyStatus(strategySnapshot);
    }

    /**
     * 面板状态条：`A1F14 T3 · hp 36/80 e3 · 敌 Lagavulin 94 (意图20)`。
     *
     * <p>只在回合开头算一次（跟快照同一时机），不在渲染里现读 —— 面板每帧都跑，
     * {@code SimState.capture()} 这种扫全场的调用放进去会直接掉帧。
     */
    private static String turnLineOf(io.github.youqing.sts1solver.sim.SimState s, int turn) {
        StringBuilder sb = new StringBuilder();
        sb.append('A').append(s.act).append('F').append(s.floor).append(" T").append(turn)
          .append(" · hp ").append(s.hp).append('/').append(s.maxHp)
          .append(" · e").append(s.energy);
        int shown = 0;
        for (io.github.youqing.sts1solver.sim.SimMonster m : s.monsters) {
            if (m.hp <= 0) {
                continue;
            }
            if (shown++ >= 3) {
                sb.append(" +");
                break;
            }
            sb.append(" · ").append(m.type).append(' ').append(m.hp);
            if (m.intentAttack && m.intentDmg > 0) {
                sb.append(" (意图").append(m.intentDmg).append(')');
            } else if (m.powers.containsKey("Anger") || m.powers.containsKey("Ritual")) {
                sb.append(" (成长)");
            }
        }
        String shape = lastSearchShape;
        if (shape != null && !shape.isEmpty()) {
            sb.append(" · ").append(shape);
        }
        return sb.toString();
    }

    /**
     * 回合内动作落地后，把策略现状刷新的时机判断。
     *
     * <p>「只在回合初刷」会让药水和遗物计数整回合陈旧（喝了药、Shuriken 转了 3 次都还显示旧值），
     * 但「每帧刷」要每帧扫全场。所以折中：<b>只在动作真正稳定后捕获一次</b>。
     *
     * <p>⚠️ 这里绝不能做任何决策或执行 —— 即使 AUTO 已被手动关闭，它也只负责把面板
     * 上的事实刷新一遍（见 {@link #tick()} 的调用顺序）。
     */
    private static void refreshStrategySnapshotIfSettled() {
        if (!strategySnapshotDirty) {
            return;
        }
        try {
            if (!CombatCapture.isCaptured()) {
                return;
            }
            AbstractRoom room = AbstractDungeon.getCurrRoom();
            if (room == null || room.phase != AbstractRoom.RoomPhase.COMBAT) {
                return;
            }
            // 回合切换期间（endPhase != 0）真局面还没定下来，等它走完
            if (endPhase != 0) {
                return;
            }
            if (!ShadowTracker.isIdle() || !drained()) {
                return;
            }
            // 任何界面（选牌网格 / 手牌选择 / 三选一奖励 / 牌堆浏览…）开着都不刷：
            // 此刻「确定的选择」还没落地，刷出来的是中间态。
            if (AbstractDungeon.isScreenUp
                    || AbstractDungeon.screen != AbstractDungeon.CurrentScreen.NONE) {
                return;
            }
            AbstractPlayer player = AbstractDungeon.player;
            if (player == null || player.isEndingTurn || player.endTurnQueued
                    || AbstractDungeon.actionManager.turnHasEnded) {
                return;
            }
            // 先清 dirty 再捕获：万一 capture 抛异常，也不能变成每帧重试
            strategySnapshotDirty = false;
            io.github.youqing.sts1solver.sim.SimState snap =
                    io.github.youqing.sts1solver.sim.SimState.capture();
            strategySnapshot = io.github.youqing.sts1solver.ui.StrategyStatusSnapshot.from(snap);
            io.github.youqing.sts1solver.ui.SolverPanel.setStrategyStatus(strategySnapshot);
            Trace.info("STRATEGY snapshot refreshed mid-turn  T" + snap.turn
                    + " hp " + snap.hp + "/" + snap.maxHp
                    + " potions " + snap.potions.size() + "/" + snap.potionSlots
                    + " energy " + snap.energy);
        } catch (Throwable t) {
            Trace.warn("strategy snapshot refresh failed: " + t);
        }
    }

    /**
     * 界面开→关 = 一次「选择完成」。在 {@code AutoPilot} 內部检测，不用去改
     * {@code GridSelector} / {@code HandSelectDriver}（那两个不属本刀改动范围）。
     *
     * <p>为什么需要它：选牌完成（军备升级、双持复制、三选一发现）会改手牌和费用，
     * 但这些动作不是「入队一张牌」，光靠 {@link #enqueue} 那条路捕不到。
     */
    private static void trackScreenTransitions() {
        boolean up = AbstractDungeon.isScreenUp
                || AbstractDungeon.screen != AbstractDungeon.CurrentScreen.NONE;
        if (screenWasUp && !up) {
            strategySnapshotDirty = true;
        }
        screenWasUp = up;
    }

    private static int playOneCard(AbstractPlayer player, AbstractMonster attackTarget) {
        reportMissingMirrors(player);
        // ① 回合翻页 = 旧计划作废（跨回合的抽牌只是预测，不能硬套）
        int turnNow = AbstractDungeon.actionManager == null
                ? planTurn : AbstractDungeon.actionManager.turn;
        if (plan != null && !plan.isEmpty() && turnNow != planTurn) {
            // 🔴 每回合一行「剩多少血」：没有这行，「白吃多少战损」既不可量化也不可归因 ——
            // 2026-10-30 用户两次抱怨「白吃战损」，我们能拿出的只有出牌记录，拼不出血量曲线。
            Trace.info("TURN " + turnNow + " start  hp " + player.currentHealth + "/"
                    + player.maxHealth + "  e" + player.energy.energy + "  hand "
                    + player.hand.size() + "  foes " + aliveMonsters());
            // 🔴 先试**精确续跑**（蓝本 ContinuationSource）：真机状态与上一回合预测逐字段相等
            // 就沿原世界线继续，不搜索。这一步以前是「无条件 clearPlan()」——
            // 于是每回合都重算，搜索推演出来的后续回合**从来没被用上第二次**，
            // 用户看到的就是「世界线完全没被展开，路线每回合都变」。
            // 🔴 顺序固定：**先校验，再出牌**。这一步在 playOneCard 的最前面，
            // 任何一张牌入队之前就已经跑完 —— 不存在「打完这一回合再看对不对」。
            int planTurnBefore = planTurn;
            if (!onTurnStart(turnNow, liveStampOrNull(),
                    ShadowTracker.mismatchSequence())) {
                Trace.info("PLAN dropped: turn " + planTurnBefore + " -> " + turnNow
                        + ", replanning from root");
            }
            dumpTurnSnapshot(turnNow);
        }
        // ② 有计划就照计划走（本回合的部署）
        if (plan != null && !plan.isEmpty()) {
            int outcome = executePlannedStep(player, attackTarget);
            if (outcome != OUTCOME_REPLAN) {
                return outcome;
            }
            // 部署漂移：{@link #deploymentDrift} 已经把整条队列作废并交了 REPLAN，
            // 下一帧走 ③ 提交一次根搜索（蓝本 SearchReason.DeploymentDrift 同义）。
            return OUTCOME_WAITING;
        }

        // ③ 没有计划：等后台整场规划的结果，没有就提交一次
        io.github.youqing.sts1solver.search.AsyncSearch.Result result = pollValidated();
        if (result == null) {
            if (!io.github.youqing.sts1solver.search.AsyncSearch.busy()) {
                submitSearch(player);
            }
            framesWaitingForPlan++;
            if (framesWaitingForPlan > PLAN_WAIT_LIMIT) {
                // 规划迟迟不出来（搜索挂了 / 预算被设得离谱大）：退回逐张兜底，别整场卡死
                Trace.warn("PLAN wait timeout (" + framesWaitingForPlan
                        + " frames) -> falling back to per-card play");
                framesWaitingForPlan = 0;
                return fallbackPlay(player, attackTarget);
            }
            return OUTCOME_WAITING;
        }
        framesWaitingForPlan = 0;

        // 蓝本顺序：先判「这一回合就死」→ 不部署（StopFullAutoOnDeathTurn）；再判失败/空计划。
        // 注意：`Survives` **不是**停手条件 —— 它在蓝本里只是最终排序的一个键
        // （FinalPlanOrdering.cs:250-252），没有任何「没有生还路线就禁止出牌」的机制。
        if (applyDeathTurnStop(result.deathTurn, turnNow)) {
            return OUTCOME_WAITING;
        }
        if (result.failed) {
            // 🔴 搜索崩了 ≠ 这一回合没事可做。以前两种都当「空计划」处理，
            // 结果就是满手牌满能量直接结束回合、白挨一轮打（2026-10-30 实测两次：
            // Mayhem 在回合初自动打牌时目标取了 -1，异常穿透整个后台搜索）。
            Trace.warn("SEARCH failed -> per-card fallback (not ending the turn)");
            return fallbackPlay(player, attackTarget);
        }

        searchCount++;
        searchStartedAt = 0;
        searchNodesTotal += result.nodes;
        lastSearchShape = (result.portfolioAdopted.isEmpty() ? "beam" : result.portfolioAdopted)
                + " / " + (result.nodes / 1000) + "k / " + result.millis + "ms"
                + (result.noveltyStop == null || "off".equals(result.noveltyStop)
                        ? "" : " / nov " + result.noveltyStop);
        if (result.millis > 2000) {
            Trace.warn("SEARCH slow: " + result.millis + " ms, " + result.nodes + " nodes, "
                    + "port=" + result.portfolioAdopted + " novelty=" + result.noveltyStop);
        }
        if (searchCount % 5 == 0) {
            Trace.info("SEARCH #" + searchCount + " avgNodes=" + (searchNodesTotal / searchCount)
                    + " depth=" + result.depth + " lines=" + result.lines
                    + " ms=" + result.millis + " plan=" + result.steps.size() + " steps"
                    + " states=" + result.states
                    + " budget=" + (result.hitNodeLimit ? "hit" : "exhausted")
                    + " novelty=" + result.noveltyStop
                    + " port=" + result.portfolioAdopted);
        }
        if (result.steps == null || result.steps.isEmpty()) {
            Trace.info("SEARCH -> empty plan, end turn");
            return OUTCOME_END_TURN;
        }
        io.github.youqing.sts1solver.ui.SolverPanel.setNotice("");
        installRoute(result.steps, turnNow, result.continuations,
                ShadowTracker.mismatchSequence());
        planSummary = summarizePlan(result);
        if (!lastSearchShape.isEmpty()) {
            // 面板第一行留一格「刚刚怎么算出来的」：135+base / 26k / 642ms
            planSummary = lastSearchShape + "   " + planSummary;
        }
        // 预算用量 + **停止原因**：horizon = 把整场战斗推演完了（最常见，节点用量很低是正常的），
        // node_limit / time_limit 才是被预算掐断。以前只印 "tree exhausted"，
        // 被读成「剪枝太狠」，据此得出过「加预算无效」的错误结论（2026-11-01 已用 123 个真机快照纠正）。
        Trace.info("PLAN ready: " + result.steps.size() + " steps, depth " + result.depth
                + " turns, " + result.nodes + "/" + result.nodeLimit + " nodes ("
                + (result.nodeLimit <= 0 ? "unlimited"
                    : (result.nodes * 100 / Math.max(1, result.nodeLimit)) + "%")
                + ", stop=" + (result.stopReason.isEmpty()
                    ? (result.hitNodeLimit ? "node_limit" : "?") : result.stopReason)
                + ") / " + result.states + " states, " + result.millis + " ms"
                + "  port=" + (result.portfolioAdopted.isEmpty() ? "off" : result.portfolioAdopted)
                + " novelty=" + result.noveltyStop);
        // 完整步骤：日志里唯一能解释「为什么先格挡/为什么结束回合」的证据。
        // 只打前 40 步，避免长路线把日志刷爆。
        StringBuilder planText = new StringBuilder();
        int shown = 0;
        for (io.github.youqing.sts1solver.search.RouteStep step : result.steps) {
            if (shown++ >= 40) {
                planText.append(" ...");
                break;
            }
            if (step.potion) {
                planText.append("POTION:").append(step.cardKey);
            } else {
                planText.append(step.cardKey == null ? "END_TURN"
                        : RoutePreview.shortName(step.cardKey));
            }
            if (shown < result.steps.size() && shown < 40) {
                planText.append(" | ");
            }
        }
        Trace.info("PLAN steps: " + planText);

        int outcome = executePlannedStep(player, attackTarget);
        return outcome == OUTCOME_REPLAN ? OUTCOME_WAITING : outcome;
    }

    /**
     * 执行计划里的下一步。
     *
     * <p>计划是「牌名 + 目标下标」，不是手牌下标 —— 中间隔了几帧，下标可能已经变了。
     * 找不到这张牌就返回 {@link #OUTCOME_REPLAN}，让上层重算而不是照着旧下标乱打。
     */
    private static int executePlannedStep(AbstractPlayer player, AbstractMonster attackTarget) {
        io.github.youqing.sts1solver.search.RouteStep step = plan.peek();
        if (step == null) {
            return OUTCOME_REPLAN;
        }
        // 影子已经和实机分叉（这回合有牌对不上）→ 这份计划剩下的步骤作废。
        // 蓝本 DeploymentDrift 同义：清 continuation source、结束部署、从根重搜。
        if (ShadowTracker.mismatchSequence() != planMismatchSeq) {
            int drift = ShadowTracker.mismatchSequence() - planMismatchSeq;
            return deploymentDrift(step, drift + " mirror mismatch(es) since it was computed");
        }
        if (step.endTurn) {
            plan.poll();
            panelRevision++;      // 下一手箭头要移动
            return OUTCOME_END_TURN;
        }
        if (step.potion) {
            return executePotionStep(player, step, attackTarget);
        }
        AbstractCard chosen = firstByKey(player, step.cardKey);
        if (chosen == null) {
            return deploymentDrift(step, "is not in hand");
        }
        if (blockedThisTurn.contains(step.cardKey)) {
            return deploymentDrift(step, "is blocked this turn");
        }
        AbstractMonster target = monsterAt(step.targetIndex, attackTarget);
        if (!chosen.canUse(player, target)) {
            return deploymentDrift(step, "not playable now");
        }
        chosenTargetIndex = step.targetIndex;
        pendingChoiceIndex = step.choiceIndex;
        enqueue(chosen, target);
        plan.poll();
        panelRevision++;      // 下一手箭头要移动
        return OUTCOME_PLAYED;
    }

    /**
     * 执行计划里的「喝药」一步。
     *
     * <p>药水不是手牌，必须走 {@code AbstractPotion.use(target)}（游戏自己的入口，
     * 和药水弹窗点「使用」是同一条路），随后 {@code removePotion} 把槽位换回空槽。
     * 缺了这段的话搜索给出的「先喝火药水」这类路线永远执行不了。
     */
    private static int executePotionStep(AbstractPlayer player, RouteStep step,
                                         AbstractMonster attackTarget) {
        if (player.potions == null) {
            return deploymentDrift(step, "has no potion belt");
        }
        // 先按 (slot, id) 精确匹配：两瓶同 ID 时只按名字找会喝错格，
        // 而用户设的 Force/Disabled 锁的正是「哪一格」。
        com.megacrit.cardcrawl.potions.AbstractPotion potion = null;
        if (step.potionSlot >= 0) {
            for (com.megacrit.cardcrawl.potions.AbstractPotion p : player.potions) {
                if (p != null && p.slot == step.potionSlot
                        && step.matchesPotionAt(p.slot, p.ID)) {
                    potion = p;
                    break;
                }
            }
            if (potion == null) {
                return deploymentDrift(step, "slot " + step.potionSlot + " no longer holds that potion");
            }
        } else {
            for (com.megacrit.cardcrawl.potions.AbstractPotion p : player.potions) {
                if (p != null && step.cardKey != null && step.cardKey.equals(p.ID)) {
                    potion = p;
                    break;
                }
            }
            if (potion == null) {
                return deploymentDrift(step, "is not in the belt");
            }
        }
        if (!potion.canUse()) {
            return deploymentDrift(step, "cannot be used now");
        }
        AbstractMonster target = potion.targetRequired
                ? monsterAt(step.targetIndex, attackTarget) : null;
        if (potion.targetRequired && target == null) {
            return deploymentDrift(step, "has no valid target");
        }
        // 药水引发的选牌（液态记忆 / 三选一发现）先记下搜索挑的下标，
        // 网格驱动会用它，而不是自己挑第一张。
        pendingChoiceIndex = step.choiceIndex;
        int slot = potion.slot;
        try {
            potion.use(target);
        } catch (Throwable t) {
            Trace.error("AUTO potion " + step.cardKey + " failed", t);
            return deploymentDrift(step, "threw on use");
        }
        player.removePotion(potion);
        player.adjustPotionPositions();
        // 药水槽、能力、血量都可能被这一瓶改掉 → 面板的策略现状要重算
        strategySnapshotDirty = true;
        // 药水效果不在对拍台架的跟踪范围里（台架只跟「打牌」）：作废基线，
        // 否则下一张牌的影子还停在喝药前，差出来的正是药水抽的牌/打的伤害。
        ShadowTracker.invalidateBaseline("potion " + step.cardKey);
        // 🔴 喝完药**整份计划作废**：三选一/液态记忆这类药水给哪张牌是游戏现掷的，
        // 计划是在「不知道给什么」的前提下排的。2026-10-30 真机那一把，攻击药水给了
        // 一张本回合 0 费的顺劈斩，计划却还按原样走，最后**留着它结束回合**，
        // 白丢 9 点伤害 + 一次抽牌，接着被大块头反打 26。
        clearPlan();
        noProgressEndTurns = 0;
        queuedCard = null;
        queuedFrames = 0;
        Trace.info("AUTO potion " + step.cardKey + " (slot " + slot + ")"
                + (target == null ? " -> self" : " -> " + target.name));
        cooldown = 24;
        return OUTCOME_PLAYED;
    }

    /** 后台结果的取用：指纹对不上就作废（局面已经被别的东西改过）。 */
    private static io.github.youqing.sts1solver.search.AsyncSearch.Result pollValidated() {
        io.github.youqing.sts1solver.search.AsyncSearch.Result result =
                io.github.youqing.sts1solver.search.AsyncSearch.poll();
        if (result == null) {
            return null;
        }
        String reason = rejectionReason(result.fingerprint, currentFingerprint());
        if (reason != null) {
            Trace.info("PLAN discarded (state moved on since it was computed)");
            return null;
        }
        return result;
    }

    /** 规划等不到时的兜底：按 prefer 顺序挑一张能打的。 */
    private static int fallbackPlay(AbstractPlayer player, AbstractMonster attackTarget) {
        ArrayList<AbstractCard> hand = playableHand(player);
        AbstractCard chosen = firstPlayable(player, hand, attackTarget, Pref.UNMIRRORED);
        if (chosen == null) {
            chosen = firstPlayable(player, hand, attackTarget, Pref.MIRRORED);
        }
        if (chosen == null) {
            return OUTCOME_NOTHING;
        }
        AbstractMonster target = resolveTarget(chosen, pickTarget(attackTarget));
        enqueue(chosen, target);
        return OUTCOME_PLAYED;
    }

    private static AbstractCard firstByKey(AbstractPlayer player, String cardKey) {
        if (cardKey == null) {
            return null;
        }
        for (AbstractCard card : player.hand.group) {
            if (card != null && key(card).equals(cardKey)) {
                return card;
            }
        }
        return null;
    }

    /** 影子里的怪物下标 → 真机怪物；下标失效就退回默认目标。 */
    private static AbstractMonster monsterAt(int index, AbstractMonster fallback) {
        try {
            java.util.ArrayList<AbstractMonster> monsters =
                    AbstractDungeon.getMonsters().monsters;
            if (index >= 0 && index < monsters.size()) {
                AbstractMonster picked = monsters.get(index);
                if (picked != null && picked.currentHealth > 0) {
                    return picked;
                }
            }
        } catch (Throwable ignored) {
            // 结构异常就退回默认目标
        }
        return fallback;
    }

    private static void enqueue(AbstractCard chosen, AbstractMonster target) {
        boolean mirrored = MirrorRegistry.supports(key(chosen));
        // 🔴 X 费牌（旋风斩 cost = -1）必须显式传「当前能量」而不是 -1。
        // 传 -1 时全靠 AbstractPlayer.useCard 里那句
        //   if (cost == -1 && energyOnUse < EnergyPanel.totalCount) energyOnUse = totalCount
        // 兜底；一旦 ignoreEnergyOnUse 被置位（自动打出过的牌对象会带这个标记），
        // 这句就跳过 -> WhirlwindAction 收到 -1/0 -> 一次伤害都不结算、能量也不扣。
        int energyOnUse = chosen.cost < 0
                ? Math.max(0, EnergyPanel.totalCount) : chosen.cost;
        AbstractDungeon.actionManager.cardQueue.add(
                new CardQueueItem(chosen, target, energyOnUse));
        queuedCard = chosen;
        queuedFrames = 0;
        noProgressEndTurns = 0;
        // 有进展了：本回合的漂移计数归零（见 deploymentDrift 的失败路径保护）
        deployDriftsThisTurn = 0;
        // 这一手落地后局面就变了（手牌/能量/能力），面板的策略现状要重算
        strategySnapshotDirty = true;
        Trace.info("AUTO play " + key(chosen) + (target == null ? "" : " -> " + target.name)
                + (chosen.cost < 0 ? "  [X cost: energyOnUse=" + energyOnUse
                    + " total=" + EnergyPanel.totalCount + "]" : "")
                + (mirrored ? "  (mirrored, diff)" : "  (unmirrored, record)"));
        cooldown = 6;
    }

    /** 一行路线统计，照蓝本的「已查阅 N 条世界线（M 条/s）」的意思来。 */
    private static String summarizePlan(io.github.youqing.sts1solver.search.AsyncSearch.Result r) {
        int turns = 0;
        for (io.github.youqing.sts1solver.search.RouteStep step : r.steps) {
            if (step.endTurn) {
                turns++;
            }
        }
        long perSecond = r.millis <= 0 ? r.nodes : r.nodes * 1000L / r.millis;
        return "route " + turns + " turns · " + r.steps.size() + " steps · "
                + r.nodes + " lines (" + perSecond + "/s) · " + r.millis + " ms";
    }

    /** 面板用的路线统计行；还没规划时返回 null。 */
    public static String planSummaryLine() {
        return planSummary;
    }

    /** 场上还活着的敌人数（日志用）。 */
    private static int aliveMonsters() {
        if (AbstractDungeon.getCurrRoom() == null || AbstractDungeon.getCurrRoom().monsters == null) {
            return 0;
        }
        int alive = 0;
        for (AbstractMonster m : AbstractDungeon.getCurrRoom().monsters.monsters) {
            if (m != null && !m.isDead && !m.escaped) {
                alive++;
            }
        }
        return alive;
    }

    /** 正在等搜索结果就返回已等的毫秒数，否则 -1（面板用来区分「在算」和「卡死」）。 */
    public static long searchElapsedMillis() {
        if (searchStartedAt <= 0) {
            return -1L;
        }
        return System.currentTimeMillis() - searchStartedAt;
    }

    /** 上一次搜索的形状，例如 {@code 135+band / 26k / 642ms}。 */
    public static String lastSearchShape() {
        return lastSearchShape;
    }


    /**
     * 这份计划的**完整**预览（含已经执行过的步骤）；没有计划时为 {@code null}。
     *
     * <p>面板只读它来画路线行：行号是绝对回合，行身份是 {@link RoutePreview.Turn}，
     * 不再从剩余队列现场编号（那会把第 4 回合写成 T1）。
     */
    public static RoutePreview routePreview() {
        return preview;
    }

    /**
     * 面板上那份路线是不是**上一份**（正在重算）。
     *
     * <p>回合翻页命中续跑时不清预览；miss 时把预览标成陈旧（面板原地变灰 + 「重算中」），
     * 这样路线不会消失再出现（用户实测「每回合闪一下」）。
     * 注意「标成陈旧」只是显示层的过渡，执行的队列必须真的清空 —— 两者不能混为一谈。
     */
    public static boolean routePreviewStale() {
        return previewStale;
    }

    /** 面板内容版本号：变了就要重排行（比每帧新建字符串便宜得多）。 */
    public static long panelRevision() {
        return panelRevision;
    }

    /**
     * 执行层下一手（{@code plan.peek()}），供预览画箭头。
     *
     * <p>只读：返回的是步骤对象本身，不暴露 {@code ArrayDeque} ——
     * 谁拿到队列谁就能把它消费掉，显示层不该有这个能力。
     */
    public static RouteStep nextRouteStep() {
        return plan == null ? null : plan.peek();
    }

    /**
     * 只作废**跨回合续用缓存**，保留本回合的执行队列（蓝本 {@code _combat.ContinuationSource = null}）。
     *
     * <p>改用药策略时用的就是它：当前这回合已经排好的动作仍可执行，但下一回合不许再拿
     * 旧策略算出来的世界线续跑 —— 等重搜的结果落地，队列会被整体替换。
     */
    static void clearContinuationSource() {
        continuation.clear();
        panelRevision++;
    }

    /**
     * 「这份路线不能再用」的统一出口。
     *
     * <p>所有作废路径都必须走这里：回合翻页、镜像失配（漂移）、喝药后重算、
     * 执行层判定这一步不可用。少清一处，面板就会继续展示一条已经不存在的路线。
     *
     * <p>⚠️ 它<b>不清</b>有界 AUTO 的停止目标：目标是「打到第几回合」这个绝对边界，
     * 与「照哪条路线打」无关 —— 重算之后目标必须原样保留。
     */
    static void clearPlan() {
        plan = null;
        planTurn = -1;
        planSteps = java.util.Collections.emptyList();
        continuation.clear();
        // 🔴 预览与摘要**保留**，只标成陈旧：面板原地变灰 + 一行「重算中」。
        // 以前这里 `preview = null`，于是每回合面板先变「planning…」再变回路线 ——
        // 用户看到的就是「路线每回合闪一下」。真正清空只在 reset()（换战斗）。
        previewStale = true;
        panelRevision++;
    }

    /**
     * 装配一条可执行路线：执行队列、完整预览、跨回合边界**一起**换。
     *
     * <p>三者同源是这个方法存在的唯一理由 —— 分开赋值就会留下「预览是新的、边界是旧的」
     * 这种错配（症状是续跑命中却打出一条搜索没推荐过的路）。
     *
     * <p>⚠️ 名字刻意不叫 {@code adopt}：蓝本的 {@code AdoptRoute} 是**另一套机制**
     * （把整条路线接管为「按序打到底」并关掉自动重算）。这里只是把一次搜索的结果
     * 装进执行队列，回合边界照样按 {@code ContinuationSource} 精确校验，miss 就重算。
     */
    static void installRoute(java.util.List<io.github.youqing.sts1solver.search.RouteStep> steps,
                          int startTurn,
                          io.github.youqing.sts1solver.search.ContinuationPlan continuations,
                          int mismatchSequence) {
        plan = new java.util.ArrayDeque<io.github.youqing.sts1solver.search.RouteStep>(steps);
        planSteps = new java.util.ArrayList<io.github.youqing.sts1solver.search.RouteStep>(steps);
        planTurn = startTurn;
        planMismatchSeq = mismatchSequence;
        // 执行队列和预览都从**完整** steps 建：预览要保留已经打过的步骤，
        // 这样行号是绝对回合（T4/T5），不是「剩余行数」的 T1/T2。
        preview = RoutePreview.fromSteps(startTurn, steps);
        previewStale = false;
        continuation.adopt(continuations, mismatchSequence);
        panelRevision++;
    }

    /**
     * 新回合的精确续接（**纯数据**，单测可直接驱动）。
     *
     * <p>对应蓝本 {@code SolverResult.TryCreateContinuation}（{@code CombatPlan.cs:1696-1760}）：
     * **保留原来那条 best route**，只按边界回合号把「本回合之前」的动作切掉，
     * 续接后的缓存只留更晚的边界（{@code onlyAfter}）。预览**不动**（已打过的回合继续显示，
     * 行号仍是绝对回合）。
     *
     * <p>miss：返回 false，调用方照旧从根重搜；日志里带原因与首个字段差异。
     *
     * @return true = 命中续跑，不需要搜索
     */
    static boolean reuseContinuation(int liveTurn,
                                     io.github.youqing.sts1solver.search.ContinuationStamp liveStamp,
                                     int mismatchSequence) {
        ContinuationDecision.Outcome outcome = continuation.evaluate(
                liveTurn, liveStamp, mismatchSequence);
        if (!outcome.isHit()) {
            Trace.info("SEARCH_REUSE_MISS reason=" + outcome.reason().label
                    + " first_difference=" + outcome.firstDifference());
            return false;
        }
        int offset = outcome.forecastOffset();
        if (offset < 0 || offset > planSteps.size()) {
            // 边界下标与步骤列表对不上 = 内部不一致，宁可重搜也不能乱裁
            Trace.warn("SEARCH_REUSE_MISS reason=continuation_missing"
                    + " first_difference=forecastOffset " + offset
                    + " out of " + planSteps.size() + " steps");
            continuation.clear();
            return false;
        }
        plan = new java.util.ArrayDeque<io.github.youqing.sts1solver.search.RouteStep>(
                planSteps.subList(offset, planSteps.size()));
        planTurn = liveTurn;
        planMismatchSeq = mismatchSequence;
        panelRevision++;
        Trace.info("SEARCH_REUSED from_turn=" + liveTurn
                + " remaining_turns=" + outcome.remainingTurns()
                + " remaining_steps=" + plan.size()
                + " validation=exact_state_text");
        return true;
    }

    /** 当前执行队列的剩余步骤（只读副本；给单测与日志用）。 */
    static java.util.List<io.github.youqing.sts1solver.search.RouteStep> pendingSteps() {
        return plan == null
                ? java.util.Collections.<io.github.youqing.sts1solver.search.RouteStep>emptyList()
                : new java.util.ArrayList<io.github.youqing.sts1solver.search.RouteStep>(plan);
    }

    /**
     * 回合开始：能不能接着上一回合算出来的世界线走（蓝本 {@code SolverController.cs:1077-1140}）。
     *
     * <p>顺序与蓝本一致 —— {@code ContinuationStamp.CaptureLive} 逐字段命中就续跑；
     * miss 就是 {@code ContinuationSource = null} + 从根重搜，没有第三条路。
     * 蓝本这里还有两道归因（{@code ManualDivergence} / {@code ContinuationMissing}），
     * 由 {@link ContinuationDecision#evaluate} 负责，只影响诊断不影响处置。
     *
     * @return true = 命中续跑（调用方不要再搜索）
     */
    static boolean onTurnStart(int liveTurn,
                               io.github.youqing.sts1solver.search.ContinuationStamp liveStamp,
                               int mismatchSequence) {
        if (reuseContinuation(liveTurn, liveStamp, mismatchSequence)) {
            return true;
        }
        clearPlan();
        return false;
    }

    /**
     * 部署阶段发现这一步现在做不了（蓝本 {@code DEPLOY_REPLAN}，{@code SolverController.cs:2925-3010}）。
     *
     * <p>蓝本的动作只有两个：{@code _combat.ContinuationSource = null} +
     * {@code RequestSearch(SearchReason.DeploymentDrift)}，然后**结束这次部署**。
     * 也就是说从头重算，不是把这一步剔掉接着执行剩下的队列 ——
     * 剔一步等于拿「搜索从未推荐过的剩余序列」去指挥真局面。
     *
     * <p>{@code DEPLOY_DRIFT_LIMIT} 是本项目的**失败路径保护**（蓝本直接操作真实状态，
     * 不存在「镜像说能打、实机说不能打」这种漂移，所以它没有对应机制）：
     * 同一回合反复从根重算而一张牌都没打出去 = 死循环，直接停手并要求看日志。
     * 它不改变任何正常路径的语义。
     *
     * @return 永远 {@link #OUTCOME_REPLAN}
     */
    static int deploymentDrift(io.github.youqing.sts1solver.search.RouteStep step, String why) {
        Trace.warn("DEPLOY_REPLAN reason=" + why + " step=" + step
                + " (从根重算，不剔步)");
        clearPlan();
        deployDriftsThisTurn++;
        if (deployDriftsThisTurn >= DEPLOY_DRIFT_LIMIT) {
            Trace.error("DEPLOY_DRIFT_LOOP: " + deployDriftsThisTurn
                    + " root replans this turn without playing a single card -> AUTO stopped", null);
            stopAuto("deployment drift loop");
        }
        return OUTCOME_REPLAN;
    }

    /**
     * 观察到一次出牌：不是我们刚排出去的那张，就记一次「手工输入分歧」。
     *
     * <p>覆盖手工点牌，也覆盖遗物/药水的自动出牌（那条路影子不一定跟得上）。
     * 归因价值在于处置不同：这是**用户/别的通道**动了手，不是镜像算错 ——
     * 只有 {@code STATE_MISMATCH} 才该被当 bug 查。
     */
    public static void onCardPlayedObserved(String key) {
        if (!enabled) {
            return;
        }
        if (queuedCard != null && key(queuedCard).equals(key)) {
            return;                     // 我们自己刚排出去的那一张
        }
        continuation.markManualDivergence();
    }

    /**
     * 主线程抓一次精确状态戳（每回合一次，只在「计划还在但回合已翻页」时调用）。
     *
     * <p>渲染路径绝不能调用它 —— {@code capture()} 要扫四个牌堆 + 能力 + 遗物。
     */
    private static io.github.youqing.sts1solver.search.ContinuationStamp liveStampOrNull() {
        try {
            return io.github.youqing.sts1solver.search.ContinuationStamp.captureLive(
                    io.github.youqing.sts1solver.sim.SimState.capture());
        } catch (Throwable t) {
            Trace.warn("continuation stamp failed: " + t);
            return null;
        }
    }

    /** 当前局面指纹；抓不到（还没捕获根状态）时返回 null = 不做有效性校验。 */
    private static String currentFingerprint() {
        try {
            return io.github.youqing.sts1solver.sim.SimState.capture().fingerprint();
        } catch (Throwable t) {
            return null;
        }
    }

    /** 把当前局面丢给后台线程去搜。 */
    private static void submitSearch(AbstractPlayer player) {
        searchStartedAt = System.currentTimeMillis();
        try {
            io.github.youqing.sts1solver.sim.SimState snapshot =
                    io.github.youqing.sts1solver.sim.SimState.capture();
            if (snapshot == null) {
                return;
            }
            // 每回合把「怪物 + 回合号 + 当前意图」喂给学习表，搜索才能在跨回合时用真实意图
            for (io.github.youqing.sts1solver.sim.SimMonster m : snapshot.monsters) {
                io.github.youqing.sts1solver.sim.IntentLearning.observe(
                        m.type, snapshot.turn, m.intentDmg);
            }
            io.github.youqing.sts1solver.config.SolverConfig.Budget budget =
                    io.github.youqing.sts1solver.config.SolverConfig.budget();
            // 整场战斗规划：回合层**不设预算上界**（蓝本没有上界，靠节点/时间预算收敛），
            // 只留一个安全上界防止病态局面死循环。搜索内部按回合均分节点预算，一层吃不掉整份。
            // 策略快照在主线程取一次，随请求带进后台：后台不再读可变配置
            if (!potionPolicyLogged) {
                potionPolicyLogged = true;
                Trace.info("POTION_POLICY active: "
                        + io.github.youqing.sts1solver.config.SolverConfig.potionStrategy()
                        .toStorageString());
            }
            io.github.youqing.sts1solver.search.AsyncSearch.submit(snapshot,
                    snapshot.fingerprint(), CombatCapture.baseEnergy(), player.gameHandSize,
                    budget.beamWidth, io.github.youqing.sts1solver.search.BeamSolver.SAFETY_MAX_TURN_LAYERS,
                    budget.timeMillis, budget.nodeLimit, budget.cardBranches,
                    io.github.youqing.sts1solver.config.SolverConfig.novelty(),
                    io.github.youqing.sts1solver.config.SolverConfig.portfolio(),
                    io.github.youqing.sts1solver.config.SolverConfig.potionStrategy());
        } catch (Throwable t) {
            Trace.warn("submit search failed: " + t);
        }
    }

    /** 搜索为选牌类卡牌挑定的手牌下标；-1 表示没有。 */
    public static int pendingChoiceIndex() {
        return pendingChoiceIndex;
    }

    /** 选牌界面消费完就清掉，避免影响下一次。 */
    public static void clearPendingChoice() {
        pendingChoiceIndex = -1;
    }

    /** 把搜索返回的目标下标（对应 SimState.monsters 顺序）映射回真机怪物。 */
    private static AbstractMonster pickTarget(AbstractMonster fallback) {
        if (chosenTargetIndex < 0) {
            return fallback;
        }
        try {
            java.util.ArrayList<AbstractMonster> monsters =
                    AbstractDungeon.getMonsters().monsters;
            if (chosenTargetIndex < monsters.size()) {
                AbstractMonster picked = monsters.get(chosenTargetIndex);
                if (picked != null && picked.currentHealth > 0) {
                    return picked;
                }
            }
        } catch (Throwable ignored) {
            // 结构异常就退回默认目标
        }
        return fallback;
    }

    private static String key(AbstractCard card) {
        return card.cardID + (card.upgraded ? "+" : "");
    }

    /** 本回合还没被游戏拒绝过的手牌。 */
    private static ArrayList<AbstractCard> playableHand(AbstractPlayer player) {
        ArrayList<AbstractCard> hand = new ArrayList<AbstractCard>(player.hand.group);
        if (blockedThisTurn.isEmpty()) {
            return hand;
        }
        ArrayList<AbstractCard> filtered = new ArrayList<AbstractCard>(hand.size());
        for (AbstractCard c : hand) {
            if (c != null && !blockedThisTurn.contains(key(c))) {
                filtered.add(c);
            }
        }
        return filtered;
    }

    private enum Pref {
        UNMIRRORED, MIRRORED
    }

    private static AbstractCard firstPlayable(AbstractPlayer player, ArrayList<AbstractCard> hand,
                                             AbstractMonster target, Pref pref) {
        for (AbstractCard card : hand) {
            String key = card.cardID + (card.upgraded ? "+" : "");
            boolean mirrored = MirrorRegistry.supports(key);
            if (pref == Pref.UNMIRRORED && mirrored) {
                continue;
            }
            if (pref == Pref.MIRRORED && !mirrored) {
                continue;
            }
            int cost = effectiveCost(card);
            if (cost < 0 || cost > EnergyPanel.totalCount) {
                continue;
            }
            // 关键：可打性交给游戏自己判断。交锋（Clash）要求「手上全是攻击牌」，
            // 这类条件只有 canUse 知道，靠我们自己拼条件必然漏。
            AbstractMonster t = resolveTarget(card, target);
            if (!card.canUse(player, t)) {
                continue;
            }
            if (resolveTargetNeeded(card) && t == null) {
                continue;
            }
            return card;
        }
        return null;
    }

    /** 需要指向某个怪物的牌（攻击牌，或 target 标记为 ENEMY 的技能如缴械）。 */
    private static boolean resolveTargetNeeded(AbstractCard card) {
        return card.target == AbstractCard.CardTarget.ENEMY
                || card.type == AbstractCard.CardType.ATTACK;
    }

    private static AbstractMonster resolveTarget(AbstractCard card, AbstractMonster attackTarget) {
        return resolveTargetNeeded(card) ? attackTarget : null;
    }

    /**
     * 真实可打性判定。用 {@code costForTurn} 而不是 {@code cost}：
     * 后者是本场基础费用，可能被遗物/状态在本回合改写，用它会误判「打不起」。
     * 约定：-1 = X 费（按当前能量全花），-2 = 不可打出。
     */
    private static int effectiveCost(AbstractCard card) {
        if (card.cost == -2) {
            return -1;
        }
        if (card.cost == -1) {
            return EnergyPanel.totalCount;
        }
        return Math.max(0, card.costForTurn);
    }

    /** 打不出牌时把手上有什么、能量多少写进日志，避免只能靠猜。 */
    private static String describeHand(AbstractPlayer player) {
        StringBuilder sb = new StringBuilder();
        sb.append("energy=").append(EnergyPanel.totalCount).append(" hand[");
        ArrayList<AbstractCard> hand = new ArrayList<AbstractCard>(player.hand.group);
        for (int i = 0; i < hand.size(); i++) {
            AbstractCard c = hand.get(i);
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(c.cardID).append(c.upgraded ? "+" : "")
              .append("(cost=").append(c.cost)
              .append("/turn=").append(c.costForTurn)
              .append(c.type == AbstractCard.CardType.ATTACK ? ",atk" : "")
              .append(')');
        }
        return sb.append(']').toString();
    }

    /**
     * 结束回合必须走**游戏自己的路** —— 而且是**和鼠标点击完全同一条路**。
     *
     * <p>🔴 2026-10-31 真机事故（灼烧 / 多层护甲 / 金属化 / 奥利哈刚全部不触发，
     * 手点结束回合就正常）。下面这条链是字节码逐条核实过的**事实**：
     *
     * <pre>
     * // GameActionManager.getNextAction()，cardQueue 非空时：
     * CardQueueItem item = cardQueue.get(0);
     * if (item.card == null) {
     *     callEndOfTurnActions();      // ← 回合末三类触发的**唯一**入口（全类仅此一处调用）
     * }
     *
     * private void callEndOfTurnActions() {
     *     room.applyEndOfTurnRelics();          // 奥利哈刚 / 香炉…
     *     room.applyEndOfTurnPreCardPowers();   // 多层护甲 / 金属化…
     *     addToBot(new TriggerEndOfTurnOrbsAction());
     *     for (AbstractCard c : player.hand.group) c.triggerOnEndOfTurnForPlayingCard();  // 灼烧 / 后悔…
     *     player.stance.onEndOfTurn();
     * }
     * </pre>
     *
     * <p>也就是说：**回合末结算挂在「手牌队列里那个 card==null 的哨兵项」上**。
     * 而 {@code EndTurnButton.disable(true)}（鼠标点击真正调用的方法）一共做 5 件事，
     * 以前我们只复刻了第 4 件 {@code player.endTurnQueued = true}：
     *
     * <ol>
     *   <li>{@code InputHelper.moveCursorToNeutralPosition()}</li>
     *   <li>{@code actionManager.addToBottom(new NewQueueCardAction())} —— 先把挂着的出牌收尾</li>
     *   <li>{@code enabled/hb.hovered/isGlowing} 置位</li>
     *   <li>{@code player.endTurnQueued = true}</li>
     *   <li>{@code player.releaseCard()} —— 清 {@code justClickedLeft/isMouseDown/inSingleTargetMode} 等输入态</li>
     * </ol>
     *
     * <p>少了第 2、5 步之后，{@code cardQueue} 的状态与「手点」有哪些差别，**目前只是假设**
     * （与「回合照常推进、但这一回合所有回合末效果一起静默消失」的现象相符）。探针已观测到
     * {@code endTurn()} 确实会启动（{@code q} 由 1 变 0 的那一帧 {@code actions} 从 0 跳到 3~5），
     * 所以「入口没启动」已被排除，但「哨兵项丢失」尚未被直接观测到 —— 这条因果待真机验证。
     *
     * <p>既然已知差异只有第 2、5 步，修法就是**直接调用按钮自己的 {@code disable(true)}**：
     * 不复制逻辑、不和版本漂移赛跑，路径与手点逐字节一致。这是**消除差异**的排除法修法，
     * 不是定位到具体某一行后的定点修复；真机逐项通过前保持「候选修复」状态。
     *
     * <p>⚠️ 仍然不能调 {@code actionManager.endTurn()}：那是 {@code EndTurnAction} 才做的事，
     * 会跳过玩家侧整段（实测：战斗专注的「禁止抽牌」永不消失、怪物完全不行动）。
     *
     * <p>等待策略不变，仍是两段式：先等敌方阶段开始（{@code turnHasEnded=true}），
     * 再等它结束（{@code turnHasEnded=false} 且玩家回合恢复），期间不做任何事 ——
     * 否则会重复置 flag，实测**怪物一回合行动两次**。
     */
    private static void requestEndTurn(AbstractPlayer player) {
        com.megacrit.cardcrawl.ui.buttons.EndTurnButton button =
                AbstractDungeon.overlayMenu == null ? null
                        : AbstractDungeon.overlayMenu.endTurnButton;
        if (button != null) {
            // 与鼠标点击同一个入口（含 NewQueueCardAction + releaseCard）
            button.disable(true);
        } else {
            // 兜底：没有按钮对象时退回旧行为（至少能推进回合，代价是回合末结算可能丢）
            Trace.warn("AUTO end turn: EndTurnButton unavailable, falling back to endTurnQueued");
            player.endTurnQueued = true;
        }
        endPhase = 1;
        endPhaseFrames = 0;
    }

    /** @return true 表示还在回合切换过程中，本次 tick 不要做任何事 */
    private static boolean waitingForNextTurn() {
        if (endPhase == 0) {
            return false;
        }
        endPhaseFrames++;
        boolean turnEnded = AbstractDungeon.actionManager.turnHasEnded;

        if (endPhase == 1) {
            // 等敌方阶段真的开始；某些情况下标志不会及时置位，超时就跳过这段
            if (turnEnded || endPhaseFrames > 180) {
                endPhase = 2;
                endPhaseFrames = 0;
            }
            return true;
        }

        AbstractPlayer player = AbstractDungeon.player;
        boolean backToPlayer = !turnEnded
                && (player == null || (!player.endTurnQueued && !player.isEndingTurn));
        if (backToPlayer) {
            endPhase = 0;
            return false;
        }
        if (endPhaseFrames > MAX_END_PHASE_FRAMES) {
            Trace.warn("turn transition took too long (" + endPhaseFrames
                    + " frames) -> resuming control");
            endPhase = 0;
        }
        return true;
    }

    private static AbstractMonster firstAliveMonster() {        if (AbstractDungeon.getMonsters() == null || AbstractDungeon.getMonsters().monsters == null) {
            return null;
        }
        for (AbstractMonster m : AbstractDungeon.getMonsters().monsters) {
            if (m.currentHealth > 0) {
                return m;
            }
        }
        return null;
    }

    private static boolean drained() {
        GameActionManager am = AbstractDungeon.actionManager;
        boolean actionsEmpty = am.actions == null || am.actions.isEmpty();
        boolean queueEmpty = am.cardQueue == null || am.cardQueue.isEmpty();
        boolean limboEmpty = AbstractDungeon.player.limbo == null || AbstractDungeon.player.limbo.isEmpty();
        return actionsEmpty && queueEmpty && limboEmpty;
    }
}
