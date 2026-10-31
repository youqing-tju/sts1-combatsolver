package io.github.youqing.sts1solver.mirror;

import io.github.youqing.sts1solver.sim.SimCard;
import io.github.youqing.sts1solver.sim.SimMonster;
import io.github.youqing.sts1solver.sim.SimState;

/**
 * Factory for the atomic effects cards are built from. Keeping these tiny and explicit
 * means a card definition reads like the card text.
 */
public final class Effects {

    /** 手牌上限，见 docs/research/mechanics-reference.md §6（A｜字节码 bipush 10）。 */
    public static final int HAND_LIMIT = 10;

    private Effects() {
    }

    public static Effect damage(final int base) {
        return new Effect() {
            @Override
            public void applyTo(SimState state, int targetIndex, SimCard played) {
                SimMonster target = requireTarget(state, targetIndex);
                DamageCalc.hit(state, target, DamageCalc.attackDamage(state, base, target, played));
            }

            @Override
            public boolean needsMonsterTarget() {
                return true;
            }

            @Override
            public String toString() {
                return "damage(" + base + ")";
            }
        };
    }

    /** 多段攻击：每段单独结算，力量与易伤逐段生效。 */
    public static Effect damageTimes(final int times, final int base) {
        return new Effect() {
            @Override
            public void applyTo(SimState state, int targetIndex, SimCard played) {
                SimMonster target = requireTarget(state, targetIndex);
                for (int i = 0; i < times; i++) {
                    DamageCalc.hit(state, target, DamageCalc.attackDamage(state, base, target, played));
                }
            }

            @Override
            public boolean needsMonsterTarget() {
                return true;
            }

            @Override
            public String toString() {
                return "damage(" + base + "x" + times + ")";
            }
        };
    }

    /** 群体攻击：对每个存活怪物各结算一次。 */
    public static Effect damageAll(final int base) {
        return new Effect() {
            @Override
            public void applyTo(SimState state, int targetIndex, SimCard played) {
                for (SimMonster m : state.monsters) {
                    if (m.hp > 0) {
                        DamageCalc.hit(state, m, DamageCalc.attackDamage(state, base, m, played));
                    }
                }
            }

            @Override
            public String toString() {
                return "damageAll(" + base + ")";
            }
        };
    }

    public static Effect block(final int base) {
        return new Effect() {
            @Override
            public void applyTo(SimState state, int targetIndex, SimCard played) {
                io.github.youqing.sts1solver.mirror.hooks.Hooks.gainBlock(
                        state, DamageCalc.blockAmount(state, base));
            }

            @Override
            public String toString() {
                return "block(" + base + ")";
            }
        };
    }

    /**
     * 抽牌 —— **转交给 {@link io.github.youqing.sts1solver.sim.CombatFlow#drawCards}**。
     *
     * <p>以前这里自己搬牌，绕过了混乱（Snecko Eye）的费用重掷 / 虚空扣能量 / Evolve 连锁 /
     * No Draw —— 真机上每抽一张牌都差一次 cardRandomRng（2026-10-31 一轮 13 次失配）。
     * 抽牌逻辑只允许有一份。
     */
    public static Effect draw(final int count) {
        return new Effect() {
            @Override
            public void applyTo(SimState state, int targetIndex, SimCard played) {
                io.github.youqing.sts1solver.sim.CombatFlow.drawCards(state, count);
            }

            @Override
            public String toString() {
                return "draw(" + count + ")";
            }
        };
    }

    /** 把这张牌的一份复制放进弃牌堆（愤怒）。 */
    public static Effect copySelfToDiscard() {
        return new Effect() {
            @Override
            public void applyTo(SimState state, int targetIndex, SimCard played) {
                if (played == null) {
                    return;
                }
                state.discard.add(new SimCard(played.id, played.upgraded, played.cost));
            }

            @Override
            public String toString() {
                return "copySelfToDiscard()";
            }
        };
    }

    /** 对指定怪物施加状态。 */
    public static Effect powerOnTarget(final String id, final int amount) {
        return new Effect() {
            @Override
            public void applyTo(SimState state, int targetIndex, SimCard played) {
                SimMonster target = requireTarget(state, targetIndex);
                if (io.github.youqing.sts1solver.mirror.hooks.Hooks.isDebuff(id, amount)) {
                    // 🔴 卡牌上的减益必须和药水走同一条门，否则「神器」形同不存在
                    if (!applyDebuff(state, target, id, amount)) {
                        return;     // 被神器吃掉：这次施加整条作废（Sadistic 也不该响）
                    }
                } else {
                    add(target.powers, id, amount);
                }
                io.github.youqing.sts1solver.mirror.hooks.Hooks.onPowerApplied(
                        state, id, amount, target);
            }

            @Override
            public boolean needsMonsterTarget() {
                return true;
            }

            @Override
            public String toString() {
                return id + "(" + amount + ")";
            }
        };
    }

    /** 对所有存活怪物施加状态（闪电霹雳）。 */
    public static Effect powerOnAll(final String id, final int amount) {
        return new Effect() {
            @Override
            public void applyTo(SimState state, int targetIndex, SimCard played) {
                for (SimMonster m : state.monsters) {
                    if (m.hp <= 0) {
                        continue;
                    }
                    if (io.github.youqing.sts1solver.mirror.hooks.Hooks.isDebuff(id, amount)) {
                        if (!applyDebuff(state, m, id, amount)) {
                            continue;   // 被神器吃掉
                        }
                    } else {
                        add(m.powers, id, amount);
                    }
                    io.github.youqing.sts1solver.mirror.hooks.Hooks.onPowerApplied(
                            state, id, amount, m);
                }
            }

            @Override
            public String toString() {
                return id + "(all," + amount + ")";
            }
        };
    }

    /** 对玩家自己施加状态。 */
    public static Effect powerOnPlayer(final String id, final int amount) {
        return new Effect() {
            @Override
            public void applyTo(SimState state, int targetIndex, SimCard played) {
                add(state.playerPowers, id, amount);
            }

            @Override
            public String toString() {
                return id + "(self," + amount + ")";
            }
        };
    }

    /**
     * 对玩家施加一个「层数之外还带伤害」的状态（炸弹、神气制胜）。
     *
     * <p>原版这两个 power 的 {@code amount} 是倒计时/计数，伤害在私有字段里，
     * 所以影子得把伤害单独记到 {@link SimState#powerAux}，否则算不出正确数值。
     */
    /**
     * 把能力**置成**这个层数（不累加），对应原版那些「不叠层」的能力。
     *
     * <p>典型是 {@code NoDrawPower}：构造器里 {@code this.amount = -1} 写死，
     * 再打一张战斗专注也不会变成 -2。以前用 {@link #powerOnPlayer} 累加，
     * 影子里就变成 -2，和实机的 -1 每回合对不上。
     */
    public static Effect powerOnPlayerExact(final String id, final int amount) {
        return new Effect() {
            @Override
            public void applyTo(SimState state, int targetIndex, SimCard played) {
                state.playerPowers.put(id, Integer.valueOf(amount));
            }

            @Override
            public String toString() {
                return id + "(self:=" + amount + ")";
            }
        };
    }

    public static Effect powerOnPlayerAux(final String id, final int amount,
                                          final String auxKey, final int auxValue) {
        return new Effect() {
            @Override
            public void applyTo(SimState state, int targetIndex, SimCard played) {
                add(state.playerPowers, id, amount);
                state.powerAux.put(auxKey, auxValue);
            }

            @Override
            public String toString() {
                return id + "(self," + amount + "," + auxKey + "=" + auxValue + ")";
            }
        };
    }

    // ---- 第三批需要的原语 ------------------------------------------------

