package io.github.youqing.sts1solver.search;

import io.github.youqing.sts1solver.sim.SimCard;
import io.github.youqing.sts1solver.sim.SimState;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 回合重放探针：把真机某一回合的快照（{@code solver-snapshots/turn-A1F14T2.json}）喂给搜索，
 * 打印「它选了什么」以及「换别的首手能走到多好」。
 *
 * <p>存在理由：用户说「这手明显有更好的线」时，我们以前只能回一句「搜索是这么算的」。
 * 现在可以把每个候选首手**钉成固定前缀**各跑一趟，拿到那条线能走到的最好分数 ——
 * 于是能分清三种完全不同的病因：
 * <ol>
 *   <li><b>估值错</b>：更好的线也能走到，但分数更低 → 看 {@code Score} 的哪一项在作怪；</li>
 *   <li><b>搜索漏</b>：那条线的首手根本不出现，或前缀被拒（{@code REJECTED}）→ 模型/展开的问题；</li>
 *   <li><b>模型错</b>：局面本身就理解错了（怪物能力、伤害公式）→ 先修镜像。</li>
 * </ol>
 *
 * <p>跑法（不带 {@code -Dreplay} 时自动跳过，不影响日常全量测试）：
 * <pre>
 * gradlew test --offline --tests "*TurnReplayProbeTest" -Dreplay=C:/path/turn-A1F14T2.json
 * </pre>
 */
class TurnReplayProbeTest {

    /** 与真机默认配置一致：宽 135 / 深度 500 / 节点 50 万 / 分支 72，组合与探索都开。 */
    private static BeamSolver realConfig() {
        return new BeamSolver(3, 5, 135, 500, 500_000, 72).withNovelty().withPortfolio();
    }

    private static String steps(BeamSolver solver, int limit) {
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (RouteStep step : solver.lastSteps) {
            if (shown++ >= limit) {
                sb.append(" …");
                break;
            }
            if (shown > 1) {
                sb.append(" | ");
            }
            sb.append(step.endTurn ? "END_TURN"
                    : (step.potion ? "POTION:" + step.cardKey : step.cardKey));
        }
        return sb.toString();
    }

    @Test
    void probe() throws Exception {
        String path = System.getProperty("replay", "");
        assumeTrue(!path.isEmpty(), "no -Dreplay=<snapshot.json> given");

        SimState root = GoldenPositions.read(new File(path));
        System.out.println("\n=== 局面 " + path + " ===");
        System.out.println(GoldenPositions.describe(root));
        System.out.println("  monster: " + root.monsters);
        System.out.println("  powers:  " + root.playerPowers);

        BeamSolver solver = realConfig();
        PlanAction chosen = solver.bestFirstAction(root, 60_000L);
        System.out.println(String.format(
                "  选择: %s   score=%d  nodes=%d states=%d depth=%d ms=%d port=%s novelty=%s%s",
                chosen, solver.lastSelectedScore, solver.lastNodes, solver.lastStates,
                solver.lastDepth, solver.lastMillis, solver.lastPortfolioAdopted,
                solver.lastNoveltyStop, solver.lastSelectedVictory ? "  [WIN]" : "  [no win]"));
        System.out.println("  路线: " + steps(solver, 14));

        // 逐个候选首手钉成固定前缀，看每条线各自能走到多好
        System.out.println("  -- 候选首手对比（固定前缀 = 纯 Beam，不含组合/探索）--");
        List<String> lines = new ArrayList<String>();
        for (int i = 0; i < root.hand.size(); i++) {
            SimCard c = root.hand.get(i);
            List<PlanAction> prefix = Collections.singletonList(PlanAction.play(i, 0));
            BeamSolver forced = realConfig().withFixedPrefix(prefix);
            forced.bestFirstAction(root.copy(), 60_000L);
            lines.add(String.format("    %-22s score=%-14d nodes=%-6d %-4s %s",
                    c.key() + "(" + c.effectiveCost() + ")",
                    forced.lastSelectedScore, forced.lastNodes,
                    forced.lastSelectedVictory ? "WIN" : "lost", steps(forced, 8)));
        }
        BeamSolver end = realConfig().withFixedPrefix(
                Collections.singletonList(PlanAction.endTurn()));
        end.bestFirstAction(root.copy(), 60_000L);
        lines.add(String.format("    %-22s score=%-14d nodes=%-6d %-4s %s",
                "END_TURN", end.lastSelectedScore, end.lastNodes,
                end.lastSelectedVictory ? "WIN" : "lost", steps(end, 8)));
        for (String line : lines) {
            System.out.println(line);
        }
    }
}
