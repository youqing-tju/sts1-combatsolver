package io.github.youqing.sts1solver.sim;

import io.github.youqing.sts1solver.mirror.hooks.HookPhase;
import io.github.youqing.sts1solver.mirror.hooks.Hooks;

/**
 * 回合切换模型：结束玩家回合 → 敌方按意图造成伤害 → 新回合回能抽牌。
 *
 * <p>顺序**逐条对齐原版字节码**，不是凭感觉排的：
 *
 * <ol>
 *   <li>powers {@code atEndOfTurnPreEndTurnCards}（金属化）——原版在弃手牌之前；
 *   <li>手牌全部进弃牌堆；
 *   <li>powers {@code atEndOfTurn}（燃烧 / 炸弹 / 欧米伽）——原版在弃手牌之后；
 *   <li>敌方按意图结算；
 *   <li>清空格挡（壁垒除外）；
 *   <li>新回合：powers {@code atStartOfTurn}（磁力 / 乱战 / 神气归零）→ 回能 → 抽牌
 *       → powers {@code atStartOfTurnPostDraw}（恶魔形态）。
 * </ol>
 *
 * <p><b>仍然不建模</b>：状态牌（灼伤/眩晕）留在手里时的回合末伤害、遗物触发、
 * 怪物侧能力。这些没地方假装知道，所以估值只能当作「相对好坏」的参考。
 */
public final class CombatFlow {

    /** 手牌上限，同 {@code Effects.HAND_LIMIT}。 */
    private static final int HAND_LIMIT = 10;

    private CombatFlow() {
    }

