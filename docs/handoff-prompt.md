# STS1 战斗求解器 · 交接说明（2026-11-02 23:00 版）

> **用法**：整份贴进新对话，第一条消息就给全上下文。
> **项目根**：`C:\Users\22757\Desktop\sts1-combatsolver`　**分支**：`master`（不是 main）
> **当前状态**：2026-11-02 23:00，测试 **822 条全绿**（0 fail / 0 err，18 skipped），
> 代码 HEAD = `8df0d70`，未 push。已安装 `build 1102-210032`（**游戏里加载的仍是 142939，未真机验证**）。
>
> 汇报：`docs/reports/2026-11-02-mirror-fidelity-report.md`。
>
> 🔴 **硬规则第一条 = 禁止自创，蓝本没有 = 不做。**
>
> 🔴 **本轮分两批**：①14:00–14:45「稳定对拍 + 模型根因」（纯离线）；②20:38–21:48「真机验证 + 二次修复」。
> 合计 12 个 commit，修掉 13 个字节码证实的镜像缺口 + 3 处对拍口径。见 §十四。
>
> 🔴 **最新 build `210032` 尚未真机验证**：游戏 20:38 启动加载的是 `142939`，
> 那一轮的 27 次失配已被定位并修掉 3 类（baseline 飞行中捕获 / 账本字段 / stamp 载荷），
> 但**修复效果未经真机确认**。下一轮第一件事 = 完全退出游戏再启动，确认日志 build 戳。
>
> 🔴 **已拍板的三条**（不变）：
> ① 不新增「无生还路线就交还控制」的闸（蓝本没有，`Survives` 只是排序键）；
> ② 目标是「战损最小 + 收益最大」，不是「能赢就行」；
> ③ **不加「失配后用实机重建影子」这类鲁棒性机制**：只治本，逐条消灭镜像根因。
>
> 🔴 **并行施工教训**：多个 agent 共用 `~/.sts1-combatsolver/build` 会互相踩坏
> （`NoSuchFileException: in-progress-results-generic.bin` / `EOFException`）。
> 并行时必须各自 `local.properties` 指独立 `build.dir`。

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
- **D6（历史，已完成）**：严格纠偏 Task 1–5 已落地，不再作为下一轮施工计划。
- **D7**：交接结构沿用；所有条目必须区分**已证实事实 / 当前假设 / 已实施 / 仍缺验证**四栏。
- **D8（2026-11-01 22:43 后最新）**：路线质量按用户批准的完整最终选择器复刻，
  只做 `docs/plans/2026-11-01-final-plan-ordering.md`；蓝本是 18 键，
  完整候选池、资格过滤、成长/收益/卖血数据源与比较器边界不得拆分豁免。

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

> **唯一施工计划**：`docs/plans/2026-11-02-mirror-fidelity.md`；
> 设计：`2026-11-02-mirror-fidelity-design.md`。18键路线质量已完成，不再重开。

1. `git log --oneline -5` → `./gradlew test --offline`（当前784绿）→ 冻结 build/run 证据。
2. **先修观测与回放**：完整 v5 快照、单一 JSON parser、稳定 MISMATCH DUMP 重放；
   对拍稳定门必须包含 `GameActionManager.currentAction`，90帧未决不能写真实 MISMATCH。
3. **并行修已证实模型根因**：BeatOfDeath 致死仍触发、Heavy Blade+PenNib、Brutality 回合初、
   通用升级16改费+34改伤、Madness持续重抽、手牌保序diff、整手牌逆序弃牌、
   Fiend Fire批消耗后抽牌、原生10张手牌上限。
4. **13:00后长尾**：颈环回合清零 → HAND/DISCARD/EXHAUST选择坐标 →
   SporeCloud/Vulnerable/SpireShield → 跨卡flush基线。每项独立RED/GREEN/commit。
5. **续跑验收**：新建严格相邻回合探针；旧151对缺v5字段只能报 incomplete_schema。
   124214在12:56后的真机基线是 `SEARCH_REUSED=0/30`；不得用旧文件或删字段伪提命中率。
6. 未做的蓝本机制仍然未做：`AdoptRoute`、`RouteFrozen`、`play_depth`、
   `PotionRewardOutlook`、琥珀限制、`SolverTheftPolicy.LetEscape` 的 UI 开关。

