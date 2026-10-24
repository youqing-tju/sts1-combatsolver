#!/usr/bin/env python3
"""从 javap -c 的转储里抽某个方法的指令，便于逐条翻译怪物 AI。

用法（先在 PowerShell 里把类落盘）：

    $javap -p -c -classpath lib/desktop-1.0.jar com.megacrit.cardcrawl.monsters.exordium.Sentry |
        Out-File -Encoding utf8 $env:TEMP\\monsters\\Sentry.txt

    python tools/monster_bytecode.py $env:TEMP/monsters/Sentry.txt getMove takeTurn

只做一件事：按 javap 的缩进切方法体，把指定方法原样打出来（去掉行号表与局部变量收益）。
"""
import io
import re
import sys


def methods(path):
    """返回 {方法名: [指令行...]}。javap 的方法体缩进 4 空格，方法头缩进 2 空格。"""
    text = io.open(path, encoding="utf-8", errors="replace").read().splitlines()
    out = {}
    cur = None
    for line in text:
        m = re.match(r"^  (?:public|protected|private|static).*?([A-Za-z_$][\w$]*)\(", line)
        if m:
            cur = m.group(1)
            out.setdefault(cur, [])
            continue
        if cur is None:
            continue
        if line.startswith("  }"):
            cur = None
            continue
        out[cur].append(line)
    return out


NOISE = (
    "SFXAction", "VFXAction", "FastShakeAction", "ShockWave", "WaitAction", "TalkAction",
    "TextAboveCreatureAction", "AnimatedSlow", "addToBottom", "actionManager",
    "Color.", "float ", "Hitbox", "hb.", "Animation", "StSLib", "GameActionManager",
)


def main(argv):
    compact = False
    args = []
    for a in argv[1:]:
        if a == "-v":
            compact = True
        else:
            args.append(a)
    if len(args) < 2:
        print(__doc__)
        return 1
    found = methods(args[0])
    for name in args[1:]:
        body = found.get(name)
        if body is None:
            print("=== %s : 没有这个方法 ===" % name)
            continue
        print("=== %s (%d 行) ===" % (name, len(body)))
        for line in body:
            stripped = line.strip()
            if stripped.startswith("//") or not stripped:
                continue
            if compact and any(n in line for n in NOISE):
                continue
            print(line)
        print()
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
