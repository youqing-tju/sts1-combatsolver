package io.github.youqing.sts1solver.search;

import io.github.youqing.sts1solver.sim.SimMonster;
import io.github.youqing.sts1solver.sim.SimState;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 批量快照探针：把真机 {@code solver-snapshots/turn-*.json} 全部跑一遍搜索，
 * 每个局面打一行**确定性**指标，末尾打汇总。
 *
 * <p>存在理由：评价「路线质量改好了没有」不能看单局，也不能看耗时。这个探针给的是
 * **可逐行 diff 的 paired A/B 基线** —— 同一批快照、同一份配置、同一个节点上限，
 * 旧实现与新实现各跑一遍，`diff` 就知道哪些局面换了路线、换了首手、掉了多少血。
 *
 * <p>输出契约（改动会导致历史基线不可比，必须升 {@link #FORMAT}）：
 * <ul>
 *   <li>每行 {@code key=value}，键按字母序，值与值之间单空格；</li>
 *   <li>快照按文件名字典序处理；</li>
 *   <li>{@code route} 用 {@code |} 连接：出牌 {@code id+@目标}、喝药 {@code POTION:槽:ID}、
 *       结束回合 {@code END_TURN}；</li>
 *   <li>同一份输入必须产生逐字节相同的输出（不写耗时、不写毫秒）。</li>
 * </ul>
 *
 * <p>跑法（不带 {@code -Dbatch} 时自动跳过）：
 * <pre>
 * gradlew test --offline --tests "*SnapshotBatchProbeTest" -Dbatch=C:/.../solver-snapshots
 * </pre>
 * 可选：{@code -Dout=<file>} 把同一份 TSV 落盘（表头 + 每快照一行），
 * 供 Task 7 的配对分析直接读；{@code -Dlimit=<n>} 只跑前 n 个（冒烟用，不进基线）。
 */
class SnapshotBatchProbeTest {

    /** 输出格式版本。字段增删或口径变化必须升它，否则旧基线会被当成可比。 */
    static final String FORMAT = "snapshot-probe-v3";

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
        int limit = Integer.parseInt(System.getProperty("limit", "0"));
        if (limit > 0 && snapshots.size() > limit) {
            snapshots = new ArrayList<File>(snapshots.subList(0, limit));
        }

        int wins = 0;
        long totalNodes = 0;
        int exhausted = 0;
        int unsatisfied = 0;
        StringBuilder rows = new StringBuilder();
        StringBuilder tsv = new StringBuilder();
        tsv.append("format\t").append(FORMAT).append('\n');
        tsv.append("snap\tfirst\twin\tstop\thp\tmaxHp\tenemyHp\tactions\tpotions\t"
                + "score\tnodes\tdeficit\thealthCost\tgrowthReward\tgrowthCredit\t"
                + "longTerm\tanger\toptionalPotions\tsold\tboundary\tpool\trejected\troute\n");
        for (File f : snapshots) {
            SimState root = GoldenPositions.read(f);
            // 2026-10-31 之前的快照没有落盘 boss 标志；按楼层补猜（16/33/50 = 各幕 Boss 房），
            // 否则 Boss 房会被当成普通房，测不到「延迟收益只在多敌人或 Boss 启用」这条。
            if (root.floor == 16 || root.floor == 33 || root.floor == 50) {
                root.boss = true;
            }
            BeamSolver solver = realConfig();
            try {
                solver.bestFirstAction(root, 20_000L);
            } catch (PotionPolicyUnsatisfiedException e) {
                // 蓝本同款失败语义：池子里没有合格候选就直接抛。
                // 探针要把这种局面**记下来**，而不是让整批中断 —— 它本身就是一条结论。
                Metrics m = new Metrics();
                m.snap = f.getName().replace("turn-", "").replace(".json", "");
                m.first = "NONE";
                m.win = false;
                m.stop = "policy_unsatisfied";
                m.route = "-";
                rows.append(m.line()).append('\n');
                tsv.append(m.tsv()).append('\n');
                unsatisfied++;
                continue;
            }

            Metrics m = metrics(f, solver);
            if (m.win) {
                wins++;
            }
            if (!solver.hitNodeLimit) {
                exhausted++;
            }
            totalNodes += solver.lastNodes;

            rows.append(m.line()).append('\n');
            tsv.append(m.tsv()).append('\n');
        }

        System.out.println("\n=== SNAPSHOT BATCH (" + snapshots.size() + " 个局面, " + FORMAT + ") ===");
        System.out.print(rows);
        System.out.println(String.format(
                "SUMMARY snapshots=%d wins=%d horizonCompleted=%d avgNodes=%d",
                snapshots.size(), wins, exhausted,
                snapshots.isEmpty() ? 0 : totalNodes / snapshots.size()));
        if (unsatisfied > 0) {
            System.out.println("policyUnsatisfied=" + unsatisfied
                    + "（这些局面在现有药水策略下没有合格路线，蓝本同款失败语义）");
        }

        String out = System.getProperty("out", "");
        if (!out.isEmpty()) {
            java.nio.file.Files.write(new File(out).toPath(), tsv.toString().getBytes("UTF-8"));
            System.out.println("WROTE " + out);
        }
    }

    /**
     * 输出契约本身也要钉住：字段增删/换序都会让历史基线不可比，
     * 所以这里用一份人造指标比字符串，改格式必须先改这条测试与 {@link #FORMAT}。
     */
    @Test
    void metricLineFormatIsPinned() {
        Metrics m = new Metrics();
        m.snap = "A1T3";
        m.first = "Strike_R";
        m.win = true;
        m.stop = "victory";
        m.hp = 42;
        m.maxHp = 80;
        m.enemyHp = 0;
        m.actions = 3;
        m.potions = 1;
        m.score = 123L;
        m.nodes = 7;
        m.route = "Strike_R@0|POTION:1:Fire Potion|END_TURN";

        m.deficit = 5;
        m.healthCost = -3;
        m.growthReward = 1;
        m.growthCredit = 0;
        m.longTerm = 20;
        m.anger = 2;
        m.optionalPotions = 1;
        m.sold = 2;
        m.boundary = "NONE";
        m.pool = 9;
        m.rejected = 4;

        assertEquals("snap=A1T3 first=Strike_R win=1 stop=victory hp=42 maxHp=80 enemyHp=0"
                        + " actions=3 potions=1 score=123 deficit=5 healthCost=-3"
                        + " growthReward=1 growthCredit=0 longTerm=20 anger=2 optionalPotions=1"
                        + " sold=2 boundary=NONE pool=9 rejected=4 route="
                        + "Strike_R@0|POTION:1:Fire Potion|END_TURN",
                m.line());
        assertEquals("snapshot-probe-v3", FORMAT,
                "改输出字段必须同时升 FORMAT，否则历史基线会被当成可比");
    }

    /** 一个快照的确定性指标行。 */
    static final class Metrics {
        String snap;
        String first;
        boolean win;
        String stop;
        int hp;
        int maxHp;
        int enemyHp;
        int actions;
        int potions;
        long score;
        int nodes;
        String route;
        int deficit;
        int healthCost;
        int growthReward;
        int growthCredit;
        int longTerm;
        int anger;
        int optionalPotions;
        int sold;
        String boundary = "NONE";
        int pool;
        int rejected;

        String line() {
            return "snap=" + snap
                    + " first=" + first
                    + " win=" + (win ? 1 : 0)
                    + " stop=" + stop
                    + " hp=" + hp
                    + " maxHp=" + maxHp
                    + " enemyHp=" + enemyHp
                    + " actions=" + actions
                    + " potions=" + potions
                    + " score=" + score
                    + " deficit=" + deficit
                    + " healthCost=" + healthCost
                    + " growthReward=" + growthReward
                    + " growthCredit=" + growthCredit
                    + " longTerm=" + longTerm
                    + " anger=" + anger
                    + " optionalPotions=" + optionalPotions
                    + " sold=" + sold
                    + " boundary=" + boundary
                    + " pool=" + pool
                    + " rejected=" + rejected
                    + " route=" + route;
        }

        String tsv() {
            return snap + '\t' + first + '\t' + (win ? 1 : 0) + '\t' + stop + '\t' + hp + '\t'
                    + maxHp + '\t' + enemyHp + '\t' + actions + '\t' + potions + '\t' + score
                    + '\t' + nodes + '\t' + deficit + '\t' + healthCost + '\t' + growthReward
                    + '\t' + growthCredit + '\t' + longTerm + '\t' + anger
                    + '\t' + optionalPotions + '\t' + sold + '\t' + boundary
                    + '\t' + pool + '\t' + rejected + '\t' + route;
        }
    }

    private static Metrics metrics(File f, BeamSolver solver) {
        Metrics m = new Metrics();
        m.snap = f.getName().replace("turn-", "").replace(".json", "");
        // 首手记**牌名**而不是手牌下标：下标会随镜像里手牌顺序变化而漂移，
        // 用它做 A/B 会把「同一张牌、不同下标」误报成「换了首手」。
        m.first = solver.lastSteps.isEmpty() ? "NONE"
                : (solver.lastSteps.get(0).endTurn ? "END_TURN"
                : (solver.lastSteps.get(0).potion
                ? "POTION:" + solver.lastSteps.get(0).cardKey
                : solver.lastSteps.get(0).cardKey));
        m.win = solver.lastSelectedVictory;
        m.stop = solver.hitNodeLimit ? "node_limit" : solver.lastStopReason;
        m.score = solver.lastSelectedScore;
        m.nodes = solver.lastNodes;

        SearchNode node = solver.lastSelectedNode;
        if (node == null) {
            m.hp = 0;
            m.maxHp = 0;
            m.enemyHp = 0;
        } else {
            m.hp = node.state.hp;
            m.maxHp = node.state.maxHp;
            m.enemyHp = enemyHp(node.state);
        }
        m.actions = solver.lastSteps.size();
        int potions = 0;
        StringBuilder route = new StringBuilder();
        for (RouteStep step : solver.lastSteps) {
            if (route.length() > 0) {
                route.append('|');
            }
            if (step.endTurn) {
                route.append("END_TURN");
            } else if (step.potion) {
                potions++;
                route.append("POTION:").append(step.potionSlot).append(':').append(step.cardKey);
            } else {
                route.append(step.cardKey).append('@').append(step.targetIndex);
            }
        }
        m.potions = potions;
        m.route = route.length() == 0 ? "-" : route.toString();

        // 蓝本 18 键里**参与比较**的那些派生量（Step 7.1 要求逐快照可见）。
        // 口径必须与最终选择器一致，所以直接复用 evaluate()，不另写一套公式。
        m.pool = solver.lastFinalPool.size();
        m.rejected = solver.lastFinalCandidatesRejected;
        if (node != null) {
            FinalPlanOrdering.CandidateResult r = FinalPlanOrdering.evaluate(
                    solver.finalCandidate(node, 0), solver.finalParams(null));
            m.deficit = r.strategicHpDeficit;
            m.healthCost = r.healthResourceCost;
            m.growthReward = r.quality.growthRewardCount;
            m.growthCredit = r.quality.growthHpCredit;
            m.longTerm = r.features.longTermResourceValue;
            m.anger = r.features.angerCopiesGenerated;
            m.optionalPotions = r.optionalPotionCount;
            m.sold = r.strategicSold;
            m.boundary = String.valueOf(r.features.boundaryReason);
        }
        return m;
    }

    private static int enemyHp(SimState state) {
        int total = 0;
        for (SimMonster monster : state.monsters) {
            if (monster.hp > 0) {
                total += monster.hp;
            }
        }
        return total;
    }
}
