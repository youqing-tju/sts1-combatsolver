# 2026-11-02 镜像精度批 · 交付汇报

> 面向：youqing（项目负责人）
> 日期：2026-11-02 23:00
> 代码状态：`master` / HEAD `8df0d70` / 未 push / 工作区干净
> 测试：**822 条全绿**（0 fail / 0 err / 18 skipped）
> 已安装：`build 1102-210032`（真机 20:38 加载的是 `142939`，**210032 尚未真机验证**）

---

## 一、一句话结论

今天做完了两件事：**把对拍从「会造假失配」改成「只在真正稳定时出结论」**，以及**按字节码修掉 13 个镜像缺口**。
失配率从上一轮的 46% 降到 **19.6%**，路线质量 A/B 出现 **7 条由负转胜、0 条由胜转负**。
但「爱不爱开能力」的根因**不在搜索量**——实测加宽 4 倍结果不变，问题指向打分/键序，这是下一轮的第一件事。

---

## 二、两批工作

### 第一批（14:00–14:45）：快照与稳定门（无真机）

| commit | 内容 |
|---|---|
| `e382207` | 致死出牌仍执行 BeatOfDeath |
| `60ec7df` | 重刃力量倍率入口补 PenNib/Boot |
| `f92b84e` | v5 快照全字段 + 严格/legacy 两档 parser + mismatch 回放 + 手牌保序 |
| `20eb7c0` | 稳定门纳入 `currentAction`，**删掉 90 帧到期强判** |
| `a9f78e9` | 六个模型缺口（Brutality / Rupture / 升级 / Madness / Fiend Fire / 10 张上限）+ 颈环清零 + 逆序弃牌 |
| `dc316e8` | 玩家侧 Vulnerable/Weakened/Frail 轮末递减 |
| `8ea4bd6` | 严格相邻回合 Continuation 配对探针 |
| `b375a2f` | 孢子云死亡联动 |
| `966db98` | 选择坐标按来源牌堆解析 |

### 第二批（20:38–21:48）：真机验证 + 二次修复

| commit | 内容 |
|---|---|
| `0d8126e` | baseline 捕获改用 `settled()`（含卡牌飞行）+ 账本 diff 收口 + Anger 复制真捕获 |
| `6a7ed38` | `ContinuationStamp` 不再比 `pendingMove` 载荷（真机侧重建它要掷 aiRng） |
| `8df0d70` | 宽度对照探针（回答「为什么算得这么快」） |

---

## 三、真机数据（20:38–20:54，build 142939）

```
对拍        138 次：111 MATCH / 27 MISMATCH（19.6%）
未决        22 次 DIFF WAIT（按新口径不出结论、不算失配）
后果        DEPLOY_REPLAN 25 次 —— 27 次失配里 25 次丢掉整条规划
续跑        SEARCH_ 41 条全是 MISS，SEARCH_REUSED = 0
```

失配归因：

| 字段 | 次数 | 根因 | 处置 |
|---|---|---|---|
| `hand.size` | 14 | baseline 在卡牌飞行中被捕获，快照少一张 | ✅ `0d8126e` |
| `angerCopiesGenerated` | 11 | `capture()` 从未读过它，恒 0 | ✅ `0d8126e` 实现捕获 |
| `rng.CARD` | 12 | 上面两条的连带 | ✅ 随根因消失 |
| `feedGrowth` | 2 | 原版无此计数器，capture 只能给 0 | ✅ 移出 diff（仍进排序） |

**样本实证**（`004-True_Grit+.json`）：`before.hand` 4 张 → sim 2 张、live 3 张（多 `Headbutt`）。
多出来的那张是上一张牌刚落地 —— baseline 比真正落位早了半拍。

---

## 四、13 个根因的证据状态

| 根因 | 原版证据（javap） | 自动化 | 真机 |
|---|---|---|---|
| 稳定门缺 `currentAction` | `getNextAction` 先 remove 再执行 | ✅ 4 条 | 已随 build 生效 |
| 90 帧到期强判 | 本地自创，已删 | ✅ 500 帧不出结论 | 已删除 |
| 致死仍触发 BeatOfDeath | `UseCardAction` 尾部无 hp 过滤 | ✅ | 待复测 |
| 重刃漏 PenNib | `HeavyBlade.use` 后走 `calculateCardDamage` | ✅ 3 条 | 待复测 |
| Brutality 只挂不生效 | `atStartOfTurnPostDraw` 抽 + 掉血 | ✅ 4 条 | 待复测 |
| Rupture 只在放血卡 | `wasHPLost` 任何掉血 | ✅ | 待复测 |
| 升级保留旧费用/伤害 | `CardExporter` 机械数据（42 改费/110 改伤） | ✅ | 待复测 |
| Madness 自造重抽上限 | `findAndModifyCard` 递归 | ✅ | 待复测 |
| Fiend Fire 抽牌污染池 | `addToBot` 抽牌在批后 | ✅ | 待复测 |
| 漏原生 10 张上限 | `MakeTempCardInHandAction.update` | ✅ | 待复测 |
| 玩家减益永不递减 | `atEndOfRound` + `justApplied` | ✅ 5 条 | 待复测 |
| 孢子云死亡联动 | `SporeCloudPower.onDeath` | ✅ 3 条 | 待复测 |
| 选择坐标只查手牌 | True Grit+/Headbutt/Exhume 来源牌堆 | ✅ 7 条 | 待复测 |

