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
| `Byrd` | 2 | 飞/落地状态机：Flight 每段掉 1 层、掉光 STUN；飞着伤害减半 |
| `Chosen` | 2 | Hex（打非攻击牌塞 Dazed）+ 削志（Weak 3 / 自身 +3 力量） |
| `ShelledParasite` | 2 | 吸血重击；Plated Armor 破甲即硬直 |
| `Centurion` | 2 | 有队友就加格挡、落单才怒击（看场上活怪数） |
| `Healer` | 2 | 看全队缺损血量决定治疗；否则全队 +力量 / 攻击 |
| `Snecko` | 2 | 首招挂 **Confusion**（每抽一张牌随机改费用） |
| `BookOfStabbing` | 2（精英） | 多刺段数随历史增长；自带 **Painful Stabs**（每段塞 1 Wound） |
| `Mugger` | 2 | 抹两次 → 掷硬币 → 烟雾弹 → 逃跑（与 Looter 同构） |
| `Taskmaster` | 2 | 永远那一鞭：7 伤 + 2 张 Wound（A18: 3 张 + 自身 +1 力量） |
| `BanditPointy` | 2 | 永远两段 5（A2: 6） |
| `BanditBear` | 2（事件） | `getMove` 只给开局那一招（2 Bear Stun），之后全靠 takeTurn 的 SetMove 推：2→3(Slash 伤+9 格挡)→1(Maul)→3→… |
| `BanditLeader` | 2（事件） | 同上：2 喊话（不打人）→ 3 Agonize（伤 + Weak 2/A17: 3）→ 1 Slash；A17 起可连刺两下（`lastTwoMoves(1)`） |
| `GremlinLeader` | 2（精英） | Rally = 「现造一只随机小鬼」填 3 个槽（不是预置）；喊话给全队力量、队友才加格挡；`getMove` 会**递归**再抽 aiRng |
| `TorchHead` | 2 | TheCollector 的召唤物：永远 7 点，HP 走 `monsterHpRng` |
| `TheCollector` | 2（boss） | 开场召 2 只火把头、第 4 回合大招（Weak+Vulnerable+Frail）、有火把头死掉才补 |
| `BronzeOrb` | 2 | Stasis 只放一次；Flux 给 **BronzeAutomaton** 加 12 格挡；两下一循环 |
| `BronzeAutomaton` | 2（boss） | 4→1→5→1→5→2→3 固定循环（`numTurns` 只能从历史重放）；BOOST 召 2 个铜球 |
| `Champ` | 2（boss） | 三阶段：半血狂暴（自 +3×力量并清自身减益）、第 4 回合 Taunt（**不打人**）、半血后连打两下重斩；姿态最多两次、门槛 `num≤15`（A19: 30）。`numTurns/threshold/forgeTimes` 全部从 `moveHistory` 重放 |
| `OrbWalker` | 3 | 两招互斥（连打两次就换）；带 Burn 的那招 10\|11，另一招 15\|16。原版字段名反着存（`damage[0]` 叫 laserDmg 却给 Claw 用） |
| `Maw` | 3 | 第 1 招必 Roar（Weak+Frail 3，A17 各 5）；之后 Nom（5 伤 × turnCount/2 段）/ Bellow（+力量）/ Slam（25\|30） |
| `Transient` | 3 | 一招到底：30\|40 起、每回合 +10；**Fading 5\|6** 到点当场消散（CombatFlow 统一递减） |
| `SpireGrowth` | 3 | 没缠绕就先挂 Constricted 10\|12（A17 起前置条件多一条）；之后快拍 16\|18 / 重砸 22\|25 交替，不连砸 |
| `Spiker` | 3 | 开场自带 Thorns 3\|4（真机快照已有）；`num<50` 且没连打就刺 7\|9，否则 Sharpen +2 荆棘，磨满 6 次后只刺 |
| `Repulsor` | 3 | 80% 往**抽牌堆**塞 2 张 Dazed，20% 打 11\|13；不会连着打两下 |
| `Hexaghost` | 1（boss） | **没有 damage()/die()、6 个 orb 是纯视觉**。真正的状态是 `orbActiveCount`，由三个 `changeState` 改 → 整场是确定性 7 招循环：Activate → Divider(6×[玩家血/12+1]) → Sear→Tackle→Sear→Strengthen→Sear→Tackle→Sear→Inferno→循环 |

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

