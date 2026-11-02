# STS1 战斗求解器 · 阶段性收工交接（2026-11-02）

> **新对话用法**：把本文件整份交给下一位 agent；先读本文件，再读 §八列出的阶段报告与蓝本对照表。
> 本文只保留**当前事实、验证边界和下一步**。2026-11-02 23:00 前的长版历史可用
> `git show 0683323:docs/handoff-prompt.md` 查看；旧计划中的“当前状态”不得覆盖本文。

## 一、项目、蓝本与游戏位置

| 项目 | 本地位置 | 仓库 / 版本 |
|---|---|---|
| **主项目** | `C:\Users\22757\Desktop\sts1-combatsolver` | `https://github.com/youqing-tju/sts1-combatsolver`；分支 **`master`**（不是 main） |
| **蓝本 STS2 CombatSolver** | `C:\Users\22757\Desktop\sts1-combatsolver\reference\sts2-combatsolver` | `https://github.com/Torch1230/CombatSolver`；独立 clone，分支 `main`，收工时 `5c773caaec742bc55588bc9ee21c361326a732f0` |
| **游戏根目录** | `C:\Users\22757\steam\steamapps\common\SlayTheSpire` | 正版 STS1 + ModTheSpire |
| **启动器** | `C:\Users\22757\steam\steamapps\common\SlayTheSpire\MTS-launch.cmd` | 用此入口启动；安装新 jar 后必须完全重启 |
| **游戏 jar** | 主项目 `lib\desktop-1.0.jar`；源文件在游戏根目录 | javap 核实原版语义；不入库 |
| **ModTheSpire jar** | 主项目 `lib\ModTheSpire.jar`；源文件 `C:\Users\22757\steam\steamapps\workshop\content\646570\1605060445\ModTheSpire.jar` | 用 `tools/sync-libs.ps1` 同步；不入库 |
| **真机日志** | `C:\Users\22757\steam\steamapps\common\SlayTheSpire\solver-logs` | 按日期与 build 分段分析 |
| **真机快照** | `C:\Users\22757\steam\steamapps\common\SlayTheSpire\solver-snapshots` | `turn-*.json` 与 `mismatch/*.json` |
| **本批冻结证据** | 主项目 `runs\2026-11-02-mirror-fidelity` | 约 4.9 MB；被 `.gitignore` 排除，只在本机，不在 GitHub |
| **默认构建目录** | `C:\Users\22757\.sts1-combatsolver\build` | 不是工作区 `build/`；并行 agent 必须各自配置独立 `build.dir` |

主项目是 Java 8 ModTheSpire mod，只做 Ironclad 战斗层（Act 1–4）。训练、地图、选牌、商店、事件、篝火后置。
模块固定为 `capture / sim / mirror / search / eval / exec / ui / verify / diag / bootstrap`。

## 二、收工基线

- **功能代码基线**：`8df0d70`；其后 `0683323` 是阶段报告与 handoff 文档提交。最新提交以 `git log -1 --oneline` 为准。
- **测试**：2026-11-02 收工时重新执行 `./gradlew test --offline`，**822 条全绿，0 failure / 0 error，18 skipped**。
- **已安装 jar**：`build 1102-210032`。
- **真机加载边界**：20:38 的游戏进程加载的是旧 `build 142939`；**210032 尚未真机验证**。
- **GitHub**：默认分支为 `master`。收工时推送本地提交；接班仍先运行 `git status --short --branch` 与 `git log -3 --oneline` 核对。
- 蓝本目录是独立第三方仓库，不属于主项目提交内容；不要向蓝本仓库推送，也不要把 `reference/` 加进主仓库。

## 三、本阶段已经完成什么

详细交付报告：`docs/reports/2026-11-02-mirror-fidelity-report.md`。

### 3.1 对拍与证据设施

- 稳定门纳入 `GameActionManager.currentAction`，删除“90 帧到期强判”。未决只等待/诊断，不制造成真失配。
- baseline 与出结论统一走 `settled()`，覆盖卡牌飞行；不再用半张状态污染下一次对拍。
- 完整版本化快照、严格/legacy parser、`MismatchReplayTest`、`ContinuationPairProbeTest`、`SnapshotReplayContractTest` 已落地。
- 手牌顺序纳入真实差异；选择坐标按 HAND/DISCARD/DRAW/EXHAUST 来源牌堆解析。
- `ContinuationStamp` 已升级 **v6**：不比较真机侧无法无副作用重建的 `pendingMove` 载荷，使用共同可观测量。

