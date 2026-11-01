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
import io.github.youqing.sts1solver.search.RouteStep;
import io.github.youqing.sts1solver.verify.AutoPilot;
import io.github.youqing.sts1solver.verify.RoutePreview;
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
    /** 路线区硬上限：再长也不往下列（面板高度会被屏幕吃掉）。 */
    private static final int MAX_PLAN_ROWS = 16;
    /** 至少留几行路线（屏幕再小也要看得见下一步）。 */
    private static final int MIN_PLAN_ROWS = 3;
    /** 除路线外的固定 chrome 估算（标题/状态/提示/策略头/摘要/按钮）。 */
    private static final float PLAN_CHROME_ROWS = 6f;
    /** 屏幕上下留白。 */
    private static final float PLAN_SCREEN_MARGIN = 48f;
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
    /**
     * 醒目提示行（目前只有「预计死亡 → 已交还控制」）。
     *
     * <p>蓝本 {@code SolverOverlay.ShowFullAutoStoppedAtDeathTurn} 会改状态文字 + 摘要两处；
     * 我们只有一条状态区，所以用一行红字说清「发生了什么、为什么停」。
     */
    private static String notice = "";
    /**
     * 「策略现状（只读）」区的缓存快照（{@code AutoPilot} 在主线程动作稳定后推来）。
     *
     * <p>🔴 渲染路径<b>只读这份缓存</b>：{@code SimState.capture()} 要扫四个牌堆 + 能力 + 遗物，
     * 放到 {@link #render} / {@link #rows()} 里每帧调用会直接吃掉帧率。
     */
    private static StrategyStatusSnapshot strategyStatus;

    /** 本帧路线区最多几行（按屏幕高度算，见 {@link #planRowLimit}）。 */
    private static int planRowLimit = MAX_PLAN_ROWS;

    /**
     * 屏幕能给路线区几行。
     *
     * <p>为什么不是固定 3 行：用户要「一次性看到全局路线」，而蓝色尖塔一场战斗通常 5~12 回合；
     * 固定 3 行等于只看得见前三回合。改成按屏幕高度算，1080p 下大约 12~16 行，
     * 仍然保留 {@link #MAX_PLAN_ROWS} 的硬上限与 {@link #MIN_PLAN_ROWS} 的下限。
     */
    static int planRowLimit(float screenHeight, float rowPitch, float chromeRows) {
        if (rowPitch <= 0f) {
            return MIN_PLAN_ROWS;
        }
        float usable = screenHeight - PLAN_SCREEN_MARGIN - chromeRows * rowPitch;
        int rows = (int) Math.floor(usable / rowPitch);
        return Math.max(MIN_PLAN_ROWS, Math.min(MAX_PLAN_ROWS, rows));
    }
    /** 折叠状态：默认收起，只活在本次进程里，不写 {@code SolverConfig}。 */
    private static boolean strategyExpanded;

    // 版面缓存：只在文本变化时重算，避免每帧分配 GlyphLayout
    private static float panelW = MIN_W;
    private static float panelH = 180f;
    private static float rowPitch = 30f;
    private static boolean layoutDirty = true;
    /**
     * 本次布局用的行数组。
     *
     * <p>🔴 以前 {@link #rows()} 在 {@code ensureLayout()} 和 {@code render()} 里各建一次。
     * 两边内容一旦不同（行数变化的那一刻就会），画出来的行和点击命中的行就不是同一行 ——
     * 典型症状是「点第 3 行却触发了第 2 行」。现在只算一次，布局/渲染/命中都读这份。
     */
    private static Row[] laidOutRows;
    private static int lastMatches = -1;
    private static int lastMismatches = -1;
    private static boolean lastAuto;
    private static String lastBudget;
    private static long lastPanelRevision = -1;

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
    /** 醒目提示（空串 = 不占行）。只在「停手交还控制」这类事件里出现。 */
    public static void setNotice(String line) {
        String next = line == null ? "" : line;
        if (next.equals(notice)) {
            return;
        }
        notice = next;
        layoutDirty = true;
    }

    public static void setTurnLine(String line) {
        if (line == null ? turnLine == null : line.equals(turnLine)) {
            return;
        }
        turnLine = line;
        layoutDirty = true;
    }

    /**
     * 「策略现状（只读）」区的快照（没有就传 {@code null}，只留折叠标题）。
     *
     * <p>由 {@code AutoPilot} 在回合边界与动作稳定后各推一次；面板不自己捕获局面。
     */
    public static void setStrategyStatus(StrategyStatusSnapshot status) {
        boolean same = status == null
                ? strategyStatus == null
                : strategyStatus != null && status.lines().equals(strategyStatus.lines());
        if (same) {
            return;
        }
        strategyStatus = status;
        layoutDirty = true;
    }

    /** 战斗结束：面板别一直挂着上一场的战况（用户会以为还在打）。 */
    public static void onCombatEnd() {
        combatLine = "combat: (none)";
        notice = "";
        turnLine = "";
        // 🔴 静态 UI 状态跨战斗会残留：不清的话下一场开局会先显示上一场的药水和遗物计数。
        // 折叠偏好（strategyExpanded）属于使用习惯，保留。
        strategyStatus = null;
        layoutDirty = true;
    }

    /** Diff counters / 计划 / 档位变化时都要重排。 */
    private static void refreshDynamicState() {
        int m = ShadowTracker.matches();
        int bad = ShadowTracker.mismatches();
        boolean auto = AutoPilot.enabled();
        String budget = SolverConfig.budget().name();
        // 只比一个版本号：计划新增/替换/作废/每出一手都会让它变。
        // （旧实现每帧调 planLines() 新建一串字符串，纯浪费。）
        long revision = AutoPilot.panelRevision();
        if (m != lastMatches || bad != lastMismatches || auto != lastAuto
                || revision != lastPanelRevision
                || (lastBudget != null && !lastBudget.equals(budget))) {
            lastMatches = m;
            lastMismatches = bad;
            lastAuto = auto;
            lastBudget = budget;
            lastPanelRevision = revision;
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

    /** 面板的全部行，按 [标题, 状态, 战斗, 策略现状, 路线头, 计划…] 的顺序。 */
    private static Row[] rows() {
        java.util.List<Row> out = new java.util.ArrayList<Row>();
        out.add(new Row(TITLE, Color.WHITE, true));
        out.add(new Row("diff " + ShadowTracker.matches() + "/"
                + (ShadowTracker.matches() + ShadowTracker.mismatches())
                + "      " + autoLabel(),
                new Color(0.78f, 0.81f, 0.85f, 1f), false));
        // 状态条：这一回合的真局面（AutoPilot 每回合推一次）
        out.add(new Row(turnLine.isEmpty() ? combatLine : turnLine,
                new Color(0.72f, 0.9f, 0.72f, 1f), false));
        if (!notice.isEmpty()) {
            out.add(new Row(notice, new Color(1f, 0.45f, 0.4f, 1f), false));
        }
        appendStrategyRows(out);

        RoutePreview preview = AutoPilot.routePreview();
        if (preview != null && !preview.turns().isEmpty()) {
            boolean stale = AutoPilot.routePreviewStale();
            String summary = AutoPilot.planSummaryLine();
            if (summary != null && !summary.isEmpty()) {
                out.add(new Row(summary, new Color(0.92f, 0.86f, 0.6f, 1f), false));
            }
            // 行号来自预览里的**绝对**回合（T4/T5），不再从剩余队列现场编号（那会写成 T1/T2）。
            // 这些行是**只读**的：蓝本的路线行 MouseFilter = Ignore，没有任何「点 Tn 打到 Tn」的入口。
            RouteStep next = AutoPilot.nextRouteStep();
            int total = preview.turns().size();
            int shown = Math.min(planRowLimit, total);
            for (int i = 0; i < shown; i++) {
                RoutePreview.Turn turn = preview.turns().get(i);
                Color color;
                if (stale) {
                    // 重算期间：路线留在原地但整体变灰，用户知道「这不是最新的」
                    color = new Color(0.50f, 0.52f, 0.58f, 1f);
                } else if (!turn.endsTurn()) {
                    // 没有 END_TURN 的尾行：这条路线没把这个回合走完，暗色提示「未收敛」
                    color = new Color(0.45f, 0.48f, 0.55f, 1f);
                } else if (i == 0) {
                    color = new Color(0.75f, 0.92f, 1f, 1f);
                } else {
                    color = new Color(0.55f, 0.68f, 0.82f, 1f);
                }
                out.add(new Row(turn.text(next), color, false));
            }
            if (total > shown) {
                out.add(new Row("… 还有 " + (total - shown) + " 回合（面板高度上限）",
                        new Color(0.55f, 0.55f, 0.60f, 1f), false));
            }
            if (stale) {
                long waited = AutoPilot.searchElapsedMillis();
                String text = waited >= 0
                        ? String.format("重算中… %.1fs（上面是上一份路线）", waited / 1000.0)
                        : "重算中…（上面是上一份路线）";
                out.add(new Row(text, new Color(0.75f, 0.62f, 0.45f, 1f), false));
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

    /** 状态行里的 AUTO 文案（蓝本只有 FullAuto 开/关两个状态）。 */
    private static String autoLabel() {
        return AutoPilot.enabled() ? "AUTO ON" : "AUTO OFF";
    }

    /**
     * 「策略现状（只读）」折叠区：标题恒在，展开才按快照顺序铺行。
     *
     * <p>⚠️ 这里是**只读现状适配**，不是蓝本 {@code SolverPotionStrategyPanel} 那种控制器复刻
     * （详见 {@code docs/plans/2026-10-31-ui-parity-design.md} §1.1）。标题文案已经把
     * 「只读」「不会修改搜索」写在脸上，避免用户以为点一下就能改策略。
     */
    private static void appendStrategyRows(java.util.List<Row> out) {
        out.add(new Row(strategyExpanded
                ? "策略（药水可点 · 遗物/成长只读） [-]"
                : "策略（药水可点） [+]",
                new Color(0.86f, 0.78f, 0.62f, 1f), false,
                Row.Kind.STRATEGY_TOGGLE, true, -1, null));
        if (!strategyExpanded) {
            return;
        }
        if (strategyStatus == null || strategyStatus.lines().isEmpty()) {
            // 没有快照就说没有：留一个空标题会让人以为「点了没反应」
            out.add(new Row("（暂无快照：本回合局面还没抓取）",
                    new Color(0.55f, 0.55f, 0.60f, 1f), false));
            return;
        }
        // 药水区是**真实控制器**（D5）；遗物/成长仍是只读事实行。
        boolean locked = AutoPilot.potionControlsLocked();
        for (PotionStrategyRows.Row row : PotionStrategyRows.build(
                strategyStatus.potionSlots(), SolverConfig.potionStrategy(), locked)) {
            Color color = row.clickable()
                    ? new Color(0.78f, 0.88f, 1.0f, 1f)
                    : new Color(0.62f, 0.62f, 0.68f, 1f);
            out.add(new Row(row.label(), color, false, Row.Kind.POTION,
                    row.clickable(), row.slot - 1, row.potionId));
        }
        for (String line : strategyStatus.otherLines()) {
            out.add(new Row(line, new Color(0.70f, 0.70f, 0.76f, 1f), false));
        }
    }

    /** 底部三个按钮的文字（下标 0 在最右）。 */
    private static String[] buttonLabels() {
        return new String[]{
                AutoPilot.enabled() ? "AUTO ON" : "AUTO",
                "budget " + SolverConfig.budget().label,
                "auto-start " + (SolverConfig.autoStart() ? "ON" : "OFF")
        };
    }

    /**
     * 面板的一行，带**身份**：布局、渲染、命中判定必须认同一份 {@link Row} 数组
     * （见 {@link #laidOutRows}）。
     */
    private static final class Row {

        /** 行的种类。{@code TEXT} 只画不点；其余是可点区域。 */
        enum Kind {
            /** 纯文本行（路线预览也走这一类：蓝本的路线行不可点）。 */
            TEXT,
            /** 「策略现状（只读）」折叠标题。 */
            STRATEGY_TOGGLE,
            /** 药水区的一行：**真实控件**（点击循环 Smart → Disabled → Force）。 */
            POTION
        }

        final String text;
        final Color color;
        final boolean title;
        final Kind kind;
        /** 这一行现在能不能点（只有药水控件会用到）。 */
        final boolean clickable;
        /** 药水行的目标槽位（**0 基**，与策略键一致）；非 POTION 行为 -1。 */
        final int potionSlot;
        /** 药水行的药水 ID；非 POTION 行为 null。 */
        final String potionId;

        Row(String text, Color color, boolean title) {
            this(text, color, title, Kind.TEXT, false, -1, null);
        }

        Row(String text, Color color, boolean title, Kind kind, boolean clickable,
            int potionSlot, String potionId) {
            this.text = text;
            this.color = color;
            this.title = title;
            this.kind = kind;
            this.clickable = clickable;
            this.potionSlot = potionSlot;
            this.potionId = potionId;
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

        // ①.5 路线区预算：屏幕越高给越多行（用户要「一次看到全局路线」）
        planRowLimit = planRowLimit(com.megacrit.cardcrawl.core.Settings.HEIGHT, rowPitch,
                PLAN_CHROME_ROWS);

        // ② 面板宽度 = max(最宽一行, 按钮总宽)，落在 [MIN_W, MAX_W]
        Row[] rows = rows();
        laidOutRows = rows;
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

            Row[] rows = laidOutRows;
            if (rows == null || rows.length == 0) {
                return;
            }
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
            refreshDynamicState();
            ensureLayout();
            if (InputHelper.justClickedLeft) {
                // 行命中优先级最高：同一点击不能既折叠策略区又落到下面的按钮或拖拽标题上
                boolean consumed = hitRow();
                if (!consumed) {
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

    /**
     * 可点击行的命中判定。
     *
     * <p>用 {@link #laidOutRows} 与 {@link #rowTop} —— 和渲染是同一份行、同一套坐标，
     * 所以「看到的行」= 「点得到的行」。
     *
     * @return true 表示这次点击已被某一行消费，调用方不得再当成按钮/拖拽
     */
    private static boolean hitRow() {
        Row[] rows = laidOutRows;
        if (rows == null) {
            return false;
        }
        float left = x + PAD;
        float width = panelW - PAD * 2f;
        for (int i = 0; i < rows.length; i++) {
            Row row = rows[i];
            if (row.kind == Row.Kind.TEXT) {
                continue;
            }
            // 文字是 top-aligned 画的，所以这一行的可视带是 [rowTop - rowPitch, rowTop]
            if (!inRect(left, rowTop(i) - rowPitch, width, rowPitch)) {
                continue;
            }
            if (row.kind == Row.Kind.STRATEGY_TOGGLE) {
                strategyExpanded = !strategyExpanded;
                layoutDirty = true;
                // 展开会让面板变高，可能顶出屏幕；立刻重排并夹取
                ensureLayout();
                clampToScreen();
                Trace.info("panel strategy section "
                        + (strategyExpanded ? "expanded" : "collapsed")
                        + " rows=" + (laidOutRows == null ? 0 : laidOutRows.length));
                return true;
            }
            if (row.kind == Row.Kind.POTION) {
                // 不可点（被动药水 / 执行中锁定）也吃掉这次点击，别穿透到下层
                if (row.clickable) {
                    AutoPilot.cyclePotionDirective(row.potionSlot, row.potionId);
                } else {
                    Trace.info("panel potion row " + row.potionSlot + " not clickable"
                            + " (AUTO deploying or non-searchable potion)");
                }
                layoutDirty = true;
                ensureLayout();
                return true;
            }
        }
        return false;
    }

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
