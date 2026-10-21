# 开发笔记 · 环境与踩坑

> 本文件记录**本机特有**的问题与已验证的解法。换机器后有些条目可能不再适用。

## 1. 构建输出放在工作区之外

`build.gradle.kts` 把 `build` 目录默认设到 `~/.sts1-combatsolver/build`，而不是项目内的 `build/`。

**原因**：工作区里的 `build/test-results/**.xml` 与 `build/reports/tests/**` 会被某个外部索引进程锁住，导致 Gradle 覆盖旧报告时失败：

```
Execution failed for task ':test'
> Could not write XML test results for ... to file ...TEST-...TraceTest.xml
```

现象很有迷惑性：测试本身是**通过**的，挂在"写报告"这一步。把构建目录移出工作区后，连续 `--rerun-tasks` 三次均通过，问题消失。

覆盖方式：在 `local.properties` 里写 `build.dir=...`。

## 2. 测试任务不生成 HTML 报告

`reports.html.required = false`，只保留 JUnit XML。与 §1 同源（HTML 报告反复被锁）。
需要人读报告时可临时打开，但请先把构建目录移出工作区。

## 3. `Settings.WIDTH/HEIGHT` 在 mod 初始化时是 0

`@SpireInitializer` 的 `initialize()` 执行时游戏窗口还没建好，`Settings.HEIGHT` 为 0。
若在这里算界面坐标，会得到 **负值**，面板被放到屏幕外面（表现为"没见到面板"）。

**解法**：坐标延迟到第一帧再算（`SolverPanel.ensurePlaced()`）。

## 4. `MonsterRoom.onPlayerEntry` 时手牌还没发

该钩子触发时 `hand=0 draw=0 energy=0`，不能当作"战斗根状态"。

**解法**：钩子只负责"武装"（arm），真正的捕获放在主线程 tick 里，等
`room.phase == COMBAT && hand.size() >= player.gameHandSize && energy > 0` 才抓。

⚠️ **发牌是一张一张来的**：只判 `hand.size() > 0` 会在 `hand=1` 时就抓（实测踩过）。
用 `gameHandSize` 作为期望手牌数（Snecko Eye 等会改它），并留 180 帧兜底 + 告警。

## 4.1 界面字号与行距必须按字体现算

固定像素间距会翻车：`FontHelper.charDescFont` 的行高远大于 24px，
四行文字会叠在一起；固定 440px 的面板宽度装不下长文本。

**解法**：用 `GlyphLayout` 量最宽行算面板宽、用 `font.getLineHeight()` 算行距，
并把结果缓存（只在文本变化时重算，避免每帧分配）。

## 5. 字节码必须是 52（Java 8）

游戏跑 JRE 8，class 版本高于 52 会在加载时报 `UnsupportedClassVersionError`。
`options.release.set(8)` 已固定；校验命令：

```bash
od -An -tu1 -j6 -N2 path/to/X.class    # 期望输出 0 52
```

## 6. 依赖 jar 的来源

| jar | 位置 |
|---|---|
| `desktop-1.0.jar` | 游戏根目录 |
| `ModTheSpire.jar` | 创意工坊 `646570/1605060445/`（**不在**游戏根目录） |

`tools/sync-libs.ps1` 负责同步到 `lib/`（不入库）。

## 7. `.ps1` 必须是 ASCII

PowerShell 5.1 按 ANSI 读取脚本，UTF-8 中文会导致解析错误（报"意外的标记"）。
所有脚本只用英文输出与注释。

## 8. 构建/安装/启动

```powershell
powershell -File tools/sync-libs.ps1      # 首次
powershell -File tools/gradle.ps1 build   # 或 .\gradlew.bat build
powershell -File tools/install.ps1        # 复制 jar 到 mods/
# 先开 Steam，再双击游戏目录的 MTS-launch.cmd
```

日志：`<游戏目录>/solver-logs/sts1-combatsolver-YYYY-MM-DD.log`（UTF-8）。
