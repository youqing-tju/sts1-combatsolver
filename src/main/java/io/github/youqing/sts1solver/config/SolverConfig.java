package io.github.youqing.sts1solver.config;

import java.io.IOException;
import java.util.Properties;

import com.evacipated.cardcrawl.modthespire.lib.SpireConfig;

import io.github.youqing.sts1solver.diag.Trace;

/**
 * 求解器的持久化设置（M4-5）。
 *
 * <p>只放**改一次就长期有效**的东西：搜索预算、自动开始。运行期的临时状态（AUTO 开关、
 * 面板位置）不进配置文件 —— 那些一局一变，存下来反而会让人困惑。
 *
 * <p>配置落在 MTS 的标准位置：{@code SlayTheSpire/config/sts1combatsolver/config.properties}。
 * 读取失败一律退回默认值：设置文件坏了不该让 mod 起不来。
 */
public final class SolverConfig {

    /**
     * 搜索预算档位 —— 数值直接照抄蓝本 {@code SolverSearchProfile}
     * （{@code reference/sts2-combatsolver/src/Search/SolverSearchProfile.cs}）：
     *
     * <pre>
     *          BeamWidth  MaxExpandedNodes  MaxCardBranches  SoftTimeLimit
     * Low          45          60,000             24            60 s
     * Medium       60         120,000             32           120 s
     * High         90         250,000             48           180 s
     * VeryHigh    135         500,000             72           300 s
     * </pre>
     *
     * <p>⚠️ 我们之前只有「宽度 6~10」，比蓝本窄一个数量级 —— 这才是「搜索量太小」的真正原因。
     * 节点上限是**硬停止**：跑满就返回当前最好的完整路线（蓝本同样会在面板上标
     * 「计算尚未彻底穷尽，已达到节点上限」）。
     */
    public enum Budget {
        LOW("低", 45, 60_000, 24, 60_000),
        MEDIUM("中", 60, 120_000, 32, 120_000),
        HIGH("高", 90, 250_000, 48, 180_000),
        VERY_HIGH("极高", 135, 500_000, 72, 300_000);

        public final String label;
        public final int beamWidth;
        /** 生成状态数的硬上限（蓝本 MaxExpandedNodes）。 */
        public final int nodeLimit;
        /** 每个节点最多展开多少个「牌 × 目标」分支（蓝本 MaxCardBranchesPerNode）。 */
        public final int cardBranches;
        /** 软时间上限（毫秒），到点就返回当前最好的完整路线。 */
        public final long timeMillis;

        Budget(String label, int beamWidth, int nodeLimit, int cardBranches, long timeMillis) {
            this.label = label;
            this.beamWidth = beamWidth;
            this.nodeLimit = nodeLimit;
            this.cardBranches = cardBranches;
            this.timeMillis = timeMillis;
        }
    }

    private static final String MOD_ID = "sts1combatsolver";
    private static final String FILE = "config";
    private static final String KEY_BUDGET = "budget";
    private static final String KEY_AUTO_START = "autoStart";
    private static final String KEY_NOVELTY = "novelty";

    private static SpireConfig config;
    private static Budget budget = Budget.MEDIUM;
    private static boolean autoStart;
    /** 新颖度探索通道（蓝本 NoveltyPortfolio）；默认开，可在 config.properties 里关掉做对照。 */
    private static boolean novelty = true;

    private SolverConfig() {
    }

    public static void load() {
        Properties defaults = new Properties();
        defaults.setProperty(KEY_BUDGET, Budget.MEDIUM.name());
        defaults.setProperty(KEY_AUTO_START, "false");
        defaults.setProperty(KEY_NOVELTY, "true");
        try {
            config = new SpireConfig(MOD_ID, FILE, defaults);
            String name = config.getString(KEY_BUDGET);
            for (Budget b : Budget.values()) {
                if (b.name().equals(name)) {
                    budget = b;
                }
            }
            autoStart = config.getBool(KEY_AUTO_START);
            novelty = config.getBool(KEY_NOVELTY);
            Trace.info("config loaded: budget=" + budget.label
                    + " (width " + budget.beamWidth + ", nodes " + budget.nodeLimit
                    + ", time " + budget.timeMillis + "ms)"
                    + " autoStart=" + autoStart + " novelty=" + novelty);
        } catch (Throwable t) {
            // 配置坏了也要能玩：全部走默认值
            config = null;
            Trace.warn("config load failed, using defaults: " + t);
        }
    }

    public static Budget budget() {
        return budget;
    }

    public static boolean autoStart() {
        return autoStart;
    }

    /** 新颖度探索通道开关（蓝本 NoveltyPortfolio）：关掉只会少算，不会算错。 */
    public static boolean novelty() {
        return novelty;
    }

    /** 循环切档：快 → 标准 → 深 → 快，并立刻落盘。 */
    public static Budget cycleBudget() {
        Budget[] all = Budget.values();
        budget = all[(budget.ordinal() + 1) % all.length];
        save();
        Trace.info("budget -> " + budget.label + " (width " + budget.beamWidth
                + ", nodes " + budget.nodeLimit + ", time " + budget.timeMillis + "ms)");
        return budget;
    }

    /** 面板上的 auto-start 开关：下一场战斗开始时是否自动接管。 */
    public static boolean toggleAutoStart() {
        autoStart = !autoStart;
        save();
        Trace.info("autoStart -> " + autoStart);
        return autoStart;
    }

    private static void save() {
        if (config == null) {
            return;
        }
        try {
            config.setString(KEY_BUDGET, budget.name());
            config.setBool(KEY_AUTO_START, autoStart);
            config.save();
        } catch (IOException e) {
            Trace.warn("config save failed: " + e);
        }
    }
}
