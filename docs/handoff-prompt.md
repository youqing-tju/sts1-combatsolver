# STS1 战斗求解器 · 新对话开工提示词

> **用法**：把整份内容贴进新对话，第一条消息就给全上下文，避免反复补背景。
> **项目根**：`C:\Users\22757\Desktop\sts1-combatsolver`
> **当前状态**：2026-10-25 凌晨，测试 **313 条全绿**，
> HEAD = `feat(sim): 第二章六只（含 Confusion）` 那一笔

---

## 一、你是谁 / 这是什么

你是 STS1（杀戮尖塔）**进程内**战斗求解器（Java 8 ModTheSpire mod）的开发助手。
对标 STS2 的 `Torch1230/CombatSolver`（蓝本在 `reference/sts2-combatsolver`）。
本期只做**战斗层**（Ironclad，Act 1–4）。模型训练、地图/选牌/商店/事件/篝火均后置。

仓库根目录在 `C:\Users\22757\Desktop\sts1-combatsolver`。
`git` 历史 99 个 commit，分支 `main`。模块名与蓝本一一对应：
`capture / sim / mirror / search / eval / exec / ui / api / verify / diag / bootstrap`。

---

## 二、🔴 硬规则（违反 = 白干）

1. **第一要义：遇到问题先看 `reference/sts2-combatsolver` 怎么做，照着复刻。不自创机制。**
   顺序：① 在蓝本里找对应实现 → ② 理解它为什么这么写 → ③ 翻译到 STS1 的 API → ④ 才允许自己设计。
   找不到对应实现时，先说「蓝本没有这块」，再讨论怎么做。

2. **改代码前先报备**：先说「改哪里、为什么、副作用」，等用户说「可以」再动手。
   报备用通俗语言，不堆术语。（用户 2026-10-21 明确要求）

3. **不使用本机遗留资产**：不反编译/复用 `mods/SolverMod.jar`、已删除的 `sts-solver` / `sts-agent`。从零实现。

4. **所有游戏语义必须 javap 字节码核实**（`lib/desktop-1.0.jar`），不凭记忆/常识写。
   卡牌文本查 jar 内 `localization/eng/cards.json`（用 `unzip` 取出）。
   升级数值查 `UPGRADE_DESCRIPTION`，不凭记忆。

5. **未知语义显式拒绝**（抄蓝本）：宁可报 `ActionRejected` 不支持，不许静默算错。

6. **不自行量化/出结论**（用户 2026-10-23 两次强调）：工作方式是「对照表 → 按蓝本流水线顺序逐项搬 → 搬完改状态」，不做自己的 benchmark 结论、不自己定阈值。

7. **一次只搬一项**，搬完在 `docs/research/blueprint-parity.md` 把状态改成 ✅，并在 `docs/research/mechanics-reference.md` 记一条「照抄了哪几行、改了哪几处」。

8. `.ps1` 一律 **ASCII-only**（PowerShell 5.1 按 ANSI 读脚本，UTF-8 中文会解析失败）。

9. **构建 = Gradle 9.8.2**（`C:\Users\22757\.workbuddy\binaries\gradle\gradle-9.8.2`，隔离安装；wrapper 已入库）。
   `options.release = 8`，产物 class major 必须 = 52（游戏跑 JRE 8）。
   构建产物输出在 `~/.sts1-combatsolver/build`（工作区内有外部索引进程会锁文件）。

10. **依赖来源固定**：`desktop-1.0.jar` 来自游戏根目录；`ModTheSpire.jar` 来自创意工坊 `646570/1605060445/`。
    `tools/sync-libs.ps1` 同步到 `lib/`（不入库）。

11. **搜索跑后台线程**（影子状态是纯数据）；真机对象永不进后台。执行只在主线程。

12. 仓库先 private，M4 验收（全自动 20 场无异常）后再转 public + MIT。

---

## 三、当前进度（2026-10-26 · 怪物镜像 63/66，Act 1/2/4 全齐）

