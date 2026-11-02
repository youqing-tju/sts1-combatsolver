# 2026-11-02 镜像精度与跨回合命中设计

> 状态：按用户「大幅加大推进力度」及既定镜像精度主线形成的执行设计。
> 本轮不改产品语义，不新增重同步、冻结、放宽比较或路线质量机制。

## 1. 目标与硬边界

目标：消灭真机对拍中的已证实根因，使精确 Continuation 因真实预测一致而命中。

必须同时满足：

- 只按原版字节码与蓝本语义修模型；不以“更鲁棒”为理由另造机制。
- 不加“失配后用实机重建影子”的通道，不删除 ContinuationStamp 字段，不把精确比较改宽。
- 不为了缩短等待或提高命中率砍搜索、跳过动作或吞掉真实失配。
- 对拍未结算样本不得触发 `DEPLOY_REPLAN`；真实稳定失配仍必须触发。
- 测试通过、离线回放、真机命中是三层证据，不能互相冒充。

## 2. 已证实根因，不再沿用旧假设

| 根因 | 证据 | 旧假设处理 |
|---|---|---|
| 对拍早于 UseCardAction 尾部的 onAfterUseCard 队列落地 | 前 7 条 block 差 1 的样本在 `mismatch dump` 后约 50ms 自动追到 sim； dump 的 `cards` 总数相等；`actions=[]` 时 `GameActionManager.currentAction` 仍可能未执行 DamageAction | “逐卡牌格挡公式/黏土公式错误”撤销；不加逐卡牌格挡日志，稳定门必须包含 currentAction |
| 致死出牌后仍执行 BeatOfDeath | `016-Bash+.json`：心脏 2→0，live 仍 block 2→1；`UseCardAction` 先 `reboundOnUse`，再遍历怪物 `onAfterUseCard`，无存活过滤 | `Hooks.java:155-164` 的 `hp<=0 continue` 是真模型缺口；与前 7 条假失配分开修 |
| Heavy Blade 的力量倍率入口漏 PenNib | `003-Heavy_Blade.json` 中 before/sim/live = 35/48/35；DamageCalc 普通入口有笔尖，倍率入口没有 | PenNib 第 9 挂/第 10 摘本身已存在，不重复实现 |
| Fiend Fire 的 PenNib/牌堆/Power 失配是强制对拍撞上中间态 | 第 9 击挂上、第 10 击队列移除是合法窗口；当前 `forced after 90f` 仍写 MISMATCH | 不改 PenNib 生命周期；修稳定判定与未决样本处理 |
| 通用 upgradeOf 保留旧费用/伤害 | 004-Apotheosis+：Madness cost 1 vs 0且 RNG 全同；同 dump 的 Strike/Bash/FiendFire/Reaper 也被 SimCard.equals 漏报。CardExporter 机械数据含 16 个改费、34 个改伤条目 | “本条只是手牌顺序/RNG 错”撤销；用 base/up 导出数据做有界通用修复，禁止 Madness 特例或126张人工审计 |
| 整手牌回合末进入弃牌堆顺序相反 | `CombatFlow.endTurn` 正序 `discard.addAll(hand)`；原版 DiscardAction 反复 `getTopCard()`；日志 292/301/309 的 `[Curse,Wound,Wound]` 直接对应 | 手牌顺序/RNG 是独立的真问题；不得再用手牌无序 diff 代替 Continuation 精确比较 |
| Brutality 只挂 Power，没有回合初效果 | 原版 `atStartOfTurnPostDraw` 依次 DrawCardAction(amount) + LoseHPAction(amount)；`PowerHooks` 无读取方。T2 手牌 6 与连续 `hp -1` 直接对应 | 独立模型缺口，不归入对拍提前；必须走统一 HP_LOSS 与受伤钩子 |
| Madness 自造重抽上限，普通 diff 忽略手牌顺序 | 原版不可降时递归重抽直到成功；当前最多 size 次后静默不降。`SimState.diff` 排序手牌且旧测试钉住换序相等 | 保留整手牌原顺序与随机下标；删除语义上限，普通 diff 改保序反向合同 |
| Fiend Fire 同步抽牌污染本批消耗池 | 008/014 的 exhaust 身份不同但 RNG 相同；原版先排完整批 ExhaustAction，DarkEmbrace 的 DrawCardAction 后执行，当前每张消耗同步 drawCards | 数量差归稳定门，消耗身份差归动作队列；新抽牌不得进入同批随机池 |
| Power Through 漏原生10张手牌上限 | 012 真机稳定为 hand10，1 Wound 在手、1在弃牌；MakeTempCardInHandAction 分别 addToHand/addToDiscard，当前 hand.add两次 | 独立模型 bug，不是早拍；覆盖弃牌顺序与溢出 |
| 手牌/非手牌选择坐标系错误 | True Grit 用出牌前 sim.hand 解析出牌后 live hint，造成左移/越界；Headbutt 明确退化为-1，且缺单弃牌自动分支 | 按 choicePile、出牌后候选列表、同名牌 occurrence 与 confirmed 选择统一回放，不猜默认卡 |
| Velvet Choker 跨回合不清零 + flush 基线错帧 | TURN_RESET_COUNTERS 无颈环；flush A before B 时 live 已执行 B 的 relic.onPlayCard，随后 B 影子再+1 | 分开修回合清零与 B 计数前基线，不能删 counter 或放宽 diff |
| SporeCloud / SpireShield 死亡联动与 Vulnerable 生命周期 | 真菌兽死亡 live +2而 sim 无；SpireShield.die 移除 Surrounded/BackAttack；玩家 Vulnerable 轮末-1未接 | 在死亡/轮末对应阶段实现，保留 justApplied 与存活怪条件，不只修单个快照 |
| 失配样本丢弃 choices，通用回放设施不存在 | `ShadowTracker.resolve` 先清 `pendingChoiceKeys/Indices`，`dumpMismatch` 后读；GoldenPositions 只读单局面且字段不全 | Headbutt/Warcry/True Grit 的 dump 不能直接当确定性 oracle；先修证据完整性 |
| 旧相邻回合快照不能承担 v5 命中验收 | 151 对文件缺 turn/pendingMove 全载荷/damageBase/invincibleMax 等，覆盖写目录还混多次运行 | 不硬算旧配对命中率；建立完整版本化快照与严格配对 |

