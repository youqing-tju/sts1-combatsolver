package io.github.youqing.sts1solver.capture;

import com.megacrit.cardcrawl.cards.AbstractCard;
import com.megacrit.cardcrawl.cards.CardGroup;
import com.megacrit.cardcrawl.characters.AbstractPlayer;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.monsters.AbstractMonster;
import com.megacrit.cardcrawl.monsters.MonsterGroup;
import com.megacrit.cardcrawl.powers.AbstractPower;
import com.megacrit.cardcrawl.relics.AbstractRelic;
import com.megacrit.cardcrawl.ui.panels.EnergyPanel;

/**
 * Structured, read-only capture of the combat root state.
 *
 * <p>Design rules:
 * <ul>
 *   <li>Everything is read on the main thread; nothing is mutated.</li>
 *   <li>Cards are reduced to {@code id} plus {@code +} for upgraded — that is all the
 *       shadow simulation needs, and it keeps the snapshot diff-able.</li>
 *   <li>Draw pile order is preserved verbatim: the solver runs in "omniscient" mode
 *       (D8), so pile order is part of the state.</li>
 *   <li>RNG is captured as {@code counter} + both 128-bit XS128 states, which is enough
 *       to rebuild the exact stream.</li>
 * </ul>
 *
 * <p>M1 still has to prove that rebuilding a shadow state from this snapshot yields
 * {@code diff = 0}. This class only produces the evidence.
 */
public final class RootSnapshot {

    public final String meta;
    public final String player;
    public final String hand;
    public final String draw;
    public final String discard;
    public final String exhaust;
    public final String relics;
    public final String monsters;
    public final String rng;

    private RootSnapshot(String meta, String player, String hand, String draw, String discard,
                         String exhaust, String relics, String monsters, String rng) {
        this.meta = meta;
        this.player = player;
        this.hand = hand;
        this.draw = draw;
        this.discard = discard;
        this.exhaust = exhaust;
        this.relics = relics;
        this.monsters = monsters;
        this.rng = rng;
    }

    public static RootSnapshot capture() {
        AbstractPlayer p = AbstractDungeon.player;

        String meta = "{\"act\":" + AbstractDungeon.actNum
                + ",\"floor\":" + AbstractDungeon.floorNum
                + ",\"ascension\":" + AbstractDungeon.ascensionLevel + "}";

        String player = "{\"hp\":" + p.currentHealth
                + ",\"maxHp\":" + p.maxHealth
                + ",\"block\":" + p.currentBlock
                + ",\"energy\":" + EnergyPanel.totalCount
                + ",\"powers\":" + powers(p.powers) + "}";

        return new RootSnapshot(
                meta,
                player,
                cards(p.hand),
                cards(p.drawPile),
                cards(p.discardPile),
                cards(p.exhaustPile),
                relics(p.relics),
                monsters(AbstractDungeon.getMonsters()),
                rng());
    }

    public String toJson() {
        return "{\n"
                + "  \"meta\": " + meta + ",\n"
                + "  \"player\": " + player + ",\n"
                + "  \"hand\": " + hand + ",\n"
                + "  \"draw\": " + draw + ",\n"
                + "  \"discard\": " + discard + ",\n"
                + "  \"exhaust\": " + exhaust + ",\n"
                + "  \"relics\": " + relics + ",\n"
                + "  \"monsters\": " + monsters + ",\n"
                + "  \"rng\": " + rng + "\n"
                + "}";
    }

    /** Compact one-liner for the log: counts + RNG counters only. */
    public String summary() {
        return "hand=" + count(hand) + " draw=" + count(draw) + " discard=" + count(discard)
                + " exhaust=" + count(exhaust) + " " + rng;
    }

