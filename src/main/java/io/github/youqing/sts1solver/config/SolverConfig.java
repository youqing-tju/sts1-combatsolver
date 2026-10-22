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

    /** 搜索预算档位。宽度/深度同时放大，节点数是二者乘积量级。 */
    public enum Budget {
        FAST("快", 4, 6, 8, 1000),
        NORMAL("标准", 6, 10, 50, 8000),
        DEEP("深", 10, 16, 300, 120000);

        public final String label;
        public final int beamWidth;
        /** 深度上限。搜索是迭代加深的，真正的深度由 {@link #budgetMillis} 决定。 */
        public final int depth;
        /** 每次决策的时间预算（跑在后台线程，不会卡帧）。 */
        public final long budgetMillis;
        /**
         * **整场战斗规划**的预算。蓝本实测是 1.1 s（第一场小怪）到 120 s（最终 boss），
         * 我们这里给的是起步值，可按需调大到 120000。
         */
        public final long planMillis;

        Budget(String label, int beamWidth, int depth, long budgetMillis, long planMillis) {
            this.label = label;
            this.beamWidth = beamWidth;
            this.depth = depth;
            this.budgetMillis = budgetMillis;
            this.planMillis = planMillis;
        }
    }

    private static final String MOD_ID = "sts1combatsolver";
    private static final String FILE = "config";
    private static final String KEY_BUDGET = "budget";
    private static final String KEY_AUTO_START = "autoStart";

    private static SpireConfig config;
    private static Budget budget = Budget.NORMAL;
    private static boolean autoStart;

    private SolverConfig() {
    }

    public static void load() {
        Properties defaults = new Properties();
        defaults.setProperty(KEY_BUDGET, Budget.NORMAL.name());
        defaults.setProperty(KEY_AUTO_START, "false");
        try {
            config = new SpireConfig(MOD_ID, FILE, defaults);
            String name = config.getString(KEY_BUDGET);
            for (Budget b : Budget.values()) {
                if (b.name().equals(name)) {
                    budget = b;
                }
            }
            autoStart = config.getBool(KEY_AUTO_START);
            Trace.info("config loaded: budget=" + budget.label
                    + " (" + budget.beamWidth + "x" + budget.depth
                    + "/" + budget.budgetMillis + "ms)"
                    + " autoStart=" + autoStart);
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

    /** 循环切档：快 → 标准 → 深 → 快，并立刻落盘。 */
    public static Budget cycleBudget() {
        Budget[] all = Budget.values();
        budget = all[(budget.ordinal() + 1) % all.length];
        save();
        Trace.info("budget -> " + budget.label + " (" + budget.beamWidth + "x" + budget.depth
                + "/" + budget.budgetMillis + "ms)");
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
