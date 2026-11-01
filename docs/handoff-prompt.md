# STS1 战斗求解器 · 交接提示词（2026-10-31 深夜版）

> **用法**：整份贴进新对话，第一条消息就给全上下文。
> **项目根**：`C:\Users\22757\Desktop\sts1-combatsolver`　**分支**：`master`（不是 main）
> **当前状态**：2026-11-01 14:50，测试 **565 条全绿**（0 fail / 0 err），HEAD = `3da4e6c`，未 push。
> 已安装 `build 1101-144558`。
> ✅ **回合末触发已在真机验证通过**（2026-11-01 13:17 用户确认：灼烧/多层护甲/金属化/奥利哈刚
> 这类「回合结束触发」现在真机正常）。`6ea8014` 从「候选修复」转为**已验证**。
> ⚠️ 但**修复的具体验证明细（四项各自的 HP/block 差值、敌方阶段是否重复）未留证**，
> 只有用户的定性确认；UI 十条验收仍是 **0/10**。
> ✅ **药水闸门假阴性已修**（`3da4e6c`，见 P6.1）：闸门拒绝后新颖度通道会把药水捞回来，
> 真机 9 次喝药里 6 次是这种。**未真机复测**。
> 🔴 **P7 是本轮最重要的结构性发现**：蓝本算一次路线后，后续回合**复用**同一份推演
> （`ContinuationSource` / `SEARCH_REUSED`），我们每回合 `clearPlan()` 全丢 ——
> 「世界线没被展开」就是这个。**未实施**。

---

## 一、你是谁 / 这是什么

STS1（杀戮尖塔）**进程内**战斗求解器，Java 8 ModTheSpire mod。
对标 STS2 的 `Torch1230/CombatSolver`（蓝本在 `reference/sts2-combatsolver`）。
只做**战斗层**（Ironclad，Act 1–4）；训练、地图、选牌、商店、事件、篝火均后置。
模块：`capture / sim / mirror / search / eval / exec / ui / verify / diag / bootstrap`。

## 二、🔴 硬规则（违反 = 白干）

1. **先看蓝本怎么写，照着复刻**。①蓝本找实现 → ②理解 → ③翻译到 STS1 API → ④才许自创。
   找不到就明说「蓝本没有这块」。
2. **默认直接执行，不等逐条确认**（2026-10-31 23:19 用户明确反馈「事事等确认效率很低」）：
   已确定目标内的代码、测试、诊断、重构、构建、安装、真机验证和本地 commit 都自主完成；
   修 bug 可以边查边改。只有产品语义存在多种不兼容取舍、需要不可逆/外部操作（push、删除等）、
   或必须违反既有硬规则时才问。**事后用短报告交代改动、证据、风险、回退；请示不算进度。**
3. **本项目「绝对不嫌慢」，只嫌牌打得臭**（用户 2026-10-30 亲口）。
   不许为了耗时砍搜索预算/宽度/深度/关机制；评估只看「牌是不是打得更好」。
4. **蓝本复刻以 `docs/research/blueprint-parity.md` 为准**，一次只搬一项并记证据；
   诊断与 bugfix 不必逐项等批准；先建立可检验假设并保留证据，再用测试/真机逐步闭环，不得把假设写成已证实结论。
5. **游戏语义必须 javap 核实**（`lib/desktop-1.0.jar`），不凭记忆。
   卡牌文本查 jar 内 `localization/eng/cards.json`；升级数值查 `UPGRADE_DESCRIPTION` / `upgrade()` 字节码。
6. **未知语义显式拒绝**（`ActionRejected`），不许静默算错。
7. `.ps1` 一律 ASCII-only（PowerShell 5.1 按 ANSI 读）。
8. Gradle 9.8.2（`~/.workbuddy/binaries/gradle/gradle-9.8.2`），`options.release=8`，class major=52。
   构建目录在 `~/.sts1-combatsolver/build`，**不是**工作区 `build/`。
9. 搜索跑后台线程（影子是纯数据）；真机对象不进后台；执行只在主线程。
10. 不用本机遗留资产（`mods/SolverMod.jar`、已删的 `sts-solver` / `sts-agent`）。
11. **不自行 push**，等用户明确要求。

---

## 三、本轮（2026-10-31）做了什么

