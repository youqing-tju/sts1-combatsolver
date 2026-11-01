# STS1 战斗求解器 · 交接说明（2026-11-01 20:28 版）

> **用法**：整份贴进新对话，第一条消息就给全上下文。
> **项目根**：`C:\Users\22757\Desktop\sts1-combatsolver`　**分支**：`master`（不是 main）
> **当前状态**：2026-11-01 21:05，测试 **692 条全绿**（0 fail / 0 err，15 skipped），
> 代码 HEAD = 纠偏四刀 + 抢錢怪一刀（`44e8ae6` → `ff2e6ee` → `8c429ac` → `652ae13` → `747f7a8`），未 push。
> 已安装 `build 1101-204319`（含抢錢怪建模）。⚠️ 游戏进程 49456 是**安装前**启动的，
> 要重启 MTS 才会加载新 jar。
>
> 🔴 **硬规则第一条 = 禁止自创，蓝本没有 = 不做**。
> `docs/plans/2026-11-01-strict-blueprint-parity.md` 的 Task 1–4 **已全部执行完**：
> 撤销默认冻结 / 单步剔除 / 无生还禁打、删除有界 AUTO 与伪 Adopt/Freeze、
> Continuation 改为「best 选边界 + 最终路线回放捕获」、药水策略恢复蓝本组合与失败语义。
> 逐项结果见 `docs/research/blueprint-parity.md`「附：2026-11-01 来源审计」的**结果**列。
>
> 🔴 **本轮全部真机项仍是 0 项**：jar 未重装、未进游戏。自动化全绿不等于 parity（旧教训）。
>
> 真机日志实测（`2026-11-01.log`，旧 build）：234 次规划 / **48 次 miss / 0 次命中**。
> 用 497 个快照做相邻回合配对复现（97 对）定位到两个硬 bug 并已修：
> ① 玩家虚体 ID 是 **`IntangiblePlayer`**（`IntangiblePlayerPower`，javap 核实），
> 我们全程只认 `Intangible` → 影子看不到减伤，`hp` 首差 **45/97**；
> ② `hpLossCount` 从来没捕获过（恒 0），影子却每次掉血 +1 → 首差 **16/97**。
> 剩余首差：`playerPowers.size/id` 12+3、`energy` 6、`discardPile` 5、`hand` 2、`block` 2。
> 用户反馈三条已修（`db60ff7`）：① 无生还路线 → 停手交还控制（蓝本 `StopFullAutoOnDeathTurn`，
> 含一个会让它失效的 off-by-one）；② 回合翻页不再清空预览（消除「路线每回合闪一下」）；
> ③ 路线区行数 3 → 按屏幕自适应（1080p 约 16 行）。🔴 **三条均未真机验证**。
> ✅ **回合末触发已在真机验证通过**（2026-11-01 13:17 用户确认）。
> ✅ **P7（跨回合精确续跑）与 P8（药水策略 D5）已实施，自动化全绿**；
> 🔴 **两者的真机验收都是 0 项**（用户 2026-11-01 挂起真机），本文件不把自动化通过当作真机闭环。
> ⚠️ UI 十条验收仍是 **0/10**；药水三选一修复（`769f919`）与药水闸假阴性修复（`3da4e6c`）同样未真机复测。

---

## 一、你是谁 / 这是什么

STS1（杀戮尖塔）**进程内**战斗求解器，Java 8 ModTheSpire mod。
对标 STS2 的 `Torch1230/CombatSolver`（蓝本在 `reference/sts2-combatsolver`）。
只做**战斗层**（Ironclad，Act 1–4）；训练、地图、选牌、商店、事件、篝火均后置。
模块：`capture / sim / mirror / search / eval / exec / ui / verify / diag / bootstrap`。

## 二、🔴 硬规则（违反 = 白干）

1. **禁止自创，一切以蓝本为标准复刻**（2026-11-01 19:35 用户最新强调，覆盖此前所有例外）。
   ①蓝本找实现 → ②理解 → ③只翻译到 STS1 API；不得改变机制、默认值、约束强弱、状态顺序或失败语义。
   **蓝本没有 = 不做**；不得用「用户此前要求 / 最小等价 / 本地扩展 / UI 适配」豁免。
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

