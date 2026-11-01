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
     * 有界 AUTO 的停止边界（{@code -1} = 无界）。
     *
     * <p>语义是「打完 Tn 的 END_TURN 与随后敌方阶段，在 Tn+1 玩家回合交还控制」，
     * 与「照哪条路线打」无关：重算、漂移、喝药都不会清它。
     */
    private static final BoundedAutoTarget boundedTarget = new BoundedAutoTarget();

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

    /** 同一步连续被拒的指纹与次数（见 {@link #rejectStep}）。 */
    private static String lastRejectSignature;
    private static int rejectStreak;
    private static final int REJECT_STREAK_LIMIT = 3;
    /** 本回合从计划里剔掉了几步；剔太多说明计划整体跑不动。 */
    private static int droppedStepsThisTurn;
    private static final int DROPPED_STEPS_LIMIT = 3;

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
        rejectStreak = 0;
        lastRejectSignature = null;
        droppedStepsThisTurn = 0;
        io.github.youqing.sts1solver.search.AsyncSearch.reset();
        clearPlan();
        // 下一场绝不继承上一场的停止目标（战斗提前胜利/死亡/离开房间都走这里）
        boundedTarget.clear();
        framesWaitingForPlan = 0;
        reportedMissingMirrors.clear();
        strategySnapshotDirty = false;
        screenWasUp = false;
        Trace.info("AUTO state reset (combat boundary)");
    }

    public static void toggle() {
        if (enabled) {
            // 手动关闭 = 立即交还控制。已经入队的游戏动作仍由游戏自然结算，不强杀。
            stopAuto("toggled off");
            return;
        }
        // 手动开启的是**无界** AUTO：只有点路线行才会设置停止目标（见 runUntil）
        prepareEnable();
        Trace.info("AUTO ON (unbounded)");
    }

    /**
     * 开启 AUTO 前的统一清理。
     *
     * <p>手动 {@link #toggle()} 与 {@link #runUntil(int)} 必须清同一批东西：
     * 不清的话上一轮残留的「刚入队的那张牌 / 连续无进展计数」会让新一次开启
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
     * <p>所有路径（手动按钮、异常、连续卡死、无进展、有界任务完成）都走这里，
     * 保证「有界目标」不会被下一次开启莫名继承。少绕一处就会出现
     * 「上一场点到 T7，这一场开 AUTO 打到 T7 自己停了」这种无法解释的行为。
     */
    private static void stopAuto(String reason) {
        boolean wasEnabled = enabled;
        boolean hadTarget = boundedTarget.isActive();
        int target = boundedTarget.targetTurn();
        enabled = false;
        boundedTarget.clear();
        if (hadTarget) {
            panelRevision++;      // 目标高亮/状态文案要跟着消失
            Trace.info("AUTO bounded target T" + target + " cleared (" + reason + ")");
        }
        if (wasEnabled) {
            Trace.info("AUTO OFF (" + reason + ")");
        }
    }

    public static void disable() {
        stopAuto("disabled");
    }

    // ------------------------------------------------------- 有界 AUTO（打到 Tn）

    /**
     * 「AUTO 打到 {@code targetTurn} 这一回合末就停」。
     *
     * <p>⚠️ 这是**项目自定义扩展**，不是蓝本的 {@code ApplyRoute} / {@code AdoptRoute}：
     * 蓝本根本没有 {@code ApplyRoute}，它的路线行 {@code MouseFilter = Ignore}（不可点）。
     * 我们只设置一个<b>绝对停止回合</b>，不冻结路线、不按旧预测跨回合照打 ——
     * 每回合按真局面**校验**（逐字段相等就续跑、否则重搜），停的边界由用户指定。
     *
     * <p>行为（对应设计 §5.2）：
     * <ol>
     *   <li>读主线程真实回合；{@code targetTurn} 小于当前回合 = 过期目标 → 不改任何状态，返回 false；</li>
     *   <li>AUTO 已开：保留开启，替换目标；</li>
     *   <li>AUTO 未开：按 {@link #prepareEnable()} 开启（<b>不</b>调 toggle()，避免翻转歧义）；</li>
     *   <li>不取消正在跑的 {@code AsyncSearch}：点击路线不等于撤回搜索。</li>
     * </ol>
     *
     * <p>入口自己重新校验，不能只信任 UI —— 面板行可能来自已经过期的预览。
     *
     * @return true = 目标已生效
     */
    public static boolean runUntil(int targetTurn) {
        int turnNow = currentTurn();
        if (!armBoundedTarget(targetTurn, turnNow)) {
            Trace.warn("AUTO bounded request rejected: target=T" + targetTurn
                    + " current=T" + turnNow + " (stale target or invalid turn)");
            return false;
        }
        boolean alreadyOn = enabled;
        if (!alreadyOn) {
            prepareEnable();
        }
        panelRevision++;          // 状态文案与目标行高亮要重排
        Trace.info("AUTO bounded start target=T" + targetTurn + " current=T" + turnNow
                + (alreadyOn ? "  (already on, target replaced)" : ""));
        return true;
    }

    /**
     * 有界 AUTO 的目标回合；普通无界 AUTO 返回 {@code -1}。
     *
     * <p>面板据此写 {@code AUTO → Tn（逐回合校验）}。注意它<b>不</b>代表
     * 「路线已冻结」：对不上就重搜，预览可能被清空，但目标本身不变。
     */
    public static int stopAfterTurn() {
        return boundedTarget.targetTurn();
    }

    /** 装填停止目标（{@code current} 由调用方给，便于脱离真机测试）。 */
    static boolean armBoundedTarget(int targetTurn, int current) {
        return boundedTarget.request(targetTurn, current);
    }

    /** 这个回合是否已经越过停止目标（tick 与单测共用同一条判据）。 */
    static boolean shouldStopAt(int turnNow) {
        return boundedTarget.shouldStop(turnNow);
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
            rejectStreak = 0;
            lastRejectSignature = null;
            droppedStepsThisTurn = 0;
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
        // 🔴 有界 AUTO 的停止点。
        //
        // 位置是三处约束的交点，挪任何一个都会出真机问题：
        //   ① 必须在 waitingForNextTurn() **之后** —— Tn 的 END_TURN 一入队就停的话，
        //      敌方阶段还没打完，等于把这个回合劈成两半，怪物行动归属说不清；
        //   ② 必须在任何搜索 / 出牌 / 喝药 / END_TURN **之前** —— 晚一步就会在 Tn+1 多打一手，
        //      用户看到的是「说了停在 Tn，结果多打了一张」；
        //   ③ 不能只放在回合变化清计划处 —— 那里可能继续本 tick 出牌。
        if (shouldStopAt(turnNow)) {
            int stoppedAt = boundedTarget.targetTurn();
            stopAuto("bounded complete");
            Trace.info("AUTO bounded stop after T" + stoppedAt
                    + "; control returned at T" + turnNow);
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
                requestEndTurn(player);
                cooldown = 30;
            }
        } catch (Throwable t) {
            Trace.error("auto pilot failed", t);
            stopAuto("exception: " + t);
        }
    }

    /** 出牌决定的三态。 */
    private static final int OUTCOME_PLAYED = 0;
    private static final int OUTCOME_WAITING = 1;
    private static final int OUTCOME_NOTHING = 2;
    private static final int OUTCOME_END_TURN = 3;
    /** 计划里的这一步执行不了（手牌对不上/被禁）→ 丢掉计划重算。 */
    private static final int OUTCOME_REPLAN = 4;
    /** 这一步已经被剔出计划，但计划本身还能继续走 → 等下一帧，别重算。 */
    private static final int OUTCOME_SKIP = 5;

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
        io.github.youqing.sts1solver.ui.SolverPanel.setStrategyStatus(
                io.github.youqing.sts1solver.ui.StrategyStatusSnapshot.from(snap));
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
            io.github.youqing.sts1solver.ui.SolverPanel.setStrategyStatus(
                    io.github.youqing.sts1solver.ui.StrategyStatusSnapshot.from(snap));
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
            if (!reuseContinuation(turnNow, liveStampOrNull(),
                    ShadowTracker.mismatchSequence())) {
                Trace.info("PLAN dropped: turn " + planTurn + " -> " + turnNow + ", replanning");
                clearPlan();
            }
            dumpTurnSnapshot(turnNow);
        }
        // ② 有计划就照计划走（蓝本的「冻结路线 / 执行本回合」）
        if (plan != null && !plan.isEmpty()) {
            if (droppedStepsThisTurn >= DROPPED_STEPS_LIMIT) {
                // 剔掉这么多次说明这条路线在本回合根本跑不动（药水槽对不上、
                // 手牌被复制成同名多张……）。别再来回重算，退回逐张兜底。
                Trace.info("PLAN unusable this turn (" + droppedStepsThisTurn
                        + " steps dropped) -> per-card fallback");
                return fallbackPlay(player, attackTarget);
            }
            int outcome = executePlannedStep(player, attackTarget);
            if (outcome == OUTCOME_SKIP) {
                return OUTCOME_WAITING;   // 这一步已剔除，下一帧按剩下的计划走
            }
            if (outcome != OUTCOME_REPLAN) {
                return outcome;
            }
            clearPlan();      // 计划失效（手牌对不上/被拒），丢掉重算（停止目标不在这里清）
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
        adoptPlan(result.steps, turnNow, result.continuations,
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
        // 不重算就继续照打，等于拿「想象中的局面」指挥真局面（蓝本 DeploymentDrift 同义）。
        if (ShadowTracker.mismatchSequence() != planMismatchSeq) {
            int drift = ShadowTracker.mismatchSequence() - planMismatchSeq;
            clearPlan();
            Trace.info("PLAN dropped: " + drift
                    + " mirror mismatch(es) since it was computed, replanning");
            return OUTCOME_REPLAN;
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
            return rejectStep(step, "is not in hand");
        }
        if (blockedThisTurn.contains(step.cardKey)) {
            return rejectStep(step, "is blocked this turn");
        }
        AbstractMonster target = monsterAt(step.targetIndex, attackTarget);
        if (!chosen.canUse(player, target)) {
            return rejectStep(step, "not playable now");
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
            return rejectStep(step, "has no potion belt");
        }
        com.megacrit.cardcrawl.potions.AbstractPotion potion = null;
        for (com.megacrit.cardcrawl.potions.AbstractPotion p : player.potions) {
            if (p != null && step.cardKey != null && step.cardKey.equals(p.ID)) {
                potion = p;
                break;
            }
        }
        if (potion == null) {
            return rejectStep(step, "is not in the belt");
        }
        if (!potion.canUse()) {
            return rejectStep(step, "cannot be used now");
        }
        AbstractMonster target = potion.targetRequired
                ? monsterAt(step.targetIndex, attackTarget) : null;
        if (potion.targetRequired && target == null) {
            return rejectStep(step, "has no valid target");
        }
        // 药水引发的选牌（液态记忆 / 三选一发现）先记下搜索挑的下标，
        // 网格驱动会用它，而不是自己挑第一张。
        pendingChoiceIndex = step.choiceIndex;
        int slot = potion.slot;
        try {
            potion.use(target);
        } catch (Throwable t) {
            Trace.error("AUTO potion " + step.cardKey + " failed", t);
            return rejectStep(step, "threw on use");
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

    /**
     * 计划里这一步执行不了。
     *
     * <p>只返回 REPLAN 是不够的：搜索是确定性的，同一局面重算必然得到同一条路线，
     * 于是「拒绝 → 重算 → 再拒绝」无限循环（2026-10-30 Boss 战实测空转 100 秒、548 次）。
     * 所以同一步连续被拒 {@value #REJECT_STREAK_LIMIT} 次就把它从计划里剔掉继续走，
     * 剔够 {@value #DROPPED_STEPS_LIMIT} 步说明这条计划整体跑不动，本回合退回逐张兜底。
     */
    private static int rejectStep(RouteStep step, String why) {
        Trace.info("PLAN step " + step.cardKey + " " + why + " -> replan");
        String signature = (step.endTurn ? "E" : step.potion ? "P" : "C") + ":" + step.cardKey;
        if (signature.equals(lastRejectSignature)) {
            rejectStreak++;
        } else {
            lastRejectSignature = signature;
            rejectStreak = 1;
        }
        if (rejectStreak < REJECT_STREAK_LIMIT) {
            return OUTCOME_REPLAN;
        }
        rejectStreak = 0;
        lastRejectSignature = null;
        if (plan != null && !plan.isEmpty()) {
            plan.poll();
            panelRevision++;      // 剔掉一步 → 预览里那一行的箭头要移走
            droppedStepsThisTurn++;
            if (!step.endTurn && !step.potion && step.cardKey != null) {
                blockedThisTurn.add(step.cardKey);
            }
            Trace.warn("PLAN step " + step.cardKey + " rejected " + REJECT_STREAK_LIMIT
                    + " times in a row -> dropped from plan ("
                    + droppedStepsThisTurn + "/" + DROPPED_STEPS_LIMIT + " this turn)");
        }
        return OUTCOME_SKIP;
    }

    /** 后台结果的取用：指纹对不上就作废（局面已经被别的东西改过）。 */
    private static io.github.youqing.sts1solver.search.AsyncSearch.Result pollValidated() {
        io.github.youqing.sts1solver.search.AsyncSearch.Result result =
                io.github.youqing.sts1solver.search.AsyncSearch.poll();
        if (result == null) {
            return null;
        }
        String fingerprint = currentFingerprint();
        if (fingerprint != null && !fingerprint.equals(result.fingerprint)) {
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
        planSummary = "";
        preview = null;
        planSteps = java.util.Collections.emptyList();
        continuation.clear();
        panelRevision++;
    }

    /**
     * 采纳一条路线：执行队列、完整预览、跨回合边界**一起**换。
     *
     * <p>三者同源是这个方法存在的唯一理由 —— 分开赋值就会留下「预览是新的、边界是旧的」
     * 这种错配（症状是续跑命中却打出一条搜索没推荐过的路）。
     */
    static void adoptPlan(java.util.List<io.github.youqing.sts1solver.search.RouteStep> steps,
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
        continuation.adopt(continuations, mismatchSequence);
        panelRevision++;
    }

    /**
     * 新回合的精确续接（**纯数据**，单测可直接驱动）。
     *
     * <p>命中：按 {@code forecastOffset} 从完整步骤里裁出本回合及以后的执行队列，
     * 预览**不动**（已打过的回合继续显示，行号仍是绝对回合）。
     * miss：返回 false，调用方照旧重搜；日志里带原因与首个字段差异。
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
            return io.github.youqing.sts1solver.search.ContinuationStamp.from(
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
            io.github.youqing.sts1solver.search.AsyncSearch.submit(snapshot,
                    snapshot.fingerprint(), CombatCapture.baseEnergy(), player.gameHandSize,
                    budget.beamWidth, io.github.youqing.sts1solver.search.BeamSolver.SAFETY_MAX_TURN_LAYERS,
                    budget.timeMillis, budget.nodeLimit, budget.cardBranches,
                    io.github.youqing.sts1solver.config.SolverConfig.novelty(),
                    io.github.youqing.sts1solver.config.SolverConfig.portfolio());
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