### 3.1 UI 第三/四刀（已交付并 commit）

| commit | 内容 |
|---|---|
| `3a6632f` | `ui/StrategyStatusSnapshot` 只读策略现状折叠区（药水槽/遗物计数/成长层数；无 setter、无假控件） |
| `0c16722` | `verify/RoutePreview` 路线改**绝对回合号**（从完整 steps 生成，出牌不重排） |
| `4067838` | `verify/BoundedAutoTarget` + 点路线行 = **AUTO 至目标回合末**（项目自定义扩展，非蓝本 `AdoptRoute`） |
| `0964c87` | 评审修正（牌名缩写单一实现、行命中不打断拖拽） |
| `4538091` | 文档状态 |
| `42a0791` | 修：空药水槽不再显示成 `Potion Slot` 假药水 |
| `7d12e9a` | `patches/EndTurnProbe` 回合结束状态机**只读探针**（每帧打 flags/hp/block/手牌，仅变化时落盘） |
| `6ea8014` | **候选修复：AUTO 结束回合改走 `EndTurnButton.disable(true)`** —— 自动化已过，真机验证未做 |

测试数 533 → **547**。`search/`、`mirror/`、`sim/`、`config/` **一行未改**。

### 3.2 回合末触发整批丢失：候选修复（尚未真机闭环）

**症状**：灼烧 / 多层护甲 / 金属化 / 奥利哈刚在 AUTO 下全部不触发；**手点结束回合就正常**。

**已核实的原生调用链**（`javap` 逐条核实，勿凭记忆）：

```
GameActionManager.getNextAction()，cardQueue 非空时：
  CardQueueItem item = cardQueue.get(0);
  if (item.card == null) callEndOfTurnActions();     // ← 全类唯一调用点

private void callEndOfTurnActions() {
    room.applyEndOfTurnRelics();          // 奥利哈刚 / 香炉…
    room.applyEndOfTurnPreCardPowers();   // 多层护甲 / 金属化…
    addToBot(new TriggerEndOfTurnOrbsAction());
    for (AbstractCard c : player.hand.group) c.triggerOnEndOfTurnForPlayingCard();  // 灼烧…
    player.stance.onEndOfTurn();
}
```

回合末**三类**触发（遗物 / 能力 / 手牌）全挂在「`cardQueue` 里那个 `card == null` 的哨兵项」上。
而 `EndTurnButton.disable(true)`（鼠标点击真正调的）做 **5** 件事：

1. `InputHelper.moveCursorToNeutralPosition()`
2. `actionManager.addToBottom(new NewQueueCardAction())` ← 先把挂着的出牌收尾
3. `enabled / hb.hovered / isGlowing` 置位
4. `player.endTurnQueued = true` ← **以前我们只做了这一件**
5. `player.releaseCard()` ← 清 `justClickedLeft / isMouseDown / inSingleTargetMode`

**当前最强假设**：少 2、5 使 `cardQueue` 状态与手点不同，从而导致回合末效果丢失。
这与三类机制同时失效、手点正常相符；但探针已表明 `endTurn()` 入口确实启动，尚未直接证明
“哨兵项丢失”。因此真机验证前只能叫候选修复，不得写成根因已闭环。

**候选修法**：`AutoPilot.requestEndTurn()` 直接调 `AbstractDungeon.overlayMenu.endTurnButton.disable(true)`，
与手点共用同一原生入口；按钮不可用时退回 `endTurnQueued = true` 并 warn。是否消除症状仍待真机验证。
⚠️ 仍**不能**调 `actionManager.endTurn()`（那是 `EndTurnAction` 干的事，会跳过玩家侧整段：
战斗专注的「禁止抽牌」永不消失、怪物完全不行动）。

**待办**：真机验证（面板 `build 1031-225403`，AUTO 结束一回合看日志
`EOT q=0 ... block=` 与 `EOT q=0 turnEnded=1 ... block=` 的差值）。

---

## 四、当前进度与里程碑

