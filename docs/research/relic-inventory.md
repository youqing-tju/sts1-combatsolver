# 遗物镜像盘点（2026-10-26）

> 口径：`lib/desktop-1.0.jar` 里共 **190** 个遗物类（含 `Test*`、`deprecated/*`）。
> 「战斗中是否改数值」按**字节码**判断，不按卡面描述。
> 自动扫描脚本见本文件末尾。

## A. 已镜像（47 件，`RelicHooks` / `CardPlayer` / `CombatFlow` / `DamageCalc`）

| 分类 | 遗物 |
|---|---|
| 计数类（`onUseCard`） | `Nunchaku` `Ornamental Fan` `Shuriken` `Kunai` `Pen Nib` `Letter Opener` `InkBottle` |
| 受伤触发（`wasHPLost` / `onBloodied`） | `Runic Cube` `Centennial Puzzle` `Self Forming Clay` `Red Skull` `TungstenRod` |
| 回合钩子 | `Mercury Hourglass` `HornCleat` `CaptainsWheel` `Orichalcum` `Happy Flower` `Incense Burner` `Pocketwatch` `Art of War` `StoneCalendar` |
| 消耗 / 洗牌 / 击杀 | `Charon's Ashes` `Sundial` `TheAbacus` `Gremlin Horn` |
| 回血 / 减益免疫 | `Magic Flower` `Toy Ornithopter` `Mark of the Bloom` `Ginger` `Turnip` |
| 能量 | `Ice Cream` `Chemical X` `Velvet Choker` |
| 倍率 | `Paper Frog`（玩家→敌人易伤 1.5→1.75）`Odd Mushroom`（敌人→玩家易伤 1.5→1.25）`Paper Crane`（敌人虚弱 0.75→0.6） |
| 其它 | `Torii` `Calipers` `Runic Pyramid` `Snecko Eye` `Blue Candle` `Medical Kit` `Mummified Hand` `OrangePellets` `Brimstone` `Necronomicon` `Bird Faced Urn` `Lizard Tail` |

## B. 开局型（效果已被快照吃掉，不需要额外代码）

`atBattleStart` / `atPreBattle` 一次性生效的，全部在 `SimState.capture` 里体现为
「力量 / 敏捷 / 格挡 / 减益 / 怪物血量 / 抽牌堆」——**再做一遍就是重复计**。

`Anchor` `Bag of Marbles` `Bag of Preparation` `Vajra` `Blood Vial` `Bronze Scales`
`ClockworkSouvenir` `CultistMask` `Du-Vu Doll` `FossilizedHelix` `Girya` `GremlinMask`
`Lantern` `Ancient Tea Set` `Mark of Pain` `MutagenicStrength` `NeowsBlessing`
`Oddly Smooth Stone` `Pantograph` `Philosopher's Stone` `PreservedInsect` `Red Mask`
`Thread and Needle` `TwistedFunnel` `Bottled Flame` `Bottled Lightning` `Bottled Tornado`
`Burning Blood` `Ectoplasm`

## C. 纯局外（不碰战斗数值）

`Astrolabe` `Black Blood` `Black Star` `Busted Crown` `Calling Bell` `Cauldron`
`CeramicFish` `Coffee Dripper` `Darkstone Periapt` `Discerning Monocle` `DollysMirror`
`DreamCatcher` `Empty Cage` `Eternal Feather` `FaceOfCleric` `Frozen Egg 2` `Fusion Hammer`
`Golden Idol` `GoldenEye` `Juzu Bracelet` `Mango` `Matryoshka` `Maw Bank` `MealTicket`
`Meat on the Bone` `Membership Card` `Molten Egg 2` `Nloth's Gift` `Omamori` `Orrery`
`Pandora's Box` `Peace Pipe` `Pear` `Potion Belt` `Prayer Wheel` `PrismaticShard`
`Question Card` `Red Circlet` `Regal Pillow` `Runic Dome` `SacredBark` `Shovel`
`Singing Bowl` `Slaver's Collar` `Smiling Mask` `Sozu` `SsserpentHead` `Strawberry`
`The Courier` `Tiny Chest` `Tiny House` `Toxic Egg 2` `War Paint` `Whetstone`
`White Beast Statue` `Wing Boots` `Lee's Waffle` `Bloody Idol` `Spirit Poop`
`FrozenEye`（只影响看牌顺序）`Toolbox` `StrikeDummy` `HandDrill` `Tingsha`
`Tough Bandages` `Wrist Blade` `Snecko Skull` `Champion's Belt`

