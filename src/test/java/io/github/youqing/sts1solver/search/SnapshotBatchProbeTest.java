package io.github.youqing.sts1solver.search;

import io.github.youqing.sts1solver.sim.SimState;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 批量快照探针：把真机 {@code solver-snapshots/turn-*.json} 全部跑一遍搜索，
 * 每个局面打一行紧凑结果，末尾打汇总。
 *
 * <p>存在理由：评价「搜索改好了没有」不能看单局，也不能看耗时。
 * 这个探针给出两组可比数字：<b>首手</b>、<b>搜索形状</b>（nodes/depth/是否打赢）。
 * 同一个 Snapshots 目录、同一份配置、同一个节点上限 = 可重复的 paired A/B。
 *
 * <p>跑法（不带 {@code -Dbatch} 时自动跳过）：
 * <pre>
 * gradlew test --offline --tests "*SnapshotBatchProbeTest" -Dbatch=C:/.../solver-snapshots
 * </pre>
 */
class SnapshotBatchProbeTest {

    /** 与真机默认档（VERY_HIGH）一致：宽 135 / 回合层 500 / 节点 50 万 / 分支 72。 */
    private static BeamSolver realConfig() {
        return new BeamSolver(3, 5, 135, 500, 500_000, 72).withNovelty().withPortfolio();
    }

    @Test
    void batch() throws Exception {
        String dir = System.getProperty("batch", "");
        assumeTrue(!dir.isEmpty(), "no -Dbatch=<snapshot dir> given");

        File[] files = new File(dir).listFiles();
        assumeTrue(files != null, "not a directory: " + dir);
        List<File> snapshots = new ArrayList<File>();
        for (File f : files) {
            if (f.getName().startsWith("turn-") && f.getName().endsWith(".json")) {
                snapshots.add(f);
            }
        }
        java.util.Collections.sort(snapshots);

        int wins = 0;
        long totalNodes = 0;
        long totalMs = 0;
        int exhausted = 0;
        int powerSnapshots = 0;
        int powerPlannedCount = 0;
        StringBuilder rows = new StringBuilder();
        for (File f : snapshots) {
            SimState root = GoldenPositions.read(f);
            // 2026-10-31 之前的快照没有落盘 boss 标志；按楼层补猜（16/33/50 = 各幕 Boss 房），
            // 否则 Boss 房会被当成普通房，测不到「延迟收益只在多敌人或 Boss 启用」这条。
            if (root.floor == 16 || root.floor == 33 || root.floor == 50) {
                root.boss = true;
            }
            BeamSolver solver = realConfig();
            long started = System.nanoTime();
            PlanAction chosen = solver.bestFirstAction(root, 20_000L);
            long ms = (System.nanoTime() - started) / 1_000_000L;
            boolean victory = solver.lastSelectedVictory;
            // 能力牌时机（P2）：手上有没有能力牌 / 这一趟计划里有没有把它打出去
            int powersInHand = 0;
            java.util.Set<String> powerKeys = new java.util.HashSet<String>();
            for (io.github.youqing.sts1solver.sim.SimCard c : root.hand) {
                if (c.isPower()) {
                    powersInHand++;
                    powerKeys.add(c.key());
                }
            }
            boolean powerPlanned = false;
            for (RouteStep step : solver.lastSteps) {
                if (!step.potion && step.cardKey != null && powerKeys.contains(step.cardKey)) {
                    powerPlanned = true;
                    break;
                }
            }
            if (powersInHand > 0) {
                powerSnapshots++;
                if (powerPlanned) {
                    powerPlannedCount++;
                }
            }
            if (victory) {
                wins++;
            }
            if (!solver.hitNodeLimit) {
                exhausted++;
            }
            totalNodes += solver.lastNodes;
            totalMs += ms;
            rows.append(String.format("%-16s %-22s score=%-15d nodes=%-6d depth=%-3d %-6s %-10s pw=%d/%d%n",
                    f.getName().replace("turn-", "").replace(".json", ""),
                    String.valueOf(chosen), solver.lastSelectedScore, solver.lastNodes,
                    solver.lastDepth, victory ? "WIN" : "lost",
                    solver.hitNodeLimit ? "node_limit" : solver.lastStopReason,
                    powersInHand, powerPlanned ? 1 : 0));
        }

        System.out.println("\n=== SNAPSHOT BATCH (" + snapshots.size() + " 个局面) ===");
        System.out.print(rows);
        System.out.println(String.format(
                "SUMMARY snapshots=%d wins=%d horizonCompleted=%d avgNodes=%d totalMs=%d"
                        + " | powerInHand=%d powerPlanned=%d",
                snapshots.size(), wins, exhausted,
                snapshots.isEmpty() ? 0 : totalNodes / snapshots.size(), totalMs,
                powerSnapshots, powerPlannedCount));
    }
}
