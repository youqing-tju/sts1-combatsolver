#!/usr/bin/env python3
"""Generate the card-pool tables straight from the game's own bytecode.

Why this exists
---------------
`ColorlessPool` used to be generated from `solver-data/cards.json`, which lists
*every* card whose colour is COLORLESS — including tokens like Shiv / Smite /
Safety / Beta / Insight / Miracle / Omega / Ghostly / Expunger / ThroughViolence.
Those never appear in `srcColorlessCardPool`, so both the pool size and the index
`rng.random(size - 1)` were wrong.

The real pools are built in `AbstractDungeon.initializeCardPools`:
    list = CardLibrary.addColorlessCards() + player.getCardPool()   // player = Ironclad
    -> split by rarity into srcCommon/srcUncommon/srcRare, order preserved
and `returnTrulyRandomXInCombat` filters that list by type and drops HEALING cards.

So we need, for each card: id, type, rarity, cost, baseDamage, HEALING flag,
plus the *declaration order* inside `CardLibrary` (that is the list order).

Everything below is parsed from `javap` output — no hand-written numbers.

Usage:
    python tools/gen_card_pools.py [--check]

`--check` only reports whether the committed files are up to date.
"""
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
JAR = Path(r"C:/Users/22757/steam/steamapps/common/SlayTheSpire/desktop-1.0.jar")
JAVAP = Path(r"C:/Program Files/Java/jdk-24/bin/javap")
OUT_DIR = ROOT / "src/main/java/io/github/youqing/sts1solver/mirror"

INT_PUSH = re.compile(r"^\s*\d+: (iconst_m1|iconst_[0-5]|bipush\s+(-?\d+)|sipush\s+(-?\d+))")
ABSTRACT_CTOR = "AbstractCard.\"<init>\""


def javap(class_name):
    res = subprocess.run([str(JAVAP), "-p", "-c", "-classpath", str(JAR), class_name],
                         capture_output=True, text=True, encoding="utf-8", errors="replace")
    return res.stdout


def int_value(line):
    """Decode `iconst_* / bipush n / sipush n` into an int (None otherwise)."""
    m = INT_PUSH.match(line)
    if not m:
        return None
    if m.group(1) == "iconst_m1":
        return -1
    if m.group(1).startswith("iconst_"):
        return int(m.group(1)[-1])
    return int(m.group(2) or m.group(3))


def constructor_blocks(text):
    """Every constructor body in the class, in declaration order."""
    lines = text.splitlines()
    blocks, current = [], None
    for line in lines:
        if re.match(r"^  public [\w.$]+\([^)]*\);$", line):
            if current is not None:
                blocks.append(current)
            current = []
            continue
        if current is None:
            continue
        if re.match(r"^  (public|private|protected|static|final) ", line) and "Code:" not in line:
            blocks.append(current)
            current = None
            continue
        current.append(line)
    if current is not None:
        blocks.append(current)
    return blocks


def constructor_block(text):
    """The constructor body that actually carries the card data.

    Some cards (e.g. SearingBlow) delegate: `this(0)` in the no-arg constructor and
    the real arguments live in the `(int)` overload — so pick the block with a string
    literal instead of blindly taking the first one.
    """
    blocks = constructor_blocks(text)
    for block in blocks:
        if any("// String " in line for line in block):
            return block
    return blocks[0] if blocks else []


def parse_card(class_name):
    text = javap(class_name)
    block = constructor_block(text)

    card_id = None
    for line in block:
        m = re.search(r"// String (.+)$", line)
        if m:
            card_id = m.group(1).strip()
            break

    ctype = rarity = None
    for line in block:
        m = re.search(r"AbstractCard\$CardType\.([A-Z_]+)", line)
        if m and ctype is None:
            ctype = m.group(1)
        m = re.search(r"AbstractCard\$CardRarity\.([A-Z_]+)", line)
        if m and rarity is None:
            rarity = m.group(1)

    cost = None
    for idx, line in enumerate(block):
        if ABSTRACT_CTOR in line:
            for back in range(idx - 1, max(idx - 12, -1), -1):
                v = int_value(block[back])
                if v is not None:
                    cost = v
                    break
            break

    damage = None
    for idx, line in enumerate(block):
        if "baseDamage:I" in line and "putfield" in line:
            for back in range(idx - 1, max(idx - 6, -1), -1):
                v = int_value(block[back])
                if v is not None:
                    damage = v
                    break
            break

    healing = "CardTags.HEALING" in text
    return {
        "id": card_id,
        "type": ctype,
        "rarity": rarity,
        "cost": cost,
        "damage": -1 if damage is None else damage,
        "healing": healing,
    }


