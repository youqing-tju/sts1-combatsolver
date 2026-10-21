package io.github.youqing.sts1solver.capture;

import com.megacrit.cardcrawl.cards.AbstractCard;
import com.megacrit.cardcrawl.helpers.CardLibrary;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.util.Map;

import io.github.youqing.sts1solver.diag.Trace;

/**
 * Dumps the whole card pool straight out of the game's own {@link CardLibrary}.
 *
 * <p>Why this exists: discovering cards by playing them is hopelessly slow — a played card
 * only tells us "supported / not supported", one bit per fight. The library already holds
 * every card with its numbers, so we take the whole table in one shot and build the mirror
 * from data instead of from luck.
 *
 * <p>Runs once, on the first combat capture (the game is fully initialized by then).
 */
public final class CardExporter {

    private static boolean done;

    private CardExporter() {
    }

    public static void exportOnce() {
        if (done) {
            return;
        }
        done = true;
        try {
            if (CardLibrary.cards == null || CardLibrary.cards.isEmpty()) {
                Trace.warn("card export skipped: CardLibrary not initialised");
                return;
            }

            StringBuilder sb = new StringBuilder(64 * 1024);
            sb.append("{\n  \"source\": \"CardLibrary\",\n  \"cards\": [\n");
            int n = 0;
            for (Map.Entry<String, AbstractCard> e : CardLibrary.cards.entrySet()) {
                AbstractCard base = e.getValue();
                if (base == null) {
                    continue;
                }
                if (n > 0) {
                    sb.append(",\n");
                }
                sb.append("    ").append(row(base));
                n++;
            }
            sb.append("\n  ],\n  \"count\": ").append(n).append("\n}");

            String dirPath = System.getProperty("sts1solver.dataDir", "solver-data");
            File dir = new File(dirPath);
            if (!dir.exists() && !dir.mkdirs()) {
                Trace.warn("card export failed: cannot create " + dir.getAbsolutePath());
                return;
            }
            File out = new File(dir, "cards.json");
            OutputStreamWriter w = new OutputStreamWriter(new FileOutputStream(out), "UTF-8");
            try {
                w.write(sb.toString());
            } finally {
                w.close();
            }
            Trace.info("DATA cards=" + n + " -> " + out.getAbsolutePath());
        } catch (Throwable t) {
            Trace.error("card export failed", t);
        }
    }

    /** One card plus its upgraded form, so the mirror table can be generated mechanically. */
    private static String row(AbstractCard base) {
        StringBuilder sb = new StringBuilder(256);
        sb.append("{\"id\":\"").append(base.cardID).append('"')
          .append(",\"name\":\"").append(esc(base.name)).append('"')
          .append(",\"color\":\"").append(base.color).append('"')
          .append(",\"type\":\"").append(base.type).append('"')
          .append(",\"cost\":").append(base.cost)
          .append(",\"dmg\":").append(base.baseDamage)
          .append(",\"blk\":").append(base.baseBlock)
          .append(",\"magic\":").append(base.baseMagicNumber)
          .append(",\"canUpgrade\":").append(base.canUpgrade());

        try {
            AbstractCard up = base.makeCopy();
            if (up.canUpgrade()) {
                up.upgrade();
            }
            sb.append(",\"up\":{\"cost\":").append(up.cost)
              .append(",\"dmg\":").append(up.baseDamage)
              .append(",\"blk\":").append(up.baseBlock)
              .append(",\"magic\":").append(up.baseMagicNumber)
              .append('}');
        } catch (Throwable t) {
            sb.append(",\"up\":null");
        }
        return sb.append('}').toString();
    }

    private static String esc(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