## 3. 三层设计

### 3.1 观测层：只比较真实稳定终态

`ShadowTracker` 与 `AutoPilot` 同名执行等待的稳定门必须共同覆盖：

1. 原生 action/cardQueue/limbo 已排空；
2. 卡牌添加动画与牌堆总数已稳定；
3. `UseCardAction` 的 `onAfterUseCard` 及其排入的尾部动作已经落地。

90 帧或其他兜底到期时，不得把未决状态写成真实 `MISMATCH`、增加 `mismatchSeq` 或触发部署重算。保留明确未决诊断，并允许后续稳定帧完成同一次比较。不能反向吞掉“稳定后仍存在”的差异。

选择证据必须在清理 pending 状态前保存：牌名、下标、来源牌堆、目标、forced/稳定状态与完整三态一起落盘。只记录手牌下标不能表达 Headbutt 的弃牌堆选择。

### 3.2 模型层：每个根因独立合同

- **BeatOfDeath**：按原版 UseCardAction 尾部顺序执行；致死怪物在该尾部点仍是 powers 遍历对象，不添加 `hp>0` 过滤。
- **Heavy Blade**：力量倍率伤害链与普通攻击链一样经过 PenNib 的 `atDamageGive` 语义；不复制另一套加成顺序。
- **升级**：复用 `capture/CardExporter.java` 已执行原版 `makeCopy()+upgrade()` 的机械导出数据，覆盖当前 SimCard 的 cost/baseDamage 变化。费用按原版 `upgradeBaseCost` 保留临时费差，伤害应用升级差值以保留动态成长；16 个改费与34个改伤条目表驱动验收，不做126张人工行为审计，也不写 Madness 特例。
- **弃牌顺序**：整手牌按原版从尾部逐张进入弃牌堆；部分弃牌仍按已选牌的逻辑顺序。测试必须继续穿过 shuffle、下一回合手牌与随机下标，不只断言一个列表。
- **长尾失配**：13:00 后的 Headbutt、True Grit、Velvet Choker、Surrounded/Vulnerable 等，先经稳定样本和原版字节码复核，再逐根因施工；不批量猜测。

### 3.3 验证层：可重放、可配对、可真机复核

1. 完整快照序列化与读取覆盖 ContinuationStamp v5 所需全部字段。严格模式缺字段即失败，不用 0/false 补齐。
2. 通用 MISMATCH DUMP 回放复用同一个 JSON parser：读取 card/target/choices/before/live，重放后比较；forced 或未决样本默认隔离，不冒充正确终态。
3. 相邻回合探针只配对同一次运行、同一 act/floor 的 Tn/Tn+1；搜索 Tn 后取实际选中路线的 Tn+1 continuation，输出 HIT/MISS、首个差异、路线与候选数。
4. 旧路线质量快照继续服务既有 A/B；缺 v5 字段的旧文件不得进入严格 continuation 配对。
5. 真机按 build 冻结日志与快照目录。124214 在 12:56 后的基线为 `SEARCH_REUSED=0/30`；修复后至少要求原修复首差不再出现，并在相应战斗出现真实 `SEARCH_REUSED`，否则不能宣称闭环。

## 4. 并行施工边界

- **Lane A 观测与回放**：`ShadowTracker`、快照格式、测试 JSON 工具、探针与 Gradle 属性。
- **Lane B 已证实模型**：`Hooks`、`DamageCalc`、`Effects`、`CombatFlow` 及各自测试。每个根因独立 RED/GREEN 与 commit。
- **Lane C 长尾复核**：只读分析 13:00 后新失配；Lane A 稳定样本落地后再改对应模型，避免与 A/B 同文件冲突。
- **合流验证**：全量测试、旧路线质量 A/B 防回归、新 continuation 配对、真机复测。

## 5. 非目标

- 不重写 18 键 FinalPlanOrdering、搜索预算、候选池或 Continuation 机制。
- 不加 AdoptRoute、RouteFrozen、实机重同步、失配容忍、强制冻结路线。
- 不用旧快照缺失字段推命中率，不用 forced 样本证明显示修复成功。
- 不借本轮顺带补全部怪物、药水、遗物或 UI  backlog。
