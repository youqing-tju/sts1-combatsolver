#!/usr/bin/env python3
"""Audit MirrorRegistry against the game's own card text.

Two classes of mistake that are invisible in gameplay until they bite:

1. `Exhaust` — a card whose text says *Exhaust* but is registered with `register`
   (goes to the discard pile instead of the exhaust pile). Found 5 real bugs on
   2026-10-23 (Feed / Reaper / Limit Break / Pummel / Intimidate+).
2. cost scope — a card whose text says *this turn* but whose effect writes the new
   cost into `SimCard.cost` (permanent) instead of `costForTurn`. Found the
   Infernal Blade bug that way.

Usage:
    python tools/audit_registry.py
"""
import json
import re
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
JAR = Path(r"C:/Users/22757/steam/steamapps/common/SlayTheSpire/desktop-1.0.jar")
REGISTRY = ROOT / "src/main/java/io/github/youqing/sts1solver/mirror/MirrorRegistry.java"
EFFECTS = ROOT / "src/main/java/io/github/youqing/sts1solver/mirror/Effects.java"

# 「Exhaust」出现在这些句式里只是效果描述，不代表这张牌自己消耗
EXHAUST_EFFECT_PATTERNS = [
    r"Exhaust 1 card", r"Exhaust all", r"Whenever .*Exhaust", r"If this card is Exhausted",
    r"and Exhaust it", r"Exhaust it\.", r"When you play a Skill, Exhaust",
]


def load_texts():
    with zipfile.ZipFile(JAR) as z:
        return json.loads(z.read("localization/eng/cards.json").decode("utf-8"))


def registered():
    text = REGISTRY.read_text(encoding="utf-8")
    plain, exhaust = set(), set()
    for m in re.finditer(r'^\s*(register\w*)\("([^"]+)"', text, re.M):
        (exhaust if "Exhaust" in m.group(1) else plain).add(m.group(2))
    return plain, exhaust


def description(texts, key):
    """升级态要用 UPGRADE_DESCRIPTION —— 有些牌升级后不再消耗（发现+ 就是）。"""
    base = key[:-1] if key.endswith("+") else key
    entry = texts.get(base) or {}
    if key.endswith("+"):
        return entry.get("UPGRADE_DESCRIPTION") or entry.get("DESCRIPTION") or ""
    return entry.get("DESCRIPTION") or ""


def main():
    texts = load_texts()
    plain, exhaust = registered()

    print("== Exhaust 标记 ==")
    bad = 0
    for key in sorted(plain):
        desc = description(texts, key)
        if "Exhaust" not in desc:
            continue
        if any(re.search(p, desc) for p in EXHAUST_EFFECT_PATTERNS):
            continue
        print("  ✗ %-24s 卡面写了 Exhaust，却注册成普通牌" % key)
        bad += 1
    print("  问题 %d 处" % bad)

    print()
    print("== 费用作用域（this turn vs this combat）==")
    effects = EFFECTS.read_text(encoding="utf-8")
    uses_turn = "withCostForTurn" in effects
    scope_bad = 0
    for key in sorted(plain | exhaust):
        desc = description(texts, key)
        if "costs 0 this turn" in desc or "to 1 this turn" in desc:
            if not uses_turn:
                print("  ✗ %-24s 卡面是「本回合」，但效果层没用 withCostForTurn" % key)
                scope_bad += 1
    print("  问题 %d 处" % scope_bad)

    return 1 if (bad or scope_bad) else 0


if __name__ == "__main__":
    sys.exit(main())