    /**
     * 自伤：无视格挡，直接扣血（放血、Offering、血液沸腾）。
     *
     * <p>走的是**同一条** {@code hitPlayer}（原版 {@code LoseHPAction} 就是
     * {@code target.damage(new DamageInfo(source, amount, HP_LOSS))}），只是把类型传成
     * {@link io.github.youqing.sts1solver.sim.DamageKind#HP_LOSS}：
     * 跳过格挡与鸟居，但仍然吃**无实体**（压成 1）与**钨棒**（减 1）。
     */
    public static Effect loseHp(final int amount) {
        return new Effect() {
            @Override
            public void applyTo(SimState state, int targetIndex, SimCard played) {
                int lost = io.github.youqing.sts1solver.sim.CombatFlow.hitPlayer(
                        state, amount, null,
                        io.github.youqing.sts1solver.sim.DamageKind.HP_LOSS);
                // RupturePower.wasHPLost：自己掉血（info.owner == owner）且**实际掉的血 > 0**
                // 时，+amount 点力量。用「实际掉的血」而不是卡面数值 —— 被钨棒抹成 0 时不该触发。
                if (lost > 0) {
                    Integer rupture = state.playerPowers.get("Rupture");
                    if (rupture != null && rupture > 0) {
                        Integer str = state.playerPowers.get(DamageCalc.STRENGTH);
                        state.playerPowers.put(DamageCalc.STRENGTH,
                                (str == null ? 0 : str) + rupture);
                    }
                }
            }

            @Override
            public String toString() {
                return "loseHp(" + amount + ")";
            }
        };
    }

    public static Effect heal(final int amount) {
        return new Effect() {
            @Override
            public void applyTo(SimState state, int targetIndex, SimCard played) {
                state.hp = Math.min(state.maxHp, state.hp
                        + io.github.youqing.sts1solver.mirror.hooks.RelicHooks
                                .healAmount(state, amount));
                io.github.youqing.sts1solver.mirror.hooks.RelicHooks.syncRedSkull(state);
            }

            @Override
            public String toString() {
                return "heal(" + amount + ")";
            }
        };
    }

    public static Effect gainEnergy(final int amount) {
        return new Effect() {
            @Override
            public void applyTo(SimState state, int targetIndex, SimCard played) {
                state.energy += amount;
            }

            @Override
            public String toString() {
                return "energy(+" + amount + ")";
            }
        };
    }

    /** 把状态牌塞进手牌（硬撑 Power Through：2 张伤口）。手牌插入位置是确定的。 */
    public static Effect addStatusToHand(final String id, final int count) {
        return new Effect() {
            @Override
            public void applyTo(SimState state, int targetIndex, SimCard played) {
                for (int i = 0; i < count; i++) {
                    // 类型必须标 STATUS：进化（Evolve）是「抽到 STATUS 就再抽」，
                    // 状态牌不带类型的话抽到也不触发（见 addStatusToDiscard 的同款注释）
                    state.hand.add(new SimCard(id, false, -2, -1, "STATUS"));
                }
            }

            @Override
            public String toString() {
                return "hand+=" + id + "x" + count;
            }
        };
    }

    /** 壁垒：格挡翻倍。 */
    public static Effect doubleBlock() {
        return new Effect() {
            @Override
            public void applyTo(SimState state, int targetIndex, SimCard played) {
                state.block *= 2;
            }

            @Override
            public String toString() {
                return "block*2";
            }
        };
    }

    /** 全身撞击：伤害等于当前格挡（力量/虚弱/易伤照常生效）。 */
    public static Effect damageEqualToBlock() {
        return new Effect() {
            @Override
            public void applyTo(SimState state, int targetIndex, SimCard played) {
                SimMonster target = requireTarget(state, targetIndex);
                DamageCalc.hit(state, target, DamageCalc.attackDamage(state, state.block, target, played));
            }

            @Override
            public boolean needsMonsterTarget() {
                return true;
            }

            @Override
            public String toString() {
                return "damage=block";
            }
        };
    }

    /** 突破极限：力量翻倍。 */
    public static Effect doubleStrength() {
        return new Effect() {
            @Override
            public void applyTo(SimState state, int targetIndex, SimCard played) {
                Integer strength = state.playerPowers.get(DamageCalc.STRENGTH);
                if (strength != null && strength > 0) {
                    state.playerPowers.put(DamageCalc.STRENGTH, strength * 2);
                }
            }

            @Override
            public String toString() {
                return "strength*2";
            }
        };
    }

    /** 重刃：力量按倍率计入。 */
    public static Effect damageWithStrengthMult(final int base, final int mult) {
        return new Effect() {
            @Override
            public void applyTo(SimState state, int targetIndex, SimCard played) {
                SimMonster target = requireTarget(state, targetIndex);
                DamageCalc.hit(state, target,
                        DamageCalc.attackDamageWithStrengthMult(state, base, mult, target));
            }

            @Override
            public boolean needsMonsterTarget() {
                return true;
            }

            @Override
            public String toString() {
                return "damage(" + base + "+str*" + mult + ")";
            }
        };
    }

    /** 收割：全体伤害，按实际扣血总量回复。 */
    public static Effect damageAllAndHeal(final int base) {
        return new Effect() {
            @Override
            public void applyTo(SimState state, int targetIndex, SimCard played) {
                int total = 0;
                for (SimMonster m : state.monsters) {
                    if (m.hp > 0) {
                        total += DamageCalc.hitAndReturnDealt(state, m,
                                DamageCalc.attackDamage(state, base, m, played));
                    }
                }
                state.hp = Math.min(state.maxHp, state.hp
                        + io.github.youqing.sts1solver.mirror.hooks.RelicHooks
                                .healAmount(state, total));
                io.github.youqing.sts1solver.mirror.hooks.RelicHooks.syncRedSkull(state);
            }

            @Override
            public String toString() {
                return "damageAll(" + base + ")+heal(dealt)";
            }
        };
    }


    // ---- 第四批需要的原语 ------------------------------------------------

    /** 把状态牌塞进弃牌堆（献祭 Immolate：1 张灼伤）。插入位置确定，无需随机。 */
    public static Effect addStatusToDiscard(final String id, final int count) {
        return new Effect() {
            @Override
            public void applyTo(SimState state, int targetIndex, SimCard played) {
                for (int i = 0; i < count; i++) {
                    // 🔴 类型必须标 STATUS：这张牌以后会被抽到，抽牌时 Evolve / 火吐
                    // 都按 `type == STATUS` 判定；不标就成了「一张没有类型的废牌」，
                    // 那些能力对它视而不见（静默漏算）。
                    state.discard.add(new SimCard(id, false, -2, -1, "STATUS"));
                }
            }

            @Override
            public String toString() {
                return "discard+=" + id + "x" + count;
            }
        };
    }

    /**
     * 消耗手牌里所有非攻击牌，每张获得 blockPer 格挡（再生 Second Wind）。
     *
     * <p>字节码 {@code BlockPerNonAttackAction}：逐张 {@code GainBlockAction(player, player, this.block)}
     * 再 {@code ExhaustSpecificCardAction}。{@code this.block} 是卡面算好的值
     * （baseBlock + 敏捷，虚弱再 ×0.75，**只 floor 一次**），所以 N 张的格挡是
     * {@code N * floor((blockPer + dex) * frail)}，不是 {@code floor((blockPer*N + dex) * frail)}。
     *
     * <p>实测差距：日志 {@code block: 13 vs 12} —— 合并算比实机多 1 点。
     */
    public static Effect exhaustNonAttacksForBlock(final int blockPer) {
        return new Effect() {
            @Override
            public void applyTo(SimState state, int targetIndex, SimCard played) {
                int perCard = DamageCalc.blockAmount(state, blockPer);
                int count = 0;
                java.util.Iterator<SimCard> it = state.hand.iterator();
                while (it.hasNext()) {
                    SimCard c = it.next();
                    if (isAttackId(c.id)) {
                        continue;      // 攻击牌保留，非攻击牌才消耗
                    }
                    it.remove();
                    exhaustCard(state, c);
                    count++;
                }
                io.github.youqing.sts1solver.mirror.hooks.Hooks.gainBlock(state, perCard * count);
            }

            @Override
            public String toString() {
                return "exhaustNonAttacks(+" + blockPer + " each)";
            }
        };
    }

