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
        }

        // 每只怪走完整的一轮：结算本招 → 把这一招记进历史 → **立刻**决策下一招。
        // 原版就是这个次序（takeTurn 里把 RollMoveAction 排在末尾），所以 aiRng 的消耗次序
        // 也与我们一致；Tsundere 这种「出招时还要抽一次随机队友」的怪才不会错位。
        int nextTurn = state.turn + 1;
        for (int slot = 0; slot < state.monsters.size(); slot++) {
            SimMonster m = state.monsters.get(slot);
            if (m.hp <= 0) {
                continue;
            }
            takeTurn(state, m);
            rollNextMove(state, m, slot, nextTurn);
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
        Hooks.firePhase(state, HookPhase.START_OF_TURN);
        state.energy = energyPerTurn;
        draw(state, handSize);
        Hooks.firePhase(state, HookPhase.START_OF_TURN_POST_DRAW);
    }

    /** 结算一只怪这一招的全部载荷（原版 {@code takeTurn()} 排进 action 队列的那些动作）。 */
    private static void takeTurn(SimState state, SimMonster m) {
        MonsterMove move = m.pendingMove;
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
                hitPlayer(state, damage, m);
            }
        }
        if (move.block > 0) {
            m.block += move.block;
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
        if (move.playerDebuff != null && move.playerDebuffAmount > 0) {
            Integer prior = state.playerPowers.get(move.playerDebuff);
            state.playerPowers.put(move.playerDebuff,
                    (prior == null ? 0 : prior) + move.playerDebuffAmount);
        }
        if (move.playerDebuff2 != null && move.playerDebuff2Amount > 0) {
            Integer prior = state.playerPowers.get(move.playerDebuff2);
            state.playerPowers.put(move.playerDebuff2,
                    (prior == null ? 0 : prior) + move.playerDebuff2Amount);
        }
        if (move.selfBuff != null && move.selfBuffAmount > 0) {
            // 原版 ApplyPowerAction(owner, owner, power)：给自己叠层
            Integer prior = m.powers.get(move.selfBuff);
            m.powers.put(move.selfBuff, (prior == null ? 0 : prior) + move.selfBuffAmount);
        }
        if (move.statusCard != null && move.statusCount > 0) {
            // MakeTempCardInDiscardAction(new Dazed(), n)：类型是 STATUS（Evolve 会数它）
            for (int i = 0; i < move.statusCount; i++) {
                state.discard.add(new SimCard(move.statusCard, false, -2, -1, "STATUS"));
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

    private static void clearCostForTurn(java.util.List<SimCard> pile) {
        for (int i = 0; i < pile.size(); i++) {
            SimCard c = pile.get(i);
            if (c.costForTurn >= 0) {
                pile.set(i, c.clearCostForTurn());
            }
        }
    }

    /** 公开：荆棘反伤、药水伤害等外部路径要走同一套（格挡吸收 + 死亡豁免）。 */
    public static void hitPlayer(SimState state, int damage) {
        hitPlayer(state, damage, null);
    }

    /**
     * 打玩家。{@code attacker} 是出手的怪（{@code null} = 无攻击者，如药水/自伤）。
     *
     * <p>带攻击者时要结算**烈焰屏障**（{@code FlameBarrierPower.onAttacked}）：
     * 玩家受击时反弹 {@code amount} 点给攻击者。
     */
    public static void hitPlayer(SimState state, int damage, io.github.youqing.sts1solver.sim.SimMonster attacker) {
        // 无实体：所有伤害上限 1（IntangiblePower.atDamageFinalReceive），
        // 在**最终伤害**上生效、扣格挡之前 —— 所以 1 点伤害仍会被格挡完全吸收
        int remaining = io.github.youqing.sts1solver.mirror.DamageCalc
                .intangibleAdjusted(state.playerPowers, damage);
        if (state.block > 0) {
            int absorbed = Math.min(state.block, remaining);
            state.block -= absorbed;
            remaining -= absorbed;
        }
        state.hp = Math.max(0, state.hp - remaining);
        // 致死时先问一次性救命遗物（蜥蜴尾巴）：会改变后续所有路线的可行性
        io.github.youqing.sts1solver.mirror.DeathSaveRelic.applyIfLethal(state);
        // 烈焰屏障：受击反弹（只有普通攻击触发，所以走攻击者那条）
        if (attacker != null) {
            io.github.youqing.sts1solver.mirror.DamageCalc.applyFlameBarrier(state, attacker);
        }
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