### 3.2 镜像修复

本批按 javap / 原版数据修复：致死仍触发 BeatOfDeath、Heavy Blade 力量倍率入口的 PenNib/Boot、Brutality、Rupture、通用升级、Madness 重抽、Fiend Fire 批消耗、原生 10 张手牌上限、颈环回合清零、整手牌逆序弃牌、玩家减益轮末递减、孢子云死亡联动等。

**自动化通过不等于真机闭环。** 上述项目多数仍需用 210032 或之后 build 逐项复测。

### 3.3 路线质量与搜索结论

- 蓝本 `FinalPlanOrdering` 是 **18 键**，不是旧文档曾写的 17 键；`PotionStrategicCost` 不是独立第 19 键。
- 188 份旧快照的同配置 A/B（基线 `a2d82b3`）结果：**7 条负→胜、0 条胜→负**；收尾血量总计 -1，不能据此宣称全面降低战损。
- `BeamWidthProbeTest` 对同一局面跑 135/270/540：首手、路线、结束血量与分数完全相同。该局面在 135 已收敛，**“不爱开能力”不要再靠加宽 beam 治，应查估值与键序**。

## 四、证据边界：不要误读旧数字

1. **旧 build 142939 的真机数据**：138 次对拍中 111 MATCH / 27 MISMATCH（19.6%），22 次 DIFF WAIT，25 次 DEPLOY_REPLAN，`SEARCH_REUSED=0`。
   这些数字属于旧 build，不是 210032 修复后的结果。
2. 当轮失配已归因为 baseline 飞行中捕获、账本字段和 stamp 载荷，并分别在 `0d8126e` / `6a7ed38` 修复；**修复效果尚待真机确认**。
3. 冻结的 221 份 mismatch dump 与 188 份 turn 快照全部缺新 schema 必需字段：
   `MismatchReplayTest` 为 `replay=0 incomplete_schema=221`，`ContinuationPairProbeTest` 为 `total=0 incomplete_schema=188`。
   **旧样本不能当 oracle，也不能用于计算续跑命中率。** 不得补默认值、删字段或放宽比较来制造通过。
4. 真机曾打赢腐化之心（41→55 血）只证明当时能赢，不证明所有镜像与路线质量已闭环。

## 五、下一轮顺序（接班从这里开始）

### 1. 先验证最新 build，不先开新机制

```bash
./gradlew test --offline
./gradlew jar --offline
powershell -File tools/install.ps1
```

完全退出游戏，再用 `MTS-launch.cmd` 启动。日志必须确认 **`build 1102-210032` 或更新**，打一场至少 5 回合的战斗。
记录真实 MATCH/MISMATCH/WAIT、DEPLOY_REPLAN、SEARCH_REUSED 与 MISS 首差；不要把 WAIT 算入失配率。

### 2. 用新样本跑两条探针

```bash
./gradlew test --offline -Pexperiments --tests "*MismatchReplayTest" -Dcase=<新dump目录>
./gradlew test --offline -Pexperiments --tests "*ContinuationPairProbeTest" -DbatchPair=<新快照目录> -Dout=<pair.tsv>
```

验收要求：

- `incomplete_schema` 必须为 0；否则先修采集/schema，不下命中率结论。
- 续跑命中只数带 `validation=exact_state_text` 的 `SEARCH_REUSED`；按 build/run/combat/from_turn 去重。
- 同时报告 HIT、state_mismatch、deployment_drift、continuation_missing、manual_divergence。
- 若仍为 0，按首个精确差异修影子；禁止重同步、冻结、删字段或放宽判等。

### 3. 查“不爱开能力”的真实打分原因

沿用 `BeamWidthProbeTest` 与完整候选池，逐项对照蓝本估值、18 键顺序及平手处理。
先构造能区分候选的样本和 RED，再改实现；不要复活已被推翻的“搜索宽度不足 / open 表过早耗尽 / 强制先开能力牌”旧诊断。

### 4. 已登记但未闭环

- `SpireShield` 死亡清理：`Surrounded` / `BackAttack` 当前未建模，需先按蓝本与 javap 确定对应物。
- 跨卡 flush 基线锚点（原 Task 5D）：需要更早的原生钩子点，不能用混合态对拍。
- `AdoptRoute`、`RouteFrozen`、`play_depth`、`PotionRewardOutlook`、琥珀限制、`SolverTheftPolicy.LetEscape` UI 开关仍未做；蓝本没有等价物的不许自造。

