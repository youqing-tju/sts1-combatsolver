package io.github.youqing.sts1solver.diag;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Minimal file logger. M0 has no framework on purpose: one file, one format,
 * flushed on every line so a crash still leaves evidence behind.
 */
public final class Trace {

    private static final String DEFAULT_DIR = "solver-logs";

    private static PrintWriter writer;
    private static String filePath = "(not initialized)";

    private static final SimpleDateFormat TS = new SimpleDateFormat("HH:mm:ss.SSS");
    private static final SimpleDateFormat DAY = new SimpleDateFormat("yyyy-MM-dd");

    private Trace() {
    }

    public static synchronized void init() {
        if (writer != null) {
            return;
        }
        try {
            String dirPath = System.getProperty("sts1solver.logDir", DEFAULT_DIR);
            File dir = new File(dirPath);
            if (!dir.exists() && !dir.mkdirs()) {
                throw new IllegalStateException("cannot create " + dir.getAbsolutePath());
            }
            File file = new File(dir, "sts1-combatsolver-" + DAY.format(new Date()) + ".log");
            writer = new PrintWriter(new OutputStreamWriter(new FileOutputStream(file, true), "UTF-8"), true);
            filePath = file.getAbsolutePath();
        } catch (Throwable t) {
            System.err.println("[sts1-combatsolver] cannot open log file: " + t);
        }
    }

    public static String path() {
        return filePath;
    }

    /** Flushes and releases the log file. Safe to call more than once. */
    public static synchronized void close() {
        if (writer != null) {
            writer.flush();
            writer.close();
            writer = null;
        }
    }

    public static void info(String msg) {
        log("INFO", msg);
    }

    public static void warn(String msg) {
        log("WARN", msg);
    }

    public static void error(String msg, Throwable t) {
        StringWriter sw = new StringWriter();
        if (t != null) {
            t.printStackTrace(new PrintWriter(sw));
        }
        log("ERROR", msg + (t == null ? "" : "\n" + sw.toString()));
    }

    private static synchronized void log(String level, String msg) {
        String line = "[" + TS.format(new Date()) + "] [" + level + "] " + msg;
        System.out.println("[sts1-combatsolver] " + line);
        if (writer != null) {
            writer.println(line);
        }
    }
}
