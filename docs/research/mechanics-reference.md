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

- ~~**掉血二次计价必须关**~~ → **2026-10-23 晚修正：已改回蓝本原味（开）**。
  上面这张表是「扁平 beam + 我关掉二次计价」得到的结论；关掉它是我自己的判断，
  不是复刻。蓝本 `StateEvaluation.cs:167` 确实有 `- cumulativePlayerHpLost * Hp`，
  且 `CumulativeHpLost` 记的是「累计未格挡伤害」、**不是**净值（回血单独记 `RecoveredHp`，
  只在最终排序 `StrategicHpDeficit` 里抵扣，不进基础分）。所以默认已改为 {@code 1.0}。
  代价：固定 12 伤的木桩会从 12 回合拖到 21 回合（那个木桩不会成长，对「龟」格外有利）。
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


## 16. 补齐 Beam 项：延迟收益 / 攻击总量成长，以及「掉血二次计价」改回蓝本原味

### 16.1 新补两项（都有 STS1 对应物）

| 项 | 蓝本 | 我们的实现 |
|---|---|---|
| 延迟收益 | `LatentSetupValue`：手牌/抽牌/弃牌里每张**还没打出去的能力牌**计 `LatentCardSetupValue` 点，普通能力牌默认 2 点，再按 `LatentSetupBeamCap = 24` 封顶；值 `LatentSetupBeamValue = 12,000` | 同。**只在开局敌人数 > 1 时启用**（蓝本 `_isActEndingBoss \|\| _initialEnemyCount > 1`，Boss 判定还没建模） |
| 攻击总量成长 | `RetainedAttackGrowth = min(16, max(0, retainedAttackValue - InitialRetainedAttackValue))`，值 20,000 | 同。`retainedAttackValue` = 手牌+抽牌+弃牌的 `max(1, 卡面伤害)` 之和 |

**能力牌分值表没有臆造**：蓝本对个别 STS2 能力卡给了更高的分（Echo Form 12 / Buffer 8 /
Defragment/Hotfix 6 / Demon Form·CreativeAi 5 / LightningRod 4 / Thunder 3 / MasterPlanner 4），
**STS1 没有这些卡**。哪张 STS1 能力更值钱属于需要判断的映射，所以统一走蓝本的默认分支（2 点）。

**顺手修掉一个保真度 bug**：我把 `retainedAttackValue` 在建对象时就截到 24 了，
而蓝本是原始值、只在易伤项里取 `min(24, ·)`。不修的话「成长」项永远是 0。

### 16.2 「掉血二次计价」改回蓝本原味（默认 1.0）

我之前把它设成 0（理由：蓝本两项同计等于收 2 份钱、实际汇率 1:6，与它自己注释里的 1:3 冲突；
而且 STS1 有 Reaper/吃这种回血）。核对源码后确认：

- `CumulativeHpLost` 记的是**累计未格挡伤害**（`SimulatedCombatState.CardEventHistory.cs:199-205`），
  只增不减；
- 回血单独记 `RecoveredHp`，**不进基础分**，只在最终排序的
  `ActEndingBossPolicy.StrategicHpDeficit` 里抵扣（`cumulativeHpLost + maxHpDeficit - recoveredHp`）。

所以「收 2 份钱」确实是蓝本的设计（他们叫「战略血量赤字」）。**按复刻要求改回 1.0。**

### 16.3 实测（确定性，`gradle test -Pexperiments`）

**Beam 项 / 二次计价 的 2×2（牌组含恶魔形态 + 暴走 + 愤怒）**：

| 只算基础分 | 二次计价 | 双教徒 | 单厚怪 | 打过恶魔形态 |
|---|---|---|---|---|
| 是 | 0 | 8 回合 / 40 血 | 10 回合 / 64 血 | 否 |
| 是 | 1 | 13 回合 / 28 血 | 10 回合 / 61 血 | 否 |
| 否 | 0 | 6 回合 / 26 血 | 5 回合 / 30 血 | **是（2 层）** |
| 否 | 1（**当前默认**） | 12 回合 / 27 血 | 9 回合 / 62 血 | 否 |

读数：
1. **Beam 项确实让 AI 会打能力牌了**（`baseScoreOnly=true` 时永远不打恶魔形态）。
2. 但 Beam 项给的 20 点血上限，只有在「不二次计价」时才压得过打恶魔形态的代价；
   开了二次计价（HP 实际 1:6），AI 在 10 回合的短战斗里选择不打 —— 从纯血量看这是对的。
3. 带能力牌的牌组在「二次计价 + Beam 项」下是 9 回合 / 62 血，**又快又省**，靠的是暴走/愤怒，
   不靠恶魔形态。

**默认配置下的回归表**（标准 12 张起手，没有能力牌）：

| 宽度×回合 | 场面 | 旧估值 | 新估值 |
|---|---|---|---|
| 60×60 | 双教徒 40+40 | 11 回合 / 19 血 | **10 回合 / 26 血** |
| 120×80 | 双教徒 40+40 | 11 回合 / 20 血 | **9 回合 / 23 血** |
| 60×60 | 单厚怪 120 | 20 回合 / 64 血 | 21 回合 / 64 血 |

→ **诚实结论**：改回蓝本原味权重后，双教徒仍有改善；单厚怪那种「固定 12 伤木桩」
新旧打平（都是 21 回合 / 64 血）。之前那张「12 回合」的表是关掉二次计价得到的，
现在不算数了。

### 16.4 还差的一环：最终排序轴

蓝本用 `StrategicHpDeficit = cumulativeHpLost + maxHpDeficit - recoveredHp`（**越小越好**）
作为路线质量的轴，并配一整套 `FinalPlanOrdering` 词典序（胜/负 → HP 赤字 → 回合 → 资源…）。
我们的最终选择仍是「基础分最大」，所以：
**回血（Reaper / 吃）在基础分里得不到credit**，只在 `damageTakenWeight` 的净值口径下被间接照顾。

要完全对齐，下一步该把 `ActEndingBossPolicy.StrategicHpDeficit` + `FinalPlanOrdering` 的
比较键搬到我们的 `better()` 里。这是目前与蓝本差得最大的一块。


## 17. 最终路线排序改成蓝本的 `FinalPlanOrdering` 词典序

### 17.1 蓝本那一串比较键（`FinalPlanOrdering.cs:253-271`，17 条）

```
Won 降序 → (!Won && !Survives) 升序 → DeathSaveUseCount 升序 → 被偷资源 升序
→ StrategicHpDeficit 升序 → GrowthHpCredit 降序 → GrowthRewardCount 降序
→ CombatEndedTurn 升序 → PolicyHpDeficit 升序 → HealthResourceCost 升序
→ LongTermResourceValue 降序 → AngerCopiesGenerated 降序 → BoundaryRank
→ OptionalPotionCount → StrategicSold → EnemyHp 升序 → Score 降序 → ActionCount 升序
```

我们搬得动的是「有 STS1 对应物」的这六条，**顺序不变**：

| 键 | 蓝本 | 我们的实现 |
|---|---|---|
| `Won` 降序 | 打赢优先 | 同 |
| `(!Won && !Survives)` 升序 | `Survives = !PlayerDead && ProjectedPlayerHp > 0` | 同（用 `Score.projectedHpOf`） |
| 复活次数 / 被偷资源 | 有 | **未建模**（无复活遗物、无偷窃遭遇） |
| `StrategicHpDeficit` 升序 | `累计掉血 + 最大生命亏空 - 回血` | 同（净掉血 + 最大生命亏空，下界=开局缺的血） |
| `CombatEndedTurn` 升序 | 赢的路线看第几回合结束 | 同（没结束 = ∞） |
| 成长/药水/局外收益等 9 条 | 有 | **未建模** |
| `EnemyHp` 升序 | 敌方剩血少优先 | 同 |
| `Score` 降序 | 基础分 | 同 |
| `ActionCount` 升序 | 动作少优先 | 同（= 搜索深度） |

**为什么不再拿基础分当主键**：基础分里的血量项是**投影血量**（一阶前瞻），
而最终路线该看真实结果；`StrategicHpDeficit` 才是蓝本衡量「这条路线赔了多少血」的轴。

### 17.2 两个必须知道的口径

1. **`Survives` 看的是投影血量**，不是当前血量。少了这一条，
   「这一步走进去会被打死」的节点会被当成还活着（第一次跑测试就红在这里）。
2. **死路之间不比优劣。** `StrategicHpDeficit` 记的是**累计**掉血，所以两条都必死的路线里，
   「早死」的那条反而掉血更少、排得更前。蓝本同样如此（死路不进 `Won` 分支，
   后续键只按数值排）。这是口径，不是 bug；判定「会不会死」靠的是 `Survives`。

### 17.3 实测

默认配置下两个回归场景结果不变（双教徒 10 回合/26 血、单厚怪 21 回合/64 血），
Beam 项 A/B 的四组数字也完全一致 —— 说明在现有场面上新排序与旧排序同序，
属于**结构性对齐**而不是行为改动。改动的价值在：
- 主键从「投影血量 + 掉血」换成蓝本的「战略血量赤字」；
- 新增「同样结果下结束得早的优先」；
- `Survives` 用投影血量，堵住「走进必死」这一类。

测试：128 条全绿。`blocksWhenAttackingWouldGetUsKilled` 的数值按新口径重设过
（原场景两条路都会死，且「早死」反而分低，见 17.2）。


## 18. Boss 分档：蓝本有**两个** boss 概念，只有一个能搬

搞清楚这一点花了点时间，因为二者名字很像但触发源完全不同。

| 概念 | 蓝本判定 | 影响 | STS1 能不能搬 |
|---|---|---|---|
| `_isActEndingBoss` | `BossHpRelief != None`，即**打完这场会回血**（幕末清完回 80% 伤害） | 持续成长 cap 4→32 / 值 150,000→50,000；虚弱「每层省 1 血」→2；力量削减 horizon 4→8；`useLatentSetup` 直接启用；`FutureResourceBeamValue` 只在 Boss 档生效 | ❌ **搬不了**：STS1 打完 Boss 不回血（只给奖励、进下一幕），`BossHpRelief` 永远是 `None` |
| `reservedTurnLayers` | `root.EncounterRoomType == RoomType.Boss ? 8 : 4`（真实房间类型） | 按回合层分节点预算时的**分母**：前 4（Boss 8）层每层分 `剩余/剩余层数`，之后分母兜底为 1 | ✅ 已搬 |

所以「Boss 档」在我们的实现里**只影响每层节点预算的分母**，不碰估值权重。
估值那一侧的 boss 分支在 STS1 永远走标准档，我们没有实现（也不该实现）。

### 18.1 实现

- `SimState.boss` 新字段，`capture()` 里用
  `AbstractDungeon.getCurrRoom() instanceof MonsterRoomBoss` 判定。
  STS1 的 `AbstractRoom` **没有 `roomType` 字段**（反编译确认：只有 `phase`），
  房间类型就是具体类，`MonsterRoomBoss` 等价于 `RoomType.BOSS` 的语义。
- `copy()` / `diff()` 一并带上（A1 克隆对拍要能查到这个字段）。
- `BeamSolver.searchToDepth` 的分母改成
  `max(1, (boss ? 8 : 4) - turn)`，循环上界仍是 `depth`（最多几个回合层）。

### 18.2 实测

`ScoreAbTest.bossReservedLayers`（双教徒，宽度 60，最多 16 回合）：

| boss | 节点上限 | 推进回合层 | 展开 | ms |
|---|---|---|---|---|
| false | 2,000 | 12 | 6,097 | 117 |
| false | 10,000 | 15 | 3,326 | 58 |
| true | 2,000 | 12 | 6,097 | 130 |
| true | 10,000 | 15 | 3,326 | 56 |

**没有差别** —— 因为这两个场面在 3 千多节点就把树搜完了，每层预算根本没咬住。
分母的差别只在「节点上限真的成为瓶颈」的大场面（Boss 战、牌多、分支宽）才体现出来。
这一项属于**结构性对齐**：把蓝本的预算分配公式摆正，等真实 Boss 战再验证收益。

### 18.3 未实现的清单（⚠️ 2026-10-23 更正）

这一节原来写的是「蓝本里仍然没有对应物、因此刻意不实现」。**这个判断是错的。**

当时把「我们没建模」写成了「STS1 没有这个机制」。逐项核对蓝本源码后：

| 项 | 之前写的 | 实际 |
|---|---|---|
| 药水全套（`PotionStrategicCost` / `OptionalPotionCount` / `SmartRequiredHpSaved`） | ⛔ 不适用 | ❌ **未做**：STS1 有药水（火焰/格挡/敏捷/瓶中仙子…） |
| 复活遗物（`DeathSave*`） | ⛔ 不适用 | 🔶 **遗物侧已做**（蜥蜴尾巴，见 §23）；**瓶中仙子**属药水，等药水建模 |
| 被偷资源（`TheftEncounterStrategy` / `UnrecoveredLoot`） | ⛔ 不适用 | ❌ **未做**：STS1 有 **Looter（掠夺者）** 偷金币逃走 |
| 局外收益（`GrowthRewards` / `LongTermResourceValue`） | ⛔ 不适用 | 🔶 **部分**：`GrowthSource` 里就有 **Feed**（吃）；只有 `Feed` 的最大生命增长进了基础分，「成长奖励」这条独立轴未做 |
| 遗物计数估值（`RelicCounterEvaluation`） | ⛔ 不适用 | 🔶 **部分**：战斗内计数真触发；**战后阈值回血**（`PostCombatRelicHeal`：`Meat on the Bone` / `Burning Blood`）未建模 |
| `LatentSetup` 的 STS1 能力牌分值表 | 未做 | ❌ 未做（蓝本按 STS2 卡逐个给分；STS1 的表需要判断，暂走默认分支） |
| `ContextualRankingModel` / `NoveltySearch` / `BeamPortfolio` | M4 之后 | ❌ 未做 |

真正 **⛔ 不适用** 的只有两条（依赖的机制 STS1 确实没有）：

- `ReplayPotentialValue`：依赖 STS2 的附魔重放（STS1 无双发/爆发以外的可重放卡）；
- `BossHpRelief.ActClearHeal`：依赖「清完一幕回复 80% 伤害」，STS1 打完 Boss 不回血
  （`RunEnding` 那一档在 STS1 是适用的，见 §18）。

完整的复刻进度见 `blueprint-parity.md`。


## 19. 真机事故：升级态镜像缺失 → AI「像不会打仗」（2026-10-23）

### 19.1 现象

真机打完一局后的反馈：「不像是具有跨回合战斗的能力」。
翻当天日志（`SlayTheSpire/solver-logs/sts1-combatsolver-2026-10-23.log`）先看到搜索其实是好的：

```
PLAN ready: 56 steps, depth 16, 7504 nodes / 18047 states, 148 ms
PLAN ready: 53 steps, depth 15, ...
PLAN ready: 50 steps, depth 14, ...
```

`depth` = **回合层数**，一路 13–16，说明确实在规划十几个回合。真正的问题在下一行：

```
AUTO end turn (search says end)  energy=1 hand[Body Slam+(cost=0/turn=0,atk), Defend_R(cost=1)]
```

**手里有一张 0 费、可打、伤害等于格挡的 `Body Slam+`，AI 选择结束回合。**

### 19.2 根因：镜像表少了升级态

`MirrorRegistry` 是「牌名 → 效果」的查表，查不到时 `CardPlayer.play` 抛 `ActionRejected`，
搜索的分支被**静默跳过** —— 在 AI 眼里那张牌不存在。

统计后：牌池里只有 9 个 `+` 键没注册，而用户那一局的牌组里正好有 3 个：

| 缺的键 | 升级改了什么 | 后果 |
|---|---|---|
| `Body Slam+` | 1 费 → 0 费 | 0 费免费输出，AI 却当成没有 |
| `Dark Embrace+` | 2 费 → 1 费 | 抽牌引擎永远打不出 |
| `Entrench+` | 2 费 → 1 费 | 格挡翻倍永远打不出 |
| `Barricade+` / `Corruption+` / `Seeing Red+` | 只改费用 | 同上 |
| `Brutality+` | 只加 Innate（开局关键字） | 战斗中无差别 |
| `Limit Break+` | **去掉 Exhaust** | 有差别：不能当消耗牌处理 |
| `Transmutation+` | 生成的是**已升级**无色牌 | 仍未支持（见下） |

### 19.3 修法

1. **补齐 8 个升级态**。费用来自 `SimCard.effectiveCost()`（实机捕获），**不进镜像**，
   所以升级只改费用的牌，效果体直接复用基础版；`Limit Break+` 用 `register` 而不是
   `registerExhaust`（升级去掉了消耗）。依据是 jar 内 `localization/eng/cards.json` 的
   `UPGRADE_DESCRIPTION`，不是记忆。
   `Transmutation+` 仍不支持：`PoolCatalog` 还没有「生成**已升级**牌」的路径，
   打不出会走 `ActionRejected`（不会算错），已登记进已知例外。
2. **加覆盖闸门** `MirrorCoverageTest`：牌池里每张牌 × {基础, 升级} 都必须有镜像，
   例外表必须写明理由、且「已支持的必须从例外里删掉」。这道闸本来能提前拦下这个 bug。
3. **加真机诊断** `MIRROR MISS: <牌名>`：每场战斗把「手牌里没有镜像的牌」各报一次。
   以前只能靠人肉对日志，现在下一次跑局就能直接看到覆盖缺口。
4. **顺手修** `PoolCatalog.Entry.toCard` 丢 `type` 的问题：不传 type 时 `isAttack()` 永假，
   于是「效果生成的牌」在估值（攻击总量 / 易伤窗口）和计数遗物（修女 / 手里剑 / 开信刀）里全算错。

### 19.4 教训

「未知语义显式拒绝」这条硬规则是对的（宁可打不出也不许算错），但**拒绝之后要能被看见**：
查表 miss 在生产里不能是静默的，否则表现成「AI 变傻」，而不是「有张牌没支持」。


## 20. 计划执行模型 = 蓝本的 FullAuto（2026-10-23 考证修正）

> ⚠️ 本节第一版写「蓝本不自动打牌」——**错了**。蓝本有全自动模式（`SearchReason.FullAuto`），
> 我们的 AutoPilot 就是它的对应物。原始考证修正记入 §22.4。

- `Runtime/SolverProgress.cs:3-12` 定义 `SearchTakeoverKind { ApplyCurrentTurn, AdoptRoute }`，
  这两个是**手动面板按钮**发起的请求（`RequestApplyCurrentTurn()` / `RequestAdoptRoute(seed)`，
  `PlayerTurnSetupPatches.cs:374 / 397`）—— 手动模式的「立即交付」路径，含早停。
- **全自动模式**：`SolverController.cs:1485 SetFullAuto` → 每回合
  `PrepareAutomaticSearchForTurn`（:1558）→ `SearchReason.AutoTurnStart` 自动重搜；
  部署 `StartDeployment`（:2799）→ `DeployCurrentTurn` **只执行 `action.Turn == StartTurnNumber`
  的动作**（手里是整场路线，执行只取本回合）；
  漂移 → `DeploymentDrift` 重搜、本回合计划用完 → `PlanExhausted` 重搜。
- 搜索触发原因全集（`SolverController.cs:20-28`）：
  `SearchReason { Manual, AutoTurnStart, Deploy, FullAuto, DeploymentDrift, PlanExhausted }`。

我们的 `AutoPilot` 采取 FullAuto 同构语义：**每回合重新规划、只执行本回合、
指纹校验不符丢弃重算**（对应 DeploymentDrift / PlanExhausted）。这与蓝本一致，不需要改。
手动模式的 `ApplyCurrentTurn` 早停不搬（判定见 §22.4）；
`AdoptRoute`（冻结路线）对应面板上的「采纳整条路线」按钮，属于 M4-1 面板，尚未实现。

### 20.1 本轮补的两件「让故障可见」的东西

1. **回归测试** `playsTheZeroCostUpgradedBodySlamInsteadOfEndingTheTurn`：
   重现真机那一手（0 费 `Body Slam+` + 1 能量 + 10 格挡），断言「本回合计划里必须用上它」。
   注意**不钉死顺序** —— 最优解是「先 Defend 把格挡顶到 18、再用 0 费 Body Slam 打 18」，
   所以第一步是 Defend 也算对。钉的是「那张牌看得见、用得上」。
2. **搜索侧拒绝日志** `SEARCH REJECT: <牌名> -> <原因>`：每次搜索、每张牌、每个原因各一条。
   只报**语义缺失**（没有镜像 / 不支持的费用 / 目标非法），
   「能量不够 / 被缠绕 / 下标越界」这类正常拒绝不报。

   加完立刻见效：写回归测试时我把牌名写成 `"Body Slam+"` 且 `upgraded=true`（正确写法是
   `"Body Slam"` + `upgraded=true`），日志当场打出行
   `SEARCH REJECT: Body Slam++ -> no mirror for card Body Slam++` —— 一眼定位。

### 20.2 排查真机问题的固定路径

1. 先看 `PLAN ready: N steps, depth M` —— `depth` 是**回合层数**，`M` 很小或 `steps` 很短，
   说明是搜索/预算问题。
2. 再看 `MIRROR MISS` / `SEARCH REJECT` —— 有没有牌在模型里根本不存在。
3. 最后看 `AUTO play X -> 目标` 的实际序列，配合 `DIFF MATCH/MISMATCH` 判断是模型错还是估值错。


## 21. 「为什么蓝本想 120 秒，我们 0.2 秒」—— 量化回答

用户真机对比：蓝本简单战斗约 1 秒、常规战斗会算满 120 秒；我们基本不超过半秒。
先把口径和账算清楚。

### 21.1 节点口径：一致

`_run.Expanded++` 在 `CombatBeamSolver.ExpansionPlan.cs:34`，位置是 `TryEnterExpansion` 里、
按**父节点**加一（日志里 `serial_parent` / `parallel_parent`）。我们的 `lastNodes` 也是父节点。
所以「7,504 节点」和蓝本的「几十万节点」是同一个量。

### 21.2 我们的预算是**用不完**，不是被掐断

`ScoreAbTest.budgetUsage`（回合层不设上限、节点上限 12 万）：

| 宽度 | 场面 | 回合层 | 展开 | 占预算 | 上限命中 | ms |
|---|---|---|---|---|---|---|
| 60 | 双教徒 | 15 | 3,326 | 2.8% | 否 | 58 |
| 60 | 单厚怪 | 25 | 5,653 | 4.7% | 否 | 73 |
| 135 | 双教徒 | 15 | 7,375 | 6.1% | 否 | 138 |
| 135 | 单厚怪 | 22 | 10,881 | 9.1% | 否 | 152 |
| 240 | 双教徒 | 15 | 12,818 | 10.7% | 否 | 289 |
| 240 | 单厚怪 | 22 | 19,187 | 16% | 否 | 313 |

**每一档都是「树搜完了」（上限命中=否）**，最多用到 16% 的节点预算。
再加预算只会重新走一遍同样的树。

### 21.3 差距从哪来：动作空间差一个数量级

工作量 ≈ `宽度 × 每回合出牌层数 × 回合数`。

| 因子 | 我们（STS1 铁甲战士） | 蓝本（STS2） |
|---|---|---|
| 单节点分支 | 手牌 ~5 张 × 目标 1–3 个 ≈ **10** | `MaxCardBranchesPerNode` 上限 **72**；还有药水、选牌、堆选择 |
| 每回合出牌层数 | ~4（3 点能量，打 3–5 张） | 蓝本自己的日志里出现 `play_depth 173→248`（0 费引擎），正常回合也更高 |
| 回合层 | 战斗真实长度（10–25） | 无上界，病态路线能拖很久 |

蓝本 120 秒里相当一部分烧在**不收敛的路线**上 —— 它自己的注释就是这么写的
（「整份节点预算烧完也没推进到下一回合」）。那不是「想得更深」，是「在病态路线上打转」。

我们简单战斗只要 4 ms（`PLAN ready: 4 steps, depth 2, 259 nodes`），因为确实没什么可搜的。