    /** 结束玩家回合并进入下一回合。 */
    public static void endTurn(SimState state, int energyPerTurn, int handSize) {
        // ⓪ 「被 Time Warp 提前结束」的标记只对**那个回合**有效，翻页即清
        state.turnForcedEnd = false;

        // ① 弃手牌之前：金属化
        Hooks.firePhase(state, HookPhase.END_OF_TURN_PRE_CARDS);

        // ② 手牌进弃牌堆
        state.discard.addAll(state.hand);
        state.hand.clear();

        // ②.5 「本回合 0 费」到期（原版 costForTurn 在回合结束恢复成 cost）
        clearCostForTurn(state.hand);
        clearCostForTurn(state.draw);
        clearCostForTurn(state.discard);
        clearCostForTurn(state.exhaust);

        // ③ 弃手牌之后：燃烧 / 炸弹 / 欧米伽
        Hooks.firePhase(state, HookPhase.END_OF_TURN);

        // ③.5 缠绕（ConstrictedPower.atEndOfTurn）：固定掉血，DamageInfo type=THORNS → **吃格挡**。
        //      它是玩家身上的「永久」减益，每回合都结算，不会自己消失。
        Integer constricted = state.playerPowers.get("Constricted");
        if (constricted != null && constricted.intValue() > 0) {
            hitPlayer(state, constricted.intValue());
        }

        // ③.9 山铜（Orichalcum.onPlayerEndTurn）：**一点格挡都没有**时才 +6。
        //      放在回合末能力之后 —— 金属化/装甲先给格挡的话它就不触发。
        io.github.youqing.sts1solver.mirror.hooks.RelicHooks.onPlayerEndTurn(state);

        // ④ 敌方行动
        // 🔴 顺序很重要：**先让格挡吸收敌方伤害，再清空格挡**。
        // 反过来写（先清后打）会让格挡完全失效 —— 搜索于是永远不防御，
        // 这正是 BeamSolverTest 抓到的问题。
        // ④.0 怪物回合开始：**清格挡**。
        // 原版 MonsterGroup.applyPreTurnLogic()：
        //   if (!isDying && !isEscaping) { if (!hasPower("Barricade")) loseBlock(); applyStartOfTurnPowers(); }
        // 不清的话怪物格挡会跨回合累加，搜索会严重高估「杀不掉」（球体守卫每两回合 +25）。
        for (SimMonster m : state.monsters) {
            if (m.hp > 0 && !m.powers.containsKey("Barricade")) {
                m.block = 0;
            }
            // InvinciblePower.atStartOfTurn → amount = maxAmt（每回合的受伤上限重置）
            if (m.hp > 0 && m.powers.containsKey("Invincible")) {
                m.powers.put("Invincible", Integer.valueOf(
                        m.invincibleMax > 0 ? m.invincibleMax
                                : m.powers.get("Invincible").intValue()));
            }
        }

        // 每只怪走完整的一轮：结算本招 → 把这一招记进历史 → **立刻**决策下一招。
        // 原版就是这个次序（takeTurn 里把 RollMoveAction 排在末尾），所以 aiRng 的消耗次序
        // 也与我们一致；Tsundere 这种「出招时还要抽一次随机队友」的怪才不会错位。
        int nextTurn = state.turn + 1;
        // 只让**这一轮开始时就在场**的怪行动：分裂出来的小史莱姆要到下一轮才动
        // （原版是它们被 SpawnMonsterAction 插进队列、这轮排在后面，实际上等下一轮）。
        int actingCount = state.monsters.size();
        for (int slot = 0; slot < actingCount; slot++) {
            SimMonster m = state.monsters.get(slot);
            if (m.hp <= 0) {
                continue;
            }
            // FadingPower.duringTurn()（Transient 的「消散」）：它自己的回合开始时 -1，
            // 减到 0 的那一刻**当场死掉、这回合不出招**。
            // 建模它的意义：不建的话 Transient 会永远打下去（30/40/50/60…），搜索会极度悲观。
            Integer fading = m.powers.get("Fading");
            if (fading != null) {
                if (fading.intValue() <= 1) {
                    m.hp = 0;
                    continue;
                }
                m.powers.put("Fading", Integer.valueOf(fading.intValue() - 1));
            }
            takeTurn(state, m, nextTurn);
            if (m.hp > 0) {
                rollNextMove(state, m, slot, nextTurn);
            }
        }

        // ④.5 怪物自己的回合末能力（原版 atEndOfTurnPreEndTurnCards / atEndOfRound）
        for (SimMonster m : state.monsters) {
            if (m.hp <= 0) {
                continue;
            }
            // MetallicizePower.atEndOfTurnPreEndTurnCards → GainBlockAction(owner, owner, amount)
            // 字节码核实：这个方法**忽略 isPlayer 参数**，所以怪物身上的金属化同样每回合加格挡
            // （睡着的 Lagavulin 就是靠它）。
            Integer metallicize = m.powers.get("Metallicize");
            if (metallicize != null && metallicize > 0) {
                m.block += metallicize;
            }
            // PlatedArmorPower.atEndOfTurnPreEndTurnCards → GainBlockAction(owner, owner, amount)
            Integer plated = m.powers.get("Plated Armor");
            if (plated != null && plated > 0) {
                m.block += plated;
            }
            // RitualPower.atEndOfRound → ApplyPowerAction(owner, owner, StrengthPower(amount))
            // 🔴 字节码核实：怪物的仪式是 atEndOfRound（玩家+怪物都行动完之后），
            //    不是 atEndOfTurn —— 后者是玩家仪式用的（onPlayer=true 时才触发）。
            //    Cultist 等越拖越强就是靠这条。
            Integer ritual = m.powers.get("Ritual");
            if (ritual != null && ritual > 0) {
                Integer str = m.powers.get("Strength");
                m.powers.put("Strength", (str == null ? 0 : str) + ritual);
            }
            // MalleablePower.atEndOfTurn → amount = basePower（默认 3）
            if (m.powers.containsKey("Malleable")) {
                m.powers.put("Malleable", Integer.valueOf(3));
            }
            // SlowPower.atEndOfRound → amount = 0（一轮结束，玩家这回合打的牌不再加成）
            if (m.powers.containsKey("Slow")) {
                m.powers.put("Slow", Integer.valueOf(0));
            }
            // RegenerateMonsterPower.atEndOfTurn（AwakenedOne 的「再生」）：
            // 没半死/没死就回 amount（10｜A19:15）。原版挂在**它自己回合末**，
            // 我们统一挂在 ④.5（一轮一次，差一次先后不影响回血总额）。
            Integer regenerate = m.powers.get("Regenerate");
            if (regenerate != null && regenerate > 0 && !m.powers.containsKey("Half Dead")) {
                m.hp = Math.min(m.maxHp, m.hp + regenerate);
            }
            // IntangiblePower.atEndOfTurn → amount--，到 0 移除
            // ⚠️ 原版有 justApplied 保护（刚加上的那回合不递减），我们没记这个标志 ——
            //    施加那回合会多递减一次，方向是**低估防御**（安全偏差）。
            io.github.youqing.sts1solver.mirror.DamageCalc.tickIntangible(m.powers);
        }

        // ④.6 意图伤害：估值/展示口径。放在 ④.5 之后，否则本回合刚结算的仪式力量
        //      要等下一回合才反映到意图上（原版是执行伤害时现算，我们存的是快照）。
        for (SimMonster m : state.monsters) {
            if (m.hp <= 0 || m.pendingMove == null) {
                continue;
            }
            MonsterMove planned = m.pendingMove;
            int perHit = planned.attacks()
                    ? io.github.youqing.sts1solver.mirror.DamageCalc
                            .monsterAttackDamage(state, planned.damage, m)
                    : 0;
            m.intentDmg = perHit * planned.hits;
            m.intentAttack = planned.attacks();
        }

        // ⑤ 敌人打完，剩余格挡清零（壁垒除外）
        if (power(state, "Barricade") <= 0) {
            state.block = 0;
        }

        // ⑥ 进入下一回合（意图已在 ④ 里逐只刷新过）
        state.turn = nextTurn;

        // ⑦ 新回合的钩子 → 回能 → 抽牌 → 抽牌后的钩子
        // ⑦.0 回合开始的遗物（原版 applyStartOfTurnRelics 在能力之前）：
        //      水银沙漏 3 点全体纯伤 / 号角夹板（第 2 回合 +14）/ 舵轮（第 3 回合 +18）
        io.github.youqing.sts1solver.mirror.hooks.RelicHooks.onPlayerTurnStart(state);
        Hooks.firePhase(state, HookPhase.START_OF_TURN);
        // 冰淇淋（Ice Cream）：EnergyManager.recharge() 里 `if (hasRelic) EnergyPanel.addEnergy(energy)`
        // —— 也就是**把本回合剩下的能量加回去**，而不是重置成 energyPerTurn。
        if (state.relics.containsKey("Ice Cream")) {
            state.energy += energyPerTurn;
        } else {
            state.energy = energyPerTurn;
        }
        // DrawReductionPower（TimeEater 的 Head Slam）：下回合**少抽** amount 张，用一次就消失。
        // 原版是 onInitialApplication 里 gameHandSize -= amount、atEndOfRound 里减 1 层，
        // 净效果就是「只影响紧接着的那一次抽牌」—— 这里照净效果写，省掉 justApplied 状态。
        int drawCount = handSize;
        Integer drawReduction = state.playerPowers.get("Draw Reduction");
        if (drawReduction != null && drawReduction.intValue() > 0) {
            drawCount = Math.max(0, drawCount - drawReduction.intValue());
            state.playerPowers.remove("Draw Reduction");
        }
        draw(state, drawCount);
        Hooks.firePhase(state, HookPhase.START_OF_TURN_POST_DRAW);
    }