> 上一轮（10-23）：221 测试全绿、怪物 37 只。**本轮（10-26）把怪物推到 63 只、测试 379 条**。
>
> **10-26 傍晚追加**：药水 **25 → 31 张**（第三批：邪教徒/再生/速度/兴奋剂/蛇油/复制，
> 顺带更正「还债类」能力的原版 ID：`Flex`/`DexLoss`/`Regeneration`/`DuplicationPower`）；
> 遗物 **0 → 45 件**（计数类 6 已有 + 第二批纸青蛙/鸟居/水银沙漏/号角夹板/舵轮/山铜 +
> 能量侧冰淇淋/化学 X）。测试 **400 → 466 条**全绿。
>
> **收尾追加（10-26 中午）**：`SpireShield` / `SpireSpear`（Act 4 精英双子，§33.38）、
> `CorruptHeart`（Act 4 boss，§33.39）—— **Act 4 三只全齐**。新增基础设施：
> `Extra.blockFromDamage`（格挡=实际伤害）、`Extra.drawPileCards`（一次多张不同状态牌进抽牌堆）、
> `Extra.extraSelfBuff`（一招挂两个能力）、Beat of Death 出牌掉血钩子。
> ✅ **CorruptHeart 的 `Invincible` 已补**（每回合累计受伤上限 + `maxAmt` 反射捕获）。
>
> **本轮新增 22 只**：A 组召唤（`GremlinLeader`/`TorchHead`/`TheCollector`/`BronzeOrb`/`BronzeAutomaton`）、
> B 组多阶段（`Champ`/`Hexaghost`）、C 组面具强盗（`BanditBear`/`BanditLeader`）、
> D 组第三章（`OrbWalker`/`Maw`/`Transient`/`Repulsor`/`Spiker`/`SpireGrowth`/`Deca`/`Donu`/
> `Exploder`/`GiantHead`/`Nemesis`/`Darkling`/`SnakeDagger`）。
>
> **本轮新基础设施**：召唤（`MonsterMove.Summon`）+ `monsterHpRng` 捕获、
> 状态牌进抽牌堆（`Extra.statusToDraw`）、自毁（`Extra.selfDestruct`）、
> 复活（`Extra.reincarnate`）、缺失才补能力（`Extra.onlyIfAbsent`）、
> Fading 计时、Constricted 回合末伤害、**Slow**、**Malleable**、**Intangible 的 justApplied**、
> 半死/三只联动（Darkling）。
>
> **本轮修掉两个静默 bug**（详见 §四）：① 负增量被 `Math.max(0,…)` + `>0` 夹成 0（连带 Lagavulin 的
> Siphon Soul 一直是 +1）；② Intangible 没有 `justApplied`（幽魂形态少一回合、Nemesis 等于从不无实体）。
>
> 详细施工账：`docs/research/monster-mirror-plan.md`（含「剩余 7 只的精确卡点」）；机制细节：`mechanics-reference` §33.20–33.37。

## 三之二、当前进度（2026-10-23 晚，留档）

### 已完成（✅）

**镜像层（`src/main/java/io/github/youqing/sts1solver/mirror/`）**：
- `MirrorRegistry` —— 卡牌镜像注册，RED 75/75 + COLORLESS 38/38（token 牌已从分母剔除）
- `DamageCalc` —— 伤害管线（无实体封顶 1 / 飞行减半 / 荆棘反伤 / 烈焰屏障 / 蜷缩 / 愤怒 / 装甲 / 神器）
- `Effects` —— 效果原语（抽牌 / 弃牌 / 洗牌 / 发现 / 施加 debuff / 扣血）
- `DeathSaveRelic` —— 死亡豁免（蜥蜴尾巴 / 瓶中仙子 / 绽放之印，瓶中仙子优先）
- `PostCombatRelicHeal` —— 战后遗物回血（燃烧之血/黑血无条件回血进 `StrategicHpDeficit`）
- `Potions` —— **21 张药水**（火/格挡/能量/瓶中仙子 + 力量/敏捷/恐惧/爆炸/血药/古老/毒药/果汁/铁心/液态青铜 + 三选一 4 张 + 选牌类 3 张）
- `hooks/` —— 七阶段回合钩子引擎 + 10 个能力生效 + 6 个计数遗物

**搜索层（`src/main/java/io/github/youqing/sts1solver/search/`）**：
- `BeamSolver` —— beam search + 回合层结构（外层回合 / 内层出牌）
- `Score` —— 蓝本线性加权（`baseTotal()` 选路线 / `beamTotal()` 剪枝排序，量纲 1 血 = 3 点敌方血）
- `PlanAction` / `RouteStep` / `AsyncSearch` / `TranspositionTable`
- 剪枝排序 = 蓝本 `CompareBeamRankOrder` 前两键（rank 降 → actionCount 升）
- 最终路线排序 = 蓝本 `FinalPlanOrdering` 词典序（Won → Survives → HpDeficit → Turn → EnemyHp → Score → ActionCount）
- **固定前缀搜索**（`withFixedPrefix`）= 蓝本 `ApplyFixedPrefix`（`Phases.cs:2296-2400`）：
  前缀先回放、走完的节点当搜索根，打不出就整条作废（不换牌顶上）
