# 镜像覆盖报告（自动生成）

- 卡池：`cards.json`（排除 CURSE / STATUS）
- 注册表：`MirrorRegistry.java`
- 卡牌总数：**346**
- 完整覆盖（原版+升级）：**102**　部分覆盖：11　未覆盖：**233**
- 注册条目：**215**

## RED：75/75

全部已覆盖。

## COLORLESS：38/49

| 卡牌 | 未覆盖原因 |
|---|---|
| Chrysalis | 随机生成能力牌，需要能力牌池 |
| Deep Breath | 洗回弃牌堆（需要洗牌 RNG） |
| Discovery | 三选一（需要选牌界面建模） |
| Expunger | 按条件重复出牌次数 |
| Magnetism | 每回合随机生成无色牌 |
| Mayhem | 每回合自动打出牌堆顶 |
| Omega | 回合结束造成伤害（需要回合末触发器） |
| Panache | 每打出 5 张造成伤害（需要出牌计数器） |
| Purity | 多选消耗（需要多选建模） |
| Sadistic Nature | 施加减益时造成伤害（需要减益触发器） |
| The Bomb | 延迟 3 回合后结算 |