    /** 结算一只怪这一招的全部载荷（原版 {@code takeTurn()} 排进 action 队列的那些动作）。 */
    private static void takeTurn(SimState state, SimMonster m, int nextTurn) {
        MonsterMove move = m.pendingMove;
        if (move != null && move.escape) {
            // 原版 EscapeAction：从怪物组里移除，不算击杀、不结算掉落
            m.hp = 0;
            return;
        }
        if (move != null && ((move.splitInto != null && move.splitCount > 0)
                || (move.splitInto2 != null && move.splitCount2 > 0))) {
            split(state, m, move, nextTurn);
            return;
        }
        if (move == null) {
            // 未镜像的怪：沿用「意图伤害」单段结算（老路径，行为不变）
            if (m.intentDmg > 0) {
                hitPlayer(state, m.intentDmg, m);
            }
            return;
        }
        for (int hit = 0; hit < move.hits; hit++) {
            int damage = io.github.youqing.sts1solver.mirror.DamageCalc
                    .monsterAttackDamage(state, move.damage, m);
            if (damage > 0) {
                int playerHpBefore = state.hp;
                hitPlayer(state, damage, m);
                // PainfulStabsPower.onInflictDamage：攻击造成伤害时往玩家弃牌堆塞 1 张 Wound
                // （每段一次；THORNS 类型不触发，我们这里是普通攻击所以都触发）
                if (m.powers.containsKey("Painful Stabs")) {
                    state.discard.add(new SimCard("Wound", false, -2, -1, "STATUS"));
                }
                // VampireDamageAction：打掉玩家多少血就给自己回多少（ShelledParasite 的 Fell）
                if (move.vampire) {
                    int dealt = playerHpBefore - state.hp;
                    if (dealt > 0) {
                        m.hp = Math.min(m.maxHp, m.hp + dealt);
                    }
                }
            }
        }
        if (move.block > 0) {
            m.block += move.block;
        }
        // SpireShield 的 SMASH：GainBlockAction(this, this, damage.get(1).output)
        // output 是「算完力量/虚弱/易伤之后」的数，所以这里重算一次（纯函数，不吃随机）。
        if (move.extra != null && move.extra.blockFromDamage && move.attacks()) {
            int perHit = io.github.youqing.sts1solver.mirror.DamageCalc
                    .monsterAttackDamage(state, move.damage, m);
            m.block += perHit * move.hits;
        }
        if (move.blockRandomAlly > 0) {
            // GainBlockRandomMonsterAction：候选 = 除自己外的活怪；取 aiRng.random(size-1)
            java.util.List<SimMonster> others = new java.util.ArrayList<SimMonster>();
            for (SimMonster other : state.monsters) {
                if (other != m && other.hp > 0) {
                    others.add(other);
                }
            }
            if (others.isEmpty()) {
                m.block += move.blockRandomAlly;
            } else {
                RngStream ai = state.rng.get(SimState.RngKind.AI);
                int pick = ai == null ? 0 : ai.random(others.size() - 1);
                others.get(pick).block += move.blockRandomAlly;
            }
        }
        // 注意判 `!= 0` 而不是 `> 0`：扣敏捷/扣力量是**负增量**（BanditBear / Lagavulin）
        if (move.playerDebuff != null && move.playerDebuffAmount != 0) {
            Integer prior = state.playerPowers.get(move.playerDebuff);
            state.playerPowers.put(move.playerDebuff,
                    (prior == null ? 0 : prior) + move.playerDebuffAmount);
        }
        if (move.playerDebuff2 != null && move.playerDebuff2Amount != 0) {
            Integer prior = state.playerPowers.get(move.playerDebuff2);
            state.playerPowers.put(move.playerDebuff2,
                    (prior == null ? 0 : prior) + move.playerDebuff2Amount);
        }
        if (move.selfBuff != null && move.selfBuffAmount > 0) {
            // 原版 ApplyPowerAction(owner, owner, power)：给自己叠层
            Integer prior = m.powers.get(move.selfBuff);
            m.powers.put(move.selfBuff, (prior == null ? 0 : prior) + move.selfBuffAmount);
            io.github.youqing.sts1solver.mirror.DamageCalc
                    .markIntangibleFresh(m.powers, move.selfBuff);
        }
        // TheGuardian 的 TwinSmash（4 号招）打完就回攻势姿态：移除 Sharp Hide、重挂 Mode Shift。
        // 原版写在 useTwinSmash 里（ChangeState("Offensive Mode") + RemoveSpecificPowerAction）。
        if ("TheGuardian".equals(m.type) && move.id == 4 && m.hp > 0) {
            m.powers.remove("Sharp Hide");
            MonsterMoves.reopenGuardian(m, state.ascension);
        }
        // 觉醒者的 REBIRTH（3 号招）：原版在 changeState("REBIRTH") 里
        // currentHealth = maxHealth（A9 起 320）+ CanLoseAction —— 回满血、解除半死。
        if ("AwakenedOne".equals(m.type) && move.id == 3 && m.hp > 0) {
            m.hp = m.maxHp;
            m.powers.remove("Half Dead");
        }
        if (move.healAllies > 0) {
            // HealAction：逐只活怪回血，上限是各自 maxHp（可以治自己）
            for (SimMonster ally : state.monsters) {
                if (ally.hp > 0) {
                    ally.hp = Math.min(ally.maxHp, ally.hp + move.healAllies);
                }
            }
        }
        if (move.buffAlliesPower != null && move.buffAlliesAmount > 0) {
            for (SimMonster ally : state.monsters) {
                if (ally.hp <= 0) {
                    continue;
                }
                Integer prior = ally.powers.get(move.buffAlliesPower);
                ally.powers.put(move.buffAlliesPower,
                        (prior == null ? 0 : prior) + move.buffAlliesAmount);
            }
        }
        if (move.statusCard != null && move.statusCount > 0) {
            // MakeTempCardInDiscardAction(new Dazed(), n)：类型是 STATUS（Evolve 会数它）
            for (int i = 0; i < move.statusCount; i++) {
                state.discard.add(new SimCard(move.statusCard, false, -2, -1, "STATUS"));
            }
        }
        if (move.extra != null && move.extra.absentBuff != null
                && move.extra.absentBuffAmount > 0
                && !m.powers.containsKey(move.extra.absentBuff)) {
            // 原版 Nemesis 在 takeTurn 末尾：if (!hasPower("Intangible")) ApplyPower(...)
            m.powers.put(move.extra.absentBuff, move.extra.absentBuffAmount);
            io.github.youqing.sts1solver.mirror.DamageCalc
                    .markIntangibleFresh(m.powers, move.extra.absentBuff);
        }
        applyExtra(state, m, move.extra, nextTurn);
        // Exploder 的 ExplosivePower.duringTurn：先自杀再打 30，本回合结算完就下场
        if (move.extra != null && move.extra.selfDestruct && m.hp > 0) {
            m.hp = 0;
        }
        // Darkling 的 Reincarnate：HealAction(this, this, maxHealth / 2) + 清半死标记
        if (move.extra != null && move.extra.reincarnate) {
            m.hp = Math.max(1, m.maxHp / 2);
            m.powers.remove("Half Dead");
        }
    }