    /** 消耗手牌所有非攻击牌，不给格挡（断魂斩 Sever Soul）。 */
    public static Effect exhaustNonAttacks() {
        return new Effect() {
            @Override
            public void applyTo(SimState state, int targetIndex, SimCard played) {
                java.util.Iterator<SimCard> it = state.hand.iterator();
                while (it.hasNext()) {
                    SimCard c = it.next();
                    if (isAttackId(c.id)) {
                        continue;      // 攻击牌保留，非攻击牌才消耗
                    }
                    it.remove();
                    exhaustCard(state, c);
                }
            }

            @Override
            public String toString() {
                return "exhaustNonAttacks";
            }
        };
    }

    /**
     * 消耗**整手牌**，每张造成 damagePer 点伤害（恶魔之火 Fiend Fire）。
     *
     * <p>字节码 {@code FiendFireAction}：先按 {@code hand.size()} 排队同样多次 {@code DamageAction}，
     * 再排队 {@code hand.size()} 次 {@code ExhaustAction(1, isRandom=true)}。
     * 随机分支用的是 {@code hand.moveToExhaustPile(hand.getRandomCard(cardRandomRng))} ——
     * 所以**每消耗一张就消耗一次 cardRandomRng**，且消耗顺序是随机的（不是手牌顺序）。
     */
    public static Effect exhaustHandForDamage(final int damagePer) {
        return new Effect() {
            @Override
            public void applyTo(SimState state, int targetIndex, SimCard played) {
                SimMonster target = requireTarget(state, targetIndex);
                int count = state.hand.size();
                for (int i = 0; i < count; i++) {
                    DamageCalc.hit(state, target, DamageCalc.attackDamage(state, damagePer, target, played));
                }
                io.github.youqing.sts1solver.sim.RngStream rng =
                        state.rng.get(SimState.RngKind.CARD);
                for (int i = 0; i < count && !state.hand.isEmpty(); i++) {
                    int pick = rng == null ? state.hand.size() - 1
                            : rng.random(state.hand.size() - 1);
                    pick = Math.max(0, Math.min(pick, state.hand.size() - 1));
                    exhaustCard(state, state.hand.remove(pick));
                }
            }

            @Override
            public boolean needsMonsterTarget() {
                return true;
            }

            @Override
            public String toString() {
                return "exhaustHand(+" + damagePer + " each)";
            }
        };
    }

    /** 目标处于易伤时追加效果（回旋踢 Dropkick）。 */
    public static Effect ifTargetVulnerable(final Effect... extra) {
        return new Effect() {
            @Override
            public void applyTo(SimState state, int targetIndex, SimCard played) {
                SimMonster target = requireTarget(state, targetIndex);
                Integer vulnerable = target.powers.get(DamageCalc.VULNERABLE);
                if (vulnerable == null || vulnerable <= 0) {
                    return;
                }
                for (Effect e : extra) {
                    e.applyTo(state, targetIndex, played);
                }
            }

            @Override
            public String toString() {
                return "ifTargetVulnerable" + java.util.Arrays.toString(extra);
            }
        };
    }

    /** 伤害 = base + perStrike × 牌组里名字含 Strike 的牌数（完美打击 Perfected Strike）。 */
    public static Effect damagePlusPerStrike(final int base, final int perStrike) {
        return new Effect() {
            @Override
            public void applyTo(SimState state, int targetIndex, SimCard played) {
                SimMonster target = requireTarget(state, targetIndex);
                int strikes = 0;
                // 🔴 打出的这张牌**自己也算**：原版 {@code AbstractPlayer.useCard} 先调
                // {@code calculateCardDamage}（字节码 offset 16），后调 {@code hand.removeCard}
                // （offset 109）—— 所以结算伤害时它还在手里。{@code PerfectedStrike.countCards()}
                // 只数 hand/draw/discard 三堆（无 exhaust），此时它必在其中（它带 STRIKE 标记）。
                // 我们这张牌在进 effect 前已经从 hand 里移走，所以要显式补 1。
                strikes += countStrikes(state.hand);
                strikes += countStrikes(state.draw);
                strikes += countStrikes(state.discard);
                if (isStrikeCard(played)) {
                    strikes++;
                }
                int dmg = base + perStrike * strikes;
                DamageCalc.hit(state, target, DamageCalc.attackDamage(state, dmg, target, played));
            }

            @Override
            public boolean needsMonsterTarget() {
                return true;
            }

            @Override
            public String toString() {
                return "damage(" + base + "+" + perStrike + "/strike)";
            }
        };
    }

    /**
     * 把状态牌插入抽牌堆的**随机位置**（野性打击 / 鲁莽冲锋）。
     *
     * <p>插入算法复刻自游戏（字节码核实）：{@code CardGroup.addToRandomSpot} →
     * {@code group.add(cardRandomRng.random(size - 1), card)}。**上界是 size-1**，
     * 也就是永远插在某个已有元素之前，绝不追加到末尾；牌堆为空时**不消耗 RNG**。
     * 随机源 {@code cardRandomRng} 对应影子里的
     * {@link io.github.youqing.sts1solver.sim.RngKind#CARD}。
     */
    public static Effect insertIntoDrawPileRandom(final String id, final int count) {
        return new Effect() {
            @Override
            public void applyTo(SimState state, int targetIndex, SimCard played) {
                io.github.youqing.sts1solver.sim.RngStream rng =
                        state.rng.get(SimState.RngKind.CARD);
                for (int i = 0; i < count; i++) {
                    SimCard status = new SimCard(id, false, -2, -1, "STATUS");
                    if (rng == null || state.draw.isEmpty()) {
                        state.draw.add(status);
                        continue;
                    }
                    state.draw.add(rng.random(state.draw.size() - 1), status);
                }
            }

            @Override
            public String toString() {
                return "drawPile+=" + id + "x" + count + " (random)";
            }
        };
    }

    /**
     * 对**随机敌人**造成 times 段伤害（剑刃回旋）。
     *
     * <p>选敌复刻 {@code MonsterGroup.getRandomMonster(null, false, cardRandomRng)}：
     * 下标 = {@code rng.random(0, monsters.size() - 1)}。
     */
    public static Effect damageRandomEnemies(final int times, final int base) {
        return new Effect() {
            @Override
            public void applyTo(SimState state, int targetIndex, SimCard played) {
                io.github.youqing.sts1solver.sim.RngStream rng =
                        state.rng.get(SimState.RngKind.CARD);
                for (int i = 0; i < times; i++) {
                    if (state.monsters.isEmpty()) {
                        return;
                    }
                    int index = rng == null
                            ? 0
                            : rng.random(state.monsters.size() - 1);
                    SimMonster target = state.monsters.get(
                            Math.min(index, state.monsters.size() - 1));
                    DamageCalc.hit(state, target,
                            DamageCalc.attackDamage(state, base, target, played));
                }
            }

            @Override
            public String toString() {
                return "randomDamage(" + base + "x" + times + ")";
            }
        };
    }

