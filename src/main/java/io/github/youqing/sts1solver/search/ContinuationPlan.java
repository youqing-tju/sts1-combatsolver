package io.github.youqing.sts1solver.search;

import io.github.youqing.sts1solver.sim.SimState;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 一条最终路线上**可复用的回合边界集合**（纯数据，可跨线程）。
 *
 * <p>对应蓝本 {@code src/Search/CombatBeamSolver.Terminal.cs:337-390}：
 * <b>分两段</b>，不能合成一段。
 * <ol>
 *   <li>{@link #capture(List)} 只**挑边界**（{@code PrepareContinuationCapture}）：
 *       从最终节点链上摘出「稳定回合边界」的<b>下标</b>，此时<b>不读任何状态</b>；</li>
 *   <li>调用方把最终路线**回放**一遍，每走完一步就 {@link Capture#observe}；
 *       到达预期下标时才 {@link ContinuationStamp#capturePredicted} 采下那一刻的状态
 *       （蓝本 {@code ContinuationCapture.Observe}）。</li>
 * </ol>
 *
 * <p>为什么要回放而不是直接从节点链取 {@code node.state}：节点链上的状态是**展开时**的快照，
 * 而真正会被执行的路线必须能从这里重新推出来。回放顺带把「发布出去的步骤能不能从根走通」
 * 这件事验了一遍 —— 缺一个预期边界就 {@link IllegalStateException} 显式失败，
 * 绝不静默产出一条半截的世界线。
 *
 * <p>资格（缺一不可，与蓝本 {@code PrepareContinuationCapture} 逐条对应）：
 * <ol>
 *   <li>由 {@code END_TURN} 抵达；</li>
 *   <li>玩家没死；</li>
 *   <li>敌人没全灭；</li>
 *   <li>边界之后确实还有动作（蓝本 {@code laterActionTurns.Contains(node.Turn)}：
 *       后面存在一个「在新回合里执行」的动作）。</li>
 * </ol>
 * 末尾终局、死亡、胜利一律不缓存。
 *
 * <p>蓝本还有两条本类**没有**对应判据，登记为翻译缺口而不是放宽：
 * {@code Snapshot.BoundaryReason == None}（STS1 侧没有搜索边界原因枚举）与
 * {@code simulator.HasPendingChoice / TerminalStamp}（我们的选牌由 {@code choiceIndex}
 * 内联结算，不存在挂起的待选状态）。回放时的死亡/全灭检查是它们的等价兜底。
 */
public final class ContinuationPlan {

    private static final ContinuationPlan EMPTY =
            new ContinuationPlan(Collections.<CachedContinuation>emptyList());

    private final List<CachedContinuation> continuations;

    private ContinuationPlan(List<CachedContinuation> continuations) {
        this.continuations = Collections.unmodifiableList(continuations);
    }

    public static ContinuationPlan empty() {
        return EMPTY;
    }

    /** 一个稳定回合边界：END_TURN 在完整步骤里的下标 + 边界之后的玩家回合号。 */
    public static final class Boundary {

        /** 这条 {@code END_TURN} 动作在完整步骤列表里的下标（0 基）。 */
        public final int endTurnStepIndex;
        /** 边界之后的玩家回合号（{@code SimState.turn} 口径）。 */
        public final int startTurnNumber;

        Boundary(int endTurnStepIndex, int startTurnNumber) {
            this.endTurnStepIndex = endTurnStepIndex;
            this.startTurnNumber = startTurnNumber;
        }

        /** 本回合第一个动作在完整步骤里的下标。 */
        public int stepOffset() {
            return endTurnStepIndex + 1;
        }

        @Override
        public String toString() {
            return "T" + startTurnNumber + "@" + endTurnStepIndex;
        }
    }

    /**
     * 只挑边界，不读状态（蓝本 {@code PrepareContinuationCapture}）。
     *
     * <p>父链不一致、首节点不是根、空路线都属于**内部不一致**，直接抛
     * {@link IllegalArgumentException}：静默生成半条缓存，后果是执行层照着一份不完整的世界线出牌。
     */
    public static Capture capture(List<SearchNode> finalRoute) {
        if (finalRoute == null || finalRoute.isEmpty()) {
            throw new IllegalArgumentException("finalRoute must not be empty");
        }
        SearchNode root = finalRoute.get(0);
        if (root.action != null) {
            throw new IllegalArgumentException("first node must be the root (action == null)");
        }
        for (int i = 1; i < finalRoute.size(); i++) {
            SearchNode node = finalRoute.get(i);
            if (node.parent != finalRoute.get(i - 1)) {
                throw new IllegalArgumentException(
                        "broken parent chain at index " + i + " (" + node + ")");
            }
            if (node.action == null) {
                throw new IllegalArgumentException("non-root node without action at index " + i);
            }
        }

        List<Boundary> boundaries = new ArrayList<Boundary>();
        Set<Integer> laterActionTurns = new HashSet<Integer>();
        for (int i = finalRoute.size() - 1; i >= 1; i--) {
            SearchNode node = finalRoute.get(i);
            SearchNode parent = finalRoute.get(i - 1);
            int actionTurn = parent.state.turn;
            if (node.action.kind == PlanAction.Kind.END_TURN
                    && node.state.hp > 0
                    && !SearchNode.noAliveMonster(node.state)
                    && laterActionTurns.contains(Integer.valueOf(node.state.turn))) {
                // steps[j] 是 nodes[j + 1] 的动作 → 本节点的动作下标是 i - 1
                boundaries.add(new Boundary(i - 1, node.state.turn));
            }
            laterActionTurns.add(Integer.valueOf(actionTurn));
        }
        Collections.reverse(boundaries);
        return new Capture(boundaries);
    }

    /**
     * 回放过程中的边界采集器（蓝本 {@code ContinuationCapture}）。
     *
     * <p>调用方每回放完一步就调一次 {@link #observe}；下标对不上、状态已经终局、
     * 或者回放结束时少采了边界，都直接抛异常 —— 这三件事都说明「发布出去的路线」
     * 与「搜索时的那条」已经不是同一条了。
     */
    public static final class Capture {

        private final List<Boundary> boundaries;
        private final List<CachedContinuation> captured = new ArrayList<CachedContinuation>();

        Capture(List<Boundary> boundaries) {
            this.boundaries = boundaries;
        }

        public int boundaryCount() {
            return boundaries.size();
        }

        /**
         * 回放到第 {@code actionIndex} 个动作之后（该动作已落地），观察当前状态。
         *
         * <p>还没到下一个预期边界就什么都不做（蓝本 {@code if (actionIndex < expected) return;}）。
         */
        public void observe(int actionIndex, SimState predicted) {
            if (captured.size() >= boundaries.size()) {
                return;
            }
            Boundary expected = boundaries.get(captured.size());
            if (actionIndex < expected.endTurnStepIndex) {
                return;
            }
            if (actionIndex != expected.endTurnStepIndex) {
                throw new IllegalStateException(
                        "续用回放越过预期稳定边界：action=" + actionIndex
                                + " expected=" + expected.endTurnStepIndex
                                + " turn=" + expected.startTurnNumber);
            }
            if (predicted == null) {
                throw new IllegalStateException(
                        "续用回放抵达边界却没有状态：action=" + actionIndex);
            }
            if (predicted.hp <= 0) {
                throw new IllegalStateException(
                        "续用回放抵达边界时玩家已死：action=" + actionIndex
                                + " turn=" + predicted.turn + " hp=" + predicted.hp);
            }
            if (SearchNode.noAliveMonster(predicted)) {
                throw new IllegalStateException(
                        "续用回放抵达边界时敌人已全灭：action=" + actionIndex
                                + " turn=" + predicted.turn);
            }
            if (predicted.turn != expected.startTurnNumber) {
                throw new IllegalStateException(
                        "续用回放回合号与边界不一致：action=" + actionIndex
                                + " expected=T" + expected.startTurnNumber
                                + " actual=T" + predicted.turn);
            }
            captured.add(new CachedContinuation(ContinuationStamp.capturePredicted(predicted),
                    expected.startTurnNumber, expected.stepOffset()));
        }

        /** 回放结束：少采一个边界就是硬错误（蓝本 {@code ContinuationCapture.Complete}）。 */
        public ContinuationPlan complete() {
            if (captured.size() != boundaries.size()) {
                throw new IllegalStateException(
                        "最终路线回放缺少预期续用边界：captured=" + captured.size()
                                + "/" + boundaries.size() + " boundaries=" + boundaries);
            }
            return captured.isEmpty() ? EMPTY : new ContinuationPlan(captured);
        }
    }

    /**
     * 真机当前回合能否续用预测。
     *
     * <p>三种结果都必须显式区分：只有 {@code HIT} 才允许「不搜索直接出牌」，
     * 另外两种都要回到搜索，并且日志要能说清是「没这一回合」还是「这一回合对不上、差在哪」。
     */
    public Lookup lookup(int liveTurn, ContinuationStamp liveStamp) {
        for (CachedContinuation cached : continuations) {
            if (cached.startTurnNumber != liveTurn) {
                continue;
            }
            if (cached.expectedState.equals(liveStamp)) {
                return Lookup.hit(cached);
            }
            return Lookup.mismatch(cached.expectedState.describeFirstDifference(liveStamp));
        }
        return Lookup.missing();
    }

    /** 已经用掉（含）更早的边界之后的剩余计划。 */
    public ContinuationPlan onlyAfter(int reusedTurn) {
        List<CachedContinuation> out = new ArrayList<CachedContinuation>();
        for (CachedContinuation cached : continuations) {
            if (cached.startTurnNumber > reusedTurn) {
                out.add(cached);
            }
        }
        return out.isEmpty() ? EMPTY : new ContinuationPlan(out);
    }

    public boolean isEmpty() {
        return continuations.isEmpty();
    }

    public int size() {
        return continuations.size();
    }

    /** 只读视图；调用方不得修改。 */
    public List<CachedContinuation> continuations() {
        return continuations;
    }

    public List<Integer> turns() {
        List<Integer> out = new ArrayList<Integer>(continuations.size());
        for (CachedContinuation cached : continuations) {
            out.add(Integer.valueOf(cached.startTurnNumber));
        }
        return out;
    }

    @Override
    public String toString() {
        return "ContinuationPlan" + turns();
    }

    /** {@link #lookup} 的结果。不可变。 */
    public static final class Lookup {

        public enum Status {
            /** 精确命中：状态文本逐字段相等，可以免搜索续跑。 */
            HIT,
            /** 缓存里没有这个回合（路线更短、或已经被消费掉）。 */
            CONTINUATION_MISSING,
            /** 有这个回合，但真机与预测不同 —— 必须带着首个差异去重搜。 */
            STATE_MISMATCH
        }

        private final Status status;
        private final CachedContinuation matched;
        private final String firstDifference;

        private Lookup(Status status, CachedContinuation matched, String firstDifference) {
            this.status = status;
            this.matched = matched;
            this.firstDifference = firstDifference;
        }

        static Lookup hit(CachedContinuation cached) {
            return new Lookup(Status.HIT, cached, "none");
        }

        static Lookup missing() {
            return new Lookup(Status.CONTINUATION_MISSING, null, "none");
        }

        static Lookup mismatch(String firstDifference) {
            return new Lookup(Status.STATE_MISMATCH, null,
                    firstDifference == null ? "unknown" : firstDifference);
        }

        public Status status() {
            return status;
        }

        public boolean isHit() {
            return status == Status.HIT;
        }

        public CachedContinuation matched() {
            return matched;
        }

        /** 命中的起点下标；没命中返回 {@code -1}。 */
        public int forecastOffset() {
            return matched == null ? -1 : matched.forecastOffset;
        }

        public String firstDifference() {
            return firstDifference;
        }

        @Override
        public String toString() {
            return status + (matched == null ? "" : "(" + matched + ")")
                    + (Status.STATE_MISMATCH == status ? " " + firstDifference : "");
        }
    }
}
