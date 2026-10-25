# 怪物镜像施工表（M3.3 全量重写）

> 目标：把 65 只怪物（`monsters/exordium|city|beyond` 里的具体类）逐个翻译成 `sim/MonsterMoves` 的机器，
> 替掉「只学意图伤害」的 `IntentLearning`。
> 框架与第一只的细节见 `mechanics-reference` §33。原始扫描数据：`monster-mirror-scan.txt`。

## 一、已镜像

| 怪物 | 幕 | 备注 |
|---|---|---|
| `SphericGuardian` | 2 | 确定性循环；格挡 25/35、Frail 5、伤害 10/11（A2）都建了模 |
| `Cultist` | 1 | 首招 Ritual 3（A2 起 4），之后 6 伤 |
| `GremlinWarrior` | 1 | 4 伤（A2 起 5） |
| `GremlinFat` | 1 | 4 伤（A2 起 5）+ Weak 1（A17 起 2） |
| `SpikeSlime_S` | 1 | 5 伤（A2 起 6） |
| `Sentry` | 1 | 位次决定首招，之后 4/3 交替；塞 Dazed（新载荷 `statusCard`） |
| `GremlinTsundere` | 1 | 有队友给随机队友格挡，落单才打（新载荷 `blockRandomAlly`） |
| `JawWorm` | 1 | num 三档 + `randomBoolean(chance)`，一次决策可能消耗两次 aiRng |
| `AcidSlime_S` | 1 | **不用 getMove**：takeTurn 自带 SetMoveAction，1→2 死循环 |
| `SpikeSlime_M` | 1 | 攻击塞 Slimed，Lick 上 Frail |
| `FungiBeast` | 1 | 连打两下才 Grow（力量），否则 Bite |
| `SlaverBlue` | 1 | num>=40 刺，否则 Rake + Weak |
| `LouseNormal` | 1 | 4 号招 Grow（力量 3 / A17: 4） |
| `LouseDefensive` | 1 | 4 号招 Web（玩家 Weak 2） |
| `GremlinWizard` | 1 | 蓄力两次 → 大招 25（A2: 30）；A17 连轰 |
| `SlaverRed` | 1 | 首招必刺；num>=75 缠身（禁攻击牌，机制早就齐）；Scrape 带易伤 |
| `Lagavulin` | 1（精英） | 睡 3 回合→醒→砍两下→吸血；**挨打即醒（格挡全吸收不算）** |
| `GremlinNob` | 1（精英） | 先 Bellow 挂**激怒**（打技能牌 → 它 +力量）；Skull Bash / Rush 交替 |
| `AcidSlime_M` | 1 | 三档 num + 两处 `randomBoolean`；喷吐塞 Slimed |
| `AcidSlime_L` | 1 | 半血**分裂**成两只中酸液（小怪血 = 父体当时血量）；喷吐塞 2 张 Slimed |
| `SpikeSlime_L` | 1 | 半血分裂成两只中刺史莱姆；Lick = Frail 2（A17: 3） |
| `SlimeBoss` | 1（boss） | Goop（塞 Slimed 3/A19:5）→ Preparing → Slam 35/A4:38 循环；半血分裂成**一刺一酸**两只大史莱姆 |
| `TheGuardian` | 1（boss） | 攻/防双循环 + 伤害阈值切姿态；Sharp Hide = 打攻击牌就掉血 |
| `GremlinThief` | 1 | 查证后：**不偷钱也不逃**，就是每回合打 9（A2: 10） |
| `Looter` | 1 | 抹两次 → 掷硬币（烟雾弹/突袭）→ 烟雾弹 → **逃跑**（新载荷 escape） |
| `ApologySlime` | 1 | 掷硬币：打 3 / 玩家 Weak 1 |

**刻意不搬（缺机制，不是缺时间）**：`GremlinThief` / `Looter`（逃逸 + 偷金币 —— 没有离场模型）。

## 二、分类（用 `javap -c` 扫 `getMove` 得到，见 `monster-mirror-scan.txt`）

### A. `num` 不用、也不用 RNG（22 只，**可以直接逐只搬**）

`Deca` `Donu` `Exploder` `SnakeDagger` `Transient` `BanditBear` `BanditLeader`
`BanditPointy` `BronzeAutomaton` `TorchHead` `Cultist` `GremlinFat` `GremlinThief`
`GremlinTsundere` `GremlinWarrior` `GremlinWizard` `Hexaghost` `Lagavulin` `Sentry`
`SlimeBoss` `SpikeSlime_S` `TheGuardian`