## 三、本轮做了什么（2026-10-31 → 2026-11-01）

### 3.0 一句话现状

测试 **672 全绿**，HEAD `53853ec`，已安装 `build 1101-193207`，未 push。
**真机只验过一件事**：回合末触发已正常（用户 2026-11-01 13:17 口头确认）。
其余全部只有自动化或回放证据，**UI 十条验收 0/10**。

🔴 **当前代码默认是项目自创的「采纳+冻结+失配照打」**，不是蓝本 `AdoptRoute` / `RouteFrozen` 的默认语义。
`-Dsts1.adoptRoute=false` 才接近蓝本严格 Continuation 路径，但严格纠偏要求删除这套自创模式，
而不是长期保留一个产品开关。见 §九与 `docs/plans/2026-11-01-strict-blueprint-parity.md`。

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
| `6ea8014` | **AUTO 结束回合改走 `EndTurnButton.disable(true)`** —— ✅ 真机已验证（2026-11-01 13:17，详见 §3.2） |

测试数 533 → **547**。`search/`、`mirror/`、`sim/`、`config/` **一行未改**。

### 3.2 回合末触发整批丢失 —— ✅ **真机已验证通过**（2026-11-01 13:17）

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

**当时的假设**：少 2、5 使 `cardQueue` 状态与手点不同，从而导致回合末效果丢失。
这与三类机制同时失效、手点正常相符；但探针已表明 `endTurn()` 入口确实启动，
**「哨兵项丢失」这一环始终没有被直接证明**，修法是排除法（消除与手点之间唯一的已知差异）。

**修法**：`AutoPilot.requestEndTurn()` 直接调 `AbstractDungeon.overlayMenu.endTurnButton.disable(true)`，
与手点共用同一原生入口；按钮不可用时退回 `endTurnQueued = true` 并 warn。
⚠️ 仍**不能**调 `actionManager.endTurn()`（那是 `EndTurnAction` 干的事，会跳过玩家侧整段：
战斗专注的「禁止抽牌」永不消失、怪物完全不行动）。

**验证状态（区分清楚）**：
- ✅ 用户 2026-11-01 13:17 定性确认：「回合结束时触发类型的已经能在真机中正常触发了」。
- ⚠️ **没有留证**：四项（灼烧 / 多层护甲 / 金属化 / 奥利哈刚）各自的 `hp`/`block` 差值、
  敌方阶段是否重复行动，都只有口头结论，没有日志数值。
- 副作用（与手点一致，已知可接受）：每回合播 `ENEMY_TURN` 音效、结束回合按钮短暂置灰。

### 3.3 2026-11-01 当天的一批修复（全部有测试，部分未真机复测）

| commit | 内容 | 证据 | 真机 |
|---|---|---|---|
| `f05db61` | `Coverage.INERT_RELICS` 补 `Lantern` / `NeowsBlessing` / `Pear`（逐件 javap 核实） | `CoverageInertRelicsTest` +2 | — |
| `90ad057` | `Burn+` = 4；**`Decay+` 不存在**（`upgrade()` 是空实现） | `StatusCardUpgradeTest` +3 | — |
| `cf09b74` | 空药水槽折 `null`；拆 `energy` / `energyPerTurn`（`Lantern` 只在首回合给 1 点，之前每回合多算 1） | `PotionSlotCaptureTest`、`SimStateEnergyPerTurnTest` | — |
| `319bd6e` | 对拍队列污染：上一张未结算时把后一张**整张 skip** → 改成先 `resolve(true)` 冲刷 | `ShadowTrackerQueueTest` +2 | 未复测 |
| `9536d7d` | 停止原因三态（`victory`/`horizon`/`node_limit`/`time_limit`）+ 候选池口径 + Boss 档延迟收益 | `BeamSolverStopReasonTest`、`ScoreLatentSetupBossTest`、`SnapshotBatchProbeTest` | 123 快照回放 |
| `769f919` | 药水三选一（发现）卡死 9 秒：原版只认「鼠标悬停那张的 `hb.clicked`」，我们没挪光标 | 无单测（需真机界面） | 未复测 |
| `3da4e6c` | **药水闸门假阴性**：新颖度二选一在后处理之后，把闸门否掉的药水捞回来 | `PotionGateBeatsNoveltyTest`（真机夹具） | 未复测 |