**已推翻的旧施工假设**：不再加逐卡牌格挡日志；前7条block差1是早拍，Bash那条才是致死钩子真bug；
PenNib第9挂/第10摘已存在；Apotheosis费用差不是Madness随机顺序；Headbutt/True Grit先修选择坐标与证据。

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

---

## 十一、2026-11-01 夜间真机修复（20:28 → 22:45）

> 这一段的**全部结论都来自真机日志 + `solver-snapshots/turn-*.json`**，不是自动化测试发现的。
> 每一行都能在日志里指回现场；日记细节见 `记录.txt` 第 55–64 条。

| # | 症状（真机原话/日志） | 根因 | 修法 | commit |
|---|---|---|---|---|
| 1 | 「遇到抢劫的就只会防御，白白被偷钱」 | 影子根本没建模偷钱；最终排序也没有资源键 | `SimMonster.stolenGold` + `SimState.outstandingStolenGold()`；`better()` 加「被偷金币」键（排在战略血量赤字**之前**，照 `FinalPlanOrdering.cs:253-256`） | `747f7a8` |
| 2 | 「又自己自动走向死亡」（1 血 34 格挡被打死） | `getIntentDmg()` 只给**一段**伤害，地精首领是 3 段；捕获侧把一段当总数 | `SimState.totalIntent()`：按 `damage` 列表推段数（同值才算多段），捕获成总伤害 | `d1623eb` |
| 3 | 失配：`Wound+ vs Wound`、`CurseOfTheBell+ vs Curse` | 神化调用 `upgradeAllPiles` 时漏了 `canUpgrade` 判据 | 逐张按原版 `AbstractCard.canUpgrade()`（诅咒/状态升不了） | `312933a` |
| 4 | 「干瘪之手镜像有失配吗」 | 候选判据写成「或」（`cost>0 \|\| costForTurn>0`），原版是**与**；候选数一变，`random(0,size-1)` 抽到另一张 | 改「与」，并把我们的 `costForTurn=-1` 哨兵翻成有效费用 | `e2160ac` |
| 5 | 「打完觉醒者 1 阶段就不打了」 | 觉醒者半死时 `currentHealth==0`，`firstAliveMonster()` 返回 null，`tick()` 直接 return；而敌方回合只有结束回合才推进 → **死锁** | 无活怪但 `areMonstersDead()` 为假 → 走按钮路径结束回合 | `2d2e10e` |
| 6 | 「一连跳过两个回合 / 第一回合啥都不干」 | 二阶段把 turn 重置成 1，发牌没落地时 hand=0，当帧就结束回合（日志 `empty hand #1/#2, draw=35/30`） | 空手牌 + 牌堆有牌 + 本回合没动作过 → 先等最多 90 帧；打完了仍立刻结束 | `68302f4` |
| 7 | 「有蜥蜴尾巴还不会死，控制就交还了」 | 回合末风险闸用 `projectedHpOf` 一阶减法，**不认死亡豁免** | 改成真跑 `CombatFlow.endTurn`（与搜索同引擎）；推不动才退回估计 | `de9de4b` |
| 8 | boss 战 406 ms 就以 `stop=victory` 收工 | 换形态只挂在 `hit()`，AoE 走 `hitAndReturnDealt` 绕过 → 觉醒者被当真死 → 假胜利 | `maybeAwaken` 挪进 `hitAndReturnDealt`（所有伤害来源都过） | `de9de4b` |

**未闭环**（下一阶段）：`Plated Armor` 递减差 1~4 / `Reaper` 回血差 1~2 / `Headbutt` 弃牌堆顺序 /
`Barricade` 量对不上；以及 §九第 3 条的「收益最大化」完整 18 键与最终选择链复刻。

---

## 十二、2026-11-02 路线质量（18 键 FinalPlanOrdering）

> 目标（用户 11-01 22:41）：「不能光赢，要战损最小 + 收益最大的赢法，和蓝本一样」。

