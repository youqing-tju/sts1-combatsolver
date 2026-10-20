# 开源项目调研：STS1 战斗求解器

> 调研时间 2026-10-20。结论：**STS1 目前没有等价于 CombatSolver 的第三方战斗求解器**，本项目的复刻不构成重复造轮子。

## 一、蓝本

| 项目 | 形态 | 关键事实 | 用途 |
|---|---|---|---|
| [Torch1230/CombatSolver](https://github.com/Torch1230/CombatSolver) | C# / .NET 9 / Godot 的 STS2 mod，MIT，v0.48.0 | 主线程捕获根状态 → **后台线程**分支搜索 → 只读路线快照 → 原生入口执行 → 状态偏离即重算。`src/` 分 `Engine / Search / Strategy / Runtime / UI / Prediction / Replay / Diagnostics / Api / Testing`。搜索 = Beam Search，三重预算约束；默认档 120 s / 12 万节点 | **复刻蓝本** |

> ⚠️ 两处易误读，已核 `docs/ARCHITECTURE.md` 原文更正：
> 1. 它的 **Mirror 是「方法镜像」**（`Engine/InCombat/Mirrors/` = 原版 Hook/Model 方法镜像），即照着原版规则重写等价实现，**不是**把战场对象整体复制后跑原版卡牌代码；实际是「根快照 + 影子状态 + 克隆模型（COW）+ 方法镜像」的混合体。
> 2. 正因为是影子模拟，搜索才能放**后台线程**（原文：主线程捕获根 → 后台分支搜索 → 主线程部署）。若改跑真卡牌代码，就只能在主线程。
> 3. **信息口径 = 全知**：模拟分支自持 RNG 与牌堆，能「预测抽牌、洗牌」；攻击意图由分支 AI 派生。
| [hotwords123/StS2.RandomForeseer](https://github.com/hotwords123/StS2.RandomForeseer) | C#（STS2） | 提供 Combat / Fork / Mirror / History / RNG 基础逻辑，是 CombatSolver 模拟核心的上游 | 架构思想参考（**STS1 无对应实现**） |

## 二、STS1 可用的现成件

| 项目 | 形态 | 覆盖 | 用途 |
|---|---|---|---|
| [gamerpuppy/sts_lightspeed](https://github.com/gamerpuppy/sts_lightspeed) | C++17 headless，MIT，2024-08 最后提交 | 全敌人 / 全遗物 / **全 Ironclad 卡** / 全无色牌；RNG 精确；1M playouts / 5 s @16 线程；pybind11 绑定；支持存档载入战斗 | 训练环境首选（后期阶段） |
| [Attemory/sts_lightspeed](https://github.com/Attemory/sts_lightspeed) | 上一项分支，MIT，**2026-09 活跃** | 追加**全 Defect 卡**；新增 `battle-sim` / `card-reward-eval` 可执行目标 | 训练环境首选（后期阶段，覆盖更广） |
| [AttemorySystem/spire-agent](https://github.com/AttemorySystem/spire-agent) | Python + CommunicationMod + `gym-sts`，MIT | **铁甲与故障机器人多次 A20 心脏通关**；Map/Build/Combat 三层 Agent，Combat 用 `sts_lightspeed` + MCTS；每局可 replay；Linux/macOS 已验证，**Windows 未测** | **全自动爬塔的现成参照**（宏观层阶段） |
| [njagh/bottled_ai](https://github.com/njagh/bottled_ai) | Python 全流程 bot，2026-02 活跃 | 观者 52% 胜率；战斗 = 图遍历 + 自建模拟；纯规则无 ML | 宏观层基线 |
| [WangCCe/slay-the-spire-ai](https://github.com/WangCCe/slay-the-spire-ai) | Python（`spirecomm` 分支），2026-01 | Beam Search 战斗 AI + DQN；A20 铁甲 | 可即跑的 STS1 战斗解，但状态靠 JSON 重实现，保真度有限 |
| [cvennevik/slay-the-spire-solver](https://github.com/cvennevik/slay-the-spire-solver) | C#，2022 停更 | 只做到「铁甲 vs 大颚虫」，不可运行 | 仅架构参考（不可变 GameState + Actions） |
| [alexdriedger/SlayTheSpireFightPredictor](https://github.com/alexdriedger/SlayTheSpireFightPredictor) | Python / ML | 32.5 万场训练，战损预测 ±7 HP | 后期「预测模型」参考 |
| [jjoba/sts_cockpit](https://github.com/jjoba/sts_cockpit) | Python | 选牌/遗物胜率预测（220 万参数 MLP，77M 局） | 宏观层参考 |

## 三、工具链

| 项目 | 说明 |
|---|---|
| [ForgottenArbiter/CommunicationMod](https://github.com/ForgottenArbiter/CommunicationMod) | stdin/stdout JSON 协议，外部进程驱动游戏的标准通道（宏观层阶段使用） |
| ModTheSpire + BaseMod | 本机已装：`mods/` 有 BaseMod.jar、CommunicationMod.jar；MTS 在创意工坊 `1605060445` |
| SuperFastMode | 训练加速 4–5×（跳动画），后期数据采集可用 |

## 四、结论与定位

1. **战斗求解器**：STS1 空白 → 本项目填这个空白（进程内、真卡牌代码、Beam）。
2. **训练环境**：`Attemory/sts_lightspeed`（MIT）成熟可用，不必自造。
3. **宏观层**：`spire-agent` 已证明 A20H 可行，但其 Windows 未验证；后期可「借架构、自建实现」。
4. 本项目的独特价值 = **保真度**（跑真代码，规则零重写）+ **教师数据/评估器**（可产出高质量轨迹与战损标注），不是取代快速模拟器。