**P1 的旧诊断被推翻**（同日）：475/475 次 `tree exhausted` 曾被读成「open 表过早耗尽、剪枝太狠」，
用 123 个真机快照回放实测：**123/123 停止原因是 `horizon`**（把整场战斗推演到终局），
赢 115/123 = 93.5%，节点平均 11.8k/500k ≈ 2.4%。**不要再按“加预算无效、查剪枝”往下做。**

---

### 3.4 2026-11-01 晚：模式纠正 + 三个 UI 反馈 + 两个镜像硬 bug

用户当时三条反馈曾推动默认冻结；**2026-11-01 19:35 最新指令已取消这类本地例外**：
禁止自创，一切以蓝本为准。以下仅记录当时改动事实，不再作为保留依据。

| commit | 内容 | 证据 |
|---|---|---|
| `db60ff7` | ① 无生还路线 → 不部署、停 AUTO、面板红字（蓝本 `StopFullAutoOnDeathTurn`）；② 回合翻页不再清空预览（消除「路线闪一下」）；③ 路线区行数 3 → 按屏幕自适应（1080p≈16） | `NoSurvivingRouteTest`、`SolverPanelRouteLayoutTest`、`BeamSolverDeathTurnTest` |
| `c6233d0` | 【非蓝本，待撤】默认采纳+冻结、失配照打、单步剔除；【镜像修复保留】`IntangiblePlayer` 与 `hpLossCount` | `AdoptRouteTest`（旧测试需改蓝本反向合同） |
| `53853ec` | ①【蓝本】回合末实时风险停手；②【非蓝本，待撤】无生还路线禁止出牌；③ parity 来源审计 | `NoSurvivingRouteTest`（旧测试需改蓝本反向合同） |
| `7978d95`/`ba04343`/`f7b7ef8` | 文档 | — |

**为什么之前每回合重算**（真机日志 `2026-11-01.log`，旧 build）：234 次规划 /
**48 次 `SEARCH_REUSE_MISS` / 0 次命中**。用 497 个快照做相邻回合配对（97 对）复现，首差排序：

| 首差 | 次数 | 根因（已修/待修） |
|---|---|---|
| `hp` | **45/97** | ✅ 已修：玩家虚体 ID 是 `IntangiblePlayer`（`IntangiblePlayerPower`，javap 核实），我们只认 `Intangible` → 影子看不到减伤 |
| `hpLossCount` | **16/97** | ✅ 已修：从没捕获过（恒 0），影子却每次掉血 +1；改读 `GameActionManager.hpLossThisCombat` |
| `playerPowers.size/id` | 12 + 3 | ⛔ 待修：能力回合末生命周期（虚体 tick / 临时能力移除）与真机不同步 |
| `energy` | 6 | ⛔ 待修：回能加成（真机 4 vs 影子 3） |
| `discardPile` / `hand` / `block` | 5 / 2 / 2 | ⛔ 待修 |

> 这条链只证明：**精确续跑的前提是影子准**。它不再推出“默认冻结”；
> 最新规则要求先恢复蓝本校验/重算语义，再用这些首差修影子，禁止用冻结或删字段掩盖失配。

## 四、当前进度与里程碑

| 里程碑 | 状态 |
|---|---|
| M0 mod 骨架 / M1 镜像核心 / M2 搜索主体 / M3.1 卡牌镜像 | ✅ |
| M3.2 怪物镜像 Act 1/2/4 | ✅ |
| M3.3 怪物镜像 Act 3 | 🔶 55/68 只 |
| M3.4 药水镜像 | 🔶 21/50+ |
| M3.5 遗物镜像 | 🔶 47 件 |
| M4-1 完整路线面板 | 🔶 跨回合续跑已按蓝本返工（`8c429ac`）；点击 Tn / 默认冻结已删除（`ff2e6ee`）；`AdoptRoute` 与 `RouteFrozen` 均登记未做。**真机命中率仍未验证** |
| M4-2 预算档位 / M4-3 AUTO 执行 / M4-5 稳定性 | ✅ |
| M4-6 局外收益 UI / M5 验收（全自动 20 场） | ❌ |

