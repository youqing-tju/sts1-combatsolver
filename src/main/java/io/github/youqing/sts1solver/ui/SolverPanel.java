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

import io.github.youqing.sts1solver.config.SolverConfig;
import io.github.youqing.sts1solver.diag.Trace;
import io.github.youqing.sts1solver.verify.AutoPilot;
import io.github.youqing.sts1solver.verify.ShadowTracker;

/**
 * 求解器面板。
 *
 * <p>版式照蓝本（{@code reference/sts2-combatsolver/src/UI/SolverOverlay.cs}）的分区思路：
 * <b>标题 / 状态 / 路线 / 操作</b> 四段，段与段之间留白，数字右对齐在同一列。
 * 但视觉沿用我们原来的「半透明黑底 + 细描边」风格，不搬它的贴图。
 *
 * <p>三条硬约束（都是踩过的坑）：
 * <ol>
 *   <li><b>宽度不能被内容撑爆</b>：早先按最长文本算宽度、又用 {@code MAX_W} 截断，
 *       长行会直接画到面板外面去。现在先定宽度，再把文本截断成省略号。</li>
 *   <li><b>按钮不能互相压</b>：按固定宽度从右往左排，间距固定，且面板宽度保证装得下。</li>
 *   <li><b>位置延迟到第一帧</b>：{@code Settings.WIDTH/HEIGHT} 在 initialize 时还是 0。</li>
 * </ol>
 */
public final class SolverPanel {

    private static final float PAD = 14f;
    private static final float ROW_GAP = 8f;
    private static final float BUTTON_H = 34f;
    private static final float BUTTON_GAP = 8f;
    private static final float TITLE_BAR = 34f;
    private static final float MIN_W = 470f;
    private static final float MAX_W = 760f;
    private static final float TOP_MARGIN = 190f;
    private static final int MAX_PLAN_ROWS = 3;

    private static final float AUTO_BUTTON_W = 96f;
    private static final float BUDGET_BUTTON_W = 150f;
    private static final float AUTOSTART_BUTTON_W = 130f;
    private static final float BUTTONS_W = AUTO_BUTTON_W + BUDGET_BUTTON_W
            + AUTOSTART_BUTTON_W + BUTTON_GAP * 2f;

    private static float x = 40f;
    private static float topY = Float.NaN;
    private static boolean placed;

    private static boolean dragging;
    private static float dragDX;
    private static float dragDY;

    private static String combatLine = "combat: (none)";

    // 版面缓存：只在文本变化时重算，避免每帧分配 GlyphLayout
    private static float panelW = MIN_W;
    private static float panelH = 180f;
    private static float rowPitch = 30f;
    private static boolean layoutDirty = true;
    private static int lastMatches = -1;
    private static int lastMismatches = -1;
    private static boolean lastAuto;
    private static String lastBudget;
    private static int lastPlanSize = -1;

    private static final GlyphLayout MEASURE = new GlyphLayout();