## D. 别的角色专属（铁甲战士拿不到）

`Cracked Core` `DataDisk` `GoldPlatedCables` `Nuclear Battery` `Symbiotic Virus`
`Runic Capacitor` `Emotion Chip` `Inserter` `FrozenCore` `Yang` `TeardropLocket`
`PureWater` `HolyWater` `VioletLotus` `Damaru` `Melange` `HoveringKite`
`Ring of the Serpent` `CloakClasp` `The Specimen`

## E. 明确未做（有战斗效果、但缺容器；写在这里而不是偷偷跳过）

| 遗物 | 缺什么 |
|---|---|
| `Akabeko` / `Sling` | **Vigor**（下一次攻击 +8/+2）。要在「真正结算伤害」时消耗，而 `DamageCalc.attackDamage` 是纯函数（估值与意图展示都会调它）—— 得先把两条路分开 |
| `Boot` | 需要「这一击的伤害类型」才能排除 THORNS/HP_LOSS；怪物侧 `DamageCalc.hit` 目前不区分 |
| `WarpedTongs` | `UpgradeRandomCardAction`：升级一张随机手牌，需要**卡牌升级表**（升级后的费用/伤害），我们没有卡牌数据库 |
| `GamblingChip` | 每个回合开始让玩家选「弃几张再抽几张」—— 是**玩家决策**，不是自动效果 |
| `Dead Branch` / `Nilry's Codex` / `Enchiridion` | 随机生成牌，需要**牌池枚举**（`AbstractDungeon` 的四张卡池） |
| `Unceasing Top` | 空手牌时抽 1 —— 触发点在「手牌变空」这个事件上，目前没有这个事件 |
| `Champion's Belt` | 给敌人上易伤时附带 1 虚弱；`ApplyPowerAction` 那条统一入口还没建（现在是逐处 `powers.put`） |
| `Strange Spoon` | 消耗改成 50% 弃置 —— 需要 `miscRng`（或它用的那条流），且与 `Effects.exhaustCard` 的调用点耦合 |

## 自动扫描脚本

```python
import subprocess, re, zipfile
JAVAP = r"C:\Program Files\Java\jdk-24\bin\javap.exe"
z = zipfile.ZipFile('lib/desktop-1.0.jar')
HOOKS = ('onUseCard','onPlayCard','atTurnStart','atTurnStartPostDraw','atEndOfTurn',
         'onPlayerEndTurn','onAttacked','onAttackedToChangeDamage','onAttackToChangeDamage',
         'wasHPLost','onLoseHp','onExhaust','onMonsterDeath','onShuffle','atPreBattle',
         'atBattleStart','atDamageGive','onPlayerHeal','onBloodied','onNotBloodied','canPlay')
for n in z.namelist():
    if not (n.startswith('com/megacrit/cardcrawl/relics/') and n.endswith('.class')) or '$' in n:
        continue
    cls = n[:-6].replace('/', '.')
    out = subprocess.run([JAVAP, '-p', '-c', '-cp', 'lib/desktop-1.0.jar', cls],
                         capture_output=True, text=True).stdout
    methods = re.findall(r'^  (?:public|protected|private) [\w\.\[\]<>]+ (\w+)\(', out, re.M)
    hooks = sorted(set(m for m in methods if m in HOOKS))
    if hooks:
        print(cls.rsplit('.', 1)[1], hooks)
```

⚠️ 「没有钩子方法」**不等于**「战斗中无关」：`OddMushroom` / `PaperCrane` / `Champion's Belt` /
`StrangeSpoon` 都没有钩子方法，但被判定的类（`VulnerablePower` / `WeakPower` /
`ApplyPowerAction` / `UseCardAction`）直接读它们的 ID。所以还要再做一遍
「这个遗物 ID 被哪些类引用」的扫描（`zipfile` + `bytes` 搜索即可）。
