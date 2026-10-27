# M5 真机验收流程（20 场全自动）

> 目标判据：**全自动跑满 20 场战斗无异常 → 仓库转 public + MIT**。
> 本文只写「怎么跑、抓哪些数、什么算过、什么算噪声」，不重复设计。

---

## 一、前置（一次性）

| 项 | 检查方式 | 期望 |
|---|---|---|
| 游戏目录 | `C:\Users\22757\steam\steamapps\common\SlayTheSpire` | 有 `desktop-1.0.jar` |
| ModTheSpire | `<game>\mods\BaseMod.jar` + `STS1CombatSolver.jar` | **只加载这两个**（`%LOCALAPPDATA%\ModTheSpire\mod_lists.json` 的 `<Default>` 列表） |
| jar 来源 | `<repo>\.sts1-combatsolver\build\libs\STS1CombatSolver.jar` → 复制到 `mods\` | 时间戳比最后一次 commit 新 |
| 开局自动接管 | `%LOCALAPPDATA%\ModTheSpire\sts1combatsolver\config.properties` | `autoStart=true` |
| 预算档 | 同上 `budget=` | **当前设为 `VERY_HIGH`**（宽 135 / 50 万 / 300s），为的是与 10-23 的历史数据可比；跑 20 场嫌慢就降到 `MEDIUM`（宽 60 / 12 万 / 120s），但降档后耗时要单独记 |
| Steam | 必须先**登录**（有 `\\.\pipe\SteamClient` 才说明真连上了） | 启动器报 "Could not connect to Steam" = Steam 没登录，不是 mod 问题 |

启动：双击 `<game>\MTS-launch.cmd`（或 `jre\bin\java.exe -jar mts-launcher.jar`，工作目录必须是游戏根）。
**启动器不是从 Steam 里点起来的也没关系**，它自己连 Steam 客户端 API。

---

## 二、跑一场

1. 启动器起来后进游戏 → 单人游戏 → 铁甲战士 → 标准模式 → 出发。
2. 进战斗后 `autoStart=true` 会**自动接管**（面板上 `AUTO ON`），逐张自动出牌。
3. 战斗之间（奖励 / 地图）仍需手动点 —— **这部分不在 combat solver 的范围内**。
4. 每场战斗结束，日志会写一行 `DIFF SUMMARY a match / b mismatch / c total`。

日志：`<game>\solver-logs\sts1-combatsolver-YYYY-MM-DD.log`（每次启动追加到当天文件）。

---

## 三、抓哪些数（一条命令）

```powershell
powershell -ExecutionPolicy Bypass -File tools\watch-solver.ps1
```

输出就是验收要的六个数：

| 字段 | 含义 | 验收看什么 |
|---|---|---|
| `combats` | 捕获到的战斗场数 | 20 场 = 这一项到 20 |
| `plans` | 规划次数（每回合/每次重规划一次） | 每场 3–8 次属正常 |
| `plan ms avg/max` | 单次规划耗时 | `MEDIUM` 档 avg < 1.5s、max < 3s 视为健康 |
| `diff MATCH/MISMATCH` | 影子 vs 真机逐字段对拍 | **不要求 100%**，见「噪声」 |
| `rejected` | `ActionRejected` 次数 | 必须 **0**（出现就是「搜索以为能打、真机打不出」） |
| `errors` | `[ERROR]` 行数 | 必须 **0** |

退出码：0 = 无 ERROR，2 = 有 ERROR（可直接串进脚本）。

实时盯：`powershell -File tools\watch-solver.ps1 -Follow`

---

## 四、什么算「噪声」，什么算「缺陷」

已知**必然**不一致的两类，统计时单独拎出来，不算失败：

1. **随机生成牌**：`Infernal Blade` / `发现` / `转化` / `死灵诅咒` 之类。
   真机生成的是具体牌，影子拿不到随机结果 —— 这一类在 10-23 的数据里占了 mismatch 的一半。
2. **`(forced after 90f)`**：diff 在动画未收尾时被迫取快照，`discard.size` / `cards` 会差 1。
   这类是**采样时机**问题，不是模型问题。

**必须当缺陷处理**的（看到就停下来查）：
- `relics.<X>` 不一致 —— 遗物钩子算错了（例：10-23 的 `relics.Kunai: 0 vs 1`）；
- `energy` 差 1 且不是 X 费牌 —— 能量结算错；
- `powers.*` 层数不一致 —— 能力生效时序错；
- 任何 `ActionRejected` / `[ERROR]`。

---

## 五、历史基线（2026-10-23，作为对照）

| 指标 | 值 |
|---|---|
| 战斗场数 | 30 |
| DIFF | MATCH 208 / MISMATCH 48（81.25%） |
| 规划次数 | 115 |
| 规划耗时 | avg 647ms / max 2801ms |
| ActionRejected | 0 |
| ERROR | 0 |

10-23 之后补的东西（怪物 66/66、药水 31、遗物 52、手牌被动、神器、Vigor…）都在**影子侧**，
真机对拍的一致率应该**不低于** 81.25%；如果反而更低，说明新代码引入了偏差。

---

## 六、跑完 20 场之后

1. `watch-solver.ps1` 六个数为 0 错误 → 把输出贴进 `CHANGELOG.md` 的 M5 条目。
2. 仓库转 public + 加 MIT LICENSE（`docs/plans` 里 M0-M5 计划的收尾项）。
3. 若中途出现 ERROR：**先停**，把 `-Follow` 抓到的行 + 当时 `hand` / `energy` 贴回来，
   按「机制 → 伤害 → 能量 → 遗物」顺序定位，不要边跑边改（会把噪声混进数据）。
