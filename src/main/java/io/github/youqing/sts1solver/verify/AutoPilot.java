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

    /** 防重复入队：同一张牌连续入队却一直没离开手牌，说明这一手游戏不认。 */
    private static String queuedKey;
    private static int queuedFrames;

    private AutoPilot() {
    }

    public static boolean enabled() {
        return enabled;
    }

    public static void toggle() {
        enabled = !enabled;
        cooldown = 0;
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
        if (queuedKey != null) {
            queuedFrames++;
            if (!handContains(queuedKey)) {
                queuedKey = null;
                queuedFrames = 0;
            } else if (queuedFrames > 120) {
                // 入队两秒还留在手上 = 这一手游戏压根没执行，别再刷屏，关掉并报告
                Trace.warn("AUTO stuck on " + queuedKey + " (" + queuedFrames
                        + " frames, still in hand) -> AUTO off");
                enabled = false;
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
            if (player.isEndingTurn || player.endTurnQueued || !drained()) {
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
                Trace.info("AUTO end turn (empty hand, energy=" + EnergyPanel.totalCount + ")");
                AbstractDungeon.actionManager.endTurn();
                cooldown = 30;
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
                Trace.info("AUTO end turn (nothing playable)  " + describeHand(player));
                AbstractDungeon.actionManager.endTurn();
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
        queuedKey = key;
        queuedFrames = 0;
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

    private static boolean handContains(String key) {
        AbstractPlayer player = AbstractDungeon.player;
        if (player == null || player.hand == null) {
            return false;
        }
        for (AbstractCard c : player.hand.group) {
            if ((c.cardID + (c.upgraded ? "+" : "")).equals(key)) {
                return true;
            }
        }
        return false;
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
