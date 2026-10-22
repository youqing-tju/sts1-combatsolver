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