---

## 五、🔴 未解决的严重问题（按优先级，下一轮从这里开始）

### P7 · 跨回合续跑 —— **已实施初版，但多处非蓝本，必须返工**

> 当前默认 `ADOPT_ROUTE_TO_THE_END=true` 是自创且待撤。初版还用最终节点链代替蓝本最终路线回放、
> 用单一 `from(SimState)` 代替 `CaptureLive/CapturePredicted` 分侧归一化，并把手牌排序成无序多重集；
> 这些均违反最新硬规则。严格按 `strict-blueprint-parity.md` Task 3 修正，命中率低就修影子。


**用户判断（2026-11-01）**：STS2 默认是「算一次，按序打完一整条路线」；我们这边
「第一回合算出的后续路线完全没用，第二回合重算又是新路线，世界线没被展开」。

**蓝本机制（逐行核实）**：`SolverController.cs:1078-1082` 回合开始先
`ContinuationSource.TryCreateContinuation`；`:1084-1112` 命中就不搜索、打
`SEARCH_REUSED … validation=exact_state_text` 直接按原路线部署；`:1115-1140` 不命中才重算并归因
`ManualDivergence / ContinuationMissing / StateMismatch`；`:175`
`UnexpectedReplanCount = StateMismatch + DeploymentDrift`（这两种视为**不该发生**）。

**本轮落地（6 个 commit）**：

| 位置 | 内容 |
|---|---|
| `search/ContinuationStamp.java` | 精确状态文本（**不是哈希**）：回合/资源、四牌堆（手牌按无序多重集、其余保序）、药水槽位、玩家 Power（保施加顺序）+ powerAux、遗物 counter、怪物（hp/block/intent/moveHistory/pendingMove 全部语义载荷/invincibleMax/damageBase）、六路 RNG（counter+state0+state1）。差异诊断精确到 `drawPile[3].upgraded` 这一级 |
| `search/CachedContinuation.java` + `ContinuationPlan.java` | 边界资格：只有 `END_TURN` 跨回合、玩家活着、敌人没全灭、后面确有动作才缓存；`lookup` 返回 `HIT / CONTINUATION_MISSING / STATE_MISMATCH` |
| `search/BeamSolver.java` `publishFinalRoute()` | 从**所有后处理之后**的最终 `selected` 一次性发布「动作序列 + 可执行步骤 + 回合边界」，三者同源 |
| `search/AsyncSearch.Result.continuations` | 纯数据跨线程交给主线程 |
| `verify/ContinuationDecision.java` | 四种 miss 归因（含 `DEPLOYMENT_DRIFT` 走 `ShadowTracker.mismatchSequence`）；**手工分歧优先于状态差异**（否则会把人引去查镜像） |
| `verify/AutoPilot.java` | 回合翻页处先试 `reuseContinuation`：命中按 `forecastOffset` 裁执行队列、**预览保留**、打 `SEARCH_REUSED`，不搜索；miss 打 `SEARCH_REUSE_MISS reason=… first_difference=…` 再重搜 |

**⚠️ 真机未验证**（用户 2026-11-01 挂起真机）。最需要盯的一条风险：
`ContinuationStamp` 按设计纳入 `intentDmg / damageBase / relics counter`，如果预测侧这些值与
真机有系统性偏差（未镜像怪物的意图、召唤物的卡面伤害、蜥蜴尾巴 counter），会出现**持续 miss**。
日志里会直接给出 `SEARCH_REUSE_MISS reason=state_mismatch first_difference=monsters[0].intentDmg …`——
那就是下一条要修的地方，不要当成「续跑没生效」草草放过。

