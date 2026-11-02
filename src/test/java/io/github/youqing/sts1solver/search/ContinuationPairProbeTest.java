package io.github.youqing.sts1solver.search;

import io.github.youqing.sts1solver.sim.SimState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.File;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 严格相邻回合的 Continuation 配对探针：**Tn 的搜索有没有真的预测对 Tn+1**。
 *
 * <p>用法：
 * <pre>
 * ./gradlew test --offline -Pexperiments --tests "*ContinuationPairProbeTest" \
 *     -DbatchPair=&lt;冻结快照目录&gt; -Dout=&lt;pair.tsv&gt; -Dlimit=&lt;n&gt;
 * </pre>
 *
 * <p>三条硬口径（写错任何一条，命中率都会变成假的）：
 * <ol>
 *   <li>只配**同一次运行、同 act/floor、turn 恰好 +1** 的两份快照 —— 跨 run 的同名文件
 *       会被覆盖写，混进来就等于拿这次的结果去比上次的预测；</li>
 *   <li>读快照一律 {@link GoldenPositions#readStrict}：缺 turn / pendingMove / RNG 等
 *       v5 字段的旧文件只报 {@code incomplete_schema}，**不进命中率分母**；</li>
 *   <li>比较的是 {@link ContinuationStamp#captureLive} 的 canonicalText —— 与真机走同一个入口，
 *       不做任何「差不多算命中」的放宽。</li>
 * </ol>
 */
class ContinuationPairProbeTest {

    private static BeamSolver realConfig() {
        return new BeamSolver(3, 5, 135, 500, 500_000, 72).withNovelty().withPortfolio();
    }

    @Test
    @EnabledIfSystemProperty(named = "sts1.experiments", matches = "true",
            disabledReason = "离线段对（gradle test -Pexperiments -DbatchPair=... 才跑）")
    void adjacentTurnPairs() throws Exception {
        String dir = System.getProperty("batchPair", "");
        assertTrue(!dir.isEmpty(), "must pass -DbatchPair=<snapshot dir>");
        File[] files = new File(dir).listFiles();
        assertTrue(files != null, "not a directory: " + dir);

        List<SimState> states = new ArrayList<SimState>();
        List<String> names = new ArrayList<String>();
        int incomplete = 0;
        for (File f : files) {
            if (!f.getName().startsWith("turn-") || !f.getName().endsWith(".json")) {
                continue;
            }
            try {
                states.add(GoldenPositions.readStrict(f));
                names.add(f.getName());
            } catch (RuntimeException e) {
                incomplete++;
            }
        }

        // 按 (act, floor, turn) 排序，方便二分/线性找下一回合
        Integer[] order = new Integer[states.size()];
        for (int i = 0; i < order.length; i++) {
            order[i] = Integer.valueOf(i);
        }
        final List<SimState> all = states;
        Arrays.sort(order, new Comparator<Integer>() {
            @Override
            public int compare(Integer a, Integer b) {
                SimState x = all.get(a.intValue());
                SimState y = all.get(b.intValue());
                if (x.act != y.act) {
                    return x.act < y.act ? -1 : 1;
                }
                if (x.floor != y.floor) {
                    return x.floor < y.floor ? -1 : 1;
                }
                return x.turn < y.turn ? -1 : (x.turn == y.turn ? 0 : 1);
            }
        });

        int limit = Integer.parseInt(System.getProperty("limit", "0"));
        int pairs = 0;
        int hit = 0;
        int miss = 0;
        int anchorMissing = 0;
        int policyUnsatisfied = 0;
        List<String> rows = new ArrayList<String>();
        rows.add("from\tto\tstatus\treason\tfirstDifference\troute\tfinalPool\tmillis");

        for (int k = 0; k < order.length; k++) {
            if (limit > 0 && pairs >= limit) {
                break;
            }
            SimState from = all.get(order[k].intValue());
            // 找同 act/floor、turn = from.turn + 1 的那一份
            SimState to = null;
            String toName = null;
            for (int j = k + 1; j < order.length; j++) {
                SimState candidate = all.get(order[j].intValue());
                if (candidate.act != from.act || candidate.floor != from.floor) {
                    break;
                }
                if (candidate.turn == from.turn + 1) {
                    to = candidate;
                    toName = names.get(order[j].intValue());
                    break;
                }
                if (candidate.turn > from.turn + 1) {
                    break;
                }
            }
            if (to == null) {
                continue;
            }
            pairs++;

            BeamSolver solver = realConfig();
            long start = System.currentTimeMillis();
            try {
                solver.bestFirstAction(from, 20_000L);
            } catch (PotionPolicyUnsatisfiedException e) {
                policyUnsatisfied++;
                rows.add(row(names.get(order[k].intValue()), toName, "policy_unsatisfied",
                        e.getMessage(), "-", "-", "-", "0"));
                continue;
            }
            long millis = System.currentTimeMillis() - start;

            if (solver.lastContinuations.isEmpty()) {
                anchorMissing++;
                rows.add(row(names.get(order[k].intValue()), toName, "anchor_missing",
                        "selected route has no turn boundary", "-", "-", "-",
                        Long.toString(millis)));
                continue;
            }

            ContinuationStamp live = ContinuationStamp.captureLive(to);
            ContinuationPlan.Lookup lookup = solver.lastContinuations.lookup(to.turn, live);
            String status;
            String reason;
            String first;
            switch (lookup.status()) {
                case HIT:
                    status = "HIT";
                    reason = "";
                    first = "none";
                    hit++;
                    break;
                case STATE_MISMATCH:
                    status = "MISS";
                    reason = "stamp_mismatch";
                    first = lookup.firstDifference();
                    miss++;
                    break;
                default:
                    status = "MISS";
                    reason = "anchor_missing";
                    first = "no cached continuation for turn " + to.turn;
                    anchorMissing++;
                    break;
            }
            rows.add(row(names.get(order[k].intValue()), toName, status, reason, first,
                    routeOf(solver), Integer.toString(solver.lastContinuations.size()),
                    Long.toString(millis)));
        }

        String out = System.getProperty("out");
        if (out != null && !out.isEmpty()) {
            PrintWriter w = new PrintWriter(out, "UTF-8");
            try {
                for (String r : rows) {
                    w.println(r);
                }
            } finally {
                w.close();
            }
        }
        System.out.println("CONTINUATION PAIRS total=" + pairs
                + " hit=" + hit + " miss=" + miss
                + " anchor_missing=" + anchorMissing
                + " policy_unsatisfied=" + policyUnsatisfied
                + " incomplete_schema=" + incomplete);
        for (String r : rows) {
            System.out.println(r);
        }
        // 分母口径：只有真正产生过比较的样本才算命中率。incomplete 一律不进分母。
        int denominator = hit + miss;
        if (denominator > 0) {
            System.out.println(String.format(Locale.ROOT,
                    "CONTINUATION HIT RATE %d/%d = %.1f%%",
                    Integer.valueOf(hit), Integer.valueOf(denominator),
                    Double.valueOf(100.0 * hit / denominator)));
        }
    }

    private static String routeOf(BeamSolver solver) {
        return solver.lastStopReason == null ? "-" : solver.lastStopReason.toString();
    }

    private static String row(String from, String to, String status, String reason,
                              String first, String route, String pool, String millis) {
        return from + "\t" + to + "\t" + status + "\t" + reason + "\t"
                + String.valueOf(first).replace('\t', ' ').replace('\n', ' ')
                + "\t" + route + "\t" + pool + "\t" + millis;
    }
}
