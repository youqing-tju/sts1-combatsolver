#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""从游戏导出的 solver-data/cards.json 生成 CardUpgradeTable.java。

为什么要有这个脚本：升级数值必须来自原版 `makeCopy() + upgrade()` 的**执行结果**，
不能手抄卡面文字（抄错一个字就是一条永久的对拍失配，而且看起来"差不多对"）。
CardExporter 已经在游戏里跑过这两个调用并把 base/up 都写进 cards.json，
这里只做机械转换 —— 不含任何游戏规则判断。

用法：
    python tools/gen_card_upgrades.py [cards.json 路径] [输出 java 路径]

默认分别指向游戏目录与本仓库的 mirror/CardUpgradeTable.java。
"""
import io
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
DEFAULT_IN = r"C:\Users\22757\steam\steamapps\common\SlayTheSpire\solver-data\cards.json"
DEFAULT_OUT = os.path.join(ROOT, "src", "main", "java", "io", "github", "youqing",
                           "sts1solver", "mirror", "CardUpgradeTable.java")

HEADER = """package io.github.youqing.sts1solver.mirror;

import java.util.HashMap;
import java.util.Map;

/**
 * 原版升级后的费用/伤害**机械数据**（由 {@code tools/gen_card_upgrades.py} 生成，请勿手改）。
 *
 * <p>数据来源：{@code capture/CardExporter.java:76-100} 在游戏里对每张卡执行
 * {@code makeCopy() + upgrade()}，把升级前后的 {@code cost} / {@code baseDamage} 一起写进
 * {@code solver-data/cards.json}。本表只是它的静态投影 —— 游戏里没有运行时读取，
 * 所以离线测试与真机用的是**同一份**数字。
 *
 * <p>⚠️ 只登记**有变化**的条目。{@code Rampage} 这类「升级只改 magicNumber、不动 baseDamage」
 * 的卡不在 DAMAGE 表里，正是要保住它的动态成长（用静态 up.dmg 覆盖会把已经打出来的
 * +5/N 次增长抹掉）。
 */
final class CardUpgradeTable {

    private CardUpgradeTable() {
    }

    /** {@code id -> {升级前费用, 升级后费用}}。 */
    private static final Map<String, int[]> COST = new HashMap<String, int[]>();
    /** {@code id -> {升级前 baseDamage, 升级后 baseDamage}}。 */
    private static final Map<String, int[]> DAMAGE = new HashMap<String, int[]>();

    static {
"""

FOOTER = """    }

    /** 升级后的费用；表里没有或未变化返回 {@code null}（调用方保留原值）。 */
    static int[] costChange(String id) {
        return COST.get(id);
    }

    /** 升级后的 baseDamage 变化；表里没有返回 {@code null}。 */
    static int[] damageChange(String id) {
        return DAMAGE.get(id);
    }

    static int costEntries() {
        return COST.size();
    }

    static int damageEntries() {
        return DAMAGE.size();
    }
}
"""


def main():
    src = sys.argv[1] if len(sys.argv) > 1 else DEFAULT_IN
    out = sys.argv[2] if len(sys.argv) > 2 else DEFAULT_OUT
    with io.open(src, encoding="utf-8") as f:
        doc = json.load(f)
    cards = doc["cards"]

    cost_rows = []
    dmg_rows = []
    for c in cards:
        up = c.get("up")
        if not up:
            continue
        cid = c["id"]
        if up["cost"] != c["cost"]:
            cost_rows.append((cid, c["cost"], up["cost"]))
        if up["dmg"] != c["dmg"]:
            dmg_rows.append((cid, c["dmg"], up["dmg"]))

    cost_rows.sort()
    dmg_rows.sort()

    body = []
    for cid, a, b in cost_rows:
        body.append('        COST.put("%s", new int[]{%d, %d});' % (cid, a, b))
    body.append("")
    for cid, a, b in dmg_rows:
        body.append('        DAMAGE.put("%s", new int[]{%d, %d});' % (cid, a, b))

    text = HEADER + "\n".join(body) + "\n" + FOOTER
    d = os.path.dirname(out)
    if d and not os.path.isdir(d):
        os.makedirs(d)
    with io.open(out, "w", encoding="utf-8", newline="\n") as f:
        f.write(text)
    print("cards=%d cost=%d damage=%d -> %s"
          % (len(cards), len(cost_rows), len(dmg_rows), out))


if __name__ == "__main__":
    main()