### P8 · 药水策略（D5）—— **已实施（自动化通过，真机未验证）**

蓝本那套 `SolverPotionPolicy`（Disabled/Smart/RequireAtLeastOne）+ 每槽
`SolverPotionDirective`（Smart/Disabled/Force）+ 四预设，本轮全部搬过来并**接进搜索**：

- `search/PotionStrategySnapshot.java`：不可变快照，持久化键 `(slot, potionId)`，Smart = 删覆盖；
- `search/PotionUsePolicy.java`：蓝本 `IsEligible` 逐行翻译（STS1 无 BossHpRelief / 琥珀，两条分支不实现）；
- `BeamSolver`：候选展开最底层就拦掉违禁药水（worker/novelty/portfolio/基线/补搜全部继承），
  最终再跑一次 `evaluateFinalRoute`；**Force 不合格就做一次有界「钉住这瓶」补搜，仍不合格返回 null**
  （显式「没有满足约束的计划」，不静默退回违规路线）；
- `AutoPilot`：`policyRevision` 代际 + `AsyncSearch` 请求/结果携带，采纳时**指纹与代际一起校验**；
  策略改动 → 保存 + 代际+1 + 清 continuation/预览；**AUTO 执行中拒绝修改**；
- 面板药水区改为**真实控件**（点击 Smart→Disabled→Force），遗物/成长仍只读。

🔴 **parity 复核发现上述初版仍有四处非蓝本**：① 全局 Disabled 错压显式 Force（蓝本允许 Force）；
② 全局 PotionPolicy 落盘（蓝本 `[JsonIgnore]`，新设置默认 Smart）；③ `policyRevision` 是本地自创；
④ Force 失败时“钉住补搜→null”不是蓝本的统一候选池资格过滤 + 专用异常。
全部列入 `strict-blueprint-parity.md` Task 4，不得再把“D5 已复刻”当成完成。

**⚠️ 真机未验证**。另外要提前知道：`Smart` 档在当前成本公式下**普通战斗基本不喝药**
（买不回 9 血就不喝）—— 这是蓝本口径，不是 bug；要「该喝就喝」得改全局档位。

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
这与蓝本一致（药水是稀缺资源）。`SolverPotionPolicy` 初版后来已接入，但全局组合、持久化与失败语义
仍有非蓝本偏差，按 P8 / strict 纠偏 Task 4 处理，不能引用这里的旧「未搬」状态。

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
10. **蓝本默认是精确 Continuation**：命中续用，miss 归因重算；不要自造默认冻结、失配照打或单步剔除。
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

## 七、已拍板决策

- **D1**：`EndTurnButton.disable(true)` 是回合末问题的**已验证**修法（用户 2026-11-01 13:17 口头确认）。
  验证明细未留证，不影响结论，但别把「四项各自差值」当成已记录。
- **D2**：`patches/EndTurnProbe.java` 暂留（纯只读日志），等 UI 验收做完再决定删或改成显式开关。
- **D3**：**P1 的旧结论作废**（「open 表耗尽/剪枝太狠」），实测 123/123 是 `horizon`。
  不要再拿它当施工依据；`horizon` 是正常终止。
- **D4（历史）**：P7 跨回合 Continuation 是必要机制，但初版的节点链捷径、单入口 stamp、手牌排序均需返工。
- **D5**：完整药水策略必须按蓝本组合语义复刻；全局 Disabled 不压 Force，全局 policy 不落盘，
  删除本地 policyRevision 与“钉住补搜→null”。执行中拒绝修改仍是蓝本已有行为。
- **D6（2026-11-01 19:35 最新）**：下一轮只做 `docs/plans/2026-11-01-strict-blueprint-parity.md`。
  先撤自创执行/UI 机制，再修影子与真机验证；目标内自主施工和本地 commit，不逐项等确认。