| 改动 | commit | 蓝本证据 | 自动化 | 快照 A/B | 真机 |
|---|---|---|---|---|---|
| 18 键纯数据排序（1 OrderBy + 17 ThenBy） | `4571418` | `FinalPlanOrdering.cs:253-271` | `FinalPlanOrderingTest`(16)+`RouteQualityTest`(6) | — | 未验证 |
| Feed / 贪婪之手 / 愤怒 真实事件账本 | `f249d13` | `CorePowerSupport.cs:100-265` + javap `FeedAction`/`GreedAction` | 5 个测试类 24 条 | — | 未验证 |
| 逐回合可避免卖血 + 战斗已承诺卖血 | `86aee8f` | `Terminal.cs:160-235`、`BattleDamageTracker.cs` | `TurnOutcomeTest`(7)+`SoldHpAnnotationTest`(3)+`BattleDamageTrackerTest`(7) | — | 未验证 |
| 完整候选池（全部 completed + frontier + 药水泳道） | `cd47ceb` | `CombatBeamSolver.cs` finalPool | `FinalCandidatePoolTest`(4) | — | 未验证 |
| 药水资格并入最终选择器（删「先选再换」） | `cd47ceb` | `PotionUsePolicy.IsEligible` | 5 条旧测试改成反向合同 | — | 未验证 |
| 探针 v3 + 四个端到端 golden | `f0d75f2` | — | `RouteQualityGoldenTest`(4) | 见下 | 未验证 |

**paired A/B（173 个 `turn-*.json`，同配置同节点上限）**

```
胜负        172 → 172（唯一那条本来就不生还），无 won→lost
收尾血量    8274 → 8354（+80，均值 +0.46/快照） 更好/持平/更差 = 17/150/6
路线        首手变 17、整条变 37、动作 +14、用药 +29
```

**可重复性**：同一份代码连跑两次（`new-v3.tsv` vs `new-v3b.tsv`）**逐字节相同**
（胜负 0 变化、血量 ±0、路线 0 变化），所以这 173 条上的差异是真实行为差异，不是计时抖动。
⚠️ 脚本默认拒绝跨 FORMAT 比较（v2 与 v3 的列不同），需要时显式加 `--allow-format-mismatch`，
它只会比两边都有的列。

**退化样本已归因**（`A2F33T2`：56 → 46 血，34 → 30 动作，首手 Inflame+ → Combust+）：
把新实现整个 1744 条候选池按 18 键排开，所有胜利路线里最好的 `strategicHpDeficit` 就是 7 ——
旧那条根本不在池子里。卖血罚分（`SoldHpPenalty = -20_000`，与蓝本逐值相同）现在进节点分与剪枝，
旧分支被剪掉了。**不是比较器选错**。

**三条已知偏差（登记，不补）**：第 13 键没有产生方（蓝本也没有 `NoCards`/`Shuffle` 赋值点）；
没有 `StateKey` 第 19 键（18 键全同保持输入顺序）；成长预算默认空（蓝本默认也是空）。

**回退**：本轮 7 个 commit 相互独立。`git revert f0d75f2 ead6257 cd47ceb 86aee8f f249d13 4571418 24c3394`
即回到 `2ae3b4b`（18 键之前的 9 键比较器）。

---

## 十三、2026-11-02 真机第一轮（12:29 → 12:47）

> 这一节的每个结论都来自 `solver-logs/sts1-combatsolver-2026-11-02.log` 与
> `solver-snapshots/turn-*.json`，不是自动化测试发现的。

### 战绩

| 时间 | build | 开局 → 结束 | 失血 | 对拍（match / mismatch / total） |
|---|---|---|---|---|
| 12:33 | `121227` | 80 → 49 | 31 | 35 / 13 / 48（27%） |
| 12:34 | `121227` | 72 → 41 | 31 | 49 / 18 / 67（27%） |
| 12:47 | `124214` | **41 → 55** | **-14（净赚）** | 19 / 16 / 35（46%） |

第三场是腐化之心（`turn-A4F55T4.json`：心脏 384 血、`BeatOfDeath 1`、`Invincible 297`，
玩家 10 血带 `Thorns 3 / Dark Embrace 1 / Brutality 1 / Strength 10 / Rupture 2 / Combust 7 / Barricade -1`）。
**赢了，净赚 14 血**，整场 20 次搜索、平均 1.6 s。