### 21.4 本轮修掉的一个真偏差

`depth` 之前被当「最多 16 个回合层」传下去（取自 `SetupValueHorizonTurns`，
那是 UI 投影用的，不是搜索上界）。蓝本的回合层循环**没有上界**：
`while (frontier.Count > 0 && ... && _run.Expanded < MaxExpandedNodes && !timeBudgetReached)`
（`CombatBeamSolver.Phases.cs:1479-1485`）。后果是长战斗在第 16 层被硬截断 ——
「单厚怪」场景从 16 层（10,457 节点）变成 **25 层**（5,653 节点，每层预算更小反而更省）。

现在 `depth` 只是安全上界（`SAFETY_MAX_TURN_LAYERS = 500`），正常不咬住。

### 21.5 想「想得更久」该调什么

只有**宽度**有意义（回合层数由战斗长度决定，节点上限根本吃不满）：

| 宽度 | 双教徒（旧估值→新估值） | 单厚怪 |
|---|---|---|
| 60 | 9 回合 / 23 血 | 21 回合 / 64 血 |
| 135（面板「极高」） | **7 回合 / 29 血** | 21 回合 / 64 血 |
| 240 / 512 | 8 回合 / 28 血 | 21 回合 / 64 血 |

→ 面板档位里的「极高 = 135」已经是最优点，再宽没收益。
真要像蓝本那样「算满 120 秒」，得先让**动作空间**变丰富（药水、更多可选项），
而不是把同一棵树重搜。

### 21.6 日志现在直接给出预算用量

```
PLAN ready: 56 steps, depth 20 turns, 7504/120000 nodes (6%, tree exhausted) / 18047 states, 148 ms
```

`tree exhausted` = 这份预算够用了；`node limit hit` = 是预算把它掐断的，才需要考虑加宽。


## 22. 搬 `ConsiderCurrentTurnCandidate`（执行层答案的选择顺序）

### 22.1 蓝本原文（`CombatBeamSolver.Phases.cs`）

- `FindCurrentTurnBoundary(node)`（:313）：从节点往上走，找到 `Outcome.Turn == _startTurnNumber`
  的那个祖先，也就是「结束本回合前的那一步」。
- `ConsiderCurrentTurnCandidate(node)`（:372）：把边界节点登记成候选，判据是
  `actionCount > 0 && 走到回合边界 && !playerDead && projectedPlayerHp > 0`
  （`IsCurrentTurnCandidate`，:2458）。比当前候选好就替换（`SolverInterimResultOrdering.IsBetter`）。
- 采用（:2218）：`adoptedNode = currentBestNode ?? currentTurnCandidateNode`，
  `finalPool = [adopted]`。

也就是说 `ApplyCurrentTurn` 的答案优先级是：
**完整胜利 → 本回合能安全走完的最好那条 → 其余**。

### 22.2 我们原来的做法

`best = bestTerminal ?? 前沿里最好的叶子`。前沿叶子可能是**回合中途**的状态，
它被选中纯粹因为中途分高，并不保证「这一回合能平安过完」。

### 22.3 现在

```java
SearchNode best = bestTerminal != null ? bestTerminal
        : (currentTurnCandidate != null ? currentTurnCandidate : bestOf(frontier));
```

`currentTurnCandidate` 在**第 0 个回合层收尾时**登记（对应蓝本的 `_startTurnNumber`）：
遍历该层 `ended` 里的每个节点，取它的父节点（= 结束回合前的那一步）作为边界，
筛掉 `hp<=0` 与 `projectedPlayerHp<=0`（`Score.projectedHpOf`），
按 `better()` 取最好的一条。

两个回归场景结果不变（双教徒 10 回合/26 血、单厚怪 21 回合/64 血）—— 因为这两个场景
都能搜到完整胜利，第一优先级就已经命中。

### 22.4 早停为什么不搬（2026-10-23 考证修正）

> ⚠️ 本节第一版把早停定性为「无请求时搜索跑满整场 + 我们拿不到整场路线」——**理由有错，结论不变**。
> 错在：早停手里有整场胜利时交付的就是整场路线（`currentBestNode` 优先）；
> 真正的理由是**早停只挂在手动面板路径上，FullAuto（我们的对应物）不点那个按钮**。

**结论：搜索保持整场战斗规划，不做「候选一出现就 break」。**

#### 早停的准确定义

- 三个检查点 —— `BeforeNoveltyParent`（`Phases.cs:1445`）、playDepth 迭代开头（`:1554`）、
  回合层收尾（`:2154`）—— 条件全部是
  `_interaction.CurrentTakeoverRequest?.Kind == ApplyCurrentTurn && (currentBestNode != null || currentTurnCandidateNode != null)`。
- 触发入口是**手动面板按钮**「执行本回合」（`PlayerTurnSetupPatches.cs:374`，
  日志 `UI_ACTION action=turn_setup_apply_current_turn`；`SearchInteractionState.RequestApplyCurrentTurn`
  写入 `_takeoverRequest`，`SolverProgress.cs:85-103`）。
- 命中后 `adoptionReached = true; timeBudgetReached = true; break` —— 提前收工，
  交付 `adoptedNode = currentBestNode ?? currentTurnCandidateNode`（:2218）：
  **整场胜利路线优先，其次「本回合能安全走完」的候选**。
  两个候选的判据不同：`SolverInterimResultOrdering.IsCompleteVictory`（`SolverInterimResultOrdering.cs:5`）
  要求 `allEnemiesDead`（整场打完）；`IsCurrentTurnCandidate`（`Phases.cs:2458`）只要求走到回合边界。

#### 我们对应的是 FullAuto，不是手动按钮

蓝本执行层有 6 种搜索触发原因（`SolverController.cs:20-28`）：
`SearchReason { Manual, AutoTurnStart, Deploy, FullAuto, DeploymentDrift, PlanExhausted }`。

- **`SetFullAuto`**（`SolverController.cs:1485`）：开全自动 → 已有最新结果直接部署
  （:1549 `LatestStamp == current`），否则 `RequestSearch(FullAuto)`（:1555）。
- **每回合自动重搜**：`PrepareAutomaticSearchForTurn`（:1558）→ `SearchReason.AutoTurnStart`。
- **部署只执行本回合**（决定性证据，`StartDeployment` :2799-2827）：
  `DeployCurrentTurn(...)` 只部署 `action.Turn == result.StartTurnNumber` 的动作
  —— **手里是整场路线，执行只取本回合**。
- **漂移重搜**：执行中状态漂移 → `DeploymentDrift`；本回合计划用完 → `PlanExhausted`
  （`StartDeployment` 里 `hasCurrentTurnPlan == false` → `RequestSearch(PlanExhausted)`，:2805-2816）。

我们的 AutoPilot 与 FullAuto **逐项同构**：每回合重搜（= AutoTurnStart）、只执行本回合
（= DeployCurrentTurn 的回合过滤）、指纹校验不符丢弃重算（= DeploymentDrift / PlanExhausted）。
区别只在触发方式（我们全自动无面板）。

#### 为什么 FullAuto 不需要早停

早停服务的场景是「用户手动打牌、等不了 120 秒预算」——点按钮后立即拿现有最好答案。
FullAuto 的搜索周期里有大量空窗（敌人回合 + 玩家看牌期间），**等得起**；
且我们实测树穷尽、预算用不完（§21），「等完」与「早停」拿到的是同一个
`adoptedNode`（三档选择顺序已在 §22.3 复刻）。

改动：`blueprint-parity.md` §2 该行、§3 执行层行、§4 待办第 2 项 —— 状态维持「不搬」，理由修正。
本节记录判定依据与 FullAuto 同构性考证。


## 23. 死亡豁免：蜥蜴尾巴（A｜蓝本源码 + STS1 字节码，2026-10-23）

> 这一节是上一轮「⛔ STS1 无对应机制」被用户纠正后的第一批补做项。
> 教训写在 §18.3：**⛔ 只能用在「蓝本机制依赖的游戏机制在 STS1 确实不存在」上**。

### 23.1 蓝本原文

| 位置 | 内容 |
|---|---|
| `ActEndingBossPolicy.DeathSavePremium(hp)` | `max(0, hp) * 900 / 100` = **9 × 回血量** |
| `ActEndingBossPolicy.DeathSaveBeamCost(hp)` | `hp + DeathSavePremium(hp)` = **10 × 回血量** |
| `ActEndingBossPolicy.StrategicHpDeficit` | `累计掉血 + 最大生命亏空 − PersistentValue(recoveredHp − deathSaveHpRestored) + DeathSavePremium(...)` |
| `StateEvaluation.cs:172` | `score -= DeathSaveBeamCost(deathSaveHpRestored + threat.DeathSaveHpRestored) * hpWeight` |
| `ProjectedDeathPrevention.TryRevive` | **先** 瓶中仙子（可多次），**再** 蜥蜴尾巴（一次） |
| `FinalPlanOrdering` 第 3 键 | `DeathSaveUseCount` 升序（排在 `Survives` 之后、`StrategicHpDeficit` 之前） |

两条关键语义：

1. **复活的血不算这条路线挣的。** 所以 `StrategicHpDeficit` 里先把 `deathSaveHpRestored`
   从「有效回血」中扣掉，再单独收 9 倍溢价 —— 净效果等价于 `净掉血 + DeathSaveBeamCost`。
2. **溢价用倍数、不用等额。** 蓝本注释写明原因：HP 不是复活唯一买到的东西，
   两场 boss 连打时场上还压着上百点「敌方血等价物」的节奏价值，一比一溢价会输给它。
   但 9 倍仍远低于 `VictoryBonus=1e10` / `DeathPenalty=-1e12`，
   所以「只有死一次才能赢」的路线还是会毫不犹豫地花掉它。

### 23.2 STS1 原版语义（`LizardTail.class`，javap 核实）

```java
public void onTrigger() {
    flash();
    addToTop(new RelicAboveCreatureAction(AbstractDungeon.player, this));
    int healAmt = AbstractDungeon.player.maxHealth / 2;
    if (healAmt < 1) healAmt = 1;              // 最少回 1
    AbstractDungeon.player.heal(healAmt, true);
    setCounter(-2);                            // → usedUp() + counter = -2
}

public void setCounter(int setCounter) {
    if (setCounter == -2) { usedUp(); this.counter = -2; }
}
```

由字节码得到四个可核对的结论：

- 回血量 = `max(1, maxHp / 2)`（整除，**截断**）。与蓝本 `Math.Max(1, maxHp * 50/100)` 一致。
- 是从 **0** 起 `heal`，不是「在当前血量上加」。原版 `damage()` 会先把血量夹到 0，
  所以复活结果恒为 `50% maxHp`，与「受到多少溢出伤害」无关。
- **`counter == -1` 是「还没用过」**（`AbstractRelic` 默认值）。`onTrigger()` 末尾
  `setCounter(-2)` → `usedUp() + counter = -2`。
- 触发点不在遗物自己身上，而是 **`AbstractPlayer.damage()` 的死亡分支**
  （字节码 `952-1102`，逐句还原）：

```java
if (currentHealth < 1) {
    if (hasRelic("Mark of the Bloom")) goto 真死;        // 960-967：整个救命分支被跳过
    if (hasPotion("FairyPotion")) {                      // 970-1056：瓶中仙子优先
        ... p.flash(); currentHealth = 0; p.use(this); destroyPotion(p.slot); return;
    } else if (hasRelic("Lizard Tail")
            && getRelic("Lizard Tail").counter == -1) {   // 1059-1083
        currentHealth = 0;                                // 先归零，再触发
        getRelic("Lizard Tail").onTrigger();
        return;
    }
    真死：isDead = true; deathScreen = new DeathScreen(...); currentHealth = 0;
}
```

两条容易踩错的：

1. **绽放之印不是「消耗掉但不回血」，而是整段救命逻辑都不执行** —— 尾巴不会被消耗。
2. **瓶中仙子优先，而且是 `else if`**（不是「用完仙子再用尾巴」的那种兜底）。

### 23.2.1 `heal` 里还有两个改治疗量的钩子

`AbstractCreature.heal(amount, showEffect)` 的结算顺序：

```
if (isEndless && hasBlight("FullBelly")) amount /= 2;   // 无尽模式，正常局不适用
if (isDying) return;
for (relic : player.relics)  amount = relic.onPlayerHeal(amount);
for (power : powers)         amount = power.onHeal(amount);
amount = min(amount, maxHealth - currentHealth);
if (amount <= 0) return;
currentHealth += amount;
```

- 遗物侧全库扫描（`grep -rl onPlayerHeal`）**只有两个**实现：
  `MagicFlower`（战斗中 `round(amount * 1.5)`）与 `MarkOfTheBloom`（返回 0，但已被上面的分支挡在前面）。
- power 侧全库扫描**没有任何实现**（只有 `AbstractPower` 的空实现）。

所以蜥蜴尾巴的实际复活量是 `min(maxHp, round(max(1, maxHp/2) × (有魔法花 ? 1.5 : 1)))`。

### 23.3 我们的实现

| 位置 | 作用 |
|---|---|
| `mirror/DeathSaveRelic.applyIfLethal` | 四个前置判断（血量 / 已用 / 绽放之印 / 有尾巴）→ 算量 → `hp = min(maxHp, 量)`，记账 |
| `mirror/DeathSaveRelic.healedAmount` | 复刻 `onPlayerHeal` 链（只有魔法花生效） |
| `sim/CombatFlow.hitPlayer` | 怪物攻击打完玩家后调用 |
| `mirror/Effects.loseHp` | 自伤（放血 / Offering / 血液沸腾）后调用 |
| `mirror/hooks/PowerHooks`（`Combust`） | 回合末燃烧自伤后调用 |
| `search/Score.baseTotal()` | `-= deathSaveBeamCost(deathSaveHpRestored) * HP` |
| `search/BeamSolver.strategicHpDeficit()` | `+ deathSaveBeamCost(...)`（= 蓝本末两项合并后的等价式） |
| `search/BeamSolver.better()` | `DeathSaveUseCount` 词典序键，排在 `Survives` 之后 |

**为什么要在仿真里真的拦一次，而不能只在估值里减分**：原地复活会改变**后续每一条路线**的
可行性 —— 本来必死的线变成能走。只在打分里减分，就等于让搜索「看不见」这些线。

回归测试 `mirror/DeathSaveRelicTest`（8 条）：复活到 50%、没尾巴就是死、只能用一次、
没致死不消耗、自伤路径同样触发、绽放之印整段失效且不消耗、魔法花把 40 抬到 60、
计价对齐蓝本三个数字（`Premium(40)=360`、`BeamCost(40)=400`）。

### 23.4 实机对拍的坑：`capture()` 会重置

`SimState.capture()` 每回合重建快照，`deathSaveUsed` 默认 `false` —— 不补读的话，
仿真会允许**一场战斗里复活第二次**。所以 `capture()` 里对蜥蜴尾巴单独读
`r.counter != -1`（= 原版 `counter == -1` 才允许触发）。

`deathSaveHpRestored` 则**读不回来**（游戏不记这笔账）。蓝本的 `SimulatedCombatState`
是整场战斗持久对象，所以它记得；我们每回合重抓，历史回血量会丢。
影响有限：`deathSaveUsed` 已经挡住了第二次使用，丢的只是「历史复活成本没计入后续回合的打分」，
偏差方向是**低估已付出的代价**，不会让 AI 主动去送死。

### 23.5 还没做

- **瓶中仙子**（`Fairy in a Bottle`）：属药水，与 §18.3 的药水建模一起做。
  接的时候注意原版是 `if (hasPotion) {...} else if (hasRelic) {...}` ——
  **仙子优先**，不是「仙子用完再用尾巴」。
- **死亡时清格挡**（`if (currentBlock > 0) loseBlock()`）：我们的 `hitPlayer` 是
  「先扣格挡再扣血」，一次伤害同时打穿格挡和血量时格挡已经归零，所以这条在单段攻击下
  不可能有残留。只有「格挡没吃满就致死」的极端情形（如自伤绕过格挡后，此前还有格挡）
  会有差异 —— 自伤那条路径原版也不清格挡，暂记。
- 同一段代码**同时**管着瓶中仙子与蜥蜴尾巴的先后，接药水时两件事一起改。


## 24. 战后阈值回血：`PostCombatRelicHeal`（A｜蓝本源码 + STS1 字节码，2026-10-23）

### 24.1 蓝本原文

`ActEndingBossPolicy.PostCombatRelicHealProfile`（`ActEndingBossPolicy.cs:34-82`）：

```csharp
record PostCombatRelicHealProfile(int UnconditionalHeal, int WoundedHeal, int WoundedHpPercent) {
    int HealFor(int finalHp, int finalMaxHp)          // 玩家看到的：含阈值回血
    int MonotoneHealFor(int finalHp, int finalMaxHp)  // 排序允许计的：只有无条件回血
}
```

用法（`FinalPlanOrdering.cs:73-85` + `BeamRetentionPolicy.Ranking.cs:137-148`）：

```csharp
int relicHeal = RankedPostCombatRelicHeal(profile, completeVictory, PlayerHp, PlayerMaxHp);
int strategicHpDeficit = StrategicHpDeficit(hpLost, maxHpDeficit,
    RecoveredPlayerHp + relicHeal,          // ← 只有打赢才有
    bossHpRelief, DeathSaveHpRestored);
```

**关键设计（蓝本注释写死了）**：

> `min(heal, maxHp - finalHp)` 对 `finalHp` **单调不减**，所以「结束血量更高永不更差」成立，
> 支配测试和搜索上界都靠这个不变式。
> **带骨肉的阈值回血破坏它** —— 比阈值高 1 点血反而更差。
> 所以阈值回血只报告给玩家、**不进排序**，直到 Pareto 测试重构成比较战后血量。
> 这样做只会让解算器在阈值附近**略微高估**血量价值，绝不会反向。

### 24.2 STS1 原版语义（javap 核实）

三个遗物的结算点（`AbstractRoom.endBattle()`，字节码 `0-56`）：

```java
public void endBattle() {
    isBattleOver = true;
    if (player.currentHealth == 1) UnlockTracker.unlockAchievement("SHRUG_IT_OFF");
    if (player.hasRelic("Meat on the Bone"))
        player.getRelic("Meat on the Bone").onTrigger();   // ① 先结算带骨肉
    player.onVictory();                                     // ② 再走每个遗物的 onVictory
    ...
}
```

**顺序很重要**：带骨肉**先**结算，所以它的阈值判据用的是战斗结束时的原始血量，
不是被燃烧之血治疗后的血量。与蓝本注释里的
"it settles first, so its threshold is tested against the HP the route actually ends on" 一致。

三个遗物的 `onTrigger`/`onVictory`：

| 遗物 | 回血 | 条件 | 字节码 |
|---|---|---|---|
| `Burning Blood`（燃烧之血，Ironclad 初始） | 6 | 无条件（只要 `currentHealth > 0`） | `bipush 6` |
| `Black Blood`（黑血，升级版） | 12 | 同上 | `bipush 12` |
| `Meat on the Bone`（带骨肉） | 12 | `currentHealth <= maxHealth / 2.0f` 且 `> 0` | `bipush 12` |

带骨肉的阈值判据是 **float 比较**（`(float)hp <= (float)maxHp / 2.0f`）。
我们的整除截断（`maxHp * 50 / 100`）在边界上同判：
奇数 maxHp=81 时，原版 40 ≤ 40.5 为真、41 ≤ 40.5 为假，与 `hp ≤ 40` 一致。

### 24.3 我们的实现

| 位置 | 作用 |
|---|---|
| `mirror/PostCombatRelicHeal.of(relics)` | 从 `SimState.relics` 读出三件遗物（燃烧之血 6 / 黑血 12 / 带骨肉 12@50%） |
| `PostCombatRelicHeal.healFor` | 玩家视角：含带骨肉阈值回血 |
| `PostCombatRelicHeal.monotoneHealFor` | 排序视角：**只有无条件回血**（`clamp(unconditional, 0, maxHp - hp)`） |
| `BeamSolver.bindRoot` | 战斗开始时固定这份画像 |
| `BeamSolver.strategicHpDeficit` | 打赢时 `netLost - relicHeal`；没打赢不减 |

**为什么是 `clamp(..., 0, maxHp - hp)`**（headroom）：回血最多补到满血，
不可能靠多打几回合把赤字刷成负的。这一层也保证了 `Math.max(netLost - relicHeal, floor)`
里的 `floor` 不会被击穿。

### 24.4 只做了主路径，没做阈值那条通道

蓝本还有一条独立通道：`StateEvaluation.cs:199-205` 里
`relicCounters.HealingHpCredit = PersistentValueOfRecoveredHp(HealFor - MonotoneHealFor)`，
即**把带骨肉的阈值回血折算成血量 credit，只对满足遗物计数条件的路线生效**。

这条依赖 `RelicCounterEvaluation`（遗物计数估值）那套基础设施，我们的计数遗物是
「在仿真里真触发」而不是估值，架构不同。先不做，记为待办。
影响方向：`Meat on the Bone` 在血量卡阈值附近的路线会略微被低估。
对「血更多永不更差」的不变式**没有破坏**（这正是不用 `healFor` 进排序的原因）。

### 24.5 还没做

- 阈值回血的 `HealingHpCredit` 通道（见 §24.4）。
- `Mark of the Bloom` 会让所有 `heal` 静默失效 —— `BurningBlood.onVictory`
  里的 `player.heal(6)` 会被 `onPlayerHeal` 链归零。当前没建模这个，
  偏差方向是「我们以为会回血、实际不回」（偏低估计回血，安全方向）。


## 25. 药水镜像层（第一批）—— 4 张 + 动作空间（A｜蓝本源码 + STS1 字节码，2026-10-23）

> 之前我把「药水」记成「⛔ STS1 无对应机制」是**错的**（见 §18.3）。
> 这是纠正后的第一批补做：**镜像层**（药水怎么生效），不含估值策略（`PotionStrategicCost`
> 那一套需要玩家在侧栏填额度，属 M4）。

### 25.1 蓝本的药水是一等动作

`CombatPlan.cs:8` 的 `PlanActionKind`：

```csharp
enum PlanActionKind { PlayCard, UsePotion, EndTurn }
```

**喝药和打牌同级**，不是「没牌可打才喝药」。我们的 `PlanAction.Kind` 照搬了三件套。

### 25.2 STS1 原版语义（javap 逐个核实）

| 药水 | 运行时 ID | 效果 | 目标 | 能主动用 |
|---|---|---|---|---|
| 火药水 | `"Fire Potion"` | 20 伤害 | 敌人 | ✅ |
| 格挡药水 | `"Block Potion"` | 12 格挡（走 `GainBlockAction`） | 自己 | ✅ |
| 能量药水 | `"Energy Potion"` | +2 能量 | 自己 | ✅ |
| 瓶中仙子 | `"FairyPotion"` | 死亡时自动回 `max(1, (int)(maxHp × 0.3f))` | 自己 | ❌ |

⚠️ **ID 格式不统一**：三张带空格、瓶中仙子**不带**。记错的话对拍一直失配且看不出原因。

**火药水的伤害管线**（`FirePotion.use`）：

```java
DamageInfo info = new DamageInfo(player, potency /*20*/, DamageType.THORNS);
info.applyEnemyPowersOnly(target);        // 只应用**受击方**能力
addToBot(new DamageAction(target, info, AttackEffect.FIRE));
```

`applyEnemyPowersOnly` 从 `output = base` 开始，只遍历 `target.powers` 改写输出。
所以：**易伤 ×1.5（向下取整）生效，玩家的力量不生效，玩家的虚弱也不打折**
（虚弱挂在攻击者身上，这里不走攻击流程）。

**瓶中仙子**（`FairyPotion`）：

```java
public void use(AbstractCreature target) {
    float pct = potency / 100.0f;                              // 0.30
    int healAmt = (int)(player.maxHealth * pct);               // f2i = 向零截断
    if (healAmt < 1) healAmt = 1;
    player.heal(healAmt, true);                                 // 从 0 起算（调用方先归零）
    AbstractDungeon.topPanel.destroyPotion(this.slot);
}
public boolean canUse() { return false; }                       // 不能主动用！
```