    /** Writes the JSON next to the logs; returns the absolute path (or an error note). */
    public String save() {
        try {
            String dirPath = System.getProperty("sts1solver.snapshotDir", "solver-snapshots");
            java.io.File dir = new java.io.File(dirPath);
            if (!dir.exists() && !dir.mkdirs()) {
                return "save failed: cannot create " + dir.getAbsolutePath();
            }
            java.io.File f = new java.io.File(dir, "combat-" + System.currentTimeMillis() + ".json");
            java.io.OutputStreamWriter w =
                    new java.io.OutputStreamWriter(new java.io.FileOutputStream(f), "UTF-8");
            try {
                w.write(toJson());
            } finally {
                w.close();
            }
            return f.getAbsolutePath();
        } catch (Throwable t) {
            return "save failed: " + t;
        }
    }

    // ------------------------------------------------------------------ pieces

    private static String cards(CardGroup group) {
        StringBuilder sb = new StringBuilder("[");
        if (group != null && group.group != null) {
            for (int i = 0; i < group.group.size(); i++) {
                AbstractCard c = group.group.get(i);
                if (i > 0) {
                    sb.append(',');
                }
                sb.append('"').append(c.cardID).append(c.upgraded ? "+" : "").append('"');
            }
        }
        return sb.append(']').toString();
    }

    private static String powers(java.util.ArrayList<AbstractPower> list) {
        StringBuilder sb = new StringBuilder("[");
        if (list != null) {
            for (int i = 0; i < list.size(); i++) {
                AbstractPower pw = list.get(i);
                if (i > 0) {
                    sb.append(',');
                }
                sb.append("{\"id\":\"").append(pw.ID).append("\",\"amt\":").append(pw.amount).append('}');
            }
        }
        return sb.append(']').toString();
    }

    private static String relics(java.util.ArrayList<AbstractRelic> list) {
        StringBuilder sb = new StringBuilder("[");
        if (list != null) {
            for (int i = 0; i < list.size(); i++) {
                AbstractRelic r = list.get(i);
                if (i > 0) {
                    sb.append(',');
                }
                sb.append("{\"id\":\"").append(r.relicId).append("\",\"ctr\":").append(r.counter).append('}');
            }
        }
        return sb.append(']').toString();
    }

    private static String monsters(MonsterGroup group) {
        StringBuilder sb = new StringBuilder("[");
        if (group != null && group.monsters != null) {
            for (int i = 0; i < group.monsters.size(); i++) {
                AbstractMonster m = group.monsters.get(i);
                if (i > 0) {
                    sb.append(',');
                }
                sb.append("{\"type\":\"").append(m.getClass().getSimpleName()).append('"')
                  .append(",\"hp\":").append(m.currentHealth)
                  .append(",\"maxHp\":").append(m.maxHealth)
                  .append(",\"block\":").append(m.currentBlock)
                  .append(",\"powers\":").append(powers(m.powers))
                  .append('}');
            }
        }
        return sb.append(']').toString();
    }

    private static String rng() {
        return "{" + one("card", AbstractDungeon.cardRandomRng)
                + "," + one("monster", AbstractDungeon.monsterRng)
                + "," + one("ai", AbstractDungeon.aiRng)
                + "," + one("shuffle", AbstractDungeon.shuffleRng)
                + "," + one("misc", AbstractDungeon.miscRng)
                + "}";
    }

    private static String one(String name, com.megacrit.cardcrawl.random.Random rng) {
        if (rng == null) {
            return "\"" + name + "\":null";
        }
        long s0 = 0L;
        long s1 = 0L;
        try {
            s0 = rng.random.getState(0);
            s1 = rng.random.getState(1);
        } catch (Throwable ignored) {
            // older builds may hide the state; counter alone still identifies the stream
        }
        return "\"" + name + "\":{\"counter\":" + rng.counter
                + ",\"s0\":" + s0 + ",\"s1\":" + s1 + "}";
    }

    private static int count(String array) {
        int n = 0;
        for (int i = 0; i < array.length(); i++) {
            if (array.charAt(i) == ',') {
                n++;
            }
        }
        return array.equals("[]") ? 0 : n + 1;
    }
}
