# STS1 战斗求解器 · 交接提示词（2026-10-31 深夜版）

> **用法**：整份贴进新对话，第一条消息就给全上下文。
> **项目根**：`C:\Users\22757\Desktop\sts1-combatsolver`　**分支**：`master`（不是 main）
> **当前状态**：测试 **547 条全绿**（0 fail / 0 err），代码 HEAD = `6ea8014`
> （`fix(exec): AUTO 结束回合改走 EndTurnButton.disable(true)`；后续文档提交见 Git 历史），未 push。
> UI 十条真机验收与回合末候选修复真机验证均未完成，不得视为验收闭环。

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

### P1 · 搜索早早穷尽（最严重，直接影响「牌打得臭」）

- 真机日志里 **475 次规划，475 次都带 `tree exhausted`**；节点预算 50 万，实际只用 **0%~10%**。
- 典型：`PLAN ready: 2 steps, depth 1 turns, 179/500000 nodes (0%, tree exhausted)`。
- 190 次收尾原因是 `open_exhausted`（**开放表被清空**），常伴随 `depth 1 turns`。
- **加预算/加宽度无效** —— 瓶颈是 open 表过早耗尽，要查：剪枝、去重/转置表、每动作展开数、回合层 horizon。
- 用户的原话：「不管打什么怪都是 1s 内计算完毕，显然搜索做得很烂」。这是**当前第一优先级**。

### P2 · 该开的能力牌永远不开

无惧疼痛（`Feel No Pain`）在手牌出现 **14 次，真正打出仅 2 次**。
多次计划把它排在 `T4~T6`（`... | END_TURN | ... | Feel No Pain |`），而计划每回合重算 →
**排在后排的牌结构性永远轮不到**。怀疑与 P1 的浅搜索（只看 1 回合）叠加。

### P3 · 对拍噪声，污染验收数据

一场 122 次比对里 8 次失配，集中在 `Warcry`（手牌/抽牌顺序）、`Sever Soul`（消耗堆顺序）、
`Secret Technique`（消耗数 + CARD rng），并出现 `DIFF xxx skipped (previous Warcry still pending)`
—— 比对队列会滞后，后续失配是被污染的。**不修这条，P1/P2 的验收数据不可信。**

### P4 · 数据干净度

- `未镜像遗物 3: Lantern, NeowsBlessing, Pear` 是**误报**：都是开局/局外一次性，漏在
  `mirror/Coverage.java:43` 的 `INERT_RELICS` 白名单外。
- `SimState.capture()` 把空药水槽存成 `"Potion Slot"`（原版空槽是 `PotionSlot` 实例，ID 字面就是这个），
  应映射成 `null`，否则搜索以为槽里有一瓶药。**面板显示侧已修（`42a0791`），capture 侧未动。**

### P5 · 影子数值缺口

`Burn+` 在影子按 2 点算，实机 4（`Burn.upgrade → upgradeMagicNumber(2)`）；`Decay+` 同理 2→3。
位置 `sim/CombatFlow.java:255`，写死常量、未读 `upgraded`。

> P1/P2 先写根因设计与验收判据，然后按新授权直接实施，不等逐条批准；P4/P5 属小范围镜像 bug，直接 TDD 修复并真机/回放验证。任何假设都必须与已证实事实分开写。

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