⚠️ 失配率 46% 比前两场更高，但**失血从 31 变成净赚 14**：分母只有 35 次对比，
而一次漂移会污染同一回合剩下的全部对比 —— 率不能直接当质量看。

### 为什么失配高还能赢

失配的处置是蓝本的 `DEPLOY_REPLAN`：清计划 + 用**实机真实状态**从根重算。
所以漂移的代价是「停顿 + 丢掉原规划」，**不是照着错的状态打**。

### 本轮修掉的六个真 bug（全部有真机现场）

| 症状（日志原文） | 根因 | commit |
|---|---|---|
| `cumulativeHpLost: 3 vs 0`、`recoveredPlayerHp: 1 vs 0` —— 任何掉血/回血都失配 | 这两个统计量**真机侧观测不到**（javap `AbstractPlayer` 只有 `damagedThisCombat`，`iadd 1` 是**次数**）。我把它们放进了 `diff` 与续跑戳 | `66c39db`（戳 → v5；`BattleDamageTracker` 真正接线，`captureLive()` 填 `cumulativeHpLost`） |
| `hand[4]: Wound(0) vs Wound(-2)` + `rng.CARD … 1 vs 2` | `MadnessAction` 是**整手牌均匀抽、抽到不能降的重抽**、且同时写 `cost` 与 `costForTurn`；我们过滤候选、只抽一次、无条件降费 | `66c39db` |
| `powers.Shackled: (missing) vs 9` | 黑暗镣铐还挂 `GainStrengthPower`（ID 就是 `"Shackled"`），`atEndOfRound` 还原力量；我们只减力量 | `66c39db`（`darkShackles()` + `CombatFlow` ④.5 还原） |
| `hand.size: 5 vs 9` + `draw.size: 13 vs 9` | `Dark Embrace` / `Feel No Pain` 只写进 powers 表、**没有任何人在消耗时读它** | `ae4ea80`（`Effects.exhaustCard` 是唯一消耗口） |
| `playerPowers.Barricade: 1 vs -1`（永久失配） | 原版存的是 `magicNumber = -1`；我们写 1。且 `CombatFlow` 用 `power(...) <= 0` 判定，改成 -1 会让壁垒**静默失效** | `ae4ea80`（存 -1 + 按 `containsKey` 判定） |
| `monsters[0].hp: 387 vs 441` + `Invincible: 0 vs 54` | 每回合伤害上限只挂在 `hit()`，多段/AoE/逐张结算走 `hitAndReturnDealt` **绕过上限** | `a2d82b3`（上限移进唯一扣血口；顺带解释了之前`Sword Boomerang+ 260 vs 233`） |

### 查了但**不是** bug

`Berserk`：我一度判定「只有写入没有读取方」并加了回合初 +1 能量，测试立刻多算 1 点。
`PowerHooks` 早就挂在 `START_OF_TURN_POST_DRAW`（还带注释说明不能挂 `START_OF_TURN`，
会被 `energy = energyPerTurn` 吃掉）。改动已撤销 —— **`energy: 3 vs 2` 仍未解释**，别再从 Berserk 查。

### 剩余失配清单（下一轮的活）

16 次失配的构成：

- `block` 差 1 —— **8 次**，方向不固定（多数少 1，`Bash+` 那次多 1）。疑似某张牌的格挡在
  脆弱/敏捷下的取整，或某个遗物的格挡没算。**优先**：加逐张牌格挡增量日志。
- `Fiend Fire` / `Warcry+` / `Power Through+` 手牌·弃牌漂移 —— 4 次，全部带 `forced after 90f`，
  疑与「上一手还没结算完就对比」有关。
- `hp -1` + `Next Turn Block +3` —— 2 次（我们多掉 1 血 → 自成形黏土多给 3 格挡）。
- `Madness` 费用（`draw[4]: Madness+(1) vs (0)`）—— 1 次。根因是 `getRandomCard` 按**手牌下标**取牌，
  而我们把「手牌」当无序多重集比对；要修得先让手牌顺序与实机一致。
- `Pen Nib: (missing) vs 1` —— 1 次。第 10 击实机会挂 `PenNibPower 1`，我们只改遗物计数。

### 结构性问题（不要用捷径绕）