| 里程碑 | 状态 |
|---|---|
| M0 mod 骨架 / M1 镜像核心 / M2 搜索主体 / M3.1 卡牌镜像 | ✅ |
| M3.2 怪物镜像 Act 1/2/4 | ✅ |
| M3.3 怪物镜像 Act 3 | 🔶 55/68 只 |
| M3.4 药水镜像 | 🔶 21/50+ |
| M3.5 遗物镜像 | 🔶 47 件 |
| M4-1 完整路线面板 | 🔶 绝对回合预览 + 点击打到 Tn；蓝本 `AdoptRoute` 冻结路线未做 |
| M4-2 预算档位 / M4-3 AUTO 执行 / M4-5 稳定性 | ✅ |
| M4-6 局外收益 UI / M5 验收（全自动 20 场） | ❌ |

---

## 五、🔴 未解决的严重问题（按优先级，下一轮从这里开始）

### P7 · 路线续跑没复刻 —— 每回合把上一回合算出来的整条未来丢掉（**最高优先，未实施**）

**用户判断（2026-11-01 14:45，与真人使用体验一致）**：STS2 的默认行为是「算一次，按序打完
一整条路线」；我们这边「第一回合算出来的后续路线完全没用，第二回合重算又是新路线，
世界线完全没被展开，达不到全知口径」。

**蓝本机制（逐行核实）**：

| 位置 | 事实 |
|---|---|
| `Runtime/SolverController.cs:1078-1082` | 回合开始（`SearchReason.AutoTurnStart`）先试 `ContinuationSource.TryCreateContinuation(liveStamp, hp, battleDamage, out reused)` |
| `:1084-1112` | 命中就 **不搜索**：`ContinuationsReused++`、打日志 `SEARCH_REUSED from_turn=… remaining_turns=… validation=exact_state_text`，直接 `StartDeployment(reused)` 按原路线部署 |
| `:1115-1140` | 不命中才重算，并且**归因**：`ManualDivergence`（玩家手改过）/ `ContinuationMissing`（缓存里没这一回合）/ `StateMismatch`（推演与实机不一致） |
| `SolverController.cs:175` | `UnexpectedReplanCount = StateMismatch + DeploymentDrift` —— 蓝本把这两种重算视为**不该发生**，会写进 bug report |
| `Runtime/SolverController.cs:136` | `SetRouteFrozen()` + `SolverOverlay.cs:1504` 的「冻结路线」按钮：连每回合的校验都跳过，纯按路线走到底 |

**我们的现状**：`AutoPilot` 只在**本回合**内持有计划，回合翻页就 `clearPlan()` 重算
（`verify/AutoPilot.java:680`）。所以：
- 搜索确实算到了 13~16 步 / depth 6~15 回合（日志 `PLAN ready` 可见），
  但**跨回合的那一段每次都被扔掉**，那份推演从来没被用上第二次；
- 今天真机 160 次规划里 **77 次是回合翻页重算**，正是被丢掉的次数；
- 因此「深搜」实际上只在**单回合内**兑现，跨回合完全是重来 —— 这就是「世界线没被展开」。

**要做的事（等任务书）**：搬 `Continuation` 缓存（每回合的期望状态 + 该回合该走的动作）、
回合开始先校验再决定复用/重算、复用命中打 `SEARCH_REUSED`、失败按
`ManualDivergence / ContinuationMissing / StateMismatch` 归因。没有这一层，
再加深搜索预算也只是把「一次性的推演」算得更贵。

### P1 · 「搜索早早穷尽」——**这个诊断已被推翻**（2026-11-01 用真机快照实测）

上一轮写在这里的结论是：「475/475 次带 `tree exhausted`、节点只用 0~10% → open 表过早耗尽、
剪枝太狠、加预算无效」。**这是把日志标签读错了**，已被 123 个真机快照的批量回放否掉：

| 量 | 旧说法 | 实测（`SnapshotBatchProbeTest`，123 个 `turn-*.json`） |
|---|---|---|
| 停止原因 | 「树被搜完了/剪枝太狠」 | **123/123 全是 `horizon`** = 把整场战斗推演到了终局 |
| 赢的局 | 未测 | **115/123 = 93.5%**（`lastSelectedVictory`，不是看分数） |
| 节点用量 | 0~10% | 平均 **11.8k / 500k ≈ 2.4%**（因为旅程本来就那么长） |
| 与束宽关系 | 剪枝太狠 | 单例 A1F12T2：w=135→900 结果完全不变（lost，-1e12） |

