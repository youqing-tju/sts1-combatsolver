package io.github.youqing.sts1solver.search;

import io.github.youqing.sts1solver.sim.SimState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.File;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「为什么算得这么快」的检验探针：同一局面，只改 beam 宽度，看第一手会不会变。
 *
 * <p>用法：
 * <pre>
 * ./gradlew test --offline -Pexperiments --tests "*BeamWidthProbeTest" \
 *     -DbatchPair=&lt;快照目录&gt; -Dreplay=turn-A2F18T2.json -Dwidths=135,270,540
 * </pre>
 *
 * <p>要回答的问题：日志里的 `PLAN ready: 606 nodes, 6 ms` 究竟是「这个局面真的这么简单」，
 * 还是「beam 剪枝把更好的分支剪掉了」。
 * <ul>
 *   <li>加宽后第一手**不变** → 搜索对这个局面是收敛的，快是对的；</li>
 *   <li>加宽后第一手**变了**（尤其变成能力牌）→ 宽度 135 不够，属于欠搜索；</li>
 *   <li>加宽后仍然不选能力 → 是估值/键序在压制能力，不是搜索深度问题。</li>
 * </ul>
 * 三种结论指向三种完全不同的修法，所以必须先测出来再动手。
 */
class BeamWidthProbeTest {

    @Test
    @EnabledIfSystemProperty(named = "sts1.experiments", matches = "true",
            disabledReason = "离线探针（gradle test -Pexperiments -DbatchPair=... -Dreplay=... 才跑）")
    void widerBeamOnTheSamePosition() throws Exception {
        String dir = System.getProperty("batchPair", "");
        String name = System.getProperty("replay", "");
        String widths = System.getProperty("widths", "135,270,540");
        assertTrue(!dir.isEmpty() && !name.isEmpty(),
                "must pass -DbatchPair=<dir> -Dreplay=<turn-*.json>");

        SimState root = GoldenPositions.readStrict(new File(dir, name));
        System.out.println("PROBE " + name + "  " + GoldenPositions.describe(root));
        System.out.println("PROBE hand " + handOf(root));
        System.out.println("PROBE powers " + root.playerPowers);

        for (String token : widths.split(",")) {
            int width = Integer.parseInt(token.trim());
            BeamSolver solver = new BeamSolver(3, 5, width, 500, 500_000, 72)
                    .withNovelty().withPortfolio();
            long start = System.currentTimeMillis();
            try {
                solver.bestFirstAction(root.copy());
            } catch (RuntimeException e) {
                System.out.println(String.format(java.util.Locale.ROOT,
                        "WIDTH %-4d FAILED %s", Integer.valueOf(width), e));
                continue;
            }
            long millis = System.currentTimeMillis() - start;
            System.out.println(String.format(java.util.Locale.ROOT,
                    "WIDTH %-4d first=%-24s nodes=%-7d %5d ms  stop=%s/%s victory=%s",
                    Integer.valueOf(width), firstOf(solver),
                    Integer.valueOf(solver.lastNodes),
                    Long.valueOf(millis), solver.lastStopReason, solver.lastNoveltyStop,
                    Boolean.valueOf(solver.lastSelectedVictory)));
            System.out.println("           route " + routeOf(solver));
            System.out.println("           endHp "
                    + (solver.lastSelectedNode == null ? "-" : solver.lastSelectedNode.state.hp)
                    + " score " + solver.lastSelectedScore);
        }
    }

    private static String handOf(SimState s) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < s.hand.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(s.hand.get(i).key());
        }
        return sb.append(']').toString();
    }

    private static String firstOf(BeamSolver solver) {
        if (solver.lastSteps == null || solver.lastSteps.isEmpty()) {
            return "NONE";
        }
        RouteStep first = solver.lastSteps.get(0);
        if (first.endTurn) {
            return "END_TURN";
        }
        return first.potion ? "POTION:" + first.cardKey : first.cardKey;
    }

    private static String routeOf(BeamSolver solver) {
        if (solver.lastSteps == null) {
            return "NONE";
        }
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (RouteStep step : solver.lastSteps) {
            if (shown >= 12) {
                sb.append(" ...");
                break;
            }
            if (shown > 0) {
                sb.append(" | ");
            }
            sb.append(step.endTurn ? "END_TURN"
                    : (step.potion ? "POTION:" + step.cardKey : step.cardKey));
            shown++;
        }
        return sb.toString();
    }
}