    private SolverPanel() {
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

    /** Diff counters / 计划 / 档位变化时都要重排。 */
    private static void refreshDynamicState() {
        int m = ShadowTracker.matches();
        int bad = ShadowTracker.mismatches();
        boolean auto = AutoPilot.enabled();
        String budget = SolverConfig.budget().name();
        int planSize = AutoPilot.planLines(MAX_PLAN_ROWS).size();
        if (m != lastMatches || bad != lastMismatches || auto != lastAuto
                || planSize != lastPlanSize
                || (lastBudget != null && !lastBudget.equals(budget))) {
            lastMatches = m;
            lastMismatches = bad;
            lastAuto = auto;
            lastBudget = budget;
            lastPlanSize = planSize;
            layoutDirty = true;
        }
    }

    /**
     * Settings.WIDTH/HEIGHT 只有等游戏视口建好才有效，所以第一帧才定位。
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

    // ------------------------------------------------------------------- 内容

    /** 面板的全部行，按 [标题, 状态, 战斗, 路线头, 计划…] 的顺序。 */
    private static Row[] rows() {
        java.util.List<Row> out = new java.util.ArrayList<Row>();
        out.add(new Row("STS1 Combat Solver   build "
                + io.github.youqing.sts1solver.BuildStamp.VALUE,
                Color.WHITE, true));
        out.add(new Row("diff " + ShadowTracker.matches() + "/"
                + (ShadowTracker.matches() + ShadowTracker.mismatches())
                + "      AUTO " + (AutoPilot.enabled() ? "ON" : "OFF"),
                new Color(0.78f, 0.81f, 0.85f, 1f), false));
        out.add(new Row(combatLine, new Color(0.72f, 0.9f, 0.72f, 1f), false));

        String route = AutoPilot.planSummaryLine();
        if (route != null && !route.isEmpty()) {
            out.add(new Row(route, new Color(0.92f, 0.86f, 0.6f, 1f), false));
            for (String line : AutoPilot.planLines(MAX_PLAN_ROWS)) {
                out.add(new Row(line, new Color(0.64f, 0.8f, 0.96f, 1f), false));
            }
        } else {
            out.add(new Row(AutoPilot.enabled()
                    ? "planning…" : "click AUTO to plan this combat",
                    new Color(0.6f, 0.62f, 0.66f, 1f), false));
        }
        return out.toArray(new Row[0]);
    }

    private static final class Row {
        final String text;
        final Color color;
        final boolean title;

        Row(String text, Color color, boolean title) {
            this.text = text;
            this.color = color;
            this.title = title;
        }
    }

    /**
     * 按面板宽度把文本裁成省略号。
     *
     * <p>之前没有这一步：长行会一路画到面板外面（截图里那段 "click AUTO …" 就是）。
     */
    private static String fit(BitmapFont font, String text, float maxWidth) {
        MEASURE.setText(font, text);
        if (MEASURE.width <= maxWidth) {
            return text;
        }
        String ellipsis = "…";
        int lo = 0;
        int hi = text.length();
        while (lo < hi) {
            int mid = (lo + hi + 1) / 2;
            MEASURE.setText(font, text.substring(0, mid) + ellipsis);
            if (MEASURE.width <= maxWidth) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        return text.substring(0, Math.max(0, lo)) + ellipsis;
    }

    private static void ensureLayout() {
        if (!layoutDirty) {
            return;
        }
        BitmapFont font = uiFont();
        if (font == null) {
            return;
        }
        rowPitch = Math.max(font.getLineHeight(), font.getCapHeight()) + ROW_GAP;

        // 🔴 先定宽度：不按内容撑，固定区间；按钮总宽是下限
        Row[] rows = rows();
        panelW = Math.min(MAX_W, Math.max(MIN_W, BUTTONS_W + PAD * 2f + 8f));
        int lineCount = rows.length;
        panelH = PAD + lineCount * rowPitch + 12f + BUTTON_H + PAD;

        layoutDirty = false;
        Trace.info("panel layout " + Math.round(panelW) + "x" + Math.round(panelH)
                + " rows=" + lineCount + " fontLineHeight=" + font.getLineHeight());
    }

    private static float bottom() {
        return topY - panelH;
    }

    private static float rowTop(int index) {
        return topY - PAD - index * rowPitch;
    }

    private static float buttonY() {
        return bottom() + PAD;
    }

    // ---------------------------------------------------------------- 按钮几何

    private static float autoButtonX() {
        return x + panelW - PAD - AUTO_BUTTON_W;
    }

    private static float budgetButtonX() {
        return autoButtonX() - BUTTON_GAP - BUDGET_BUTTON_W;
    }

    private static float autoStartButtonX() {
        return budgetButtonX() - BUTTON_GAP - AUTOSTART_BUTTON_W;
    }

    private static boolean inRect(float bx, float by, float bw, float bh) {
        return InputHelper.mX >= bx && InputHelper.mX <= bx + bw
                && InputHelper.mY >= by && InputHelper.mY <= by + bh;
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
            float textMax = panelW - PAD * 2f;

            // 底板 + 两条细边
            sb.setColor(new Color(0f, 0f, 0f, 0.78f));
            sb.draw(ImageMaster.WHITE_SQUARE_IMG, x, y, panelW, panelH);
            sb.setColor(new Color(0.55f, 0.58f, 0.62f, 0.9f));
            sb.draw(ImageMaster.WHITE_SQUARE_IMG, x, topY - 2f, panelW, 2f);
            sb.draw(ImageMaster.WHITE_SQUARE_IMG, x, y, panelW, 2f);

            Row[] rows = rows();
            for (int i = 0; i < rows.length; i++) {
                FontHelper.renderFontLeftTopAligned(sb, font, fit(font, rows[i].text, textMax),
                        x + PAD, rowTop(i), rows[i].color);
            }

            // 标题下的一条分隔线（蓝本也是这么分段的）
            float sepY = rowTop(0) - rowPitch * 0.55f;
            sb.setColor(new Color(1f, 1f, 1f, 0.12f));
            sb.draw(ImageMaster.WHITE_SQUARE_IMG, x + PAD, sepY, panelW - PAD * 2f, 1.5f);

            drawButton(sb, font, autoStartButtonX(), buttonY(), AUTOSTART_BUTTON_W,
                    "auto-start " + (SolverConfig.autoStart() ? "ON" : "OFF"),
                    new Color(0.24f, 0.30f, 0.26f, 0.95f));
            drawButton(sb, font, budgetButtonX(), buttonY(), BUDGET_BUTTON_W,
                    "budget " + SolverConfig.budget().label,
                    new Color(0.24f, 0.28f, 0.36f, 0.95f));
            drawButton(sb, font, autoButtonX(), buttonY(), AUTO_BUTTON_W,
                    AutoPilot.enabled() ? "AUTO ON" : "AUTO",
                    AutoPilot.enabled()
                            ? new Color(0.20f, 0.42f, 0.28f, 0.95f)
                            : new Color(0.20f, 0.30f, 0.48f, 0.95f));

            sb.setColor(Color.WHITE);
        } catch (Throwable t) {
            Trace.error("panel render failed", t);
        }
    }

    private static void drawButton(SpriteBatch sb, BitmapFont font, float bx, float by,
                                   float bw, String label, Color color) {
        sb.setColor(color);
        sb.draw(ImageMaster.WHITE_SQUARE_IMG, bx, by, bw, BUTTON_H);
        FontHelper.renderFontCentered(sb, font, label, bx + bw / 2f, by + BUTTON_H / 2f + 6f,
                Color.WHITE);
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
                float by = buttonY();
                if (inRect(autoStartButtonX(), by, AUTOSTART_BUTTON_W, BUTTON_H)) {
                    SolverConfig.toggleAutoStart();
                    layoutDirty = true;
                } else if (inRect(budgetButtonX(), by, BUDGET_BUTTON_W, BUTTON_H)) {
                    SolverConfig.cycleBudget();
                    layoutDirty = true;
                } else if (inRect(autoButtonX(), by, AUTO_BUTTON_W, BUTTON_H)) {
                    AutoPilot.toggle();
                    layoutDirty = true;
                } else if (inRect(x, topY - TITLE_BAR, panelW, TITLE_BAR)) {
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
