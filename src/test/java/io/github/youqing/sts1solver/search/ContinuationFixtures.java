package io.github.youqing.sts1solver.search;

import java.util.List;

/**
 * 测试用的续用边界采集入口。
 *
 * <p>{@link ContinuationPlan#capture} 只挑边界，状态必须由**回放**提供
 * （{@code BeamSolver.captureContinuations} 用 {@code replayPrefixAction} 跑）。
 * 纯数据测试里没有模拟器，所以这里用节点链上的状态代替回放状态 ——
 * 只用于「边界资格」与「命中/失配」这类与推进无关的合同，
 * 真实回放另有 {@code BeamSolverContinuationTest} 独立验证。
 */
public final class ContinuationFixtures {

    private ContinuationFixtures() {
    }

    /** 按节点链顺序逐个 observe，然后 complete。 */
    public static ContinuationPlan capture(List<SearchNode> route) {
        ContinuationPlan.Capture capture = ContinuationPlan.capture(route);
        for (int i = 1; i < route.size(); i++) {
            capture.observe(i - 1, route.get(i).state);
        }
        return capture.complete();
    }
}
