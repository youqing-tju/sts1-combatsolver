# 2026-11-02 镜像精度与跨回合命中实施计划

> **状态（2026-11-02 14:40）**：Task 1、2、3A、3B、3C、4A、4B、4C、4D、4E、5A、5C(部分)、6 已完成；
> Task 5B（choicePile 坐标）、5C 的 SporeCloud/SpireShield、5D（跨卡 flush 基线）**未做**；
> Task 7 的构建安装已完成（`build 1102-142125`），**真机复测未做**（需重启游戏）；
> Task 8 文档已更新。完成明细见 `docs/handoff-prompt.md` §十四。
>
> **For implementer:** 全程 TDD。每个根因先写失败测试并看到预期失败，再写最小实现。目标内自主调查、修改、测试、构建、安装、真机验证和本地 commit；不 push。
>
> 设计依据：`docs/plans/2026-11-02-mirror-fidelity-design.md`。禁止重同步、删戳字段、放宽比较、冻结路线或砍搜索预算。

## Task 0：冻结证据与基线，禁止混 run

**Files:**
- 只读：`C:/Users/22757/steam/steamapps/common/SlayTheSpire/solver-logs/sts1-combatsolver-2026-11-02.log`
- 只读：`C:/Users/22757/steam/steamapps/common/SlayTheSpire/solver-snapshots/`
- Modify：`记录.txt`（只追加当前基线，不改历史）

1. 记录代码 HEAD、build、日志行数与当前真机 `SEARCH_REUSED / SEARCH_REUSE_MISS`。124214 在 12:56 后口径已知为 `0/30`，若新 run 已产生数据则分开统计，不合并。
2. 将本轮要用的日志与快照复制到按 build/run 命名的冻结目录；不得覆盖游戏目录文件。旧 `turn-*.json` 同名覆盖目录不得作为跨 run 配对输入。
3. 把当前样本分为：稳定真失配、forced/未决、字段不全旧格式。后两类不得作为“修复后 diff=0”的 oracle。
4. 记录基线：当前测试应为 784 绿；HEAD 文档 `870125f`、代码 `a2d82b3`。若执行时 HEAD 已前进，以实际 `git log` 为准并写明。

**Commit:** 不单独提交；证据路径随 Task 1 或后续对应 commit 记录。

## Task 1：完整快照与通用失配回放基础设施

**Files:**
- Modify：`src/main/java/io/github/youqing/sts1solver/sim/SimState.java`（序列化，不改战斗语义）
- Modify：`src/test/java/io/github/youqing/sts1solver/search/GoldenPositions.java`
- Create：`src/test/java/io/github/youqing/sts1solver/search/SnapshotReplayContractTest.java`
- Create：`src/test/java/io/github/youqing/sts1solver/search/MismatchReplayTest.java`
- Modify：`build.gradle.kts`

1. RED：写出含 turn、hpLossCount、turnForcedEnd、potionSlots、完整 MonsterMove/Extra、damageBase、invincibleMax、六路 RNG 的状态，读取后 `ContinuationStamp` 必须逐字相同。当前会缺字段失败。
2. 扩展快照 schema 与 parser。严格模式缺字段直接失败；只有既有 route-quality 读取允许 legacy 默认，continuation/mismatch replay 禁止默认补齐。
3. RED：读取一份稳定 MISMATCH DUMP 的 `card/target/choices/before/live`，从 before 重放后与 live 比较。当前 GoldenPositions 不能读外层三态，且 choices 已丢。
4. 在同一个 parser 上增加 mismatch case 读取，不复制第二套 JSON 解析器。支持 `-Dcase=<file-or-dir>`、`-Dout=<path>`、`-Dlimit=<n>` 转发，并把 MismatchReplayTest 注册进现有 `-Pexperiments` 过滤；forced/明确未决样本默认跳过并单列计数。
5. GREEN：round-trip 与稳定 golden 回放通过；旧 route-quality 快照测试不退化。

**Commands:**
```bash
./gradlew test --offline --tests "*SnapshotReplayContractTest"
./gradlew test --offline -Pexperiments --tests "*MismatchReplayTest" -Dcase=<stable-dump.json>
./gradlew test --offline --tests "*GoldenPositionsTest" --tests "*RouteQualityGoldenTest"
```

**Commit:** `test(verify): add full snapshot and mismatch replay infrastructure`

## Task 2：对拍只在真实稳定终态出结论