**（已收口，见下面「B 组落地记录（续）」）** 当时以为还差三块才敢动手：
① 分支 1 到底干什么；② `damage()` 重写里 Divide 的触发与生成参数（6 个 `HexaghostOrb` + `HexaghostBody`）；
③ `burnUpgraded` 怎么翻（Sear 塞的 Burn 什么时候变 Burn+）。

## 🔴 Hexaghost 的假设被推翻：它**没有** damage()/die() 重写

上一轮我写「半血后 Divide 会分裂成 6 个 orb」——读完发现 `Hexaghost` 类里
**既没有 `damage()` 也没有 `die()` 重写**，`update()` 只是转发给 `HexaghostBody/orb` 的 update。
6 个 orb 是 `usePreBattleAction → createOrbs()` **开场就造好的**（不是半血才分裂）。

所以「phase 2」这件事在我脑子里的模型是错的，得重新查：
- orb 是什么（是不是独立可击杀单位、有没有血）；
- `changeState` 里 `orbActiveCount = 6 / += 1 / = 0` 三处分别属于哪个状态；
- `burnUpgraded` 在哪翻。

~~未查清之前不写 Hexaghost~~ —— 2026-10-26 已查清并落地，见下面「B 组落地记录（续）」；Act 1 **30 只全齐**。

## 剩余 28 只的施工顺序（2026-10-25 夜）

已完成 **37 只**（Act 1 除 Hexaghost 外全部 + Act 2 的 11 只）。剩下这些按「需要什么新能力」分组：

| 组 | 怪物 | 缺什么 |
|---|---|---|
| ~~**A. 召唤**~~ ✅ 已完成（2026-10-26） | ~~`GremlinLeader` / `TheCollector` / `BronzeAutomaton`~~ | 见下面「A 组落地记录」——小鬼血量其实是 `monsterHpRng`（seed 控制），补捕获后**精确** |
| ~~**B. 多阶段**~~ ✅ 已完成（2026-10-26） | ~~`Champ` / `Hexaghost`~~（`BronzeAutomaton`、`TheCollector` 在 A 组顺带做完） | 结论：**一只都不需要通用钩子** —— 全都是在 `getMove` 里读普通字段。详见下面「B 组落地记录」 |
| ~~**C. 面具强盗事件**~~ ✅ 已完成（2026-10-26） | ~~`BanditBear` / `BanditLeader`~~（`BanditPointy` 更早已做） | 两只都是「getMove 只给开局那一招」，其余全在 takeTurn 里 SetMove —— 从历史重放即可 |
| **D. 第三章**（beyond 包，18 只） | `Darkling`/`OrbWalker`/`Spiker`/`Repulsor`/`SpireGrowth`/`Transient`/`WrithingMass`/`Nemesis`/`GiantHead`/`TimeEater`/`AwakenedOne`/`Deca`/`Donu`/`SpireSpear`/`SpireShield`/`Maw`/`JawWorm`? | 未读；先按 A/B 两组的新基础设施铺完再批量做 |

⚠️ `GremlinLeader` 比另外两只多一层：构造器里**预先造好 3 只小鬼**（`gremlins[3]` 字段，
从 5 种里随机挑），Rally 只是把「还没上场的」放出来。所以它不只是「召唤」，还要表达
「这场战斗预置了哪几只、放到第几只」—— 建议和召唤载荷一起设计，别分开做。

**下一步建议**：先做 A 组的召唤载荷（一次解锁 GremlinLeader + Collector + Automaton 三只 boss/精英），
再做 B 组的「血量阈值换招」钩子（同样一次解锁多只）。

---

## A 组落地记录（2026-10-26，已完成）