    /**
     * 统一的「消耗一张牌」入口。
     * 哨兵（Sentinel）在**被消耗**时回 2 点能量，所以所有消耗路径都必须走这里，
     * 否则那张牌的触发会被漏掉。
     */
    public static void exhaustCard(SimState state, SimCard card) {
        if (card == null) {
            return;
        }
        state.exhaust.add(card);
        // AbstractRelic.onExhaust（灰烬之主）：每消耗一张牌，全体 3 点纯伤
        io.github.youqing.sts1solver.mirror.hooks.RelicHooks.onExhaust(state);
        // 死灵诅咒（Necronomicurse.onExhaust）：消耗后**立刻回到手牌**（不是留在消耗堆）
        if ("Necronomicurse".equals(card.id)) {
            state.exhaust.remove(card);
            state.hand.add(card);
        }
        if ("Sentinel".equals(card.id) || "Sentinel+".equals(card.id)
                || "Sentinel".equals(card.id)) {
            state.energy += 2;
        }
    }

    /**
     * 随机一张铁甲战士攻击牌加入手牌，本回合费用为 0（地狱之刃 Infernal Blade）。
     *
     * <p>复刻依据：{@code AbstractDungeon.returnTrulyRandomCardInCombat(CardType.ATTACK)}
     * → 取当前角色攻击池 → {@code cardRandomRng.random(...)}，然后
     * {@code makeCopy()} + {@code setCostForTurn(0)} + 塞进手牌。
     * 红卡已 74/75 覆盖，池子是完整的。
     */
    public static Effect addRandomAttackToHand(final boolean upgraded) {
        return new Effect() {
            @Override
            public void applyTo(SimState state, int targetIndex, SimCard played) {
                io.github.youqing.sts1solver.sim.RngStream rng =
                        state.rng.get(SimState.RngKind.CARD);
                int size = AttackPool.size();
                if (size <= 0) {
                    return;
                }
                int index = rng == null ? 0 : rng.random(size - 1);
                String id = AttackPool.idAt(index);
                int cost = upgraded ? AttackPool.upgradedCostAt(index) : AttackPool.costAt(index);
                int dmg = upgraded ? AttackPool.upgradedDamageAt(index) : AttackPool.damageAt(index);
                if (cost < 0) {
                    // X 费牌本回合仍是 X，交给 XEffect 处理
                    state.hand.add(new SimCard(id, upgraded, -1, dmg));
                } else {
                    // setCostForTurn(0)：**只管本回合**，回合结束回落到原费用
                    state.hand.add(new SimCard(id, upgraded, cost, dmg, "ATTACK", 0));
                }
            }

            @Override
            public String toString() {
                return "hand+=randomAttack" + (upgraded ? "+" : "");
            }
        };
    }

    /** 随机加入 count 张无色牌到手牌（Jack Of All Trades / Transmutation），可选本回合 0 费。 */
    public static Effect addRandomColorlessToHand(final int count, final boolean freeThisTurn) {
        return new Effect() {
            @Override
            public void applyTo(SimState state, int targetIndex, SimCard played) {
                for (int i = 0; i < count; i++) {
                    // 原版每张都重新走一遍 returnTrulyRandomColorlessCardInCombat：
                    // 重建候选列表 → rng.random(size - 1)。列表长度参与取值，所以不能预排下标。
                    SimCard c = io.github.youqing.sts1solver.sim.PoolCatalog
                            .randomColorlessCard(state, -1);
                    if (c == null) {
                        return;
                    }
                    state.hand.add(freeThisTurn ? c.withCostForTurn(0) : c);
                }
            }

            @Override
            public String toString() {
                return "hand+=colorless(" + count + ")";
            }
        };
    }

    /**
     * 消耗手牌里最多 count 张（净化 Purity）。
     *
     * <p>字节码 {@code Purity.use} → {@code new ExhaustAction(magicNumber, false, true)}
     * = 不随机、可少选、上限 {@code magicNumber} 张。
     *
     * <p>搜索不传选择时用固定策略：先丢状态/诅咒，再丢费用最高的 —— 枚举 10 选 5 的
     * 全部组合（252 种）对 beam search 不划算，蓝本处理多选类效果也是限定候选后展开。
     */
    public static Effect exhaustUpTo(final int count) {
        return new MultiChoiceEffect() {
            @Override
            public void applyWithChoices(SimState state, int targetIndex, SimCard played,
                                         int[] choices) {
                int limit = Math.min(count, state.hand.size());
                if (limit <= 0) {
                    return;
                }
                int[] picked = choices != null ? choices : policyPick(state, limit);
                // 从大到小删，避免删前面的元素后下标错位
                int[] sorted = picked.clone();
                java.util.Arrays.sort(sorted);
                for (int i = sorted.length - 1; i >= 0; i--) {
                    int idx = sorted[i];
                    if (idx < 0 || idx >= state.hand.size()) {
                        continue;
                    }
                    exhaustCard(state, state.hand.remove(idx));
                }
            }

            private int[] policyPick(SimState state, int limit) {
                Integer[] order = new Integer[state.hand.size()];
                for (int i = 0; i < order.length; i++) {
                    order[i] = i;
                }
                final SimState s = state;
                java.util.Arrays.sort(order, new java.util.Comparator<Integer>() {
                    @Override
                    public int compare(Integer a, Integer b) {
                        return Double.compare(score(s.hand.get(b)), score(s.hand.get(a)));
                    }
                });
                int n = Math.min(limit, order.length);
                int[] out = new int[n];
                for (int i = 0; i < n; i++) {
                    out[i] = order[i];
                }
                return out;
            }

            private double score(SimCard c) {
                if ("STATUS".equals(c.type) || "CURSE".equals(c.type)) {
                    return 1000 + c.cost;
                }
                return c.cost;
            }

            @Override
            public String toString() {
                return "exhaust<=" + count;
            }
        };
    }

    /**
     * 弃牌堆洗回抽牌堆 —— 复刻 {@code EmptyDeckShuffleAction}。
     *
     * <p>字节码：{@code player.discardPile.shuffle(AbstractDungeon.shuffleRng)}，然后逐张从
     * 弃牌堆取走、交给 {@code Soul} 落到抽牌堆。所以这里同样**只洗弃牌堆**（1 次 randomLong），
     * 再按洗好的顺序搬进抽牌堆。
     *
     * <p>⚠️ 原版对第 11 张起用 {@code souls.shuffle(card, true)}（视觉上飞到牌堆底），
     * 落点没在字节码里确认；这里统一按顺序搬。若对拍在「洗牌后抽牌」处出现 {@code draw[i]} 失配，
     * 先查这条。
     */
    public static void reshuffleDiscardIntoDraw(SimState state) {
        if (state.discard.isEmpty()) {
            return;
        }
        io.github.youqing.sts1solver.sim.Shuffle.shuffle(state.discard,
                state.rng.get(SimState.RngKind.SHUFFLE));
        for (SimCard c : state.discard) {
            state.draw.add(c);
        }
        state.discard.clear();
        // Sundial.onShuffle：弃牌堆洗回抽牌堆算一次洗牌
        io.github.youqing.sts1solver.mirror.hooks.RelicHooks.onShuffle(state);
    }

    /**
     * 深呼吸：弃牌堆洗回抽牌堆（若有），再把抽牌堆整体洗一次，然后抽 drawCount 张。
     *
     * <p>字节码 {@code DeepBreath.use}：
     * {@code if (discardPile.size() > 0) addToBot(new EmptyDeckShuffleAction())}
     * → {@code addToBot(new ShuffleAction(player.drawPile, false))}
     * → {@code addToBot(new DrawCardAction(player, magicNumber))}。
     * 也就是**两次** randomLong（弃牌堆一次 + 抽牌堆一次）。
     */
    public static Effect deepBreath(final int drawCount) {
        return new Effect() {
            @Override
            public void applyTo(SimState state, int targetIndex, SimCard played) {
                reshuffleDiscardIntoDraw(state);
                // Sundial.onShuffle：DeepBreath 的第二次洗牌（抽牌堆整体重洗）也算一次
                io.github.youqing.sts1solver.mirror.hooks.RelicHooks.onShuffle(state);
                io.github.youqing.sts1solver.sim.Shuffle.shuffle(state.draw,
                        state.rng.get(SimState.RngKind.SHUFFLE));
                draw(drawCount).applyTo(state, targetIndex, played);
            }

            @Override
            public String toString() {
                return "shuffleAll+draw(" + drawCount + ")";
            }
        };
    }

