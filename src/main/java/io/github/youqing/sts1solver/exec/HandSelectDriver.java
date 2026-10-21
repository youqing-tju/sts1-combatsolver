package io.github.youqing.sts1solver.exec;

import com.megacrit.cardcrawl.cards.AbstractCard;
import com.megacrit.cardcrawl.characters.AbstractPlayer;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.rooms.AbstractRoom;
import com.megacrit.cardcrawl.screens.select.HandCardSelectScreen;

import io.github.youqing.sts1solver.diag.Trace;
import io.github.youqing.sts1solver.verify.AutoPilot;

/**
 * 手牌选择界面（{@code HandCardSelectScreen}）的自动驱动。
 *
 * <p>🔴 关键认知：STS1 有两套选牌界面，**不是同一个**：
 * <ul>
 *   <li>{@code GridCardSelectScreen} —— 从抽牌堆/弃牌堆里翻牌（如 Headbutt）</li>
 *   <li>{@code HandCardSelectScreen} —— 从**手牌**里选（双持、军备、战吼、烧牌契约…）</li>
 * </ul>
 * 双持用的是后者。之前一直驱动 Grid 界面，所以怎么点都没反应。
 *
 * <p>做法对齐蓝本：选择在蓝本里是一个「确定性决策」，执行侧按固定策略取第一个合格项，
 * 不追求最优（最优交给搜索），先把流程跑通。
 */
public final class HandSelectDriver {

    private HandSelectDriver() {
    }

    public static void drive(HandCardSelectScreen screen) {
        if (!AutoPilot.enabled() || screen == null) {
            return;
        }
        try {
            if (AbstractDungeon.screen != AbstractDungeon.CurrentScreen.HAND_SELECT) {
                return;
            }
            // 🔴 只在战斗里接管。火堆敲牌、事件里的选牌同样是 HAND_SELECT 界面，
            // 自动点头会把玩家的升级/事件选择吞掉（用户实测：火堆处被自动敲牌）。
            AbstractRoom room = AbstractDungeon.getCurrRoom();
            if (room == null || room.phase != AbstractRoom.RoomPhase.COMBAT) {
                return;
            }
            AbstractPlayer player = AbstractDungeon.player;
            if (player == null || player.hand == null || screen.selectedCards == null) {
                return;
            }
            if (screen.wereCardsRetrieved) {
                return;
            }

            int need = screen.canPickZero ? 0 : Math.max(1, Math.min(screen.numCardsToSelect, player.hand.size()));
            if (screen.upTo) {
                need = Math.min(1, player.hand.size());
            }
            if (need <= 0) {
                need = 1;
            }

            while (screen.selectedCards.size() < need) {
                AbstractCard pick = pickBySearchIndex(player, screen);
                if (pick == null) {
                    pick = firstEligible(player, screen);
                }
                if (pick == null) {
                    break;
                }
                screen.selectedCards.group.add(pick);
                Trace.info("HAND pick " + pick.cardID + (pick.upgraded ? "+" : "")
                        + " (" + screen.selectedCards.size() + "/" + need + ")");
            }

            if (screen.selectedCards.size() >= need && screen.selectedCards.size() > 0) {
                AutoPilot.clearPendingChoice();
                AbstractDungeon.closeCurrentScreen();
                Trace.info("HAND confirm " + screen.selectedCards.size() + " card(s)");
            }
        } catch (Throwable t) {
            Trace.error("hand select driver failed", t);
        }
    }

    /**
     * 搜索已经决定了选哪张（{@code PlanAction.choiceIndex}）——按它的下标取，
     * 这样模拟和实机做的选择是同一个，diff 才不会因为「选错牌」而炸。
     */
    private static AbstractCard pickBySearchIndex(AbstractPlayer player, HandCardSelectScreen screen) {
        int index = AutoPilot.pendingChoiceIndex();
        if (index < 0 || index >= player.hand.group.size()) {
            return null;
        }
        AbstractCard card = player.hand.group.get(index);
        if (screen.selectedCards.contains(card)) {
            return null;
        }
        return card;
    }

    /**
     * 选哪张：优先攻击/能力（双持只能复制这两类），否则任意一张。
     * 这是**确定性策略**，不是最优策略 —— 最优留给搜索。
     */
    private static AbstractCard firstEligible(AbstractPlayer player, HandCardSelectScreen screen) {
        AbstractCard fallback = null;
        for (AbstractCard card : player.hand.group) {
            if (screen.selectedCards.contains(card)) {
                continue;
            }
            if (card.type == AbstractCard.CardType.ATTACK || card.type == AbstractCard.CardType.POWER) {
                return card;
            }
            if (fallback == null) {
                fallback = card;
            }
        }
        return fallback;
    }
}