### 三处认知被字节码推翻

1. **「GremlinLeader 构造器预置 3 只小鬼」是错的**。`usePreBattleAction` 只是把**开场就站着**的
   两只登记进 `gremlins[0]`/`gremlins[1]`（`monsters.get(0)` / `get(1)`），`gremlins[2] = null`。
   真正召唤时 `SummonGremlinAction.identifySlot` 找**第一个 null 或 isDying 的槽**，
   `getRandomGremlin` 再现造一只。所以语义是「3 个槽 + 每次现造」，不是「预置队列」。
2. **池子是 8 项且带重复**：`Warrior×2 / Thief×2 / Fat×2 / Tsundere×1 / Wizard×1`，
   `aiRng.random(0,7)` 抽 —— 抽到战士是 1/4，不是 1/5。
3. **小鬼血量不是 `MathUtils.random`，是 `monsterHpRng`**（`setHp(min,max)` 内部就是它）。
   所以只要捕获这条流，召唤出来的血是**精确值**，之前担心的「只能取中值」不成立。
   → 已在 `SimState.RngKind` 补 `MONSTER_HP`（`AbstractDungeon.monsterHpRng`）。

### 随机消耗次序（照抄才不错位）

- GremlinLeader 的 Rally 在 `takeTurn` 里连排**两个** `SummonGremlinAction`：
  两个**构造器**在入队时就把「类型 + 血」抽掉（类型 `aiRng.random(0,7)`、血 `monsterHpRng.random`），
  `update()` 之后才对每只调 `init()`（`aiRng.random(99)`）。
  所以次序是 **类型1/血1 → 类型2/血2 → 招1 → 招2**，不是「一只一只走完」。
- 所有召唤（`SpawnMonsterAction` / `SummonGremlinAction`）都会 `monster.init()`，各消耗一次 aiRng。
- GremlinLeader 的 `getMove` 在「只剩 1 只小鬼」档会**递归调自己**，并且再抽的是
  `aiRng.random(50,99)` / `aiRng.random(0,80)`（**区间不同**，`nextInt` 消耗的内部位也可能不同）。
  `random(0,80)` 的结果可能又 `< 50`，于是**会递归多次** —— 用循环照抄，不做深度截断。

### 载荷

`MonsterMove.Extra`（新）：`summon`（`Summon{pool[Spawn{type,hpMin,hpMax}], count, cap, minion}`）
+ `blockAllyType`（给指定类型的队友加格挡）+ `blockAllies`（给其他队友加格挡）+ `playerDebuff3`（第三个减益）。
把新机制收进 `Extra` 是为了让「权威构造器」的参数定死，不再每加一个机制就多一层重载。

`cap` 统一表达三种原版写法：`identifySlot` 的 3 槽、`enemySlots` 的 2 槽、`SpawnMonsterAction` 的 2 只。

### 未建模（明确记录）

- **BronzeOrb 的 Stasis 偷牌**：`ApplyStasisAction` 按 RARE→UNCOMMON→COMMON→任意 在玩家
  弃牌堆/抽牌堆里挑一张抽走。`SimCard` 目前不带稀有度，所以没建。
  偏差方向 = 影子**高估**玩家手牌一张（搜索略乐观）；招式节奏本身是对的（3 号招就是「这回合不输出」）。
- 召唤物的能力初始化：原版 `applyPowers()` 会给「有力量的怪」补力量等；我们只挂 `Minion`。

---

## B 组落地记录（2026-10-26，Champ 已完成）

### 「多阶段」其实不需要通用钩子

计划里以为要给 `damage()` 重写做钩子。实际读字节码：**Champ 没有 `damage()` 重写**，
它在 `getMove` 开头用 `this.currentHealth * 2 < this.maxHealth && !thresholdReached` 判半血，
其余状态（`numTurns` / `thresholdReached` / `forgeTimes`）都是**普通字段**。
所以「多阶段」= `getMove` 里多一个 if，不需要任何新基础设施。

