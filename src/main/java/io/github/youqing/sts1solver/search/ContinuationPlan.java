package io.github.youqing.sts1solver.search;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 一条最终路线上**可复用的回合边界集合**（纯数据，可跨线程）。
 *
 * <p>对应蓝本 {@code src/Search/CombatBeamSolver.Terminal.cs:159-274}：搜索跑完之后，从**最终采纳**
 * 的最优节点链上把「稳定回合边界」摘出来，每段带上它的预测状态。下一回合主线程只要发现
 * 真机状态与某段预测**逐字段相等**，就不再搜索，沿原世界线继续。
 *
 * <p>为什么必须从最终 `selected` 生成：药水后处理与 0 费补搜都可能**换掉**答案；
 * 从候选（novelty / 闸门之前 / 补搜之前）生成的边界缓存的是没被采纳的世界线 ——
 * 那会让执行层走一条搜索并不推荐的路。
 *
 * <p>资格（缺一不可，见设计 §3.3）：
 * <ol>
 *   <li>由 {@code END_TURN} 抵达（普通出牌节点不算边界）；</li>
 *   <li>回合号恰好前进 1（搜索边界异常时不缓存）；</li>
 *   <li>玩家没死；</li>
 *   <li>敌人没全灭；</li>
 *   <li>边界之后确实还有动作（否则缓存了也执行不了）。</li>
 * </ol>
 * 末尾终局、死亡、胜利一律不缓存。
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

    /**
     * 从最终节点链（{@code [root, …, selected]}，按根到叶顺序）摘出稳定回合边界。
     *
     * <p>父链不一致、首节点不是根、空路线都属于**内部不一致**，直接抛
     * {@link IllegalArgumentException}：静默生成半条缓存，后果是执行层照着一份不完整的世界线出牌。
     */
    public static ContinuationPlan fromFinalRoute(List<SearchNode> finalRoute) {
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

        List<CachedContinuation> out = new ArrayList<CachedContinuation>();
        for (int i = 1; i < finalRoute.size() - 1; i++) {
            SearchNode node = finalRoute.get(i);
            SearchNode parent = finalRoute.get(i - 1);
            if (node.action.kind != PlanAction.Kind.END_TURN) {
                continue;                       // 普通出牌不是回合边界
            }
            if (node.state.turn != parent.state.turn + 1) {
                continue;                       // 边界异常（回合没前进 / 前进了不止一回合）
            }
            if (node.state.hp <= 0) {
                continue;                       // 玩家已死
            }
            if (SearchNode.noAliveMonster(node.state)) {
                continue;                       // 战斗已经结束
            }
            SearchNode next = finalRoute.get(i + 1);
            if (next.action == null) {
                continue;                       // 边界之后没有动作
            }
            // steps 的下标 j 对应 nodes[j + 1] 的动作：本回合第一个动作在 steps[i]
            out.add(new CachedContinuation(ContinuationStamp.from(node.state),
                    node.state.turn, i));
        }
        return out.isEmpty() ? EMPTY : new ContinuationPlan(out);
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
