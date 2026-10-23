# 战斗机制参考（实现标准）

> 用途：镜像实现的**规范来源**。写代码前先查这里，不要靠"我觉得应该是"。
> 最后更新：2026-10-21 ／ 版本 v0.1

## 证据分级（每条结论都标注来源）

| 级别 | 含义 | 可信度 |
|---|---|---|
| **A｜字节码** | 反编译 `desktop-1.0.jar` 得到，**这是唯一的事实源** | 最高 |
| **A｜实测** | 本项目差分台架在真机验证过，附日志 | 最高 |
| **B｜wiki** | [slaythespire.wiki.gg](https://slaythespire.wiki.gg) 等社区维基 | 高，但可能与实现有出入 |
| **C｜待验** | 有说法但未验证 | 不可当依据 |

> ⚠️ **wiki 不能当第一证据**：本文件已经发现至少一处 wiki 未写明、只有字节码能确定的规则（见 §4 递减时机）。
> 正确姿势：**wiki 定方向 → 字节码定事实 → 差分台架做最终裁决**。

---

## 1. 回合与轮（Round）

| 事实 | 来源 |
|---|---|
| 玩家回合：能量回满 → 抽牌至 `gameHandSize`（默认 5）→ 出牌 → 结束回合 | B |
| 玩家回合结束：手牌全部进弃牌堆；玩家格挡清零（有例外） | B |
| 之后所有敌人依次行动 | B |
| **怪物侧**减益（Vulnerable / Weak / Frail）**每轮结束减 1 层** | **A｜字节码**：`MonsterGroup` 中调用 `atEndOfRound`（2 处），各 `*Power` 覆写该方法 |
| **玩家侧**减益的递减时机 | ⚠️ **未确认**：玩家对象里没找到 `atEndOfRound` 调用点，可能走另一条路径（待验 §8-1） |

⚠️ **两个"结束"必须分清**：`atEndOfRound`（一整轮，含敌方行动）与"我的回合结束"是不同时机，**且玩家侧与怪物侧不一定对称**——这一点 wiki 完全没写，字节码目前也只证明了怪物侧。

### 1.1 回合结束必须做的事（A｜实测，2026-10-21）

**触发入口**：`EndTurnButton` 点下去只做一件事——`AbstractPlayer.endTurnQueued = true`
（反编译确认）。由游戏主循环执行完整流程。

流程里**至少**包含：回合末状态结算（`atEndOfTurn`）→ 手牌弃置 → 格挡清零 → **敌方行动阶段** → 下一回合开始（回能 + 抽牌）。

🔴 实测教训：直接调用 `AbstractDungeon.actionManager.endTurn()` 会**跳过玩家侧这一整段**，
表现为「能量每回合 +4，但不抽牌、怪物也不行动」，而且只在特定状态（如战斗专注施加的
`No Draw`）下才暴露。**镜像实现「结束回合」时，必须复刻完整流程，不能只调用底层 API。**

### 1.2 回合阶段钩子的精确顺序（A｜字节码，2026-10-22 复核）

上面那句"回合末状态结算 → 手牌弃置"是**粗的**。逐方法反编译后，玩家的回合边界是这样：

| # | 调用方（字节码） | 做了什么 |
|---|---|---|
| 1 | `GameActionManager.callEndOfTurnActions` → `AbstractRoom.applyEndOfTurnRelics` | 每个遗物 `onPlayerEndTurn()` |
| 2 | 同上 → `AbstractRoom.applyEndOfTurnPreCardPowers` | 每个 power `atEndOfTurnPreEndTurnCards(true)` —— **金属化在这** |
| 3 | 同上 → `TriggerEndOfTurnOrbsAction` / 手牌 `triggerOnEndOfTurnForPlayingCard()` / `AbstractStance.onEndOfTurn` | 充能球、手牌里的回合末效果、姿态（铁甲战士无关） |
| 4 | `DiscardAtEndOfTurnAction` | 手牌进弃牌堆（保留牌走 `RestoreRetainedCardsAction`） |
| 5 | `AbstractRoom.endTurn()` → `AbstractCreature.applyEndOfTurnTriggers()` | 每个 power `atEndOfTurn(true)` —— **燃烧/炸弹/欧米伽在这** |
| 6 | 敌方阶段 | 怪物按意图行动 |
| 7 | 玩家下一回合（`GameActionManager.getNextAction`） | `applyStartOfTurnRelics` → `applyStartOfTurnPreDrawCards` → `applyStartOfTurnCards` → **powers `atStartOfTurn`** → 回能 + `DrawCardAction` → `applyStartOfTurnPostDrawRelics` → **powers `atStartOfTurnPostDraw`** |

两条容易搞反的硬事实：

- `applyEndOfTurnTriggers` 的字节码是 `if (!isPlayer) p.atEndOfTurnPreEndTurnCards(false); p.atEndOfTurn(isPlayer);`
  —— 对**玩家**只调用 `atEndOfTurn`。所以金属化（pre-end-turn-cards）与燃烧（atEndOfTurn）
  **不在同一时刻**，前者在手牌弃置之前、后者在之后。
- `atStartOfTurn` 在**回能与抽牌之前**，`atStartOfTurnPostDraw` 在**抽牌之后**。
  磁力/乱战/神气归零属于前者，恶魔形态属于后者。

各能力的重写点（`javap -p` 逐类核对）：

| 能力 | 重写的方法 |
|---|---|
| Metallicize | `atEndOfTurnPreEndTurnCards` |
| Combust / TheBomb / Omega（watcher） | `atEndOfTurn` |
| DemonForm | `atStartOfTurnPostDraw` |
| Magnetism / Mayhem / Panache（归零） | `atStartOfTurn` |
| Panache（计数） | `onUseCard` |
| Sadistic | `onApplyPower` |
| Juggernaut | `onGainedBlock` |

---

## 2. 伤害公式

### 2.1 计算顺序（A｜字节码 + B｜wiki 一致）

```
伤害 = floor( (基础 + 力量 + 其他加算) × 虚弱系数 × 易伤系数 … )
```

- **加算先于乘算**：`(6 + 2) × 1.5 = 12`，而不是 `(6 × 1.5) + 2 = 11`
  （A｜字节码：`AbstractCard#applyPowers` 中先遍历 `AbstractRelic#atDamageModify`、再 `AbstractPower#atDamageGive`、最后 `AbstractPower#atDamageFinalGive`，中间各做一次 `f2i`，末尾 `MathUtils.floor`）
- **每一步都向下取整**（`f2i` 对正数等价于 floor）
- 加算清单（B）：力量、Vigor、Accuracy、Strike Dummy、Wrist Blade
- 乘算清单（B）：易伤、虚弱、Wrath/Divinity 姿态、Phantasmal Killer、Pen Nib、Slow、Surrounded

### 2.2 系数（A｜字节码）

| 效果 | 系数 | 证据 |
|---|---|---|
| 易伤 Vulnerable | **×1.5**；带 Paper Phrog 遗物 → ×1.75；Odd Mushroom 相关 → ×1.25 | `VulnerablePower#atDamageReceive` 中的 `1.25f` / `1.75f` 分支 |
| 虚弱 Weak | **×0.75**；带 Paper Crane 遗物 → ×0.6 | `WeakPower#atDamageGive` 中的 `0.75f` / `0.6f` 分支 |
| 脆弱 Frail | 格挡获取 ×0.75 | A｜`FrailPower#modifyBlock`（`0.75f`） |

**限定**：易伤只放大 **Attack 类型卡牌**与**敌人意图**的伤害；能力牌/遗物/药水的伤害不吃易伤。直接扣血（如 Hemokinesis）不算伤害。（B）

### 2.3 格挡吸收（A｜实测）

```
先扣格挡，剩余部分扣血；格挡不会为负
```
实测：5 格挡吃 6 伤 → 格挡 0、扣血 1（`CardPlayerTest.blockSoaksDamageFirst`）。

---

## 3. 格挡

| 事实 | 来源 |
|---|---|
| 默认**回合结束时清零** | B："By default, Block is removed at the end of each turn." |
| 上限 999 | B |
| 敏捷 +N 作用于**卡牌获得**的格挡；脆弱 ×0.75 同上 | A｜实测 + B |
| 例外：Barricade 保留、Blur 下回合不清、Calipers 只减 15 | B |

实测确认：`Defend_R` 5 格挡；敏捷 +2 → 7；脆弱 → `floor(5×0.75)=3`。

---

## 4. 状态（Power）

| 状态 | 效果 | 递减时机 | 来源 |
|---|---|---|---|
| Vulnerable | 受到的攻击伤害 ×1.5 | **怪物侧：每轮结束 -1**（玩家侧待验） | A｜`atEndOfRound` |
| Weak | 造成的攻击伤害 ×0.75 | **怪物侧：每轮结束 -1**（玩家侧待验） | A｜`atEndOfRound` |
| Frail | 获得的格挡 ×0.75 | **怪物侧：每轮结束 -1**（玩家侧待验） | A｜`modifyBlock` + `atEndOfRound` |
| Strength | 攻击伤害 +N，永久 | 不递减 | B |
| Dexterity | 格挡 +N，永久 | 不递减 | B |
| Artifact | 抵消一次减益施加 | — | B（待字节码） |
| Intangible | **最终伤害**压到 1 | 自己回合结束 -1 | A｜`atDamageFinalReceive`（在易伤之后的最末环节） |

---

## 5. 能量与费用

| 事实 | 来源 |
|---|---|
| 每回合开始能量回满（铁甲基础 3；Ectoplasm 等遗物 +1） | A｜实测（`energy=4` 带 Ectoplasm） |
| 费用 `-1` = X 费；`-2` = 不可打出 | A｜字节码 `AbstractCard.cost` 约定 |
| 卡牌实际费用可被遗物/状态改写（如 Snecko Eye 随机化费用） | B（待验） |

---

## 6. 抽牌、手牌、牌堆

| 事实 | 来源 |
|---|---|
| 抽牌数默认 5（`gameHandSize`，可被遗物改写） | A｜字节码字段 `AbstractPlayer.gameHandSize` |
| 🔴 **手牌上限 10，满手时抽牌动作直接不抽**（不是"抽了再弃"） | **A｜字节码**：`DrawCardAction` 中 `bipush 10` 与 `CardGroup.size()` 比较，超过就不再抽 |
| 抽牌堆空时，弃牌堆洗回抽牌堆（消耗 `shuffleRng`） | B |
| 打出的牌先进 `limbo` 暂存，动画结束才落进弃牌堆 | **A｜实测**（见 §7） |
| 死亡怪物在死亡动画期间仍留在怪物列表（0 血） | **A｜实测** |

### 6.1 「随机搬牌 / 随机消耗」都会消耗 `cardRandomRng`（A｜字节码，2026-10-22）

一类容易漏掉的 RNG 消耗点：动作里写 `isRandom` 或「张数不够就随机来」的分支，
内部都是 `CardGroup.getRandomCard(rng)` = `group.get(rng.random(size - 1))`，**每张消耗一次**。

| 动作 | 触发条件 | RNG 消耗 |
|---|---|---|
| `PutOnDeckAction(p,p,amount,isRandom=false)`（战吼 / 未雨绸缪） | 手牌数 **>** amount → 开手牌选择窗 | 0 |
| 同上 | 手牌数 **≤** amount → 不弹窗，循环 `moveToDeck(getRandomCard(rng))` | **每搬一张 1 次** |
| `FiendFireAction`（恶魔之火） | 循环 `hand.size()` 次 `ExhaustAction(1, isRandom=true)` | **每消耗一张 1 次**，且消耗顺序是随机的 |
| `ExhaustAction(amount, isRandom=true, …)` | 随机分支 | 每张 1 次 |

⚠️ 两个连带结论：
1. **恶魔之火的消耗顺序不是手牌顺序** —— 影子若按手牌顺序消耗，`exhaust[i]` 一定对不上。
2. `PutOnDeckAction` 的循环边界是 `for (i = 0; i < hand.size(); i++)`，而 `size` 边搬边缩，
   所以实际搬走的是 ⌈n/2⌉ 张；只有 `n ≤ amount` 时才走到这里（战吼 amount=1 → n∈{0,1}），
   照抄这个边界即可，别"顺手修正"。

### 6.2 计数类遗物（A｜字节码，2026-10-22）

全部在 `AbstractRelic.onUseCard(card, action)` 里 `counter++`，到阈值归零并触发。
`state.relics` 存的就是 `AbstractRelic.counter`，可直接对拍。

| 遗物 | 计什么 | 阈值 → 效果 |
|---|---|---|
| Nunchaku | 攻击牌 | 10 → 回 1 能量 |
| Ornamental Fan | 攻击牌 | 3 → 4 格挡（裸加，不吃敏捷） |
| Shuriken | 攻击牌 | 3 → 力量 +1 |
| Pen Nib | 攻击牌 | 10 → 归零；计数 9 时（第 10 击）伤害翻倍 |
| Letter Opener | 技能牌 | 3 → 全体 5 点 THORNS 伤害 |
| **InkBottle** | **所有牌**（不分牌型） | 10 → 归零 + **抽 1 张** |

⚠️ 墨水瓶是最容易漏的：它不像修女只数攻击牌，`onUseCard` 里没有任何牌型判断。

### 6.3 洗牌 = 取一个 long 当种子交给 `java.util.Random`（A｜字节码，2026-10-22）

```java
// CardGroup.shuffle()  —— 无参版本（ShuffleAction / 弃牌堆洗回都走它）
Collections.shuffle(group, new java.util.Random(AbstractDungeon.shuffleRng.randomLong()));
```

三条硬结论：
1. **不是** `rng.random(n)` 洗牌，也不是「n 次随机交换」→ 整个洗牌只消耗 **1 次** `shuffleRng`。
2. 底层是 JDK 的 48 位 LCG（`RandomXS128` 完全不同），必须按 `next(31)` + 拒绝采样实现，
   否则排列对不上。实现见 `sim/Shuffle.java`，用 JDK 自己的 `Collections.shuffle` 做了逐元素对拍。
3. **即使列表只有 0/1 张，`randomLong()` 也照样消耗**（先取种子再调 shuffle）。

`EmptyDeckShuffleAction`（抽牌堆空时自动洗牌）= `discardPile.shuffle(shuffleRng)` + 逐张搬进抽牌堆；
`Deep Breath` = 上面这一步 + `ShuffleAction(drawPile)`（再洗一次）→ 共 **2 次** `randomLong`。

⚠️ 未确认：`EmptyDeckShuffleAction` 对第 11 张起改用 `souls.shuffle(card, true)`（视觉上飞到牌堆底），
落点没在字节码里核实；目前统一按顺序搬，若出现「洗牌后 `draw[i]` 失配」先查这条。

### 6.4 随机生成卡牌用的池子顺序（A｜字节码，2026-10-22）

`returnTrulyRandomCardInCombat(type)`：

```
for pool in [srcCommonCardPool, srcUncommonCardPool, srcRareCardPool]:
    for card in pool.group:
        if card.type == type and not card.hasTag(HEALING): list.add(card)
return list.get(cardRandomRng.random(list.size() - 1))
```

- `src*CardPool` 的内容 = `CardLibrary.addColorlessCards()` + `player.getCardPool()`，再按稀有度分桶
  → 每个桶里**无色在前、本职业在后**，池内顺序 = `CardLibrary.cards`（一个 **HashMap**）的迭代顺序。
- HashMap 迭代顺序手算必错 → 所以 `PoolCatalog` 在战斗开始时直接从实机 `src*CardPool` 抓一次快照
  （和蓝本缓存 root 候选集一个思路），静态表只作单测兜底。
- `HEALING` 过滤：无色是 **Bandage Up / Bite**，红卡是 **Feed / Reaper**。
- `random(size - 1)` 闭区间，**候选只有 1 张也消耗一次**。

⚠️ 之前 `ColorlessPool` 的错误（已修）：按字母序 + 混进 10 张 token 牌（Shiv/Smite/Safety/Beta/Insight/
Miracle/Omega/Ghostly/Expunger/ThroughViolence）+ `FlashofSteel`、`MasterofStrategy` 两个 ID 拼错
（正确是 `Flash of Steel` / `Master of Strategy`）→ 磁力 / Jack Of All Trades / Transmutation 取到的牌必错。

### 6.5 STS1 与 STS2 的随机数差异（A｜字节码 + A｜蓝本源码，2026-10-22）

两者**不是同一套模型**，移植时不能照抄蓝本的 RNG 代码：

| | STS1 | STS2（蓝本 `CombatPredictionRngSet`） |
|---|---|---|
| 流数量 | `AbstractDungeon` 上 13 个 `Random`，战斗相关 **5 个**：`cardRandomRng` / `monsterRng` / `aiRng` / `shuffleRng` / `miscRng` | **9 个按用途拆分**：Shuffle / CombatCardGeneration / CombatPotionGeneration / CombatCardSelection / CombatEnergyCosts / CombatTargets / CombatOrbGeneration / MonsterAi / Niche |
| 取值粒度 | 一个 `miscRng` 兜住所有杂项；没有「费用随机」「目标随机」这种专用流 | 打哪张牌的费用、打谁，各有独立流 |
| `random(n)` | **闭区间 `[0, n]`**（`counter++; nextInt(n + 1)`） | 蓝本用 `Rng.NextItem(list)` 取元素，语义等于「列表下标」 |
| 洗牌 | 取 **1 个 long** 当种子交给 `java.util.Random`（见 §6.3） | `StableShuffle(Rng.Shuffle)`，自己实现的稳定洗牌 |
| 可复现性 | `Random` 自带 `copy()`；`(state0, state1, counter)` 足以完全还原 | 每流独立 fork，`PredictionRngState` 快照 |

**移植结论**：
1. 照抄蓝本的「按用途分流」会**多消耗随机数** —— STS1 里根本不存在这些流，照抄必然全流错位。
2. 照抄 `NextItem` 的下标语义到 STS1 要写 `random(size - 1)`，写成 `random(size)` 就越界（这条踩过）。
3. 洗牌一律走 §6.3 的种子法，**不要**自己写 Fisher-Yates。
4. `miscRng` 是杂项垃圾桶：拿不准某效果用哪条流时，先在实机对拍看 `rng.*` 的 counter 变化定位，
   不要猜。

---

## 7. 本项目实测踩过的坑（A｜实测，2026-10-21）

这几条 wiki 上查不到，只能靠真机对拍发现：

| 现象 | 真实规则 |
|---|---|
| `random(n)` 边界 | **闭区间 `[0, n]`** —— 实现是 `counter++; nextInt(n + 1)` |
| 随机数可重建 | `(state0, state1)` 足以完全还原流，`counter` 只是记账 |
| 打出的牌何时进弃牌堆 | 先进 `limbo`，**动画结束**才落堆；"动作队列空 + limbo 空"仍可能早一帧 |
| 死亡怪物 | 游戏保留 0 血单位到死亡动画结束才移除；影子若立刻删会与实机长度不符 |
| 手牌顺序 | 对策略无意义，同名牌可互换；但**抽牌堆顺序有意义** |
| 金币 | Ectoplasm：`gainGold` 直接 return，但 `loseGold` 不受影响（能失不能得） |

---

## 8. 待验证清单（C 级，按优先级）

1. 🔴 **玩家侧减益的递减时机**：怪物侧已确认走 `atEndOfRound`，玩家侧找不到调用点，很可能不同步
2. **Artifact 与减益的交互**：抵消发生在施加时还是结算时
3. **多段攻击**：每段单独吃力量，易伤逐段生效
4. **敌人意图**的生成时机与 `aiRng` 消耗点
5. **遗物触发顺序**（多个遗物同钩子的执行次序）
6. **洗牌算法**与 `shuffleRng` 的消耗方式
7. **X 费卡**的能量消耗与伤害结算

---

## 9. 对镜像实现的直接约束

1. 伤害公式必须按 §2.1 顺序，且**每步 floor**。
2. 减益层数只能在一轮结束时递减——不要在"该生物回合结束"时减。
3. 易伤只作用于 Attack 与怪物意图，不要作用于能力牌伤害。
4. 未在 §4 登记的状态一律**显式拒绝**，不许当成 0 处理。
5. 每个系数/顺序改动，都要在 `docs/research/mechanics-reference.md` 补一条来源。

---

## 10. 卡牌数据从哪来（不再靠"打出来才知道"）

**问题**：靠实战打出某张牌，只能得到 1 bit（支持 / 不支持），一场战斗最多覆盖 5–10 张，效率极低。

**做法**：游戏自带 `com.megacrit.cardcrawl.helpers.CardLibrary`，字段
`public static HashMap<String, AbstractCard> cards` 就是完整卡池。
mod 在首场战斗时一次性导出（`capture/CardExporter`）：

```json
{"id":"Bash","name":"重击","color":"RED","type":"ATTACK","cost":2,
 "dmg":8,"blk":-1,"magic":2,"canUpgrade":true,
 "up":{"cost":2,"dmg":10,"blk":-1,"magic":3}}
```

导出后可以：
- 用数据生成镜像表，而不是一卡一手写
- 对照 `dmg/blk/magic` 校验我们写的常量（机器对机器，不用人看）
- 统计覆盖率：`已镜像 / 卡池总数`

⚠️ 已知限制：`isMultiDamage` 是 `protected`，当前没导出；多段攻击的判定要另想办法。

### 10.1 三类来源的优先级

镜像表的每一条都可能来自三个地方，冲突时的裁决顺序：

| 优先级 | 来源 | 说明 |
|---|---|---|
| 1（最高） | **真机对拍** | 唯一有裁决权的证据；与任何文档冲突时，改文档 |
| 2 | **游戏数据**（`cards.json` 的 dmg/blk/magic） | 数值直接来自 `CardLibrary`，可信，但只覆盖"数字" |
| 3 | **调研结论**（wiki / 机制文档） | 覆盖"数字之外"的规则（消耗、每回合掉血、触发时机），必须标注出处 |

⚠️ **用户点出的"爆牌"就是典型第 3 类**：卡面只写"抽 4 张"，
但手牌满 10 张时抽牌动作直接不抽——**这种规则只能靠调研或对拍发现，卡面文字会骗人**。

举例（用户提供）：战斗专注抽 4 张，手牌 8 张（含它自己）→ 打出后剩 7 张 → **只能抽 3 张**。
镜像若不实现手牌上限，就会算出"抽 4 张"，与实机差 1 张，而且只在满手时暴露。

---

## 11. 自检记录（写完自己过一遍）

按用户要求，逐条复核本文件里的 A 级结论，明确标出哪些其实站不住。

| 结论 | 复核方式 | 结论 |
|---|---|---|
| 加算先于乘算、每步 floor | `javap -c AbstractCard#applyPowers` 看到 `atDamageModify → f2i → atDamageGive → f2i → atDamageFinalGive → MathUtils.floor` | ✅ 成立 |
| Weak ×0.75 | `WeakPower#atDamageGive` 中 `ldc 0.75f` + `fmul` | ✅ 成立 |
| Vulnerable ×1.5 | `VulnerablePower#atDamageReceive` 中出现 1.25f / 1.75f 的**分支**，基值 1.5 由 wiki 与反编译共同支持 | ✅ 成立（基值来自分支推断，非直接常量） |
| Frail ×0.75 | `FrailPower#modifyBlock` 中 `ldc 0.75f` + `fmul` | ✅ 成立 |
| **减益每"轮"结束减 1** | 找到 `MonsterGroup` 调用 `atEndOfRound`（2 处）；**但玩家侧没找到调用点** | ⚠️ **降级**：怪物侧成立，玩家侧未证实 |
| Intangible 在最后环节 | `IntangiblePower#atDamageFinalReceive`（另有 `atEndOfTurn`） | ✅ 成立 |
| 格挡回合结束清零 | `GameActionManager` 中 `loseBlock` 调用 2 处；wiki 明确 | ✅ 成立（具体时机待实测） |
| `random(n)` 闭区间 | 反编译 + 离线重建实机 8 连抽逐位一致 | ✅ 成立（有回归测试） |
| 打出的牌先进 limbo | 实机 diff 观测（`limbo=N` 字段） | ✅ 成立 |
| 死亡怪物保留到动画结束 | 实机 diff 观测（`monsters.size` 差） | ✅ 成立 |

### 我明确不确定、但文档里可能被误读的地方

1. **"一轮结束"到底以什么为界**：`atEndOfRound` 被 `MonsterGroup` 调用，说明它是**怪物行动结束后**统一触发；玩家身上的减益是否也在同一时刻递减，没有证据。
2. **Vulnerable 基值 1.5**：反编译只看到 1.25/1.75 两个遗物分支，1.5 是"排除分支后的默认值"，属于**强推断**而非直接常量。
3. **格挡清零的具体时机**（回合结束前还是结束后、与减益递减的先后）：只知道调用了 `loseBlock`，顺序未验证。

> 这三条都属于"看起来对但不该当铁律"的范畴。任何人（包括我）据此写实现前，先用差分台架验一次。

## 12. 蓝本的搜索规模（A｜实测截图，2026-10-22）

用户在 STS2 里用 `sts2-combatsolver` 打了一整局铁甲战士，面板给出的数据：

| 战斗 | 世界线 | 耗时 | 速度 | 规划回合 |
|---|---|---|---|---|
| 第 1 场小怪 | 1,823 | 1.1 s | 1,682/s | 5 |
| 第 1 次强怪（3 劫掠者） | 5,181 | 3.1 s | 1,688/s | 3 |
| 第 1 个精英（乐加维林） | 11,288 | 3.0 s | 3,756/s | 7 |
| 第 1 幕 boss | 10,740 | 1.5 s | 6,942/s | 6 |
| 第 2 幕 boss | 135,403 | 50.1 s | 2,704/s | 7 |
| 第 3 幕小怪 | 120,000 | 63.3 s | 1,896/s | 5 |
| 第 3 幕精英 | 245,858 | 119.9 s | 2,051/s | 5 |
| 最终 boss | 324,632 | 117.6 s | 1,395/s | 13 |

### 三条硬结论

1. **一次计算 = 整场战斗的完整路线**，不是每张牌算一次。
   面板直接列出「第 1 回合 / 第 2 回合 …」的动作序列，并给出每回合的对敌伤害、扣血、剩余能量。
   按钮是「冻结路线 / 执行本回合 / 重新计算」—— 也就是「计划一次 → 按回合执行 → 偏离才重算」。
2. **吞吐在 1,400–6,900 世界线/秒**。和我们测的「5 万状态/秒」同一量级
   （一条世界线 = 一条完整路线，含十几个 ply）—— 所以差距不在引擎速度，**在单次计算的时间预算**。
3. **有节点上限**：最终 boss 那次提示「计算尚未彻底穷尽，已达到当前设置的【节点上限】」，
   即蓝本是「时间/节点上限 + 剪枝」，不是穷尽。

### 我们要对齐的地方

| 蓝本 | 我们（2026-10-22 现状） |
|---|---|
| 一次规划整场战斗（5–13 回合） | 每次决策只搜 8–16 步（≈1 个回合），且**每打一张牌就重搜** |
| 预算 1–120 秒（后台） | 预算 8–300 ms |
| 输出完整路线 + 每回合战损/能量 | 只输出「下一张牌」 |
| 冻结路线 / 执行本回合 / 重新计算 | AUTO 逐张实时决策 |


### 12.1 「世界线」到底是什么（A｜蓝本源码，2026-10-23）

面板上的「已查阅 N 条世界线」**不是完整路线数**，而是**展开节点数**：

```csharp
// src/Runtime/SolverController.cs:617-625
private static void RecordReviewedWorldlines(SolverResult result) {
    if (result.WasRestoredFromCache) return;
    if (!_combat.ReviewedWorldlineResults.Add(result)) return;
    long reviewed = result.TotalExpandedNodes;          // ← 就是它
    _combat.ReviewedWorldlinesTotal = checked(_combat.ReviewedWorldlinesTotal + reviewed);
}
```

循环里 {@code _run.Expanded++} 在 `src/Search/CombatBeamSolver.ExpansionPlan.cs:34`，
而节点上限的判定在 `CombatBeamSolver.Phases.cs:534`：
`else if (boundary == None && _run.Expanded >= _profile.MaxExpandedNodes)`。

**结论**：`MaxExpandedNodes` 卡的就是这个计数器，所以：
- 我们早期记「展开了几个父节点」是**对的**；
- 后来改成「生成的子状态数」反而**偏离了蓝本口径**（一个展开会生成十几个子状态，差一个数量级）；
- 现在两个都记：`lastNodes`（展开数 = 世界线）+ `lastStates`（子状态数 = 诊断）。

对齐后的实测（合成 220 血战斗）：

| 档 | 节点 | 状态 | 到达终局 | 宽度 | 耗时 |
|---|---|---|---|---|---|
| 固定 6×4（单次决策） | 19 | 176 | - | 6 | 0.63 ms |
| Medium，2 s 预算 | 102,807 | 286,934 | 3,720 | 960 | 946 ms |

吞吐 ≈ **10.8 万节点/秒**，蓝本 ≈ 1000 节点/秒 —— 差两个数量级，
说明 STS2 那边一个「展开」真实成本比我们重得多（完整对象图克隆 vs 纯数据 copy）。
所以**节点上限不能照抄数值**：蓝本 Medium=12 万节点要 120 s，我们 1 s 就跑完了。


## 13. 搜索量 vs 估值：第一次 A/B 实测（A｜本项目离线自对弈，2026-10-23）

离线跑「规划 → 执行 → 结束回合」整场（`search/SelfPlayTest`，不碰游戏），
同一场面只改每次决策的时间预算：

| 场面 | 预算 | 回合 | 剩余血量 | 总节点 | 结果 |
|---|---|---|---|---|---|
| 双教徒 40+40（每回合共 20 伤） | 5 ms | 9 | 24 | 3,097 | 存活 |
| 同上 | 60 ms | 16 | **0** | 75,260 | **阵亡** |
| 同上 | 300 ms | 9 | 20 | 180,353 | 存活 |
| 单厚怪 120 血（12 伤） | 5 ms | 20 | 64 | 18,638 | 击杀 |
| 同上 | 60 ms | 20 | 64 | 109,831 | 击杀 |
| 同上 | 300 ms | 20 | 64 | 504,646 | 击杀 |

### 结论（与用户假设相反，但数据如此）

1. **节点数放大 60 倍，结果几乎不变**，中间那档还直接打死了 —— **非单调**。
2. 说明当前瓶颈**不是搜索量**：搜索把「评分更高、实际更差」的路线找出来了。
   也就是估值函数与「活着并打死对面」不一致，搜得越深错得越狠。
3. 单厚怪 120 血打了 20 回合才死（玩家只掉 16 血）—— AI 明显**过度防守**，
   这同样是估值偏向（怕掉血 > 推进）。

> ⚠️ **2026-10-23 复检：这张表的方法本身有问题。** 变量是「每次决策的墙钟预算」，
> 同一配置两次跑出来的结果不一样，三档之间的差异有相当一部分是机器负载噪声。
> 「非单调」有一半是噪声造的假象。**确定性的台架见 §14。**
> 但结论 3（过度防守 / 磨洋工）是真实的，并且有明确的机制解释（汇率差 333 倍）。

### 下一步方向

不要继续加预算，先修估值与「路线择优」：
- 蓝本 `BeamRankScore` 是**投影血量 + 死亡惩罚 + 敌方耐久向量 + 资源**的加权，
  我们目前是字典序硬编码，且 `projectedHp` 权重（0.2×血量）压过了推进收益。
- 需要把「回合数」也纳入：拖着不打等于慢性失血。
- 用 `SelfPlayTest` 的表做回归：改动必须让「回合数下降、剩余血量不降」。


## 14. 估值改成蓝本的线性加权（A｜本项目实现 + 确定性 A/B，2026-10-23）

### 14.1 蓝本怎么算（源码位置）

| 概念 | 蓝本位置 | 说明 |
|---|---|---|
| 权重常量 | `src/Search/SolverWeights.cs` | 全套汇率都在这一个文件里 |
| 基础分 | `CombatBeamSolver.StateEvaluation.cs:162-231`（`Snapshot`） | 决定**最终选哪条路线** |
| Beam 排序分 | `CombatBeamSolver.BeamRetentionPolicy.Ranking.cs:892`（`BeamRankScore`） | 决定**哪些半成品路线能活到下一层** |

蓝本把「分数」写成有单位的钱：**单位是血**。

```
Hp = 30,000        1 点自己的血
EnemyHp = -10,000  1 点敌方血   →  1 血 ≈ 3 点伤害
VictoryBonus = 1e10, DeathPenalty = -1e12   →  赢/死不可交换
```

基础分（`Snapshot`）：

```
base = 投影血量 * Hp                        // 敌人按当前意图打完之后还剩多少
     + (最大生命 - 路线起点的最大生命) * Hp   // 吃（Feed）
     - 已损血量 * Hp                        // 蓝本有；我们默认关，见 14.3
     + 胜利加成
     + 敌方有效血量 * EnemyHp               // 有效 = 血 + 格挡
     + 牌库杂质 * (-8,000)                   // 状态/诅咒占着牌堆
```

Beam 排序分（`BeamRankScore`，在基础分上再加）：

```
+ min(6, 能量) * 60,000                    // 还能再打几张
+ min(4, 持续成长层) * 150,000              // 能力牌层数（力量也算进这里）
+ 集火目标易伤 * min(24, 牌堆攻击总量) * 5,000
+ 其余易伤     * min(24, 牌堆攻击总量) * 1,000
+ min(16, 敌方虚弱) * 1 * Hp
+ min(16, 敌方力量削减) * 4 * Hp
+ 动作数 * (-1)
```

### 14.2 我们照抄了什么、改了什么

| 项 | 蓝本 | 我们 | 原因 |
|---|---|---|---|
| 单位 | 血 = 30,000 | 同 | 直接照抄 |
| 汇率 | 1 血 = 3 敌方血 | 同 | **最关键的一条**，见下 |
| 胜负 | 1e10 / -1e12 | 同 | 照抄 |
| 死亡连续化 | 只有 `EndTurn` 且还能救的节点连续化 | **所有死亡都连续化**（`DeathPenalty + 投影血量*Hp`） | 否则所有必死路线同分，只能靠随机项决胜 |
| 已损血量二次计价 | 有 | **默认关**（`damageTakenWeight = 0`） | 蓝本自己注释写「1 血 ≈ 3 伤」，但两项同时计等于收 2 份钱、实际汇率变 1:6；STS1 还有 Reaper/吃这种战斗中回血，二次计价会把回血算成零收益 |
| 每回合惩罚 | 无 | **默认 0**（`turnPenaltyPerTurn`） | 实测一开就退化成 25 回合磨洋工，见 14.3 |
| 持续成长 | 按能力逐个估 RetentionValue | 按层估，封顶 4 层 | 我们没有那张能力表 |
| LatentSetup / ReplayPotential / 长期资源 / 遗物计数 / 药水 | 有 | **未实现** | STS1 对应物还没建模，先留空 |

**汇率为什么是关键**：旧实现是 `存活 * 1e9 > 血量 * 1e6 > 敌方血 * 1e3`，
即 **1 点血 = 1000 点敌方血**（蓝本是 3）。差 333 倍，于是
「少掉 16 血」在评分里等于「让对面多活 16000 血」——
AI 宁可磨 20 回合也不肯用 10 点血换 30 点伤害。这不是权重没调好，是**单位错了**。

### 14.3 确定性 A/B（同机、同搜索、只改估值）

台架 `search/ScoreAbTest`（`@Disabled`，手动跑）。变量改成**固定「宽度 × 深度」**，
每个输入必然得到同一个输出。场面=铁甲战士 12 张标准起手，玩家 80 血。

| 搜索 | 场面 | 旧估值（字典序 1:1000） | 新估值（蓝本 1:3） |
|---|---|---|---|
| 20×20 | 双教徒 40+40（每回合 20 伤） | 13 回合 / 剩 14 血 | **9 回合 / 剩 21 血** |
| 20×20 | 单厚怪 120 血（12 伤） | 22 回合 / 剩 64 血 | **12 回合 / 剩 55 血** |
| 60×60 | 双教徒 40+40 | 17 回合 / **阵亡** | **9 回合 / 剩 21 血** |
| 60×60 | 单厚怪 120 血 | 20 回合 / 剩 64 血 | **12 回合 / 剩 55 血** |
| 120×80 | 双教徒 40+40 | 10 回合 / 剩 22 血 | **8 回合 / 剩 17 血** |
| 120×80 | 单厚怪 120 血 | 20 回合 / 剩 64 血 | **12 回合 / 剩 55 血** |

三条硬结论：

1. **双教徒全档更好**：回合 13→9、剩血 14→21；旧估值在 60×60 会直接阵亡，新估值不会。
2. **单厚怪回合数腰斩**（20→12），代价是 9 点血 —— 这正是 1:3 汇率在起作用：
   用 9 点血换 8 个回合，按蓝本的账是赚的。
3. **新估值对搜索规模不敏感**（横着看每行都一样），旧估值 13/17死/10 三档乱跳。
   这比「分数更高」更重要：结果稳定才谈得上调参。

开关扫（60×60，只动一个变量）—— ⚠️ **这张表是「扁平 beam」时代的**，换成回合层结构后
`energy=0` 不再致死（见 §15.3），结论方向不变但幅度小了：

| 能量 Beam 分 | 掉血二次计价 | 双教徒 | 单厚怪 |
|---|---|---|---|
| 60,000（蓝本） | 0（我们的默认） | **9 回合 / 21 血** | **12 回合 / 55 血** |
| 60,000 | 1（蓝本原味） | 13 回合 / **阵亡** | 21 回合 / 64 血 |
| 0 | 0 | 9 回合 / **阵亡** | 19 回合 / 55 血 |
| 0 | 1 | 12 回合 / **阵亡** | 25 回合 / 64 血 |

- **掉血二次计价必须关**：开了之后两个场面都退化（更磨、还会死）。
- **能量 Beam 分必须留**：关掉后双教徒会阵亡 —— 它是「保留还有牌可打的路线」的信号，
  少了它，beam 里留下的全是「已经打空手牌」的死路。

### 14.4 还没做、但蓝本有的

以下项在 STS1 里都还没有对应模型，等 M4 之后按需补（补之前不要假装有）：

- `LatentSetupValue`（手里捏着的能力牌算「延迟收益」）
- `ReplayPotentialValue`（可复用攻击的未来价值，防「为了一点即时伤害把循环打断」）
- 药水与遗物计数（`RelicCounterEvaluation`）
- 每回合层分配节点预算（`MinimumTurnLayerExpandedNodes`）—— 我们的 beam 是单层的




## 15. 搜索结构改成蓝本的「回合层」（A｜蓝本源码 + 确定性实测，2026-10-23）

### 15.1 蓝本的结构（`CombatBeamSolver.Phases.cs:1479-1600`）

蓝本的搜索循环是**两层**的，不是我们原来那种扁平 beam：

```
while 前沿不空 && 展开数 < 节点上限 && 未超时:
    active  = 前沿里还没终局的节点（终局的进 completed）
    layerBudget = max(500, (节点上限 - 已展开) / 剩余保留回合数)
    for playDepth = 0; active 不空; playDepth++:
        展开 active 里每个节点
            ├─ 同一回合的后续出牌      → nextPlays
            └─ 过了回合末 / 已终局      → ended        ← 单独一档
        active = 按 Beam 宽度剪 nextPlays
        if 本层预算用光 && playDepth > 0 && ended 非空:
            把 active 全部「强制结束回合」塞进 ended，本层收工
    前沿 = 按 Beam 宽度剪 ended
```

三个要点，缺一不可：

1. **`EndTurn` 与「出牌」不在同一个 beam 里比。** 结束回合的节点能量回满、手牌重抽，
   出牌的节点两样都少 —— 混在一起排序等于拿苹果比橘子。
2. **节点预算按回合层分配**（`MinimumTurnLayerExpandedNodes = 500` 兜底）。
   蓝本注释记着事故现场：0 费引擎（干瘪之手、真言转神格）在**同一个回合里**能一直出牌，
   每步都真的产出一点资源、所以「无进展」判据永不触发，`play_depth` 从 173 涨到 248、
   `ended` 逼近 10 万、整份节点预算烧完也没推进到下一回合。STS1 有腐化 / 无痛 / 黑暗拥抱，同一个病。
3. **回合层本身就是「加深」，蓝本没有迭代加深。** 我们原来那套「几何加深 + 宽度阶梯」是
   为了在扁平结构里硬凑深度，现在整段删掉 —— 顺带修掉一个真 bug：迭代加深各轮共享
   `lastNodes`，后几轮被前几轮吃空的预算饿死，却照样覆盖了 `best`。

### 15.2 加宽补搜也照抄（`CombatSearchCoordinator.FailureRecovery.cs:40-140`）

蓝本 `EscalateSearchWhenNoVictory` 的完整逻辑：

| 环节 | 蓝本做法 | 我们的实现 |
|---|---|---|
| 触发 | `!IsCompleteVictory(selected)`（一场都没打赢） | 同 |
| 停止 | 赢了一局 / 上一轮没搜完（超时）/ 已经抬过 2 次 / 剩余时间不够 | 同 |
| 倍数 | `NoVictoryEscalationFactor = 2`，累计 2 次（最多 4 倍） | 同 |
| 放大对象 | **Beam 与节点帽一起翻**（注释：只翻一个没用，是乘的关系），分支上限 `×multiple` 封顶 100 | 同 |
| 耗时预估 | `剩余 < max(1, 上一轮耗时) × 2` 就不启动 | 同 |
| 采纳条件 | 新结果**严格改善**才换，否则停 | 同 |

> 教训：我一开始把触发条件改成「树搜完了就加宽」（因为我们的树 3 千节点就走空了，看着浪费预算）。
> 那是自主发挥，不是复刻。已改回蓝本的「没打赢才加宽」。

### 15.3 实测（确定性，同机）

`ScoreAbTest`（`gradle test -Pexperiments`）。场面＝铁甲战士 12 张标准起手，玩家 80 血。

**回合层覆盖**（单次决策，宽度 60，最多 16 回合）：

| 节点上限 | 场面 | 推进回合层 | 展开 | 耗时 |
|---|---|---|---|---|
| 2,000 | 双教徒 | 12 | 6,120（触发加宽，累计） | 150 ms |
| 10,000 | 双教徒 | 14 | 3,078 | 52 ms |
| 120,000 | 双教徒 | 14 | 3,078 | 49 ms |
| 2,000 | 单厚怪 | 15 | 14,106（触发加宽） | 200 ms |
| 10,000 | 单厚怪 | 13 | 2,752 | 34 ms |

**节点上限到 1 万就已经把树搜完了**（3,078 个节点、14 个回合层）。改成回合层之后，
2,000 的预算也能推进到 12–15 个回合层 —— 一层再也吃不掉整份预算。

**估值对照**（都跑在回合层结构上）：

| 宽度×回合 | 场面 | 旧估值 | 新估值 |
|---|---|---|---|
| 20×20 | 双教徒 40+40 | 9 回合 / 14 血 | 10 回合 / 20 血 |
| 60×60 | 双教徒 40+40 | 11 回合 / 19 血 | **9 回合 / 23 血** |
| 120×80 | 双教徒 40+40 | 11 回合 / 20 血 | **7 回合 / 29 血** |
| 20×20 | 单厚怪 120 | 21 回合 / 64 血 | **12 回合 / 55 血** |
| 60×60 | 单厚怪 120 | 20 回合 / 64 血 | **12 回合 / 55 血** |

**宽度扫**（回合层结构 + 新估值，只改宽度）：

| 宽度 | 双教徒 | 单厚怪 | 节点 |
|---|---|---|---|
| 30 | 8 回合 / 21 血 | 12 / 55 | 5,092 / 8,035 |
| 60 | 9 回合 / 23 血 | 12 / 55 | 10,287 / 15,618 |
| 135 | **7 回合 / 29 血** | 12 / 55 | 11,736 / 33,766 |
| 240 | 8 回合 / 28 血 | 12 / 55 | 22,010 / 58,412 |
| 512 | 8 回合 / 28 血 | 12 / 55 | 45,630 / 121,800 |

结论：面板档位里的「极高 = 135」正好落在最优点上，不用改档位。

### 15.4 吞吐与开销变化

| 指标 | 改前（扁平 + 迭代加深） | 改后（回合层） |
|---|---|---|
| 每次决策节点 | 102,807 | 3,258（树搜完了） |
| 每次决策耗时 | 946 ms | 105 ms |
| 吞吐 | 10.8 万节点/秒 | 3.4 万节点/秒 |

吞吐下降是预期的：现在**每个节点都要算一次「结束回合」**（`CombatFlow.endTurn` 含洗牌/抽牌/意图），
而以前只作为一个普通子节点。换来的是「每次决策总耗时降到 1/9、结果对预算不敏感」，
这笔账是划算的（A4 的门槛是 120 秒 10 万节点，按 3.4 万/秒 也有 400 万）。