- `horizon` 是正常终止：搜索把每条线都推演到了「打死 / 被打死」，自然用不到预算。
- **不是** `open_exhausted`（那是新颖度通道自己的停止词，属于正常）。
- 真正可疑的漏杀只有 1 例（`A1F12T2`：28 血对 95 血拉格维林，唯一能赢的首手 `Strike_R`
  只在**强制前缀**下出现 `WIN`）——前缀会改变模拟出来的未来（意图/RNG 相关），
  属于**模型/估值**问题，不是剪枝问题。下一轮要从 `IntentLearning` / 怪物意图模型入手。
- 已落地：`BeamSolver.lastStopReason`（victory/horizon/node_limit/time_limit）、
  `lastSelectedVictory`（修掉「用分数阈值判胜负」这个错），日志改成 `stop=...`。

### P2 · 能力牌时机（部分缓解，未闭环）

- 旧证据：`Feel No Pain` 在手 14 次、只打出 2 次。
- 新证据：123 个真机快照里，**首手是能力牌的只有 2 次**（`Dark Embrace`、`Demon Form`，两局都赢）。
  说明「首手开能力」很少但不为零；`BeamSolverTest.playsAScalingPowerWhenThereIsNoPressure`
  也保证「无压力时先开成长」。
- 已补的 parity 缺口：`LatentSetupValue` 的启用条件原本只写了 `enemyCount > 1`，
  蓝本是 `_isActEndingBoss || _initialEnemyCount > 1` —— **Boss 那一半丢了**，已在
  `Score.initialBoss` 补上（A/B：123 快照首手变化 0 次，属于「补对了但样本没覆盖」）。
- **P2 已被 A/B 判掉一半**（`PowerTimingProbeTest`，123 快照）：
  手上有能力牌 57 局，计划里打出它的 **9 局**；强制「先开这张能力」重搜同一局面 →：
  **先开更差 50 局 / 先开更好 7 局 / 先开才赢 0 局**。
  所以「少开能力」在当前估值下**是对的**，不是 bug；P2 的旧叙述（「结构性永远轮不到」）
  缺证据，不要再按它改代码。
- 仍缺：最终排序 `better()` 比的是 `score`（不含 `persistentBuffValue`）。蓝本
  `FinalPlanOrdering` 在 `StrategicHpDeficit` 与 `EnemyHp` 之间有 `HealthResourceCost` /
  `LongTermResourceValue` 两个键，我们一个都没有 —— 这是**口径差异**，但上面 A/B 说明
  补它未必让牌打得更好，动之前必须先有能赢的样本。

### P3 · 对拍队列污染 —— **已修（commit `319bd6e`）**

- 根因：`ShadowTracker.onCardPlayed` 在上一张还没结算完（`pending`）时，把**后一张直接 skip**。
  后果：那张牌既不进 `comparisons`、也不产生属于自己的 MISMATCH，而 `pre` 还停在更早那一手。
- 修法：新牌到达时先 `resolve(true)` 把上一张当场结算（`resolve` 末尾会把基线重设成实机状态），
  再开新的一次。日志改为 `DIFF flush <前一张> before <后一张>`。
- 回归：`ShadowTrackerQueueTest`（2 条，用纯数据 + `ShadowTracker.liveState` 注入）。
  同一条失配只记 1 次 `mismatchSequence`，第二张不再被连坐。
- ⚠️ 真机复测未做（见 §九）；「不再出现 pending skip」需要真机日志确认。

### P4 · 数据干净度 —— **已修（commit `f05db61`、`cf09b74`）**

- `Lantern` / `NeowsBlessing` / `Pear` 进白名单，理由逐件 javap 核实（不是「看起来没事」）。
  同时补了反向测试：真有未镜像遗物时必须继续告警。
- `SimState.capture()` 把 `PotionSlot`（空槽）映射成 `null`：`instanceof PotionSlot` +
  ID 字面量双保险，`normalizePotionId` 有单测。
- 附带发现（**上一轮记错的一件**）：`Lantern` 只在**第一回合**给 1 点能量，
  而 `baseEnergy` 取的是首回合当前能量 → 仿真从第二回合起每回合**多算 1 点**。
  已拆成 `energy` / `energyPerTurn`（`EnergyManager.energyMaster`），有单测。

### P6 · 药水三选一（发现）卡死 9 秒 → 0 费牌白开（**已修 `769f919`，未真机复测**）

