"""Cross-check every power ID we use against the ones that actually exist in the game jar.

Why: an ID typo is silent. `powers.get("Enrage")` never throws -- it just returns null, and the
search then believes the mechanic does not exist. That is exactly how the Gremlin Nob kept
hitting us for 33 while the shadow predicted 14 (real ID is "Anger").

Usage:
    python tools/audit_power_ids.py            # report only
    python tools/audit_power_ids.py --verbose  # also list every key we use

Exit code 1 when an unknown key is found (so it can gate a build step).
"""

from __future__ import annotations

import os
import re
import subprocess
import sys
import zipfile

JAR = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                   "lib", "desktop-1.0.jar")
JAVAP = r"C:\Program Files\Java\jdk-24\bin\javap.exe"
SRC = "src/main/java"

# Markers we invent for our own bookkeeping. They have no vanilla counterpart on purpose.
SIM_ONLY = {"Half Dead"}

PATTERN = re.compile(
    r'(?:powers?\.(?:get|put|containsKey|remove)'
    r'|powerOnPlayer|powerOnPlayerExact|hasPower|getPower)\(\s*"([^"]+)"')


def javap(args: list[str]) -> str:
    try:
        return subprocess.check_output([JAVAP] + args, stderr=subprocess.DEVNULL
                                       ).decode("utf-8", "replace")
    except Exception:
        return ""


def vanilla_power_ids() -> set[str]:
    ids: set[str] = set()
    with zipfile.ZipFile(JAR) as z:
        names = [n for n in z.namelist()
                 if n.startswith("com/megacrit/cardcrawl/powers/")
                 and n.endswith(".class") and "$" not in n]
    for name in names:
        cls = name[:-6].replace("/", ".")
        consts = javap(["-p", "-constants", "-classpath", "lib/desktop-1.0.jar", cls])
        m = re.search(r'String POWER_ID = "(.*?)"', consts)
        if m:
            ids.add(m.group(1))
        body = javap(["-p", "-c", "-classpath", "lib/desktop-1.0.jar", cls])
        # `this.ID = "X"` appears as: ldc "X" ... putfield ID
        for hit in re.finditer(r'putfield\s+#\d+\s+// Field ID:Ljava/lang/String;', body):
            window = body[max(0, hit.start() - 200):hit.start()]
            found = list(re.finditer(r'// String (\S+)', window))
            if found:
                ids.add(found[-1].group(1))
    return ids


def used_keys() -> dict[str, set[str]]:
    used: dict[str, set[str]] = {}
    for root, _, files in os.walk(SRC):
        for f in files:
            if not f.endswith(".java"):
                continue
            path = os.path.join(root, f)
            with open(path, encoding="utf-8", errors="replace") as fh:
                text = fh.read()
            for m in PATTERN.finditer(text):
                used.setdefault(m.group(1), set()).add(path)
    return used


def main() -> int:
    ids = vanilla_power_ids()
    used = used_keys()
    unknown = {k: v for k, v in used.items() if k not in ids and k not in SIM_ONLY}
    print(f"vanilla power ids: {len(ids)}   keys used in code: {len(used)}")
    if "--verbose" in sys.argv:
        for k in sorted(used):
            print(f"  {k}")
    if not unknown:
        print("OK: every key exists in the jar (or is an explicit sim-only marker)")
        return 0
    for k in sorted(unknown):
        print(f"UNKNOWN {k}  <- {sorted(unknown[k])[:2]}")
    return 1


if __name__ == "__main__":
    sys.exit(main())