    /**
     * 结算 {@link MonsterMove.Extra}：召唤 / 给特定类型队友加格挡 / 给其他队友加格挡。
     *
     * <p>🔴 召唤的**随机消耗次序**要照抄原版，否则 aiRng 会错位：
     * 原版是「先把 N 只怪造出来（每只：aiRng 选类型 → monsterHpRng 抽血），
     * 再让每只 {@code init()}（aiRng 各抽一次 0..99）」。
     * 以 GremlinLeader 的 Rally 为例，takeTurn 里连排两个 {@code SummonGremlinAction}：
     * 两个**构造器**在入队时就把类型和血抽掉了，{@code update()} 之后才 init。
     * 所以顺序是 类型1/血1、类型2/血2、招1、招2 —— 不是「一只一只走完」。
     */
    private static void applyExtra(SimState state, SimMonster owner, MonsterMove.Extra extra,
                                   int nextTurn) {
        if (extra == null || extra.isEmpty()) {
            return;
        }
        if (extra.summon != null) {
            MonsterMove.Summon s = extra.summon;
            java.util.List<SimMonster> fresh = new java.util.ArrayList<SimMonster>();
            int alive = countAliveInPool(state, s);
            for (int i = 0; i < s.count && alive < s.cap; i++) {
                MonsterMove.Spawn spawn = pickSpawn(state, s);
                if (spawn == null) {
                    break;
                }
                int hp = rollSpawnHp(state, spawn);
                SimMonster child = new SimMonster(spawn.type, hp, hp, 0,
                        java.util.Collections.<String, Integer>emptyMap());
                if (s.minion) {
                    // SummonGremlinAction.update 末尾的 ApplyPowerAction(MinionPower)
                    child.powers.put("Minion", Integer.valueOf(1));
                }
                state.monsters.add(child);
                fresh.add(child);
                alive++;
            }
            // 造完之后才 init（原版 SpawnMonsterAction.update → monster.init()）
            for (SimMonster child : fresh) {
                if (MonsterMoves.supported(child.type)) {
                    int slot = state.monsters.indexOf(child);
                    MonsterMove first = MonsterMoves.next(state, child, nextTurn, slot);
                    child.pendingMove = first;
                    child.pendingMoveId = first.id;
                }
            }
        }
        if (extra.blockAllyType != null && extra.blockAllyTypeAmount > 0) {
            // GainBlockAction(owner=那个怪, source=this, amt)：BronzeOrb 给 BronzeAutomaton 加格挡
            for (SimMonster ally : state.monsters) {
                if (ally.hp > 0 && extra.blockAllyType.equals(ally.type)) {
                    ally.block += extra.blockAllyTypeAmount;
                }
            }
        }
        if (extra.blockAllies > 0) {
            // GremlinLeader 的喊话：自己只加力量，**别人**才加格挡
            for (SimMonster ally : state.monsters) {
                if (ally != owner && ally.hp > 0) {
                    ally.block += extra.blockAllies;
                }
            }
        }
        if (extra.playerDebuff3 != null && extra.playerDebuff3Amount != 0) {
            // TheCollector 的大招一次上三样（Weak + Vulnerable + Frail）
            Integer prior = state.playerPowers.get(extra.playerDebuff3);
            state.playerPowers.put(extra.playerDebuff3,
                    (prior == null ? 0 : prior) + extra.playerDebuff3Amount);
        }
        if (extra.statusDrawCard != null && extra.statusDrawCount > 0) {
            // MakeTempCardInDrawPileAction(card, n, true, true)：随机位置插进**抽牌堆**。
            // 那个「随机位置」用的是 libgdx 的 MathUtils.random，不吃 run seed，我们复现不了；
            // 这里固定插在中间（≈ 均匀随机的期望），偏差方向是中性的。
            for (int i = 0; i < extra.statusDrawCount; i++) {
                int at = state.draw.size() / 2;
                state.draw.add(at, new SimCard(extra.statusDrawCard, false, -2, -1, "STATUS"));
            }
        }
        if (extra.drawPileCards.length > 0) {
            // CorruptHeart 的 Debuff：五种状态牌各 1 张，逐个插进抽牌堆（同一套「插中间」口径）。
            for (String card : extra.drawPileCards) {
                int at = state.draw.size() / 2;
                state.draw.add(at, new SimCard(card, false, -2, -1, "STATUS"));
            }
        }
        if (extra.extraSelfBuff != null && extra.extraSelfBuffAmount > 0) {
            // CorruptHeart 的 Buff 第二个能力（主字段给 Strength +2，这里再挂一样）
            Integer prior = owner.powers.get(extra.extraSelfBuff);
            owner.powers.put(extra.extraSelfBuff,
                    (prior == null ? 0 : prior) + extra.extraSelfBuffAmount);
            io.github.youqing.sts1solver.mirror.DamageCalc
                    .markIntangibleFresh(owner.powers, extra.extraSelfBuff);
        }
        if (extra.cleanseSelf) {
            // 原版 RemoveDebuffsAction：遍历 powers 移除 type == DEBUFF 的。
            // 🔴 我们只存了 ID、没有 PowerType，所以这里用**名字白名单**。
            //    漏掉一个减益的后果是「影子以为它还挂着 Weak」，方向是低估它的输出。
            for (String debuff : SELF_DEBUFFS) {
                owner.powers.remove(debuff);
            }
        }
    }

