#!/usr/bin/env python3
"""Paired A/B comparison of two snapshot-probe TSVs.

Both files must come from ``SnapshotBatchProbeTest -Dout=...`` with the same
``FORMAT`` header, the same snapshot directory and the same search config; then the
only difference is the implementation under test.

Usage::

    python tools/ab_route_quality.py <base.tsv> <new.tsv>

The report is descriptive on purpose. ``turn-*.json`` files are consecutive turns of
a handful of fights, so they are *not* independent samples: this tool reports paired
deltas and direction counts, never a significance claim.
"""
import sys
from collections import Counter


def load(path):
    lines = open(path, encoding="utf-8").read().splitlines()
    if not lines or not lines[0].startswith("format\t"):
        raise SystemExit(f"{path}: missing format header")
    fmt = lines[0].split("\t", 1)[1]
    header = lines[1].split("\t")
    rows = {}
    for line in lines[2:]:
        if not line.strip():
            continue
        cells = line.split("\t")
        row = dict(zip(header, cells))
        rows[row["snap"]] = row
    return fmt, rows


def main():
    if len(sys.argv) != 3:
        raise SystemExit(__doc__)
    base_fmt, base = load(sys.argv[1])
    new_fmt, new = load(sys.argv[2])
    if base_fmt != new_fmt:
        raise SystemExit(f"format mismatch: {base_fmt} vs {new_fmt} (not comparable)")

    shared = sorted(set(base) & set(new))
    only_base = sorted(set(base) - set(new))
    only_new = sorted(set(new) - set(base))

    print(f"format={base_fmt} snapshots: base={len(base)} new={len(new)} paired={len(shared)}")
    if only_base:
        print(f"only in base ({len(only_base)}): {only_base[:5]}")
    if only_new:
        print(f"only in new ({len(only_new)}): {only_new[:5]}")

    wins = Counter()
    hp_delta = 0
    hp_better = hp_worse = hp_same = 0
    first_changed = route_changed = 0
    stop_changed = Counter()
    actions_delta = 0
    potion_delta = 0
    worse = []

    for snap in shared:
        b, n = base[snap], new[snap]
        bw, nw = b["win"] == "1", n["win"] == "1"
        wins[(bw, nw)] += 1
        d = int(n["hp"]) - int(b["hp"])
        hp_delta += d
        if d > 0:
            hp_better += 1
        elif d < 0:
            hp_worse += 1
            worse.append((snap, d, b, n))
        else:
            hp_same += 1
        if b["first"] != n["first"]:
            first_changed += 1
        if b["route"] != n["route"]:
            route_changed += 1
        if b["stop"] != n["stop"]:
            stop_changed[(b["stop"], n["stop"])] += 1
        actions_delta += int(n["actions"]) - int(b["actions"])
        potion_delta += int(n["potions"]) - int(b["potions"])

    print("\n-- outcome --")
    print(f"  still won      : {wins[(True, True)]}")
    print(f"  lost -> won    : {wins[(False, True)]}")
    print(f"  won -> lost    : {wins[(True, False)]}")
    print(f"  still lost     : {wins[(False, False)]}")

    print("\n-- end HP (simulated; higher is better) --")
    print(f"  total delta    : {hp_delta:+d} over {len(shared)} paired snapshots")
    if shared:
        print(f"  mean delta     : {hp_delta / len(shared):+.2f}")
    print(f"  better/same/worse: {hp_better}/{hp_same}/{hp_worse}")

    print("\n-- route shape --")
    print(f"  first action changed: {first_changed}")
    print(f"  full route changed  : {route_changed}")
    print(f"  actions delta       : {actions_delta:+d}")
    print(f"  potions used delta  : {potion_delta:+d}")
    if stop_changed:
        print(f"  stop reason changes : {dict(stop_changed)}")

    if worse:
        print(f"\n-- worst HP regressions (top 10 of {len(worse)}) --")
        for snap, d, b, n in sorted(worse, key=lambda item: item[1])[:10]:
            print(f"  {snap}: {b['hp']} -> {n['hp']} ({d:+d})"
                  f"  first {b['first']} -> {n['first']}"
                  f"  actions {b['actions']} -> {n['actions']}")


if __name__ == "__main__":
    main()
