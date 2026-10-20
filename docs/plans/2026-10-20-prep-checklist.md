# 准备工作清单

> 勾选项在开工前逐条确认；标 🔴 的未完成则不能进入对应里程碑。

## A. 环境（本机已核实）

- [x] 游戏安装：`C:/Users/22757/steam/steamapps/common/SlayTheSpire`（`desktop-1.0.jar`，12-18-2022 版）
- [x] ModTheSpire：创意工坊 `646570/1605060445/ModTheSpire.jar`（3.30.0）
- [x] 运行时：游戏自带 JRE 8；`mods/` 已有 BaseMod.jar、CommunicationMod.jar
- [x] JDK：24（`javac 24.0.2`），`--release 8` 实测编译通过（产物 class v52）
- [x] C++ 工具链（后期训练用）：`w64devkit` g++ 16.2、cmake 4.4.3、ninja
- [x] git 2.55 + GitHub 凭据可用（`youqing-tju`，免密 `ls-remote` 实测通过）
- [ ] 🔴 Gradle 不可用（本机未安装）→ **构建链改用 `javac + jar` 脚本**，零外网依赖

## B. 仓库

- [x] 目录：`C:/Users/22757/Desktop/sts1-combatsolver`
- [ ] `git init` + 首次提交（文档 + 构建链）
- [ ] 远程 `youqing-tju/sts1-combatsolver`（**private**）——等 M4 验收后再推
- [ ] `.gitignore` 生效校验（`lib/*.jar`、`build/` 不入库）
- [ ] `THIRD_PARTY_NOTICES.md` 起草（涉及 ModTheSpire API 引用、后期 lightspeed）

## C. 构建链

- [ ] `tools/sync-libs.ps1`：从 Steam 目录同步 `desktop-1.0.jar` + `ModTheSpire.jar` 到 `lib/`
- [ ] `tools/build.ps1`：`javac --release 8` → `jar`，产物 `build/STS1CombatSolver.jar`
- [ ] `-Smoke` 自检通过（编译并运行 `tools/probe/Probe.java`）
- [ ] 🔴 `MTS-launch.cmd` 启动链路可用（先开 Steam → 双击脚本）

## D. 隔离与安全（沙箱前置）

- [ ] 移除本机遗留 `mods/SolverMod.jar`（避免 modid 冲突），归档到仓库外 `_archive/`
- [ ] 存档自动备份脚本（`saves/` → `_backup/saves-<ts>/`）
- [ ] 只读保护开关设计：`-Dsolver.readonly=true` 时禁止任何写存档 / 写偏好
- [ ] 静态字段写白名单初稿（`AbstractDungeon` 下允许换入换出的字段清单）

## E. 基线与样本

- [ ] 采集 200 帧真实战斗快照（种子 + 楼层 + 牌组 + 怪物），用于 A1/A2 台架
- [ ] 记录规则基线：20 场固定种子 Act1 的战损，作为 A3 对比基准
- [ ] 帧快照格式定稿（JSON + 可回放到沙箱的最小字段集）

## F. 文档与流程

- [x] 设计定稿 `docs/plans/2026-10-20-design.md`
- [x] 执行计划 `docs/plans/2026-10-20-m0-m5-plan.md`
- [x] 开源调研 `docs/research/open-source-survey.md`
- [ ] `CHANGELOG.md` 骨架（Keep a Changelog 格式）
- [ ] 每轮迭代模板：`docs/plans/TEMPLATE-iteration.md`

## G. 待用户拍板

- [ ] **D8 信息口径**：全知 / 严格信息集 / 全知+开关
- [ ] **D9 覆盖门槛**：A6 要求 100% 等价，还是允许白名单例外