`BronzeAutomaton`（`numTurns` 固定循环）与 `TheCollector`（`turnsTaken` 计数）同理，
都在 A 组顺手做完了 —— A 组之所以能一次收三只 boss，就是因为它们只是「带计数的顺序表」。

### 三个字段全部从 `moveHistory` 重放

影子不另存字段，一律由历史推：

| 字段 | 重放规则 |
|---|---|
| `numTurns` | 每次决策开头 +1；**走 Taunt（6 号招）那条路径会把它清零** |
| `thresholdReached` | `history.contains(7)` —— 狂暴只可能发生一次 |
| `forgeTimes` | `countOf(history, 2)` —— 计数到 2 就不再打姿态 |

`champNumTurns` 里的「Taunt 清零」是原版 `if (numTurns == 4) { numTurns = 0; ... }` 的直接投影，
**不写这条就会在长战斗里把 Taunt 打成每 4 回合一次**。

### 测试踩坑（顺序表类怪物的通病）

给 Champ 写历史时，**历史长度本身会推进 `numTurns`**，于是「第 N 次决策」会被 Taunt 抢走。
三次连续失败都是这个原因，不是机器写错：
- `history(2)` 期望 2 得 4 —— `!lastMove(2)` 本就会挡住连打姿态；
- `history(2,5,2)` 期望 1 得 6 —— 3 条历史使 `numTurns==4`，Taunt 优先；
- `history(2,5,2,4)` + `num=10` 期望 1 得 5 —— 姿态被 `forgeTimes=2` 挡住后，
  `num≤30` 落到的是 **Gloat**，不是重斩。

结论：**给顺序表型怪物写断言，先算 `numTurns`，再挑 `num` 落在哪个分档**，
否则测试会一直"期望值写错"而不是"机器写错"。

### B 组落地记录（续）：Hexaghost —— 「6 个 orb = 半血分裂」这个模型彻底错了

字节码把两个假设都推翻了：

1. **没有 `damage()` / `die()` 重写**（上一轮已发现）；
2. **`HexaghostOrb` 是纯视觉对象** —— `createOrbs()` 只是按 6 个坐标 new 出 6 个 orb 塞进
   `ArrayList`（构造器参数是 `(x, y, index)`），**没有血、不可选中、打不掉**。
   所谓「半血分成 6 个 orb」在原版里根本不存在。

真正的状态是**一个 int 字段 `orbActiveCount`**，只有三处 `changeState` 会改它：

| changeState | 谁调用 | 副作用 |
|---|---|---|
| `"Activate"` | `takeTurn` 5 号分支（第 1 回合） | 6 个 orb 全亮，`orbActiveCount = 6` |
| `"Activate Orb"` | Sear / Tackle / Strengthen 结尾 | 点亮**下一个**还没亮的 orb，`orbActiveCount++`；**加到 6 时直接 `setMove(6, Inferno)`** |
| `"Deactivate"` | Divider / Inferno 结尾 | 全部熄灭，`orbActiveCount = 0` |

`getMove` 就一句 `tableswitch(orbActiveCount)`：0/2/5 → Sear、1/4 → Tackle、3 → Strengthen、6 → Inferno。
**一个随机数都不用**（`num` 抽了但不看），所以整场是确定性循环：

```
Activate → Divider(6×[玩家血/12+1]) → Sear → Tackle → Sear → Strengthen → Sear → Tackle → Sear
         → Inferno(6×2|3) → Sear → Tackle → …（此后每 7 招一轮，Divider 不会再来）
```

### 两个数在第 5 号分支里现算

`takeTurn` 的 5 号分支（Activate）做了三件容易漏的事：

```java
changeState("Activate");                                  // orbActiveCount = 6
int dmg = AbstractDungeon.player.currentHealth / 12 + 1;   // 注意是**当时**的血
damage.get(2).base = dmg; applyPowers();
setMove((byte)1, ATTACK, damage.get(2).base, 6, true);     // 硬设 Divider，绕过 getMove
```