- **0 费开局补搜**（`openingNoCostPrefixes` + `zeroCostOpeningRescue`）=
  蓝本 `RunZeroCostOpeningRescue`：主搜索答案是「第一步就结束回合」时，把免费牌的组合
  各重搜一轮（4 万节点 / 12 秒 / 最多 8 趟 / 不喝药），严格更好才换

**执行层**：
- `AutoPilot` = 蓝本 `ApplyCurrentTurn`（每回合重规划，不冻结路线）
- 面板 `SolverPanel` + 预算档位（低/中/高/极高：beamWidth 45/60/90/135、nodeLimit 6万/12万/25万/50万、time 60/120/180/300 秒）

**测试**：313 条全绿（28 个测试文件）。

**文档**（`docs/research/`）：
- `blueprint-parity.md` —— **蓝本复刻对照表**（蓝本 `src/Search/` 176 个文件逐项列 ✅/🔶/❌/⛔）
- `mechanics-reference.md` —— 25 节机制考证（每条标注证据等级 A/B/C）
- `coverage.md` —— 镜像覆盖率
- `observed-analysis.md` / `open-source-survey.md` —— 实测分析

### 里程碑状态

| 里程碑 | 状态 |
|---|---|
| M0 mod 骨架 | ✅ |
| M1 镜像核心 | ✅ |
| M2 搜索主体 | ✅ |
| M3.1 卡牌镜像 | ✅ |
| M3.2 怪物镜像（Act 1） | ✅ |
| M3.3 怪物镜像（Act 2-4） | ❌ |
| M3.4 药水镜像 | 🔶 21/50+ |
| M3.5 遗物镜像 | 🔶 **45 件**（计数 7 + 被动/回合 6 + 能量 3 + 受伤侧 5 + 金字塔 + 蛇眼 + 消耗/洗牌/击杀/回血/格挡/药水 6 + 姜/萝卜 + 鸟面罐） |
| M4-1 完整路线面板 | ❌ |
| M4-2 预算档位 | ✅ |
| M4-3 AUTO 执行 | ✅ |
| M4-5 稳定性 | ✅ |
| M4-6 局外收益 UI | ❌ |
| M5 验收 | ❌ |

---

## 四、🔴 反复踩过的坑（必须知道）

> **2026-10-26 新增两条**：
> 1. **减益量可以是负数**。原版 `ApplyPowerAction(amt)` 的 amt 就是**带符号增量**
>    （扣敏捷 = `-N`）。`MonsterMove` 构造器和 `CombatFlow` 的落地条件都不能用
>    `Math.max(0,…)` / `>0` 夹 —— 夹了之后减益变成「无操作」，而且不报错。
>    连带教训：Lagavulin 的 Siphon Soul 因此错了很久（写成 `+1`），因为 clamp 让它读出 0。
> 2. **查 power 有没有 hook，先 `javap -p` 列方法名**（不带 `-c`）。
>    只看 `javap -c` 的片段会漏掉像 `duringTurn` / `onAttacked` / `atEndOfTurn` 这种关键钩子
>    —— Exploder 的爆炸就在 `ExplosivePower.duringTurn` 里，上一轮因为只看了构造器就误判成「没有 hook」。

### 1. 「能力被 set 但不生效」是一类静默 bug
不报错、不崩溃，但估值算错。
**排查方法**：`grep 'powerOnPlayer("' src/main/java -r` 列出所有卡牌设的能力，
逐个查除 `MirrorRegistry`/`RedPool` 外有没有生效逻辑（`CombatFlow` / `PowerHooks` / `DamageCalc`）。
已修：`PoolCatalog` 丢 type、`Intangible`、`No Block`/`Berserk`/`Flame Barrier`、`Flex`/`Rupture`/`Evolve`/`Double Tap`。

### 2. 能力名必须用原版 ID
`SimState.capture()` 用 `pw.ID`（原版能力 ID）作 key。
Flex 的能力是 `GainStrengthPower`（ID = `"Gain Strength"`），**不是 `"Flex"`** —— 写错对拍必然 MISMATCH。