    /**
     * 结茧：把 count 张随机**技能牌**洗进抽牌堆，本场 0 费，自身消耗。
     *
     * <p>字节码 {@code Chrysalis.use}：循环
     * {@code AbstractDungeon.returnTrulyRandomCardInCombat(CardType.SKILL)}
     * → {@code makeCopy()} → {@code cost = costForTurn = 0}
     * → {@code new MakeTempCardInDrawPileAction(card, 1, true, true)}。
     * 每张牌都重新取一次候选列表，所以 RNG 消耗不能合并。
     */
    public static Effect chrysalis(final int count) {
        return new Effect() {
            @Override
            public void applyTo(SimState state, int targetIndex, SimCard played) {
                io.github.youqing.sts1solver.sim.RngStream rng =
                        state.rng.get(SimState.RngKind.CARD);
                for (int i = 0; i < count; i++) {
                    SimCard picked = io.github.youqing.sts1solver.sim.PoolCatalog
                            .randomCombatCard(state, "SKILL");
                    if (picked == null) {
                        return;
                    }
                    SimCard c = new SimCard(picked.id, false, 0, -1, "SKILL");
                    int at = (rng == null || state.draw.isEmpty())
                            ? 0 : rng.random(state.draw.size() - 1);
                    state.draw.add(at, c);
                }
            }

            @Override
            public String toString() {
                return "draw+=randomSkill(" + count + ")";
            }
        };
    }

    /**
     * 发现：生成 3 张**互不相同**的牌，选 1 张进手牌，本回合 0 费。
     *
     * <p>字节码 {@code DiscoveryAction.generateCardChoices}：
     * {@code while (list.size() != 3) { c = returnTrulyRandomCardInCombat(type);
     * 去重后 list.add(c.makeCopy()); }} —— 去重按 {@code cardID}，重复就再抽，
     * 所以 RNG 消耗次数不固定。
     *
     * <p>无差别版（原版 {@code Discovery} 卡用）。
     */
    public static ChoiceEffect discovery() {
        return discovery(null);
    }

    /**
     * 带类型过滤的发现（原版 {@code DiscoveryAction(CardType, amount)}）。
     *
     * <p>{@code PowerPotion} / {@code AttackPotion} / {@code SkillPotion} /
     * {@code ColorlessPotion} 都走这条路，只是 {@code CardType} 不同；
     * 无色那张用 {@code generateColorlessCardChoices()}，我们按
     * {@code PoolCatalog.randomCombatCard(state, "COLORLESS")} 等价处理。
     *
     * @param cardType {@code "POWER"/"ATTACK"/"SKILL"/"COLORLESS"}，{@code null} = 不限
     */
    public static ChoiceEffect discovery(final String cardType) {
        return new ChoiceEffect() {
            @Override
            public void applyWithChoice(SimState state, int targetIndex, SimCard played, int choiceIndex) {
                java.util.List<SimCard> choices = new java.util.ArrayList<SimCard>(3);
                int guard = 0;
                while (choices.size() < 3 && guard++ < 64) {
                    SimCard c = io.github.youqing.sts1solver.sim.PoolCatalog
                            .randomCombatCard(state, cardType);
                    if (c == null) {
                        return;
                    }
                    boolean dup = false;
                    for (SimCard e : choices) {
                        if (e.id.equals(c.id)) {
                            dup = true;
                        }
                    }
                    if (!dup) {
                        choices.add(c);
                    }
                }
                if (choices.isEmpty()) {
                    return;
                }
                int pick = (choiceIndex >= 0 && choiceIndex < choices.size()) ? choiceIndex : 0;
                SimCard chosen = choices.get(pick);
                // 本回合 0 费：改 costForTurn 而不是 cost
                state.hand.add(new SimCard(chosen.id, false, chosen.cost, chosen.baseDamage,
                        chosen.type, 0));
            }

            @Override
            public String toString() {
                return "choose1of3(" + cardType + ")";
            }
        };
    }


    /** 把 count 张随机攻击牌洗进抽牌堆，本场 0 费（Metamorphosis）。 */
    public static Effect shuffleRandomAttacksIntoDraw(final int count) {
        return new Effect() {
            @Override
            public void applyTo(SimState state, int targetIndex, SimCard played) {
                io.github.youqing.sts1solver.sim.RngStream rng =
                        state.rng.get(SimState.RngKind.CARD);
                for (int i = 0; i < count; i++) {
                    SimCard picked = io.github.youqing.sts1solver.sim.PoolCatalog
                            .randomCombatCard(state, "ATTACK");
                    if (picked == null) {
                        return;
                    }
                    SimCard c = new SimCard(picked.id, false, 0, picked.baseDamage, "ATTACK");
                    // 同一个 MakeTempCardInDrawPileAction(randomSpot=true) →
                    // CardGroup.addToRandomSpot → add(random(size-1), card)，空堆不消耗 RNG
                    int at = (rng == null || state.draw.isEmpty())
                            ? 0 : rng.random(state.draw.size() - 1);
                    state.draw.add(at, c);
                }
            }

            @Override
            public String toString() {
                return "draw+=randomAttacks(" + count + ")";
            }
        };
    }


    /** X 费：随机 X 张无色牌进手牌，本回合 0 费（转化 Transmutation）。 */
    public static Effect addRandomColorlessXToHand() {
        return new XEffect() {
            @Override
            public void applyWithX(SimState state, int targetIndex, SimCard played, int x) {
                for (int i = 0; i < x; i++) {
                    SimCard c = io.github.youqing.sts1solver.sim.PoolCatalog
                            .randomColorlessCard(state, -1);
                    if (c == null) {
                        return;
                    }
                    // 本回合 0 费
                    state.hand.add(c.withCostForTurn(0));
                }
            }

            @Override
            public String toString() {
                return "hand+=colorless(X)";
            }
        };
    }


    /** 升级所有牌堆里的每一张牌（神化 Apotheosis）。 */
    public static Effect upgradeAllPiles() {
        return new Effect() {
            @Override
            public void applyTo(SimState state, int targetIndex, SimCard played) {
                upgradeAll(state.hand);
                upgradeAll(state.draw);
                upgradeAll(state.discard);
                upgradeAll(state.exhaust);
            }

            private void upgradeAll(java.util.List<SimCard> pile) {
                for (int i = 0; i < pile.size(); i++) {
                    SimCard c = pile.get(i);
                    if (!c.upgraded) {
                        pile.set(i, new SimCard(c.id, true, c.cost, c.baseDamage));
                    }
                }
            }

            @Override
            public String toString() {
                return "upgrade(all piles)";
            }
        };
    }

    /** 把手牌费用降到不超过 limit（启蒙 Enlightenment：全部降到 1）。 */
    /**
     * 手牌费用降到上限（启蒙 Enlightenment）。
     *
     * <p>⚖️ 升级态不同：原版是「**本回合**降到 1」，{@code Enlightenment+} 是「**本场**降到 1」。
     * 所以这里要区分改 {@code costForTurn} 还是 {@code cost}。
     */
    public static Effect reduceHandCostsTo(final int limit, final boolean wholeCombat) {
        return new Effect() {
            @Override
            public void applyTo(SimState state, int targetIndex, SimCard played) {
                for (int i = 0; i < state.hand.size(); i++) {
                    SimCard c = state.hand.get(i);
                    if (c.effectiveCost() > limit) {
                        state.hand.set(i, wholeCombat ? c.withCost(limit)
                                : c.withCostForTurn(limit));
                    }
                }
            }

            @Override
            public String toString() {
                return "handCost<=" + limit + (wholeCombat ? "(combat)" : "(turn)");
            }
        };
    }