所以 `Divider` **不是** `getMove` 选出来的，而是 takeTurn 直接写的；我们的机器用
`if (lastMove(5)) return divider(...)` 表达同一件事。伤害也在这一瞬间定死，
玩家之后掉血不会改它 —— 影子在「Activate 执行完、玩家回合开始前」生成这招，时点一致。

### 进阶数值（构造器三段 if）

| 档 | 力量 | Sear 塞 Burn | Tackle 单段 | Inferno 单段 | HP |
|---|---|---|---|---|---|
| A0–3 | 2 | 1 | 5 | 2 | 250 |
| A4–18 | 2 | 1 | 6 | 3 | 250 |
| A19+ | 3 | 2 | 6 | 3 | 264（A9 起就是 264） |

`searDmg`（Sear 本体的 6 点）与 `strengthenBlockAmt`（12）**所有档位都一样**。

### 未建模（明确记录）

- **Burn 在回合末的伤害**（2 / 升级后 4）。这是**状态牌机制**，不是怪物 AI；影子对所有状态牌
  都只当「占手牌」。偏差方向 = 影子**低估**玩家受伤（六火战中每回合 4–12 点）。
  这条要单独立议题（连同 Slimed/Wound 的「不可打出」是否已建模一起核）。
- `burnUpgraded`（第一次 Inferno 之后 Sear 塞的是 Burn+）：因为上一条没建，它在影子里没有可观测效果，
  所以只记录不实现。

---

## C 组落地记录（2026-10-26，面具强盗收口）

### 两只都是「getMove 只给开局那一招」

`BanditBear.getMove` 全文只有 `setMove(2, STRONG_DEBUFF)`；`BanditLeader.getMove` 只有
`setMove(2, UNKNOWN)`。**它们之后的招全在 `takeTurn` 里用 `SetMoveAction` 直接写死**，
`num` 抽了但从来不看。所以这两只和 Champ 一样：从 `moveHistory` 重放，不需要任何新载荷。

```
熊  ：2 Bear Stun(敏捷 -2 / A17: -4) → 3 Slash(9|10 伤 + 9 格挡) → 1 Maul(18|20 伤) → 3 → 1 → …
头目：2 喊话(不打人)                 → 3 Agonize(10|12 伤 + Weak 2 / A17: 3) → 1 Slash(15|17 伤)
       A17 起 Slash 之后若 !lastTwoMoves(1) 就再刺一下 → 节奏变成 3→1→1→3→1→1→…
```

熊的 `damage()` / `die()`、头目的 `deathReact()` / `damage()` 全部只有动画和台词 ——
「熊死了头目会狂暴」是**不存在的**，它只会喊一句。

### 🔴 顺手挖出两个静默 bug（都是「负增量」踩的）

做熊的 Bear Stun 时发现敏捷根本扣不掉，顺藤摸下去是两个叠在一起的坑：

1. **`MonsterMove` 权威构造器用 `Math.max(0, playerDebuffAmount)` 夹住了减益量** ——
   负增量被静默改成 0。于是「扣敏捷 / 扣力量」这类负增量永远不可能表达。
2. **`CombatFlow` 里落地条件是 `playerDebuffAmount > 0`** —— 即使构造器放行，也会被这里拦掉。

连带发现 **Lagavulin 的 Siphon Soul 一直是错的**：原版字段 `debuff` 本身就是 `-1`（A18: `-2`），
我们镜像写成 `+1`。因为 ① 的 clamp 把它压成 0（字段读出来是 0），测试当年只断言了
「playerDebuff2 是 Dexterity」而没断言**符号**，所以红绿灯一直全绿。
现在的处理：构造器与三处落地条件都改成「允许负数」，Lagavulin 改成 `-1/-2`，
并补了一条 CombatFlow 级别的集成测试（Bear Stun 落地后玩家 `Dexterity == -2`）。

**教训**：凡是「原版是 `ApplyPowerAction(amt)`」的字段，amt 就是**带符号增量**，
不要用 `> 0` 判空、也不要用 `Math.max(0, ...)` 夹 —— 那会把减益变成无操作。

---

