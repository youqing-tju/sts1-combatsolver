package io.github.youqing.sts1solver.sim;

import com.megacrit.cardcrawl.cards.AbstractCard;
import com.megacrit.cardcrawl.cards.CardGroup;
import com.megacrit.cardcrawl.characters.AbstractPlayer;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.monsters.AbstractMonster;
import com.megacrit.cardcrawl.monsters.MonsterGroup;
import com.megacrit.cardcrawl.powers.AbstractPower;
import com.megacrit.cardcrawl.relics.AbstractRelic;
import com.megacrit.cardcrawl.ui.panels.EnergyPanel;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The shadow combat state: plain data, no game objects, safe to fork and to advance on a
 * background thread.
 *
 * <p>This is the single authority for "what a combat state is". Anything that needs to
 * compare states (the diff harness, the transposition table, the search) goes through
 * this class, so there is exactly one definition to keep honest.
 *
 * <p>{@link #capture()} reads the live game and must run on the <b>main thread</b>.
 * Everything else ({@link #copy()}, {@link #diff}, {@link #fingerprint()}) is pure.
 */
public final class SimState {

    /** The RNG streams that matter for combat. */
    public enum RngKind {
        CARD, MONSTER, AI, SHUFFLE, MISC
    }

    public int act;
    public int floor;
    public int ascension;

    public int hp;
    public int maxHp;
    public int block;
    public int energy;

    public final TreeMap<String, Integer> playerPowers = new TreeMap<String, Integer>();
    public final List<SimCard> hand = new ArrayList<SimCard>();
    public final List<SimCard> draw = new ArrayList<SimCard>();
    public final List<SimCard> discard = new ArrayList<SimCard>();
    public final List<SimCard> exhaust = new ArrayList<SimCard>();
    public final TreeMap<String, Integer> relics = new TreeMap<String, Integer>();
    public final List<SimMonster> monsters = new ArrayList<SimMonster>();
    public final EnumMap<RngKind, RngStream> rng = new EnumMap<RngKind, RngStream>(RngKind.class);

    // ----------------------------------------------------------------- capture

    /** Main thread only: reads the live combat. */
    public static SimState capture() {
        AbstractPlayer p = AbstractDungeon.player;

        SimState s = new SimState();
        s.act = AbstractDungeon.actNum;
        s.floor = AbstractDungeon.floorNum;
        s.ascension = AbstractDungeon.ascensionLevel;

        s.hp = p.currentHealth;
        s.maxHp = p.maxHealth;
        s.block = p.currentBlock;
        s.energy = EnergyPanel.totalCount;
        s.playerPowers.putAll(powersOf(p.powers));

        addCards(p.hand, s.hand);
        addCards(p.drawPile, s.draw);
        addCards(p.discardPile, s.discard);
        addCards(p.exhaustPile, s.exhaust);

        if (p.relics != null) {
            for (AbstractRelic r : p.relics) {
                s.relics.put(r.relicId, r.counter);
            }
        }

        MonsterGroup group = AbstractDungeon.getMonsters();
        if (group != null && group.monsters != null) {
            for (AbstractMonster m : group.monsters) {
                s.monsters.add(new SimMonster(
                        m.getClass().getSimpleName(),
                        m.currentHealth,
                        m.maxHealth,
                        m.currentBlock,
                        powersOf(m.powers)));
            }
        }

        s.rng.put(RngKind.CARD, rngOf(AbstractDungeon.cardRandomRng));
        s.rng.put(RngKind.MONSTER, rngOf(AbstractDungeon.monsterRng));
        s.rng.put(RngKind.AI, rngOf(AbstractDungeon.aiRng));
        s.rng.put(RngKind.SHUFFLE, rngOf(AbstractDungeon.shuffleRng));
        s.rng.put(RngKind.MISC, rngOf(AbstractDungeon.miscRng));
        return s;
    }

    private static RngStream rngOf(com.megacrit.cardcrawl.random.Random rng) {
        if (rng == null) {
            return null;
        }
        return RngStream.from(rng.random.getState(0), rng.random.getState(1), rng.counter);
    }

    private static void addCards(CardGroup group, List<SimCard> into) {
        if (group == null || group.group == null) {
            return;
        }
        for (AbstractCard c : group.group) {
            into.add(new SimCard(c.cardID, c.upgraded, c.cost));
        }
    }

    private static Map<String, Integer> powersOf(java.util.ArrayList<AbstractPower> list) {
        TreeMap<String, Integer> map = new TreeMap<String, Integer>();
        if (list != null) {
            for (AbstractPower pw : list) {
                map.put(pw.ID, pw.amount);
            }
        }
        return map;
    }

    // -------------------------------------------------------------------- fork

    /** Deep copy: mutating the copy can never touch the original. */
    public SimState copy() {
        SimState c = new SimState();
        c.act = act;
        c.floor = floor;
        c.ascension = ascension;
        c.hp = hp;
        c.maxHp = maxHp;
        c.block = block;
        c.energy = energy;

        c.playerPowers.putAll(playerPowers);
        c.hand.addAll(hand);        // SimCard is immutable, sharing is safe
        c.draw.addAll(draw);
        c.discard.addAll(discard);
        c.exhaust.addAll(exhaust);
        c.relics.putAll(relics);

        for (SimMonster m : monsters) {
            c.monsters.add(m.copy());
        }
        for (Map.Entry<RngKind, RngStream> e : rng.entrySet()) {
            c.rng.put(e.getKey(), e.getValue() == null ? null : e.getValue().fork());
        }
        return c;
    }

    // -------------------------------------------------------------------- diff

    /**
     * Field-by-field comparison. Empty result means the two states are identical.
     * This is what the A1/A2 acceptance gates are built on.
     */
    public List<String> diff(SimState o) {
        List<String> out = new ArrayList<String>();
        cmp(out, "act", act, o.act);
        cmp(out, "floor", floor, o.floor);
        cmp(out, "ascension", ascension, o.ascension);
        cmp(out, "hp", hp, o.hp);
        cmp(out, "maxHp", maxHp, o.maxHp);
        cmp(out, "block", block, o.block);
        cmp(out, "energy", energy, o.energy);

        diffMap(out, "playerPowers", playerPowers, o.playerPowers);
        diffCards(out, "hand", hand, o.hand);
        diffCards(out, "draw", draw, o.draw);
        diffCards(out, "discard", discard, o.discard);
        diffCards(out, "exhaust", exhaust, o.exhaust);
        diffMap(out, "relics", relics, o.relics);

        if (monsters.size() != o.monsters.size()) {
            out.add("monsters.size: " + monsters.size() + " vs " + o.monsters.size());
        } else {
            for (int i = 0; i < monsters.size(); i++) {
                SimMonster a = monsters.get(i);
                SimMonster b = o.monsters.get(i);
                String p = "monsters[" + i + "].";
                if (!a.type.equals(b.type)) {
                    out.add(p + "type: " + a.type + " vs " + b.type);
                }
                cmp(out, p + "hp", a.hp, b.hp);
                cmp(out, p + "maxHp", a.maxHp, b.maxHp);
                cmp(out, p + "block", a.block, b.block);
                diffMap(out, p + "powers", a.powers, b.powers);
            }
        }

        for (RngKind kind : RngKind.values()) {
            RngStream a = rng.get(kind);
            RngStream b = o.rng.get(kind);
            if (a == null || b == null) {
                if (a != b) {
                    out.add("rng." + kind + ": null mismatch");
                }
                continue;
            }
            if (a.state0() != b.state0() || a.state1() != b.state1() || a.counter() != b.counter()) {
                out.add("rng." + kind + ": " + a.snapshot() + " vs " + b.snapshot());
            }
        }
        return out;
    }

    private static void cmp(List<String> out, String path, int a, int b) {
        if (a != b) {
            out.add(path + ": " + a + " vs " + b);
        }
    }

    private static void diffMap(List<String> out, String path, Map<String, Integer> a, Map<String, Integer> b) {
        for (Map.Entry<String, Integer> e : a.entrySet()) {
            Integer other = b.get(e.getKey());
            if (other == null) {
                out.add(path + "." + e.getKey() + ": " + e.getValue() + " vs (missing)");
            } else if (!other.equals(e.getValue())) {
                out.add(path + "." + e.getKey() + ": " + e.getValue() + " vs " + other);
            }
        }
        for (String key : b.keySet()) {
            if (!a.containsKey(key)) {
                out.add(path + "." + key + ": (missing) vs " + b.get(key));
            }
        }
    }

    private static void diffCards(List<String> out, String path, List<SimCard> a, List<SimCard> b) {
        if (a.size() != b.size()) {
            out.add(path + ".size: " + a.size() + " vs " + b.size());
        }
        int n = Math.min(a.size(), b.size());
        for (int i = 0; i < n; i++) {
            if (!a.get(i).equals(b.get(i))) {
                out.add(path + "[" + i + "]: " + a.get(i) + " vs " + b.get(i));
            }
        }
    }

    // ------------------------------------------------------------------- output

    /** Stable one-line summary for the overlay. */
    public String shortLine() {
        return "A" + act + " F" + floor + "  hp " + hp + "/" + maxHp
                + "  e" + energy + "  hand " + hand.size() + "  foes " + monsters.size();
    }

    /** 64-bit FNV-1a over the JSON form: cheap, stable, good enough for a transposition table. */
    public String fingerprint() {
        return fingerprintOf(toJson());
    }

    public static String fingerprintOf(String payload) {
        long hash = 0xcbf29ce484222325L;
        byte[] bytes;
        try {
            bytes = payload.getBytes("UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            bytes = payload.getBytes();
        }
        for (byte b : bytes) {
            hash ^= (b & 0xff);
            hash *= 0x100000001b3L;
        }
        return Long.toHexString(hash);
    }

    public String toJson() {
        StringBuilder sb = new StringBuilder(512);
        sb.append("{\n  \"meta\":{\"act\":").append(act)
          .append(",\"floor\":").append(floor)
          .append(",\"ascension\":").append(ascension).append("},\n");
        sb.append("  \"player\":{\"hp\":").append(hp)
          .append(",\"maxHp\":").append(maxHp)
          .append(",\"block\":").append(block)
          .append(",\"energy\":").append(energy)
          .append(",\"powers\":").append(mapJson(playerPowers)).append("},\n");
        sb.append("  \"hand\":").append(cardsJson(hand)).append(",\n");
        sb.append("  \"draw\":").append(cardsJson(draw)).append(",\n");
        sb.append("  \"discard\":").append(cardsJson(discard)).append(",\n");
        sb.append("  \"exhaust\":").append(cardsJson(exhaust)).append(",\n");
        sb.append("  \"relics\":").append(mapJson(relics)).append(",\n");
        sb.append("  \"monsters\":").append(monstersJson()).append(",\n");
        sb.append("  \"rng\":").append(rngJson()).append("\n}");
        return sb.toString();
    }

    private static String cardsJson(List<SimCard> cards) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < cards.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(cards.get(i).key()).append('"');
        }
        return sb.append(']').toString();
    }

    private static String mapJson(Map<String, Integer> map) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Integer> e : map.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append('"').append(e.getKey()).append("\":").append(e.getValue());
        }
        return sb.append('}').toString();
    }

    private String monstersJson() {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < monsters.size(); i++) {
            SimMonster m = monsters.get(i);
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"type\":\"").append(m.type)
              .append("\",\"hp\":").append(m.hp)
              .append(",\"maxHp\":").append(m.maxHp)
              .append(",\"block\":").append(m.block)
              .append(",\"powers\":").append(mapJson(m.powers)).append('}');
        }
        return sb.append(']').toString();
    }

    private String rngJson() {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (RngKind kind : RngKind.values()) {
            RngStream s = rng.get(kind);
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append('"').append(kind.name().toLowerCase()).append("\":");
            if (s == null) {
                sb.append("null");
            } else {
                sb.append("{\"counter\":").append(s.counter())
                  .append(",\"s0\":").append(s.state0())
                  .append(",\"s1\":").append(s.state1()).append('}');
            }
        }
        return sb.append('}').toString();
    }

    /** Writes the JSON next to the logs; returns the absolute path (or an error note). */
    public String save() {
        try {
            String dirPath = System.getProperty("sts1solver.snapshotDir", "solver-snapshots");
            File dir = new File(dirPath);
            if (!dir.exists() && !dir.mkdirs()) {
                return "save failed: cannot create " + dir.getAbsolutePath();
            }
            File f = new File(dir, "combat-" + System.currentTimeMillis() + ".json");
            OutputStreamWriter w = new OutputStreamWriter(new FileOutputStream(f), "UTF-8");
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
}
