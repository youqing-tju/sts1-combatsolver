# STS1 CombatSolver 下一轮施工提示词（镜像精度，2026-11-02）

你接手 `C:\Users\22757\Desktop\sts1-combatsolver`，分支 `master`。你是执行负责人：在既定目标内直接调查、修改、测试、构建、安装、真机验证并逐项本地 commit，最后交付证据，不停在方案。

## 一、最高硬规则

**禁止自创，一切以蓝本/原版机制为准。蓝本没有 = 不做。**

- 不加失配后实机重建影子、默认冻结、失配容忍、删戳字段、无序手牌续跑或任何重同步捷径。
- 每项原版数值/生命周期给 `javap` 证据；每项蓝本机制给 `file:line`。不得把猜测写成根因。
- 不嫌慢，只嫌牌打得臭；不得为耗时砍预算、宽度、深度或机制。
- 目标内自主施工与本地 commit，不逐项等确认；不 push。

## 二、开工必读与当前基线

按顺序读：

1. `docs/handoff-prompt.md`
2. `docs/plans/2026-11-02-mirror-fidelity-design.md`
3. `docs/plans/2026-11-02-mirror-fidelity.md`（本轮唯一施工计划）
4. `docs/research/blueprint-parity.md`
5. 根目录 `记录.txt`

当前文档 HEAD `870125f`，代码 HEAD `a2d82b3`；交接称 **784 tests 全绿**。已安装并加载 `build 1102-124214`。先亲自核对 Git 和测试，不直接相信交接。

18 键 FinalPlanOrdering 已完成，不再作为本轮施工内容；旧 P7/D5/UI/strict 计划不得复活。

## 三、本轮目标与已证实根因

目标：**让镜像因真实规则一致而对拍通过，让 Continuation 因预测兑现而命中。**

不要再按交接旧清单逐张猜格挡。审计已确认：

1. 前 7 条 block 差 1 是对拍早于 `onAfterUseCard` 尾部队列；稳定门漏了 `GameActionManager.currentAction`。
2. Bash 致死心脏仍触发 BeatOfDeath，影子却因 0 血跳过，是真模型差。
3. Heavy Blade 的力量倍率入口漏 PenNib；PenNib 第9挂/第10摘本身已存在。
4. Fiend Fire 的 PenNib/牌堆/Power 差异多由 `forced after 90f` 撞上合法中间态；未决样本不得记真实 MISMATCH。
5. Apotheosis 的通用 upgradeOf 保留旧 cost/baseDamage；复用 CardExporter 的16改费+34改伤机械数据，不写 Madness 特例。
6. Madness 必须持续重抽直到成功，不能自造手牌数上限；普通 diff 的手牌无序口径必须改保序。
7. 整手牌回合末按原版从尾部逐张进弃牌堆；当前正序 addAll 会经洗牌/RNG改变后续世界线。
8. Brutality 要在 `START_OF_TURN_POST_DRAW` 抽 amount 并以 HP_LOSS 扣 amount，继续触发黏土/Rupture。
9. Velvet Choker 跨回合未清零；跨卡 flush 另有“live 已含下一张牌计数”的基线错帧，两者不得混修。
10. True Grit/Headbutt 的选择坐标错误；SporeCloud、SpireShield 死亡清理、Vulnerable 轮末 tick 均有原版证据。
11. Fiend Fire 要先完成本批消耗再执行 DarkEmbrace 抽牌，新抽牌不得污染本批随机池；数量早拍与身份队列分开验收。
12. Power Through 等生成手牌效果必须执行原生10张上限，溢出入弃牌堆；不是早拍。

## 四、按 Task 0 → 8 施工，可并行但不得同文件抢改

1. **Task 0**：按 build/run 冻结日志、快照与基线，禁止覆盖写目录混 run。
2. **Task 1**：完整 v5 快照 + 单一 JSON parser + 通用稳定 MISMATCH DUMP 回放。
3. **Task 2**：`currentAction + actions/cardQueue/limbo/screen` 稳定屏障；90帧和跨卡不得强出假失配；choices/来源牌堆完整落盘。
4. **Task 3**：BeatOfDeath、Heavy Blade+PenNib、Brutality，分别 TDD/commit。
5. **Task 4**：通用升级、Madness 重抽、手牌保序 diff、整手牌逆序弃牌，分别 TDD/commit。
6. **Task 5**：颈环清零 → HAND/DISCARD/EXHAUST 选择坐标 → SporeCloud/Vulnerable/Surrounded → 跨卡 flush 基线。
7. **Task 6**：严格相邻回合 ContinuationPairProbe；缺字段样本只报 incomplete_schema，不进命中率分母。
8. **Task 7/8**：全量回归、路线质量 A/B、构建安装重启、真机验收、文档与回退。

Lane A（ShadowTracker/快照/回放）与 Lane B（Hooks/DamageCalc/Effects/CombatFlow）可并行；Task 5 若触及同文件必须等对应 lane 合流。每个根因独立 RED/GREEN 和 commit，不做一颗巨型 commit。

## 五、证据与验收底线

- 稳定真失配、forced/未决、缺字段旧格式分开；后两类不得作为 diff=0 的 oracle。
- `actions=[]` 不代表当前动作执行完；不得用增加固定等待帧代替 currentAction。
- 不得把所有 forced 样本一刀切吞掉：未决不能出结论，稳定后真差异仍必须报。
- 旧 151 对快照缺 v5 字段，不得硬算命中率。124214 在 12:56 后真机基线是 `SEARCH_REUSED=0/30`。
- 真机修复字段不得再成为首差；相应战斗仍 0 命中就不能写续跑闭环。胜率、失配率、续跑命中率分开报告。
- 真机对象只留主线程；后台只碰纯数据；渲染不 capture。

## 六、最终交付

每轮跑：

```bash
./gradlew test --offline
./gradlew jar --offline
git diff --check
```

最终表格：`根因 | commit | 原版/蓝本证据 | 自动化 | 稳定回放 | 真机 | 状态`。另列未闭环、风险、回退方式。更新 handoff、blueprint-parity、`记录.txt` 与施工计划状态。

现在直接开始：核对 Git → 跑784基线 → 冻结证据 → 从 Task 0 开始。
