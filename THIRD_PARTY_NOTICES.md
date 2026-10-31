# 第三方来源与署名

## 算法参考（MIT）

- **[Torch1230/CombatSolver](https://github.com/Torch1230/CombatSolver)** —— 本项目对标的 STS2 战斗求解器。
  `search/` 里的 Beam Search 回合层结构、BFWS 新颖度搜索（`BfwsPackedNovelty` / `BfwsBoundedOpen` /
  `BfwsEscapeBudget`）、宽度组合（`BeamWidthPortfolio` 五成员与准入门）、保留策略排序键、
  药水战略成本、怪物 AI 镜像的组织方式，都是**按源码逐条翻译**的。
  哪里搬了、哪里没搬、为什么没搬，逐项记在 [`docs/research/blueprint-parity.md`](docs/research/blueprint-parity.md)。

## 运行依赖（不随仓库分发）

- **Slay the Spire**（Mega Crit Games）—— 游戏本体、`desktop-1.0.jar`、卡牌文本与美术资源。
  本项目不包含也不分发任何游戏资源；使用者需要自行拥有正版游戏。
- **ModTheSpire**（Apache-2.0）—— mod 加载器，从 Steam 创意工坊获取。
- **Gradle Wrapper**（Apache-2.0）—— 构建工具链。

## 声明

本项目与 Mega Crit Games 无关联，未获其背书。所有游戏内名词（卡牌/遗物/怪物/能力名称）
的版权归 Mega Crit Games 所有，本项目仅在描述游戏机制时引用。