    /** 随机一张手牌费用归零（疯狂 Madness）。 */
    public static Effect freeRandomHandCard() {
        return new Effect() {
            @Override
            public void applyTo(SimState state, int targetIndex, SimCard played) {
                if (state.hand.isEmpty()) {
                    return;
                }
                io.github.youqing.sts1solver.sim.RngStream rng =
                        state.rng.get(SimState.RngKind.CARD);
                int index = rng == null ? 0 : rng.random(state.hand.size() - 1);
                index = Math.min(index, state.hand.size() - 1);
                state.hand.set(index, state.hand.get(index).withCost(0));
            }

            @Override
            public String toString() {
                return "randomHandCardCost=0";
            }
        };
    }

    /** 把选中的手牌放到抽牌堆底（预谋 Forethought）。 */
    public static Effect putChosenOnBottom() {
        return new ChoiceEffect() {
            @Override
            public void applyWithChoice(SimState state, int targetIndex, SimCard played, int choiceIndex) {
                if (choiceIndex < 0 || choiceIndex >= state.hand.size()) {
                    return;
                }
                SimCard picked = state.hand.remove(choiceIndex);
                state.draw.add(0, picked);
            }

            @Override
            public String toString() {
                return "bottom(chosen)";
            }
        };
    }

    /** 从抽牌堆取一张指定类别（attack/skill）的牌加入手牌（秘密武器 / 秘密技巧）。 */
    public static Effect retrieveChosenFromDraw(final boolean wantAttack) {
        return new ChoiceEffect() {
            @Override
            public void applyWithChoice(SimState state, int targetIndex, SimCard played, int choiceIndex) {
                if (choiceIndex < 0 || choiceIndex >= state.draw.size()) {
                    return;
                }
                SimCard c = state.draw.get(choiceIndex);
                if (isAttackId(c.id) != wantAttack) {
                    return;
                }
                state.hand.add(state.draw.remove(choiceIndex));
            }

            @Override
            public String toString() {
                return "hand+=draw[" + (wantAttack ? "attack" : "skill") + "]";
            }
        };
    }

    /** 从抽牌堆随机取 n 张攻击牌加入手牌（暴力 Violence）。 */
    public static Effect randomAttacksFromDrawToHand(final int count) {
        return new Effect() {
            @Override
            public void applyTo(SimState state, int targetIndex, SimCard played) {
                io.github.youqing.sts1solver.sim.RngStream rng =
                        state.rng.get(SimState.RngKind.CARD);
                for (int i = 0; i < count; i++) {
                    java.util.List<Integer> candidates = new java.util.ArrayList<Integer>();
                    for (int j = 0; j < state.draw.size(); j++) {
                        if (isAttackId(state.draw.get(j).id)) {
                            candidates.add(j);
                        }
                    }
                    if (candidates.isEmpty()) {
                        return;
                    }
                    int pick = rng == null ? 0 : rng.random(candidates.size() - 1);
                    pick = Math.min(pick, candidates.size() - 1);
                    state.hand.add(state.draw.remove((int) candidates.get(pick)));
                }
            }

            @Override
            public String toString() {
                return "hand+=randomAttacks(" + count + ")";
            }
        };
    }

    /** 造成伤害，击杀则获得金币（贪婪之手 Hand of Greed）。 */
    public static Effect damageAndGoldOnKill(final int base, final int gold) {
        return new Effect() {
            @Override
            public void applyTo(SimState state, int targetIndex, SimCard played) {
                SimMonster target = requireTarget(state, targetIndex);
                boolean alive = target.hp > 0;
                DamageCalc.hit(state, target, DamageCalc.attackDamage(state, base, target, played));
                if (alive && target.hp <= 0) {
                    state.gold += gold;
                }
            }

            @Override
            public boolean needsMonsterTarget() {
                return true;
            }

            @Override
            public String toString() {
                return "damage(" + base + ")+gold(" + gold + " on kill)";
            }
        };
    }

    /** 空效果（史莱姆粘液 Slimed：只有 Exhaust）。 */
    public static Effect nothing() {
        return new Effect() {
            @Override
            public void applyTo(SimState state, int targetIndex, SimCard played) {
            }

            @Override
            public String toString() {
                return "nothing";
            }
        };
    }


    /** 伤害 = 这张牌当前的卡面伤害（暴走 / 灼烧打击这类会成长的牌）。 */
    public static Effect damageEqualToCardBase() {
        return new Effect() {
            @Override
            public void applyTo(SimState state, int targetIndex, SimCard played) {
                SimMonster target = requireTarget(state, targetIndex);
                int base = played == null || played.baseDamage < 0 ? 0 : played.baseDamage;
                DamageCalc.hit(state, target, DamageCalc.attackDamage(state, base, target, played));
            }

            @Override
            public boolean needsMonsterTarget() {
                return true;
            }

            @Override
            public String toString() {
                return "damage(=card base)";
            }
        };
    }

    /** 打出抽牌堆顶那张，然后把它消耗掉（破灭 Havoc）。 */
    public static Effect playTopOfDrawPile() {
        return new Effect() {
            @Override
            public void applyTo(SimState state, int targetIndex, SimCard played) {
                if (state.draw.isEmpty()) {
                    return;
                }
                SimCard top = state.draw.remove(state.draw.size() - 1);
                try {
                    io.github.youqing.sts1solver.mirror.CardPlayer.playFromPile(state, top, targetIndex);
                } catch (ActionRejected unsupported) {
                    // 没镜像的牌：打不出去，直接进消耗堆（与游戏一致：破灭会把它消耗）
                    state.exhaust.add(top);
                    return;
                }
                // 破灭规则：打完之后该牌被消耗（若上一步已进消耗堆就不重复）
                if (!state.exhaust.contains(top)) {
                    state.exhaust.add(top);
                    if (state.discard.remove(top)) {
                        // 从弃牌堆移走
                    }
                }
            }

            @Override
            public String toString() {
                return "play(top of draw)+exhaust";
            }
        };
    }

    /** 复制指定手牌 1/2 张（双持 Dual Wield）。 */
    public static Effect copyChosenToHand(final int copies) {
        return new ChoiceEffect() {
            @Override
            public void applyWithChoice(SimState state, int targetIndex, SimCard played, int choiceIndex) {
                if (choiceIndex < 0 || choiceIndex >= state.hand.size()) {
                    return;
                }
                SimCard src = state.hand.get(choiceIndex);
                for (int i = 0; i < copies; i++) {
                    state.hand.add(new SimCard(src.id, src.upgraded, src.cost));
                }
            }

            @Override
            public String toString() {
                return "copy(chosen)x" + copies;
            }
        };
    }

    /** 从消耗堆取回一张牌到手牌（发掘 Exhume）。 */
    public static Effect retrieveFromExhaust() {
        return new ChoiceEffect() {
            @Override
            public void applyWithChoice(SimState state, int targetIndex, SimCard played, int choiceIndex) {
                if (choiceIndex < 0 || choiceIndex >= state.exhaust.size()) {
                    return;
                }
                state.hand.add(state.exhaust.remove(choiceIndex));
            }

            @Override
            public String toString() {
                return "hand+=exhaust[chosen]";
            }
        };
    }

