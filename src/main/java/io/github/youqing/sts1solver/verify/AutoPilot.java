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
            // 发牌还没完成时手牌为空，不能据此判定「无牌可出」而结束回合
            if (player.hand == null || player.hand.size() == 0) {
                return;
            }

            AbstractMonster target = firstAliveMonster();
            if (target == null) {
                return;
            }
            if (!playOneCard(player, target)) {
                AbstractDungeon.actionManager.endTurn();
                Trace.info("AUTO end turn (nothing playable)");
                cooldown = 30;
            }
        } catch (Throwable t) {
            Trace.error("auto pilot failed", t);
            enabled = false;
        }
    }

    /**
     * 优先打已镜像的牌（能产生对拍数据），没有就打未镜像的牌（不比对，但让局势推进）。
     * 早期版本只打已镜像的牌 —— 结果牌组里一旦有未镜像的牌就永远轮不到、还被迫结束回合，
     * 表现为「新抓的牌打不出去」。
     */
    private static boolean playOneCard(AbstractPlayer player, AbstractMonster attackTarget) {
        ArrayList<AbstractCard> hand = new ArrayList<AbstractCard>(player.hand.group);

        AbstractCard chosen = firstPlayable(hand, attackTarget, true);
        boolean mirrored = chosen != null;
        if (chosen == null) {
            chosen = firstPlayable(hand, attackTarget, false);
        }
        if (chosen == null) {
            return false;
        }

        String key = chosen.cardID + (chosen.upgraded ? "+" : "");
        AbstractMonster target = chosen.type == AbstractCard.CardType.ATTACK ? attackTarget : null;
        AbstractDungeon.actionManager.cardQueue.add(new CardQueueItem(chosen, target, chosen.cost));
        Trace.info("AUTO play " + key + (target == null ? "" : " -> " + target.name)
                + (mirrored ? "" : "  (unmirrored, no diff)"));
        cooldown = 6;
        return true;
    }

    private static AbstractCard firstPlayable(ArrayList<AbstractCard> hand, AbstractMonster target,
                                             boolean mirroredOnly) {
        for (AbstractCard card : hand) {
            String key = card.cardID + (card.upgraded ? "+" : "");
            if (mirroredOnly && !MirrorRegistry.supports(key)) {
                continue;
            }
            if (card.cost < 0 || card.cost > EnergyPanel.totalCount) {
                continue;
            }
            if (card.type == AbstractCard.CardType.ATTACK && target == null) {
                continue;
            }
            return card;
        }
        return null;
    }

    private static AbstractMonster firstAliveMonster() {
        if (AbstractDungeon.getMonsters() == null || AbstractDungeon.getMonsters().monsters == null) {
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