    /**
     * {@code PowerType.DEBUFF} 的能力 ID 白名单（见 {@code applyExtra} 的说明）。
     *
     * <p>⚠️ 「还债类」减益的原版 ID 是 {@code Flex}（{@code LoseStrengthPower}）与
     * {@code DexLoss}（{@code LoseDexterityPower}）—— 早先按中文/英文描述写成
     * 「Lose Strength / Lose Dexterity」，那是**查无此名**的死条目（2026-10-26 更正）。
     */
    public static final String[] SELF_DEBUFFS = {
            "Weakened", "Vulnerable", "Frail", "Shackled", "Flex", "DexLoss",
            "Strength Down", "Dexterity Down", "Slow", "Constricted", "Poison",
    };

    /** 该召唤池里现在活着的数量（原版 identifySlot 等价：槽位被活怪占着的个数）。 */
    private static int countAliveInPool(SimState state, MonsterMove.Summon summon) {
        int alive = 0;
        for (SimMonster m : state.monsters) {
            if (m.hp <= 0) {
                continue;
            }
            for (MonsterMove.Spawn spawn : summon.pool) {
                if (spawn.type.equals(m.type)) {
                    alive++;
                    break;
                }
            }
        }
        return alive;
    }

    /** 从召唤池里抽一项：池只有一项就不消耗随机，多项就照原版 {@code aiRng.random(0,size-1)}。 */
    private static MonsterMove.Spawn pickSpawn(SimState state, MonsterMove.Summon summon) {
        if (summon.pool.length == 0) {
            return null;
        }
        if (summon.pool.length == 1) {
            return summon.pool[0];
        }
        RngStream ai = state.rng.get(SimState.RngKind.AI);
        int pick = ai == null ? 0 : ai.random(summon.pool.length - 1);
        return summon.pool[pick];
    }

