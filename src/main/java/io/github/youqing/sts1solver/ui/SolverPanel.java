package io.github.youqing.sts1solver.ui;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.megacrit.cardcrawl.core.Settings;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.helpers.FontHelper;
import com.megacrit.cardcrawl.helpers.ImageMaster;
import com.megacrit.cardcrawl.helpers.input.InputHelper;

import io.github.youqing.sts1solver.diag.Trace;
import io.github.youqing.sts1solver.verify.AutoPilot;
import io.github.youqing.sts1solver.verify.ShadowTracker;

/**
 * M0 skeleton panel.
 *
 * <p>Purpose is narrow on purpose: prove that we can (1) draw on top of the real
 * game frame, (2) move the panel with the mouse, (3) receive a click without
 * stealing input from the game, (4) keep working while a combat runs.
 *
 * <p>Layout is measured from the actual font metrics instead of hard-coded pixel
 * offsets: the game ships several fonts with very different line heights, and a fixed
 * row pitch made the rows overlap (and a fixed width made long lines overflow).
 *
 * <p>The panel is anchored by its <b>top</b> edge, not its bottom: content grows
 * downward, so a longer line can never push the panel into the game's top bar.
 *
 * <p>All input is polled from {@link InputHelper}, so we never install a custom
 * InputProcessor and never fight the game for input.
 */
public final class SolverPanel {

    private static final float PAD = 14f;
    private static final float ROW_GAP = 8f;
    private static final float BUTTON_W = 96f;
    private static final float BUTTON_H = 34f;
    private static final float TITLE_BAR = 34f;
    private static final float MIN_W = 380f;
    private static final float MAX_W = 900f;
    private static final float TOP_MARGIN = 190f;

    private static float x = 40f;
    private static float topY = Float.NaN;
    private static boolean placed;

    private static boolean dragging;
    private static float dragDX;
    private static float dragDY;

    private static int pingCount;
    private static String combatLine = "combat: (none)";

    // 版面缓存：只在文本变化时重算，避免每帧分配 GlyphLayout
    private static float panelW = MIN_W;
    private static float panelH = 180f;
    private static float rowPitch = 30f;
    private static boolean layoutDirty = true;
    private static int lastMatches = -1;
    private static int lastMismatches = -1;
    private static boolean lastAuto;

    private SolverPanel() {
    }

    /** Diff counters change while playing; the layout must follow them. */
    private static void refreshDynamicState() {
        int m = ShadowTracker.matches();
        int bad = ShadowTracker.mismatches();
        boolean auto = AutoPilot.enabled();
        if (m != lastMatches || bad != lastMismatches || auto != lastAuto) {
            lastMatches = m;
            lastMismatches = bad;
            lastAuto = auto;
            layoutDirty = true;
        }
    }

    public static void initialize() {
        // 注意：此刻 Settings.HEIGHT 仍是 0（游戏还没建窗口），不能在这里算坐标。
        Trace.info("panel registered (placement deferred until first frame)");
    }

    public static void onCombatStart(String summary) {
        combatLine = "combat: " + summary;
        layoutDirty = true;
        Trace.info("panel sees combat start: " + summary);
    }

    /**
     * Settings.WIDTH/HEIGHT are only valid once the game viewport exists, so the panel
     * position has to be resolved on the first rendered frame, not in initialize().
     */
    private static void ensurePlaced() {
        if (placed) {
            return;
        }
        float vw = Settings.WIDTH > 0 ? Settings.WIDTH : 1920f;
        float vh = Settings.HEIGHT > 0 ? Settings.HEIGHT : 1080f;
        x = 40f;
        topY = vh - TOP_MARGIN;
        placed = true;
        Trace.info("panel placed at x=" + x + " top=" + topY + " (viewport " + vw + "x" + vh + ")");
    }

    private static BitmapFont uiFont() {
        if (FontHelper.cardDescFont_N != null) {
            return FontHelper.cardDescFont_N;
        }
        return FontHelper.charDescFont;
    }

    private static String[] lines() {
        int m = ShadowTracker.matches();
        int bad = ShadowTracker.mismatches();
        return new String[]{
                "STS1 Combat Solver",
                "v0.0.1  M1 diff harness   |   diff " + m + "/" + (m + bad)
                        + "   |   AUTO " + (AutoPilot.enabled() ? "ON" : "OFF"),
                combatLine,
                "click AUTO to auto-play mirrored cards"
        };
    }

