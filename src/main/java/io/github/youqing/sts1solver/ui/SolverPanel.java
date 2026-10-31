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
 * <b>标题 / 状态 / 路线 / 操作</b> 四段，段与段之间留白，按钮固定在底部一行。
 * 视觉沿用我们原来的「半透明黑底 + 细描边」风格，不搬它的贴图。
 *
 * <p>四条硬约束（都是踩过的坑）：
 * <ol>
 *   <li><b>宽度不能被内容撑爆</b>：长行按面板宽度截断成省略号。
 *       反过来说宽度也要够：{@link #MAX_W} 是上限，正常宽度由「最宽的一行」决定。</li>
 *   <li><b>按钮不能互相压</b>：按钮宽度 = <b>实测文字宽 + 内边距</b>，从右往左排，
 *       间距固定。以前写死 96/130/150 —— 在 2560×1440 下字体行高 56，
 *       「auto-start ON」有 260px 宽，画进 130px 的框里就直接压到隔壁按钮的文字上
 *       （2026-10-30 用户截图反馈「字样混在一起」）。</li>
 *   <li><b>字体按分辨率选</b>：{@code cardDescFont_N} 是卡面说明字体（行高 56），
 *       拿来画面板相当于用海报字写便签。现在按 {@code 30 * Settings.scale}
 *       在候选字体里挑最接近的。</li>
 *   <li><b>位置延迟到第一帧</b>：{@code Settings.WIDTH/HEIGHT} 在 initialize 时还是 0。</li>
 * </ol>
 */
public final class SolverPanel {

    private static final float PAD = 14f;
    private static final float ROW_GAP = 6f;
    private static final float BUTTON_H = 34f;
    private static final float BUTTON_GAP = 8f;
    private static final float BUTTON_TEXT_PAD = 13f;
    private static final float TITLE_BAR = 34f;
    private static final float MIN_W = 360f;
    private static final float MAX_W = 820f;
    private static final float TOP_MARGIN = 190f;
    private static final int MAX_PLAN_ROWS = 3;
    /** 面板正文目标字号（1080p 下），实际按 {@link Settings#scale} 放大。 */
    private static final float BODY_TARGET_PX = 27f;

    private static final String TITLE = "STS1 Combat Solver";
    private static final String BUILD = "build "
            + io.github.youqing.sts1solver.BuildStamp.VALUE;

    private static float x = 40f;
    private static float topY = Float.NaN;
    private static boolean placed;

    private static boolean dragging;
    private static float dragDX;
    private static float dragDY;

    private static String combatLine = "combat: (none)";
    /**
     * 每回合刷新一次的「这一回合的真相」：`A1F14 T3 · hp 36/80 e3 · 敌 Lagavulin 94 (意图20)`。
     *
     * <p>为什么不每帧现读：{@code SimState.capture()} 要扫牌堆/能力/遗物，每帧调会把帧率吃掉。
     * 由 {@code AutoPilot} 在回合开头落快照时顺手推一次（每回合一次，成本可忽略）。
     */
    private static String turnLine = "";
    /** 药水对账闸的最近结论（蓝本 PotionStrategicCost 的可读版），没有就不占行。 */
    private static String strategyLine = "";

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

    /** 三个按钮的实测宽度：{AUTO, budget, auto-start}（下标 0 是最右边那个）。 */
    private static final float[] buttonW = new float[3];
    private static final float[] BUTTON_MIN_W = {72f, 96f, 104f};
    private static float buttonsTotal;

    private static BitmapFont cachedBodyFont;
    /** 面板字体相对原字号的缩放（原版字体太大，见 {@link #uiFont()}）。 */
    private static float uiScale = 1f;
    private static boolean baseScaleCaptured;
    private static float baseScaleX = 1f;
    private static float baseScaleY = 1f;
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

    /** AutoPilot 每回合开头推一次：`A1F14 T3 · hp 36/80 e3 · 敌 X 94 (意图20)`。 */
    public static void setTurnLine(String line) {
        if (line == null ? turnLine == null : line.equals(turnLine)) {
            return;
        }
        turnLine = line;
        layoutDirty = true;
    }

    /** 药水/遗物/成长的结论行（没有就传空串，面板不占行）。 */
    public static void setStrategyLine(String line) {
        if (line == null ? strategyLine == null : line.equals(strategyLine)) {
            return;
        }
        strategyLine = line;
        layoutDirty = true;
    }

    /** 战斗结束：面板别一直挂着上一场的战况（用户会以为还在打）。 */
    public static void onCombatEnd() {
        combatLine = "combat: (none)";
        turnLine = "";
        strategyLine = "";
        layoutDirty = true;
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

    /**
     * 正文 / 按钮用的字体：在几个候选里挑行高最接近 {@code 30 * Settings.scale} 的。
     *
     * <p>{@code Settings.scale} 在第一次渲染前是 0，这时退回 1。
     *
     * <p>⚠️ 原版所有字体在 1440p 下行高都 ≥ 52（卡面字体 56），挑最近的也还是「海报字」。
     * 所以选中之后按 {@link #uiScale} 把字体的 data 缩下来用 —— 只在我们的绘制窗口内生效，
     * 画完立刻还原（见 {@link #applyFontScale()} / {@link #restoreFontScale()}）。
     */
    private static BitmapFont uiFont() {
        if (cachedBodyFont != null) {
            return cachedBodyFont;
        }
        float scale = Settings.scale > 0f ? Settings.scale : 1f;
        float target = BODY_TARGET_PX * scale;
        BitmapFont[] candidates = {
                FontHelper.tipBodyFont,
                FontHelper.buttonLabelFont,
                FontHelper.healthInfoFont,
                FontHelper.cardDescFont_N,
                FontHelper.charDescFont
        };
        BitmapFont best = null;
        float bestDelta = Float.MAX_VALUE;
        for (BitmapFont font : candidates) {
            if (font == null || font.getLineHeight() <= 0f) {
                continue;
            }
            float delta = Math.abs(font.getLineHeight() - target);
            if (delta < bestDelta) {
                bestDelta = delta;
                best = font;
            }
        }
        cachedBodyFont = best;
        if (best != null) {
            float lineHeight = best.getLineHeight();
            uiScale = Math.min(1f, Math.max(0.4f, target / lineHeight));
            Trace.info("panel font lineHeight=" + lineHeight
                    + " (target " + Math.round(target) + ", scale " + uiScale + ")");
        }
        // Settings.scale 还没定下来时先不缓存，等真正的视口建好再挑一次
        if (Settings.scale <= 0f) {
            cachedBodyFont = null;
        }
        return best;
    }

    /** 把面板字体的 data 缩到 {@link #uiScale}（原版字体太大，不缩就装不下几个字）。 */
    private static void applyFontScale() {
        BitmapFont font = uiFont();
        if (font == null) {
            return;
        }
        BitmapFont.BitmapFontData data = font.getData();
        if (!baseScaleCaptured) {
            baseScaleX = data.scaleX;
            baseScaleY = data.scaleY;
            baseScaleCaptured = true;
        }
        data.setScale(baseScaleX * uiScale, baseScaleY * uiScale);
    }

    /** 画完必须还原：这是游戏自己的字体对象，别的界面也在用。 */
    private static void restoreFontScale() {
        BitmapFont font = cachedBodyFont;
        if (font == null || !baseScaleCaptured) {
            return;
        }
        font.getData().setScale(baseScaleX, baseScaleY);
    }

    // ------------------------------------------------------------------- 内容

    /** 面板的全部行，按 [标题, 状态, 战斗, 路线头, 计划…] 的顺序。 */
    private static Row[] rows() {
        java.util.List<Row> out = new java.util.ArrayList<Row>();
        out.add(new Row(TITLE, Color.WHITE, true));
        out.add(new Row("diff " + ShadowTracker.matches() + "/"
                + (ShadowTracker.matches() + ShadowTracker.mismatches())
                + "      AUTO " + (AutoPilot.enabled() ? "ON" : "OFF"),
                new Color(0.78f, 0.81f, 0.85f, 1f), false));
        // 状态条：这一回合的真局面（AutoPilot 每回合推一次）
        out.add(new Row(turnLine.isEmpty() ? combatLine : turnLine,
                new Color(0.72f, 0.9f, 0.72f, 1f), false));
        if (!strategyLine.isEmpty()) {
            out.add(new Row(strategyLine, new Color(0.86f, 0.78f, 0.62f, 1f), false));
        }

        String route = AutoPilot.planSummaryLine();
        if (route != null && !route.isEmpty()) {
            out.add(new Row(route, new Color(0.92f, 0.86f, 0.6f, 1f), false));
            // 第一行是「本回合」，用亮色；后面是推演，用暗色（蓝本动作条也是当前档高亮）
            boolean first = true;
            for (String line : AutoPilot.planLines(MAX_PLAN_ROWS)) {
                Color c = first ? new Color(0.75f, 0.92f, 1f, 1f)
                        : new Color(0.55f, 0.68f, 0.82f, 1f);
                out.add(new Row(line, c, false));
                first = false;
            }
        } else {
            // 开了宽度组合 + 新颖度之后单步要 1~5 秒：把「已等多少秒」显示出来，
            // 否则和「卡死」长得一模一样（2026-10-30 用户就报过一次）。
            long waited = AutoPilot.searchElapsedMillis();
            String text;
            if (!AutoPilot.enabled()) {
                text = "click AUTO to plan this combat";
            } else if (waited >= 0) {
                text = String.format("planning… %.1fs   (beam portfolio + novelty)", waited / 1000.0);
            } else {
                text = "waiting for combat…";
            }
            out.add(new Row(text, new Color(0.6f, 0.62f, 0.66f, 1f), false));
        }
        return out.toArray(new Row[0]);
    }

    /** 底部三个按钮的文字（下标 0 在最右）。 */
    private static String[] buttonLabels() {
        return new String[]{
                AutoPilot.enabled() ? "AUTO ON" : "AUTO",
                "budget " + SolverConfig.budget().label,
                "auto-start " + (SolverConfig.autoStart() ? "ON" : "OFF")
        };
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
     * 按面板宽度把文本裁成省略号（只有超长行才会触发）。
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

    private static float textWidth(BitmapFont font, String text) {
        if (text == null || text.isEmpty()) {
            return 0f;
        }
        MEASURE.setText(font, text);
        return MEASURE.width;
    }

    private static void ensureLayout() {
        if (!layoutDirty) {
            return;
        }
        BitmapFont font = uiFont();
        if (font == null) {
            return;
        }
        rowPitch = font.getLineHeight() + ROW_GAP;

        // ① 按钮宽度按实测文字算，绝不写死 —— 写死就会「文字压到隔壁按钮上」
        String[] labels = buttonLabels();
        buttonsTotal = 0f;
        for (int i = 0; i < labels.length; i++) {
            float w = textWidth(font, labels[i]) + BUTTON_TEXT_PAD * 2f;
            buttonW[i] = Math.max(BUTTON_MIN_W[i], w);
            buttonsTotal += buttonW[i];
            if (i > 0) {
                buttonsTotal += BUTTON_GAP;
            }
        }

        // ② 面板宽度 = max(最宽一行, 按钮总宽)，落在 [MIN_W, MAX_W]
        Row[] rows = rows();
        float widest = textWidth(font, TITLE) + 24f + textWidth(font, BUILD);
        for (Row row : rows) {
            widest = Math.max(widest, textWidth(font, row.text));
        }
        panelW = Math.min(MAX_W, Math.max(MIN_W,
                Math.max(widest + PAD * 2f, buttonsTotal + PAD * 2f)));

        panelH = PAD + rows.length * rowPitch + ROW_GAP + BUTTON_H + PAD;

        layoutDirty = false;
        Trace.info("panel layout " + Math.round(panelW) + "x" + Math.round(panelH)
                + " rows=" + rows.length + " fontLineHeight=" + font.getLineHeight()
                + " buttons=" + Math.round(buttonW[0]) + "/" + Math.round(buttonW[1])
                + "/" + Math.round(buttonW[2]));
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

    /** 最右边的按钮（AUTO）左边线。 */
    private static float autoButtonX() {
        return x + panelW - PAD - buttonW[0];
    }

    private static float budgetButtonX() {
        return autoButtonX() - BUTTON_GAP - buttonW[1];
    }

    private static float autoStartButtonX() {
        return budgetButtonX() - BUTTON_GAP - buttonW[2];
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
        applyFontScale();
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
            for (int i = 1; i < rows.length; i++) {
                FontHelper.renderFontLeftTopAligned(sb, font, fit(font, rows[i].text, textMax),
                        x + PAD, rowTop(i), rows[i].color);
            }

            // 标题行：左边标题，右边 build（以前把 build 拼在标题后面，宽度不够就被截成 "…"）
            FontHelper.renderFontLeftTopAligned(sb, font, rows[0].text, x + PAD, rowTop(0),
                    Color.WHITE);
            String build = fit(font, BUILD, textMax - textWidth(font, rows[0].text) - 24f);
            FontHelper.renderFontRightTopAligned(sb, font, build, x + panelW - PAD, rowTop(0),
                    new Color(0.6f, 0.63f, 0.68f, 1f));

            // 标题下的一条分隔线（蓝本也是这么分段的）
            float sepY = rowTop(0) - rowPitch * 0.55f;
            sb.setColor(new Color(1f, 1f, 1f, 0.12f));
            sb.draw(ImageMaster.WHITE_SQUARE_IMG, x + PAD, sepY, panelW - PAD * 2f, 1.5f);

            String[] labels = buttonLabels();
            drawButton(sb, font, autoStartButtonX(), buttonY(), buttonW[2], labels[2],
                    new Color(0.24f, 0.30f, 0.26f, 0.95f));
            drawButton(sb, font, budgetButtonX(), buttonY(), buttonW[1], labels[1],
                    new Color(0.24f, 0.28f, 0.36f, 0.95f));
            drawButton(sb, font, autoButtonX(), buttonY(), buttonW[0], labels[0],
                    AutoPilot.enabled()
                            ? new Color(0.20f, 0.42f, 0.28f, 0.95f)
                            : new Color(0.20f, 0.30f, 0.48f, 0.95f));

            sb.setColor(Color.WHITE);
        } catch (Throwable t) {
            Trace.error("panel render failed", t);
        } finally {
            restoreFontScale();
        }
    }

    private static void drawButton(SpriteBatch sb, BitmapFont font, float bx, float by,
                                   float bw, String label, Color color) {
        sb.setColor(color);
        sb.draw(ImageMaster.WHITE_SQUARE_IMG, bx, by, bw, BUTTON_H);
        FontHelper.renderFontCentered(sb, font, label, bx + bw / 2f, by + BUTTON_H / 2f,
                Color.WHITE);
    }

    // ------------------------------------------------------------------ update

    public static void update() {
        if (!isInDungeon()) {
            return;
        }
        applyFontScale();
        try {
            ensurePlaced();
            ensureLayout();
            if (InputHelper.justClickedLeft) {
                float by = buttonY();
                if (inRect(autoStartButtonX(), by, buttonW[2], BUTTON_H)) {
                    SolverConfig.toggleAutoStart();
                    layoutDirty = true;
                } else if (inRect(budgetButtonX(), by, buttonW[1], BUTTON_H)) {
                    SolverConfig.cycleBudget();
                    layoutDirty = true;
                } else if (inRect(autoButtonX(), by, buttonW[0], BUTTON_H)) {
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
        } finally {
            restoreFontScale();
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