    /** 召唤物血量：{@code setHp(min,max)} → {@code monsterHpRng.random(min,max)}。 */
    private static int rollSpawnHp(SimState state, MonsterMove.Spawn spawn) {
        int min = spawn.hpMin;
        int max = spawn.hpMax;
        if (max < min) {
            max = min;
        }
        RngStream hp = state.rng.get(SimState.RngKind.MONSTER_HP);
        if (hp == null) {
            // 没捕获到血量流（老快照）→ 取区间中值，宁可近似也不抛
            return (min + max) / 2;
        }
        return hp.random(min, max);
    }

    /**
     * 分裂（大史莱姆的 SPLIT）：生成 N 只小怪，原怪自己消失。
     *
     * <p>原版 {@code takeTurn} 的 3 号分支是 `CannotLoseAction` + `SuicideAction(this, false)`
     * + 两条 `SpawnMonsterAction(new AcidSlime_M(x, y, 0, this.currentHealth), false)`。
     * 所以：
     * <ul>
     *   <li>小怪的血量 = **父体分裂那一刻的血量**（不是固定值、也不是减半）；</li>
     *   <li>原怪是「自杀」不是「被打死」—— 不给击杀计数、不结算掉落，
     *       我们只把 hp 置 0（搜索的胜利判定是「全部 hp ≤ 0」，够用）；</li>
     *   <li>每只小怪要掷一次 aiRng：原版 {@code AbstractMonster.<init>} 末尾会走
     *       {@code init() → getMove(aiRng.random(99))}，这里用 {@link MonsterMoves#next} 等价复刻。</li>
     * </ul>
     */
    private static void split(SimState state, SimMonster parent, MonsterMove move, int nextTurn) {
        int childHp = Math.max(1, parent.hp);
        spawnChildren(state, move.splitInto, move.splitCount, childHp, nextTurn);
        spawnChildren(state, move.splitInto2, move.splitCount2, childHp, nextTurn);
        parent.hp = 0;
    }

    private static void spawnChildren(SimState state, String type, int count, int childHp,
                                      int nextTurn) {
        for (int i = 0; i < count; i++) {
            SimMonster child = new SimMonster(type, childHp, childHp, 0,
                    java.util.Collections.<String, Integer>emptyMap());
            state.monsters.add(child);
            if (MonsterMoves.supported(child.type)) {
                int slot = state.monsters.size() - 1;
                MonsterMove first = MonsterMoves.next(state, child, nextTurn, slot);
                child.pendingMove = first;
                child.pendingMoveId = first.id;
            }
        }
    }

