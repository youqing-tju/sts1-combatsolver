#!/usr/bin/env python3
"""Mirror coverage report: registry vs the game's own card library.

Reads the registered keys straight out of MirrorRegistry.java (single source of
truth) and compares them with solver-data/cards.json, so "what is still missing"
never depends on my memory.

Usage:
    python tools/coverage_report.py [cards.json] [MirrorRegistry.java]
"""
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_CARDS = Path(r"C:/Users/22757/steam/steamapps/common/SlayTheSpire/solver-data/cards.json")
DEFAULT_REGISTRY = ROOT / "src/main/java/io/github/youqing/sts1solver/mirror/MirrorRegistry.java"
OUT = ROOT / "docs/research/coverage.md"

# 尚未覆盖的原因（按机制分类，写清楚是为了以后能一项项消掉）
REASONS = {
    "Dual Wield": "选择来自手牌，且要复制整张牌（需要复制卡牌对象）",
    "Exhume": "选择来自消耗堆（需要跨牌堆选择）",
    "Headbutt": "选择来自弃牌堆（需要跨牌堆选择）",
    "Havoc": "要把抽牌堆顶那张真的打出去（递归执行）",
    "Infernal Blade": "随机抽攻击牌，需要完整攻击牌池 + cardRng",
    "Rampage": "每次打出永久 +5，需要卡牌级持久状态",
    "Sentinel": "被消耗时回能，需要消耗触发器",
    "Searing Blow": "多次升级的成长曲线（当前只支持 0/1 次升级）",
    "Jack Of All Trades": "随机生成无色牌，需要无色牌池",
    "Metamorphosis": "随机生成攻击牌，需要攻击牌池",
    "Chrysalis": "随机生成能力牌，需要能力牌池",
    "Discovery": "三选一（需要选牌界面建模）",
    "Transmutation": "X 费 + 随机生成无色牌",
    "The Bomb": "延迟 3 回合后结算",
    "Apotheosis": "升级牌组里所有牌（含抽牌堆/弃牌堆）",
    "Enlightenment": "本回合手牌费用降为 1（需要费用修改器）",
    "Madness": "随机一张手牌费用归零",
    "Forethought": "把手牌放到底部（需要底部插入）",
    "Thinking Ahead": "抽 2 后放回牌堆顶（可做，待接线）",
    "Purity": "多选消耗（需要多选建模）",
    "Secret Technique": "从抽牌堆检索技能牌",
    "Secret Weapon": "从抽牌堆检索攻击牌",
    "Violence": "从抽牌堆检索 3 张攻击牌",
    "Deep Breath": "洗回弃牌堆（需要洗牌 RNG）",
    "HandOfGreed": "击杀获得金币（金币不在战斗模型内）",
    "RitualDagger": "击杀永久成长",
    "Expunger": "按条件重复出牌次数",
    "ThroughViolence": "卡牌文本待核实",
    "Safety": "卡牌文本待核实",
    "Mayhem": "每回合自动打出牌堆顶",
    "Magnetism": "每回合随机生成无色牌",
    "Panache": "每打出 5 张造成伤害（需要出牌计数器）",
    "Sadistic Nature": "施加减益时造成伤害（需要减益触发器）",
    "Omega": "回合结束造成伤害（需要回合末触发器）",
    "BecomeAlmighty": "事件牌（获得力量）",
    "Beta": "形态链中间态（转为 Omega）",
    "Ghostly": "形态链中间态（转为 Intangible）",
    "FameAndFortune": "事件牌（获得金币）",
    "LiveForever": "事件牌（最大生命）",
    "Blood for Blood": "费用随损血下降（需要动态费用）",
    "Dropkick": "",
    "Whirlwind": "",
}

SKIP_COLORS = {"CURSE", "STATUS"}


def registered_keys(path: Path):
    text = path.read_text(encoding="utf-8")
    keys = set()
    for m in re.finditer(r'register(?:Exhaust)?\("([^"]+)"', text):
        keys.add(m.group(1))
    return keys


def main():
    cards_path = Path(sys.argv[1]) if len(sys.argv) > 1 else DEFAULT_CARDS
    registry_path = Path(sys.argv[2]) if len(sys.argv) > 2 else DEFAULT_REGISTRY

    data = json.loads(cards_path.read_text(encoding="utf-8"))
    # cost == -2 是不可打出的状态/诅咒牌，不需要镜像
    cards = [c for c in data["cards"]
             if c["color"] not in SKIP_COLORS and c["cost"] != -2]
    keys = registered_keys(registry_path)

    rows = []
    for c in cards:
        base = c["id"]
        up = base + "+"
        rows.append((c["color"], base, base in keys, up in keys))

    total = len(rows)
    fully = sum(1 for r in rows if r[2] and r[3])
    partial = sum(1 for r in rows if r[2] != r[3])
    none = sum(1 for r in rows if not r[2] and not r[3])
    entries = len([r for r in rows for ok in (r[2], r[3]) if ok])

    lines = ["# 镜像覆盖报告（自动生成）", "",
             f"- 卡池：`{cards_path.name}`（排除 CURSE / STATUS）",
             f"- 注册表：`{registry_path.name}`",
             f"- 卡牌总数：**{total}**",
             f"- 完整覆盖（原版+升级）：**{fully}**　部分覆盖：{partial}　未覆盖：**{none}**",
             f"- 注册条目：**{entries}**", ""]

    for color in ("RED", "COLORLESS"):
        subset = [r for r in rows if r[0] == color]
        got = [r for r in subset if r[2] or r[3]]
        lines.append(f"## {color}：{len(got)}/{len(subset)}")
        lines.append("")
        missing = [r for r in subset if not r[2] and not r[3]]
        if not missing:
            lines.append("全部已覆盖。")
            lines.append("")
            continue
        lines.append("| 卡牌 | 未覆盖原因 |")
        lines.append("|---|---|")
        for _, name, _, _ in sorted(missing):
            lines.append(f"| {name} | {REASONS.get(name, '（待补原因/可补）')} |")
        lines.append("")

    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"wrote {OUT}")
    print(f"total={total} full={fully} partial={partial} none={none} entries={entries}")


if __name__ == "__main__":
    main()
