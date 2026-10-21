#!/usr/bin/env python3
"""Summarise solver-data/observed.jsonl into a per-card table.

Why: the recorder writes one line per unmirrored card play. Those lines are the
"information set" we plan to turn into mirrors, but raw deltas are too noisy to
read directly (hand-order noise dominates). This script keeps only the fields
that actually describe a card's effect and aggregates them.

Usage:
    python tools/analyze_observed.py [jsonl] [--out report.md]
"""
import json
import re
import sys
from collections import Counter, defaultdict
from pathlib import Path

FIELD = re.compile(r"^(?P<key>[a-zA-Z0-9_.\[\]]+): (?P<before>.*) vs (?P<after>.*)$")

# 只看能表达"这张牌做了什么"的字段，手牌顺序/弃牌堆下标之类的噪声全部丢掉
INTERESTING = (
    "monsters[",
    "block",
    "playerPowers.",
    "monsters[0].powers.",
    "hp:",
    "energy:",
    "exhaust.size",
)


def parse_delta(text):
    m = FIELD.match(text)
    if not m:
        return None
    return m.group("key"), m.group("before"), m.group("after")


def as_int(value):
    try:
        return int(value)
    except (TypeError, ValueError):
        return None


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    src = Path(args[0]) if args else Path("solver-data/observed.jsonl")
    out = Path("docs/research/observed-analysis.md")

    if not src.exists():
        print(f"missing input: {src}")
        return 1

    per_card = defaultdict(lambda: {"n": 0, "dmg": Counter(), "block": Counter(),
                                    "powers": Counter(), "energy": Counter()})
    total = 0

    for line in src.read_text(encoding="utf-8", errors="replace").splitlines():
        line = line.strip()
        if not line:
            continue
        try:
            rec = json.loads(line)
        except json.JSONDecodeError:
            continue
        card = rec.get("card")
        if not card:
            continue
        total += 1
        entry = per_card[card]
        entry["n"] += 1

        for raw in rec.get("delta", []):
            if not any(k in raw for k in INTERESTING):
                continue
            parsed = parse_delta(raw)
            if not parsed:
                continue
            key, before, after = parsed

            if key.startswith("monsters[") and key.endswith(".hp"):
                b, a = as_int(before), as_int(after)
                if b is not None and a is not None and b > a:
                    entry["dmg"][b - a] += 1
            elif key == "block":
                b, a = as_int(before), as_int(after)
                if b is not None and a is not None and a > b:
                    entry["block"][a - b] += 1
            elif key.startswith("playerPowers.") or ".powers." in key:
                name = key.split(".")[-1]
                a = as_int(after)
                if a is not None and a > 0:
                    entry["powers"][name] += 1
            elif key == "energy":
                b, a = as_int(before), as_int(after)
                if b is not None and a is not None:
                    entry["energy"][b - a] += 1

    lines = ["# 观察样本汇总（自动生成）", "",
             f"- 输入：`{src}`",
             f"- 记录条数：**{total}**",
             f"- 不同卡牌：**{len(per_card)}**", "",
             "> 数据来自 AUTO 打未镜像牌时记录的前后状态差；手牌顺序等噪声已过滤。",
             "> 用途：写镜像时的第一手期望值，以及判断某张牌的效果是否单一。", "",
             "| 卡牌 | 次数 | 观测伤害 | 观测格挡 | 观测状态 | 能量变化 |",
             "|---|---|---|---|---|---|"]
    for card, e in sorted(per_card.items(), key=lambda kv: -kv[1]["n"]):
        dmg = ", ".join(f"{k}×{v}" for k, v in e["dmg"].most_common(3)) or "—"
        blk = ", ".join(f"{k}×{v}" for k, v in e["block"].most_common(2)) or "—"
        pw = ", ".join(f"{k}×{v}" for k, v in e["powers"].most_common(3)) or "—"
        en = ", ".join(f"{k}×{v}" for k, v in e["energy"].most_common(2)) or "—"
        lines.append(f"| {card} | {e['n']} | {dmg} | {blk} | {pw} | {en} |")

    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"wrote {out} ({len(per_card)} cards, {total} records)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