    /** 原版 {@code RollMoveAction}：把打过的招记进历史，再决策下一招（没镜像的走观测表）。 */
    private static void rollNextMove(SimState state, SimMonster m, int slot, int turn) {
        if (m.pendingMove != null) {
            m.moveHistory.add(Integer.valueOf(m.pendingMove.id));
        } else if (m.pendingMoveId >= 0) {
            // 捕获来的那一招（我们没算过它，但它是历史的一部分）
            m.moveHistory.add(Integer.valueOf(m.pendingMoveId));
        }
        m.pendingMove = null;
        m.pendingMoveId = -1;
        if (MonsterMoves.supported(m.type)) {
            MonsterMove planned = MonsterMoves.next(state, m, turn, slot);
            m.pendingMove = planned;
            m.pendingMoveId = planned.id;
        } else {
            m.intentDmg = IntentLearning.predict(m.type, turn, m.intentDmg);
        }
    }

    /**
     * 混乱（{@code ConfusionPower.onCardDraw}）：**每抽一张牌**都先掷一次
     * {@code cardRandomRng.random(3)}（0..3，**不管结果变不变都要消耗**），
     * 掷出来的值与原 {@code cost} 不同时就把这张牌的 {@code cost} 改掉（原版是永久改）。
     *
     * <p>这不只是「费用变随机」——它把整手牌的出牌顺序都打乱了，搜索要是按原费用算，
     * 面对 Snecko 的每条路线都会偏乐观。所以必须建模。
     */
    private static SimCard applyConfusion(SimState state, SimCard card) {
        Integer confused = state.playerPowers.get("Confused");
        if (confused == null || confused <= 0) {
            return card;
        }
        RngStream rng = state.rng.get(SimState.RngKind.CARD);
        if (rng == null) {
            return card;
        }
        int newCost = rng.random(3);
        if (newCost == card.cost) {
            return card;
        }
        return new SimCard(card.id, card.upgraded, newCost, card.baseDamage, card.type);
    }

    private static void clearCostForTurn(java.util.List<SimCard> pile) {
        for (int i = 0; i < pile.size(); i++) {
            SimCard c = pile.get(i);
            if (c.costForTurn >= 0) {
                pile.set(i, c.clearCostForTurn());
            }
        }
    }

    /**
     * 公开：荆棘反伤、药水伤害等外部路径要走同一套（格挡吸收 + 死亡豁免）。
     *
     * <p>没有攻击者 → 只能是 {@link DamageKind#THORNS}（原版这类伤害的 {@code info.owner}
     * 要么是怪、要么是 null，反正不会是玩家自己出手的普通攻击）。
     */
    public static void hitPlayer(SimState state, int damage) {
        hitPlayer(state, damage, null, DamageKind.THORNS);
    }

    /**
     * 打玩家。{@code attacker} 是出手的怪（{@code null} = 无攻击者，如药水/荆棘）。
     *
     * <p>带攻击者时要结算**烈焰屏障**（{@code FlameBarrierPower.onAttacked}）：
     * 玩家受击时反弹 {@code amount} 点给攻击者。
     */
    public static int hitPlayer(SimState state, int damage,
                                io.github.youqing.sts1solver.sim.SimMonster attacker) {
        return hitPlayer(state, damage, attacker, DamageKind.NORMAL);
    }

