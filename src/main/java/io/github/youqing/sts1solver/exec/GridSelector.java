package io.github.youqing.sts1solver.exec;

import com.megacrit.cardcrawl.cards.AbstractCard;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.helpers.input.InputHelper;
import com.megacrit.cardcrawl.rooms.AbstractRoom;
import com.megacrit.cardcrawl.screens.select.GridCardSelectScreen;
import com.megacrit.cardcrawl.ui.buttons.GridSelectConfirmButton;

import io.github.youqing.sts1solver.diag.Trace;
import io.github.youqing.sts1solver.verify.AutoPilot;

/**
 * Drives card-selection grids (Dual Wield, Armaments, Warcry …).
 *
 * <p>⚠️ 时序是关键，这里踩过两次坑：
 * <ol>
 *   <li>不能把驱动挂在 {@code AbstractDungeon.update()}：界面打开时它不被调用（实测 0 次触发）。</li>
 *   <li>不能在整个界面 update 之后设 {@code hb.clicked}：{@code Hitbox.update()} 每帧都会重写
 *       这个字段，下一帧就被冲掉（实测 pick 了 42 次，牌一张没选上）。</li>
 * </ol>
 *
 * <p>正确窗口：<b>卡片自己 update 之后、界面循环读取之前</b> → 挂在
 * {@code AbstractCard.update()} 的 postfix 上。
 *
 * <p>确认按钮同理：它内部先 {@code hb.update()} 再判断 clicked，两者之间插不进去。
 * 所以用的是「临时把鼠标放到按钮上并伪造一次松开」——这是唯一能走游戏原始逻辑的办法，
 * 且只在这一帧内生效，postfix 里立刻还原。
 */
public final class GridSelector {

    private static boolean inputSaved;
    private static int savedX;
    private static int savedY;
    private static boolean savedRelease;

    private GridSelector() {
    }

    private static GridCardSelectScreen activeGrid() {
        if (!AutoPilot.enabled() || !AbstractDungeon.isScreenUp) {
            return null;
        }
        if (AbstractDungeon.screen != AbstractDungeon.CurrentScreen.GRID) {
            return null;
        }
        // 🔴 只接管战斗内的选牌。火堆升级（forUpgrade）、事件选择也用 GRID 界面，
        // 自动点确认会把玩家的选择吞掉。
        AbstractRoom room = AbstractDungeon.getCurrRoom();
        if (room == null || room.phase != AbstractRoom.RoomPhase.COMBAT) {
            return null;
        }
        return AbstractDungeon.gridSelectScreen;
    }

    /** 卡片 update 之后调用：把「刚点过」标记写进去，供界面同一帧读取。 */
    public static void markClick(AbstractCard card) {
        try {
            GridCardSelectScreen grid = activeGrid();
            if (grid == null || card == null || grid.confirmScreenUp) {
                return;
            }
            if (grid.targetGroup == null || grid.targetGroup.group == null) {
                return;
            }
            if (!grid.targetGroup.group.contains(card)) {
                return;
            }
            if (grid.selectedCards != null && grid.selectedCards.contains(card)) {
                return;
            }
            card.hb.clicked = true;
            // 记下实机选中的是哪张，对拍重放时要用同一个选择
            io.github.youqing.sts1solver.verify.ShadowTracker.onChoicePicked(card);
            Trace.info("GRID pick " + card.cardID + (card.upgraded ? "+" : ""));
        } catch (Throwable t) {
            Trace.error("grid mark failed", t);
        }
    }

    /** 确认按钮 update 之前调用：伪造一次「在这个按钮上松开鼠标」。 */
    public static void prepareConfirm(GridSelectConfirmButton button) {
        try {
            GridCardSelectScreen grid = activeGrid();
            if (grid == null || button == null || button.isDisabled || !grid.confirmScreenUp) {
                return;
            }
            if (inputSaved) {
                return;
            }
            savedX = InputHelper.mX;
            savedY = InputHelper.mY;
            savedRelease = InputHelper.justReleasedClickLeft;
            inputSaved = true;

            InputHelper.mX = (int) (button.hb.x + button.hb.width / 2f);
            InputHelper.mY = (int) (button.hb.y + button.hb.height / 2f);
            InputHelper.justReleasedClickLeft = true;
            Trace.info("GRID confirm");
        } catch (Throwable t) {
            Trace.error("grid confirm failed", t);
        }
    }

    /** 确认按钮 update 之后调用：还原输入状态，避免污染同一帧的其他判断。 */
    public static void restoreInput() {
        if (!inputSaved) {
            return;
        }
        inputSaved = false;
        InputHelper.mX = savedX;
        InputHelper.mY = savedY;
        InputHelper.justReleasedClickLeft = savedRelease;
    }
}
