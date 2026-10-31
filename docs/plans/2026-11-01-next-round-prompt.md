# STS1 CombatSolver 下一轮施工提示词（2026-11-01）

你接手 `C:\Users\22757\Desktop\sts1-combatsolver`，分支 `master`。你是本轮执行负责人，不是只给建议的评审员。目标是直接调查、修改、测试、构建、安装、真机验证并逐项本地 commit，最后交付证据，不要写完方案就停下等批准。

## 一、开工与基线

1. 先读：
   - `docs/handoff-prompt.md`
   - `docs/plans/2026-10-31-ui-parity.md`，尤其 Task 4 与实施结果
   - `docs/plans/2026-10-31-ui-parity-design.md`
   - `docs/research/blueprint-parity.md`
   - 根目录 `记录.txt`
2. 执行 `git status --short --branch`、`git log -5 --oneline`，保留他人改动，禁止 `reset --hard` 和覆盖未提交工作。
3. 已知起点：最近文档基线 `784bde1`，此前实测 547 tests / 0 failures / 0 errors；当前 HEAD 与测试实数以你现场结果为准。UI 十条真机验收和回合末候选修复真机验证目前都没有完成。

## 二、授权与底线

- 既定目标内的代码、测试、诊断、重构、构建、安装、真机验证和本地 commit 全部自主完成；修 bug 可以边查边改。不要为计划外文件、测试、探针、点击穿透或独立小修逐项请示。
- 只有产品语义存在多种不兼容取舍、不可逆/外部操作、或必须违反硬规则时才问。事后用短报告交代改动、证据、风险、回退；请示不算进度。
- 不 push，不擅自删除用户文件，不用本机遗留 SolverMod/sts-agent 资产。
- 不许为了耗时砍搜索预算、宽度、深度或关闭机制；评价改动看战损、胜率、路线与能力牌时机，不看“算得更快”。
- 游戏语义必须 javap / 原版 localization 核实；未知语义显式拒绝。真机对象不进后台线程，渲染不 capture。
- 严格区分：已证实事实、当前假设、候选修复、自动化通过、真机闭环。没有真机证据不得写“根因已修复”。

## 三、本轮任务，按 A → B → C → D 持续推进

### A. 先完成 UI 与回合末真机闭环

1. 运行：
   ```bash
   ./gradlew test --offline
   ./gradlew jar --offline
   ```
   然后执行 `powershell -File tools/install.ps1`，按项目现有 ModTheSpire 流程启动真机。日志在游戏目录 `solver-logs/sts1-combatsolver-YYYY-MM-DD.log`。
2. 逐项完成 UI 计划 Task 4 Step 2 的十条真机验收，不抽样：只读区、settled 刷新、AUTO off/on 有界停止、目标保留、完整敌方阶段、取消、跨战斗清理、无 END_TURN 尾行、两种分辨率布局与点击穿透、bounded 日志。
3. 有界停止语义不可改：点击 Tn 后必须执行完 Tn 的 END_TURN 和敌方阶段，在 Tn+1 玩家行动前停；每回合按真局面重算，不冻结跨回合预览。
4. 对 `6ea8014` 的 `EndTurnButton.disable(true)` 做手点 vs AUTO 的可复现 A/B：分别验证 Burn、Plated Armor、Metallicize、Orichalcum，以及敌方阶段不跳过、不重复。记录 turn、HP、block、关键 flags 和对应日志。
5. 先修正 `EndTurnProbe` / `AutoPilot` 注释及 `verdict()` 中“队列非空 = 永远不会启动”“根因已确定”等过强措辞。探针保留到验证完成，闭环后删除或明确关闭，并跑回归。
6. 任一真机项失败：直接复现、定位、TDD 修复、重装并复测。点击穿透可在评估副作用与回退后直接做最小输入修复；不得删测试、缩小验收场景或停下来等确认。
7. 若真机被具体环境问题阻塞，记录实际错误与已尝试步骤，继续推进可独立完成的 B/C/D；不得把“未运行”写成“通过”。

### B. 独立完成 P4/P5 小修，全部 TDD

- `Coverage.java`：消除 `Lantern`、`NeowsBlessing`、`Pear` 的未镜像误报，补测试证明真实未镜像遗物仍会告警。
- `SimState.capture()`：原版 `PotionSlot` 空槽映射为 `null`，不仅修 UI；补 capture/search 输入侧测试，确保不再把空槽当药水。
- `CombatFlow.java`：先用 javap 核实 Burn/Burn+、Decay/Decay+ 数值与 upgraded 表示，再修回合末状态牌伤害；测试未升级不回归、升级值正确，不只把一组写死常量换成另一组。
- 每项独立 commit；跑相关测试和全量测试，能真机/回放验证的必须留证。

### C. 修 P3 对拍队列污染，再评价搜索

- 复现 `DIFF ... skipped (previous ... still pending)`，查清比对请求、执行动作和结果归属为何错位。
- 用 Warcry、Sever Soul、Secret Technique 等已有案例做回归：连续动作时不能因上一条 pending 丢掉或错配下一条；每个 MISMATCH 必须归属于产生它的动作。
- 不得通过关闭对拍、静默丢弃失配或放宽断言来“清零”。验收数据可信后再进入 D。

### D. 把 P1/P2 作为同一搜索问题调查并实施修复

- 先基于日志与源码写简短根因设计和验收判据，然后直接实施，不等用户批准设计。
- P1 已见：475/475 规划带 `tree exhausted`，节点预算 500000 却常用 0%～10%，存在 `open_exhausted` 与很浅回合层。重点查剪枝、去重/转置表、每动作展开、回合层 horizon；对照蓝本 frontier/continuation，不靠继续加预算掩盖。
- P2 已见：Feel No Pain 在手 14 次仅打出 2 次，且常被排到 T4～T6 后每回合重算。验证并消除“后排能力牌结构性永远轮不到”的问题，不为单卡硬编码特例。
- 用同种子/同战斗的 paired A/B 比较修复前后：open 表耗尽原因、有效搜索形状、战损/胜率、Feel No Pain 实际打出时机。节点数增加不是目标，耗时下降也不是功劳；必须证明牌打得更好或镜像更真实。

## 四、工程与提交要求

- 新 bug 先写能失败的测试并看到 RED，再实现到 GREEN；诊断性探针可先用，但不能拿探针代替回归测试。
- 每个逻辑项独立 commit，保证可 revert。每轮评审修复后运行：
  ```bash
  ./gradlew test --offline
  ./gradlew jar --offline
  git diff --check
  ```
- 更新 `docs/handoff-prompt.md`、UI 实施计划及必要的 `blueprint-parity.md`：填写真实测试数、真机逐项结果、commit 和未解决风险。不要新建 CHANGELOG.md，本仓库历史上没有它。
- 蓝本三个 StrategyPanel 仍是控制器未复刻；只读区不算 parity。蓝本无 ApplyRoute；本功能仍标为项目自定义有界 AUTO。
- 做 spec review 与 quality review；发现问题直接修并复测。保留可定位的日志/快照证据，不伪造真机结果。

## 五、最终汇报格式

用一张表列出：`任务 | commit | 自动化结果 | 真机/回放结果 | 证据位置 | 状态`。
随后仅列：修改的关键文件、仍未闭环的问题、回退方式。明确写出 UI 10 项通过数、回合末各项通过数、P3 是否还出现 pending skip、P1/P2 的 A/B 结果。不要只报告“测试全绿”。

现在直接开始：读上述文件、核对 Git、跑基线、构建安装并做真机验收；不要先返回一份等待批准的方案。