⚠️ 但「不用 RNG」不等于「不用环境」：`Sentry` 的 `getMove` 用
`AbstractDungeon.getMonsters().monsters.lastIndexOf(this) % 2` —— 依赖**自己在怪物组里的位次**。
这类要在 `MonsterMoves` 的机器签名里多给一个「怪物下标」，别硬编码。

### B. `getMove(num)` 里用 `num`（43 只，需要先对齐 aiRng）

这些的 `rollMove()` 是 `getMove(aiRng.random(99))`，**每次决策消耗一次 aiRng**。
搬它们之前必须先决定一件事：

- **要不要让影子也消耗 aiRng？**
  消耗 → 与原版同分布，但我们别处（怪物内部随机、意图重掷）少消耗一次就整条序列错位；
  不消耗 → 确定、可复现，但和原版的后续随机不同源。
  现在的选择是**先不搬**，等这一批 A 跑完再单独做这一决策（属于「新问题」，见 §四）。

## 三、搬运流程（每只怪）

1. `javap -p -c` 看三处：构造器（血量/伤害常量）、`getMove`（分支）、`takeTurn`（伤害段数/格挡/减益）；
2. 在 `MonsterMoves` 里加一个 `Machine`，把分支逐条写成 if/else，**不许「差不多」**；
3. `MonsterMovesTest` 加 1-2 条：机器序列 + 一条接进 `CombatFlow.endTurn` 的落点断言；
4. 文档里记一行「字节码要点」，便于复查。

⚠️ `javap` 不在 PATH：`C:\Program Files\Java\jdk-24\bin\javap.exe`。

## 四、🔴 需要拍板的新问题

1. ~~aiRng 消耗口径~~ → **已定：复刻蓝本，消耗**（`MonsterMoves.next` 每次抽一次
   `aiRng.random(99)`，与 `AbstractMonster.rollMove` 同口径）。
2. ~~进阶档~~ → **已定：补**（机器读 `Context.ascension`）。

## 下一步：Hexaghost（第一章 boss）—— 两个真卡点

1. **`orbActiveCount` 是类字段**，`getMove` 用 `switch(orbActiveCount)` 分 7 档
   （0/1/2/3/4/5/6 → Sear / Tackle / Sear / Strengthen / Tackle / ? / ?）。
   它是「还剩几个火球亮着」，每回合 -1 —— **理论上可从历史反推**（`activated` 之后走了几招），
   但要先把 7 个档位全读准，且 `burnUpgraded`（火球烧掉后 Burn 是否升级）也会影响。
2. **Divider（5 号招）**：`damage[2].base = 玩家当前血量 / 12 + 1`，然后 6 段 ——
   这条好办（要看玩家血量，`SimState.hp` 就有）。
   真正难的是**半血后的 Divide 阶段**：它会分裂成 6 个 `HexaghostOrb` + `HexaghostBody`，
   而且这段时间 Hexaghost 本体不可攻击。要建模就得复用分裂管线 + 「无敌/不可选中」状态。

**结论：先不做半只**。半只 Hexaghost 在 50% 血以下会静默算错（搜索以为还能继续打本体），
这比不搬更糟。下一轮整块做：先读 `damage()` 重写（找 Divide 触发）与 6 个 orb 的构造参数。

## Hexaghost 七档已读清（下一轮直接写）

`getMove` 按 `orbActiveCount`（6 → 0，每回合 -1）分档：

| orbActiveCount | 招 |
|---|---|
| 6 | 6 = Inferno（3 段） |
| 5 | 4 = Sear |
| 4 | 2 = Tackle（fireTackleCount 段） |
| 3 | 3 = Strengthen（DEFEND_BUFF） |
| 2 | 4 = Sear |
| 1 | 2 = Tackle |
| 0 | 4 = Sear |

`takeTurn` 的 6 个分支（1..6）：5 = Activate/Divider（`damage[2] = 玩家当前血/12 + 1`，6 段）；
2 = Tackle；3 = Strengthen；4 = Sear；6 = Inferno；1 = ?（119 起的那个分支，疑似把 orbActiveCount 置回 6）。

**还差三块**才敢动手：
① 分支 1 到底干什么；② `damage()` 重写里 Divide 的触发与生成参数（6 个 `HexaghostOrb` + `HexaghostBody`）；
③ `burnUpgraded` 怎么翻（Sear 塞的 Burn 什么时候变 Burn+）。