### 25.3 我们的实现

| 位置 | 作用 |
|---|---|
| `mirror/Potions` | 4 张的语义 + `canUse` / `needsTarget` / `fairyHealAmount` |
| `sim/SimState.potions` | 槽位列表（空格 = `null`），`potionSlots` 上限 |
| `sim/SimState.capture()` | 读 `p.potions`（含空槽），`potionSlots = potions.size()` |
| `search/PlanAction.Kind.USE_POTION` | 蓝本 `PlanActionKind.UsePotion` |
| `BeamSolver.potionNode` + `expandTurnLayer` | 药水与打牌同级展开；火药水按存活敌人分目标 |
| `DeathSaveRelic.applyIfLethal` | **接上瓶中仙子**：`fairy 优先，else if 蜥蜴尾巴` |

**未登记的药水**走 `ActionRejected`，搜索里报 `SEARCH REJECT: -1 -> Potion X -> no mirror`
—— 覆盖缺口会出现在日志里，不静默算错（D2）。

### 25.4 蓝本里**没有**搬的部分（估值策略）

蓝本还有 8 个药水文件（`PotionUsePolicy` / `PotionValuationRegistry` /
`SmartPotionBound` / `BlockPotionInsertion` / `PotionChoiceContinuation` /
`PotionRewardOutlook` / `PotionChain` / `PotionStrategySnapshot`，共约 1100 行），
做的是「**该不该喝**」的估值：

- `StrategicHpCost`：按药水档次折算战略代价（Token 免费，其他至少 baseline）
- `RequiredHpSaved = potionCount × PotionMinimumHpSaved`
- `HpSaved = max(0, potionFree赤字 − potionRoute赤字)`
- `SmartRequiredHpSaved`：按 `BossHpRelief` 折算后的门槛

这套依赖**玩家在侧栏填额度**（`SolverSettings.GrowthBudgets`，默认 0），
不做 UI 就等于不算收益。所以留到 M4，本轮只做正确性。

### 25.5 还没做

- 剩下 11 张药水：`SmokeBomb`（逃跑结局）/ `DistilledChaos`（自动打顶）/ `EntropicBrew`（槽位）
  三张缺容器；`Focus`/`PotionOfCapacity`/`EssenceOfDarkness`/`StancePotion`/`Ambrosia`/
  `BottledMiracle`/`BlessingOfTheForge`/`CunningPotion` 是别的角色的池（铁甲战士拿不到）；
- `BlockPotionInsertion`（把药水插进已解路线）、`PotionChoiceContinuation`（药水开出的选牌）；
- 估值策略（见 §25.4）。

## 26. 保留策略第一批：排序第三键 + 边界多样化（A｜蓝本源码，2026-10-23）

> 蓝本 `Retention` 族实际规模：9 文件约 1.3 万行（比交接文档记的 5060 大）。
> 按依赖拆批：批 1 = 边界多样化、批 2 = 排序第三键、批 3 = Pareto cohort、
> 批 4 = 药水配额、批 5 = 承诺代表、批 6 = OrderedMutation（依赖 NoveltySearch）。
> 本节记录已完成的批 1 + 批 2。

### 26.1 排序第三键 `offensiveProgressValue`（批 2）

蓝本 `CompareBeamRankOrder`（`BeamRetentionPolicy.cs:20-41`）三键：
`beamRankScore 降 → actionCount 升 → offensiveProgressValue 降`。前两键已有，第三键补齐。

量的来源（`CombatBeamSolver.StateEvaluation.cs:448-451`）：

```
offensiveProgress = 力量潜力 + 延迟伤害 + 反应伤害 + (活牌里有全身撞击 ? max(0, 格挡) : 0)
```

STS1 Ironclad 逐项对应（`BeamSolver.offensiveProgressOf`）：