真机现象（用户 2026-11-01）：「总是开能力/技能/攻击药水，开出 0 费牌后不打，白白浪费药水」。

日志实证（`2026-10-31.log`，22:13:01）：

```
22:13:01.145  PLAN steps: POTION:ColorlessPotion      ← 计划只有「喝这瓶药」
22:13:01.145  AUTO potion ColorlessPotion (slot 1)
22:13:01.195  REWARD pick #0 Secret Technique         ← 三选一选中了（0 费牌）
22:13:01.195 → 22:13:10.278                           ← 🔴 9.1 秒全无日志，回合停住
22:13:10.779  AUTO play Secret Technique              ← 人的手点了一下之后才继续
```

根因（字节码核实 `CardRewardScreen.cardSelectUpdate`）：

- 它先算出「鼠标当前**悬停**的那张」（`hb.hovered`），再读**那一张**的 `hb.clicked`
  （偏移 58-70 / 73-88 / 142-153）。
- 我们的 `GridSelector.markReward` 只把目标卡的 `hb.clicked = true`，**没把鼠标放上去** ——
  鼠标不在那张卡上时这个标记没人读，界面就一直开着，AUTO 因 `isScreenUp` 全程早退。
- 更糟的是「重试」是死代码：`if (rewardPicked == group) { if (attempts++ < LIMIT) return; return; }`
  两个分支都 `return`，永远不会第二次点。

修法：按本类 `prepareConfirm` 已有的做法，把虚拟光标挪到卡面中心（`InputHelper.mX/mY`）+
保留 `hb.clicked`，并改成「界面没关就继续点同一张」、每 60 帧打一条 warn。
`InputHelper.update()` 每帧用真实鼠标重写坐标，所以覆盖只活当前帧。

⚠️ 这是**输入仿真**，没有单测可写（要真机界面）；下一轮判据：日志出现
`REWARD pick #0 <card>  cursor->x,y` 之后**不应**再出现
`REWARD pick still blocked after N frames`，且喝药后 1 帧内就能出牌。

### P6.1 · 药水闸门假阴性：闸说「别喝」，计划照喝（**已修 `3da4e6c`，未真机复测**）

真机现场（`2026-11-01.log`，9 次喝药里 **6 次**是这种）：

```
14:31:22.722  POTION_GATE cost=9 hp_saved=0 potions=1 rejected=true   ← 闸说别喝
14:31:22.730  PLAN steps: Strike | POTION:ColorlessPotion | …        ← 计划里还在喝
14:31:23.363  AUTO potion ColorlessPotion (slot 0)                   ← 真喝了
```

根因：`BeamSolver.bestFirstAction` 的顺序是「**闸门 → 0 费补搜 → 新颖度二选一**」。
新颖度那条候选是在闸门**之前**算的、带着喝药步骤，`better()` 一比较就能把闸门否掉的路线
换回来 —— 闸门等于白跑。

量化（`-Dbatch` 扫 107 个有药水的真机快照，真机档位 宽 135 / 节点 50 万 / 分支 72 / 深度 500）：
闸门拒绝 9 次、其中 **4 次被捞回**（`A1F4T2 / A1F7T2 / A1F13T2 / A2F20T3`，全部 `novelty=true`）。

修法：把二选一挪到后处理**之前**（蓝本 post-search 链处理的也是「已定下来的答案」）。
回归：`PotionGateBeatsNoveltyTest` + 真机夹具 `src/test/resources/golden/act1-potion-gate-novelty.json`
（2026-11-01 13:12 A1F7T2，修前 RED「闸门拒绝之后路线里仍有喝药」→ 修后 GREEN）。

⚠️ 副作用（要团队拍板）：闸门生效后**普通战斗里基本不会喝药**（买不回 9 血就不喝）。
这与蓝本一致（药水是稀缺资源），但要「该喝就喝」必须搬蓝本的 `SolverPotionPolicy`
档位（Smart / RequireAtLeastOne / Disabled），**未搬**。

### P5 · 影子数值缺口 —— **已修（commit `90ad057`），且旧结论有一处是错的**

