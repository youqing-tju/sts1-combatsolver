# STS1 CombatSolver 下一轮施工提示词（完整路线质量，2026-11-01）

你接手 `C:\Users\22757\Desktop\sts1-combatsolver`，分支 `master`。你是执行负责人：在既定目标内直接调查、修改、测试、构建、安装、真机验证并逐项本地 commit，最后交付证据，不停在方案。

## 一、最高硬规则

**禁止自创，一切以蓝本为标准复刻。蓝本没有 = 不做。**

- 不允许用“用户此前要求 / 最小等价 / 本地扩展 / 项目自定义 / UI 适配”豁免。
- STS1/STS2 API 差异只改变翻译方式，不改变机制、默认值、约束强弱、状态顺序或失败语义。
- 每项行为动工前定位蓝本 `file:line`；STS1 原版数值/生命周期另给 `javap` 证据。
- 本项目不嫌慢，只嫌牌打得臭；不得为耗时砍预算、宽度、深度或机制。

## 二、开工必读与当前基线

按顺序读：

1. `docs/handoff-prompt.md`
2. `docs/plans/2026-11-01-final-plan-ordering-design.md`（用户已批准）
3. `docs/plans/2026-11-01-final-plan-ordering.md`（本轮唯一施工计划）
4. `docs/research/blueprint-parity.md`
5. 根目录 `记录.txt`

当前代码 HEAD `de9de4b`，文档 HEAD `1214068`；交接称 **696 tests 全绿**，已安装 `build 1101-222554` 但需重启 MTS。先亲自核对 Git 和测试，不直接相信交接。

旧 strict 纠偏 Task 1–5 已完成，不再作为本轮施工计划；旧 P7/D5/UI 计划不得复活。

## 三、本轮目标与关键纠偏

目标：**不能光赢，要“战损最小 + 收益最大”的赢法。**

权威蓝本是 `reference/sts2-combatsolver/src/Search/CombatBeamSolver.FinalPlanOrdering.cs`。

🔴 先纠正交接中的两个事实错误：

1. `FinalPlanOrdering.cs:253-271` 是 **18 键**（1 个 OrderBy + 17 个 ThenBy），不是 17。
2. `PotionStrategicCost` **不是独立最终排序键**；独立键是 `OptionalPotionCount`。不得自造第 19 键。

当前 STS1 是 9 键，但本轮不是简单补 9 个 `if`：

- 最终 FinalPlanOrdering、搜索中 interim、Beam retention 必须分开。
- `lastFinalPool` 当前没有保存全部 completed，需恢复蓝本完整候选池。
- 当前“先选一条 → 药水闸 → 补搜 → 复核”需按蓝本统一资格链返工。
- 当前 Survives、CombatEndedTurn、StrategicHpDeficit 等既有口径也要重验，不能只加新键。

## 四、严格按 Task 0 → 8 施工

1. **Task 0**：封存旧实现快照基线；安装重启后复测夜间 8 项。
2. **Task 1**：纯数据 FinalPlanOrdering，18 键逐行 TDD。
3. **Task 2**：Feed / Hand of Greed / Anger 的真实事件账本、copy/fork 与跨重搜基线。
4. **Task 3**：逐回合 FutureSoldHp + BattleDamageTracker，恢复 StrategicSold。
5. **Task 4**：全部 completed + frontier，区分 interim/final，候选自带 Boundary。
6. **Task 5**：统一药水资格、forced-only/无药基线、post-search 与专用异常。
7. **Task 6**：串并行、novelty、portfolio、continuation 和最终发布一致性。
8. **Task 7**：同一批快照 paired A/B，比较胜负、战损、收益与路线变化。
9. **Task 8**：真机、规范/质量双评审、handoff/parity/结果闭环。

每个 Task 先 RED 再 GREEN，独立 commit；具体文件、测试与命令严格照施工计划，不自选子集。

## 五、默认值与范围底线

- 默认成长预算为 0、遗物目标为空：免费成长可比较，但不许自造卖血换 GrowthHpCredit。
- 只映射 STS1 同名且 javap 核实的 Feed / Hand of Greed / Anger；不搬 STS2 独有来源。
- StrategicSold 不是总掉血；按同组最低可达掉血 + 战斗 committed 实现。
- 每候选 Boundary 不得用全局 lastStopReason 冒充。
- 不加 persistentBuffValue 额外最终键，不实现默认冻结、无生还禁打、点 Tn、本地 UI 控制器。
- 最终 lastRoute / lastSteps / continuations 必须来自同一最终选中路线。

## 六、授权与证据纪律

- 目标内代码、测试、诊断、重构、构建、安装、真机和本地 commit 自主完成，不逐项等确认。
- 真机对象只留主线程；后台搜索只用纯数据；渲染不 capture。
- 不 push，不删除用户文件，不用遗留 SolverMod/sts-agent 资产。
- paired A/B 用来发现回归，不用来调换蓝本顺序或创造新权重；相关快照不得伪称独立样本。
- 自动化、快照投影、真机结果分开写；未验证就写未验证。
- 旧测试若钉住 17 键、局部池或本地闸门，改成蓝本反向合同，不直接删除。

## 七、最终交付

每轮跑：

```bash
./gradlew test --offline
./gradlew jar --offline
git diff --check
```

最终表格：`改动 | commit | 蓝本证据 | 自动化 | 快照 A/B | 真机 | 状态`。另列未闭环、风险和回退方式。更新 handoff、blueprint-parity、施工计划和 `记录.txt`。

现在直接开始：核对 Git → 跑 696 基线与旧快照输出 → 从 Task 0 开始。
