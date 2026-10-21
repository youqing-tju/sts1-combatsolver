# 镜像覆盖报告（自动生成）

- 卡池：`cards.json`（排除 CURSE / STATUS）
- 注册表：`MirrorRegistry.java`
- 卡牌总数：**346**
- 完整覆盖（原版+升级）：**84**　部分覆盖：8　未覆盖：**254**
- 注册条目：**176**

## RED：75/75

全部已覆盖。

## COLORLESS：17/49

| 卡牌 | 未覆盖原因 |
|---|---|
| Apotheosis | 升级牌组里所有牌（含抽牌堆/弃牌堆） |
| Beta | 形态链中间态（转为 Omega） |
| Chrysalis | 随机生成能力牌，需要能力牌池 |
| Deep Breath | 洗回弃牌堆（需要洗牌 RNG） |
| Discovery | 三选一（需要选牌界面建模） |
| Enlightenment | 本回合手牌费用降为 1（需要费用修改器） |
| Expunger | 按条件重复出牌次数 |
| Forethought | 把手牌放到底部（需要底部插入） |
| Ghostly | 形态链中间态（转为 Intangible） |
| HandOfGreed | 击杀获得金币（金币不在战斗模型内） |
| J.A.X. | （待补原因/可补） |
| Jack Of All Trades | 随机生成无色牌，需要无色牌池 |
| Madness | 随机一张手牌费用归零 |
| Magnetism | 每回合随机生成无色牌 |
| Mayhem | 每回合自动打出牌堆顶 |
| Metamorphosis | 随机生成攻击牌，需要攻击牌池 |
| Miracle | （待补原因/可补） |
| Omega | 回合结束造成伤害（需要回合末触发器） |
| Panache | 每打出 5 张造成伤害（需要出牌计数器） |
| PanicButton | （待补原因/可补） |
| Purity | 多选消耗（需要多选建模） |
| RitualDagger | 击杀永久成长 |
| Sadistic Nature | 施加减益时造成伤害（需要减益触发器） |
| Safety | 卡牌文本待核实 |
| Secret Technique | 从抽牌堆检索技能牌 |
| Secret Weapon | 从抽牌堆检索攻击牌 |
| Slimed | （待补原因/可补） |
| The Bomb | 延迟 3 回合后结算 |
| Thinking Ahead | 抽 2 后放回牌堆顶（可做，待接线） |
| ThroughViolence | 卡牌文本待核实 |
| Transmutation | X 费 + 随机生成无色牌 |
| Violence | 从抽牌堆检索 3 张攻击牌 |

