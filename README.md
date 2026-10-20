# sts1-combatsolver

《杀戮尖塔 1》（Slay the Spire 1）的**进程内战斗求解器**，对标 [Torch1230/CombatSolver](https://github.com/Torch1230/CombatSolver)（STS2）。

进入战斗后，mod 捕获真实战斗根状态 → 克隆出独立沙箱 → 在沙箱里**执行游戏自己的卡牌代码**展开 Beam Search → 给出跨回合路线、预计战损与关键行动 → 可只查看、执行本回合，或连续全自动。

> 状态：**M0 骨架**（mod 可加载、面板可拖动/点击、进战斗打印根状态摘要）。

## 与 CombatSolver 的关键差异

| | CombatSolver (STS2) | 本项目 (STS1) |
|---|---|---|
| 语言/宿主 | C# / .NET 9 / Godot | Java 8 / libGDX（ModTheSpire） |
| 模拟核心 | 根快照 + 影子状态 + 方法镜像（改写自 Random Foreseer） | 同构：自建影子状态 + 逐条镜像卡牌/遗物/怪物语义 |
| 搜索 | Beam Search，三重预算，**后台并行** | 同构，另加状态指纹去重与确定性提交 |
| 信息口径 | 全知（自持 RNG 与牌堆） | 同构：全知为默认，另留信息集对照实验 |

## 文档

- 设计定稿：[`docs/plans/2026-10-20-design.md`](docs/plans/2026-10-20-design.md)
- 准备工作清单：[`docs/plans/2026-10-20-prep-checklist.md`](docs/plans/2026-10-20-prep-checklist.md)
- 执行计划（M0–M5）：[`docs/plans/2026-10-20-m0-m5-plan.md`](docs/plans/2026-10-20-m0-m5-plan.md)
- 开源调研：[`docs/research/open-source-survey.md`](docs/research/open-source-survey.md)

## 构建

```powershell
# 1) 从 Steam 目录同步编译所需的两个 jar 到 lib/（不入库）
powershell -File tools/sync-libs.ps1

# 2) 构建 + 测试（Gradle 9.8.2，产物 Java 8 字节码）
.\gradlew.bat build
#    本机已隔离安装 Gradle 时也可：powershell -File tools/gradle.ps1 build

# 3) 产物
#    build/libs/STS1CombatSolver.jar

# 4) 安装到游戏 mods/ 并启动
powershell -File tools/install.ps1
#    先开 Steam，再双击游戏目录下的 MTS-launch.cmd
```

> 无 Gradle 环境时可用备用链：`powershell -File tools/build.ps1 -Smoke`（只验证工具链，不产出 mod）。

## 环境

| 项 | 值 |
|---|---|
| 游戏 | `C:\Users\22757\steam\steamapps\common\SlayTheSpire`（`desktop-1.0.jar`） |
| ModTheSpire | 创意工坊 `646570/1605060445/ModTheSpire.jar`（不在游戏根目录） |
| JDK | 24（`--release 8`，产物 class 版本 52） |
| Gradle | 9.8.2（隔离安装于 `~/.workbuddy/binaries/gradle/`） |

## 许可

未定（计划成型后转 MIT）。第三方来源与署名见后续 `THIRD_PARTY_NOTICES.md`。
