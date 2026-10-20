package io.github.youqing.sts1solver.ui;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.megacrit.cardcrawl.core.Settings;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.helpers.FontHelper;
import com.megacrit.cardcrawl.helpers.ImageMaster;
import com.megacrit.cardcrawl.helpers.input.InputHelper;

import io.github.youqing.sts1solver.diag.Trace;

/**
 * M0 skeleton panel.
 *
 * <p>Purpose is narrow on purpose: prove that we can (1) draw on top of the real
 * game frame, (2) move the panel with the mouse, (3) receive a click without
 * stealing input from the game, (4) keep working while a combat runs.
 *
 * <p>All input is polled from {@link InputHelper}, so we never install a custom
 * InputProcessor and never fight the game for input.
 */
public final class SolverPanel {

    private static final float WIDTH = 440f;
    private static final float HEIGHT = 148f;
    private static final float BUTTON_W = 96f;
    private static final float BUTTON_H = 34f;
    private static final float TITLE_BAR = 28f;

    private static float x = 40f;
    private static float y = Settings.HEIGHT - 200f;

    private static boolean dragging;
    private static float dragDX;
    private static float dragDY;

    private static int pingCount;
    private static String combatLine = "combat: (none)";

    private SolverPanel() {
    }

    public static void initialize() {
        Trace.info("panel initialized at " + x + "," + y);
    }

    public static void onCombatStart(String summary) {
        combatLine = "combat: " + summary;
        Trace.info("panel sees combat start: " + summary);
    }

    // ------------------------------------------------------------------ render

    public static void render(SpriteBatch sb) {
        if (!isInDungeon()) {
            return;
        }
        try {
            sb.setColor(new Color(0f, 0f, 0f, 0.78f));
            sb.draw(ImageMaster.WHITE_SQUARE_IMG, x, y, WIDTH, HEIGHT);

            sb.setColor(new Color(0.55f, 0.58f, 0.62f, 0.95f));
            sb.draw(ImageMaster.WHITE_SQUARE_IMG, x, y + HEIGHT - 2f, WIDTH, 2f);
            sb.draw(ImageMaster.WHITE_SQUARE_IMG, x, y, WIDTH, 2f);

            FontHelper.renderFontLeftTopAligned(sb, FontHelper.charDescFont,
                    "STS1 Combat Solver", x + 10f, y + HEIGHT - 8f, Color.WHITE);

            FontHelper.renderFontLeftTopAligned(sb, FontHelper.charDescFont,
                    "v0.0.1  M0 skeleton  |  ping " + pingCount,
                    x + 10f, y + HEIGHT - 34f, new Color(0.75f, 0.78f, 0.82f, 1f));

            FontHelper.renderFontLeftTopAligned(sb, FontHelper.charDescFont,
                    combatLine, x + 10f, y + HEIGHT - 58f, new Color(0.72f, 0.9f, 0.72f, 1f));

            FontHelper.renderFontLeftTopAligned(sb, FontHelper.charDescFont,
                    "drag the title bar; click PING", x + 10f, y + HEIGHT - 82f,
                    new Color(0.6f, 0.62f, 0.66f, 1f));

            // button
            sb.setColor(new Color(0.18f, 0.32f, 0.5f, 0.95f));
            sb.draw(ImageMaster.WHITE_SQUARE_IMG, x + WIDTH - BUTTON_W - 10f, y + 12f, BUTTON_W, BUTTON_H);
            FontHelper.renderFontCentered(sb, FontHelper.charDescFont, "PING",
                    x + WIDTH - BUTTON_W / 2f - 10f, y + 12f + BUTTON_H / 2f + 6f, Color.WHITE);

            sb.setColor(Color.WHITE);
        } catch (Throwable t) {
            Trace.error("panel render failed", t);
        }
    }

    // ------------------------------------------------------------------ update

    public static void update() {
        if (!isInDungeon()) {
            return;
        }
        try {
            if (InputHelper.justClickedLeft) {
                if (inButton()) {
                    pingCount++;
                    Trace.info("PING " + pingCount);
                } else if (inTitleBar()) {
                    dragging = true;
                    dragDX = InputHelper.mX - x;
                    dragDY = InputHelper.mY - y;
                }
            }

            if (dragging) {
                if (InputHelper.isMouseDown) {
                    x = InputHelper.mX - dragDX;
                    y = InputHelper.mY - dragDY;
                    clampToScreen();
                } else {
                    dragging = false;
                    Trace.info("panel moved to " + Math.round(x) + "," + Math.round(y));
                }
            }
        } catch (Throwable t) {
            Trace.error("panel update failed", t);
        }
    }

    // ------------------------------------------------------------------- utils

    private static boolean isInDungeon() {
        return AbstractDungeon.player != null;
    }

    private static boolean inButton() {
        float bx = x + WIDTH - BUTTON_W - 10f;
        float by = y + 12f;
        return InputHelper.mX >= bx && InputHelper.mX <= bx + BUTTON_W
                && InputHelper.mY >= by && InputHelper.mY <= by + BUTTON_H;
    }

    private static boolean inTitleBar() {
        return InputHelper.mX >= x && InputHelper.mX <= x + WIDTH
                && InputHelper.mY >= y + HEIGHT - TITLE_BAR && InputHelper.mY <= y + HEIGHT;
    }

    private static void clampToScreen() {
        if (x < 0f) {
            x = 0f;
        }
        if (y < 0f) {
            y = 0f;
        }
        if (x > Settings.WIDTH - WIDTH) {
            x = Settings.WIDTH - WIDTH;
        }
        if (y > Settings.HEIGHT - HEIGHT) {
            y = Settings.HEIGHT - HEIGHT;
        }
    }
}