## D 组落地记录（2026-03 幕先头 4 只，2026-10-26）

第一批挑了 4 只**不需要新玩家侧机制**的：

| 怪 | 关键点 |
|---|---|
| `OrbWalker` | 两招互斥（`lastTwoMoves` 判重）；原版字段名反着存 —— `damage[0]` 字段叫 `laserDmg` 却给 1 号招（Claw）用，照数值抄 |
| `Maw` | `turnCount` 从 1 起、每次 `getMove` 开头 +1 → 第 n 次决策时 `= history.size() + 2`；Nom 段数 `turnCount / 2` |
| `Transient` | 一招递增 30→40→50…；**Fading 5\|6** 是通用机制，放进 `CombatFlow` 怪物回合开头统一递减 |
| `Repulsor` | Dazed 进**抽牌堆**（不是弃牌堆）→ 新增 `Extra.statusToDraw`，落地时插在抽牌堆中间（原版随机位置用的是 libgdx `MathUtils.random`，不吃 run seed） |

### 两处新基础设施

1. **`Extra.statusToDraw(card, count)`**：`MakeTempCardInDrawPileAction`。和主字段
   `statusCard`（`MakeTempCardInDiscardAction`，进弃牌堆）分开 —— 抽牌堆里的牌这一场更容易摸到。
2. **`Fading` 递减**：`FadingPower.duringTurn()`，每只怪的回合开始时 -1，减到 0 当场死、
   这回合不出招。不建的话 Transient 会永远打下去，搜索会极度悲观。

### 还没做（D 组剩余 14 只，按「缺什么」排队）

| 缺的机制 | 怪兽 |
|---|---|
| **死亡时爆炸 / 自毁** | `Exploder`（`ExplosivePower` 在字节码里只有 description，触发点待查） |
| **半血复活 / 形态切换** | `Darkling`（`halfDead` + Reincarnate）、`AwakenedOne`、`TimeEater` |
| **多段固定循环 + 队友联动** | `Deca` / `Donu`、`SpireShield` / `SpireSpear`、`GiantHead`、`Nemesis`、`WrithingMass`、`SnakeDagger` |

⚠️ 这 14 只**不是「抄不动」而是「抄了会假」**：缺的那条机制不建，影子会把它们的威胁算错
（例如 SpireGrowth 的 Constrict 每回合 10 点、Spiker 的荆棘 4–10 点）。
所以先记在这里，等对应机制单独立项再做，不硬上。

### 补记：Spiker 其实**不用新机制**

`applyThorns`（`ThornsPower.onAttacked`）在 `DamageCalc.hit` 里**早就建好了**：玩家攻击带
Thorns 的怪 → 玩家按 amount 掉血（走格挡）。所以 Spiker 只差一个机器：

- 开场 Thorns 3（A2: 4）是 `usePreBattleAction` 挂的，真机快照里已经有了；
- `thornsCount` 从 0 起、**只在 2 号招里 +1** → 直接数历史里 2 的个数；
- 判据顺序：`thornsCount > 5` → 只刺；`num < 50 && !lastMove(1)` → 刺；否则 Sharpen（+2 荆棘）。
- 注意「刚刺过就不能再刺」这条**和 num 无关** —— 低 num 也只会在磨刺后的一击上生效。

### 补记：Constricted 的回合末伤害（SpireGrowth）

`ConstrictedPower.atEndOfTurn(boolean)` → `DamageAction(owner, new DamageInfo(owner, amount, THORNS))`：
**type = THORNS**，所以它**吃格挡**（`AbstractCreature.damage` 只有 HP_LOSS 才越过格挡）。
已在 `CombatFlow.endTurn` 的「弃手牌之后」补一条结算（③.5 步），永久生效、不会自己消失。

A17 起的差别不是「叠两层」，而是**数值 +2**：
```java
if (ascensionLevel >= 17) new ConstrictedPower(player, this, constrictDmg + 2);
else                      new ConstrictedPower(player, this, constrictDmg);
```
所以 A17 是每回合 12，不是 20。