### 3. 能力挂载阶段很重要
`START_OF_TURN` 在 `CombatFlow` 的 `energy = energyPerTurn` **之前** → 加能量会被覆盖（Berserk 踩过）。
原版 `GainEnergyAction` 是 action 入队、回能后结算 → 挂 `START_OF_TURN_POST_DRAW`。

### 4. 药水 ID 格式不统一
多数带空格（`"Fire Potion"`），**三选一和瓶中仙子不带**（`"PowerPotion"` / `"FairyPotion"`）。
选牌类的也不带：`"GamblersBrew"` / `"ElixirPotion"` / `"LiquidMemories"`。

### 5. 抽牌从堆顶取
`draw.remove(size-1)`，不是 `remove(0)`。测试里放 STATUS 牌验证 Evolve 时要放**堆顶**，不是堆底。

### 6. 抽牌堆空了要洗回弃牌堆
`CombatFlow.draw` 空了调 `Effects.reshuffleDiscardIntoDraw`（消耗 1 次 `shuffleRng`）。
曾经因为「洗牌未建模」直接 return，导致 AUTO 第 3 回合起手牌永远空。

### 7. `PoolCatalog.randomCombatCard` 丢 type 的 bug
4 参构造没传 type → 所有随机生成的牌 type 为空 → 估值的牌型计数/攻击总量/牌库杂质全错。
**必须用 5 参构造** `new SimCard(id, false, cost, damage, chosen.type)`。

### 8. 升级态镜像缺失 = AI「像不会打仗」
`MirrorRegistry` 缺 `+` 键 → 真机查不到 → `ActionRejected` → 搜索分支静默跳过。
费用来自 `SimCard.effectiveCost()`，**不进镜像** —— 升级只改费用的牌，效果体可复用基础版。
`Limit Break+` 升级去掉 Exhaust，必须用 `register` 而非 `registerExhaust`。
已加 `MirrorCoverageTest` 闸门（牌池每张牌 × {基础,升级} 都要有镜像）和真机诊断 `MIRROR MISS: <牌名>`。

### 9. 计划只在本回合有效
回合翻页就丢弃重算（蓝本也是回合开始时重新规划）。
不要自己改成「冻结两回合」之类。

### 10. 死路之间不比优劣
`Survives` 必须用**投影血量**（`hp>0 && Score.projectedHpOf>0`），否则「走进必死」的节点被当成活着。
赤字记累计掉血，两条都必死时「早死」反而更前（蓝本同口径）。

### 11. Boss 分档只有「房间类型」能搬
🔴 `_isActEndingBoss` 来自 `BossHpRelief != None`（打完这场会回血，STS2 幕末回 80%），STS1 打完 Boss 不回血 → 永远 `None`。
估值侧的 boss 分支（持续成长 cap 32 / 虚弱省 2 血 / 力量削减 horizon 8）**在 STS1 永远走标准档，不该实现**。
✅ `reservedTurnLayers = RoomType.Boss ? 8 : 4` 用真实房间类型（`SimState.boss`，`capture()` 用 `getCurrRoom() instanceof MonsterRoomBoss`）。

### 12. `depth` 语义 = 最多几个回合层
蓝本回合层循环**没有上界** → 已改成安全上界 `SAFETY_MAX_TURN_LAYERS = 500`。
不要误解 `SetupValueHorizonTurns`（那是 UI 投影用途）。

### 13. 新卡接进来先跑 `python tools/audit_registry.py`
查 Exhaust 标记与费用作用域。

### 14. 固定前缀：打不出就整条作废，**不许**换一张牌顶上
蓝本注释写得很清楚（`Phases.cs:2415-2417`）：前缀代表「已经决定要这么打」，
一步不可达就整条路线作废，不能拿别的牌凑。我们的 `replayPrefixAction` 直接返回 null。
另外 `PlanAction` 带的是**手牌下标**（不是牌名），所以前缀只能在「同一个根状态上立刻回放」，
不能跨回合缓存 —— 手牌一变下标就错位。

---

## 五、下一步该做什么（按优先级）

> **严格按照 `docs/research/blueprint-parity.md` §4 的流水线顺序**。
> 一次只搬一项，搬完改状态、写文档、commit。

### 第一梯队（搜索结构，对照表 §4 顺序）

