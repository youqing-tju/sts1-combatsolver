package io.github.youqing.sts1solver.verify;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import io.github.youqing.sts1solver.search.RouteStep;

/**
 * 一次搜索给出的**完整**路线预览（不可变、纯数据）。
 *
 * <p>存在的理由是两个已经暴露出来的显示缺陷：
 * <ol>
 *   <li>旧 {@code AutoPilot.planLines()} 从剩余队列生成行，所以永远从 {@code T1} 开始编号 ——
 *       第 4 回合的路线被写成 T1/T2，用户按「T2」理解就会打错回合。</li>
 *   <li>每出一手，队列前移，后续回合的行号跟着重排（同一个 T2 一会儿是这手、一会儿是下一手）。</li>
 * </ol>
 *
 * <p>所以这里在**接受搜索结果的那一刻**就把整条路线定下来（含已经打过的步骤），
 * 之后执行层照旧消费 {@code ArrayDeque<RouteStep>}，但显示不再重算：
 * 行号是绝对回合 {@code T{originTurn + n}}，行身份是 {@link Turn}，当前下一手靠
 * {@code step == nextStep} 的**引用相等**画箭头。
 *
 * <p>⚠️ 它不是「执行承诺」，但也不是「每回合都会重算」：满不满足续跑由
 * {@code ContinuationPlan} 的**精确状态文本**决定 —— 真机状态与上一回合预测逐字段相等时
 * 沿这条路线继续（{@code SEARCH_REUSED}），只有对不上才重搜（{@code SEARCH_REUSE_MISS}）。
 * 蓝本的「冻结路线」（{@code RouteFrozen}）本轮没有复刻，所以这里也没有与之配套的交互：
 * 路线行只读，不可点。
 */
public final class RoutePreview {

    /** 一行最多画几个动作；超出写 {@code +N}（面板宽度有限，宁可截断也不要让 fit() 随机切）。 */
    private static final int MAX_ACTIONS = 6;

    private final List<Turn> turns;

    private RoutePreview(List<Turn> turns) {
        this.turns = Collections.unmodifiableList(new ArrayList<Turn>(turns));
    }

    /**
     * 按 {@code END_TURN} 把完整步骤分组。
     *
     * @param originTurn 这份计划是从哪个**绝对**玩家回合算出来的（{@code GameActionManager.turn}）
     * @param steps      搜索返回的完整步骤；{@code null} / 空表示没有路线
     */
    public static RoutePreview fromSteps(int originTurn, List<RouteStep> steps) {
        List<Turn> out = new ArrayList<Turn>();
        List<RouteStep> actions = new ArrayList<RouteStep>();
        int turn = originTurn;
        if (steps != null) {
            for (RouteStep step : steps) {
                if (step == null) {
                    continue;
                }
                if (step.endTurn) {
                    // END_TURN 自己不进动作文本：它就是「这一行到此为止」的标记
                    out.add(new Turn(turn, true, actions));
                    actions = new ArrayList<RouteStep>();
                    turn++;
                } else {
                    actions.add(step);
                }
            }
        }
        // 末尾没被 END_TURN 收尾的步骤 = 一条「没走到回合末」的尾行：显示上画暗色，
        // 表示这份计划在这个回合没有收敛（不是「可以打到这里的按钮」）。
        if (!actions.isEmpty()) {
            out.add(new Turn(turn, false, actions));
        }
        return new RoutePreview(out);
    }

    /** 按绝对回合升序的预览行。 */
    public List<Turn> turns() {
        return turns;
    }

    /** 预览里的一行 = 一个绝对回合。 */
    public static final class Turn {

        private final int absoluteTurn;
        private final boolean hasEndTurn;
        private final List<RouteStep> steps;

        private Turn(int absoluteTurn, boolean hasEndTurn, List<RouteStep> steps) {
            this.absoluteTurn = absoluteTurn;
            this.hasEndTurn = hasEndTurn;
            this.steps = Collections.unmodifiableList(new ArrayList<RouteStep>(steps));
        }

        /** 绝对回合号（{@code GameActionManager.turn} 的口径），不是「第几行」。 */
        public int absoluteTurn() {
            return absoluteTurn;
        }

        /**
         * 这一行在计划里是否走到了回合末（以 {@code END_TURN} 收尾）。
         *
         * <p>只影响显示（未收敛的尾行画暗色）：蓝本的路线行不可点，
         * 没有「打到这一回合末」这种控制入口。
         */
        public boolean endsTurn() {
            return hasEndTurn;
        }

        /**
         * 一行的文本，{@code nextStep} 那一手前面加 {@code ▶}。
         *
         * @param nextStep 执行层下一手（{@code plan.peek()}）；用引用相等比较，
         *                 为 {@code null} 时不画箭头
         */
        public String text(RouteStep nextStep) {
            StringBuilder sb = new StringBuilder("T").append(absoluteTurn).append(" ▸ ");
            if (steps.isEmpty()) {
                return sb.append("—").toString();
            }
            int shown = Math.min(steps.size(), MAX_ACTIONS);
            for (int i = 0; i < shown; i++) {
                if (i > 0) {
                    sb.append(" · ");
                }
                RouteStep step = steps.get(i);
                if (step == nextStep) {
                    sb.append("▶");
                }
                sb.append(label(step));
            }
            if (steps.size() > MAX_ACTIONS) {
                sb.append(" · +").append(steps.size() - MAX_ACTIONS);
            }
            return sb.toString();
        }

        private static String label(RouteStep step) {
            return (step.potion ? "药·" : "") + shortName(step.cardKey);
        }
    }

    /**
     * 牌名缩写：去掉 {@code _R} 后缀（面板宽度有限）。
     *
     * <p>面板行与日志共用这一份口径 —— 两处各写一个「去掉后缀」很容易在改名后分叉，
     * 而分叉的表现是「日志和面板对不上」，排查成本远高于一个 static 方法。
     */
    static String shortName(String key) {
        if (key == null) {
            return "?";
        }
        return key.endsWith("_R") ? key.substring(0, key.length() - 2) : key;
    }
}