---

## 五、三个必须记住的负结果

**1. 旧证据全部不能当 oracle。**
221 份 mismatch dump 与 188 份 turn 快照**全部**缺 v5 字段，`MismatchReplayTest` 报 `replay=0 incomplete_schema=221`，
`ContinuationPairProbeTest` 报 `total=0 incomplete_schema=188`。这不是失败，是「这批证据不足以下结论」。

**2. 续跑命中率 0 不是镜像不准，是戳本身不可比。**
`pendingMove` 载荷只有 sim 侧有，真机侧只有一个字节 —— 戳永远不等。
修掉之后 `SEARCH_REUSED` 才有机会非 0（`6a7ed38`，戳版本升 `v6`）。

**3. 「算得快」不是「搜得糙」。**
同一局面（手牌含 `Corruption` + `Dark Embrace+`）宽度 135/270/540 对照：

| 宽度 | 节点 | 耗时 | 第一手 | 结束血量 | 分数 |
|---|---|---|---|---|---|
| 135 | 24,805 | 1,416 ms | Dark Embrace+ | 65 | 10001866000 |
| 270 | 31,043 | 1,503 ms | Dark Embrace+ | 65 | 10001866000 |
| 540 | 58,536 | 2,228 ms | Dark Embrace+ | 65 | 10001866000 |

**完全一致**。所以「不爱开能力」如果成立，根因在**打分函数或最终排序键**，不在搜索宽度。

---

## 六、路线质量 A/B（188 份旧快照）

基线 `a2d82b3` vs 本批：

```
胜负        175 仍胜 / 7 负→胜 / 0 胜→负 / 6 仍负
收尾血量    总计 -1（均值 -0.01）  更好/持平/更差 = 24/140/24
路线        首手变 36、整条变 71、动作 +55、用药 -2
```

7 条翻盘最可能来自「易伤终于会递减」—— 以前搜索把后续每一击都按 1.5 倍估，直接判死。
24 条略差是同一批数值修正的副作用，总血量几乎不动，暂不逐条归因。

---

## 七、未闭环与风险

| 项 | 状态 | 说明 |
|---|---|---|
| 最新 build 真机复测 | ❌ | `210032` 已装但游戏加载的是 `142939`，需要完全退出游戏再启动 |
| `SpireShield` 死亡清理 | ❌ | `Surrounded`/`BackAttack` 我们没有建模，目前无对应物 |
| 跨卡 flush 基线锚点（原 Task 5D） | ❌ | 需要更早的钩子点 |
| `a9f78e9` 合并提交 | ⚠️ | 6 个根因同处 `Effects/CombatFlow`，拆 hunk 会留下不能编译的中间态，已在 message 逐条列明 |
| 并行构建踩踏 | ⚠️ | 多 agent 共用 `~/.sts1-combatsolver/build` 会互相破坏，必须各自 `local.properties` |

回退：`git revert 8df0d70 6a7ed38 0d8126e e0de94f 966db98 b375a2f c4e54b6 8ea4bd6 dc316e8 a9f78e9 20eb7c0 f92b84e 60ec7df e382207` → 回到 `4172fd1`。

---

## 八、下一轮第一件事（按优先级）

1. **完全退出游戏再启动**，确认日志里 `build 1102-210032`，打一场 5 回合以上的战斗。
2. 跑两条探针，拿到**第一次真实**失配率与命中率：
   ```bash
   ./gradlew test --offline -Pexperiments --tests "*MismatchReplayTest" -Dcase=<新 dump 目录>
   ./gradlew test --offline -Pexperiments --tests "*ContinuationPairProbeTest" -DbatchPair=<新快照目录>
   ```
   `incomplete_schema` 必须为 0；`SEARCH_REUSED` 应开始出现非零。
3. **查「不爱开能力」**：用 `BeamWidthProbeTest` + 候选池打分，定位是估值错还是键序错。
   这是目前最可能影响「牌打得臭」的一项。

---

## 九、交付物

- 代码：12 个 commit（`e382207` … `8df0d70`）
- 测试：`822` 条；新增 13 个测试类，其中 6 个是「反向合同」（钉住旧错误行为，防止回潮）
- 工具：`tools/gen_card_upgrades.py`（从游戏导出的机械数据生成升级表）
- 探针：`MismatchReplayTest`、`ContinuationPairProbeTest`、`BeamWidthProbeTest`、`SnapshotReplayContractTest`
- 证据：`runs/2026-11-02-mirror-fidelity/`（冻结的日志、221 份 dump、188 份快照、A/B 两张 TSV、配对 TSV）
- 文档：本汇报 + `docs/handoff-prompt.md` §十四 + `记录.txt` 99–107