**Files:**
- Modify：`src/main/java/io/github/youqing/sts1solver/verify/ShadowTracker.java`
- Modify：`src/main/java/io/github/youqing/sts1solver/verify/AutoPilot.java`（同名执行等待共用 currentAction 屏障）
- Modify：`src/test/java/io/github/youqing/sts1solver/verify/ShadowTrackerQueueTest.java`
- Create：`src/test/java/io/github/youqing/sts1solver/verify/ShadowTrackerSettleTest.java`
- Modify：`src/test/java/io/github/youqing/sts1solver/verify/ShadowTrackerMismatchDumpTest.java`（若现有名称不同，新增对应测试）

1. RED：`actions=[]` 但 `currentAction!=null`（可用 RemoveSpecificPowerAction）时，ShadowTracker 不得 resolve/baseline，AutoPilot 不得出下一手；`currentAction=null` 但 actions 非空同样等待。模拟 Dark Embrace 后第 6–8 帧才排入 BeatOfDeath，落地后只得唯一正确结论。
2. RED：Fiend Fire 多段队列超过 90 帧。到期不得增加 comparisons/mismatches/mismatchSeq，不得触发部署漂移、不得把 live 写成 baseline、不得释放 isIdle；只限频报告 `DIFF WAIT/STUCK` 与未满足 gate，后续稳定帧仍完成本次对拍。forced MATCH 也不计入验收。
3. 查找并移除所有 `resolve(true)` 强制调用路径：90 帧兜底与“下一张牌到达时 flush”都不得在半途产生真实失配。改为明确未决诊断，不能静默吞掉稳定后的差异。
4. ShadowTracker 与 AutoPilot 的稳定门必须把公开字段 `GameActionManager.currentAction` 与 actions/cardQueue/limbo/screen 一起纳入：`getNextAction` 是先把动作移出列表再执行，所以 `actions=[]` 不代表当前 DamageAction/ApplyPowerAction 已落地。覆盖 `onAfterUseCard` 的尾部队列，不用固定毫秒猜测，也不加实机重同步。baseline 捕获复用同一判据。
5. RED：Warcry/Headbutt 的选择在 dump 中必须保留牌名、下标与来源牌堆；当前 resolve 先清空再 dump，choices 为空。
6. 在清 pending 前保存完整选择、target、稳定/forced 状态；未决样本不写伪稳定 oracle。
7. 必测真机时间线：Warcry+ 007 中间态不报、稳定后 hand/draw 差消失；Fiend Fire 008 中间态不报、稳定后只保留真实队列差；Power Through+ 012 稳定后仍报 hand11/10与discard差，证明没有吞真错；Fiend Fire+ 014 的 PenNib 中间态差不进统计，真实 exhaust 差仍报。
8. GREEN：上述时间线和真机已有稳定样本通过；`ShadowTracker.isIdle()` 只在实验真正完成后释放，AutoPilot 等待语义不抢跑。

**Commands:**
```bash
./gradlew test --offline --tests "*ShadowTrackerSettleTest" --tests "*ShadowTrackerQueueTest" --tests "*ShadowTrackerMismatchDumpTest"
./gradlew test --offline --tests "*AutoPilot*"
```

**Commit:** `fix(verify): compare cards only after native play effects settle`

## Task 3：三个独立模型根因，分别 TDD、分别 commit

### 3A BeatOfDeath 致死仍触发

**Files:** `mirror/hooks/Hooks.java`、`src/test/java/.../mirror/hooks/HooksTest.java`、Task 1 的稳定 Bash golden。

1. RED：心脏 2 HP、BeatOfDeath 1、玩家 37 HP/2 block，Bash 致死；期望怪物 0、玩家 block 1。当前 sim block 2。
2. 按 `UseCardAction.java:67-120`：出牌效果后仍遍历怪物 powers 调 `onAfterUseCard`，该点没有 `isDeadOrEscaped` 或 hp 过滤。
3. 只修这个尾部点的存活过滤；不要把其他怪物 onUseCard 钩子一并改成“死者都触发”。
4. GREEN：稳定 `016-Bash+.json` 回放 diff=0；前 7 条由 Task 2 的稳定门处理，不改格挡公式。

**Commit:** `fix(mirror): apply beat of death after lethal card plays`

### 3B Heavy Blade 经过 PenNib 伤害链

**Files:** `mirror/DamageCalc.java`、`src/test/java/.../mirror/DamageCalcTest.java`、稳定 `003-Heavy_Blade.json`。

1. RED：before/sim/live = 35/48/35 的力量倍率样本；普通入口与倍率入口必须有一致 PenNib 结果。
2. 按原版 `HeavyBlade.use` 的 strengthMult → `DamageInfo` → powers `atDamageGive` 顺序复用同一加成链，不另造一套顺序。
3. 保留现有第 9 击挂 PenNibPower、第 10 击移除的生命周期；不重复修 RelicHooks。
4. GREEN：golden 三态吻合，普通攻击/重刃/Vigor/力量顺序回归通过。

