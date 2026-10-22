package io.github.youqing.sts1solver;

import com.evacipated.cardcrawl.modthespire.lib.SpireInitializer;

import io.github.youqing.sts1solver.diag.Trace;
import io.github.youqing.sts1solver.patches.PatchManifest;
import io.github.youqing.sts1solver.ui.SolverPanel;

/**
 * ModTheSpire entry point. MTS discovers this class through the
 * {@link SpireInitializer} annotation.
 */
@SpireInitializer
public class SolverMod {

    public static final String MOD_ID = "sts1combatsolver";
    public static final String VERSION = "0.0.1";

    public static void initialize() {
        Trace.init();
        Trace.info("SolverMod " + VERSION + " build " + BuildStamp.VALUE
                + " initializing (jre=" + System.getProperty("java.version") + ")");
        Trace.info("log file: " + Trace.path());
        io.github.youqing.sts1solver.config.SolverConfig.load();
        SolverPanel.initialize();
        PatchManifest.report();
        Trace.info("SolverMod ready");
    }
}