- **D7**：交接结构沿用；所有条目必须区分**已证实事实 / 当前假设 / 已实施 / 仍缺验证**四栏。

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
| `search/PotionCostGateTest` / `PotionGateBeatsNoveltyTest` | 药水闸门回归（后者用真机夹具，钉死「闸门结论必须对最终答案生效」） |
| `src/test/resources/golden/act1-potion-gate-novelty.json` | 真机夹具：2026-11-01 13:12 A1F7T2，闸门拒绝却被新颖度捞回的现场 |
| `search/SnapshotBatchProbeTest` | 批量回放 `-Dbatch=<solver-snapshots>`，出胜负/节点/停止原因/能力牌时机 |
| `docs/research/blueprint-parity.md` | **蓝本复刻对照表**（施工总纲） |
| `docs/plans/2026-10-31-ui-parity-design.md` | UI 两刀的已批准设计 |

---

## 九、下一轮怎么开工

**唯一主线：严格蓝本纠偏**。施工计划与执行提示词：
- `docs/plans/2026-11-01-strict-blueprint-parity.md`
- `docs/plans/2026-11-01-next-round-prompt.md`

Task 1–4 已完成（见 `blueprint-parity.md` 结果列），**剩下的是 Task 5 的收尾与 Task 6 的验证**：

1. ``git status`` 干净、``./gradlew test --offline`` 679 绿；先 ``./gradlew jar`` + ``tools/install.ps1``
   把新 jar 装上，否则真机跑的还是旧盘。
2. 真机验收（`blueprint-parity.md` 末「结果列」逐条对应）：默认无冻结、有新回合要么
   `SEARCH_REUSED` 要么 `SEARCH_REUSE_MISS reason=…` 后根重算、单步不可用/药水槽漂移不剔步、
   仅「全都不生还」不触发停手、改策略后旧 continuation 作废且配置里只剩 `directive=` 行。
3. 真机命中率仍未知（纠偏前 97 对配对 **0 命中**，首差 `playerPowers` → `energy` →
   `discardPile` → `hand` → `block`）。按新回放捕获再取一轮日志，用离线配对工具复算。
4. 未做的蓝本机制**明确登记为未做**，不要补半成品：`AdoptRoute`、`RouteFrozen`、
   `SolverProgress` 的 play_depth、`PotionRewardOutlook`、琥珀限制。

### 🔴 自创清单（不是“登记后继续”，而是必须撤销/返工）

1. 默认采纳冻结、失配照打，以及把 AdoptRoute 与 RouteFrozen 合并；
2. 单步走不通只剔一步；
3. 无生还路线禁止出牌；
4. 点 Tn 有界 AUTO、冻结模式额外诊断等蓝本没有的本地机制；
5. P7 节点链代替最终路线回放、单入口 stamp、手牌无序化；
6. D5 全局 Disabled 压 Force、全局 policy 落盘、policyRevision、Force 失败钉住补搜→null；
7. 其他写着“最小等价 / 本地扩展 / 项目自定义 / 用户规则 / UI 适配”的行为，逐项重新审计。

### 不要做

- 不要用冻结、剔步或删 stamp 字段掩盖 `SEARCH_REUSE_MISS`。
- 不要用“用户此前要求”对抗 2026-11-01 19:35 的最新禁止自创规则。
- 不要把 672 条测试全绿当成蓝本 parity；其中多条旧测试需要改成蓝本反向合同。
- 不要新建 `CHANGELOG.md`（这个仓库从来没有过）。

### 历史遗留（仍未闭环）

1. UI 十条验收（`docs/plans/2026-10-31-ui-parity.md`）——0/10。
2. 药水三选一卡死修复（`769f919`）、药水闸假阴性修复（`3da4e6c`）、对拍队列修复（`319bd6e`）的真机复测。
3. P2 第一嫌疑（最终排序不看 `persistentBuffValue`）—— 需要能赢的样本才准动，见 P2。
4. `BeamSolver.offensiveProgressOf` 把**抽牌堆里的 Body Slam** 也算「格挡=进攻推进」，
   导致怪物不攻击时也会先出格挡（`turn-A1F16T2` 复现：那一回合格挡纯白给）。
   改法只有一行（只认手牌），但要先在 497 个快照上跑 paired A/B，有改善才入库。

---