**Commit:** `fix(mirror): apply pen nib to strength-scaled attacks`

### 3C Brutality 回合初抽牌与 HP_LOSS

**Files:** `mirror/hooks/PowerHooks.java`、`src/test/java/.../mirror/hooks/PowerHooksTest.java`、`sim/CombatFlow.java`（仅当现有阶段接入确实缺失）。

1. RED：Brutality 2 的 T2，必须在原抽 5 后追加抽 2，再 HP_LOSS 2；当前只挂 power。
2. 挂 `START_OF_TURN_POST_DRAW`，不得挂会被 `energy=energyPerTurn` 覆盖的阶段。
3. RED：同场景带 Self Forming Clay / Rupture，必须继续触发受伤钩子；不能简单 `state.hp -= amount`。
4. 用原始 ID `Brutality`；测试抽牌来源、HP、Next Turn Block、力量及相关顺序。
5. GREEN：对应 `turn-A4F55T2.json` 与自动化通过。Berserk 的 `energy: 3 vs 2` 不归入本项，禁止再次从 Berserk 猜修。

**Commit:** `fix(mirror): implement brutality start-of-turn effects`

## Task 4：升级、随机与牌序，拆成四个独立根因

### 4A 用机械导出数据做通用升级，禁止 Madness 特例补丁

**Files:** `mirror/Effects.java`、必要的升级数据映射类、`src/test/java/.../mirror/ApotheosisTest.java`、`sim/SimStateDiffTest.java`；只读复用 `capture/CardExporter.java:76-100` 与游戏 `solver-data/cards.json`。

1. RED：`004-Apotheosis+.json` 重放，Madness 升级后 cost/costForTurn 必须与 live 一致，RNG 不变；同 dump 的 Strike/Bash/FiendFire/Reaper baseDamage 也必须一致。
2. 复用 CardExporter 已执行原版 `makeCopy()+upgrade()` 的 base/up 数据，不手抄126张表。覆盖其中16个改费、34个改伤条目；缺表或异常显式暴露。
3. 费用按原版 `upgradeBaseCost` 保留 `costForTurn-cost` 差值并夹0，再写回 SimCard 等值哨兵；X费与临时改费不得误伤。baseDamage 应用升级差值，不能用静态 up.dmg 覆盖 Rampage 等动态成长。
4. 表驱动测试覆盖16+34条目；另留 Madness 1→0、Body Slam 1→0、Strike 6→9及动态成长不丢失三个行为夹具。`upgradeOf` 同时服务 Apotheosis 与 Armaments，禁止只修一个调用点。
5. 手牌 diff 改保序；把 `CardPlayerTest.handIsAnUnorderedMultisetForDiff` 改成换序必报差的反向合同。
6. GREEN：Apotheosis 全堆、curse/status 不可升级、卡面费用/伤害与 diff 顺序测试通过。

**Commit:** `fix(mirror): preserve exact upgraded card state and hand order`

### 4B Madness 持续重抽直到成功

**Files:** `mirror/Effects.java`、`src/test/java/.../mirror/MadnessTest.java`。

1. RED：第一次随机下标是不可降费牌、下一项才可降；第一次调用后必须再抽并成功，不能只抽一次。
2. RED：前 size 项均不可降、下一项可降；不得被自造 size 上限静默终止。
3. 不可降集合恒非空（手牌全 0 费）时不掷骰且无事发生；守护只能防异常死循环，不得改变正常候选分布。
4. GREEN：cost 与 costForTurn 同步、RNG 次数、分布与既有 `66c39db` 合同全部通过。

**Commit:** `fix(mirror): retry madness until an eligible card is selected`

### 4C 整手牌按原版逆序进入弃牌堆

**Files:** `sim/CombatFlow.java`、`src/test/java/.../sim/CombatFlowTest.java`、`search/BeamSolverPileOrderTest.java`（或现有对应类）。

1. RED：`[a,b,c,d,e] → discard [e,d,c,b,a]`；部分弃牌仍保留选择逻辑顺序。
2. 继续测试 shuffle、下一回合手牌与同一随机下标落点，不能只断言 discard 一个列表。
3. 用 `turn-A1F5T3.json` 等冻结配对复核 `[Curse,Wound,Wound]` 顺序；禁止删 RNG 或手牌字段制造命中。

**Commit:** `fix(sim): discard the whole hand from the top card down`

