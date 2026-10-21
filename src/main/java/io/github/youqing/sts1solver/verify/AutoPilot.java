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

    /** 回合切换的两段式等待：1=等敌方阶段开始，2=等它结束并回到玩家回合。 */
    private static int endPhase;
    private static int endPhaseFrames;
    private static final int MAX_END_PHASE_FRAMES = 900;

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
                // 入队三秒还留在手上、且动作队列已空 = 这一手游戏压根没执行，别再刷屏
                Trace.warn("AUTO stuck on " + queuedCard.cardID + " (" + queuedFrames
                        + " frames, same card object still in hand) -> AUTO off");
                enabled = false;
                queuedCard = null;
                queuedFrames = 0;
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
            if (!playOneCard(player, target)) {
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

    /** 先打未镜像的牌（探索盲区），没有可探索的再打已镜像的（产出对拍）。 */
    private static boolean playOneCard(AbstractPlayer player, AbstractMonster attackTarget) {
        ArrayList<AbstractCard> hand = new ArrayList<AbstractCard>(player.hand.group);

        AbstractCard chosen = firstPlayable(player, hand, attackTarget, Pref.UNMIRRORED);
        boolean mirrored = chosen == null;
        if (chosen == null) {
            chosen = firstPlayable(player, hand, attackTarget, Pref.MIRRORED);
        }
        if (chosen == null) {
            return false;
        }

        String key = chosen.cardID + (chosen.upgraded ? "+" : "");
        AbstractMonster target = resolveTarget(chosen, attackTarget);
        AbstractDungeon.actionManager.cardQueue.add(new CardQueueItem(chosen, target, chosen.cost));
        queuedCard = chosen;
        queuedFrames = 0;
        noProgressEndTurns = 0;   // 本当に進んだので「无进展」计数清零
        Trace.info("AUTO play " + key + (target == null ? "" : " -> " + target.name)
                + (mirrored ? "  (mirrored, diff)" : "  (unmirrored, record)"));
        cooldown = 6;
        return true;
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
