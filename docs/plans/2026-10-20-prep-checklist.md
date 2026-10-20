# 准备工作清单

> 勾选项在开工前逐条确认；标 🔴 的未完成则不能进入对应里程碑。

## A. 环境（本机已核实）

- [x] 游戏安装：`C:/Users/22757/steam/steamapps/common/SlayTheSpire`（`desktop-1.0.jar`，12-18-2022 版）
- [x] ModTheSpire：创意工坊 `646570/1605060445/ModTheSpire.jar`（3.30.0）
- [x] 运行时：游戏自带 JRE 8；`mods/` 已有 BaseMod.jar、CommunicationMod.jar
- [x] JDK：24（`javac 24.0.2`），`--release 8` 实测编译通过（产物 class v52）
- [x] C++ 工具链（后期训练用）：`w64devkit` g++ 16.2、cmake 4.4.3、ninja
- [x] git 2.55 + GitHub 凭据可用（`youqing-tju`，免密 `ls-remote` 实测通过）
- [x] **Gradle 9.8.2**：已隔离安装到 `~/.workbuddy/binaries/gradle/`，wrapper 已生成，`gradle build` 实测通过（39 s，2 个测试全绿，产物 class 版本 52）

## B. 仓库

- [x] 目录：`C:/Users/22757/Desktop/sts1-combatsolver`
- [ ] `git init` + 首次提交（文档 + 构建链）
- [ ] 远程 `youqing-tju/sts1-combatsolver`（**private**）——等 M4 验收后再推
- [ ] `.gitignore` 生效校验（`lib/*.jar`、`build/` 不入库）
- [ ] `THIRD_PARTY_NOTICES.md` 起草（涉及 ModTheSpire API 引用、后期 lightspeed）

## C. 构建链

- [x] `tools/sync-libs.ps1`：从 Steam 目录同步 `desktop-1.0.jar` + `ModTheSpire.jar` 到 `lib/`
- [x] `build.gradle.kts` + wrapper + `tools/gradle.ps1`（Gradle 9.8.2）
- [x] `gradle build` 通过：编译 `--release 8`、JUnit 5 跑通、`build/libs/STS1CombatSolver.jar` 出包
- [x] 备用链 `tools/build.ps1 -Smoke`（无 Gradle 时验证工具链与类路径）
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

## G. 已拍板

- [x] **D3 搜索内核**：**甲 方法镜像**（蓝本原味，影子模拟 + 后台多线程）
- [x] **D8 信息口径**：**全知**（M2 另跑严格信息集对照，只出数据）
- [x] **构建工具**：Gradle（非 javac 脚本）
- [ ] **D9 覆盖门槛**：A6 要求 100% 等价，还是允许白名单例外（M3 前定）