> ⚠️ 2026-10-23 深夜更新：第 1–4 项已到底。**第 4/5/6 项全都卡在同一件事上 —— 快照量没建模**
> （`PersistentBuffValue` / `ReachableHandValue` / `SearchRouteTraits` / `PersistentSetupTraits` /
> `FocusTargetCombatId` / `RelicCounters.SatisfiedMask` / `PotionStrategicCost`）。
> 也就是说：**下一步要么先补量，要么转第二梯队**，不该再硬搬搜索结构。

1. ~~`ApplyCurrentTurn` 的早停~~ → **不搬**（判据见 §四 与 `mechanics-reference` §22.4）。
2. ~~`Retention` 保留策略族批 1+2~~ → **已搬**（`mechanics-reference` §26）；
   批 3–6 卡在依赖量上。
3. ~~`Expansion.Opening` + `_earlyTurnScoutDepth`~~ → **拆开后只搬了可搬的一半**：
   0 费开局补搜已整条搬（`mechanics-reference` §27）；`SelectEarlyTurnFrontier`
   的 7 元组分桶缺 5 项 → ❌ 等补量；`OpeningActionRegistry` → ⛔ 本期无对应。
4. **补快照量**（新插入的前置任务）：`PersistentBuffValue` 一族先做成「快照上能读出的数」，
   补到能支撑 Pareto 支配 / 前沿分桶 / 承诺代表三处。
5. `NoveltySearch` / `BeamPortfolioSelector`（多轮搜索）—— 同样吃上面那批量。
6. **~~`ExpansionExecutor` / `ParallelExpansion` 并行展开~~** —— **结构已搬、默认关**
   （`mechanics-reference` §28）。修完指纹后复测：2/4/8 lane 全都比串行慢（层太小），
   默认 1。**「先修指纹并行就能用」的假设已被实测否掉** —— 别再凭感觉开。
7. **指纹提速已做**（`mechanics-reference` §29）：`SimState.fingerprint()` 2.24µs → 0.24µs，
   整趟搜索 **2.8×**（1507ms → 约 540ms）。验收判据是「等价类与老口径完全相同」
   （`SimStateFingerprintTest` 38 个状态两两对拍）—— 换算法可以，**漏字段不行**。
   ⚠️ 顺带查出：指纹里没有 `turn` / `hpLossCount` / `potionSlots`（§29.4），
   补不补是**语义变更**，等用户拍板。
8. 下一步候选：① 拍板 §29.4 的指纹口径缺口；② 第二梯队的机制镜像（药水 21→50+ 需要先有
   药水战略成本，否则搜索会白喝；Act 2-4 怪物镜像需要真机跑图采集意图）。

### 第二梯队（机制镜像补全）

6. **Act 2-4 怪物镜像**（M3.3）—— 逐遭遇差分，参照 Act 1 的做法。
7. **药水估值**（依赖 UI 额度，M4-6）+ 其余 30+ 张药水。
8. **局外收益**（`GrowthRewards` 逐来源计数 + `GrowthHpCredit` 轴 + UI）。
9. **被偷资源**（`Looter` 偷金币逃走 → `UnrecoveredLoot` / `TheftEncounterStrategy`，要建模逃跑时序）。
10. **HealingHpCredit 通道**（阈值回血折算成血量 credit，依赖 `RelicCounterEvaluation`）。

### 第三梯队（UI / 验收）

11. **M4-1 完整路线面板**（`AdoptRoute` = 冻结整条路线，属面板按钮，用户说过不急）。
12. **M5 验收**（全自动 20 场无异常 → 转 public + MIT）。

---

## 六、关键文件速查

| 文件 | 作用 |
|---|---|
| `src/main/java/io/github/youqing/sts1solver/search/BeamSolver.java` | beam search 主循环（回合层结构） |
| `src/main/java/io/github/youqing/sts1solver/search/Score.java` | 估值（`baseTotal` / `beamTotal` 两个总分） |
| `src/main/java/io/github/youqing/sts1solver/mirror/MirrorRegistry.java` | 卡牌镜像注册 |
| `src/main/java/io/github/youqing/sts1solver/mirror/Potions.java` | 药水镜像（21 张） |
| `src/main/java/io/github/youqing/sts1solver/mirror/DamageCalc.java` | 伤害管线 |
| `src/main/java/io/github/youqing/sts1solver/sim/CombatFlow.java` | 回合流程（`endTurn` / `hitPlayer` / `draw`） |
| `src/main/java/io/github/youqing/sts1solver/mirror/hooks/PowerHooks.java` | 能力钩子注册 |
| `docs/research/blueprint-parity.md` | **蓝本复刻对照表**（施工总纲） |
| `docs/research/mechanics-reference.md` | 25 节机制考证 |
| `tools/audit_registry.py` | 新卡接入检查 |