    /** 造成伤害后，把弃牌堆里指定的一张放到抽牌堆顶（头槌 Headbutt）。 */
    public static Effect damageThenTopFromDiscard(final int base) {
        return new ChoiceEffect() {
            @Override
            public void applyWithChoice(SimState state, int targetIndex, SimCard played, int choiceIndex) {
                SimMonster target = requireTarget(state, targetIndex);
                DamageCalc.hit(state, target, DamageCalc.attackDamage(state, base, target, played));
                if (choiceIndex >= 0 && choiceIndex < state.discard.size()) {
                    state.draw.add(state.discard.remove(choiceIndex));
                }
            }

            @Override
            public boolean needsMonsterTarget() {
                return true;
            }

            @Override
            public String toString() {
                return "damage(" + base + ")+top(discard[chosen])";
            }
        };
    }

    /** 随机消耗一张手牌（真坚毅 True Grit 原版）。 */
    public static Effect exhaustRandomHandCard() {
        return new Effect() {
            @Override
            public void applyTo(SimState state, int targetIndex, SimCard played) {
                if (state.hand.isEmpty()) {
                    return;
                }
                io.github.youqing.sts1solver.sim.RngStream rng =
                        state.rng.get(SimState.RngKind.CARD);
                int index = rng == null ? 0 : rng.random(state.hand.size() - 1);
                exhaustCard(state, state.hand.remove(Math.min(index, state.hand.size() - 1)));
            }

            @Override
            public String toString() {
                return "exhaust(random hand)";
            }
        };
    }

    /** 消耗指定手牌（真坚毅+）。 */
    public static Effect exhaustChosen() {
        return new ChoiceEffect() {
            @Override
            public void applyWithChoice(SimState state, int targetIndex, SimCard played, int choiceIndex) {
                if (choiceIndex >= 0 && choiceIndex < state.hand.size()) {
                    exhaustCard(state, state.hand.remove(choiceIndex));
                }
            }

            @Override
            public String toString() {
                return "exhaust(chosen)";
            }
        };
    }

    /** 伤害等于抽牌堆张数（心灵爆震 Mind Blast）。 */
    public static Effect damageEqualToDrawPile() {
        return new Effect() {
            @Override
            public void applyTo(SimState state, int targetIndex, SimCard played) {
                SimMonster target = requireTarget(state, targetIndex);
                int base = state.draw.size();
                DamageCalc.hit(state, target, DamageCalc.attackDamage(state, base, target, played));
            }

            @Override
            public boolean needsMonsterTarget() {
                return true;
            }

            @Override
            public String toString() {
                return "damage(=drawPile)";
            }
        };
    }

    /** 手牌里没有攻击牌时才抽牌（急躁 Impatience）。 */
    public static Effect drawIfNoAttackInHand(final int count) {
        return new Effect() {
            @Override
            public void applyTo(SimState state, int targetIndex, SimCard played) {
                for (SimCard c : state.hand) {
                    if (isAttackId(c.id)) {
                        return;
                    }
                }
                if (played != null && isAttackId(played.id)) {
                    return;
                }
                draw(count).applyTo(state, targetIndex, played);
            }

            @Override
            public String toString() {
                return "draw(" + count + " if no attack)";
            }
        };
    }

    /** 若击杀目标则提升最大生命（啃食 Feed）。 */
    public static Effect damageAndMaxHpOnKill(final int base, final int maxHpGain) {
        return new Effect() {
            @Override
            public void applyTo(SimState state, int targetIndex, SimCard played) {
                SimMonster target = requireTarget(state, targetIndex);
                boolean aliveBefore = target.hp > 0;
                DamageCalc.hit(state, target, DamageCalc.attackDamage(state, base, target, played));
                if (aliveBefore && target.hp <= 0) {
                    state.maxHp += maxHpGain;
                    state.hp += maxHpGain;
                }
            }

            @Override
            public boolean needsMonsterTarget() {
                return true;
            }

            @Override
            public String toString() {
                return "damage(" + base + ")+maxHp(" + maxHpGain + " on kill)";
            }
        };
    }

    /** 目标本回合意图为攻击时获得力量（观察弱点 Spot Weakness）。 */
    public static Effect strengthIfEnemyAttacks(final int amount) {
        return new Effect() {
            @Override
            public void applyTo(SimState state, int targetIndex, SimCard played) {
                SimMonster target = requireTarget(state, targetIndex);
                if (!target.intentAttack) {
                    return;
                }
                add(state.playerPowers, DamageCalc.STRENGTH, amount);
            }

            @Override
            public boolean needsMonsterTarget() {
                return true;
            }

            @Override
            public String toString() {
                return "strength(+" + amount + " if attacking)";
            }
        };
    }

    /** 先抽牌，再把指定手牌放回抽牌堆顶（战吼 Warcry）。 */
    public static Effect drawThenPutChosenOnTop(final int drawCount) {
        return drawThenPutChosenOnTop(drawCount, 1);
    }

    /**
     * 抽若干张，再把 count 张手牌放回抽牌堆顶（战吼 Warcry）。
     *
     * <p>对应原版两条动作：{@code DrawCardAction(magicNumber)} + {@code PutOnDeckAction(p, p, 1, false)}。
     * 注意**放回数量恒为 1**，升级只加抽牌数（字节码里 {@code PutOnDeckAction} 的 amount 是 iconst_1）。
     *
     * <p>{@code PutOnDeckAction} 有两个分支：
     * <ul>
     *   <li>手牌数 &gt; amount → 开手牌选择窗，玩家决定（不消耗 RNG）</li>
     *   <li>手牌数 &lt;= amount → 不弹窗，改为循环 {@code hand.moveToDeck(hand.getRandomCard(cardRandomRng))}
     *       —— **每搬一张消耗一次 {@code cardRandomRng}</b>，且搬走哪张是随机的</li>
     * </ul>
     * 第二条实测过：第二次战吼手牌只剩 1 张、没有 HAND pick 日志，RNG 却掉了 1 位。
     */
    public static Effect drawThenPutChosenOnTop(final int drawCount, final int putBack) {
        return new ChoiceEffect() {
            @Override
            public void applyWithChoice(SimState state, int targetIndex, SimCard played, int choiceIndex) {
                draw(drawCount).applyTo(state, targetIndex, played);
                // if (hand.size() < amount) amount = hand.size();
                int amount = Math.min(putBack, state.hand.size());
                if (state.hand.size() > amount) {
                    // 玩家选牌（choiceIndex 是「出牌后手牌」的下标）
                    if (choiceIndex >= 0 && choiceIndex < state.hand.size()) {
                        state.draw.add(state.hand.remove(choiceIndex));
                    }
                    return;
                }
                // 不够选：原版循环体是 i < hand.size()（size 边搬边缩），照抄这个边界
                io.github.youqing.sts1solver.sim.RngStream rng =
                        state.rng.get(SimState.RngKind.CARD);
                for (int i = 0; i < state.hand.size(); i++) {
                    int pick = rng == null ? state.hand.size() - 1
                            : rng.random(state.hand.size() - 1);
                    pick = Math.max(0, Math.min(pick, state.hand.size() - 1));
                    state.draw.add(state.hand.remove(pick));
                }
            }

            @Override
            public String toString() {
                return "draw(" + drawCount + ")+top(" + putBack + ")";
            }
        };
    }

    /** 升级手牌里指定的一张（军备 Armaments）。 */
    public static Effect upgradeChosen() {
        return new ChoiceEffect() {
            @Override
            public void applyWithChoice(SimState state, int targetIndex, SimCard played, int choiceIndex) {
                if (choiceIndex < 0 || choiceIndex >= state.hand.size()) {
                    return;
                }
                SimCard c = state.hand.get(choiceIndex);
                if (c.upgraded || !canUpgrade(c)) {
                    return;
                }
                state.hand.set(choiceIndex, upgradeOf(c));
            }

            @Override
            public String toString() {
                return "upgrade(chosen)";
            }
        };
    }