### 4D Fiend Fire 先完成本批消耗，再执行抽牌钩子

**Files:** `mirror/Effects.java`、`sim/CombatFlow.java`（如需要队列语义）、对应 FiendFire/DarkEmbrace 测试与008/014稳定 before。

1. RED：按008/014断言 exhaust 只含原手牌+Fiend Fire；新抽的 Defend_R(1)/Wound/Bloodletting+ 不得进入本批随机消耗池。只断言 hand=9/10不够。
2. 按原生 FiendFireAction/ExhaustAction/DarkEmbracePower：先确定并执行完整批 ExhaustAction，再执行 addToBot 的 DrawCardAction；不得边随机消耗边同步抽牌。
3. 保持 RNG 次数、原手牌消耗顺序与后续抽牌一致；hand/draw 数量早拍由 Task 2 验收，身份队列由本项验收。

**Commit:** `fix(mirror): finish fiend fire exhausts before draw triggers`

### 4E 原生手牌上限与溢出入弃牌堆

**Files:** `mirror/Effects.java`（addStatusToHand及同类入口）、`src/test/java/.../mirror/CardPlayerTest.java`、012稳定样本。

1. RED：打出后原手牌9，再生成2 Wound；期望 hand10，手内新增1 Wound，弃牌新增1 Wound，弃牌顺序 `[Wound, Power Through+]`。当前 hand11。
2. 按 `MakeTempCardInHandAction` 计算 overflow 并分别 addToHand/addToDiscard；审计同类生成手牌入口，不把修复写成 Power Through 卡名特例。
3. GREEN：空手牌旧测试不退化，012回放与溢出/非溢出边界通过。

**Commit:** `fix(mirror): overflow generated cards to discard at ten hand size`

## Task 5：13:00 后长尾按已证实根因分组施工

### 5A Velvet Choker 回合清零

**Files:** `mirror/hooks/RelicHooks.java`、`src/test/java/.../mirror/hooks/RelicHooksTest.java`。

1. RED：T1 打3张后跨到 T2，counter 必须从3归0，T2 第7张才再次拒绝；当前 TURN_RESET_COUNTERS 漏项会跨回合累计。
2. 按 `VelvetChoker.atTurnStart: counter=0` 加入既有清零集合，不新增状态通道。
3. 不通过删除 `relics.Velvet Choker` 字段、忽略 counter 或放宽 diff 过关。

**Commit:** `fix(mirror): reset velvet choker at turn start`

### 5B 统一手牌与非手牌选择坐标

**Files:** `verify/ShadowTracker.java`、`mirror/Effects.java`、`mirror/CardMirror.java`（若需 choicePile 元数据）、`exec/GridSelector.java`、对应 TrueGrit/Headbutt/Warcry 测试与稳定 dump。

1. RED True Grit：出牌前 `[Strike,Strike,TG,Defend,Warcry]`，live confirmed 选择出牌后下标0的 Strike；当前用出牌前 sim 左移/越界。
2. RED Headbutt：confirmed 目标是弃牌堆第2张，下一张必须从弃牌堆置抽牌堆顶；当前 choiceIndices 只查 hand 并退回-1。
3. 记录 choicePile（HAND/DRAW/DISCARD/EXHAUST）、同名牌 occurrence、choiceIndex 与 confirmed 目标；按“出牌后候选列表”解析 live hint。元数据只描述原版选择来源，不发明选择规则。
4. Headbutt 单弃牌时原版自动选择也要覆盖；GridSelector 找不到 confirmed 时重试同一名义目标，不能把两个同 ID 等同物混成不同选择。
5. `005-Headbutt` 的终局 hand-2/discard+1 仍归 Task 2 的 currentAction 屏障，不能拿它否定稳定选择样本。

**Commit:** `fix(verify): replay card choices in their original pile coordinates`

### 5C 死亡联动与轮末 Vulnerable

**Files:** `mirror/DamageCalc.java`、`sim/CombatFlow.java`、`sim/MonsterMoves.java`、对应 FungiBeast/SpireShield/Vulnerable 测试。

1. RED Spore Cloud：两只存活 FungiBeast 时杀死目标，其余存活者 Vulnerable +2；只挂怪物 Side/Moves，不全局查 type。
2. RED SpireShield：die 后移除玩家 Surrounded 与 BackAttack；按原版阶段接入，不在帧末另造清理机制。
3. RED Vulnerable：玩家轮末 -1，历史 justApplied 与存活怪物修正保持原顺序；与 Bash 施加时序分开测试。
4. 三项分别跑稳定 dump/合成合同，必要时分别 commit，禁止把总场 MISMATCH 数下降当唯一验收。