## 六、硬规则（违反 = 白干）

1. **禁止自创，一切以蓝本为标准复刻；蓝本没有 = 不做。** 先找蓝本实现，理解后只翻译到 STS1 API；不得改变机制、默认值、约束强弱、状态顺序或失败语义。
2. 既定目标内自主调查、修改、测试、构建、安装、真机验证和本地 commit；事后短报告证据、风险、回退。只有产品语义不兼容取舍、不可逆/外部操作或必须违反硬规则时才问。
3. **项目不嫌慢，只嫌牌打得臭。** 不得为耗时砍预算、宽度、深度或关闭机制；评估看战损、胜率、收益与路线合理性。
4. 蓝本复刻以 `docs/research/blueprint-parity.md` 为对照，一次一项并记录源码证据。事实、假设、候选修复、已验证结论分开写。
5. 游戏语义必须 javap 核实；卡牌文本查 jar 内 localization，升级数值查 `UPGRADE_DESCRIPTION` / `upgrade()`，不凭记忆。
6. 未知语义显式拒绝（`ActionRejected`），不许静默算错。
7. 搜索在后台纯数据上进行；真机对象不进后台，执行只在主线程；渲染路径不得每帧 `SimState.capture()`。
8. 蓝本默认是精确 Continuation：命中续用，miss 归因重算。禁止默认冻结、失配照打、单步剔除或有界 AUTO 等本地例外。
9. 不新增“无生还路线就交还控制”的闸；停手只保留蓝本已有的 DeathTurn 与 LiveRisk 口径。
10. 不引入“失配后用实机重建影子”的通道；只逐条消灭根因。
11. `.ps1` 一律 ASCII-only；Gradle 9.8.2，`options.release=8`，class major=52。
12. 不使用本机遗留 `mods/SolverMod.jar`、已删的 `sts-solver` / `sts-agent`。
13. **不自行 push**；只有用户明确要求时才推送。不要删除 `.workbuddy/`、`runs/` 或真机证据。

## 七、关键文件

| 文件 | 用途 |
|---|---|
| `docs/reports/2026-11-02-mirror-fidelity-report.md` | 本阶段完整交付报告、提交清单、A/B 与真机数据 |
| `docs/research/blueprint-parity.md` | 蓝本逐项复刻对照与来源审计 |
| `docs/research/mechanics-reference.md` | 原版机制与 javap 证据 |
| `记录.txt` | 项目工程经验与踩坑记录 |
| `src/main/java/io/github/youqing/sts1solver/search/BeamSolver.java` | 搜索、候选池、最终选择 |
| `src/main/java/io/github/youqing/sts1solver/search/Score.java` | 估值；能力牌时机调查入口之一 |
| `src/main/java/io/github/youqing/sts1solver/search/ContinuationStamp.java` | 当前 v6 精确续跑戳 |
| `src/main/java/io/github/youqing/sts1solver/sim/SimState.java` | 影子状态、capture、diff、JSON |
| `src/main/java/io/github/youqing/sts1solver/sim/CombatFlow.java` | 回合流程与原生时序镜像 |
| `src/main/java/io/github/youqing/sts1solver/verify/ShadowTracker.java` | 稳定对拍与失配落盘 |
| `src/main/java/io/github/youqing/sts1solver/verify/AutoPilot.java` | 主线程执行、重算与续跑 |
| `src/test/java/io/github/youqing/sts1solver/search/BeamWidthProbeTest.java` | 固定局面宽度对照 |
| `src/test/java/io/github/youqing/sts1solver/search/MismatchReplayTest.java` | 新 mismatch dump 严格回放 |
| `src/test/java/io/github/youqing/sts1solver/search/ContinuationPairProbeTest.java` | 相邻回合精确续跑配对 |

## 八、回退与仓库边界

- 本批代码回退见阶段报告 §七；不要把文档提交混入功能回退。
- `lib/*.jar`、`reference/`、`runs/`、日志、快照、本机 `local.properties` 均不入主仓库。
- 新卡接入先跑 `python tools/audit_registry.py`。
- 收工文档提交后应保持工作区干净；若接班看到改动，先辨认来源，不要直接 reset 或删除。
