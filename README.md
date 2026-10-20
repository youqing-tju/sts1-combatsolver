# sts1-combatsolver

《杀戮尖塔 1》（Slay the Spire 1）的**进程内战斗求解器**，对标 [Torch1230/CombatSolver](https://github.com/Torch1230/CombatSolver)（STS2）。

进入战斗后，mod 捕获真实战斗根状态 → 克隆出独立沙箱 → 在沙箱里**执行游戏自己的卡牌代码**展开 Beam Search → 给出跨回合路线、预计战损与关键行动 → 可只查看、执行本回合，或连续全自动。

> 状态：**准备阶段**。当前仓库只有设计文档与构建链，尚无 mod 代码。

## 与 CombatSolver 的关键差异

| | CombatSolver (STS2) | 本项目 (STS1) |
|---|---|---|
| 语言/宿主 | C# / .NET 9 / Godot | Java 8 / libGDX（ModTheSpire） |
| 模拟核心 | 改造 Random Foreseer 的 Mirror | 自建 Mirror（游戏自带 `Random.copy()` / `AbstractCard.makeCopy()`） |
| 搜索 | Beam Search（时间/节点/内存三重预算） | 同构，另加状态指纹去重与主线程分片 |

## 文档

- 设计定稿：[`docs/plans/2026-10-20-design.md`](docs/plans/2026-10-20-design.md)
- 准备工作清单：[`docs/plans/2026-10-20-prep-checklist.md`](docs/plans/2026-10-20-prep-checklist.md)
- 执行计划（M0–M5）：[`docs/plans/2026-10-20-m0-m5-plan.md`](docs/plans/2026-10-20-m0-m5-plan.md)
- 开源调研：[`docs/research/open-source-survey.md`](docs/research/open-source-survey.md)

## 构建

```powershell
# 1) 从 Steam 目录同步编译所需的两个 jar 到 lib/
pwsh -File tools/sync-libs.ps1

# 2) 构建链自检（不产生 mod，只验证 JDK/类路径）
pwsh -File tools/build.ps1 -Smoke

# 3) 正式构建（有源码后）
pwsh -File tools/build.ps1 -Configuration Release
```

## 许可

未定（计划成型后转 MIT）。第三方来源与署名见后续 `THIRD_PARTY_NOTICES.md`。