`SEARCH_REUSED = 0`，全部是 `SEARCH_REUSE_MISS`，首差都是 1~3 点：

```
block: expected=0 actual=3
hp:    expected=33 actual=32
hp:    expected=32 actual=31
```

只要这个精度上不去，跨回合续跑永不命中 → 每个回合边界都重算 → 用户看到的「路线每回合变」。
**用户 12:39 明确选 B：不加「失配后重建影子」的鲁棒性通道，只治本。**

**回退**：本轮 5 个 commit 相互独立，
`git revert a2d82b3 ae4ea80 66c39db 0f2e581 f0d75f2` 可回到 `ead6257`（真机修镜像之前的状态）。

---

## 十四、2026-11-02 镜像精度批（13:00 → 21:48）

> 口径：**自动化** = `./gradlew test --offline`；**离线回放** = `-Pexperiments -Dcase=<dump>`；
> **真机** = 进游戏实跑。本轮真机 **未复测**。

### 14.1 提交与状态

| commit | 内容 |
|---|---|
| `e382207` | 致死出牌仍执行 BeatOfDeath（`onAfterUseCard` 无存活过滤） |
| `60ec7df` | 重刃力量倍率入口补 PenNib/Boot（与普通攻击共用尾部链） |
| `f92b84e` | v5 快照全字段 + `Extra.restore` + 严格/legacy 两档 parser + mismatch 回放 + 手牌保序 diff |
| `20eb7c0` | 稳定门纳入 `currentAction`；90 帧不再强判；choices 在清空 pending 前落盘 |
| `a9f78e9` | 六个模型缺口（Brutality / Rupture / 通用升级 / Madness 重抽 / Fiend Fire 批消耗 / 10 张上限）+ 颈环清零 + 逆序弃牌 |
| `dc316e8` | 玩家侧 Vulnerable/Weakened/Frail 轮末递减 + justApplied |
| `8ea4bd6` | 严格相邻回合 Continuation 配对探针 |
| `b375a2f` | 孢子云：真菌兽战斗中死亡给玩家 +2 易伤（战斗要结束时不触发） |
| `966db98` | 选择坐标按来源牌堆解析（HAND/DISCARD/DRAW/EXHAUST），未知来源拒绝而非猜手牌 |
| `0d8126e` | baseline 捕获改用 `settled()`（含卡牌飞行）；账本 diff 收口；Anger 复制真捕获 |
| `6a7ed38` | `ContinuationStamp` 不再比 `pendingMove` 载荷（真机侧重建要掷 aiRng），版本升 v6 |
| `8df0d70` | 宽度对照探针（回答「为什么算得这么快」） |

### 14.2 每个根因的证据状态

| 根因 | 原版证据 | 自动化 | 离线回放 | 真机 | 状态 |
|---|---|---|---|---|---|
| BeatOfDeath 致死仍触发 | `UseCardAction.update` 尾部遍历 powers 无 hp 过滤 | ✅ `HooksTest` + `BeatOfDeath*` | 旧 dump 缺字段，无 oracle | 未验证 | 已修 |
| 重刃漏 PenNib | `HeavyBlade.use` 后仍走 `calculateCardDamage` | ✅ `DamageCalcTest`(3) | 同上 | 未验证 | 已修 |
| 稳定门缺 currentAction | `getNextAction()` 先 remove 再执行 | ✅ `ShadowTrackerSettleTest`(4) | — | 未验证 | 已修 |
| 90 帧强判 | — | ✅ 同上（500 帧不出结论） | — | 未验证 | 已删 |
| Brutality 只挂不生效 | `BrutalityPower.atStartOfTurnPostDraw` | ✅ `BrutalityTest`(4) | 同上 | 未验证 | 已修 |
| Rupture 只在 loseHp | `RupturePower.wasHPLost` | ✅ `BrutalityTest` + 既有 | 同上 | 未验证 | 已修 |
| 升级保留旧费用/伤害 | `CardExporter` 的 makeCopy+upgrade 数据 | ✅ `CardPlayerTest` + 表驱动 | 同上 | 未验证 | 已修 |
| Madness 自造上限 | `findAndModifyCard` 递归 | ✅ 既有 Madness 合同 | 同上 | 未验证 | 已修 |
| 整手牌正序弃牌 | `DiscardAction` + `getTopCard()` | ✅ `CombatFlowTest` | 同上 | 未验证 | 已修 |
| Fiend Fire 同步抽牌污染池 | `ExhaustAction` 批 + `addToBot` 抽牌 | ✅ 既有 + 新 | 同上 | 未验证 | 已修 |
| 手牌上限 10 未执行 | `MakeTempCardInHandAction.update` | ✅ `CardPlayerTest` | 同上 | 未验证 | 已修 |
| 颈环跨回合不清零 | `VelvetChoker.atTurnStart` | ✅ `RelicHooksTest` | — | 未验证 | 已修 |
| 玩家减益不递减 | `VulnerablePower.atEndOfRound` | ✅ `PlayerDebuffRoundTickTest`(5) | 同上 | 未验证 | 已修 |
| 孢子云死亡联动 | `SporeCloudPower.onDeath` | ✅ `SporeCloudTest`(3) | 同上 | 未验证 | 已修 |
| 选择坐标只查手牌 | True Grit+ / Headbutt / Exhume 的来源牌堆 | ✅ `ChoicePileCoordinateTest`(7) | 同上 | 未验证 | 已修 |
| 快照缺 v5 字段 | — | ✅ `SnapshotReplayContractTest`(3) | — | — | 已修 |
| 旧 dump 不能当 oracle | — | ✅ `MismatchReplayTest`（221 全 incomplete） | — | — | 已登记 |