    /**
     * 打玩家的唯一入口，逐条复刻 {@code AbstractPlayer.damage(DamageInfo)} 的管线。
     *
     * <p>顺序（字节码 offset 顺序，别调换）：
     * <ol>
     *   <li>负数夹 0；</li>
     *   <li><b>无实体</b>：{@code damage > 1} → 1（**不看类型**，所以掉血也被压成 1）；</li>
     *   <li><b>格挡</b>：{@code decrementBlock} —— **HP_LOSS 直接跳过**；</li>
     *   <li><b>鸟居</b>：只认 {@code owner != null} 且类型不是 THORNS/HP_LOSS，{@code 1 < dmg <= 5} → 1；</li>
     *   <li><b>钨棒</b>（{@code onLoseHpLast}）：无分支，三种类型都 {@code -1}；</li>
     *   <li>扣血；{@code damage > 0} 时记一次「受伤」（浴血奋战的减费计数）；</li>
     *   <li>死亡豁免 → 烈焰屏障反弹（只认普通攻击）。</li>
     * </ol>
     *
     * <p>🔴 第 3 步与第 5 步的先后是「易伤/挡」与「钨棒」的交界：原版先扣格挡、
     * 再用剩下的伤害过 {@code onLoseHpLast}。所以 6 点伤害撞 4 点格挡 → 剩 2 → 钨棒减到 1。
     *
     * @return **实际掉的血**（格挡/钨棒/无实体之后）。{@code RupturePower.wasHPLost} 认这个数，
     *         被钨棒抹成 0 时它不触发。
     */
    public static int hitPlayer(SimState state, int damage,
                                io.github.youqing.sts1solver.sim.SimMonster attacker,
                                DamageKind kind) {
        if (damage <= 0) {
            return 0;
        }
        DamageKind type = kind == null ? DamageKind.NORMAL : kind;
        boolean normalAttack = type == DamageKind.NORMAL && attacker != null;

        // ① 无实体：IntangiblePlayerPower.atDamageFinalReceive —— 不看 DamageType
        int remaining = io.github.youqing.sts1solver.mirror.DamageCalc
                .intangibleAdjusted(state.playerPowers, damage);
        // ② 格挡：decrementBlock 第一件事就是 `if (type == HP_LOSS) return damage`
        if (type != DamageKind.HP_LOSS && state.block > 0) {
            int absorbed = Math.min(state.block, remaining);
            state.block -= absorbed;
            remaining -= absorbed;
        }
        // ③ 鸟居（Torii.onAttacked）：只认有 owner 的普通攻击
        if (normalAttack) {
            remaining = io.github.youqing.sts1solver.mirror.hooks.RelicHooks
                    .torii(state, remaining);
        }
        // ④ 钨棒（TungstenRod.onLoseHpLast）：原版在 damage() 里无分支遍历遗物
        remaining = io.github.youqing.sts1solver.mirror.hooks.RelicHooks
                .tungstenRod(state, remaining);
        if (remaining <= 0) {
            // 全被挡/抹掉：原版 `if (damage > 0)` 的两个计数都不动，也不触发受伤类效果
            return 0;
        }
        state.hp = Math.max(0, state.hp - remaining);
        // 原版 updateCardsOnDamage()（浴血奋战的减费）+ damagedThisCombat++
        state.hpLossCount++;
        // 致死时先问一次性救命遗物（蜥蜴尾巴）：会改变后续所有路线的可行性
        io.github.youqing.sts1solver.mirror.DeathSaveRelic.applyIfLethal(state);
        // 烈焰屏障：受击反弹（只有普通攻击触发）
        if (normalAttack) {
            io.github.youqing.sts1solver.mirror.DamageCalc.applyFlameBarrier(state, attacker);
        }
        return remaining;
    }

    /**
     * 回合开始的抽牌。
     *
     * <p>🔴 抽牌堆空了必须**洗回弃牌堆**（复刻 {@code EmptyDeckShuffleAction}，消耗 1 次 shuffleRng）。
     * 以前这里是直接 return —— 结果第 3 回合以后模型里的手牌永远是空的，
     * 整场路线的后半段全是「结束回合」，执行层就照着空打，AI 站着被打死（2026-10-23 实机事故）。
     */
    /**
     * 公开抽牌入口：药水（赌徒酿造）用。和回合开始的 {@link #draw} 同一套语义，
     * 包括「抽牌堆空了洗回弃牌堆」。
     */
    public static void drawAfterPotion(SimState state, int count) {
        draw(state, count);
    }

    private static void draw(SimState state, int count) {
        draw(state, count, 0);
    }

    /**
     * @param evolveDepth 递归深度保护：{@code EvolvePower.onCardDraw} 抽到 STATUS 会再抽，
     *                    再抽到 STATUS 还会再抽……原版是 action 异步入队不会死循环，
     *                    我们同步算就要自己限深度。
     */
    private static void draw(SimState state, int count, int evolveDepth) {
        int statusDrawn = 0;
        for (int i = 0; i < count; i++) {
            if (state.hand.size() >= HAND_LIMIT) {
                break;
            }
            if (state.draw.isEmpty()) {
                io.github.youqing.sts1solver.mirror.Effects.reshuffleDiscardIntoDraw(state);
                if (state.draw.isEmpty()) {
                    break;    // 弃牌堆也空了，真的没牌可抽
                }
            }
            io.github.youqing.sts1solver.sim.SimCard drawn =
                    state.draw.remove(state.draw.size() - 1);
            drawn = applyConfusion(state, drawn);
            state.hand.add(drawn);
            if ("STATUS".equals(drawn.type)) {
                statusDrawn++;
            }
        }
        // EvolvePower.onCardDraw：每抽到一张 STATUS 牌，抽 amount 张
        if (evolveDepth < 3 && statusDrawn > 0) {
            Integer evolve = state.playerPowers.get("Evolve");
            if (evolve != null && evolve > 0) {
                draw(state, evolve * statusDrawn, evolveDepth + 1);
            }
        }
    }

    private static int power(SimState state, String id) {
        Integer v = state.playerPowers.get(id);
        return v == null ? 0 : v;
    }
}
