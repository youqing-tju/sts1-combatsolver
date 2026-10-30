package io.github.youqing.sts1solver.search;

import java.util.ArrayList;
import java.util.List;

/**
 * Beam 宽度组合（蓝本 {@code BeamWidthPortfolio.cs} + {@code BeamWidthPortfolioGate.cs}）。
 *
 * <p>为什么需要：单趟 Beam 的体量 ≈ 宽度 × 每节点子状态 × 回合数，第二章精英只有 3.8k 展开。
 * 蓝本用**五个成员**各跑一趟（基线 / 2÷3 窄 / 3÷2 宽 / 次段 / 基础分），共享同一份节点与时间预算，
 * 最后按既有比较规则取最好的一条 —— 节点数直接乘 5，Boss 战就是十几秒的来源。
 *
 * <p>这里只放「成员清单 + 准入算术」，不含任何搜索状态（蓝本同一份代码也是纯算术，
 * 所以它能被独立的 checks 项目原样编译）。
 */
final class BeamWidthPortfolio {

    private BeamWidthPortfolio() {
    }

    /** 蓝本 {@code BeamWidthPortfolioMemberSpec} 里我们有的三个维度。 */
    static final class Member {
        final int beamWidth;
        /**
         * 全局剪枝的普通席位取「分数排名第 W+1 至 2W 位」而不是前 W 位
         * （蓝本 {@code SolverSearchProfile.SecondRankBand}）。
         */
        final boolean secondRankBand;
        /** 中途排序只用基础分 {@code score}，不用带资源项的 {@code rank}（{@code BaseScoreOnly}）。 */
        final boolean baseScoreOnly;

        Member(int beamWidth, boolean secondRankBand, boolean baseScoreOnly) {
            this.beamWidth = beamWidth;
            this.secondRankBand = secondRankBand;
            this.baseScoreOnly = baseScoreOnly;
        }

        @Override
        public String toString() {
            return beamWidth + (secondRankBand ? "+band" : "") + (baseScoreOnly ? "+base" : "");
        }
    }

    /**
     * 蓝本 {@code ProductionMembers}「配置宽度为空」那条路：
     * <pre>
     * [基线, round(基线×2/3), round(基线×3/2), 基线+次段, 基线+基础分]
     * </pre>
     * 例：基线 135 → [135, 90, 203, 135+band, 135+base]。
     *
     * <p>**不搬**的三个成员：{@code AggressivePowerCommitment} / {@code OffensiveRefinement} /
     * {@code BoundedRefinement} —— 它们各自依赖一个我们没有的 profile 开关
     * （能力牌承诺估值、进攻精炼倍率、有界精炼预算），硬搬等于自创语义。
     * 蓝本自己也把这三个放在默认关的参数后面（{@code includePowerCommitmentMember=false} 等）。
     */
    static List<Member> productionMembers(int baselineBeamWidth) {
        int base = Math.max(1, baselineBeamWidth);
        List<Member> members = new ArrayList<Member>(5);
        members.add(new Member(base, false, false));
        addIfNew(members, new Member(scaledWidth(base, 2d / 3d), false, false));
        addIfNew(members, new Member(scaledWidth(base, 3d / 2d), false, false));
        members.add(new Member(base, true, false));
        members.add(new Member(base, false, true));
        return members;
    }

    private static void addIfNew(List<Member> members, Member member) {
        for (Member existing : members) {
            if (existing.beamWidth == member.beamWidth
                    && existing.secondRankBand == member.secondRankBand
                    && existing.baseScoreOnly == member.baseScoreOnly) {
                return;
            }
        }
        members.add(member);
    }

    /** 蓝本 {@code ScaledWidth}：四舍五入（.5 远离零），至少 1。 */
    static int scaledWidth(int baselineBeamWidth, double ratio) {
        return Math.max(1, (int) Math.round(baselineBeamWidth * ratio));
    }

    /** 蓝本 {@code EstimateMemberCost}：基线实测耗时 × 宽度比 × 3/2 保险系数，向上取整。 */
    static long estimateMemberCost(long baselineCostMillis, int baselineBeamWidth, int memberBeamWidth) {
        int base = Math.max(1, baselineBeamWidth);
        int member = Math.max(1, memberBeamWidth);
        long numerator = baselineCostMillis * member * 3L;
        long denominator = (long) base * 2L;
        return (numerator + denominator - 1) / denominator;
    }

    /** 准入被拒的原因（原样进诊断，方便判断「为什么这一趟没跑」）。 */
    static final String SKIP_ZERO_DAMAGE = "BaselineProvenZeroDamage";
    static final String SKIP_NOT_EXHAUSTED = "BaselineNotFrontierExhausted";
    static final String SKIP_TIME_SHARE = "BaselineTimeShareExceeded";
    static final String SKIP_NODE_HEADROOM = "NodeHeadroomInsufficient";
    static final String SKIP_TIME_HEADROOM = "TimeHeadroomInsufficient";

    /** 基线耗时允许占用的时间预算份额的倒数：1/4。 */
    static final int BASELINE_TIME_SHARE_DIVISOR = 4;

    /**
     * 蓝本 {@code RejectRefinement}：返回非 null = 这个精炼成员不跑。
     *
     * <p>顺序与蓝本一致：先看「基线是不是已经证明最优」，再看「基线有没有被上限截断」，
     * 再看「基线自己吃掉了多少时间」，最后看节点与时间余量。
     */
    static String rejectRefinement(boolean frontierExhausted, boolean provenZeroDamage,
                                   long baselineMillis, int baselineBeamWidth,
                                   int memberBeamWidth, long remainingNodes,
                                   long remainingMillis, long timeBudgetMillis,
                                   int baselineExpandedNodes) {
        if (provenZeroDamage) {
            return SKIP_ZERO_DAMAGE;
        }
        if (!frontierExhausted) {
            return SKIP_NOT_EXHAUSTED;
        }
        if (timeBudgetMillis != Long.MAX_VALUE
                && baselineMillis * BASELINE_TIME_SHARE_DIVISOR > timeBudgetMillis) {
            return SKIP_TIME_SHARE;
        }
        if (remainingNodes >= 0 && remainingNodes < baselineExpandedNodes) {
            return SKIP_NODE_HEADROOM;
        }
        if (remainingMillis != Long.MAX_VALUE
                && estimateMemberCost(baselineMillis, baselineBeamWidth, memberBeamWidth) > remainingMillis) {
            return SKIP_TIME_HEADROOM;
        }
        return null;
    }
}
