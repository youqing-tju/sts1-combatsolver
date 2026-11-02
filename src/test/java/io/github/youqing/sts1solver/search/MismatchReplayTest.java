package io.github.youqing.sts1solver.search;

import io.github.youqing.sts1solver.mirror.ActionRejected;
import io.github.youqing.sts1solver.mirror.CardPlayer;
import io.github.youqing.sts1solver.sim.SimState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.File;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 通用失配回放：把真机 {@code mismatch/*.json} 当成**可重复执行的用例**。
 *
 * <p>用法：
 * <pre>
 * ./gradlew test --offline -Pexperiments --tests "*MismatchReplayTest" -Dcase=&lt;dump.json|目录&gt;
 * </pre>
 * 可选 {@code -Dout=<tsv>}、{@code -Dlimit=<n>}。
 *
 * <p>三种样本严格分开，**后两种不得当作 diff=0 的 oracle**：
 * <ol>
 *   <li>{@code replay}：完整 v5 三态、非 forced —— 可以断言「重放结果等于 live」；</li>
 *   <li>{@code pending}：{@code forced=true} 或 90 帧未决 —— 只报告，不参与结论；</li>
 *   <li>{@code incomplete_schema}：旧 dump 缺 v5 字段 —— 读取即失败，单列计数。</li>
 * </ol>
 *
 * <p>为什么不吞掉 forced 样本又不用它做断言：forced 意味着「当时还没结算完」，
 * 它既不能证明影子错，也不能证明影子对。正确做法是留着样本、等稳定门修好后重新采集，
 * 而不是把「没有稳定证据」当成「已修复」。
 */
class MismatchReplayTest {

    @Test
    @EnabledIfSystemProperty(named = "sts1.experiments", matches = "true",
            disabledReason = "离线回放（gradle test -Pexperiments -Dcase=... 才跑）")
    void replayMismatchCases() throws Exception {
        String path = System.getProperty("case");
        assertTrue(path != null && !path.isEmpty(),
                "must pass -Dcase=<dump.json|dir>");
        File root = new File(path);
        assertTrue(root.exists(), "case path does not exist: " + path);

        List<File> files = new ArrayList<File>();
        collect(root, files);
        int limit = Integer.parseInt(System.getProperty("limit", "0"));
        if (limit > 0 && files.size() > limit) {
            files = files.subList(0, limit);
        }

        int replayed = 0;
        int matched = 0;
        int mismatched = 0;
        int pending = 0;
        int incomplete = 0;
        List<String> rows = new ArrayList<String>();
        rows.add("file\tstatus\tcard\tforced\tfield_diffs\tdetail");

        for (File file : files) {
            GoldenPositions.MismatchCase c;
            try {
                c = GoldenPositions.readMismatch(file);
            } catch (RuntimeException e) {
                incomplete++;
                rows.add(row(file.getName(), "incomplete_schema", "?", "?", "-", oneLine(e)));
                continue;
            }
            if (c.forced) {
                pending++;
                rows.add(row(file.getName(), "pending", c.card, "true",
                        Integer.toString(c.diffs.size()), "forced sample: no oracle"));
                continue;
            }
            List<String> got;
            try {
                got = replay(c);
            } catch (ActionRejected e) {
                pending++;
                rows.add(row(file.getName(), "pending", c.card, "false",
                        Integer.toString(c.diffs.size()), "replay rejected: " + e.getMessage()));
                continue;
            } catch (RuntimeException e) {
                incomplete++;
                rows.add(row(file.getName(), "incomplete_schema", c.card, "false",
                        "-", oneLine(e)));
                continue;
            }
            replayed++;
            if (got.isEmpty()) {
                matched++;
                rows.add(row(file.getName(), "replay_match", c.card, "false", "0", ""));
            } else {
                mismatched++;
                rows.add(row(file.getName(), "replay_mismatch", c.card, "false",
                        Integer.toString(got.size()), join(got)));
            }
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
        StringBuilder summary = new StringBuilder();
        summary.append("MISMATCH REPLAY cases=").append(files.size())
               .append(" replay=").append(replayed)
               .append(" match=").append(matched)
               .append(" mismatch=").append(mismatched)
               .append(" pending=").append(pending)
               .append(" incomplete_schema=").append(incomplete);
        System.out.println(summary);
        for (String r : rows) {
            System.out.println(r);
        }
        // 断言口径：**能重放**的样本里不允许出现差异。pending / incomplete 不参与。
        //
        // 🔴 旧 dump（v5 之前）全部缺 turn / boss / pendingMove 等字段，只能报
        //    incomplete_schema —— 那不是失败，是「这批证据不足以下结论」。
        //    把「全是 incomplete」判成失败，会逼人去补默认值，那才是伪造 oracle。
        assertEquals(files.size(), replayed + pending + incomplete,
                "every case must be classified: " + summary);
        assertTrue(mismatched == 0,
                "replay mismatches remain: " + mismatched + " / " + replayed);
        if (replayed == 0 && !files.isEmpty()) {
            System.out.println("NOTE: no replayable case in this set (all pending/incomplete) — "
                    + "re-capture with a v5 build before drawing conclusions");
        }
    }

    /** 从 before 重放同一张牌，与 live 比。**只在真正稳定的样本上调用**。 */
    private static List<String> replay(GoldenPositions.MismatchCase c) {
        SimState sim = c.before.copy();
        CardPlayer.playByKey(sim, c.card, c.target, choicesFor(c, sim));
        return sim.copy().dropDeadMonsters().diff(c.live.copy().dropDeadMonsters());
    }

    /**
     * 把 dump 里的选择映射成影子下标。
     *
     * <p>只支持「手牌里的选择」（{@code pile=HAND} 或旧格式无牌堆信息）；
     * 弃牌堆/抽牌堆/消耗堆的选择在 Task 5B 之前**显式拒绝**，不猜一个下标 ——
     * 猜错会得到一个看起来很像的假失配。
     */
    private static int[] choicesFor(GoldenPositions.MismatchCase c, SimState sim) {
        if (c.choices.isEmpty()) {
            return null;
        }
        int[] out = new int[c.choices.size()];
        for (int i = 0; i < out.length; i++) {
            GoldenPositions.Choice choice = c.choices.get(i);
            if (choice.pile != null && !"HAND".equals(choice.pile)) {
                throw new ActionRejected("choice pile not replayable yet: " + choice);
            }
            out[i] = handIndexOf(sim, choice.key, choice.index);
        }
        return out;
    }

    private static int handIndexOf(SimState sim, String key, int hint) {
        if (hint >= 0 && hint < sim.hand.size() && sim.hand.get(hint).key().equals(key)) {
            return hint;
        }
        for (int i = 0; i < sim.hand.size(); i++) {
            if (sim.hand.get(i).key().equals(key)) {
                return i;
            }
        }
        throw new ActionRejected("chosen card not in shadow hand: " + key + " hint=" + hint);
    }

    private static void collect(File f, List<File> out) {
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children == null) {
                return;
            }
            Arrays.sort(children);
            for (File child : children) {
                collect(child, out);
            }
            return;
        }
        if (f.getName().toLowerCase(Locale.ROOT).endsWith(".json")) {
            out.add(f);
        }
    }

    private static String row(String file, String status, String card, String forced,
                              String diffs, String detail) {
        return file + "\t" + status + "\t" + card + "\t" + forced + "\t" + diffs
                + "\t" + detail.replace('\t', ' ').replace('\n', ' ');
    }

    private static String join(List<String> values) {
        StringBuilder sb = new StringBuilder();
        for (String v : values) {
            if (sb.length() > 0) {
                sb.append(" | ");
            }
            sb.append(v);
        }
        return sb.toString();
    }

    private static String oneLine(Throwable t) {
        return t.getClass().getSimpleName() + ": " + t.getMessage();
    }
}
