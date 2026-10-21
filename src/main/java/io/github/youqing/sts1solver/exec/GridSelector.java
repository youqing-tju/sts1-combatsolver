package io.github.youqing.sts1solver.exec;

import com.megacrit.cardcrawl.cards.AbstractCard;
import com.megacrit.cardcrawl.screens.select.GridCardSelectScreen;

import io.github.youqing.sts1solver.diag.Trace;
import io.github.youqing.sts1solver.verify.AutoPilot;

/**
 * Drives the card-selection grid that some cards open (Dual Wield, Armaments, Warcry …).
 *
 * <p>Approach: <b>simulate clicks</b>, do not edit state. We set the same
 * {@code hb.clicked} flag a real mouse click would set and let the game run its own
 * confirm flow. Editing {@code selectedCards} directly would skip the game's own
 * validation (minimum counts, allowed card types).
 *
 * <p>⚠️ 重要：这个驱动必须挂在 {@link GridCardSelectScreen#update()} 上。
 * 挂 {@code AbstractDungeon.update()} 没用——界面打开时那个方法不被调用（实测：
 * 一次 GRID pick 都没有，卡在选牌界面 80 秒）。
 */
public final class GridSelector {

    private GridSelector() {
    }

    /** Called from the grid screen's own update postfix. */
    public static void drive(GridCardSelectScreen grid) {
        if (!AutoPilot.enabled() || grid == null) {
            return;
        }
        try {
            if (grid.confirmScreenUp) {
                confirm(grid);
                return;
            }
            if (grid.targetGroup != null && grid.targetGroup.group != null) {
                for (AbstractCard card : grid.targetGroup.group) {
                    if (grid.selectedCards != null && grid.selectedCards.contains(card)) {
                        continue;
                    }
                    card.hb.clicked = true;
                    Trace.info("GRID pick " + card.cardID + (card.upgraded ? "+" : ""));
                    return;
                }
            }
            confirm(grid);
        } catch (Throwable t) {
            Trace.error("grid selector failed", t);
        }
    }

    private static void confirm(GridCardSelectScreen grid) {
        if (grid.confirmButton != null && !grid.confirmButton.isDisabled) {
            grid.confirmButton.hb.clicked = true;
        }
    }
}