- `Burn+` = **4**（`magicNumber 2` + `upgradeMagicNumber(2)`），已修，未升级仍是 2。
- ❌ **`Decay+` 不存在**：`Decay.upgrade()` 字节码是 `0: return` 空实现，`use()` 里 `iconst_2` 写死，
  所以恒为 2。上一轮写的「Decay+ 2→3」是错的，已按实测改。

> 事实 / 假设 / 已实施 / 仍缺验证 四栏必须分开写。真机没跑过的项一律写「未验证」，不得写「已修复」。


---

## 六、🔴 反复踩过的坑（必须知道）

1. **减益量可以是负数**：`ApplyPowerAction(amt)` 的 amt 是**带符号增量**（扣敏捷 = `-N`）。
   构造器与落地条件都不能 `Math.max(0,…)` / `>0` 夹 —— Lagavulin 的 Siphon Soul 因此错了很久。
2. **查 power 有没有 hook，先 `javap -p` 列方法名**（不带 `-c`）：`duringTurn` / `onAttacked` /
   `atEndOfTurn` 这类钩子只看 `javap -c` 的片段会漏（Exploder 的爆炸在 `duringTurn`）。
3. **「能力被 set 但不生效」是静默 bug**：`grep 'powerOnPlayer("'` 逐个查有没有生效逻辑。
4. **能力名必须用原版 ID**：`SimState.capture()` 用 `pw.ID`；Flex 是 `"Gain Strength"` 不是 `"Flex"`。
5. **能力挂载阶段**：`START_OF_TURN` 在 `energy = energyPerTurn` 之前 → 加能量要挂 `START_OF_TURN_POST_DRAW`。
6. **药水 ID 格式不统一**：多数带空格；`PowerPotion` / `FairyPotion` / `GamblersBrew` /
   `ElixirPotion` / `LiquidMemories` 不带。
7. **抽牌从堆顶**：`draw.remove(size-1)`；抽空要洗回弃牌堆，否则 AUTO 第 3 回合起手牌永远空。
8. **`PoolCatalog.randomCombatCard` 必须用 5 参构造**（4 参会丢 type）。
9. **升级态镜像缺失 = AI「像不会打仗」**：缺 `+` 键 → ActionRejected → 分支静默跳过。
   费用不进镜像（`effectiveCost`）；`Limit Break+` 去 Exhaust 要用 `register`。
10. **计划只在本回合有效**，回合翻页丢弃重算；不要自己搞「冻结两回合」。
11. **死路之间不比优劣**：`Survives` 必须用投影血量。
12. **Boss 分档只有「房间类型」能搬**；`_isActEndingBoss`（幕末回血）在 STS1 不存在。
13. **`depth` = 最多几个回合层**，安全上界 `SAFETY_MAX_TURN_LAYERS = 500`。
14. **新卡接入先跑 `python tools/audit_registry.py`**（查 Exhaust 与费用作用域）。
15. **固定前缀：打不出就整条作废**，不许换一张牌顶上；前缀是手牌下标，不能跨回合缓存。
16. **`endTurnQueued` 不是自己置就完事**（本轮教训，见 §3.2）：回合末结算挂在
    `cardQueue` 的 `card==null` 哨兵上，必须走 `EndTurnButton.disable(true)`。
17. **面板渲染路径禁止 `SimState.capture()`**：只在主线程动作稳定后由 `AutoPilot` 推快照，
    渲染只读缓存（`ui/StrategyStatusSnapshot`）。

---

## 七、已拍板决策与下一步顺序

- **D1**：接受 `EndTurnButton.disable(true)` 作为候选修复方向；真机逐项通过前，回合末问题保持未闭环。
- **D2**：`EndTurnProbe` 保留到本轮真机验证结束；先修正“队列非空 = 永远不会启动”等误导措辞，验证后移除或明确关闭。
- **D3**：先闭环 UI 与回合末真机验收，再修 P3 让验收数据可信，随后把 P1/P2 作为同一搜索问题推进；P4/P5 是独立小修，可直接 TDD，不必排在所有大项之后。
- **D4**：交接结构沿用，但所有条目必须区分已证实事实、当前假设、候选修复与已验证结果。

1. **构建、安装并完成 UI 十条真机验收与 §3.2 回合末 A/B 验证**，记录实际日志，不只跑自动化。
2. **P3 对拍噪声**：先让验收数据可信，否则不用污染数据评价 P1/P2。
3. **P1/P2 搜索与能力牌排序**：先写根因设计和验收判据，再直接实施；查 open 表为何清空，不靠加预算掩盖。
4. **P4/P5 数据与镜像小修**：独立 TDD，可提前完成并单独 commit。