    private static void ensureLayout() {
        if (!layoutDirty) {
            return;
        }
        BitmapFont font = uiFont();
        if (font == null) {
            return;
        }
        String[] rows = lines();
        GlyphLayout layout = new GlyphLayout();
        float widest = 0f;
        for (String row : rows) {
            layout.setText(font, row);
            widest = Math.max(widest, layout.width);
        }

        // 行高由字体自身决定；固定 24-26px 的间距小于字高，四行会叠在一起
        rowPitch = Math.max(font.getLineHeight(), font.getCapHeight()) + ROW_GAP;
        panelW = Math.min(MAX_W, Math.max(MIN_W, widest + PAD * 2f));
        panelH = PAD + rows.length * rowPitch + 10f + BUTTON_H + PAD;

        layoutDirty = false;
        Trace.info("panel layout " + Math.round(panelW) + "x" + Math.round(panelH)
                + " fontLineHeight=" + font.getLineHeight());
    }

    private static float bottom() {
        return topY - panelH;
    }

    // ------------------------------------------------------------------ render

    public static void render(SpriteBatch sb) {
        if (!isInDungeon()) {
            return;
        }
        try {
            ensurePlaced();
            refreshDynamicState();
            ensureLayout();

            BitmapFont font = uiFont();
            if (font == null) {
                return;
            }

            float y = bottom();

            sb.setColor(new Color(0f, 0f, 0f, 0.82f));
            sb.draw(ImageMaster.WHITE_SQUARE_IMG, x, y, panelW, panelH);

            sb.setColor(new Color(0.55f, 0.58f, 0.62f, 0.95f));
            sb.draw(ImageMaster.WHITE_SQUARE_IMG, x, topY - 2f, panelW, 2f);
            sb.draw(ImageMaster.WHITE_SQUARE_IMG, x, y, panelW, 2f);

            String[] rows = lines();
            Color[] colors = new Color[]{
                    Color.WHITE,
                    new Color(0.75f, 0.78f, 0.82f, 1f),
                    new Color(0.72f, 0.9f, 0.72f, 1f),
                    new Color(0.6f, 0.62f, 0.66f, 1f)
            };
            for (int i = 0; i < rows.length; i++) {
                float rowTop = topY - PAD - i * rowPitch;
                FontHelper.renderFontLeftTopAligned(sb, font, rows[i], x + PAD, rowTop, colors[i]);
            }

            // button
            float bx = x + panelW - BUTTON_W - PAD;
            float by = y + PAD;
            sb.setColor(new Color(0.18f, 0.32f, 0.5f, 0.95f));
            sb.draw(ImageMaster.WHITE_SQUARE_IMG, bx, by, BUTTON_W, BUTTON_H);
            FontHelper.renderFontCentered(sb, font, AutoPilot.enabled() ? "ON" : "AUTO",
                    bx + BUTTON_W / 2f, by + BUTTON_H / 2f + 6f, Color.WHITE);

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
            ensurePlaced();
            ensureLayout();
            if (InputHelper.justClickedLeft) {
                if (inButton()) {
                    AutoPilot.toggle();
                    layoutDirty = true;
                } else if (inTitleBar()) {
                    dragging = true;
                    dragDX = InputHelper.mX - x;
                    dragDY = InputHelper.mY - topY;
                }
            }

            if (dragging) {
                if (InputHelper.isMouseDown) {
                    x = InputHelper.mX - dragDX;
                    topY = InputHelper.mY - dragDY;
                    clampToScreen();
                } else {
                    dragging = false;
                    Trace.info("panel moved to " + Math.round(x) + "," + Math.round(bottom()));
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
        float bx = x + panelW - BUTTON_W - PAD;
        float by = bottom() + PAD;
        return InputHelper.mX >= bx && InputHelper.mX <= bx + BUTTON_W
                && InputHelper.mY >= by && InputHelper.mY <= by + BUTTON_H;
    }

    private static boolean inTitleBar() {
        return InputHelper.mX >= x && InputHelper.mX <= x + panelW
                && InputHelper.mY >= topY - TITLE_BAR && InputHelper.mY <= topY;
    }

    private static void clampToScreen() {
        if (x < 0f) {
            x = 0f;
        }
        if (x > Settings.WIDTH - panelW) {
            x = Settings.WIDTH - panelW;
        }
        if (topY > Settings.HEIGHT) {
            topY = Settings.HEIGHT;
        }
        if (topY - panelH < 0f) {
            topY = panelH;
        }
    }
}
