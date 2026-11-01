# STS1 CombatSolver 下一轮施工提示词（P7 + D5，2026-11-01）

你接手 `C:\Users\22757\Desktop\sts1-combatsolver`，分支 `master`。你是本轮执行负责人，不是只给建议的评审员。目标是按已批准设计直接完成代码、测试、构建、安装、真机验证和逐项本地 commit，最后交付证据；不要写完方案就停下等批准。

## 一、开工必读与基线

按顺序读：

1. `docs/handoff-prompt.md`
2. `docs/plans/2026-11-01-continuation-potion-design.md`（用户已拍板的产品/架构语义）
3. `docs/plans/2026-11-01-continuation-potion.md`（逐项 TDD 施工计划）
4. `docs/research/blueprint-parity.md`
5. 根目录 `记录.txt`

执行：
```bash
git status --short --branch
git log -5 --oneline
./gradlew test --offline
```

最近文档 HEAD 为 `423eb54`，此前实测 **565 tests / 0 failures / 0 errors**；现场以实际结果为准。保留他人改动，禁止 `reset --hard` 和覆盖未提交工作。

## 二、授权与底线

- 既定目标内的代码、测试、诊断、重构、构建、安装、真机验证和本地 commit 全部自主完成；修 bug 可以边查边改，不为计划外测试文件、探针或独立小修逐项请示。
- 只有需要改变已批准产品语义、不可逆/外部操作、或必须违反硬规则时才问。事后用短报告交代改动、证据、风险、回退；请示不算进度。
- 不 push，不擅自删除用户文件，不用本机遗留 SolverMod/sts-agent 资产。
- 不许为了耗时砍搜索预算、宽度、深度或关闭机制；评价只看牌是否打得更好、镜像是否更真实。
- 游戏语义必须 javap / 原版 localization 核实；未知语义显式拒绝。真机对象不进后台线程，渲染不 capture。
- 严格区分事实、假设、候选修复、自动化通过、真机闭环。未运行的真机项写“未验证”。

## 三、本轮唯一主线：P7 → D5

### P7：精确状态 Continuation（最高优先）

按 TDD 计划 Task 1–4 完成：

1. 新建独立精确状态文本与字段差异诊断；**不得拿现有 `fingerprint()` 冒充 continuation**。
2. 从 novelty、药水后处理、0 费补搜全部完成后的最终 `selected` 节点链提取稳定回合边界；只缓存 END_TURN 跨回合、非终局且后面确有动作的边界。
3. 预测和真机使用同一 `ContinuationStamp.from(SimState)`；手牌按无序多重集，抽牌堆等保序，覆盖卡面未来字段、Power、遗物、怪物完整语义和五路 RNG。
4. 新回合主线程精确命中则免搜索续跑，打 `SEARCH_REUSED … validation=exact_state_text`；miss 按 `ManualDivergence / ContinuationMissing / StateMismatch` 归因，给首个差异后重搜。
5. 有界 AUTO 语义不变：点击 Tn 仍打完 Tn 的 END_TURN 和敌方阶段，在 Tn+1 玩家行动前停。Continuation 不是冻结路线。
6. 本回合喝药/三选一、手工输入、部署漂移、战斗 reset 按设计失效；线程边界不变。

### D5：完整药水策略

按 TDD 计划 Task 5–7 完成：

1. 全局 `Disabled / Smart / RequireAtLeastOne`，每槽 `Smart / Disabled / Force`，四预设 `AllSmart / AllProtected / AllForced / OnlyForced`。
2. 持久化键为 `(slot,potionId)`；Smart 删除显式覆盖；OnlyForced 不等于全局 RequireAtLeastOne。
3. 搜索请求携带不可变策略快照和 `policyRevision`；同 fingerprint 的旧 revision 结果也必须拒绝。
4. 主 Beam、novelty、portfolio、不喝药 baseline、药水后处理、0 费补搜、最终 selected 和 continuation 全部使用同一策略；Force 校验最终真实路线，不是排序加分。
5. 药水区成为真实控制器，遗物/成长仍只读；栏位循环 `Smart → Disabled → Force → Smart`。
6. **自动部署/执行中拒绝修改策略**（用户已拍板）。非执行中修改后保存、revision+1、清 continuation，按 autoCompute 决定 Manual 重搜或只保存。
7. 最终执行步骤绑定 slot+potionId，防止两瓶同 ID 药水错槽。

## 四、明确禁止复活旧任务

- 不按旧 P1“open 表过早耗尽、剪枝太狠”施工：123/123 真机快照停止原因都是 `horizon`，115/123 获胜。
- 不按旧 P2“能力牌必须强制先开”施工：A/B 为 50 局更差、7 局更好、0 局由负转胜。
- 不把“每回合重算”写成蓝本行为；蓝本默认是精确校验后续跑。
- 不做冻结路线，不把只读 UI 当 D5 parity，不用新 UI 设置绕过搜索最终答案约束。

## 五、TDD、验证与提交

严格按计划逐 Task：先写失败测试并看到 RED，再最小实现到 GREEN，相关回归后独立 commit。不得删测试、缩小 fixture、禁用搜索通道或放宽断言来全绿。

每轮至少：
```bash
./gradlew test --offline
./gradlew jar --offline
git diff --check
```

完成后执行 `powershell -File tools/install.ps1`，按 TDD 计划 Task 8 做 P7/D5 真机验收并保存日志。随后做 fresh-eyes spec review 与 quality review，发现问题直接修并复测。

更新 `docs/handoff-prompt.md`、`docs/research/blueprint-parity.md` 和计划中的实际结果：真实测试数、commit、真机逐项状态、日志路径、未解决风险。不新建 CHANGELOG.md。

## 六、最终汇报

用表格列：`任务 | commit | 自动化结果 | 真机/回放结果 | 证据位置 | 状态`。随后只列关键文件、未闭环问题、回退方式。必须明确：续跑命中/miss 证据、有界停止是否回归、全局/栏位/预设实际结果、旧 revision 是否被拒绝、同 ID 药水是否错槽。不要只报告“测试全绿”。

现在直接开始：读设计与计划、核对 Git、跑基线，然后从 P7 Task 1 的 RED 测试开始，不要返回一份等待批准的新方案。
