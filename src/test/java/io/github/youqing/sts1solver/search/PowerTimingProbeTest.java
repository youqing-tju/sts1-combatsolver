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
 * 「能力牌该不该开」的判定探针（P2）。
 *
 * <p>争议：真机上 `Feel No Pain` 在手 14 次只打出 2 次，看起来像「AI 不会开能力」。
 * 但「少开」不等于「开错了」—— 判据只能是**对着同一局面强制先开一次，会不会更好**。
 *
 * <p>做法：取每个「手上有能力牌」的真机快照，跑两趟同一份配置：
 * <ol>
 *   <li>不加强制：搜索自己选（= 真机行为）；</li>
 *   <li>强制前缀：先把那张能力牌打出去，再从那里搜。</li>
 * </ol>
 * 比较「是否真的打赢」与分数。强制侧更差 → 少开是对的；强制侧更好 → 少开是缺陷。
 *
 * <p>跑法：{@code gradlew test --offline --tests "*PowerTimingProbeTest" -Dbatch=<snapshot dir>}
 */
class PowerTimingProbeTest {

    private static BeamSolver realConfig() {
        return new BeamSolver(3, 5, 135, 500, 500_000, 72).withNovelty().withPortfolio();
    }

    @Test
    void probe() throws Exception {
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
        Collections.sort(snapshots);

        int withPower = 0;
        int searchPlaysPower = 0;
        int forcedBetter = 0;
        int forcedWorse = 0;
        int forcedWinsSearchLoses = 0;
        StringBuilder rows = new StringBuilder();

        for (File f : snapshots) {
            SimState root = GoldenPositions.read(f);
            if (root.floor == 16 || root.floor == 33 || root.floor == 50) {
                root.boss = true;
            }
            int powerIndex = -1;
            for (int i = 0; i < root.hand.size(); i++) {
                if (root.hand.get(i).isPower()) {
                    powerIndex = i;
                    break;
                }
            }
            if (powerIndex < 0) {
                continue;
            }
            withPower++;
            String key = root.hand.get(powerIndex).key();

            BeamSolver search = realConfig();
            search.bestFirstAction(root.copy(), 20_000L);
            boolean planned;
            try {
                planned = search.lastSteps.size() > 0
                        && !search.lastSteps.get(0).potion
                        && key.equals(search.lastSteps.get(0).cardKey);
            } catch (Throwable t) {
                planned = false;
            }
            if (planned) {
                searchPlaysPower++;
            }

            BeamSolver forced = realConfig().withFixedPrefix(
                    Collections.singletonList(PlanAction.play(powerIndex, -1)));
            forced.bestFirstAction(root.copy(), 20_000L);

            String verdict;
            if (forced.lastSelectedVictory && !search.lastSelectedVictory) {
                forcedWinsSearchLoses++;
                forcedBetter++;
                verdict = "<- 强制开能力才赢";
            } else if (search.lastSelectedVictory && !forced.lastSelectedVictory) {
                forcedWorse++;
                verdict = "<- 强制开能力反而输";
            } else if (forced.lastSelectedVictory && search.lastSelectedVictory) {
                if (forced.lastSelectedScore > search.lastSelectedScore) {
                    forcedBetter++;
                    verdict = "<- 都赢，强制分更高";
                } else {
                    forcedWorse++;
                    verdict = "";
                }
            } else {
                // 两边都没赢：比分（都是负分，越接近 0 越好）
                if (forced.lastSelectedScore > search.lastSelectedScore) {
                    forcedWorse++;
                    verdict = "  (都输，先开更差)";
                } else {
                    forcedBetter++;
                    verdict = "  (都输，先开不难看)";
                }
            }
            rows.append(String.format("%-14s %-16s search=%-5s %-14d forced=%-5s %-14d %s%n",
                    f.getName().replace("turn-", "").replace(".json", ""), key,
                    search.lastSelectedVictory ? "WIN" : "lost", search.lastSelectedScore,
                    forced.lastSelectedVictory ? "WIN" : "lost", forced.lastSelectedScore,
                    verdict));
        }

        System.out.println("\n=== POWER TIMING (" + snapshots.size() + " snapshots) ===");
        System.out.print(rows);
        System.out.println(String.format(
                "SUMMARY withPower=%d searchPlaysPower=%d forcedWinsSearchLoses=%d"
                        + " forcedBetter=%d forcedWorse=%d",
                withPower, searchPlaysPower, forcedWinsSearchLoses, forcedBetter, forcedWorse));
    }
}