### 14.2.5 路线质量 A/B（188 份 turn-*.json，同配置）

基线 = `a2d82b3`（worktree + 独立 build.dir），对照 = `8ea4bd6`。
原始数据：`runs/2026-11-02-mirror-fidelity/ab-base.tsv` / `ab-head.tsv`。

```
胜负        175 仍胜 / 7 负→胜 / 0 胜→负 / 6 仍负
收尾血量    总计 -1（188 份，均值 -0.01） 更好/持平/更差 = 24/140/24
路线        首手变 36、整条变 71、动作 +55、用药 -2
```

**关键**：7 条「原来判必败」变成「现在能赢」，且**没有一条从赢变输**。
最可能的原因就是本轮修掉的「玩家减益从不递减」—— 以前易伤/虚弱永远不减，
搜索把后续每一击都按 1.5 倍估伤，直接判死。24 条更差是同一批数值修正的副作用
（估值变了，路线自然不同），总血量几乎不动，暂不逐条归因。

### 14.3 旧证据的结论（重要的负结果）

- `runs/2026-11-02-mirror-fidelity/mismatch-1102/` 的 **221 份**真机 dump
  **全部** `incomplete_schema`（缺 `player.turn` / `meta.boss` / `pendingMove` 等）。
  它们**不能**用来证明任何修复 —— 必须用新 build 重新采集。
- `ContinuationPairProbeTest` 对 188 份旧 `turn-*.json` 报
  `total=0 incomplete_schema=188`：旧 151/188 对同样不能算命中率。
  这正面确认了「`SEARCH_REUSED=0/30` 是快照缺字段 + 稳定门早拍共同造成」。
- 因此本轮**不宣称**失配率下降或续跑命中提升；只宣称「已证实的模型缺口被按原版修掉」。

### 14.4 下一轮第一件事

1. 重启游戏（新 jar `1102-142125` 已装），打一场多回合战斗 + 一场带 Brutality/易伤的战斗。
2. 用新 `mismatch/*.json` 跑：
   ```bash
   ./gradlew test --offline -Pexperiments --tests "*MismatchReplayTest" -Dcase=<新 dump 目录>
   ```
   只有 `replay=*` 的样本能出结论；`incomplete_schema` 必须为 0。
3. 用新 `turn-*.json` 跑：
   ```bash
   ./gradlew test --offline -Pexperiments --tests "*ContinuationPairProbeTest" \
       -DbatchPair=<冻结目录> -Dout=<pair.tsv>
   ```
   第一次得到**真实**命中率。
4. 仍要做（本轮未做）：`SpireShield` 死亡清理（`Surrounded`/`BackAttack` 我们没有建模，
   所以这条目前无对应物，需先确认是否要建）、跨卡 flush 的基线锚点（Task 5D）。
