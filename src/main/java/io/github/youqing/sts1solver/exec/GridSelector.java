package io.github.youqing.sts1solver.exec;

import com.megacrit.cardcrawl.cards.AbstractCard;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.screens.select.GridCardSelectScreen;

import io.github.youqing.sts1solver.diag.Trace;
import io.github.youqing.sts1solver.verify.AutoPilot;

/**
 * Drives the card-selection grid that some cards open (Dual Wield, Armaments, Warcry …).
 *
 * <p>Approach: <b>simulate clicks</b>, do not edit state. We set the same
 * {@code hb.clicked} flag a real mouse click would set and let the game run its own
 * confirm flow. Editing {@code selectedCards} directly would skip the game's own
 * validation (minimum counts, allowed card types), which is exactly the sort of shortcut
 * that produces runs the solver can never reproduce.
 *
 * <p>Only runs when the auto pilot is on — otherwise a human is choosing.
 */
public final class GridSelector {

    private GridSelector() {
    }

    public static void tick() {
        if (!AutoPilot.enabled()) {
            return;
        }
        try {
            if (!AbstractDungeon.isScreenUp
                    || AbstractDungeon.screen != AbstractDungeon.CurrentScreen.GRID) {
                return;
            }
            GridCardSelectScreen grid = AbstractDungeon.gridSelectScreen;
            if (grid == null) {
                return;
            }

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
