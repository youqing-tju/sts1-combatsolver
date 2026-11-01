# STS1 CombatSolver 下一轮施工提示词（严格蓝本纠偏，2026-11-01）

你接手 `C:\Users\22757\Desktop\sts1-combatsolver`，分支 `master`。你是执行负责人：直接调查、修改、测试、构建、安装、真机验证并逐项本地 commit，最后交付证据，不停在方案。

## 一、最高硬规则

**禁止自创，一切以蓝本为标准复刻。蓝本没有 = 不做。**

- 不允许用“用户此前要求”“最小等价”“本地扩展”“项目自定义”“UI 适配”作为保留自创行为的理由；最新指令已经废止这些豁免。
- STS1/STS2 API 差异只允许改变翻译方式，不允许改变机制、默认值、约束强弱、状态顺序或失败语义。
- 每项行为动工前先给蓝本 `file:line`；找不到就明确写“蓝本没有”，不要实现。
- STS1 原版行为补正必须另给 `javap` 证据，不能冒充 CombatSolver parity。

## 二、开工必读与基线

按顺序读：
1. `docs/handoff-prompt.md`
2. `docs/plans/2026-11-01-strict-blueprint-parity.md`（本轮唯一施工计划）
3. `docs/research/blueprint-parity.md`
4. 根目录 `记录.txt`

旧 `2026-11-01-continuation-potion-design.md` 与旧施工提示词中的本地简化**已废止，不得继续作为依据**。

执行：
```bash
git status --short --branch
git log -8 --oneline
./gradlew test --offline
```
最近文档 HEAD `a927e8c`，此前 672 tests 全绿；其中若干测试钉的是自创语义，不能当作 parity 证据。保留他人改动，禁止 `reset --hard`。

## 三、本轮顺序

严格按纠偏计划 Task 1 → 6：

1. **撤销默认采纳冻结、单步剔除、无生还禁打**。默认恢复精确 Continuation；部署漂移从根重算；DeathTurn/LiveRisk 两个蓝本停手机制保留。
2. **撤销本地有界 AUTO 与伪 Adopt/Freeze**。AdoptRoute 和 RouteFrozen 不得混写；不能完整复刻就明确未做，不做半吊子模式。
3. **Continuation 按蓝本返工**：best 只选边界、最终路线回放捕获；`CaptureLive`/`CapturePredicted` 分侧归一化；四个牌堆全部保序；禁止删字段凑命中。
4. **药水策略按蓝本返工**：全局 Disabled 不压显式 Force；全局 policy 不落盘；删除自造 policyRevision；统一候选池资格过滤，无合格路线抛专用异常；删除“钉住补搜→null”。
5. **全量来源审计**：代码、注释、测试和文档里的“项目自定义/用户规则/本地扩展”逐项移除或给出蓝本证据。
6. 全量测试、jar、安装、真机验收、双评审、文档闭环。

**先撤自创，再修影子。** 不继续用冻结或剔步掩盖 `SEARCH_REUSE_MISS`，不为了提高命中率删 stamp 字段。

## 四、授权与底线

- 既定目标内代码、测试、诊断、重构、构建、安装、真机验证和本地 commit 自主完成，不逐项等确认。
- 恢复蓝本语义不需要 paired A/B 证明更好；parity 就是验收。不得用旧 A/B 或“用户以前喜欢”阻止纠偏。
- 不 push，不擅自删除用户文件，不用遗留 SolverMod/sts-agent 资产。
- 不砍预算、宽度、深度或搜索机制；真机对象不进后台，渲染不 capture。
- 旧测试若断言自创行为，改成蓝本反向合同并看到 RED→GREEN；不得只删测试。
- 严格区分自动化、回放和真机；未运行写未验证。

## 五、提交与最终汇报

每个 Task 独立 commit。每轮：
```bash
./gradlew test --offline
./gradlew jar --offline
git diff --check
```

更新 `docs/handoff-prompt.md`、`docs/research/blueprint-parity.md` 和纠偏计划的实际结果。最终表格：`纠偏项 | commit | 蓝本证据 | 自动化 | 真机 | 状态`，随后列未闭环问题和回退方式。

现在直接开始：核对 Git、跑基线，从 Task 1 的“默认不冻结、失配重算、单步不可用 root replan”失败测试开始。