---

## 八、关键文件速查

| 文件 | 作用 |
|---|---|
| `search/BeamSolver.java` | beam search 主循环（回合层结构） |
| `search/Score.java` | 估值（`baseTotal` / `beamTotal`） |
| `search/AsyncSearch.java` | 后台搜索 + 指纹校验 |
| `mirror/MirrorRegistry.java` | 卡牌镜像注册 |
| `mirror/hooks/RelicHooks.java` | 遗物钩子（含 `onPlayerEndTurn` 山铜） |
| `mirror/hooks/PowerHooks.java` | 能力钩子 |
| `mirror/Potions.java` / `PotionValuation.java` | 药水镜像 / 战略 HP 门槛 |
| `sim/CombatFlow.java` | 回合流程（`endTurn` / `hitPlayer` / `draw`） |
| `sim/SimState.java` | 影子状态（`capture` / `diff` / `fingerprint`） |
| `verify/AutoPilot.java` | 执行层（`tick` / `requestEndTurn` / `runUntil`） |
| `verify/ShadowTracker.java` | 逐张对拍 |
| `verify/RoutePreview.java` / `BoundedAutoTarget.java` | 路线预览 / 有界 AUTO |
| `ui/SolverPanel.java` / `StrategyStatusSnapshot.java` | 面板 / 只读策略现状 |
| `patches/EndTurnProbe.java` | 回合结束状态机只读探针（排查用，可随时 revert） |
| `docs/research/blueprint-parity.md` | **蓝本复刻对照表**（施工总纲） |
| `docs/plans/2026-10-31-ui-parity-design.md` | UI 两刀的已批准设计 |

---

## 九、下一轮开工顺序（真机卡在这里，别绕）

**阻塞事实（2026-11-01 01:12 实测）**：本机 `Steam` 原本未运行；我启动 Steam 后运行
`jre\bin\java.exe -jar mts-launcher.jar`，**游戏没有起来**（45 秒内无 `java` / `SlayTheSpire` 进程，
`solver-logs/` 也没有当天新文件）。用户确认「启动失败」，要求次日再做。
已安装的包是 `build 1101-011033`（md5 `b47c53e4…`，与 `~/.sts1-combatsolver/build/libs/` 完全一致）。

**下一轮第一件事（按顺序，缺一不可）**

1. 用户手动启动 Steam → 在游戏目录运行 `MTS-launch.cmd`（**不要**用
   `jre\bin\java.exe -jar mts-launcher.jar` 绕过，2026-11-01 用这条路径起不来）。
2. 进战斗后看面板右上角 build 号 = **`1101-011033`**（不是旧号才说明本次包生效；
   ModTheSpire 只在启动时读 mods 目录，中途换 jar 不生效）。
3. 回合末 A/B：身上带金属化 / 多层护甲（有灼烧更好），**先手点一次结束回合**记录
   `EOT` 两行的 `hp/block` 差值，再 `开 AUTO` 让它自己结束一回合，比同一 turn 的两段差值。
   判据：两边 block 增量一致 = 修复成立；AUTO 侧为 0 = `6ea8014` 没解决，转 `mirror/` 与执行时序。
4. UI 十条（`docs/plans/2026-10-31-ui-parity.md` Task 4 Step 2）：只读区、settled 刷新、
   AUTO off 点 Tn 停、改目标、完整敌方阶段、取消、跨战斗清理、无 END_TURN 尾行不可点、
   两种分辨率布局与点击穿透、日志含 bounded start/stop。
5. 对拍复测：连续出牌时日志应出现 `DIFF flush …` 而**不再**出现
   `skipped (previous … still pending)` —— 这是 P3 修复的真机判据。
6. 全部通过后再删 `patches/EndTurnProbe.java`（或把它改成显式关闭），并跑一次全量回归。

**不要做**：不要因为「日志里没有 pending skip」就宣布 P3 通过（要先确认那段日志确实跑到过）；
不要把 `horizon` 再读成「剪枝太狠」；不要用 `lastSelectedScore >= 1e10` 判胜负。