5. **查「不爱开能力」**：`BeamWidthProbeTest` 已排除「宽度不够」（135/270/540 结果全同），
   下一步查 `FinalPlanOrdering` 的键序与 `PowerValuation` 的能力估值。这是目前最可能
   影响「牌打得臭」的一项。

### 14.5 回退

本轮 commit 相互独立，`git revert 8df0d70 6a7ed38 0d8126e e0de94f 966db98 b375a2f c4e54b6 8ea4bd6 dc316e8 a9f78e9 20eb7c0 f92b84e 60ec7df e382207`
可回到 `4172fd1`（即上一轮的 `a2d82b3` + 两个 docs）。

### 14.6 第二批：真机验证与二次修复（20:38–21:48）

**实测数据（build `142939`，一次连续战斗）**

```
对拍   138 次：111 MATCH / 27 MISMATCH（19.6%，上一轮 46%）
未决   22 次 DIFF WAIT（新口径：不出结论、不进分母）
后果   DEPLOY_REPLAN 25 次（93% 的失配都丢掉整条规划）
续跑   SEARCH_ 41 条全 MISS，SEARCH_REUSED = 0
```

失配归因：`hand.size` 14 次、`angerCopiesGenerated` 11 次、`rng.CARD` 12 次（连带）、
`feedGrowth` 2 次。**只有这四类，没有第五类** —— 说明第一批的模型修复没有引入新问题。

**三处修复**

1. `0d8126e` baseline 捕获门。`tick()` 只用 `actionsDrained()` 重置基线，不含卡牌飞行
   （`ShowCardAndAddToHand` / `SoulGroup`）。飞行中的牌不属于任何牌堆，快照就少一张；
   下一次出牌拿这份缺一张的基线当起点，而 live 已落位 → `hand.size` 差 1 + `rng.CARD` 差一步。
   样本 `004-True_Grit+.json`：`before` 4 张 → sim 2 张、live 3 张（多 `Headbutt`）。
   现在 baseline 与出结论用**同一个** `settled()`。
2. `0d8126e` 账本口径。`feedGrowth` / `handOfGreedGrowth` 原版没有计数器，
   `capture()` 只能给 0 —— 留在 diff 里就是「打一张 Feed 必失配」。
   改为不进 diff（仍进排序与指纹）。`angerCopiesGenerated` 相反：能由
   「战斗内 Anger 张数 − 牌组 Anger 张数」精确还原，所以**实现了 capture**，继续逐一比较。
3. `6a7ed38` stamp 载荷。`capture()` 从不填 `pendingMove`（真机只有 `nextMove` 一个字节），
   而 `ContinuationStamp` 比全载荷 → 一侧恒 null、另一侧有值 → **戳永远不等**。
   这就是 `SEARCH_REUSED = 0` 的根因（日志原话：
   `pendingMove.id: expected=1 actual=(missing)`）。现在比 `pendingMoveId + damageBase + intentDmg`，
   戳版本升 `sts1-cont-v6`。

**「为什么算得这么快」的实测答案**

`BeamWidthProbeTest` 对 `turn-A2F18T2`（手牌含 `Corruption` + `Dark Embrace+`）跑宽度对照：

| 宽度 | 节点 | 耗时 | 第一手 | 结束血量 | 分数 |
|---|---|---|---|---|---|
| 135 | 24,805 | 1,416 ms | Dark Embrace+ | 65 | 10001866000 |
| 270 | 31,043 | 1,503 ms | Dark Embrace+ | 65 | 10001866000 |
| 540 | 58,536 | 2,228 ms | Dark Embrace+ | 65 | 10001866000 |

**三档完全一致** → 该局面在 135 就收敛了，「快」不等于「浅」。
快的三个真实原因：①全局上限 500k 节点/300s，实测只用 0–5%；
②novelty 阶段有蓝本硬限 `NOVELTY_MAX_NODES = 2_500` / 5s；
③portfolio 同时跑 5 个宽度，**结果一样就采纳最窄的**（日志 `adopted=135`）。

所以「不爱开能力」若成立，根因在**打分/键序**，不在搜索宽度。
该局面第一手就是 `Dark Embrace+`；没开的是 `Corruption`（3 费，4 能量下开完只剩 1 费）。
