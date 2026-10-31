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
        plan = null;
        planTurn = -1;
        planSummary = "";
        framesWaitingForPlan = 0;
        reportedMissingMirrors.clear();
        Trace.info("AUTO state reset (combat boundary)");
    }

    public static void toggle() {
        enabled = !enabled;
        cooldown = 0;
        // 重新开启时把上一轮的计数清干净，否则会「一开就立刻再触发一次卡死」
        queuedCard = null;
        queuedFrames = 0;
        noProgressEndTurns = 0;
        Trace.info("AUTO " + (enabled ? "ON" : "OFF"));
    }

    public static void disable() {
        enabled = false;
    }

    public static void tick() {
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
                    enabled = false;
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
                    enabled = false;
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
                    enabled = false;
                    return;
                }
                Trace.info("AUTO end turn (nothing playable)  " + describeHand(player));
                requestEndTurn(player);
                cooldown = 30;
            }
        } catch (Throwable t) {
            Trace.error("auto pilot failed", t);
            enabled = false;
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
        } catch (Throwable t) {
            Trace.warn("turn snapshot failed: " + t);
        }
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
            Trace.info("PLAN dropped: turn " + planTurn + " -> " + turnNow + ", replanning");
            plan = null;
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
            plan = null;      // 计划失效（手牌对不上/被拒），丢掉重算
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
        plan = new java.util.ArrayDeque<io.github.youqing.sts1solver.search.RouteStep>(
                result.steps);
        planTurn = turnNow;
        planMismatchSeq = ShadowTracker.mismatchSequence();
        planSummary = summarizePlan(result);
        if (!lastSearchShape.isEmpty()) {
            // 面板第一行留一格「刚刚怎么算出来的」：135+base / 26k / 642ms
            planSummary = lastSearchShape + "   " + planSummary;
        }
        // 预算用量一并打出来：树被搜完了（exhausted）再加预算也没用，
        // 只有 hitNodeLimit=true 才说明「是预算把它掐断的」。
        Trace.info("PLAN ready: " + result.steps.size() + " steps, depth " + result.depth
                + " turns, " + result.nodes + "/" + result.nodeLimit + " nodes ("
                + (result.nodeLimit <= 0 ? "unlimited"
                    : (result.nodes * 100 / Math.max(1, result.nodeLimit)) + "%")
                + ", " + (result.hitNodeLimit ? "node limit hit" : "tree exhausted")
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
                planText.append(step.cardKey == null ? "END_TURN" : shortName(step.cardKey));
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
            plan = null;
            planTurn = -1;
            planSummary = "";
            Trace.info("PLAN dropped: " + drift
                    + " mirror mismatch(es) since it was computed, replanning");
            return OUTCOME_REPLAN;
        }
        if (step.endTurn) {
            plan.poll();
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
        // 药水效果不在对拍台架的跟踪范围里（台架只跟「打牌」）：作废基线，
        // 否则下一张牌的影子还停在喝药前，差出来的正是药水抽的牌/打的伤害。
        ShadowTracker.invalidateBaseline("potion " + step.cardKey);
        // 🔴 喝完药**整份计划作废**：三选一/液态记忆这类药水给哪张牌是游戏现掷的，
        // 计划是在「不知道给什么」的前提下排的。2026-10-30 真机那一把，攻击药水给了
        // 一张本回合 0 费的顺劈斩，计划却还按原样走，最后**留着它结束回合**，
        // 白丢 9 点伤害 + 一次抽牌，接着被大块头反打 26。
        plan = null;
        planTurn = -1;
        planSummary = "";
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
     * 当前计划的摘要（按回合分组），给面板显示用。
     *
     * <p>只列「还剩哪些回合、每回合打什么」——完整路线面板留到 M4-1 再做。
     */
    public static java.util.List<String> planLines(int maxLines) {
        java.util.List<String> out = new java.util.ArrayList<String>();
        if (plan == null || plan.isEmpty()) {
            return out;
        }
        int turn = 1;
        StringBuilder sb = new StringBuilder();
        for (io.github.youqing.sts1solver.search.RouteStep step : plan) {
            if (step.endTurn) {
                if (sb.length() > 0) {
                    out.add("T" + turn + "  " + sb);
                    sb.setLength(0);
                }
                turn++;
                if (out.size() >= maxLines) {
                    return out;
                }
            } else {
                if (sb.length() > 0) {
                    sb.append("  ");
                }
                sb.append(step.potion ? "药·" + shortName(step.cardKey) : shortName(step.cardKey));
            }
        }
        if (sb.length() > 0 && out.size() < maxLines) {
            out.add("T" + turn + "  " + sb);
        }
        return out;
    }

    /** 牌名缩写：去掉 {@code _R} 后缀，面板宽度有限。 */
    private static String shortName(String key) {
        if (key == null) {
            return "?";
        }
        return key.endsWith("_R") ? key.substring(0, key.length() - 2) : key;
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
     * 结束回合必须走**游戏自己的路**：{@code EndTurnButton} 点下去就是把
     * {@code player.endTurnQueued} 置为 true，由游戏主循环执行完整流程
     * （回合末结算、手牌弃置、下回合抽牌、敌方行动）。
     *
     * <p>🔴 直接调 {@code actionManager.endTurn()} 会跳过玩家侧那一段，
     * 实测后果：战斗专注的「禁止抽牌」永不消失、**怪物完全不行动**。
     *
     * <p>但只置 flag 又会出现反向问题：flag 很快被游戏清掉，而收尾动作还在跑，
     * 下一帧我们会再置一次 —— 实测**怪物一回合行动两次**，玩家直接被打死。
     *
     * <p>所以这里用两段式等待：先等敌方阶段开始（turnHasEnded=true），
     * 再等它结束（turnHasEnded=false 且玩家回合恢复）。整个期间不做任何事。
     */
    private static void requestEndTurn(AbstractPlayer player) {
        player.endTurnQueued = true;
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
