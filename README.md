# sts1-combatsolver

《杀戮尖塔 1》（Slay the Spire）的**进程内战斗求解器**：一个 ModTheSpire mod，在真实战斗里读取当前局面，克隆出独立影子状态，在影子世界里按游戏自己的规则搜索，然后把「这一回合该怎么打、接下来几回合怎么走、预计掉多少血」交给执行层自动打完。

对标 [Torch1230/CombatSolver](https://github.com/Torch1230/CombatSolver)（STS2），搜索层是**逐条翻译**它的实现，不是自己发明的机制。

> 当前状态（2026-10-31）：铁甲战士 Act 1–4 可全自动跑通；最新一局以 29/80 血通过第三幕 Boss 进入第四幕。532 条单元/回归测试全绿。真机对拍（每张牌都和游戏结算逐字段比对）失配率约 25%，仍在逐条清零。

## 它怎么工作

```
战斗开始
  │  capture/     读真实战斗：血量/能量/牌堆/遗物/能力/怪物意图/RNG 五流
  ▼
SimState（影子状态，纯数据，不持有游戏对象）
  │  search/      Beam Search + BFWS 新颖度 + 宽度组合，每个节点跑 mirror/ 里镜像的卡牌语义
  ▼
PlanAction[]（跨回合路线：打哪张牌、打谁、什么时候结束回合）
  │  exec/       回放本回合动作；面板实时显示搜索形状与路线
  ▼
verify/        每打一张牌，把影子算出的结果与游戏真实结果逐字段对拍（DIFF MATCH / MISMATCH）
```

关键点：

- **全知口径**：影子自己持有抽牌堆顺序与 5 条 RNG 流（`cardRandomRng` / `aiRng` / `shuffleRng` / `monsterRng` / `miscRng`），所以能精确推演「下一步会抽到什么、怪物下一步出什么招」。
- **镜像语义而非硬编码数值**：`mirror/` 里按字节码逐条翻译卡牌/遗物/能力/怪物 AI；遇到没建模的机制**显式拒绝**（`ActionRejected`），绝不静默算错。
- **对拍是一等公民**：每张牌都是一次实验。失配会落盘成可离线重放的样本（`solver-snapshots/mismatch/*.json`），每回合开头也会落一份真局面快照（`turn-A1F14T2.json`）。

## 构建与运行

前置：正版游戏本体、ModTheSpire、JDK（用 `--release 8` 编译）、Gradle。

```powershell
# 1) 从游戏目录与创意工坊同步编译所需的两个 jar 到 lib/（不入库）
powershell -File tools/sync-libs.ps1

# 2) 构建 + 测试（产物是 Java 8 字节码）
.\gradlew.bat build

# 3) 安装到游戏 mods/ 并启动
powershell -File tools/install.ps1
```

`lib/*.jar`、`reference/`（蓝本参考实现）以及所有运行期日志/快照都**不在仓库里** —— 它们要么是游戏版权资源，要么是本机路径产物。

## 诊断与回归

| 工具 | 用途 |
|---|---|
| `tools/audit_power_ids.py` | 把代码里用到的能力 ID 与游戏 jar 里的真实 ID 交叉核对（写错不会报错，只会静默失效） |
| `TurnReplayProbeTest` | `gradlew test -Dreplay=<turn-A1F14T2.json>`：把每个候选首手钉成固定前缀各跑一趟，打印每条世界线的分数 |
| `tools/watch-solver.ps1` | 汇总日志：搜索耗时、组合/探索采纳分布、慢搜索计数 |

## 文档

- 设计定稿：[`docs/plans/2026-10-20-design.md`](docs/plans/2026-10-20-design.md)
- 执行计划（M0–M5）：[`docs/plans/2026-10-20-m0-m5-plan.md`](docs/plans/2026-10-20-m0-m5-plan.md)
- 蓝本复刻对照表（哪块已搬、哪块没搬、按什么顺序搬）：[`docs/research/blueprint-parity.md`](docs/research/blueprint-parity.md)
- 机制实录（每条都带 javap / 字节码出处）：[`docs/research/mechanics-reference.md`](docs/research/mechanics-reference.md)

## 许可与声明

MIT，见 [LICENSE](LICENSE)。

- 本项目**不包含**任何游戏本体资源、卡牌文本或美术素材；运行时依赖你自己拥有的《Slay the Spire》。
- 与 Mega Crit Games 无关联。请自行确认使用 mod 是否符合你所在地区的许可与平台条款。
- 搜索层算法翻译自 [Torch1230/CombatSolver](https://github.com/Torch1230/CombatSolver)，署名见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