def library_order(color_dir):
    """Declaration order inside CardLibrary = the actual pool order."""
    text = javap("com.megacrit.cardcrawl.helpers.CardLibrary")
    names = []
    pattern = "cards/%s/" % color_dir
    for line in text.splitlines():
        for m in re.finditer(re.escape(pattern) + r"([A-Za-z0-9_]+)", line):
            name = m.group(1)
            if name not in names:
                names.append(name)
    return names


def java_str(s):
    return '"' + s.replace('"', '\\"') + '"'


def emit_class(class_name, rows, doc):
    body = ",\n".join(
        "            {%s, %d, %d, %s, %s}" % (
            java_str(r["id"]), r["cost"], r["damage"],
            java_str(r["type"] or "SKILL"), java_str(r["rarity"] or "COMMON"))
        for r in rows)
    return """package io.github.youqing.sts1solver.mirror;

/**
 * %s
 *
 * <p>由 {@code tools/gen_card_pools.py} 从 {@code desktop-1.0.jar} 的字节码生成，不要手改。
 */
public final class %s {

    /** {id, cost, damage, type, rarity} */
    public static final Object[][] CARDS = {
%s,
    };

    private %s() {
    }

    public static int size() {
        return CARDS.length;
    }

    public static String idAt(int i) {
        return (String) CARDS[i][0];
    }

    public static int costAt(int i) {
        return (Integer) CARDS[i][1];
    }

    public static int damageAt(int i) {
        return (Integer) CARDS[i][2];
    }

    public static String typeAt(int i) {
        return (String) CARDS[i][3];
    }

    public static String rarityAt(int i) {
        return (String) CARDS[i][4];
    }
}
""" % (doc, class_name, body, class_name)


def main():
    check = "--check" in sys.argv
    out = {}

    for color_dir, target in (("colorless", "ColorlessPool"), ("red", "RedPool")):
        order = library_order(color_dir)
        rows = []
        for name in order:
            data = parse_card("com.megacrit.cardcrawl.cards.%s.%s" % (color_dir, name))
            if not data["id"]:
                print("  !! cannot parse %s" % name)
                continue
            if data["cost"] is None:
                data["cost"] = -2
            if data["type"] is None:
                print("  !! no CardType for %s" % name)
            rows.append(data)

        pool = [r for r in rows if not r["healing"]]
        dropped = [r["id"] for r in rows if r["healing"]]
        print("%s: %d declared, %d in pool (dropped HEALING: %s)"
              % (target, len(rows), len(pool), ", ".join(dropped) or "-"))

        doc = ("%s —— 由字节码生成。\n *\n * <p>顺序 = {@code CardLibrary} 的声明顺序（= {@code srcXxxCardPool} 的真实排列），\n * 并剔除带 {@code CardTags.HEALING} 的牌 —— 随机取牌用的是\n * {@code list.get(rng.random(size - 1))}，顺序和长度错一个下标就全错。"
               % target)
        out[target] = emit_class(target, pool, doc)
        out[target + "_raw"] = rows

    if check:
        stale = []
        for target in ("ColorlessPool", "RedPool"):
            path = OUT_DIR / (target + ".java")
            if not path.exists() or path.read_text(encoding="utf-8") != out[target]:
                stale.append(target)
        print("stale: %s" % (", ".join(stale) or "none"))
        return 1 if stale else 0

    for target in ("ColorlessPool", "RedPool"):
        (OUT_DIR / (target + ".java")).write_text(out[target], encoding="utf-8")
        print("wrote %s.java (%d rows in pool)" % (target, len([r for r in out[target + "_raw"]
                                                                 if not r["healing"]])))
    return 0


if __name__ == "__main__":
    sys.exit(main())
