package io.github.youqing.sts1solver.verify;

import io.github.youqing.sts1solver.search.ContinuationPlan;
import io.github.youqing.sts1solver.search.ContinuationStamp;

/**
 * 「这个新回合能不能接着上一回合算出来的世界线走」的**纯数据判定**。
 *
 * <p>为什么单独成类：{@code AutoPilot} 一碰就是 {@code AbstractDungeon} / {@code AbstractPlayer}
 * 这些真机静态对象，单测里没法安全构造；而这里恰恰是最需要钉死的部分 ——
 * 判错一个方向，要么「明明对得上却每回合重搜」（回到世界线永远不展开的老毛病），
 * 要么「明明对不上还硬走旧路线」（拿想象中的局面指挥真局面）。
 *
 * <p>四种 miss 必须分开归因（设计 §3.5）：
 * <ul>
 *   <li>{@code MANUAL_DIVERGENCE} —— 有人手工打了牌/不是我们排的动作；</li>
 *   <li>{@code CONTINUATION_MISSING} —— 压根没有这一回合的缓存（路线更短，或已消费）；</li>
 *   <li>{@code STATE_MISMATCH} —— 有预测但逐字段对不上，必须带首个差异；</li>
 *   <li>{@code DEPLOYMENT_DRIFT} —— 影子与实机已经分叉（沿用 {@code ShadowTracker} 的序号）。</li>
 * </ul>
 * 蓝本把后两种算进 {@code UnexpectedReplanCount}（{@code SolverController.cs:175}）——
 * 也就是说这两类**不该发生**，是诊断信号，不是「正常现象」。
 */
public final class ContinuationDecision {

    public enum Reason {
        /** 精确命中，可以免搜索续跑。 */
        HIT("hit"),
        /** 没有缓存，或缓存里没有这个回合。 */
        CONTINUATION_MISSING("continuation_missing"),
        /** 上一回合发生了手工输入 / 不是我们排的牌。 */
        MANUAL_DIVERGENCE("manual_divergence"),
        /** 有这一回合的预测，但状态文本不同。 */
        STATE_MISMATCH("state_mismatch"),
        /** 影子与实机分叉（部署漂移）。 */
        DEPLOYMENT_DRIFT("deployment_drift");

        public final String label;

        Reason(String label) {
            this.label = label;
        }
    }

    /** 一次判定的结果。不可变。 */
    public static final class Outcome {

        private final Reason reason;
        private final int forecastOffset;
        private final String firstDifference;
        private final int remainingTurns;

        private Outcome(Reason reason, int forecastOffset, String firstDifference,
                        int remainingTurns) {
            this.reason = reason;
            this.forecastOffset = forecastOffset;
            this.firstDifference = firstDifference;
            this.remainingTurns = remainingTurns;
        }

        public Reason reason() {
            return reason;
        }

        public boolean isHit() {
            return reason == Reason.HIT;
        }

        /** 命中时本回合第一个动作在完整步骤列表里的下标；没命中返回 -1。 */
        public int forecastOffset() {
            return forecastOffset;
        }

        /** 失败原因的人类可读补充（{@code STATE_MISMATCH} 时是首个字段差异）。 */
        public String firstDifference() {
            return firstDifference;
        }

        /** 命中之后还剩几个回合的预测。 */
        public int remainingTurns() {
            return remainingTurns;
        }

        @Override
        public String toString() {
            return "reason=" + reason.label + " first_difference=" + firstDifference;
        }
    }

    private ContinuationPlan plan = ContinuationPlan.empty();
    private int planMismatchSequence = -1;
    private boolean manualDivergence;

    /** 采纳一份新计划（连同它带来的边界缓存）。{@code mismatchSequence} 是采纳当时的漂移序号。 */
    public void adopt(ContinuationPlan continuationPlan, int mismatchSequence) {
        this.plan = continuationPlan == null ? ContinuationPlan.empty() : continuationPlan;
        this.planMismatchSequence = mismatchSequence;
        this.manualDivergence = false;
    }

    /** 战斗 reset / 本回合计划失效 / 策略变更：整份缓存作废。 */
    public void clear() {
        plan = ContinuationPlan.empty();
        planMismatchSequence = -1;
        manualDivergence = false;
    }

    /**
     * 记下「上一回合有人手工动过」。
     *
     * <p>只要不是 AUTO 自己排出去的那张牌，都算这一类（手工点击、遗物/药水的自动出牌）。
     * 归因价值在于：它和 {@code STATE_MISMATCH} 的处置不同 —— 前者是用户动作，
     * 后者是镜像缺陷，后者要当 bug 查。
     */
    public void markManualDivergence() {
        manualDivergence = true;
    }

    /** 上一次判定之后有没有「用户手工动过手」的标记（采纳模式据此决定要不要丢路线）。 */
    public boolean manualDivergence() {
        return manualDivergence;
    }

    /** 采纳时的漂移序号（诊断用）。 */
    public int planMismatchSequence() {
        return planMismatchSequence;
    }

    public boolean hasPlan() {
        return !plan.isEmpty();
    }

    public ContinuationPlan plan() {
        return plan;
    }

    public int size() {
        return plan.size();
    }

    /**
     * 判定这个回合能否续跑。
     *
     * <p>顺序有讲究：**手工分歧优先于状态差异**。手工打了一张牌必然也会让状态对不上，
     * 但把这种情况报成 {@code STATE_MISMATCH} 会把人引去查镜像，而真正的原因是用户动手了。
     */
    public Outcome evaluate(int liveTurn, ContinuationStamp liveStamp, int mismatchSequence) {
        if (plan.isEmpty()) {
            return miss(Reason.CONTINUATION_MISSING, "no cached continuation");
        }
        if (manualDivergence) {
            clear();
            return miss(Reason.MANUAL_DIVERGENCE,
                    "manual or non-pilot action since the route was planned");
        }
        if (mismatchSequence != planMismatchSequence) {
            int drift = mismatchSequence - planMismatchSequence;
            clear();
            return miss(Reason.DEPLOYMENT_DRIFT,
                    drift + " mirror mismatch(es) since it was computed");
        }
        if (liveStamp == null) {
            clear();
            return miss(Reason.STATE_MISMATCH, "live state could not be captured");
        }
        ContinuationPlan.Lookup lookup = plan.lookup(liveTurn, liveStamp);
        switch (lookup.status()) {
            case HIT:
                plan = plan.onlyAfter(liveTurn);
                return new Outcome(Reason.HIT, lookup.forecastOffset(), "none", plan.size());
            case STATE_MISMATCH:
                clear();
                return miss(Reason.STATE_MISMATCH, lookup.firstDifference());
            default:
                clear();
                return miss(Reason.CONTINUATION_MISSING, "no cached boundary for T" + liveTurn);
        }
    }

    private Outcome miss(Reason reason, String detail) {
        return new Outcome(reason, -1, detail == null ? "none" : detail, 0);
    }
}