    /** 升级**全部**手牌（军备+）。 */
    public static Effect upgradeAllHand() {
        return new Effect() {
            @Override
            public void applyTo(SimState state, int targetIndex, SimCard played) {
                for (int i = 0; i < state.hand.size(); i++) {
                    SimCard c = state.hand.get(i);
                    if (c.upgraded || !canUpgrade(c)) {
                        continue;
                    }
                    state.hand.set(i, upgradeOf(c));
                }
            }

            @Override
            public String toString() {
                return "upgrade(allHand)";
            }
        };
    }

    /**
     * 原版 {@code AbstractCard.canUpgrade()}：<b>诅咒和状态牌永远升不了</b>
     * （字节码：{@code type == CURSE → false; type == STATUS → false}）。
     *
     * <p>🔴 真机实测：军备+ 打完，影子把手里的「黏液」升成了 Slimed+，实机没有 ——
     * 因为 {@code ArmamentsAction} 逐张调的就是 {@code canUpgrade()}。
     * 少了这一层，每次军备+ 都会在影子里多出几张不该有的升级牌。
     */
    private static boolean canUpgrade(SimCard card) {
        return !"CURSE".equals(card.type) && !"STATUS".equals(card.type);
    }

    /**
     * 生成「升级后」的卡片副本。
     *
     * <p>🔴 必须把 {@code baseDamage} / {@code type} / {@code costForTurn} 一起带过去：
     * 以前只写 {@code new SimCard(c.id, true, c.cost)}，伤害字段落成 -1，
     * 影子里的灼热打击打完只掉个位数血，实机掉 11 点（真机日志：{@code monsters[0].hp 16 vs 5}）。
     * 升级带来的数值上涨本身要靠捕获（下一次对拍会读到真实卡面），这里先保证不丢数据。
     */
    private static SimCard upgradeOf(SimCard c) {
        return new SimCard(c.id, true, c.cost, c.baseDamage, c.type, c.costForTurn);
    }

    /** 消耗指定手牌，然后抽牌（烧牌契约 Burning Pact）。 */
    public static Effect exhaustChosenThenDraw(final int drawCount) {
        return new ChoiceEffect() {
            @Override
            public void applyWithChoice(SimState state, int targetIndex, SimCard played, int choiceIndex) {
                if (choiceIndex >= 0 && choiceIndex < state.hand.size()) {
                    exhaustCard(state, state.hand.remove(choiceIndex));
                }
                draw(drawCount).applyTo(state, targetIndex, played);
            }

            @Override
            public String toString() {
                return "exhaust(chosen)+draw(" + drawCount + ")";
            }
        };
    }

    /** 全体 X 段伤害（旋风斩：每点能量 5 点伤害）。 */
    public static Effect damageAllX(final int perEnergy) {
        return new XEffect() {
            @Override
            public void applyWithX(SimState state, int targetIndex, SimCard played, int x) {
                for (int i = 0; i < x; i++) {
                    for (SimMonster m : state.monsters) {
                        if (m.hp > 0) {
                            DamageCalc.hit(state, m,
                                    DamageCalc.attackDamage(state, perEnergy, m, played));
                        }
                    }
                }
            }

            @Override
            public String toString() {
                return "damageAll(" + perEnergy + "xX)";
            }
        };
    }

    /** 这张牌的 ID 是不是已登记的攻击牌（缠身拦截、消耗非攻击牌那类效果都要用）。 */
    public static boolean isAttackId(String id) {
        return ATTACK_IDS.contains(id);
    }

    /** 已知的攻击牌（与新注册同步维护；未列出的按非攻击处理并被消耗，风险由对拍暴露）。 */
    private static final java.util.Set<String> ATTACK_IDS =
            new java.util.HashSet<String>(java.util.Arrays.asList(
                    "Strike_R", "Bash", "Anger", "Cleave", "Clothesline", "Twin Strike",
                    "Pommel Strike", "Iron Wave", "Clash", "Bludgeon", "Carnage", "Pummel",
                    "Searing Blow", "Uppercut", "Heavy Blade", "Body Slam", "Hemokinesis",
                    "Reaper", "Thunderclap", "Wild Strike", "Reckless Charge", "Immolate",
                    "Sever Soul", "Fiend Fire", "Perfected Strike", "Dropkick", "Feed",
                    "Sword Boomerang", "Whirlwind", "Rampage", "Headbutt"
            ));

    private static int countStrikes(java.util.List<SimCard> pile) {
        int n = 0;
        for (SimCard c : pile) {
            if (isStrikeCard(c)) {
                n++;
            }
        }
        return n;
    }

    /**
     * 带 {@code CardTags.STRIKE} 标记的牌（javap 扫全 jar 核实的**全部**六张）：
     * Strike_Red、PommelStrike、TwinStrike、WildStrike、PerfectedStrike、SwiftStrike。
     * 名字里都含 "Strike"，所以字符串判据与标记等价（铁甲 + 无色范围内没有反例）。
     */
    private static boolean isStrikeCard(SimCard c) {
        return DamageCalc.isStrikeTagged(c);
    }

    private static SimMonster requireTarget(SimState state, int targetIndex) {        if (targetIndex < 0 || targetIndex >= state.monsters.size()) {
            throw new ActionRejected("effect needs a valid monster target, got index " + targetIndex);
        }
        return state.monsters.get(targetIndex);
    }

    private static void add(java.util.Map<String, Integer> powers, String id, int amount) {
        Integer current = powers.get(id);
        powers.put(id, (current == null ? 0 : current) + amount);
        DamageCalc.markIntangibleFresh(powers, id);
    }

    /**
     * 给怪物施加**负面**效果，先过神器（{@code ArtifactPower}）。
     *
     * <p>原版 {@code ApplyPowerAction}：目标有 {@code Artifact} 时，
     * 调 {@code ArtifactPower.onSpecificTrigger()} 消耗一层（{@code amount>0} 时减 1，
     * 否则整个移除），**这次施加被抵消**。
     *
     * <p>只用于**负面**效果（易伤/虚弱/中毒/缠身等）。正面效果（力量/格挡/装甲）
     * 不走这里 —— 神器不吃正面。
     *
     * @return {@code true} = 真的加上了；{@code false} = 被神器抵消
     */
    public static boolean applyDebuff(SimState state, SimMonster target, String id, int amount) {
        // 冠军腰带（{@code ApplyPowerAction} 里的 Champion Belt 分支）：玩家给**别的**生物
        // 挂易伤时，只要目标身上**一层神器都没有**，就先排一个 +1 虚弱（`onTrigger`）。
        // 字节码判据：hasRelic("Champion Belt") && source.isPlayer && target != source
        //            && powerToApply.ID.equals("Vulnerable") && !target.hasPower("Artifact")
        // 注意这里用的是 hasPower（有没有），不是「还剩几层」—— 有神器就整条不触发。
        boolean championBelt = "Vulnerable".equals(id)
                && state != null && target != null
                && state.relics.containsKey(
                        io.github.youqing.sts1solver.mirror.hooks.RelicHooks.CHAMPION_BELT)
                && !target.powers.containsKey("Artifact");
        Integer artifact = target.powers.get("Artifact");
        if (artifact != null && artifact > 0) {
            if (artifact == 1) {
                target.powers.remove("Artifact");
            } else {
                target.powers.put("Artifact", artifact - 1);
            }
            return false;
        }
        add(target.powers, id, amount);
        if (championBelt) {
            add(target.powers, "Weakened", 1);
        }
        return true;
    }
}