---

## 七、构建 / 测试命令

```bash
# 构建
powershell -File tools/build.ps1

# 测试（313 条）
powershell -File tools/gradle.ps1 test

# 离线实验（ScoreAbTest / moreSearchTimeHelps，默认 skip）
powershell -File tools/gradle.ps1 test -Pexperiments

# 新卡接入检查
python tools/audit_registry.py

# 镜像覆盖率
python tools/coverage_report.py
```

---

## 八、工作风格约定

- **改代码前先报备**：先说「改哪里、为什么、副作用」，等用户说「可以」再动手。
- **报备用通俗语言**，不堆术语。
- **一次只搬一项**，搬完 commit + 更新文档。
- **不自己出量化结论**，对照表说了算。
- **遇到不确定就查字节码**，不凭记忆。
- **用户会说「继续」**表示继续推进下一项；说「？」表示你上一条回复有问题。
- 回复中文，附 `file:line` 证据链。

---

## 九、🔴 待用户确认清单（2026-10-24 夜）

> 这些是「动手前需要拍板、我不该自己定」的问题。每条给了我的倾向，但**没做**。

| # | 问题 | 状态 |
|---|---|---|
| 1 | 指纹口径缺口 | ✅ **已补**（2026-10-24）：`turn` / `hpLossCount` / `potionSlots` 三个都进了指纹；判据改成单向「新口径只能更细，不能更粗」（`SimStateFingerprintTest`） |
| 2 | 药水战略成本 | ✅ **第一切片已做**（`mechanics-reference` §31）：三档成本 + 对账闸（喝药必须比不喝药基线多省 ≥ Σ成本）。⚠️ 档位映射只有 3 瓶有蓝本同形对应，其余 18 张按基准档，**映射表待你核对**；`SolverPotionPolicy` 档位与额度输入仍未做 |
| 3 | 怪物镜像（M3.3，全量重写） | 🔨 **已 63 / 66 只**（2026-10-26 中午更新，Act 4 全齐）。Act 1 **30 只全齐**；Act 2 全齐（含三只面具强盗）；Act 3 已做 `OrbWalker` / `Maw` / `Transient` / `Repulsor` / `Spiker` / `SpireGrowth` / `Deca` / `Donu` / `Exploder` / `GiantHead` / `Nemesis` / `Darkling` / `SnakeDagger` / `Reptomancer`。**剩余 6 只：`AwakenedOne`（两阶段，需要「换一只怪上场」）/ `TimeEater`（Time Warp 计数重排）/ `WrithingMass`（寄生牌 + 挨打即换招）/ `SpireShield` / `SpireSpear`（Act 4，`moveCount % 3` + 结伴增益）/ `CorruptHeart`（Act 4，四阶段）。另有两个**纯视觉类**不算：`HexaghostBody` / `HexaghostOrb`。框架支持：多段 / 格挡 / 队友格挡 / 自身增益 / 玩家（含**负增量**）减益 / 进阶数值 / 塞状态牌（弃牌堆 + **抽牌堆**）/ 召唤 / 分裂 / 逃逸 / 吸血 / Fading / Constricted 回合末伤害。施工表与每只的账：`docs/research/monster-mirror-plan.md`，机制细节：`mechanics-reference` §33 |
| 4 | `BeamPortfolioSelector` | ⏳ **待你拍板**：它不是规则代码，是**训练出来的决策树 JSON**（32 特征 + 程序集 GUID 校验）。搬运行器没意义（没有模型文件）；要就得先建自对弈数据 + 训练管线。倾向先记 ⛔ |
| 5 | 能力估值模型族（`PersistentBuffValue`） | ✅ **第一批已做**（`mechanics-reference` §32）：五维模型 + 7 个能力专门分支 + 默认档，已接进 Beam 排序。⚠️ 查证结果：`Combust` / `Metallicize` / `FireBreathing` / `Evolve` / `Brutality` 在蓝本**全库 0 命中**，只能走蓝本默认档。Retention 批 3/5 与前沿分桶仍缺其它维度 |
| 6 | `CyclePlanning` | 🔜 **你已说「可搬」** → 按批推进：批 1 = 同回合内的动作周期检测（`CycleShapeKey` + 周期 ≤32）；批 2 = 周期退出探测（probe 预算）；批 3 = 周期族（family）状态机 |