## 十、2026-11-01 纠偏结果表（Task 1–5）

> 口径：**自动化** = `./gradlew test --offline` 的合同测试；**真机** = 进游戏实跑。
> 本轮全部真机项为 **未验证**：20:28 时游戏进程不存在，jar 已装 `build 1101-202828`。

| 纠偏项 | commit | 蓝本证据 | 自动化 | 真机 | 状态 |
|---|---|---|---|---|---|
| 默认不冻结；失配从根重算 | `44e8ae6` | `SolverController.cs:1077-1140` | ✅ `RouteReplanTest`(3) | 未验证 | 已完成 |
| 单步不可用 → DEPLOY_REPLAN | `44e8ae6` | `SolverController.cs:2925-3010` | ✅ `unplayableStepReplansFromRootInsteadOfDroppingOnlyThatStep` | 未验证 | 已完成 |
| 无生还路线不再禁打 | `44e8ae6` | `FinalPlanOrdering.cs:246-270` | ✅ `noSurvivingRouteDoesNotInventDeploymentBan` | 未验证 | 已完成 |
| 有界 AUTO / 点 Tn 删除 | `ff2e6ee` | `SolverOverlay` 路线行 `MouseFilter=Ignore` | ✅ `NoLocalRouteControlTest`(2) | 未验证 | 已完成 |
| AdoptRoute / RouteFrozen 不伪实现 | `ff2e6ee` | `SolverController.cs:128-158` | ✅ 同上（反射） | — | 登记未做 |
| 边界只选下标、状态由回放采集 | `8c429ac` | `Terminal.cs:337-390` | ✅ `ContinuationPlanTest`(4)+`BeamSolverContinuationTest.bestNode…` | 未验证 | 已完成 |
| CaptureLive / CapturePredicted 分侧 | `8c429ac` | `ContinuationStamp.cs:68-182` | ✅ `liveAndPredictedCaptureAgreeOnTheSameState` | 未验证 | 已完成 |
| 四牌堆（含手牌）保序 | `8c429ac` | `AppendLivePile/AppendPredictedPile` | ✅ `handOrderMattersExactlyLikeDrawDiscardAndExhaust` | 未验证 | 已完成 |
| 全局 Disabled 不压显式 Force | `652ae13` | `PotionStrategySnapshot.cs:66-87` | ✅ `globalDisabledStillAllowsExplicitForceDirective` | 未验证 | 已完成 |
| 全局档位不落盘 | `652ae13` | `SolverSettings.cs:94-96` `[JsonIgnore]` | ✅ `onlyPerSlotDirectivesRoundTrip` + 配置往返 | 未验证 | 已完成 |
| 删除 policyRevision | `652ae13` | `SolverController.cs:1899-2008` | ✅ `PotionPolicyChangeTest`(6) | 未验证 | 已完成 |
| 无合格候选抛专用异常 | `652ae13` | `FinalPlanOrdering.cs:274-282` | ✅ `unsatisfiedPolicyThrowsDedicatedExceptionInsteadOfPinnedRescueOrNull` | 未验证 | 已完成 |
| 来源审计与保留项登记 | `638e970` | 见 `blueprint-parity.md` | — | — | 已完成 |
| 抢錢怪：金币被偷/找回/逃跑 + 排序键 | `747f7a8` | `Looter/Mugger/DamageAction`(javap) + `FinalPlanOrdering.cs:253-256` | ✅ `TheftTest`(6)+`TheftOrderingTest`(3) | 未验证（需重启游戏） | 已完成 |

**未闭环**：① 全部真机项；② 真机跨回合命中率（纠偏前 97 对配对 0 命中）；
③ 影子首差 `playerPowers` → `energy` → `discardPile` → `hand` → `block`；
④ `AdoptRoute` / `RouteFrozen` / play_depth / `PotionRewardOutlook` 未做。

**回退方式**：四个 commit 相互独立且都在 `master` 本地（未 push）。
`git revert 652ae13 8c429ac ff2e6ee 44e8ae6` 可整体回到 `73f44c8` 的旧语义（旧测试会一起回来）。