| 项 | 蓝本 | 我们的对应 |
|---|---|---|
| 力量潜力 | `StrategicEffectModel.cs:499` `Damage(力量 × (AttackHits ?? AttackPlays), enemyHp)` | 力量 × attackHits（可负，无 clamp） |
| AttackHits | `CardMechanismFacts.cs:44-55`（无飞刀主干 = `ceil(命中段数 × 可达牌数 / 活牌数)`） | 同公式，命中段数查表：双持打击 2、回旋飞剑 3，其余 1（X 费动态段数按 1 保守） |
| 可达牌数 | `StrategicEffectModel.cs:306-322` 投影（`SetupValueHorizonTurns=16`） | 同公式 |
| 延迟伤害 | 敌人 poison/demise/doom（:427-443） | 恒 0 —— 铁甲战士无施加源 |
| 反应伤害 | `SleightOfFlesh`（:444-446） | 恒 0 —— STS2 特有 |
| 全身撞击 | 活牌有 `BodySlam` → 加当前格挡（:447,451） | 活牌（手+抽+弃，:124-128 顺序）有 `Body Slam`(+ 同） |

`totalAttackValue` 我们用牌面伤害和（蓝本 `CardChoiceSupport.CardValue` 含效果价值，
语义同向，只影响 remainingTurns 的粗估精度）。**不进分数** —— 只做 beam 排序第三键
与多样化分组键，与 score 里的成长项（LatentSetup / RetainedAttackGrowth）不重复计价。

### 26.2 边界多样化 `DiversifyOrdinaryBeamBoundary`（批 1）

蓝本 `BeamRetentionPolicy.cs:47-165`，唯一调用点 :1704（beam 剪枝入口，截断之后）。
语义：在 rankedPool 尾部定位「最后一个已入选的普通席位」（boundary），以主键
（`beamRankScore` + `actionCount`）向两边扩出并列段，然后：

1. 段内按**已用药水数**分桶（`SearchNode.potionsUsed`，路线中 USE_POTION 计数）；
2. 每桶「部分入选」（1 < 入选 < 桶大小）时，按 offensiveProgress **降序分组、轮转占位**
   —— 高推进组的代表（含被截掉的）换进席位，单一推进值不再垄断并列段；
3. 段内出现「已打赢」节点（`IsCompleteVictory`）→ 整体放弃（终局交给最终排序策略）。

我们的实现挂在 `prune` 截断之后；required 传空集（还没有策略强制保留）、
`finalQualityFirst=false`（这条路径只服务 beam 剪枝）。

**刻意不搬**：`OrderOrdinaryBeamTacticalCohorts` 战术重排 —— 依赖
`ZeroCostPlayableCount` / `ReachableHandValue` / `HandCount` 三个快照量，未建模；
`includeSingleProgressGroup` 实验开关（BaseScoreOnly 专用）。

### 26.3 验证

- 新增 `BeamRetentionPolicyTest` 5 条：推进量计算（力量×命中段数+BodySlam 格挡 = 25 的手工推演）、
  无力量/无撞击 = 0、排序第三键、轮转（[B,A,D] 手工推演）、分桶隔离。
- 全量 221 条通过（216 旧 + 5 新），现有搜索行为测试无一受影响。

## 27. 0 费开局补搜（A｜蓝本源码，2026-10-23）

> 对照表 §4 第 4 项拆开后的可搬部分。蓝本一条链：
> `RunZeroCostOpeningRescue`（`CombatSearchCoordinator.PostSearch.cs:352-392`，后处理第 4 趟）
> → `OpeningNoCostContinuationSource`（`OpeningNoCostContinuationSource.cs`）
> → `BuildOpeningNoCostPrefixes`（`CombatBeamSolver.Expansion.Opening.cs:1028-1064`）
> → 每条前缀各跑一个 `CombatBeamSolver(fixedPrefixActions: prefix, maximumPotionUses: 0)`
> → `ApplyFixedPrefix`（`CombatBeamSolver.Phases.cs:2296-2400`）。

### 27.1 固定前缀回放（前置件）

蓝本 ctor 参数 `fixedPrefixActions`（`CombatBeamSolver.cs:38`），搜索开跑前把前缀
**回放**一遍，走完的节点当搜索根（`Phases.cs:1106-1129`）。

逐条照抄的四件事：

1. **动作不合法 → 整条作废**：蓝本 `CanApplyFixedPrefixAction`（`:2402-2441`）
   对出牌查「在手牌里 + 当前打得出 + 目标合法」，对药水查「枚举出来的药水动作里有这一条」。
   我们的 `replayPrefixAction` 直接在副本上试打，`ActionRejected` / 任何异常 → 返回 null。
2. **打不出不许换牌顶上**：蓝本注释「Keep the planned identity: an invalid suffix rejects
   the adjusted route rather than replaying it against a missing target or choosing another one.」
   —— 我们同样整条拒绝，不 fallback。
3. **战斗提前结束就 `break`**：前缀剩下的动作已经不可达（`:2384-2388`）。
4. **索引题**：蓝本 `PlanAction` 带 `CardId`，靠 `FindCardForReplay` 在手里找牌；
   我们 `PlanAction` 带**手牌下标**，前缀一律「从当前局面立刻回放」，所以下标天然有效。
   蓝本的 `action.Turn != node.Turn` 检查与「这张牌打完会强制结束回合」检查（`:2312-2338`）
   在 STS1 无对应物（`PlanAction` 不带回合号；铁甲没有强制结束回合的牌），不做。

**无对应、不是漏搬**：蓝本 `resetFixedPrefixSchedulingBaseline` 会重置
`InitialPersistentBuffValue` / `InitialEnemyStrengthSuppression` / `InitialEnemyWeakTurns` /
`InitialRetainedAttackValue` 四个排程基线（`:1135-1142`）—— 这四个量我们都没建模。

### 27.2 药水上限闸

`EnumeratePlannedPotionActions`（`ExpansionPlan.cs:146-147`）：
`_maximumPotionUses != null && ExplicitPotionUseCount(node) >= 上限` → 整段不枚举药水。
我们落在 `expandTurnLayer` 的药水循环上，判据 `node.potionsUsed >= maxPotionUses`
（`potionsUsed` 就是路线里 USE_POTION 的累计数；STS1 没有「自动用药」，
瓶中仙子是被动死亡豁免，不算显式用量）。

### 27.3 免费前缀枚举

`BuildOpeningNoCostPrefixes`（`Expansion.Opening.cs:1028-1064`）逐行：

| 蓝本 | 我们 |
|---|---|
| BFS `depth < 4` | `NO_COST_PREFIX_MAX_DEPTH = 4` |
| 子节点筛 `PlayCard && Turn == parent.Turn && Energy == parent.Energy` | `collectNoCostPlays` 同三条（plays 与 ended 都过一遍 —— 蓝本也不排除终局子节点） |
| `DistinctBy(StateKey)` 后按 `Score` 降序取 24 | `noCostFrontier`：按指纹去重（保留先出现的），按基础分降序取 24 |
| 汇总后 `OrderByDescending(ActionCount).ThenByDescending(Score).Take(12)` | `depth` 降序、`score` 降序取 12 |
| `node.Actions.ToArray()` 当前缀 | `routeOf(node)` |

只用到了六个量：动作种类 / 回合 / 能量 / 状态指纹 / 基础分 / 动作数 —— **全部已有**，
这就是「这条能整条搬」的判据。

### 27.4 触发条件与采纳

`PostSearch.cs:355-362` 的与条件，逐条对：

| 蓝本 | 我们（`shouldRescueZeroCostOpening`） |
|---|---|
| `ResultScope == SearchCompletion` | `!hitNodeLimit && !lastPassCutShort` |
| `IsCompleteVictory(selected)` | 同 |
| `selected.ExplicitPotionCount == 0` | `selected.potionsUsed == 0` |
| `selected.ProjectedBattleHpLost > 0` | `rootHp - selected.state.hp > 0`（投影掉血 = 起点血 - 该节点血） |
| `BestNode.Actions.First().Kind == EndTurn` | `selected.firstAction().kind == END_TURN` |
| `PotionPolicy == Smart && !HasForcedDirectives` | **无对应**：药水策略族未建模，用「这条路线用药数」代替 |
| `!IncludeTurnSetup` | 无对应（我们没有 turn-setup 分支） |

采纳（`PostSearch.cs:366-389`）：
`minimumRemainingMilliseconds 5000` → `ZERO_COST_MIN_REMAINING_MILLIS`；
`maximumNodes 40000` → `ZERO_COST_MAX_NODES`；`maximumMilliseconds 12000` → `ZERO_COST_MAX_MILLIS`；
`reserveMilliseconds 2000` → `ZERO_COST_RESERVE_MILLIS`；`++attempts >= 8` → `ZERO_COST_MAX_ATTEMPTS`；
`ResultScope == SearchCompletion` 才算成绩 → `!sub.hitNodeLimit && !sub.lastPassCutShort`；
`IsBetterPotionPolicyResult` → 我们的 `better()`（最终排序词典序）；
`ProjectedBattleHpLost == 0` 就停 → `rootHp - selected.state.hp <= 0` 就停。

**位置差异（记录在案）**：蓝本在 coordinator 的 post-search 链（主搜索之后第 4 趟）；
我们没有 coordinator 这一层，放在 `bestFirstAction` 里紧跟主搜索、共用同一份 deadline。
补搜自己是 sub-solver（带固定前缀），`fixedPrefix` 非空时不再触发补搜 —— 不递归。

### 27.5 验证

- 新增 `ZeroCostOpeningRescueTest` 6 条：前缀回放（`lastSteps[0].cardKey == Flex`）、
  非法前缀整条作废（能量不够 / 下标越界两档）、药水闸 0 时路线无用药且不限时必用药、
  免费前缀只收 0 费牌（回放后按牌名校验，不看下标）、采纳逻辑（喂一条「赢了但赔 25 血」的
  假解，补搜必须换掉）、第一步是出牌时不补搜。
- 全量 **227 条通过**（221 旧 + 6 新）。
- ⚠️ 未覆盖：「主搜索真误判、补搜救回来」的端到端局面 —— 它依赖 beam 宽度，换宽度就不复现，
  钉不住，所以只钉采纳逻辑。

## 28. 并行展开（A｜蓝本源码 + 实测，2026-10-24）

### 28.1 搬了什么

蓝本 `CombatBeamSolver.ParallelExpansion.cs`（1025 行）+ `ExpansionExecutor.cs`（39 行）。
它注释里点明两条不变式，我们照抄这两条，其余（批次池 `ExpansionBatchPool`、
模拟器所有权 `ReplayForkSeed`、内存压力闸、取消令牌、per-lane 自建线程）是蓝本
自己那套模拟器的负担 —— 我们的影子状态是纯数据，没有对应的东西要管：

1. **lane 隔离**：「每条 lane 有自己的 worker 求解器，候选只在各 lane 内物化」。
   我们 `BeamSolver.expansionWorker()` 造 worker：共享**不可变**的打分上下文
   （`Score.Root` / `postCombatRelicHeal` / 配置），可变状态里只有「日志去重集合」共享
   （否则每条 lane 各刷一遍同样的 REJECT 日志）；
2. **有序提交**：「coordinator 独占按序提交；transposition / dominance 仍按父节点原序提交」。
   `ParallelExpansion.expandAll` 把父节点按**连续切片**分给各 lane，结果按切片序合并 ——
   下游 `prune` 是稳定排序，候选次序不变 ⇒ 整棵树与串行同构。

### 28.2 验收：逐位一致（不是「差不多」）

`ParallelExpansionTest`：同一局面跑 `lanes=1` 与 `lanes=2/4/8`，逐项断言
选中动作、路线每一步、`lastNodes` / `lastDepth` / `lastLeaves` / `lastStaticScore` /
`lastTerminalScore` 全部相等。用节点上限（不传时间预算），否则两次会砍在不同地方。

### 28.3 🔴 实测：不划算，所以默认关

| lane | 父节点 | 子状态 | 耗时 | 吞吐 |
|---|---|---|---|---|
| 1（串行） | 63823 | 307418 | 1589 ms | 40165 /s |
| 2 | 同上 | 同上 | 1724 ms | 37020 /s |
| 4 | 同上 | 同上 | 1520 ms | 41988 /s |
| 8 | 同上 | 同上 | 1544 ms | 41336 /s |

场景：beam 135 / depth 40 / nodeLimit 40 万 / 两只 2000–3000 血怪 / 10 张手牌。
结论：**8 lane 只有 1.03×，2 lane 反而更慢**。

原因（同一趟量出来的）：

- 一个父节点展开只要几十微秒（`SimState.copy()` 很便宜，跟蓝本那份要 fork 整套预测
  模拟器的快照不是一个量级），跨线程交接的开销跟它同量级 → 「一个父节点一个作业」太细；
  改成连续切片派活后仍只有 1.07×。
- 真正的热点是**单线程的剪枝**：`prune` 里每个候选都要 `SimState.fingerprint()`，
  实现是 `toJson()`（拼 JSON 字符串）再 FNV —— 实测 **2103 ns/次**，
  30.7 万子状态 ≈ 0.65 s，占整趟搜索约四成。这一段并行碰不到（Amdahl 上限 ≈1.6×）。

所以：蓝本默认 8 lane，我们**默认 1（串行）**，`-Dsts1solver.searchLanes=N` 可开。

**后续（同日，§29 修完指纹之后复测）——原先的假设被否掉**：

| 配置 | 3 次测量 | 结论 |
|---|---|---|
| 旧指纹 + 1 lane | 1507 ms | 基线 |
| 新指纹 + 1 lane | 524 / 562 / 536 ms | **≈2.8×，全靠指纹** |
| 新指纹 + 2 lane | 655 / 680 / 649 ms | 比串行慢 |
| 新指纹 + 4 lane | 614 / 619 / 635 ms | 比串行慢 |
| 新指纹 + 8 lane | 572 / 578 / 571 ms | 仍比串行慢 |

也就是说「先修指纹、并行就能用」不成立。真正的限制是**层太小**：每层只有 beam 宽
（45–135）个父节点、每个约 30µs，而每层派发一次的线程唤醒 + 切片合并要几百 µs，
开销盖过收益。蓝本每层的工作重得多（要 fork 整套预测模拟器），所以它 8 lane 划算。
→ 结论：代码与开关留着，**默认关**；等单节点展开变重（例如把怪物 AI 状态机也镜像进来）
再复测，别凭「应该更快」开。

### 28.4 顺带改的一处线程安全

`IntentLearning.predict` 原来是 `synchronized` 方法，而它是 `CombatFlow.endTurn` 的必经之路
（每展开一个回合边界就查一次）—— 并行一开就变成全局串行点。改成
`ConcurrentHashMap` + `AtomicInteger` 计数，**读写语义完全不变**（`putIfAbsent` 保持
「同 type+turn 只写一次」），只是不再拿同一把锁。

## 29. 指纹换算法：免拼字符串的直接哈希（A｜实测，2026-10-24）

### 29.1 问题

`SimState.fingerprint()` 原来是 `fingerprintOf(toJson())`：先拼一份完整 JSON
（几百到几千字符），再 `getBytes("UTF-8")`，最后 FNV-1a。实测 **2241 ns/次**。
而它躺在剪枝热路径上 —— `prune` 对每个候选算一次、`noCostFrontier` 也对每个候选算一次。
30 万子状态 × 2.1µs ≈ 0.65 s，占整趟搜索四成（这也是并行展开只有 1.03× 的原因）。

### 29.2 改法

`fingerprint()` 直接把这些字段喂进混洗器，**一个字符串都不拼**：

| 段 | 参与哈希的字段（与 `toJson` 逐个对应） |
|---|---|
| meta | act / floor / ascension |
| player | hp / maxHp / block / energy / deathSaveUsed / deathSaveUseCount / deathSaveHpRestored / playerPowers / powerAux |
| 四个牌堆 | 张数 + 每张的**牌名 key**（原版 JSON 也只写 key，`cost`/`damage` 本来就不进指纹） |
| 其他 | relics / potions（**空格要占位**）/ monsters（type、hp、maxHp、block、powers；**意图本来就不参与**）/ rng（每个流 counter+s0+s1，缺席要占位） |

迭代顺序与 JSON 一致（`powerAux` / `relics` 是 TreeMap 有序，`playerPowers` 是
LinkedHashMap 的施加顺序）。混洗器：黄金比例步进 + murmur 收官（两轮 xor-shift-multiply），
字符串先走一遍逐字符 FNV 再把结果折进来。

**结果：2241 ns → 244 ns（9.2×）**，整趟搜索 1507 ms → 约 540 ms（**2.8×**）。

### 29.3 验收：等价类必须一模一样（这是唯一的安全判据）

换算法不谈「哈希值」—— 值本来就该变，它只用于「这两条路线是不是同一个局面」。
危险的是**漏字段**：漏一个就是静默撞库，两条不同路线被当成同一条，搜索莫名丢分支，
不报错、不崩溃。

`SimStateFingerprintTest` 造了 38 个状态的语料（覆盖上表每一个字段的「改值」与「换序」），
判据是**等价类相等**：

```
对任意 i<j：  fingerprintOf(toJson_i).equals(fingerprintOf(toJson_j))
           ⟺ fingerprint_i.equals(fingerprint_j)
```

外加「不同状态的数量与老口径一致」「只有 base 与它的 copy 允许同指纹」两条。

### 29.4 ⚠️ 顺带查出来的口径缺口（原样保留，未改）

`toJson()` 里**没有** `turn` / `hpLossCount` / `potionSlots` / `gold`，
所以这几个字段不同的两个状态会被判成同一个局面：

- `turn` 不进指纹：`TranspositionTable` 那段注释写「键含回合号」是**错的**（注释待改）；
  实践中回合前进必然伴随抽牌/洗牌，局面会变，所以暂时没炸；
- `hpLossCount` 不进指纹：它决定浴血奋战（Blood for Blood）的动态费用 ——
  「同样血量、挨打次数不同」的两条路线会被去重掉一条；
- `potionSlots` 是常量（铁甲 2 格），`gold` 战斗内不变。

**这是语义变更**（补进去 = 等价类变细 = 去重变少 = 节点数变多、结果可能变），
所以要用户拍板，不擅自改。§29.2 的哈希字段严格按现有口径排列，就是为了让这一步
可以独立决定。

## 30. 带骨肉的阈值回血进基础分（A｜蓝本源码，2026-10-24）

### 30.1 蓝本怎么写

`CombatBeamSolver.StateEvaluation.cs:199-207`：

```csharp
RelicCounterEvaluation relicCounters = combat.EvaluateRelicCounters(...);
if (won && (relicCounters.SatisfiedMask & (1UL << (int)RelicCounterId.MeatOnTheBone)) != 0)
{
    int thresholdHeal = root.PostCombatRelicHeal.HealFor(hp, maxHp)
                      - root.PostCombatRelicHeal.MonotoneHealFor(hp, maxHp);
    relicCounters = relicCounters with { HealingHpCredit =
        ActEndingBossPolicy.PersistentValueOfRecoveredHp(thresholdHeal, _strategicBossHpRelief) };
}
score += (double)(relicCounters.HpCredit + relicCounters.HealingHpCredit) * hpWeight;
```

要点：**阈值回血不走赤字轴**（那会破坏「血更多永不更差」的单调不变式，见
`PostCombatRelicHeal` 类注释），它按 `hpWeight` 直接进**基础分**，而且只算
`HealFor - MonotoneHealFor` 这个**差额** —— 无条件那部分已经在赤字里抵过了。

### 30.2 我们怎么对

| 蓝本 | 我们 |
|---|---|
| `root.PostCombatRelicHeal` | `Score.Root.postCombatHeal`（从根快照的 `relics` 建，和 `BeamSolver.postCombatRelicHeal` 同源） |
| `HealFor - MonotoneHealFor` | `Score.thresholdPostCombatHeal(heal, hp, maxHp)`，逐项同式（都按 headroom 截断） |
| `won && MeatOnTheBone 满足` | `won`（= 没死且怪全灭）+ `heal.hasAnyHeal()` |
| `PersistentValueOfRecoveredHp(x, relief)` | 恒等 —— 我们只有 `BossHpRelief.None` 一档，`RawHpRequiredForPersistentValue(1, None) = 1` |
| `HpCredit`（计数遗物目标） | 未建模（`RelicCounterTarget` 是 UI 目标策略），记 0 |
| `hpWeight` | `Score.HP = 30_000`（1 血） |

### 30.3 验证

`PostCombatThresholdHealTest` 5 条：

1. 结束时正好 40/80（= 50%）→ 比无遗物多 12 血的分；
2. 41/80（高于阈值）→ 一点不多；
3. 还有怪活着（没赢）→ 一点不多；
4. **燃烧之血 + 带骨肉** → 增量仍是 12（不是 18）—— 防止无条件回血被算两遍；
5. 10/20 只剩 10 点空位 → 只兑现 10。

全量 239 条通过。

## 31. 药水战略成本 + 对账闸（A｜蓝本源码，2026-10-24）

### 31.1 蓝本怎么写

两层：

1. **成本**：`PotionValuationRegistry.StrategicHpCost(potion)`（`PotionValuationRegistry.cs:58-70`）
   把每瓶药水归到三档，返回「喝掉它至少要买回多少血」：
   `High → PotionHighValueHpSaved(18)`、`Elevated → PotionElevatedValueHpSaved(14)`、
   默认 `PotionMinimumHpSaved(9)`。
2. **对账**：`PotionUsePolicy`（`PotionUsePolicy.cs:9-37`）要求
   `PotionHpSaved = 不喝药的赤字 - 这条的赤字 ≥ Σ StrategicHpCost`，
   达不到的路线在最终选路时被剔掉（`potionBranchesRejected`）。

### 31.2 我们怎么对

| 蓝本 | 我们 |
|---|---|
| `StrategicHpCost(potion)` 三档 | `mirror/PotionValuation.strategicHpCost(id)`，9 / 14 / 18 逐值照抄；档位表只对**效果同形**的三瓶（见 §31.3） |
| `PotionHpSaved` | `gatePotionSpending` 里跑一趟 `maxPotionUses = 0` 的基线搜索，`saved = deficit(基线) - deficit(选中)` |
| 达不到就剔掉 | `saved < cost` → 直接换成基线路线（`lastPotionSpendRejected = true`） |
| 「不喝就输、喝了能赢」 | 胜负不同时不做赤字相减，记 `lastPotionHpSaved = Integer.MAX_VALUE`（哨兵：买到的是整场胜利） |
| 没预算跑基线 | 剩余 < 5s 就保持原答案（不硬跑） |

### 31.3 STS1 档位映射（⚠️ 待核对）

只把**效果同形**的对上，其余按基准档（宁可低估价值）：

| STS1 药水 | 档 | 门槛 | 蓝本对应 |
|---|---|---|---|
| Fruit Juice（+5 最大生命） | HIGH | 18 | `GLOWWATER_POTION`（永久最大生命） |
| GamblersBrew（弃任意张再抽等量） | HIGH | 18 | `GAMBLERS_BREW`（同名） |
| LiquidMemories（弃牌堆取回一张） | ELEVATED | 14 | `LIQUID_MEMORIES`（同名） |
| 其余 18 张 | BASELINE | 9 | 默认档 |

瓶中仙子是被动死亡豁免（`canUse` 为假），不进表。

### 31.4 验证

`PotionCostGateTest` 4 条：

1. 三档数值（9 / 14 / 18）+ 未登记 ID 落基准档；
2. **只买「早一回合结束」不买血的药水必须被拒**（怪 20 血、意图 0、喝火药 1 回合结束 vs
   不喝 2 回合结束、两者都 0 掉血 → 省血 0 < 9 → 退回不吃药路线）；
   对照组 `withPotionCostGate(false)` 确认不设闸时搜索真的会喝（否则这条测试没意义）；
3. 救命场面必须放行（怪 20 血意图 25、能量 0、空手 → 不喝必死）；
4. 一滴药没喝的路线不触发对账（不多跑一趟基线）。

全量 **243 条**通过。

### 31.5 成本

喝药时会多跑一趟「不喝药」基线搜索（同样的节点上限、同一份 deadline 里扣预算）。
蓝本也是这么做的（`PotionFreePolicyBaseline`），所以这是对齐而非新增开销。

## 32. 能力估值模型族（PowerValuation）—— 清单 5 的第一批（A｜蓝本源码，2026-10-24）

### 32.1 蓝本怎么写

`StrategicEffectModel.Evaluate(power, context)`（`src/Search/StrategicEffectModel.cs:476-560`）
给每层能力算一个**五维向量**，加起来就是它的保留价值：

```
DamagePotential        这层能力最终能多打出多少伤害（按敌方总血封顶）
PreventionPotential    能挡多少伤害（按「意图伤害 × 最多 2 回合」封顶）
ResourcePotential      省/赚多少能量（1 能量 ≈ 一张牌的平均价值）
CardAccessPotential    多给多少张可打的牌（同样折成牌价值）
ScalingPotential       前面都算不进去时的兜底（每层 1 点）
RetentionValue = clamp(五项和, 0, MAX)
```

`PersistentBuffValue` = 所有能力 RetentionValue 之和；Beam 排序里用的是
`min(4, max(0, 本节点值 - 起点值)) × 150_000`（`Ranking.cs:925-929` +
`SolverWeights.StandardPersistentBuffDeltaBeamCap/Value`）。

### 32.2 我们搬了什么

| 能力 | 蓝本分支 | 公式 |
|---|---|---|
| Gain Strength / Strength | `StrengthPower` | `Damage(amount × AttackHits)` |
| Dexterity | `DexterityPower` | `Prevention(amount × BlockSkillPlays)` |
| Demon Form | `DemonFormPower` | `Damage(amount × ⌊AttackPlays/T⌋ × T(T+1)/2)` |
| Feel No Pain | `FeelNoPainPower` | `Prevention(amount × ExhaustPlays)` |
| Dark Embrace | `DarkEmbracePower` | `CardAccess(amount × ExhaustPlays × AverageCardValue)` |
| Corruption | `CorruptionPower` | `Resource(SkillEnergySpend × max(3, AverageCardValue))` |
| Thorns | `ThornsPower` | `Damage(amount × IncomingHitCount)` |
| 其余 18 个（Metallicize / Combust / Juggernaut / Rupture / Barricade / Berserk / Brutality / Evolve / Fire Breathing / Flame Barrier / Rage / Double Tap / Mayhem / Magnetism / Sadistic / Panache / …） | **默认档** `_ => Scaling(amount)` | 每层 1 点 |

🔴 关于「燃烧并非 STS1 独有」：查了蓝本全库，`Combust` / `Metallicize` / `FireBreathing` /
`Evolve` / `Sadistic` / `Brutality` **0 命中** —— STS2 没有这几个能力，所以它们走的是蓝本
自己的默认档 `_ => Scaling(amount)`。这是**蓝本的兜底**，不是我们自创。

### 32.3 两处近似（必须记住）

| 量 | 蓝本 | 我们 |
|---|---|---|
| `IncomingHitCount` | 本回合会打过来的**段数**（连击算多段） | 有意图伤害的怪物**只数**（怪物招式段数未建模） |
| `AverageCardValue` | `CardChoiceSupport.CardValue`（含效果价值） | 牌堆攻击牌的**平均面板伤害**，没有攻击牌时退 1 |

另外 `BlockSkillPlays` 的「给格挡的技能」用从镜像表抽出来的 28 条固定名单（蓝本读
`DynamicVars` 的 `Block` 变量）；`ExhaustPlays` 直接读 `CardMirror.exhaustsOnPlay`，不另维护名单。

### 32.4 接线

`Score.of(state, root, actions)` 里算 `persistentBuffValue`，`Score.Root` 里算
`initialPersistentBuffValue`（起点快照），`beamTotal` 用两者的差 —— 与蓝本同口径：
**算的是「相对起点新增的价值」，不是「能力层数」**（原来是 `scalingPowers + strength`
按层封顶，拿数量冒充价值）。

单参 `Score.of(state)` 把起点按「没有能力」算（`Root(state, true)`），否则独立估值里
delta 恒为 0、完全看不见能力牌 —— 这是给单测/面板用的口径，搜索走三参版本。

**性能**：投影要扫一遍三个牌堆 + 怪物，所以在 `playerPowers` 为空时整段跳过。
同一时间窗 A/B（beam 135 / depth 40 / 40 万节点）：改动前 808–880ms，改动后 634–790ms
—— 差异在噪声内，投影开销可忽略。

### 32.5 验证与测试夹具修正

三条既有测试因为口径变化需要修正（不是放松断言，是夹具本身不真实）：

1. `BeamSolverTest.state()` 原来用 `new SimCard(id, false, 1)`（**type 为空、面板伤害 -1**），
   投影算不出「攻击牌」，恶魔形态的潜力变成 0 → 改成带 `type` 与 `baseDamage`；
2. `ScoreAndTranspositionTest` 的两条（力量加分 / 恶魔形态更值钱）在**空牌堆**上断言 ——
   按蓝本口径「层数 × 吃得到的攻击量」，没有攻击牌时任何攻击型能力都该是 0 →
   夹具补 5 张攻击牌（蓝本同口径）。

全量 **243 条**通过。

### 32.6 还没做

- Retention 批 3（Pareto 支配）/ 批 5（承诺代表）/ `SelectEarlyTurnFrontier` 前沿分桶**仍未接**：
  它们需要的是「快照上能读出的多维量」，本批只补了 `PersistentBuffValue` 一维。
- `LatentSetupValue`（手牌/牌堆里未打出的能力牌）仍是按张数估的默认档。

## 33. 怪物出招镜像（MonsterMoves）—— 清单 3 的第一批（A｜字节码，2026-10-24）

### 33.1 为什么要重写

原来怪物只建模一件事：**意图伤害**（`SimMonster.intentDmg`，来自 `getIntentDmg()`），
未来回合靠 `IntentLearning` 观测表猜（"同类型同回合的意图"）。
这条路的三个缺口：

1. 未观测过的回合只能退化成"沿用当前意图"；
2. **格挡完全没建模** —— 怪加块（如球体守卫 DEFEND 25）在影子里等于不存在；
3. **减益没建模** —— 球体守卫的 5 层 Frail、掠夺者的 Weak 都不会进影子。

`MonsterMoves` 换掉这三处：逐个怪物翻译 `getMove(int)`（决定出哪招）+ `takeTurn()`
（决定这招产生什么），数值全部来自字节码常量。

### 33.2 结构

| 概念 | 原版 | 我们 |
|---|---|---|
| 出招决策 | `AbstractMonster.getMove(int)` / `rollMove()` | `MonsterMoves.Machine.next(history, turn)` |
| 招式历史 | `AbstractMonster.moveHistory`（`ArrayList<Byte>`，public） | `SimMonster.moveHistory`（捕获时直接读） |
| 本回合的招 | `AbstractMonster.nextMove`（byte，public） | `SimMonster.pendingMoveId`（捕获时读）+ `pendingMove`（算出来的） |
| 执行 | `takeTurn()` 里的 `DamageAction` / `GainBlockAction` / `ApplyPowerAction` | `CombatFlow.endTurn` ④：逐段 `hitPlayer`、加格挡、上减益 |
| 伤害公式 | `DamageInfo.applyPowers`（base → +力量 → ×0.75 虚弱 → ×1.5 易伤，每步 float 截断） | `DamageCalc.monsterAttackDamage` |

**没登记的怪物走老路**（`IntentLearning` + 单段意图伤害），所以覆盖率可以一只一只加，
加之前的行为逐位不变（`MonsterMovesTest.unmirroredMonstersKeepTheOldBehaviour` 钉住）。

### 33.3 为什么不翻译 `getMove` 的 `num` 参数

原版 `rollMove()` = `getMove(AbstractDungeon.aiRng.random(99))` —— **每次决策都消耗一次 aiRng**。
我们的影子虽然也有 aiRng 快照，但在别处少消耗一次就会让整条随机序列错位。
所以第一批只收「`num` 不影响分支」的确定性循环怪；带随机分支的要连 aiRng 的消耗次数一起对齐，
单独一批做（记在 `blueprint-parity`）。

### 33.4 已镜像：球体守卫 SphericGuardian

字节码（`javap -c com.megacrit.cardcrawl.monsters.city.SphericGuardian`）：

```
getMove(num):  firstMove  → 2 (DEFEND)
               secondMove → 4 (ATTACK_DEBUFF, dmg)
               否则 lastMove(1) → 3 (ATTACK_DEFEND, dmg) : 1 (ATTACK, dmg × 2 段)
takeTurn:      1 → 两段 dmg          2 → 格挡 25（A17+: 35）
               3 → 格挡 15 + 一段 dmg 4 → 一段 dmg + 玩家 Frail 5
常数:          HP 20、基础伤害 10（A2+: 11）、Frail 5
```

“firstMove / secondMove” 两个标志用 `history.size()` 等价表达（第 1 次决策历史为空、
第 2 次恰好 1 条）—— 比在影子里多存两个布尔更不容易漂。

进阶档（A2 伤害 11、A17 格挡 35）**已补**：机器从 `Context.ascension`（= `SimState.ascension`）读，
逐条对着字节码里的 `ascensionLevel` 比较。

### 33.5 验证

`MonsterMovesTest` 6 条：

1. 机器序列（空历史 → 2；[2] → 4；[2,4] → 1 两段；[2,4,1] → 3；[2,4,1,3] → 1）；
2. 回合流程按**段数**结算（10×2 = 20）、历史追加、下一招预告；
3. 格挡 25 落到怪身上、Frail 5 落到玩家身上；
4. 每段都吃「怪物力量 + 玩家易伤」（(10+3)×1.5 截断 → 19×2 = 38）；
5. 未镜像的怪行为不变；
6. 伤害公式四档（10 / 10×0.75=7 / 7×1.5=10 / 10×1.5=15）。

全量 **249 条**通过。


### 33.6 第二批：aiRng 与进阶（2026-10-25）

按用户拍板「复刻蓝本」「进阶也补」两件事都做了：

1. **每次决策消耗一次 aiRng**。原版 `AbstractMonster.rollMove()` 是
   `getMove(AbstractDungeon.aiRng.random(99))` —— 字节码直读（`rollMove` 第 0-9 条指令）。
   蓝本既然照抄这一步，我们也照抄：`MonsterMoves.next` 先 `state.rng(AI).random(99)` 再交给机器。
   ⚠️ 这意味着「一次决策 = 一次 aiRng 消耗」，等价于原版的消耗次数；
   `MonsterMovesTest.everyDecisionConsumesOneAiRoll` 钉住它。
2. **进阶数值**。`Context.ascension` 直接读 `SimState.ascension`，各机器按字节码里的
   `ascensionLevel >= N` 分支给不同数值。

第二批镜像（全部逐条对字节码）：

| 怪物 | 招式 | 伤害 | 进阶 |
|---|---|---|---|
| `Cultist` | 首招 3=INCANTATION（给自己挂 Ritual），之后 1=DARK_STRIKE | 6 | A2 起 Ritual 4；A7 起 HP 50–56 |
| `GremlinWarrior` | 永远 1=ATTACK | 4 | A2 起 5 |
| `GremlinFat` | 永远 2=ATTACK_DEBUFF（玩家 Weak 1） | 4 | A2 起 5；A17 起 Weak 2 |
| `SpikeSlime_S` | 永远 1=ATTACK | 5 | A2 起 6；A7 起 HP 11–15 |

Ritual 走的是原有那条路：`CombatFlow.endTurn` ④.5 的 `atEndOfRound` 把 Ritual 变力量 ——
`cultistRitualTurnsIntoStrengthAtRoundEnd` 钉住「同一回合就转」（原版 `RitualPower.atEndOfRound`）。

### 33.7 下一批的三个卡点（写下来免得重复试）

| 怪物 | 卡在哪 | 需要的模型扩展 |
|---|---|---|
| `GremlinTsundere` | 格挡给的是**随机队友**（`GainBlockRandomMonsterAction`） | `MonsterMove` 要能表达「给别的怪加格挡」+ 选目标的随机 |
| `Sentry` | 攻击附带**往弃牌堆塞 2 张 Dazed** | `MonsterMove` 要能表达「给玩家牌堆加状态牌」 |
| `Lagavulin` | 睡 3 回合（带 Metallicize/Plated Armor 变化）+ 醒后重击 | 需要「多回合状态机 + 自身能力变化」 |

另外 `Exploder` / `Transient` / `SnakeDagger` / `TorchHead` 的 `javap` 输出为空
（类名对不上或没有 `getMove`），下一批先确认它们到底在哪个类里。


### 33.8 第三批：两个载荷扩展 + 四只怪（2026-10-25）

这三个卡点里两个已经打通，`MonsterMove` 现在能表达六类载荷（伤害 / 自身格挡 / **队友格挡** /
自身能力 / 玩家减益 / **塞状态牌**）：

| 新载荷 | 原版 Action | 用它的怪 |
|---|---|---|
| `blockRandomAlly` | `GainBlockRandomMonsterAction` | `GremlinTsundere` |
| `statusCard` | `MakeTempCardInDiscardAction` | `Sentry`（Dazed）、`SpikeSlime_M`（Slimed） |
| （既有）`selfBuff + block` | `ApplyPowerAction` + `GainBlockAction` | `JawWorm` 的 Bellow |

第三批四只怪（数值全部对着字节码 + `localization/eng/monsters.json` 的 MOVES 名核对）：

| 怪物 | 决策 | 招式 |
|---|---|---|
| `Sentry` | 首招看**位次**：偶数位先 3、奇数位先 4；之后 4/3 交替 | 3 = 塞 Dazed 2（A18: 3）；4 = beam 9（A3: 10） |
| `GremlinTsundere` | 有队友就 1（Protect），落单才 2（Shield Bash） | Protect = 给**随机队友**格挡 7（A7: 8 / A17: 11）；Bash 6（A2: 8） |
| `JawWorm` | `num` 三档（25 / 55）+ `aiRng.randomBoolean(0.5625/0.357/0.416)` | 1 Chomp 11（A2: 12）；2 Bellow（力量 3/4/5 + 格挡 6/6/9）；3 Thrash 7 + 格挡 5 |
| `AcidSlime_S` | **按 takeTurn 的 SetMoveAction 写**：1→2→1 死循环 | 1 打（3 / A2: 4）；2 玩家 Weak 1 |
| `SpikeSlime_M` | `num < 30` 看 `lastTwoMoves(1)`，否则看 `last(4)/lastTwo(4)` | 1 打（8 / A2: 10）+ 塞 1 张 Slimed；4 玩家 Frail 1 |

### 33.9 三条踩坑（都写进代码注释了）

1. **`AcidSlime_S` 根本不用 `getMove`**：它的 `takeTurn` 自带 `SetMoveAction`，字节码里
   没有 `RollMoveAction`。照 `getMove` 翻（`randomBoolean` 二选一）会得到错的序列 ——
   真实序列是死循环 1→2→1。**判据：先看 takeTurn 末尾有没有 `RollMoveAction`**，
   没有就说明决策被写死在 takeTurn 里。
2. **随机数原语的消耗次数**：`randomBoolean()` / `randomBoolean(float)` 各消耗一次
   （`counter++` + `nextBoolean()` / `nextFloat() < chance`）。JawWorm 的 `getMove`
   里就有，所以一次决策可能消耗 **两次** aiRng。`RngStream` 已补这两个原语。
3. **`RandomXS128(state0, state1)` 不是种子混合器**：拿小整数当 state0 时，前若干个
   `nextFloat()` 几乎恒为 0，`randomBoolean(0.5625f)` 于是永远返回 true。写测试造种子
   必须先摊一遍（`MonsterMovesTest.mix`），否则会以为分支逻辑坏了。

### 33.10 回合流程的重排（顺带修正）

原来 `CombatFlow.endTurn` 是「所有怪 takeTurn → 所有怪 rollMove」。原版是**逐只**
`takeTurn` 末尾就排 `RollMoveAction`。只要怪物在 takeTurn 里消耗随机数
（Tsundere 的随机队友、JawWorm 的 `randomBoolean`），两种次序就会让 aiRng 的**取值**
错位。现在改成逐只「takeTurn → 记历史 → 决策下一招」，与蓝本同序。

意图伤害（`intentDmg`）挪到回合末能力结算**之后**重算 —— 否则本回合刚结算的仪式力量
要等下一回合才反映到意图上。

### 33.11 验证

`MonsterMovesTest` 新增 6 条（哨兵位次与交替、Dazed 落进弃牌堆且类型是 STATUS、
傲娇鬼给队友格挡 / 落单改打、大颚虫三档 num、小酸史莱姆 1→2 循环、中刺史莱姆
Slimed + Frail）。全量 **262 条**通过。

顺带修了一条被误伤的旧测试：`DeathSaveRelicTest` 原来拿 `JawWorm` 当「意图 100」的木桩，
可它现在是镜像怪、招式由机器决定，写死的 intentDmg 会被覆盖 → 换成未镜像的 `Looter`。
**教训：拿真怪当木桩会随镜像进度悄悄失效，木桩一律用没镜像的类型。**

### 33.12 第四批：五只怪 + 捕获卡面伤害（2026-10-25）

| 怪物 | 决策 | 招式 | 数值 |
|---|---|---|---|
| `FungiBeast` | `num<60`：连打两下 → Grow，否则 Bite；`num>=60` 反过来 | Bite / Grow（自己力量） | bite 6、力量 3（A2: 4 / A17: 5） |
| `SlaverBlue` | `num>=40` 且非连续两刺 → Stab；否则 Rake | Stab / Rake（+Weak） | 12/7（A2: 13/8）、Weak 1（A17: 2） |
| `LouseNormal` | `num<25` 看 4 号招是否重复，否则看 3 号招是否连续 | Bite / **Grow**（自己力量 3，A17: 4） | bite 5-7（A2: 6-8 随机） |
| `LouseDefensive` | 同上（字节码与普通虱子逐条相同） | Bite / **Web**（玩家 Weak 2） | 同上 |
| `GremlinWizard` | 永远排 2(Charging)；蓄力到 3 排大招 | Charging / Ultimate Blast | blast 25（A2: 30） |

**新增基础设施：`SimMonster.damageBase`**

不少怪的伤害是**构造器里 `MathUtils.random` 抽的**（虱子 5-7 / A2 起 6-8、奴隶主、
大颚虫…）。那个随机发生在 `AbstractMonster` 子类构造器里，影子复现不出来 ——
所以捕获时直接读真机的 `damage` 列表（public 字段，每项是 `DamageInfo.base`），
机器优先用它，没有才退回字节码默认档（`Context.baseDamage(i, fallback)`）。

⚠️ 它整场不变，所以**不进指纹**（与 `intentDmg` 同口径）。

### 33.13 三只刻意没搬的怪（理由）

| 怪物 | 卡在哪 | 缺什么 |
|---|---|---|
| ~~`SlaverRed`~~ → ✅ **已搬**（见 §33.14） | — | 原先以为缺「禁用攻击牌」状态，查证后发现两侧**早就都有** |
| `GremlinThief` / `Looter` | `escapeNext` → `SetMove(99, ESCAPE)` 离场 | 没有逃逸模型（也没有偷金币）；不建模就会让它们永远站着挨打 |
| `Lagavulin` | `isOut` / `idleCount` / `debuffTurnCount` 三个类字段，且 `damage()` 被重写 —— **睡着时被打会立刻醒并晕** | 需要「每只怪自定义状态」+「怪物受伤时的钩子」 |

这三块都不是照抄能解决的，要么先补机制，要么明确不搬 —— 先记在这里。

### 33.14 红奴隶主：缠身（Entangle）—— 查证后其实早就齐了

本来把它列进「缺机制」，查证后推翻：**禁攻击牌这条链两侧都是现成的**。

- 施加侧：`EntanglePower.ID = "Entangled"`、`amount = 1`、`isTurnBased`；
  `atEndOfTurn(isPlayer)` 里 `RemoveSpecificPowerAction` —— 也就是「挂上之后，到玩家下一个回合末自动消失」。
- 拦截侧：`CardPlayer.play` 早就有一行 `isAttack(card) && power(state, "Entangled") > 0 → ActionRejected`。
- 移除侧：`PowerHooks` 里注册了 `Entangled` 的 `END_OF_TURN` 处理器。

所以这只怪只需要补机器，不需要新机制：

```
getMove:  firstTurn → 1(Stab)
          num >= 75 且还没缠过 → 2(Entangle)
          num >= 55 且缠过 且非连续两刺 → 1(Stab)
          否则 A17+: lastMove(3) ? 1 : 3；其余: lastTwoMoves(3) ? 1 : 3
takeTurn: 1 → 一段 stab 13（A2: 14）；2 → 玩家缠身；
          3(Scrape) → 一段 scrape 8（A2: 9）+ 玩家 Vulnerable 1（A17: 2）
```

两个类字段都从历史反推：`firstTurn` = 历史为空，`usedEntangle` = 历史里出现过 2。

验证 2 条：机器分支（首招必刺 / num>=75 缠身 / 缠过不再缠 / Scrape 带易伤 / A17 易伤 2 / 连续两 Scrape 换刺），
以及**端到端**——红奴隶主真把缠身挂上玩家后，`Strike` 被拒（错误信息里带 `Entangled`）、
`Defend` 照常打出。全量 **270 条**通过。

### 33.15 🔴 修一个静默 bug：怪物格挡跨回合累加（2026-10-25）

建模怪物格挡（球体守卫 +25、傲娇鬼给队友 +7）之后才发现：
`CombatFlow.endTurn` **从来没清过怪物的格挡**，而玩家侧一直有清。

原版 `MonsterGroup.applyPreTurnLogic()`（字节码）：

```java
for (AbstractMonster m : monsters) {
    if (!m.isDying && !m.isEscaping) {
        if (!m.hasPower("Barricade")) m.loseBlock();
        m.applyStartOfTurnPowers();
    }
}
```

也就是**怪物回合一开始就把自己的格挡清零**（壁垒除外），然后才行动。
漏掉这条的后果不是「差一点」：球体守卫每两回合 +25，几回合后搜索会认定「打不死」，
于是所有路线都退化成纯挨打 —— 方向是**系统性低估进攻**。

落点：`endTurn` 的 ④.0，逐怪 `if (!powers.containsKey("Barricade")) block = 0`。

顺带补上**怪物金属化**：`MetallicizePower.atEndOfTurnPreEndTurnCards(boolean)`
的字节码**完全忽略那个 isPlayer 参数**，永远 `GainBlockAction(owner, owner, amount)` ——
所以怪物身上的金属化照样每回合加块（睡着的 Lagavulin 就靠它）。我们原来只在玩家钩子里处理。

**被这条修正打红的一条旧测试**：`flameBarrierReflectionGoesThroughMonsterBlock`
原来断言「怪物 3 格挡吸收掉 6 点反弹 → 只掉 3 血」，但怪物出手时格挡已经清掉了，
正确是全额 6 点。断言已改成 34，并另加一条「格挡不跨回合累加」的反向测试。

### 33.16 拉格瓦林（Lagavulin）—— 第一章精英（2026-10-25）

它是本章最复杂的一只：三个类字段 + 一个 `damage()` 重写。

| 类字段 | 从历史怎么反推 |
|---|---|
| `isOut`（醒没醒） | 历史里出现过 1 / 3 / 4 |
| `idleCount`（睡了几回合） | 历史里 5 的个数 |
| `debuffTurnCount`（距上次吸血打了几下） | 末尾连续 3 的个数（Siphon 会清零） |

```
getMove:  没醒 → 5(SLEEP)
          醒了：debuffTurnCount < 2 ? (lastTwoMoves(3) ? 1 : 3) : 1
takeTurn: 5 → idleCount++；到 3 就 OPEN 并排 3(ATTACK)
          1(Siphon Soul) → debuffTurnCount = 0；玩家力量与敏捷各 -N
          3(ATTACK) → debuffTurnCount++；一段 attackDmg
          4(STUN) → 只 OPEN，不打人
常数:     attackDmg 18 (A3: 20)、debuff -1 (A18: -2)、HP 109-111 (A8: 112-115)
```

**`damage()` 重写**（`Lagavulin.damage`）：血量**真的变了**且还没醒过 →
`setMove((byte)4, Intent.STUN)` + `ChangeState("OPEN")`，后者再做
`ReducePowerAction(Metallicize, 8)`。

落在 `DamageCalc.hit`（怪物受伤的唯一咽喉）：比较打前打后的 `hp`，
判据用「当前排的招还是 5」当作 `isOutTriggered` 的等价物（改过之后就不是 5 了，不会重复触发）。

🔴 **一个反直觉的细节**：全被格挡吸收时**不会醒** —— 原版判的是 `currentHealth` 变没变，
不是「挨没挨打」。测试里就踩到了：8 点格挡挡下 8 点伤害，`pendingMove` 仍是 SLEEP。

`MonsterMove` 因此补了第二个玩家减益槽（`playerDebuff2`）—— Siphon Soul 一次扣两项。

验证 3 条：机器序列（睡 3 回合→醒→砍两下→吸血，A3/A18 数值）、
「挨打即醒 + 金属化被拿掉 + 醒着再挨打不改招」、端到端（睡着每回合 +8 格挡 /
格挡没打穿不醒 / 打穿后 STUN 那回合不造成伤害 / 自己回合开始清格挡）。全量 **274 条**。

### 33.17 第六批：小鬼头目（GremlinNob）+ 激怒（Enrage）+ 中酸液史莱姆（2026-10-25）

**Enrage 是新机制**（之前全库 0 命中）：`Hooks.onCardUsed` 里补了怪物侧分支 ——
玩家每打出一张 **技能牌**，身上有 `Enrage` 的怪就 `+amount` 力量
（`EnragePower.onUseCard`，判据 `card.type == SKILL`）。

不建模的后果：搜索会以为「多打技能牌没代价」，在小鬼头目面前狂甩防御，
然后被 Rush 14（A3: 16）一拳打醒。这是**方向性错误**，不是精度问题。

`GremlinNob`：

```
getMove:  第一次 → 3(Bellow)；此后
          A18+：最近两招没出过 2 → 2(Skull Bash)；否则连续两招 1 → 2；否则 1(Rush)
          其余：num < 33 → 2；否则连续两招 1 → 2；否则 1
takeTurn: 3 → 挂 Enrage 2（A18: 3）；2(Skull Bash) → 一段 + 玩家易伤 2；1(Rush) → 一段
常数:     bash 6(A3: 8)、rush 14(A3: 16)、HP 82-86(A8: 85-90)
```

⚠️ `canVuln` 是构造器参数（有些场合造出来不允许上易伤），影子**没有捕获它**，
一律按「能上易伤」处理 —— 这是已知近似，记在这里。

`AcidSlime_M`：

```
getMove:  A17+: num<40 → 连续两招 1 ? (randBool() ? 2 : 4) : 1
                num<80 → 连续两招 2 ? (randBool(0.5) ? 1 : 4) : 2
                否则    → lastMove(4) ? (randBool(0.4) ? 1 : 2) : 4
          其余:  num<30 / num<70，判据分别换成 lastMove(2) / lastTwoMoves(4)
takeTurn: 1 → 一段 spit + 塞 1 张 Slimed；2 → 一段 tackle；4 → 玩家 Weak 1
常数:     spit 7(A2: 8)、tackle 10(A2: 12)、HP 28-32(A7: 29-34)
```

`Context.randomBoolean()`（无参版）也补上了 —— 中酸液史莱姆有两处用的就是它。

验证 3 条（Nob 招序 + 数值、Enrage 只认技能牌、中史莱姆三档 num 含随机二选一），
全量 **277 条**通过。

### 33.18 第八批：分裂（Split）—— 大史莱姆 ×2 + 「中途加怪」基础设施（2026-10-25）

这是**第一条「怪物能生出怪物」的机制**，之前影子完全没有。

#### 触发（`damage()` 重写）

```java
// AcidSlime_L.damage / SpikeSlime_L.damage
super.damage(info);
if (!isDying && currentHealth <= maxHealth / 2 && nextMove != 3 && !splitTriggered) {
    setMove(SPLIT_NAME, (byte) 3, Intent.UNKNOWN);
    splitTriggered = true;
}
```

落在 `DamageCalc.hit`（与 Lagavulin 挨打即醒同一个咽喉）的 `maybeQueueSplit`：
判据「hp 真的变了 + `hp*2 <= maxHp` + 当前排的不是 3」。

#### 执行（`takeTurn` 的 3 号分支）

原版：`CannotLoseAction` + `HideHealthBarAction` + `SuicideAction(this, false)` +
两条 `SpawnMonsterAction(new AcidSlime_M(x, y, 0, this.currentHealth), false)`。

我们 `CombatFlow.split` 逐条对：

| 原版 | 我们 |
|---|---|
| 小怪血量 = 构造器的第 4 个参数 = **父体分裂那一刻的 currentHealth** | `childHp = parent.hp`（**不减半**） |
| `SuicideAction`：父体消失、不算击杀 | `parent.hp = 0` |
| 每只小怪 `init() → getMove(aiRng.random(99))` | `MonsterMoves.next(...)`（各消耗一次 aiRng） |
| 新怪插进 `MonsterGroup` | `state.monsters.add(child)` |

🔴 **一个必须记住的坑**：`endTurn` 的怪物循环原来按 `state.monsters.size()` 遍历，
分裂出来的小怪会**当回合就行动**（原版它们要等下一轮）。现在先快照
`actingCount = state.monsters.size()`，只让开局就在场的怪动；父体自杀后也不再给它
`rollNextMove`。

#### 两只怪

| 怪物 | 决策 | 招式 |
|---|---|---|
| `AcidSlime_L` | 三档 num（30/70 或 40/80）+ 随机（0.6 / 0.4） | 喷吐 11（A2: 12）+ **2 张** Slimed；撞击 16（A2: 18）；Lick = Weak 2 |
| `SpikeSlime_L` | `num<30` 打、否则舔 | 撞击 16（A2: 18）+ 1 张 Slimed；Lick = Frail 2（A17: 3） |

验证 4 条：大酸液喷两张 Slimed + 数值、半血排队（一半以上不排 / 正好半血排 / 不重复排）、
分裂落地（父体归零 / 两只小怪血 = 父体当时血量 / 出生就有招 / 那回合不打人）、
大刺史莱姆的招式与数值。全量 **281 条**通过。

### 33.19 史莱姆王（SlimeBoss）—— 复用分裂管线 + 第二个分裂槽

`getMove` 几乎是空的（只有首回合 `firstTurn → 4(Goop Spray)`），**后面每一招都是
`takeTurn` 末尾用 `SetMoveAction` 排的** —— 又一个「机器要按 takeTurn 写、不能按 getMove 写」的例子：

```
4(Goop Spray) → 塞 Slimed 3 张（A19: 5），排 2
2(Preparing)  → 不打人，排 1
1(Slam)       → 一段 35（A4: 38），排回 4
3(Split)      → 自杀 + 生成两只不同的怪
```

分裂这次要**两只不同的怪**（先 `SpikeSlime_L`、再 `AcidSlime_L`，血量都 = 父体当时血量），
所以 `MonsterMove` 补了第二个分裂槽（`splitInto2` / `splitCount2`），
`CombatFlow.split` 两个槽依次生成。

半血触发与两只大史莱姆完全同构（同一个 `damage()` 重写写法），所以 `splitMove` 加一行即可。

验证 2 条：招序循环 + Slimed 张数 + A4/A19 数值；半血分裂（排队 / 父体归零 /
先刺后酸 / 两只各 69 血）。全量 **283 条**通过。

### 33.20 守卫者（TheGuardian）—— 姿态状态机不靠额外状态槽（2026-10-25）

上一轮判断它「需要每只怪自定义状态槽」，**这轮推翻了**：那两个字段在能力表里都有对应物。

| 原版字段 | 影子里的等价物 |
|---|---|
| `dmgTaken` | `Mode Shift` 能力的 amount（初值 = `dmgThreshold`，与 `dmgTaken` 同步递减） |
| `isOpen` | 「身上有没有 `Mode Shift`」—— 进防御时 `RemoveSpecificPowerAction(Mode Shift)` |
| `dmgThreshold` | 基础 30（A9: 35 / A19: 40）+ 10 × 历史里 CloseUp 出现的次数 |
| `closeUpTriggered` | 冗余：`damage()` 要求 `isOpen`，切姿态那一刻 `isOpen` 就变假了 |

**两个循环**（每招末尾自己 `SetMove`，`getMove` 只在兜底时用到）：

```
攻势：ChargeUp(6, +9 格挡) → FierceBash(2, 32/A4:36) → VentSteam(7, Weak2+Vuln2)
      → Whirlwind(5, 5×4) → ChargeUp(6) → …
防御：CloseUp(1, +19 格挡 + Sharp Hide 3) → RollAttack(3, 9/A4:10)
      → TwinSmash(4, 8×2，**移除 Sharp Hide、回攻势**) → Whirlwind(5) → …
```

切姿态（`damage()` 重写）：`Mode Shift.amount` 被伤害扣到 ≤ 0 →
移除该能力、立即 `+20` 格挡（`DEFENSIVE_BLOCK`）、下一招改 `CloseUp`。
落在 `DamageCalc.hit` 的 `maybeShiftGuardian`。

### 新的能力机制：Sharp Hide

`SharpHidePower.onUseCard`：玩家**每打出一张攻击牌**就掉 `amount` 点血
（`DamageInfo` 类型 `THORNS`，走格挡）。⚠️ 触发点是「你出手」不是「它被打」——
跟荆棘 `ThornsPower` 完全不同，别混。已挂在 `Hooks.onCardUsed` 的怪物侧。

### ⚠️ 一处推断（未在字节码里直接看到）

「回攻势时**重挂** `Mode Shift`」是推断：`usePreBattleAction` 会
`ChangeState("Reset Threshold")` 来开局挂能力，而 `useTwinSmash` 切回 `Offensive Mode`
的分支里没直接看到 `ApplyPowerAction`。不重挂的话第二次姿态切换永远不会发生，
与游戏行为不符，所以按「重挂、阈值 +10」实现。**下次读 `changeState("Reset Threshold")`
分支时核实这一条。**

验证 3 条（攻势循环与数值 / 防御循环与数值 / 阈值到点切姿态含 +20 格挡与 CloseUp 载荷），
全量 **286 条**通过。

### 33.21 镜像表体检（3 条测试）：类型名笔误 + 第一回合崩

23 只怪的机器名全是手写的字符串 key。写错一个字母（`SphericGuardian` 打成
`SphericGaurdian`）**不会报错**，那只怪只会静默退回 `IntentLearning` ——
「以为镜像了其实没跑」，而且只有真机打起来才可能发现。

`MonsterRegistryHealthTest` 三件事：

1. 每个登记的类型名必须能在游戏 jar 里找到对应类（四个包依次试）。
   🔴 必须 `Class.forName(name, false, cl)` —— **带初始化会炸**：
   怪物类的静态块要读 `CardCrawlGame.languagePack`，测试环境里直接
   `ExceptionInInitializerError`（踩过）。
2. 每个机器在**空历史**下都要能出一招 —— 战斗第一回合就走这条路，抛异常等于进场即崩。
3. `MonsterMoves.next` 对未登记类型必须抛 `IllegalArgumentException`（不静默回退）。

全量 **290 条**通过。

### 33.22 GremlinThief / Looter —— 查证结果和原先的假设不一样（2026-10-25）

两只都曾被记成「卡在金币上」，读完全部字节码后结论**都推翻了**：

| 怪 | 原先的假设 | 实际情况 |
|---|---|---|
| `GremlinThief` | 偷金币，然后 `escapeNext` 逃跑 | 类里**一次都没提过 gold/Steal**；`escapeNext` 只有读、**没有任何地方置位**（死代码）→ 它就是每回合打 9（A2: 10）的普通怪 |
| `Looter` | 逃逸时机看金币 | 逃逸是**自己按招序排的**（`takeTurn` 里直接 `EscapeAction`），与金币无关；金币只影响偷多少 |

`Looter` 的招序（`getMove` 恒为 1，其余靠 `SetMove`）：

```
1 Mug   → 一段 swipe 10（A2: 11），slashCount++；
          第 2 次之后掷硬币：2(Smoke Bomb) 或 4(Lunge)，否则继续 1
4 Lunge → 一段 lunge 12（A2: 14），排 2
2 Smoke → 自己 +6 格挡，排 3
3       → EscapeAction：离场
```

`slashCount` 从历史里数「1 出现了几次」即可（不需要额外状态）。

**新载荷 `MonsterMove.escape`**：`CombatFlow` 里把 `hp` 置 0 并跳过后续结算 ——
逃离是「离场」不是「被杀」，不结算掉落。胜利判定仍是「全部 hp ≤ 0」，够用。

⚠️ 又一次「木桩」事故：`DeathSaveRelicTest` 的木桩先是被 `JawWorm` 撞、改成 `Looter` 又被撞。
现在改成 `TestMonster`（游戏里不存在、永远不会被镜像）。**这条规则该记住：
测试木桩一律用不存在的类型名。**

验证 3 条（小偷只打人 / 掠夺者的抹-掷硬币-烟雾弹-逃跑链 / 逃跑真的离场），
全量 **293 条**通过。

### 33.23 道歉史莱姆（ApologySlime）+ 木桩规则再钉一次

`getMove`：`aiRng.randomBoolean() ? 1(ATTACK, 3) : 2(DEBUFF, 玩家 Weak 1)` ——
注意它在 `getMove` 里**又掷了一次** `randomBoolean()`（`next` 开头那次 `num` 之外再消耗一格），照抄。
HP 8-12、tackle 3、Weak 1。

⚠️ **木桩事故第三次**：`unmirroredMonstersKeepTheOldBehaviour` 用 `ApologySlime` 当「未镜像」样本，
它这一被搬就又红。现在两处木桩全部改成 `TestMonster` / `"TestMonster"` ——
游戏里不存在的名字，永远不会被镜像。

规则定死：**测试里凡是要「未镜像」语义的地方，一律用 `TestMonster`，不许用真怪名。**

### 33.24 第二章开张：Byrd / Chosen / ShelledParasite（2026-10-25）

三只怪各带一个新机制，都落在既有咽喉上：

| 怪 | 新机制 | 落点 |
|---|---|---|
| `Byrd` | **Flight（飞行）**：挨打掉 1 层、掉光就落地（STUN 一回合） | `DamageCalc.tickFlight` |
| `Chosen` | **Hex（诅咒）**：玩家每打一张**非攻击牌**，往抽牌堆随机位置塞 1 张 Dazed | `Hooks.hex` |
| `ShelledParasite` | **吸血**（打掉多少回多少）+ **破甲硬直**（Plated Armor 归零 → STUN） | `MonsterMove.vampire` / `DamageCalc.tickPlatedArmor` → `MonsterMoves.onArmorBreak` |

#### Byrd

```
getMove:  firstMove → 掷硬币：6(Caw, +1 力量) / 1(Peck, 1 伤 × peckCount)
          飞着：num<50 → 连续两招 1 ? 掷硬币(3 Swoop : 6) : 1
                num<70 → lastMove(3) ? 掷硬币(6 : 1) : 3
                否则   → lastMove(6) ? 掷硬币(3 : 1) : 6
          落地 → 5(Headbutt, 3 伤)
takeTurn: 2 → 挂 Flight=flightAmt；5 → 3 伤后**排 2 再起飞**；4 → 空过（STUN）
常数:     peck 1×5（A2: 1×6）、swoop 12（A2: 14）、headbutt 3、Flight 3（A17: 4）
```

🔴 **Flight 的判据是「每段掉 1 层」，不是「按伤害掉」**：5 段 1 伤的小啄能一口气打掉 5 层，
而一发 30 伤的重击也只掉 1 层。原来的 `tickFlight` 有两个错：
**(a) 被格挡全吃掉也掉层**（原版 `damageAmount > 0` 是**过完格挡**的数）；
**(b) 这一下打死也掉层**（原版还有 `damageAmount < currentHealth`）。都按字节码改掉了。
另外飞着时**伤害减半**（`calculateDamageTakenAmount`：非 HP_LOSS/THORNS 时 `damage / 2`），
所以 1 点伤害会被砍成 0 —— 那一击既不掉血也不掉层（测试钉住）。

`isFlying` 从历史反推：最近一次「状态招」是 2（起飞）还是 4/5（落地后的招）。

#### Chosen

```
getMove:  A18+：没放过诅咒 → 4(Hex)；否则 上一招既不是 3 也不是 2 时 num<50 → 3 : 2；否则 num<40 → 1 : 5
          其余：第一回合 → 5(双击)；没放过诅咒 → 4(Hex)；之后同上
takeTurn: 1 → 18（A2: 21）；2 → 玩家 Weak 3 + **自己 +3 力量**；
          3 → 10（A2: 12）+ 玩家 Vulnerable 2；4 → 玩家 Hex 1；5 → 5（A2: 6）×2
```

`HexPower.onUseCard`：`card.type != ATTACK` → `MakeTempCardInDrawPileAction(new Dazed(), amount, true, true)`。
不建模的后果是方向性错误（搜索会以为「多打技能牌没代价」）。

#### ShelledParasite

```
getMove:  第一回合：A18+ → 1(Suck)；否则掷硬币 2(双击) / 3(Fell)
          num<20：上一招不是 1 → 1；否则**递归重掷**（aiRng.random(20,99) 再走一遍）
          num<60：最近两招不是 2 → 2；否则 3
          否则：  最近两招不是 3 → 3；否则 2
takeTurn: 1 → 10（A2: 12）+ 玩家 Frail 2；2 → 6（A2: 7）×2；
          3 → 18（A2: 21）+ **吸血**（VampireDamageAction）；4 → 空过（破甲硬直）
```

两条外部触发：
- **吸血**：`VampireDamageAction` 回的是「玩家**真正掉的血**」——格挡挡掉的部分不回（测试钉住 18 伤 / 10 格挡 → 只回 8）。
- **破甲硬直**：`PlatedArmorPower.onRemove` → `ChangeStateAction("ARMOR_BREAK")` → 这只怪的
  `changeState` 翻成 `setMove(4, STUN)`。落点 `tickPlatedArmor`，用 `MonsterMoves.onArmorBreak`
  做「谁认这个状态」的分派（目前只有它）。

顺带给 `RngStream` 补了 `random(int min, int max)`（`nextInt(max-min+1)+min`，两端都含，字节码直读）
—— 递归重掷那步要用。

验证 7 条测试，全量 **303 条**通过；累计 **30 只**。

### 33.25 第二章续：Centurion / Healer / Snecko（2026-10-25）

| 怪 | 决策要点 | 新增载荷 |
|---|---|---|
| `Centurion` | `num>=65` 且最近两招都不是 2/3 时看**队友**：有队友 → 2（格挡），落单 → 3（怒击） | 复用 `blockRandomAlly` |
| `Healer` | 先看**全队缺损血量**：>15（A17: 20）且最近两招不是 2 → 治疗；否则 `num>=40` 且没连着打 → 攻击；否则全队加力量 | `healAll` / `buffAll` |
| `Snecko` | 首招 Tail Whip（只挂 Confusion，**不造成伤害**），之后 `num<40` → Glare，否则 Bite（连咬两口换 Glare） | `attackDoubleDebuff` + **Confusion 建模** |

数值：Centurion slash 12（A2: 14）、fury 6（A2: 7）×3、格挡 15（A17: 20）；
Healer magic 8（A2: 9）、力量 2（A2: 3 / A17: 4）、治疗 16（A17: 20）、Frail 2；
Snecko bite 15（A2: 18）、tail 8（A2: 10）、Glare 上 Vulnerable 2（A17 起再加 Weak 2）。

#### Confusion（混乱）—— 这条不建模会让整手牌的费用算错

`ConfusionPower.onCardDraw`（字节码直读）：

```java
int newCost = AbstractDungeon.cardRandomRng.random(3);   // **每张牌都掷，掷了就算数**
if (newCost != card.cost) { card.cost = newCost; card.costForTurn = card.cost; }
```

落点 `CombatFlow.applyConfusion`（在抽牌动作里）：有 `Confused` 时**每抽一张都消耗一次
`cardRandomRng`**，值不同就替换成一张新 `SimCard`（`cost` 改掉，`costForTurn` 归零）。

测试钉住两件事：**5 张牌 = 计数器 +5**（掷了就要消耗，哪怕结果没变）、每张牌费用落在 0..3
且至少有一张真被改（否则等于没建模）。

累计 33 只；全量 **308 条**通过。

### 33.26 BookOfStabbing / Mugger / Taskmaster（2026-10-25）

| 怪 | 决策 | 招式 |
|---|---|---|
| `BookOfStabbing` | `num<15`：上一招是 2 → 多刺，否则重刺；`num>=15`：连着两发多刺 → 重刺，否则多刺 | 多刺 6（A3: 7）× **stabCount**；重刺 21（A3: 24） |
| `Mugger` | 与 Looter 同构：抹两次 → 掷硬币（烟雾弹/突袭）→ 烟雾弹 → 逃跑 | 抹 10（A2: 11）；突袭 16（A2: 18）；烟雾弹 11（A17: 17） |
| `Taskmaster` | 永远 2 | 7 伤 + 2 张 Wound（A18: 3 张 + 自己 +1 力量） |

**stabCount 从历史反推**：初值 1，每打一发多刺就 +1；**A18 起打重刺也 +1**
（原版在选招那一刻就自增，所以「下一发几段」在选招时就定了 —— 机器用 `count(history)+1` 表达）。

**新机制 Painful Stabs**（BookOfStabbing 开场自带）：`onInflictDamage` 里
`type != THORNS` → 往玩家弃牌堆塞 1 张 Wound，**每段一次**。落在 `CombatFlow.takeTurn` 的逐段循环里
（测试：3 段攻击 → 3 张 Wound）。

全量 **312 条**通过；累计 **36 只**。

### 33.27 BanditPointy（面具强盗事件）2026-10-25

`getMove` 恒为 1（两段攻击），每段 5（A2: 6），HP 30（A7: 34）。没有额外机制 —— 加它是为了覆盖率。

全量 **313 条**通过；累计 **37 只**。

### 33.28 召唤基础设施 + 5 只（GremlinLeader / TorchHead / TheCollector / BronzeOrb / BronzeAutomaton）2026-10-26

这一批的**重点不是五只怪，是「召唤」这个机制**——三只 boss/精英共用它。

#### 原版三种写法，一个抽象

| 原版 | 写法 | 落成什么 |
|---|---|---|
| `SummonGremlinAction(gremlins)` | `identifySlot` 找第一个 `null` 或 `isDying` 的槽（共 3 个），`getRandomGremlin` 现造一只 | 3 槽 + 随机类型 |
| TheCollector 的 `enemySlots` | 1 号招 `HashMap.put(1/2, new TorchHead)`，5 号招遍历把 `isDying` 的槽逐个换新 | 2 槽 + 固定类型 |
| `SpawnMonsterAction(new BronzeOrb(...), true)` | 直接造两只 | 2 只 + 固定类型 |

统一成 `MonsterMove.Summon{pool, count, cap, minion}`：
**尝试 `count` 次，该池活着的不超过 `cap` 只**。`identifySlot` 的「空槽」=「活怪数 < cap」。

#### 🔴 三处认知被字节码推翻

1. **不是「预置 3 只小鬼」**。`GremlinLeader.usePreBattleAction` 只做两件事：
   把开场就在的两只登记进 `gremlins[0..1]`（`monsters.get(0)`/`get(1)`），`gremlins[2]=null`；
   给这两只挂 `MinionPower`。**构造器里没有任何随机**。
2. **池子 8 项带重复**：`Warrior / Warrior / Thief / Thief / Fat / Fat / Tsundere / Wizard`，
   `aiRng.random(0,7)` 抽 → 战士 1/4。
3. **召唤物血量走 `monsterHpRng` 而不是 `MathUtils.random`**：
   `AbstractMonster.setHp(int,int)` 的实现就是
   `currentHealth = AbstractDungeon.monsterHpRng.random(min, max)`（字节码直读）。
   → `SimState.RngKind` 补了 `MONSTER_HP`（原先只捕获 5 条流）。
   **这条补上之后，召唤出来的血是精确值，不再是近似中值。**

#### 随机消耗次序（错一格就整条序列错位）

```
Rally（GremlinLeader 2 号招）：
  takeTurn 里 addToBottom 两次 SummonGremlinAction
    → 构造 #1：aiRng.random(0,7) 选类型 + monsterHpRng.random 抽血
    → 构造 #2：aiRng.random(0,7) 选类型 + monsterHpRng.random 抽血
    → update #1：monster.init() → aiRng.random(99)
    → update #2：monster.init() → aiRng.random(99)
```
也就是 **类型1/血1 → 类型2/血2 → 招1 → 招2**，不是「一只一只走完」。
所以 `CombatFlow.applyExtra` 先造完所有只、再逐个 `MonsterMoves.next`。

#### GremlinLeader 的 `getMove` 会递归（且区间不同）

「只剩 1 只小鬼」档里有两处 `getMove(...)` 自我调用：
- `num < 50 && lastMove(2)` → `getMove(aiRng.random(50, 99))`
- `num >= 80 && lastMove(4)` → `getMove(aiRng.random(0, 80))`

⚠️ `random(0,80)` 的结果**可能又 < 50**，于是再递归 —— 「最多两层」是错的。
且 `random(50,99)` 是 `nextInt(50)`、`random(99)` 是 `nextInt(100)`，
**内部消耗的随机位可能不同**，混用会让后续错位。
实现：`Context.rollAiRange(min,max)` + 循环（不做深度截断）。

#### 五只怪

| 怪 | 决策 | 招式 |
|---|---|---|
| `GremlinLeader` | 三档：无小鬼（num<75 优先 Rally）/ 1 只（num<50 优先 Rally）/ ≥2 只（num<66 优先喊话） | Rally 召唤；喊话全队 +str、**队友** +block；三连刺 6×3（不随进阶） |
| `TorchHead` | 恒 1 | 7 伤 |
| `TheCollector` | `initialSpawn`→1；`turnsTaken≥3 && !ultUsed`→4；`num≤25 && 有火把头死 && !lastMove(5)`→5；`num≤70 && !lastTwoMoves(2)`→2；否则 3/2 | 召 2 只；rake 18（A4: 21）；格挡 15(+5@A19) + 全队力量；Weak/Vulnerable/Frail 各 3（A19: 5） |
| `BronzeOrb` | `!usedStasis && num≥25`→3（**在 getMove 里就置位**）；`num≥70 && !lastTwoMoves(2)`→2；否则 1/2 交替 | 8 伤；给 **BronzeAutomaton** +12 格挡；Stasis |
| `BronzeAutomaton` | `firstTurn`→4；`numTurns==4`→2 并清零；`lastMove(2)`→A19?5:3；`lastMove(3\|5\|4)`→1；否则 5 | 两段 7（A4: 8）；BEAM 45（A4: 50）；STUN 空过；BOOST 召 2 个球；5 = 格挡 9（A9: 12）+ 力量 3（A4: 4） |

**`numTurns` 只能从历史重放**：它只在「走到 getMove 末尾」的两条路径自增，
早期 return（firstTurn / numTurns==4 / lastMove(2)）不增。见 `MonsterMoves.replayNumTurns`。
固定循环：`4 → 1 → 5 → 1 → 5 → 2 → 3 → 1 → …`（测试锁死前 8 步）。

#### 未建（明确记录，不静默）

- **BronzeOrb 的 Stasis 偷牌**：`ApplyStasisAction` 按 RARE→UNCOMMON→COMMON→任意
  在玩家弃牌堆/抽牌堆挑一张抽走。`SimCard` 不带稀有度，没建。
  方向 = 影子**高估**手牌一张（略乐观）；节奏本身对（3 号招就是空过）。
- 召唤物的 `applyPowers()`：原版会给新怪补「基础力量」等，我们只挂 `Minion`。

全量 **326 条**通过；累计 **42 只**。

### 33.29 Champ（勇士）三阶段 2026-10-26

**结论先行：所谓「多阶段 boss」不需要任何新钩子** —— `Champ` 没有 `damage()` / `die()` 重写，
它在 `getMove` 里读三个普通字段。这三个字段影子都不存，全部由 `moveHistory` 重放。

| 字段 | 重放规则（照抄原版副作用） |
|---|---|
| `numTurns` | 决策开头无条件 +1；**走 Taunt（6）那条路径时清零** |
| `thresholdReached` | `history.contains(7)`（狂暴只发生一次） |
| `forgeTimes` | `countOf(history, 2)`（姿态最多 2 次） |

分支顺序（**不能换**，原版就是这个优先级）：

```
1. hp*2 < maxHp && !threshold     → 7 狂暴：自 +3×力量（A19: 4×3）+ 清自身所有减益
2. !last(3) && !last2(3) && threshold → 3 连打两下重斩（baseDamage(1,10)）
3. numTurns == 4 && !threshold    → 6 Taunt：只上 Weak 2 + Vulnerable 2，不打人
4. !last(2) && forgeTimes < 2 && num <= (A19 ? 30 : 15) → 2 姿态：Metallicize 5/6/7 + 格挡 15/18/20
5. !last(5) && !last(2) && num <= 30 → 5 Gloat：自 +力量 2/3/4
6. !last(4) && num <= 55          → 4 Face Slap：12/14 伤 + Weak 2 + Vulnerable 2
7. 否则 → last(1) ? Face Slap : 1 Heavy Slash 16/18
```

### 「清自身减益」用白名单

原版是 `new RemoveDebuffsAction(this)`，按 `PowerType.DEBUFF` 逐个移除。影子只存能力 ID，
所以 `Extra.cleanseSelf` 走一个白名单（`CombatFlow.SELF_DEBUFFS`：Weakened / Vulnerable / Frail /
Shackled / Lose Strength / Lose Dexterity / Strength Down / Dexterity Down / Slow / Constricted / Poison）。
用「按名字筛」的偏差方向是**漏清**（影子高估怪物被削弱的程度），偏保守，可以接受。

### 测试坑（顺序表型怪物通用）

给 Champ 写历史时**历史长度本身会推进 `numTurns`**，第 4 次决策会被 Taunt 抢走。
`history(2,5,2,4)` + `num=10` 落到 Gloat(5) 而不是重斩(1) —— 因为姿态被 `forgeTimes` 挡住后，
`num≤30` 命中的是第 5 条。**先算 `numTurns`，再挑 `num` 落档**，否则会误判成机器写错。

全量 **331 条**通过；累计 **43 只**。

### 33.30 Hexaghost：状态字段是 `orbActiveCount`，不是「6 个 orb」2026-10-26

**两个假设都被字节码推翻**：① 没有 `damage()`/`die()` 重写；② `HexaghostOrb` 是**纯视觉对象**
（`createOrbs()` 按 6 个坐标 new 出来塞进 `ArrayList`，构造参数 `(x, y, index)`，无血、不可选中）。

出招由 int 字段 `orbActiveCount` 驱动，只有三处 `changeState` 改它：

| changeState | 调用点 | 副作用 |
|---|---|---|
| `Activate` | takeTurn 5 号分支（第 1 回合） | `orbActiveCount = 6` |
| `Activate Orb` | Sear / Tackle / Strengthen 结尾 | `+1`；**到 6 时 `setMove(6, Inferno)`** |
| `Deactivate` | Divider / Inferno 结尾 | `= 0` |

`getMove` = `tableswitch(orbActiveCount)`：0/2/5→Sear(4)、1/4→Tackle(2)、3→Strengthen(3)、6→Inferno(6)。
`num` 抽了不看 → **确定性循环**：Activate → Divider → Sear→Tackle→Sear→Strengthen→Sear→Tackle→Sear→Inferno → 循环。

`Divider` 不在 `getMove` 里：takeTurn 5 号分支现算 `玩家当前血/12+1`、`applyPowers()`、
`setMove(1, ATTACK, base, 6, true)`。所以影子用 `if (lastMove(5)) return divider(...)`。

进阶：A4 起 Tackle 6 / Inferno 3；A19 起 力量 3、Sear 塞 2 张；HP 250（A9 起 264）。
`searDmg=6` 与 `strengthenBlockAmt=12` 全档一致。

**未建模**：Burn 的回合末伤害（2/4）—— 状态牌机制，不在怪物 AI 层；偏差方向 = 低估玩家受伤。
`burnUpgraded` 因此没有可观测效果，只记录不实现。

全量 **336 条**通过；累计 **44 只**（Act 1 三十只全齐）。

### 33.31 面具强盗（BanditBear / BanditLeader）+ 负增量 bug 2026-10-26

两只的 `getMove` 都只有一句（熊 `setMove(2, STRONG_DEBUFF)`、头目 `setMove(2, UNKNOWN)`）——
**只用于开局**，之后全靠 `takeTurn` 里的 `SetMoveAction`。所以从 `moveHistory` 重放即可。

| 怪 | 节奏 | 备注 |
|---|---|---|
| `BanditBear` | 2 Stun(敏捷 -2/A17: -4) → 3 Slash(9\|10 伤 + 9 格挡) → 1 Maul(18\|20 伤) → 3 → 1 … | HP 老档 38-42 / A7 起 40-44（`setHp` → `monsterHpRng`） |
| `BanditLeader` | 2 喊话 → 3 Agonize(10\|12 伤 + Weak 2/A17: 3) → 1 Slash(15\|17 伤) → 3 → 1 … | A17 起 Slash 后若 `!lastTwoMoves(1)` 再刺一下 → 3→1→1→… |

### 🔴 `ApplyPowerAction(amt)` 的 amt 一定是带符号增量

踩坑链：Bear Stun 的敏捷扣不掉 → 查出 `MonsterMove` 构造器 `Math.max(0, playerDebuffAmount)`
把负数夹成 0 → 再查出 `CombatFlow` 落地条件是 `> 0`（第二道闸门）→ 连带发现
**Lagavulin 的 Siphon Soul 一直写成 `+1`（应为 `-1`，A18 `-2`）**，因为 clamp 让它读出来是 0，
而旧测试只断言字段名不断言符号。

现在：构造器不再夹、三处落地条件改 `!= 0`、`MonsterMove.debuff(id, power, amt)` 新工厂
（专门表达「只上减益、不打不挡」），并加了 CombatFlow 级集成测试钉住符号。

**自查口径**：凡是模型里表示「玩家能力增量」的字段，问一句「原版这里传的是负数吗」；
是就必须能让负数穿到底（构造器 + 落地 + 读数三处都不能夹）。

### 33.32 第三章先头 5 只（OrbWalker / Maw / Transient / Repulsor / Spiker）2026-10-26

| 怪 | 招式表 | 需要的新东西 |
|---|---|---|
| `OrbWalker` | `num<40` 先 2 号（15\|16），否则先 1 号（10\|11 + Burn）；`lastTwoMoves` 互斥 | 无（原版字段名反着存，照数值抄） |
| `Maw` | Roar（Weak+Frail 3\|5）→ Nom（5 伤 × `turnCount/2` 段）/ Bellow（+3\|5 力量）/ Slam（25\|30） | 无（`turnCount = history.size()+2`） |
| `Transient` | 一招递增：`30|40 + 10×已出招数` | **Fading 递减**（通用，放 CombatFlow） |
| `Repulsor` | 80% 塞 Dazed×2 进**抽牌堆**，20% 打 11\|13，不连打 | **`Extra.statusToDraw`** |
| `Spiker` | 刺 7\|9 / Sharpen +2 荆棘（最多 6 次） | 无 —— `applyThorns` 早就有 |

### 两处通用机制

- **`FadingPower.duringTurn`**：怪物回合开始时 amount-1，减到 0 当场死、这回合不出招。
  不建的话 Transient 会无限打（30/40/50…），搜索会严重悲观。
- **`Extra.statusToDraw(card, n)`**：`MakeTempCardInDrawPileAction`。插入位置在**抽牌堆中间**
  （原版随机位置用 libgdx `MathUtils.random`，不吃 run seed，无法也不必复现）。

### 记录在案：SpireGrowth / Exploder 暂缓

- `SpireGrowth` 的 Constrict 是 `ConstrictedPower.atEndOfTurn` → `DamageInfo(type=THORNS)`，
  需要在玩家回合末加一条「缠绕掉血」；本次先不做（否则它的威胁会被算成 0）。
- `Exploder` 的 `ExplosivePower` 在字节码里**只有构造器与 updateDescription，没有任何 hook**，
  `takeTurn` 的 2 号分支也是空 `goto`、没有 `die()` 重写 —— 爆炸触发点还没找到，先不猜。

全量 **348 条**通过；累计 **51 只**。

### 33.33 Deca / Donu（第三章 boss 对）2026-10-26

一个 `isAttacking` 字段管两只：初值 **true**，攻击招（id 0）后置 false、辅助招（id 2）后置 true
→ 出招恒为「攻击 ↔ 辅助」，第一招必攻击。重放：`attacking = 上一招不是 0`。

| | 攻击（0） | 辅助（2） |
|---|---|---|
| `Deca` | 2 段 × 10（A4: 12）+ 弃牌堆 2 张 Dazed | 全体（含自己）16 格挡；A19 起再给全体 +3 Plated Armor |
| `Donu` | 2 段 × 10（A4: 12） | 全体 +3 力量 |

HP 都是 250（A9: 265）；开场 Artifact 2（A19: 3）由 `usePreBattleAction` 挂，快照里有。

新增载荷组合：`multiAttackStatus` / `blockAll` / `blockAllAndBuffAll`。

全量 **353 条**通过；累计 **54 只**。

### 33.34 Exploder：爆炸在 power 的 duringTurn 里 2026-10-26

`ExplosivePower(3)` 由 `usePreBattleAction` 挂上，`duringTurn()` 每回合递减，
`amount == 1` 时：`SuicideAction(owner)` → `DamageAction(player, new DamageInfo(owner, 30, THORNS))`。
`takeTurn` 的 2 号分支是空 `goto` —— 那一招只是意图显示。

影子用 `Extra.selfDestruct`：打完（30 点，THORNS → 吃格挡）把自己 hp 置 0。
节奏：打两下（9|11）→ 第三回合自爆。

**方法论**：`javap -p`（不带 `-c`）先列方法名，再决定看哪段字节码。
上一轮因为只 grep 了 `-c` 的片段，误判成「这个 power 没有任何 hook」。

全量 **355 条**通过；累计 **55 只**。

### 33.35 GiantHead + Slow（第三章精英）2026-10-26

`count` 初值 5、每次 `getMove` 开头 -1；`count(减之前) > 1` 时按 num 在 Glare / Count(13) 间轮换，
`≤ 1` 后进入 It's Time：伤害 `= startingDeathDmg - count*5`（count 封底 -6 → 最高 +30）。
Glare 只上 **Weak 1**；HP 500（A8: 520）；`startingDeathDmg` 30（A3: 40）。

**SlowPower**（真正的机制）三处落地：
- `onAfterUseCard` → `amount++`（**每张牌**，不分类型）：`Hooks.monsterOnCardUsed`；
- `atDamageReceive` → `damage × (1 + 0.1×amount)`（只对 NORMAL）：`DamageCalc.attackDamageWithoutRelics`；
- `atEndOfRound` → `amount = 0`：`CombatFlow` ④.5 段。

全量 **358 条**通过；累计 **56 只**。

### 33.36 Nemesis + Intangible 的 justApplied 2026-10-26

`IntangiblePower.atEndOfTurn`：`if (justApplied) { justApplied = false; return; }` —— **刚挂上不 tick**。
影子用 `DamageCalc.INTANGIBLE_FRESH` 记这个标志（`markIntangibleFresh` / `tickIntangible`）。
不记的话：幽魂形态少算一回合；Nemesis 的无实体在玩家回合前就掉光（等于没有）。

Nemesis：`scytheCooldown`（0 起、每次 -1、巨镰后归 2）+ 三分支 num 状态机 + 额外硬币；
每招末尾补 Intangible（`Extra.onlyIfAbsent`）。HP 185（A8: 200）、火 6（A3: 7）、
巨镰 45、Burn 3 张（A18: 5）。

全量 **363 条**通过；累计 **57 只**。

### 33.37 Darkling（三只联动）+ SnakeDagger / Malleable 2026-10-26

**Darkling**：第一次致死 → `halfDead = true`（血留 1 + `Half Dead` 标记）、下回合 `5 Reincarnate`
（`HealAction(maxHealth/2)`）；**三只全部半死才一起真死**（原版同一段里干掉所有 Darkling）。
`getMove` 有两条递归（`random(40,99)` / `random(0,99)`），`slot % 2 == 0` 才允许 Nip。

**Malleable**（`MalleablePower.onAttacked`）：先加 `amount` 点格挡，再 `amount++`；
`atEndOfTurn` 重置回 `basePower`(3)。**不是**「固定挡 3 三次」。格挡从下一段起生效。

**SnakeDagger**：9 伤 + Wound → 25 伤 + 自爆。

全量 **369 条**通过；累计 **59 只**。

### 33.38 Act 4 精英双子：SpireShield / SpireSpear（2026-10-26）

两只共用 `moveCount % 3` 骨架，但**各自的 moveCount 都只由 `getMove` 末尾 +1** →
「本次决策看到的 moveCount」= `history.size()`（第一次 = 0）。开局 Artifact
（A18: 2 / 否则 1）是 `usePreBattleAction` 挂的，快照已有；玩家身上的 `SurroundedPower`
`javap -p` 核实**没有任何 hook**（只翻立绘），不建。

**SpireShield**（HP 110｜A8: 125，`damage[0]` BASH 12｜A3:14，`damage[1]` SMASH 34｜A3:38）：

| moveCount%3 | 招 |
|---|---|
| 0 | `aiRng.randomBoolean()` ? 2 FORTIFY : 1 BASH |
| 1 | `!lastMove(1)` ? 1 BASH : 2 FORTIFY |
| 2 | 3 SMASH |

- **BASH**：`damage[0]` → 玩家 `orbs` 非空 && `randomBoolean()` ? `Focus -1` : **`Strength -1`**。
  铁甲战士没有 orb → 恒走力量 -1，**而且这条路径不抽随机**（randomBoolean 在 orbs 分支里面）。
- **FORTIFY**：`for (m : monsters) GainBlockAction(m, this, 30)` —— **全体含自己**，
  用 `blockAll(2, 30)`（主字段 30 = 自己，`Extra.blockAllies` = 队友）。
- **SMASH**：`damage[1]` → A18 起自己 `+99` 格挡，否则 `+damage[1].output`。
  `output` 是算完力量/虚弱/易伤之后的数 → 新增 `Extra.blockFromDamage`，在 `CombatFlow.takeTurn`
  里用 `DamageCalc.monsterAttackDamage` 重算一次（纯函数，不吃随机）。

**SpireSpear**（HP 160｜A8: 180，`skewerCount` 3｜A3:4，`damage[0]` 5｜A3:6，`damage[1]` 10）：

| moveCount%3 | 招 |
|---|---|
| 0 | `!lastMove(1)` ? 1 SKEWER : 2 PREPARE |
| 1 | 3 BURN STRIKE（**与历史无关**，每三回合固定来一次） |
| 2 | `randomBoolean()` ? 2 PREPARE : 1 SKEWER |

- **SKEWER**：**字面量 2 段**（`takeTurn` 里 `iconst_2` 的循环，不是 `skewerCount`）× `damage[0]`；
  然后 A18 起 2 张 Burn 进**抽牌堆**（`multiAttackStatusToDraw`），否则进弃牌堆（`multiAttackStatus`）。
- **PREPARE**：`for (m : monsters) Strength +2` —— 全体含自己（`buffAll(2,"Strength",2)`）。
- **BURN STRIKE**：`skewerCount` 段 × `damage[1]`。

全量 **375 条**通过；累计 **62 只**（Act 4 精英双子开张）。

### 33.39 CorruptHeart（腐化之心，Act 4 boss）2026-10-26

HP 750（A9: 800）；`damage[0]` Echo 40（A4:45）、`damage[1]` Blood Shots **每段 2**；
`bloodHitCount` 12（A4:15）。开场 `usePreBattleAction` 挂 `Invincible 300`（A19:200）+
`Beat of Death 1`（A19:2）—— 快照里已有。

🔴 **`moveCount` 不等于 history.size()**：第一招（Debuff）那一支提前 `return`，**没有 +1**。
所以「本次决策看到的 moveCount」= `history.size() - 1`。

| 条件 | 招 |
|---|---|
| history 空 | 3 Debuff（并置 isFirstMove=false） |
| moveCount%3 == 0 | `aiRng.randomBoolean()` ? 1 Blood Shots : 2 Echo |
| moveCount%3 == 1 | `!lastMove(2)` ? 2 Echo : 1 Blood Shots |
| moveCount%3 == 2 | 4 Buff |

- **Debuff**：玩家 Vulnerable 2 + Weak 2 + Frail 2（**三样都写死 2**，不随进阶变），
  外加 Dazed / Slimed / Wound / Burn / Void **各 1 张** 进抽牌堆 → 新增
  `Extra.drawPileCards`（`Extra.statusDrawCard` 只装得下一张）。
- **Buff**：`strGain = -min(0, Strength)`（先把负力量补回来）→ `Strength + (strGain + 2)`；
  再按 `buffCount`（= 历史里 4 的个数）追加一样：0→Artifact 2、1→Beat of Death 1、
  2→Painful Stabs、3→Strength 10、其余→Strength 50。一次两个能力 → 新增
  `Extra.extraSelfBuff`（主字段装 Strength，Extra 装第二样）。
- **Beat of Death**（`onAfterUseCard`）：每打一张牌 `DamageAction(player, owner, amount, THORNS)`
  → THORNS 不是 HP_LOSS，**吃格挡**。落在 `Hooks.monsterOnCardUsed`。

✅ **Invincible 已补**（同日）：`InvinciblePower.onAttackedToChangeDamage` 是**每回合累计上限**
（逐次扣同一个 amount，扣完就归零），`atStartOfTurn` 重置成 `maxAmt`。
落点：`DamageCalc.capByInvincible`（伤害落地前夹一次）+ `CombatFlow` ④.0（怪物回合开始重置）+
`SimMonster.invincibleMax`（`maxAmt` 是私有字段，捕获时反射读一份，读不到退回当前值）。

全量 **379 条**通过；累计 **63 只**（Act 4 全部三只齐了；机器表 63 项）。

### 33.40 WrithingMass（缠绕蔓，第三章）2026-10-26

HP 160（A7: 175）。`damage[0]` 重击 32（A2:38）、`damage[1]` 三连击每段 7（A2:9）、
`damage[2]` 打+挡 15（A2:16）、`damage[3]` 打+减益 10（A2:12）；`normalDebuffAmt` 两档都是 2。

**getMove 是一棵递归树**（分支里会再调 `getMove(aiRng.random(...))`），所以机器也写成递归，
随机数按原版区间逐个抽：

| 条件 | 结果 |
|---|---|
| firstMove（history 空） | num<33 三连击(1)｜num<66 打+挡(2)｜否则 打+Weak2+Vuln2(3) |
| num<10 | !lastMove(0) ? 重击(0) : `getMove(random(10,99))` |
| num<20 | !usedMegaDebuff && !lastMove(4) ? 植入(4) : (`randomBoolean(0.1)` ? 重击 : `getMove(random(20,99))`) |
| num<40 | !lastMove(3) ? 打+减益(3) : (`randomBoolean(0.4)` ? `getMove(random(19))` : `getMove(random(40,99))`) |
| num<70 | !lastMove(1) ? 三连击(1) : (`randomBoolean(0.3)` ? 打+挡(2) : `getMove(random(39))`) |
| 否则 | !lastMove(2) ? 打+挡(2) : `getMove(random(69))` |

- **打+挡的格挡 = `damage[2].base`**（字节码 `GainBlockAction(this, this, damage.get(2).base)`），
  和攻击伤害同源，不是单独一个常量。
- **4 号招「植入」= `AddCardToDeckAction(new Parasite())`** —— 污染的是**主牌组**
  （局外/长线代价），战斗层这一回合什么都不发生（`usedMegaDebuff` 在 **takeTurn** 里置 true，
  不在 getMove，所以从历史里数 4）。
- **「挨打改招」不在 `damage()` 重写里**（那里只有动画）：是
  `ReactivePower`（**ID = `Compulsive`**）的 `onAttacked` ——
  `damage > 0 && damage < currentHealth`（没打死）→ `addToBot(new RollMoveAction(owner))`，
  每挨一段就重掷一次。我们落在 `DamageCalc.hit` 的 `maybeRerollCompulsive`：
  `pendingMove = MonsterMoves.next(...)`，恰好消耗一次 `aiRng.random(99)`（+ 递归随机）。
  ⚠️ `hit()` 没有 `DamageInfo.type`，THORNS/HP_LOSS 那两类也会重掷 —— 只多消耗随机，不改数值。

全量 **383 条**通过；累计 **64 只**（剩 `AwakenedOne` / `TimeEater`）。

### 33.41 AwakenedOne（觉醒者，第三章 boss，两形态）2026-10-26

HP 300（A9: 320）。`damage[]`：`[0]` SLASH 20、`[1]` SOUL_STRIKE 每段 6（4 段）、
`[2]` DARK_ECHO 40、`[3]` SLUDGE 18、`[4]` TACKLE 每段 10（3 段）。
开场 `usePreBattleAction`：`room.cannotLose = true`；Regenerate 10（A19:15）、
Curiosity 1（A19:2）、Unawakened；A4 起 Strength +2 —— 全在快照里。

| 形态 | 条件 | 招 |
|---|---|---|
| 一 | history 空 | 1 SLASH |
| 一 | num<25 | !lastMove(2) ? 2 SOUL_STRIKE(6×4) : 1 SLASH |
| 一 | 否则 | !lastTwoMoves(1) ? 1 SLASH : 2 SOUL_STRIKE |
| 二 | 还没出过 5/6/8 | 5 DARK_ECHO 40 |
| 二 | num<50 | !lastTwoMoves(6) ? 6 SLUDGE : 8 TACKLE(10×3) |
| 二 | 否则 | !lastTwoMoves(8) ? 8 TACKLE : 6 SLUDGE |

- **SLUDGE 的「debuff」其实是塞牌**：`MakeTempCardInDrawPileAction(new VoidCard(), 1, …)` ——
  1 张 Void（状态牌）进抽牌堆，不是能力。意图仍是 `ATTACK_DEBUFF`。
- 🔴 **换形态在 `damage()` 重写里，不在 `die()`/`getMove` 里**：血量归零且 `!halfDead` →
  `halfDead = true`、房间 `cannotLose`、**摘掉所有 DEBUFF + Curiosity/Unawakened/Shackled**、
  `setMove(3, UNKNOWN)`、`firstTurn = true; form1 = false`。
  影子落在 `DamageCalc.maybeAwaken`：血留 1（同 Darkling 口径）、打 `Half Dead`、
  摘能力/减益、把 `pendingMove` 直接钉成 `none(3)`。
- **REBIRTH(3) 的回血写死在 `changeState("REBIRTH")`**：`currentHealth = maxHealth` +
  `CanLoseAction`。落在 `CombatFlow.takeTurn`（`AwakenedOne` + `move.id == 3` → `hp = maxHp`）。
- **形态判据**：原版是 `form1` 布尔；影子用游戏自己的信号 ——
  `powers` 里有 `Unawakened` = 一形态，被摘掉 = 二形态；拿不到能力时退回
  「历史里既没有 5/6/8、也没打过 3」。
- **Curiosity**（`onUseCard`，只认 `CardType.POWER`）：玩家每打一张能力牌 → 它 +amount 力量
  → `Hooks.monsterOnCardUsed`。**Regenerate**（`atEndOfTurn`，半死窗口里不回）→ `CombatFlow` ④.5。

全量 **389 条**通过；累计 **65 只**（只剩 `TimeEater`）。

### 33.42 TimeEater（时间吞噬者）+ Time Warp 容器 2026-10-26

HP 456（A9: 480）；`reverbDmg` 7（A4: 8）、`headSlamDmg` 26（A4: 32）。

| 条件 | 招 |
|---|---|
| 血量 < 一半 且没用过 Haste | 5 HASTE（回血到半血、清自身减益；A19 起再加 headSlamDmg 格挡） |
| num<45 | !lastTwoMoves(2) ? 2 回响（3 × reverbDmg）: `getMove(random(50,99))` |
| num<80 | !lastMove(4) ? 4 头槌 :（`randomBoolean(0.66)` ? 2 回响 : 3 涟漪） |
| 否则 | !lastMove(3) ? 3 涟漪 : `getMove(random(74))` |

- **3 涟漪**：`GainBlock(20)` + Vulnerable 1 + Weak 1；**A19 起才**追加 Frail 1。
- **4 头槌**：headSlamDmg + `DrawReductionPower(1)`；**A19 起才**追加 2 张 Slimed 进弃牌堆。
- **5 Haste**：`HealAction(this, this, maxHealth/2 - currentHealth)`（**现算**）+
  `RemoveDebuffsAction` + 摘 `Shackled`；A19 起再 `GainBlock(headSlamDmg)`。
  `usedHaste` 在 getMove 里置真 → 从历史里数 5 就知道用过没有。

**🔴 Time Warp（真正的容器）**：`TimeWarpPower.onAfterUseCard` ——
玩家**每打一张牌** `amount++`；到 **12** 时 `amount = 0`、`callEndTurnEarlySequence()`
（当场结束玩家回合），并且给**每只怪 +2 力量**。

影子里的接法（三层，缺一层就是静默算错）：
1. `Hooks.monsterOnCardUsed`：计数 / 满 12 清零 / 全体怪 +2 力量 / 置 `SimState.turnForcedEnd`；
2. `SimState.turnForcedEnd` 进 `copy()` 与 `fingerprint()`（不同局面不能互相去重）；
   `CombatFlow.endTurn` 开头清掉（只对那个回合有效）；
3. `BeamSolver.route(...)`：子节点带这个标记 → 直接 `endTurnOf(child)` 推进「回合结束」档，
   计划里仍保留「打这张牌」这一步。**不接这层，搜索会以为还能打第 13 张牌。**

**Draw Reduction**：原版 `onInitialApplication` 里 `gameHandSize -= amount`、
`atEndOfRound` 里减 1 层（带 `justApplied` 跳过第一次）—— 净效果就是
「只影响紧接着的那一次抽牌」。影子照净效果写：`CombatFlow.endTurn` 的 ⑦ 里
`drawCount = handSize - amount`，抽完立刻移除。

全量 **394 条**通过；**怪物镜像 66/66（Act 1–4 全齐，M3.3 完成）**。

### 33.43 药水第三批：回合末结算类 + 蛇油 + 复制（2026-10-26）

**ID 格式又混编**：这一批只有再生药水**带空格**（`"Regen Potion"`），
其余五个都是类名照抄（`CultistPotion` / `SpeedPotion` / `SteroidPotion` /
`SneckoOil` / `DuplicationPotion`）。

🔴 **这一批最大的坑是「还债能力」的原版 ID**（按描述写会写出查无此名的死条目）：

| 类 | `POWER_ID`（字节码构造器直读） | 我们早先写的（错） |
|---|---|---|
| `RegenPower` | **`Regeneration`** | `Regen` |
| `LoseStrengthPower` | **`Flex`** | `Lose Strength` |
| `LoseDexterityPower` | **`DexLoss`** | `Lose Dexterity` |
| `DuplicationPower` | **`DuplicationPower`**（region `doubleTap`） | `Duplication` |

「Lose Strength / Lose Dexterity」这两条同时出现在 `Hooks.DEBUFFS` 与
`CombatFlow.SELF_DEBUFFS` 里 —— 因为 `capture()` 用 `pw.ID` 当 key，它们**永远匹配不上**，
是纯死条目（已更正）。

| 药水 | 效果（`use()` 字节码） |
|---|---|
| `CultistPotion` | `RitualPower(player, 1, true)` → 玩家 version 的仪式 |
| `"Regen Potion"` | `RegenPower(player, 5)` |
| `SpeedPotion` | `DexterityPower(+5)` + `LoseDexterityPower(5)` |
| `SteroidPotion` | `StrengthPower(+5)` + `LoseStrengthPower(5)` |
| `SneckoOil` | `DrawCardAction(5)` → `RandomizeHandCostAction` |
| `DuplicationPotion` | `DuplicationPower(player, 1)` |

**回合末的四条钩子**（`PowerHooks`，全部 `END_OF_TURN`）：

- **`Ritual`（玩家）**：`atEndOfTurn` → Strength += amount。
  🔴 怪物版是 `atEndOfRound`（`CombatFlow` ④.5，早就做了）—— 两条是**同一个能力的两个钩子**，
  靠 `owner.isPlayer` 分开。别看到「怪物仪式做过了」就以为玩家版也有了。
- **`Regeneration`**：`RegenAction` = 先 `heal(amount)`，再 `amount--`，减到 0 移除。
  🔴 那个衰减带 `target.isPlayer` 条件（怪物不衰减），且 `heal` 会夹在 `maxHp`。
- **`Flex` / `DexLoss`**：`atEndOfTurn` → Strength/Dexterity **-= amount**，然后 `RemoveSpecificPowerAction`。
  （跨回合的「先借后还」= 一次 +5、一次 -5，中间那回合才吃得到收益。）

**蛇油的重掷**（`RandomizeHandCostAction.update` 字节码）：

```java
for (card : hand) {
    if (card.cost >= 0) {              // -1 = X 费，-2 = 打不出，都跳过
        int r = cardRandomRng.random(3);   // 🔴 先抽再比：掷到相同值也照抽一次
        if (card.cost != r) {
            card.cost = r;                 // 本场费用
            card.costForTurn = r;          // 本回合费用
        }
    }
}
```

- `Random.random(3)` 是**两端都含**的 0..3（`RngStream` 注释里那条），所以新费用落在 0/1/2/3。
- 掷到与原值相同时**不写** `costForTurn`（它保持 -1）—— 这个细节测试里钉住了。
- 快照里没有 `CARD` 流就显式 `ActionRejected`，不假装重掷过。

**复制药水**（`DuplicationPower.onUseCard` 字节码）：

```
!card.purgeOnUse && amount > 0
  → card.makeSameInstanceOf() 进 limbo，副本 purgeOnUse = true
  → CardQueueItem(copy, target, energyOnUse, true, true) 免费重放
  → amount--；到 0 时 RemoveSpecificPowerAction
```

影子落在 `CardPlayer.play`：**效果与 `onUseCard` 各跑两次，能量只扣一次、牌堆只动一次**
（副本 `purgeOnUse` 不进任何牌堆）。`playFromPile`（Havoc 那种自动打出的牌）不复制 ——
原版那类牌本来就 `purgeOnUse`。

**这一批没做的三张（都要新容器，不是抄一段）**：

- `SmokeBomb`：非 Boss 战直接**逃跑**。战斗层没有「逃跑」这个结局（不是胜利也不是失败），
  要一层新的出口语义才谈得上；
- `DistilledChaosPotion`：`PlayTopCardAction × 3` —— 自动打抽牌堆顶三张，
  要「随机目标 + 打不出时怎么办 + 与蛇眼式改费交互」三件事一起定；
- `EntropicBrew`：只填满空药水槽（局外收益，战斗层无效果）。

全量 **406 条**通过；药水镜像 **31 / 42 张**。余下 11 张 = **8 张别的角色专属池**
（`Focus`/`PotionOfCapacity`/`EssenceOfDarkness` → 故障机器人，`Ambrosia`/`BottledMiracle`/
`BlessingOfTheForge`/`StancePotion` → 观者，`CunningPotion` → 静默猎手，铁甲战士**拿不到**）
+ **3 张缺容器**（`SmokeBomb` / `DistilledChaos` / `EntropicBrew`）。
换言之：铁甲战士能拿到的 34 张里已覆盖 31 张。

### 33.44 遗物第二批：被动 / 回合钩子（2026-10-26）

遗物 ID 与类名**经常不是同一串**，这一批就踩了两次：

| 类 | 运行时 ID（构造器首参） | 备注 |
|---|---|---|
| `PaperFrog` | **`Paper Frog`** | 显示名是 "Paper Phrog"，三种拼法都不同 |
| `HornCleat` | **`HornCleat`** | 无空格 |
| `CaptainsWheel` | **`CaptainsWheel`** | 无空格、无撇号 |
| `Orichalcum` / `Torii` | 同名 | — |
| `MercuryHourglass` | **`Mercury Hourglass`** | 带空格 |

| 遗物 | 钩子（字节码） | 影子落点 |
|---|---|---|
| 纸青蛙 | 没有自己的钩子方法，只有一个 `public static final float VULN_EFFECTIVENESS` 被伤害管线读 | `RelicHooks.vulnerableMultiplier` → `DamageCalc` 的**两处「玩家打敌人」**易伤站点 |
| 鸟居 | `onAttacked(info, dmg)`：`info.owner != null` && type ∉ {HP_LOSS, THORNS} && `dmg > 1` && `dmg <= 5` → 1 | `CombatFlow.hitPlayer`，且只在 `attacker != null` 时 |
| 水银沙漏 | `atTurnStart` → `DamageAllEnemiesAction(null, createDamageMatrix(3, true), THORNS, BLUNT_LIGHT)` | `RelicHooks.onPlayerTurnStart`；`createDamageMatrix(..., true)` = **纯伤** |
| 号角夹板 | `atBattleStart` counter = 0；`atTurnStart`：`counter++`，`counter == 2` → `GainBlock(14)`、`counter = -1`、`grayscale = true` | `counterBlockRelic(..., 2, 14)` |
| 舵轮 | 同上，阈值 3、格挡 18 | `counterBlockRelic(..., 3, 18)` |
| 山铜 | `onPlayerEndTurn`：`currentBlock == 0`（或 `trigger`）→ `GainBlock(6)` | `RelicHooks.onPlayerEndTurn` |

**三个容易写错的点**：

1. **纸青蛙只放大「玩家打敌人」那条**。它的常量被 `AbstractCard` 那条链路读，
   敌人打玩家的 `AbstractMonster` 链路不读 —— 所以玩家自己身上的易伤仍是 ×1.5。
2. **`grayscale` 没有对应的快照字段**。号角夹板/舵轮触发过之后 `counter = -1` 且
   **不再自增**（`if (!grayscale) counter++`）。影子用「`counter == -1` 就冻住」表达它 ——
   如果不冻，第 5 回合会二次触发（`-1 → 0 → 1 → 2`），这是最容易漏的一步。
3. **沙漏的结算点在「怪物格挡清空之后」**。怪物格挡在它自己回合开始就清了
   （`MonsterGroup.applyPreTurnLogic`），沙漏在玩家回合开始 → 玩家回合开始时怪物身上
   一定没格挡。顺序写反（沙漏挪到怪物回合之前）会让「刚架起格挡的怪」被少算伤害。

⚠️ **山铜的先后未真机核对**：原版调用点在 `AbstractRoom` 里，与能力钩子（金属化 / 装甲）
的先后没法从字节码直接读出。影子按「先算回合末能力、再算遗物」写 —— 影响面只有
「同一回合既有金属化又没别的格挡」这一种情况，对拍出现山铜差异时先查这条。

`Coverage.unmirroredRelics` 的白名单同步补上（含之前漏登记的计数类六件与蜥蜴尾巴）——
它现在只报**真的还没镜像**的遗物。

**这一批没做（各有各的坎）**：

- `RedSkull`（半血 +3 力量）：根状态捕获时若已半血，力量里**已经含了那 3 点**，
  影子再「补上」就重复计。要先有「这个 +3 是否已计」的标记才能做；
- `TungstenRod`（掉血 -1）：它只作用于 `AbstractPlayer.damage()` 这条链路，
  **不作用于 `loseHp`**（缠绕、自伤）。我们的 `hitPlayer` 同时承担两者，
  接之前得先把「伤害」与「掉血」两条路分开；
- ~~`IceCream` / `Chemical X`~~ → **已做**（同日）：
  `EnergyManager.recharge()` 里 `if (hasRelic("Ice Cream")) EnergyPanel.addEnergy(energy)`
  —— 剩的能量加回去而不是重置；`Chemical X` 是每个 X 动作里的 `iinc 1, 2`
  （`WhirlwindAction.update` 直读），影子在 `CardPlayer` 统一 `xValue = energy + 2`，
  能量照旧全花掉；
- `RunicPyramid` / `SneckoEye`：一个要改弃牌规则、一个要每回合抽牌 +2 并随机费用
  （费用随机那半已经随蛇油做好了）。

全量 **417 条**通过；遗物镜像 **14 件**（计数类 6 + 被动/回合 6 + 能量侧 2）。

### 33.45 伤害类型容器 DamageKind + 钨棒（TungstenRod）2026-10-26

🔴 **先纠一条错结论**：之前记的是「`TungstenRod` 只作用于 `AbstractPlayer.damage()`，
不管 `loseHp`」。**错了** —— `LoseHPAction.update()` 的实现是

```java
this.target.damage(new DamageInfo(this.source, this.amount, DamageInfo.DamageType.HP_LOSS));
```

掉血**不是另一条管线**，只是同一个 `damage()` 的一个类型值。所以钨棒对掉血照样有效。
（真正的差别在**格挡**上：`decrementBlock` 第一件事就是 `if (type == HP_LOSS) return damage`。）

新增 `sim/DamageKind`（`NORMAL` / `THORNS` / `HP_LOSS`）与
`CombatFlow.hitPlayer(state, dmg, attacker, kind) -> int`（返回**实际掉的血**）。
管线顺序按 `AbstractPlayer.damage()` 的字节码 offset 排：

| 步 | 动作 | 三种类型的差别 |
|---|---|---|
| ① | 负数夹 0 | — |
| ② | **无实体**：`dmg > 1` → 1 | **无差别**（字节码不看 `DamageType`，掉血也被压成 1） |
| ③ | **格挡**：`decrementBlock` | 只有 `HP_LOSS` 跳过 |
| ④ | **鸟居** `onAttacked` | 只认 `owner != null` 的 `NORMAL` |
| ⑤ | **钨棒** `onLoseHpLast` | **无差别**（末尾遍历遗物那段没有类型分支） |
| ⑥ | 扣血 → `hpLossCount++` | 只有 `> 0` 才算「受伤」 |
| ⑦ | 死亡豁免 → 烈焰屏障 | 只认 `NORMAL` 且有攻击者 |

**顺带修掉一个静默 bug**：`hpLossCount`（浴血奋战的减费计数）以前只在
`Effects.loseHp` 与燃烧里自增 —— **被怪打完全不计**。现在统一在 `hitPlayer` 里按
「实际掉血 > 0」记一次，与原版 `updateCardsOnDamage()` 的 `if (damage > 0)` 对齐
（被格挡全吃的那次不算，这一点测试钉住了）。

路线收敛：
- `Effects.loseHp`（放血 / Offering / 血液沸腾 / J.A.X.）→ `HP_LOSS`
- 燃烧的自伤 → `HP_LOSS`
- 缠绕 / 荆棘 / 苦痛之击 / 尖刺外壳 → `THORNS`
- 怪物出招 → `NORMAL`

`RupturePower.wasHPLost` 现在吃**实际掉血**：被钨棒抹成 0 时不触发力量（原版同口径）。

全量 **424 条**通过；遗物镜像 **15 件**。

### 33.46 遗物第三批：受伤触发 + 手牌保留（2026-10-26）

前一批把「伤害类型」拆开之后，这一批四件遗物都挂在**同一个点**上：
原版 `AbstractPlayer.damage()` 里 `if (damage > 0)` 之后的
「`wasHPLost`（遗物）/ `onBloodied`（遗物）」那两段。

ID 全部按构造器首参核实（又是一堆空格差异）：

| 类 | 运行时 ID |
|---|---|
| `RunicCube` | **`Runic Cube`** |
| `CentennialPuzzle` | **`Centennial Puzzle`** |
| `SelfFormingClay` | **`Self Forming Clay`** |
| `RedSkull` | **`Red Skull`** |
| `RunicPyramid` | **`Runic Pyramid`** |

| 遗物 | 钩子（字节码） | 影子落点 |
|---|---|---|
| 符文立方 | `wasHPLost(n>0)` → `DrawCardAction(player, 1)` | `RelicHooks.onPlayerHpLost` |
| 百夫长谜题 | `wasHPLost(n>0)` && `!usedThisCombat` → 抽 3 张，`usedThisCombat = true`、`grayscale = true` | 同上（标记存 `relics` 的值） |
| 自形成黏土 | `wasHPLost(n>0)` → `ApplyPowerAction(player, player, NextTurnBlockPower(player, 3, name))` | 同上 + `PowerHooks` 的 `Next Turn Block` |
| 红骷髅 | `atBattleStart` / `onBloodied` → `Strength +3`；`onNotBloodied` → `Strength -3`（私有 `isActive` 防重复） | `RelicHooks.syncRedSkull` |
| 符文金字塔 | 没有自己的钩子，是 `DiscardAtEndOfTurnAction` 里 `hasRelic("Runic Pyramid")` 直接 `return` | `CombatFlow.endTurn` ② 跳过弃手牌 |

**四个坑**：

1. **`GainBlockAction` 不吃敏捷**。字节码 `update()` 只有 `target.addBlock(amount)` ——
   敏捷是在**卡牌**的 `applyPowers()` 里加的，遗物/能力给的格挡是裸数值。
   （顺带确认山铜 / 号角夹板 / 舵轮之前写对了。）
2. **`NextTurnBlockPower` 是「一次性」**：`atStartOfTurn` 里 `GainBlockAction(amount)` 之后
   `RemoveSpecificPowerAction` **整条移除**，不是每回合减 1。多次叠加是全款一次结清。
3. **红骷髅没有快照字段**：`isActive` 是私有布尔。影子把它存在 `state.relics` 的值里，
   并在 `SimState.capture` 时**按当时血线初始化** —— 不初始化的话，
   「开局就已经半血」的局面会把 `atBattleStart` 已经加进 `Strength` 里的 +3 再加一遍。
4. **百夫长谜题的 `usedThisCombat` 是 `private static boolean`**，同样没有快照字段。
   影子借 `relics` 的值当标记（`> 0` = 已用）；代价是「战斗中途中第一次 capture」时会
   退回「没用过」，最多多抽 3 张一次。

`Runic Pyramid` 还顺手把 `HAND_LIMIT`（10）这条老规矩暴露出来：手牌留在手上的话，
下一回合抽牌会被上限卡住 —— 这与原版一致（`draw` 里 `if (hand.size() >= 10) break`）。

全量 **429 条**通过；遗物镜像 **20 件**。

### 33.47 SneckoEye / ConfusionPower —— 抓到一个「两边一起错」的死条目 2026-10-26

🔴 **`ConfusionPower.POWER_ID` 是 `"Confusion"`，不是 `"Confused"`。**
而 `CombatFlow.applyConfusion` 里查的是 `"Confused"`、单元测试灌的也是 `"Confused"`
—— **生产代码与测试一起错**，所以测试一直是绿的，但真实游戏里 `capture()` 用
`pw.ID` 当 key，永远匹配不上 ⇒ **蛇眼的「抽到的牌费用随机」从来没生效过**。
这是本项目第 N 次踩同一个坑：**按描述/记忆写能力名**。

顺带两条同源细节：
- `ConfusionPower` 的 `amount` **恒为 0**（构造器只写 ID/name/type，没有 setAmount），
  所以判据必须是 `containsKey`，写成 `> 0` 也会静默失效。
- 重掷是 `cardRandomRng.random(3)`，`cost < 0`（X 费 / 打不出）**既不重掷也不消耗随机数**。

`SneckoEye` 本体很简单：
- `atPreBattle` → `ApplyPowerAction(player, player, ConfusionPower(player))`；
- `onEquip` / `onUnequip` → `gameHandSize ± 2`。这个**不需要镜像** ——
  执行侧传进来的 `handSize` 就是 `AbstractDungeon.player.gameHandSize`，
  穿蛇眼时它已经含 +2 了。

影子落点：`CombatFlow.applyConfusion`（在 `draw` 里逐张调用）。
快照里没有 `cardRandomRng` 时**显式 `ActionRejected`**，不再「静默按原费用继续」
（那会让整条路线建立在假费用上）。

全量 **430 条**通过；遗物镜像 **21 件**。

### 33.48 遗物第四批：消耗 / 洗牌 / 击杀 / 回血 / 格挡衰减 / 药水 2026-10-26

| 类 | 运行时 ID | 钩子（字节码） | 影子落点 |
|---|---|---|---|
| `CharonsAshes` | **`Charon's Ashes`**（带撇号） | `onExhaust(card)` → `DamageAllEnemiesAction(player, createDamageMatrix(3, true), THORNS, …)` | `Effects.exhaustCard` |
| `Sundial` | `Sundial` | `onShuffle()`：`counter++`，`== 3` → `counter = 0` + `GainEnergyAction(2)` | `Effects.reshuffleDiscardIntoDraw` + DeepBreath 的第二次洗牌 |
| `GremlinHorn` | **`Gremlin Horn`** | `onMonsterDeath`：`if (!monsters.areMonstersBasicallyDead())` → `GainEnergy(1)` + `DrawCard(1)` | `DamageCalc.hitAndReturnDealt` |
| `MagicFlower` | **`Magic Flower`** | `onPlayerHeal(int)`：战斗中 → `MathUtils.round(n * 1.5f)` | `RelicHooks.healAmount`（4 个回血落点） |
| `Calipers` | `Calipers` | 没有钩子方法，被 `GameActionManager` 直接读：`Barricade/Blur/Calipers` 三分支，Calipers → `loseBlock(15)` | `RelicHooks.blockAfterTurnEnd` |
| `ToyOrnithopter` | **`Toy Ornithopter`** | `onUsePotion()` → `HealAction(player, player, 5)` | `Potions.use` 的成功出口 |

**四个容易写错的点**：

1. **`MathUtils.round` 是四舍五入，不是截断**：`(int)(x + 0.5f)`。
   所以魔法花把 `heal(5)` 变成 **8**（7.5 → 8），不是 7。写成截断每回合少 1 点。
2. **`GremlinHorn` 有「整场打完就不给」这层判断**。等价判据是「还有别的怪活着」；
   写成「每次击杀都给」的话，最后一击会凭空多 1 能量 + 1 张牌。
3. **`Sundial` 的计数是遗物 `counter`**，`onEquip` 置 0 —— 所以捕获到的值直接可用。
   洗牌点有两个：弃牌堆洗回抽牌堆（`EmptyDeckShuffleAction`）和深呼吸的抽牌堆重洗
   （`ShuffleAction`），**两处都要记**，只记一处会少算次数。
4. **`Calipers` 的判据里有 `Blur`**（`Barricade || Blur || Calipers`）。`Blur` 我们还没建模
   （静默的「无实体格挡保留」），所以现在只对齐了 `Barricade` 与 `Calipers` 两支；
   铁甲战士拿不到 `Blur`，短期无影响，但**别以为三分支只做了两支就等于做完了**。

顺带把 `DamageCalc.hitAndReturnDealt` 的签名改成带 `SimState`（小鬼号角要读遗物、要抽牌），
三个调用点（普通攻击 / 烈焰屏障 / 收割）全部同步。

全量 **436 条**通过；遗物镜像 **27 件**。

### 33.49 遗物第五批：回合计数清零 / 快乐花 / 鸟面罐 / 姜与萝卜 2026-10-26

🔴 **又是一种「记反了」的静默 bug**：`Shuriken` / `OrnamentalFan` / `LetterOpener` / `Kunai`
四个都有 `atTurnStart()`，第一句就是 `counter = 0` —— 它们数的是**本回合**打了几张，
不是整场累计。影子以前只在 `onUseCard` 里自增、从不清零，于是搜索跨回合推演时
「两回合各打 2 张」会被误判成「攒够 3 张」触发一次。已在 `RelicHooks.onPlayerTurnStart`
里统一清零（`TURN_RESET_COUNTERS`）。

对照着看，另外三只是**不清零**的（没有 `atTurnStart`）：`Nunchaku` / `PenNib` / `InkBottle`
—— 它们的计数是整场累计。这两组别混。

| 类 | 运行时 ID | 钩子（字节码） | 影子落点 |
|---|---|---|---|
| `Kunai` | `Kunai` | `atTurnStart` 清零；`onUseCard`：攻击 → `counter++`，`% 3 == 0` → `counter = 0` + 敏捷 +1 | `kunai` |
| `HappyFlower` | **`Happy Flower`** | `atTurnStart`：`counter++`，`== 3` → `0` + `GainEnergyAction(1)`（`onEquip` 置 0 → 第 3/6/9 回合） | `happyFlower` |
| `BirdFacedUrn` | **`Bird Faced Urn`** | `onUseCard`：`type == POWER` → `HealAction(player, player, 2)` | `birdFacedUrn` |
| `Ginger` | `Ginger` | **没有钩子**，判定在 `ApplyPowerAction.update()` 开头 | `RelicHooks.blocksPlayerDebuff` |
| `Turnip` | `Turnip` | 同上（拦 `Frail`） | 同上 |

**姜 / 萝卜的判定写在 action 里，不在遗物里**：
`if (player.hasRelic("Ginger") && target.isPlayer && powerToApply.ID.equals("Weakened")) return;`
—— 是**整条 action 直接 return**，连层数都不加，不是「加了再清掉」。
所以影子里所有「给玩家上减益」的路径必须走同一个入口：新增
`CombatFlow.addPlayerDebuff`（替换掉 `takeTurn` 与 `applyExtra` 里三处手写 `playerPowers.put`）。
顺带一提，那个入口的判据必须是 `amount != 0` —— Lagavulin 的 Siphon Soul 是**负数**减益。

`BirdFacedUrn` 的回血走 `healAmount`（魔法花 ×1.5 通道），所以 `2 → 3`。

全量 **441 条**通过；遗物镜像 **32 件**。

### 33.50 遗物第六批：回合结构（香炉 / 天鹅绒颈环 / 怀表）2026-10-26

| 类 | 运行时 ID | 钩子（字节码） |
|---|---|---|
| `IncenseBurner` | **`Incense Burner`** | `atTurnStart`：`counter++`（-1 时 +2），`counter % 6 == 0` → 归零 + `IntangiblePlayerPower(1)` |
| `VelvetChoker` | **`Velvet Choker`** | `onPlayCard`：`if (counter < 6) counter++`；`canPlay`：`counter >= 6` → **false**；`atTurnStart` 清零 |
| `Pocketwatch` | `Pocketwatch` | `onPlayCard`：`counter++`；`atTurnStartPostDraw`：`counter <= 3` → `DrawCardAction(3)`，然后归零 |

**三个坑**：

1. **怀表的 `atTurnStartPostDraw` 位置很讲究**：它在**抽完牌之后**。影子必须挂在
   `CombatFlow.endTurn` 的 `draw(...)` 之后 —— 挂到 `draw` 之前的话，多抽的 3 张会被
   `HAND_LIMIT` 或后续抽牌顺序算错。
2. **怀表的私有 `firstTurn` 不需要建模**。第 1 回合的 `atTurnStartPostDraw` 发生在
   战斗刚开始、捕获快照之前，而 `endTurn` 只产出第 2 回合及以后 —— 所以这个钩子被调用时
   `firstTurn` 一定是 false。**我一开始用 `state.turn > 1` 近似，是错的**：
   `endTurn` 在调这个钩子之前就把 `state.turn` 递增成新回合号了，测试当场抓住了
   （「第 1 回合不触发」那条断言拿到 8 张而不是 5 张）。
3. **香炉的 `counter % 6 == 0` 而不是 `== 6`**：`onEquip` 置 0，`atTurnStart` 里先 `+1`
   再判——所以第一次触发是第 6 回合（`0 → 1 … → 6`），之后每 6 回合一次。

全量 **444 条**通过；遗物镜像 **35 件**。

### 33.51 Necronomicon（死灵之书）2026-10-26

**触发条件（`onUseCard` 字节码直读）**：

```java
if (card.type == ATTACK
        && (card.costForTurn >= 2
            || (card.freeToPlayOnce && card.cost == -1 && card.energyOnUse >= 2))
        && activated) {
    activated = false;
    // 副本 makeSameInstanceOf() → purgeOnUse = true → CardQueueItem(免费, 立即)
}
```

`atTurnStart` → `activated = true`；`checkTrigger()` 直接返回它。

影子：
- `activated` 借用 `state.relics` 的值（`1` = 就绪 / `0` = 本回合用过），
  `RelicHooks.onPlayerTurnStart` 每回合置 1，`SimState.capture` 也初始化为 1
  （⚠️ 若「本回合已经用过它之后」才第一次捕获，会多给一次重放）；
- 重放逻辑与 `DuplicationPower` 完全一致 —— 顺手把「跑一遍效果 + 触发 onUseCard」
  抽成 `CardPlayer.applyMirroredEffects`，三个入口（正常 / 死灵之书 / 复制）共用，
  避免以后改一处漏两处；
- X 费牌（`cost == -1`）走原版那条 `freeToPlayOnce` 分支，我们没建 `freeToPlayOnce`，
  所以**X 费牌一律不触发**（宁可少给一次，也不凭空多打一次）。

全量 **446 条**通过；遗物镜像 **36 件**。

### 33.52 Blue Candle / Medical Kit：让「打不出的牌」能打 2026-10-26

这两件遗物不改变任何数值，改变的是**动作空间**：诅咒牌与状态牌默认 `cost = -2`（打不出），
带上之后可以花 0 费打出去清手牌，并**强制消耗**。

| 位置 | 字节码事实 |
|---|---|
| `AbstractCard.canUse` | `type == STATUS && costForTurn < -1 && !hasRelic("Medical Kit")` → false；`type == CURSE && costForTurn < -1 && !hasRelic("Blue Candle")` → false |
| `AbstractPlayer.useCard` | 扣费用 `Math.max(costForTurn, 0)` —— 所以 `-2` 实际是 0 费 |
| `BlueCandle.onUseCard` | `type == CURSE` → `LoseHPAction(player, player, 1, FIRE)` + `card.exhaust = true` + `action.exhaustCard = true` |
| `MedicalKit.onUseCard` | `type == STATUS` → 同上，但**不掉血** |

影子：
- `CardPlayer.play` 里把 `cost == -2` 单独一支（以前是「unsupported cost」直接拒），
  只放行「诅咒 + 蓝蜡烛」与「状态 + 医疗包」两种组合，两者**不通用**（测试钉住了）；
- 强制消耗与「这张牌自己消耗」分开判，`mirror.exhaustsOnPlay || blueCandle || medicalKit`；
- 蓝蜡烛的 1 点是 `HP_LOSS`（无视图格挡、吃钨棒/无实体）—— 走 `hitPlayer` 统一入口。

**基础设施**：`MirrorRegistry` 里注册了一批**空效果**的诅咒/状态牌
（`Wound` / `Dazed` / `Burn` / `Slimed` / `Void` / `AscendersBane` / `Pain` / `Regret` /
`Shame` / `Doubt` / `Decay` / `Injury` / `Normality` / `Clumsy` / `Writhe` / `Parasite` /
`Necronomicurse` / `CurseOfTheBell`）。原版它们的 `use()` 本来就是空的，
注册只是为了 `CardPlayer` 不再以「no mirror」拒绝 —— **不注册的话这两件遗物等于没带**。

`Necronomicurse`（死灵诅咒）另有 `onExhaust` → 回到手牌，已在 `Effects.exhaustCard` 里特判。

⚠️ **没做**：状态牌/诅咒牌的**在手动效果**（`Burn` 回合末 2 点伤害、`Dazed` 虚体消耗、
`Void` 抽到时 -1 能量、`Regret` 每张手牌 1 点伤害…）。这些是独立的「手牌被动」，
与这两件遗物无关，属于**已知缺口** —— 也就是说「手上有 Burn」这类局面目前是低估伤害的。

全量 **450 条**通过；遗物镜像 **38 件**。

### 33.53 手牌的在手被动（Burn / Decay / Regret / Doubt / Shame / Pain / Void / 虚体）2026-10-26

这是 §33.52 里点名的「已知缺口」，现在补上了。

🔴 **关键事实：这些效果全写在卡自己的 `use()` 里，靠 `CardQueueItem(card, true)`
「按触发方式打一遍」才生效** —— 每个 `use()` 的第一句都是

```java
if (this.dontTriggerOnUseCard) { ...真正的内容... }
```

`dontTriggerOnUseCard` 只在「触发方式」下为真。所以：
**用蓝蜡烛/医疗包主动打出 Burn，反而什么都不发生** —— 我们给它注册的**空效果镜像是对的**
（这一点有专门测试钉住，免得以后有人「顺手补上」）。

| 卡 | 类别 | 时机 | 实际动作（字节码） |
|---|---|---|---|
| `Burn` | 状态 | 回合末 | `DamageAction(player, DamageInfo(player, 2, THORNS))` —— **吃格挡** |
| `Decay` | 诅咒 | 回合末 | 同上，2 点 |
| `Regret` | 诅咒 | 回合末 | `LoseHPAction(player, player, magicNumber)`，而 `magicNumber = 当时手牌数`（`triggerOnEndOfTurnForPlayingCard` 里现算）→ **HP_LOSS，无视图格挡** |
| `Doubt` | 诅咒 | 回合末 | `WeakPower(player, 1)` —— 吃姜 |
| `Shame` | 诅咒 | 回合末 | `FrailPower(player, 1)` —— 吃萝卜 |
| `Pain` | 诅咒 | 打出别的牌时 | `LoseHPAction(player, player, 1)` —— HP_LOSS |
| `Void` | 状态 | 抽到时 | `LoseEnergyAction(1)`（夹在 0） |

**虚体**（`isEthereal`）：字节级核实有该字段写入的只有
**`Dazed` / `VoidCard` / `AscendersBane` / `Clumsy`** 四张；
`Burn` / `Slimed` / `Wound` / `Decay` / `Regret` / `Doubt` / `Shame` / `Pain` 都**不是**虚体。
实现在 `SimCard.isEthereal()`（按 ID 判，不加字段）。

**虚体 vs 符文金字塔**：虚体的消耗**不在** `DiscardAtEndOfTurnAction` 里，所以
「符文金字塔不弃手牌」**救不了**虚体牌 —— 它照样在回合末被消耗。测试钉住了这条。

⚠️ **顺序没核实**：金属化（给格挡）与手牌触发（Burn 伤害）谁先。影子按
「先能力、再手牌」写（`endTurn` 的 ① → ①.5）。影响面只有「同一回合既有金属化又摸到 Burn」，
对拍出现 2 点差异时先查这条。

全量 **459 条**通过。

### 33.54 遗物第八批：补满（算盘 / 硫磺 / 石历 / 血花印记 / 木乃伊手 / 橙色药丸 / 战争艺术）2026-10-26

先把底数摸清了：jar 里共 **190** 个遗物类（含 `Test*` 与 `deprecated/*`），
按「有没有战斗钩子方法」整体扫了一遍，见 `docs/research/relic-inventory.md`。

| 类 | 运行时 ID | 钩子（字节码） | 影子落点 |
|---|---|---|---|
| `Abacus` | **`TheAbacus`**（不是 Abacus！） | `onShuffle` → `GainBlockAction(player, player, 6)` | `RelicHooks.abacus` |
| `Brimstone` | `Brimstone` | `atTurnStart`：自己 `Strength+2`，**全体敌人** `Strength+1` | `brimstone` |
| `StoneCalendar` | `StoneCalendar` | `atTurnStart` `counter++`；`onPlayerEndTurn`：`counter == 7` → 全体 52 **纯伤** + `grayscale` | `stoneCalendarEndTurn` |
| `MarkOfTheBloom` | **`Mark of the Bloom`** | `onPlayerHeal` → **直接 `return 0`** | `healAmount` 开头 |
| `MummifiedHand` | **`Mummified Hand`** | `onUseCard`：`type == POWER` → 手牌里 `cost > 0 \|\| costForTurn > 0` 的随机一张 `setCostForTurn(0)`（`cardRandomRng.random(0, size-1)`） | `mummifiedHand` |
| `OrangePellets` | **`OrangePellets`**（无空格） | `onUseCard`：攻击/技能/能力三个标记都置位 → `RemoveDebuffsAction(player)` | `orangePellets` |
| `ArtOfWar` | **`Art of War`** | `atPreBattle`/`atTurnStart` 的 `gainEnergyNext`；`onUseCard` 打攻击就清 | `artOfWarTurnStart` |

**三个坑**：

1. **`Abacus` 的运行时 ID 是 `TheAbacus`** —— 又一个「类名 ≠ ID」。
2. **`MarkOfTheBloom` 是「治疗归零」而不是「治疗减半」**：`onPlayerHeal` 直接 `iconst_0; ireturn`。
   所以魔法花的 ×1.5 也救不了，顺序必须是「先看血花印记、再看魔法花」。
3. **橙色药丸的三个标记是私有布尔，快照里没有** —— 影子压成 `state.relics` 的位掩码
   （1 攻击 / 2 技能 / 4 能力）。**代价**：本回合中途才第一次捕获时会退回「一张都没打过」，
   最多多清一次减益。

**这一批明确没做的（都写进 `relic-inventory.md`）**：

- `Akabeko` / `Sling`：给的是 **Vigor**（下一次攻击 +8/+2 伤害）。Vigor 要在「真正结算伤害」时
  消耗掉，而 `DamageCalc.attackDamage` 是**纯函数**（估值/意图展示都会调它），
  现在放进去会被重复消耗 —— 需要先把「估值」与「实际结算」两条路分开。
- `Boot`：需要知道「这一击的伤害类型」才能排除 THORNS/HP_LOSS，而怪物侧的
  `DamageCalc.hit` 目前不区分类型。
- `WarpedTongs`：`UpgradeRandomCardAction` —— 升级一张随机手牌，需要**卡牌升级表**
  （升级后的费用/伤害），我们没有卡牌数据库。
- `GamblingChip`（每回合让玩家选弃几张再抽）、`DeadBranch` / `NilrysCodex` / `Enchiridion`
  （随机生成牌，需要牌池枚举）、`SacredBark`（药水效果 ×2）、`UnceasingTop`（空手牌抽 1）。

**第九批（同日晚）**：`OddMushroom` / `PaperCrane` —— 两条**倍率**，
分别落在 `DamageCalc.monsterAttackDamage` 的两个点：
- `Odd Mushroom`：**自己**中易伤 ×1.5 → **×1.25**（`VulnerablePower.atDamageReceive` 里 `owner.isPlayer` 分支）；
- `Paper Crane`：**敌人**中虚弱 ×0.75 → **×0.6**（`WeakPower.atDamageGive` 里 `!owner.isPlayer` 分支）。

🔴 这两条与纸青蛙（玩家→敌人易伤 1.5 → 1.75）是**三个不同的点**，别混：
纸青蛙放大你打人，奇异蘑菇缩小你挨打，纸鹤放大敌人在虚弱时的减伤。

⚠️ 这两只**没有钩子方法**，是被 `VulnerablePower` / `WeakPower` 直接读 ID 的 ——
所以「按钩子方法扫描」会漏掉它们。盘点见 `docs/research/relic-inventory.md`（含 190 个遗物的分类
与「明确未做」清单）。

全量 **467 条**通过；遗物镜像 **47 件**。