**Commit:** `fix(sim): restore death cleanup and end-of-round vulnerable ticks`

### 5D 跨卡 flush 锚定到下一张牌计数前

**Files:** `verify/ShadowTracker.java`、必要的原生 patch/测试。

1. RED：A 仍 pending 时 B 到达；原生 `AbstractPlayer.useCard` 已先执行 relic.onPlayCard，B 的 live 基线已含 B 计数，A/B 同算一次后必然出现 A少1、B多1。
2. 让旧卡比对与当前卡基线锚定到 B 的 onPlayCard 前同一状态，或在更早原生点冲刷；不得在 B 已计数后强制比 A 再把该状态当 B 的 before。
3. 不用忽略 counter/放宽 diff 掩盖；与 Task 2 的“90帧不得出假失配”同时通过。

**Commit:** `fix(verify): flush pending comparisons before the next card hooks`

**每项命令：**
```bash
./gradlew test --offline --tests "*<RootCauseTest>"
./gradlew test --offline -Pexperiments --tests "*MismatchReplayTest" -Dcase=<stable-dump.json>
```

## Task 6：严格相邻回合 Continuation 配对探针

**Files:**
- Create：`src/test/java/io/github/youqing/sts1solver/search/ContinuationPairProbeTest.java`
- Modify：`build.gradle.kts`
- 复用：`BeamSolver.lastContinuations`、`ContinuationStamp`、Task 1 完整快照

1. RED：Tn 搜索选出的最终路线没有 Tn+1 continuation 时必须报 anchor_missing，不得当 HIT。
2. 只配同一次运行、同 act/floor 的相邻 turn；完整字段缺失即拒绝该样本并计数，不填默认值。
3. 对 Tn 跑真实配置，取最终 selected 的 Tn+1 stamp，与 Tn+1 `captureLive` 比 canonicalText；输出 HIT/MISS、reason、firstDifference、route、finalPool、时间。
4. 支持 `-DbatchPair=<frozen-dir>`、`-Dout=<tsv>`、`-Dlimit=<n>`；同代码连跑两次结果必须一致，否则先查计时/集合顺序。
5. 旧 151 对缺字段文件只能报 `incomplete_schema`，不得纳入命中率分母。
6. 真机 124214 的 `0/30` 是日志基线；新离线基线必须来自 Task 1 后冻结的新快照，不能倒填。

**Commands:**
```bash
./gradlew test --offline --tests "*ContinuationPairProbeTest" -DbatchPair=<frozen-dir> -Dout=<pair.tsv>
```

**Commit:** `test(search): replay adjacent-turn continuation pairs`

## Task 7：全量回归、构建安装与真机验收

1. 全量自动化：
```bash
./gradlew test --offline
```
预期在当前 784 基础上增加；0 fail / 0 err，skip 仅既有探针。
2. 路线质量防回归：对同一批旧快照跑既有 route-quality A/B，报告胜负、收尾 HP、路线变化；模型修正导致路线变化要归因，不预设“完全不变”。
3. 构建安装：
```bash
./gradlew jar --offline
powershell -File tools/install.ps1
```
重启 MTS，日志确认新 build；旧进程不得当新包验收。
4. 真机至少覆盖：普通多回合战斗、整手牌弃牌+洗牌、Madness/Apotheosis、Brutality+黏土、Heavy Blade+PenNib、腐化之心 BeatOfDeath、Fiend Fire 长队列。
5. 每项记录：稳定对拍首差、是否仍 forced 假 MISMATCH、续跑 HIT/MISS、实际 HP/block/牌堆/Power/RNG。修复字段不得再成为首差；相应战斗仍 `SEARCH_REUSED=0` 就不能写闭环。
6. 真机战绩与命中率分开报告；赢了不等于镜像准，SEARCH_REUSED 增加也不等于路线质量提升。

**Commit:** `docs: record mirror fidelity and continuation verification`

## Task 8：文档与交付

- 更新 `docs/handoff-prompt.md`：新 HEAD、测试数、build、每个根因的自动化/回放/真机状态、剩余首差。
- 更新 `docs/research/blueprint-parity.md`：只登记已验证对应关系；未做的仍写未做。
- 更新 `记录.txt`：追加本轮经验，尤其“稳定对拍先于镜像猜测”“致死仍执行 onAfterUseCard”“手牌顺序是随机语义”“快照缺字段不能算命中率”。
- `git diff --check`、工作区状态、commit 列表与回退点写清楚。未验证项不得写成已完成，不 push。
