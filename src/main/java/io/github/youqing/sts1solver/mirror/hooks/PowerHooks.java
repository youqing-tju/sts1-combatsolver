package io.github.youqing.sts1solver.mirror.hooks;

import java.util.HashMap;
import java.util.Map;

import io.github.youqing.sts1solver.mirror.ActionRejected;
import io.github.youqing.sts1solver.mirror.CardPlayer;
import io.github.youqing.sts1solver.mirror.DamageCalc;
import io.github.youqing.sts1solver.mirror.DeathSaveRelic;
import io.github.youqing.sts1solver.mirror.Effects;
import io.github.youqing.sts1solver.sim.RngStream;
import io.github.youqing.sts1solver.sim.SimCard;
import io.github.youqing.sts1solver.sim.SimMonster;
import io.github.youqing.sts1solver.sim.SimState;

/**
 * 能力牌的「到点执行」语义：power ID → (阶段 → 处理函数)。
 *
 * <p>照蓝本 {@code reference/sts2-combatsolver/src/Engine/InCombat/Mirrors/Hooks/TurnEnd/
 * BeforeSideTurnEndMirrors.cs} 的结构：**每个阶段一张注册表**，派发时按战斗中的监听者
 * 顺序逐个查表，查不到就什么都不做（原版基类是空实现）。蓝本用的是 C# 泛型方法镜像；
 * 我们的影子状态里 power 只有「ID + 层数」，所以键就是 ID 字符串。
 *
 * <p>每条实现都注明了对应的原版方法，方便复核。
 */
public final class PowerHooks {

    /** 一层钩子处理函数。{@code amount} 是这个 power 的层数（= 原版 {@code AbstractPower.amount}）。 */
    public interface Handler {
        void fire(SimState state, int amount, HookContext ctx);
    }

    private static final Map<String, Map<HookPhase, Handler>> TABLE =
            new HashMap<String, Map<HookPhase, Handler>>();

    /** 燃烧每次自伤的数值：原版 {@code new CombustPower(this.p, 1, magic)}，升级只加伤害。 */
    private static final int COMBUST_HP_LOSS = 1;

    /** Panache 计数满 5 张就归零重来（原版常量 CARD_AMT）。 */
    private static final int PANACHE_CARDS = 5;

    /** 防递归：Mayhem 打出的牌可能又是「打出抽牌堆顶」类效果。 */
    private static final int MAX_NESTED_PLAYS = 6;
    private static int nestedPlays;

    private PowerHooks() {
    }

    private static void reg(String powerId, HookPhase phase, Handler handler) {
        Map<HookPhase, Handler> byPhase = TABLE.get(powerId);
        if (byPhase == null) {
            byPhase = new HashMap<HookPhase, Handler>();
            TABLE.put(powerId, byPhase);
        }
        byPhase.put(phase, handler);
    }

    public static Handler lookup(String powerId, HookPhase phase) {
        Map<HookPhase, Handler> byPhase = TABLE.get(powerId);
        return byPhase == null ? null : byPhase.get(phase);
    }

    /** 这个 power 在该阶段有没有登记。诊断用。 */
    public static boolean supports(String powerId, HookPhase phase) {
        return lookup(powerId, phase) != null;
    }

    static {
        // ---- 回合末，弃手牌之前 -------------------------------------------------
        // MetallicizePower.atEndOfTurnPreEndTurnCards → GainBlockAction(owner, owner, amount)
        // 注意是裸加：GainBlockAction 这个重载不经过 applyPowers，所以吃不到敏捷。
        reg("Metallicize", HookPhase.END_OF_TURN_PRE_CARDS, new Handler() {
            @Override
            public void fire(SimState state, int amount, HookContext ctx) {
                // 走 gainBlock 而不是直接 +=，因为原版 GainBlockAction 也会触发 onGainedBlock
                // （尖刺外壳会跟着打一下）
                Hooks.gainBlock(state, amount);
            }
        });

        // ---- 回合末，弃手牌之后 -------------------------------------------------
        // EntanglePower.atEndOfTurn → 自己消失（「本回合不能打出攻击牌」只管这一回合）
        reg("Entangled", HookPhase.END_OF_TURN, new Handler() {
            @Override
            public void fire(SimState state, int amount, HookContext ctx) {
                state.playerPowers.remove("Entangled");
            }
        });

        // IntangiblePower.atEndOfTurn → amount--，到 0 移除（伤害上限 1 的那条见 DamageCalc）
        // ⚠️ 原版 justApplied 保护未建模：施加那回合会多递减一次（低估防御，安全方向）
        reg("Intangible", HookPhase.END_OF_TURN, new Handler() {
            @Override
            public void fire(SimState state, int amount, HookContext ctx) {
                DamageCalc.tickIntangible(state.playerPowers);
            }
        });

        // GainStrengthPower.atEndOfTurn（Flex「活动肌肉」）→ ApplyPowerAction(..., Strength, -amount)
        // 即回合末减去 amount 点力量。原版 power ID 是 "Gain Strength"，不是 "Flex"。
        reg("Gain Strength", HookPhase.END_OF_TURN, new Handler() {
            @Override
            public void fire(SimState state, int amount, HookContext ctx) {
                addPower(state, DamageCalc.STRENGTH, -amount);
                state.playerPowers.remove("Gain Strength");
            }
        });

        // RitualPower.atEndOfTurn（邪教徒药水）→ ApplyPowerAction(owner, owner, StrengthPower(amount))
        // 🔴 玩家版走 atEndOfTurn、怪物版走 atEndOfRound（后者在 CombatFlow ④.5），两条是
        //    同一个能力的两个钩子，靠 owner.isPlayer 分开 —— 别以为「已经做过了」。
        reg("Ritual", HookPhase.END_OF_TURN, new Handler() {
            @Override
            public void fire(SimState state, int amount, HookContext ctx) {
                addPower(state, DamageCalc.STRENGTH, amount);
            }
        });

        // RegenPower.atEndOfTurn（再生药水）→ RegenAction：先 heal(amount)，再 amount--，
        // 减到 0 就整个移除。字节码里那个衰减带 `target.isPlayer` 条件（怪物不衰减）。
        // 🔴 ID 是 "Regeneration"，不是 "Regen"。
        reg("Regeneration", HookPhase.END_OF_TURN, new Handler() {
            @Override
            public void fire(SimState state, int amount, HookContext ctx) {
                state.hp = Math.min(state.maxHp, state.hp + RelicHooks.healAmount(state, amount));
                RelicHooks.syncRedSkull(state);
                if (amount - 1 <= 0) {
                    state.playerPowers.remove("Regeneration");
                } else {
                    state.playerPowers.put("Regeneration", Integer.valueOf(amount - 1));
                }
            }
        });

        // NextTurnBlockPower.atStartOfTurn（自形成黏土）：GainBlockAction(amount) →
        // **整条移除**（不是每回合减 1）。原版 GainBlockAction 走 AbstractCreature.addBlock，
        // 只加不乘敏捷（字节码 update() 里就是 addBlock(amount)），所以这里是裸数值。
        reg(RelicHooks.NEXT_TURN_BLOCK, HookPhase.START_OF_TURN, new Handler() {
            @Override
            public void fire(SimState state, int amount, HookContext ctx) {
                if (amount > 0) {
                    state.block += amount;
                }
                state.playerPowers.remove(RelicHooks.NEXT_TURN_BLOCK);
            }
        });

        // LoseStrengthPower.atEndOfTurn（兴奋剂药水 / 活动肌肉的还债）→ Strength -= amount，然后自删。
        // 🔴 ID 是 "Flex"（LoseStrengthPower.POWER_ID），不是 "Lose Strength"。
        reg("Flex", HookPhase.END_OF_TURN, new Handler() {
            @Override
            public void fire(SimState state, int amount, HookContext ctx) {
                addPower(state, DamageCalc.STRENGTH, -amount);
                state.playerPowers.remove("Flex");
            }
        });

        // LoseDexterityPower.atEndOfTurn（速度药水）→ Dexterity -= amount，然后自删。
        // 🔴 ID 是 "DexLoss"（LoseDexterityPower.POWER_ID），不是 "Lose Dexterity"。
        reg("DexLoss", HookPhase.END_OF_TURN, new Handler() {
            @Override
            public void fire(SimState state, int amount, HookContext ctx) {
                addPower(state, DamageCalc.DEXTERITY, -amount);
                state.playerPowers.remove("DexLoss");
            }
        });

        // CombustPower.atEndOfTurn → LoseHPAction(owner, owner, hpLoss) + DamageAllEnemies(amount)
        reg("Combust", HookPhase.END_OF_TURN, new Handler() {
            @Override
            public void fire(SimState state, int amount, HookContext ctx) {
                // 自伤走 HP_LOSS：无视格挡，但吃无实体/钨棒（与卡牌的 loseHp 同一条路）
                io.github.youqing.sts1solver.sim.CombatFlow.hitPlayer(state, COMBUST_HP_LOSS, null,
                        io.github.youqing.sts1solver.sim.DamageKind.HP_LOSS);
                damageAll(state, amount);
            }
        });

        // TheBombPower.atEndOfTurn → ReducePowerAction(1)；amount == 1 时先炸再减
        reg("TheBomb", HookPhase.END_OF_TURN, new Handler() {
            @Override
            public void fire(SimState state, int amount, HookContext ctx) {
                if (amount <= 1) {
                    damageAll(state, aux(state, "TheBomb.damage"));
                    state.playerPowers.remove("TheBomb");
                } else {
                    state.playerPowers.put("TheBomb", amount - 1);
                }
            }
        });

        // OmegaPower(watcher).atEndOfTurn → 对全体造成 amount（层数即伤害）
        reg("OmegaPower", HookPhase.END_OF_TURN, new Handler() {
            @Override
            public void fire(SimState state, int amount, HookContext ctx) {
                damageAll(state, amount);
            }
        });

        // ---- 回合初 -------------------------------------------------------------
        // BerserkPower.atStartOfTurn → GainEnergyAction(amount)。
        // 🔴 不能挂 START_OF_TURN：那个阶段在 CombatFlow 的 `energy = energyPerTurn` **之前**，
        //    加的会被回能覆盖掉。原版是 action 入队、回能之后才结算，
        //    所以挂在回能之后的 START_OF_TURN_POST_DRAW（抽牌不耗能量，效果等价）。
        reg("Berserk", HookPhase.START_OF_TURN_POST_DRAW, new Handler() {
            @Override
            public void fire(SimState state, int amount, HookContext ctx) {
                state.energy += amount;
            }
        });

        // DemonFormPower.atStartOfTurnPostDraw → ApplyPowerAction(owner, owner, Strength, amount)
        reg("Demon Form", HookPhase.START_OF_TURN_POST_DRAW, new Handler() {
            @Override
            public void fire(SimState state, int amount, HookContext ctx) {
                addPower(state, DamageCalc.STRENGTH, amount);
            }
        });

        // MagnetismPower.atStartOfTurn → 每层塞一张随机无色牌进手牌
        reg("Magnetism", HookPhase.START_OF_TURN, new Handler() {
            @Override
            public void fire(SimState state, int amount, HookContext ctx) {
                for (int i = 0; i < amount; i++) {
                    Effects.addRandomColorlessToHand(1, false).applyTo(state, -1, null);
                }
            }
        });

        // MayhemPower.atStartOfTurn → 随便挑个目标 + PlayTopCardAction(target, true)
        // 注意目标选择本身就会消耗一次 cardRandomRng（原版在 action.update 里先取随机怪），
        // 所以哪怕抽牌堆是空的，这一步也要照样抽一次，否则后面整条 RNG 流都会错位。
        reg("Mayhem", HookPhase.START_OF_TURN, new Handler() {
            @Override
            public void fire(SimState state, int amount, HookContext ctx) {
                for (int i = 0; i < amount; i++) {
                    int target = pickRandomAliveMonster(state);
                    if (state.draw.isEmpty()) {
                        continue;
                    }
                    playTopOfDrawPile(state, target);
                }
            }
        });

        // PanachePower.atStartOfTurn → amount = 5
        reg("Panache", HookPhase.START_OF_TURN, new Handler() {
            @Override
            public void fire(SimState state, int amount, HookContext ctx) {
                state.playerPowers.put("Panache", PANACHE_CARDS);
            }
        });

        // ---- 出牌 ---------------------------------------------------------------
        // DoubleTapPower.onUseCard：打出 ATTACK 时，用 makeSameInstanceOf() 复制一张
        // 再打一遍（进 limbo）。atEndOfTurn 递减 amount。
        // 🔴 原版判据是 !card.purgeOnUse && type == ATTACK && amount > 0。
        //    复制的那张不会再触发双击（它在 limbo 里、purgeOnUse 不同），
        //    我们用 powerAux 里的标志做递归保护。
        reg("Double Tap", HookPhase.USE_CARD, new Handler() {
            @Override
            public void fire(SimState state, int amount, HookContext ctx) {
                if (ctx.card == null || !"ATTACK".equals(ctx.card.type)) {
                    return;
                }
                if (state.powerAux.containsKey("DoubleTap.replaying")) {
                    return;      // 复制的那张不再双击
                }
                state.powerAux.put("DoubleTap.replaying", 1);
                try {
                    io.github.youqing.sts1solver.sim.SimCard copy = new io.github.youqing.sts1solver.sim.SimCard(
                            ctx.card.id, ctx.card.upgraded, ctx.card.cost,
                            ctx.card.baseDamage, ctx.card.type, -1);
                    io.github.youqing.sts1solver.mirror.CardPlayer.playFromPile(
                            state, copy, ctx.targetIndex);
                } finally {
                    state.powerAux.remove("DoubleTap.replaying");
                }
                // atEndOfTurn 递减
                if (amount <= 1) {
                    state.playerPowers.remove("Double Tap");
                } else {
                    state.playerPowers.put("Double Tap", amount - 1);
                }
            }
        });

        // PanachePower.onUseCard → amount--；归零时对全体造成 damage 并重置
        reg("Panache", HookPhase.USE_CARD, new Handler() {
            @Override
            public void fire(SimState state, int amount, HookContext ctx) {
                int left = amount - 1;
                if (left > 0) {
                    state.playerPowers.put("Panache", left);
                    return;
                }
                damageAll(state, aux(state, "Panache.damage"));
                state.playerPowers.put("Panache", PANACHE_CARDS);
            }
        });

        // ---- 施加状态 -----------------------------------------------------------
        // SadisticPower.onApplyPower → 对「刚被挂上减益的敌人」造成 amount 点伤害
        reg("Sadistic", HookPhase.APPLY_POWER, new Handler() {
            @Override
            public void fire(SimState state, int amount, HookContext ctx) {
                if (ctx.appliedTo == null || ctx.appliedTo.hp <= 0) {
                    return;
                }
                DamageCalc.hit(state, ctx.appliedTo, amount);
            }
        });

        // ---- 获得格挡 -----------------------------------------------------------
        // JuggernautPower.onGainedBlock → DamageRandomEnemyAction(THORNS, amount)
        reg("Juggernaut", HookPhase.GAINED_BLOCK, new Handler() {
            @Override
            public void fire(SimState state, int amount, HookContext ctx) {
                if (ctx.gainedBlock <= 0) {
                    return;
                }
                int target = pickRandomAliveMonster(state);
                SimMonster monster = aliveAt(state, target);
                if (monster != null) {
                    DamageCalc.hit(state, monster, amount);
                }
            }
        });
    }

    // ------------------------------------------------------------------ helpers

    /** 全体伤害：不经过力量/易伤（原版用 DamageType.THORNS + createDamageMatrix(_, true)），但仍会被格挡吃掉。 */
    static void damageAll(SimState state, int amount) {
        if (amount <= 0) {
            return;
        }
        for (SimMonster m : state.monsters) {
            if (m.hp > 0) {
                DamageCalc.hit(state, m, amount);
            }
        }
    }

    /**
     * 随机存活怪的下标（对应原版 {@code MonsterGroup.getRandomMonster(null, true, cardRandomRng)}）。
     * 返回的是**存活列表**里的下标，用 {@link #aliveAt} 取回实体。
     */
    static int pickRandomAliveMonster(SimState state) {
        int alive = 0;
        for (SimMonster m : state.monsters) {
            if (m.hp > 0) {
                alive++;
            }
        }
        if (alive <= 0) {
            return -1;
        }
        RngStream rng = state.rng.get(SimState.RngKind.CARD);
        return rng == null ? 0 : rng.random(alive - 1);
    }

    static SimMonster aliveAt(SimState state, int aliveIndex) {
        if (aliveIndex < 0) {
            return null;
        }
        int seen = 0;
        for (SimMonster m : state.monsters) {
            if (m.hp > 0) {
                if (seen == aliveIndex) {
                    return m;
                }
                seen++;
            }
        }
        return null;
    }

    static void addPower(SimState state, String id, int amount) {
        Integer current = state.playerPowers.get(id);
        state.playerPowers.put(id, (current == null ? 0 : current) + amount);
    }

    /** 读「层数之外的附属数值」（Panache 的伤害、炸弹的伤害），见 {@link SimState#powerAux}。 */
    static int aux(SimState state, String key) {
        Integer v = state.powerAux.get(key);
        if (v == null) {
            // 宁可报不支持，也不许用 0 当成正确值静默算下去
            throw new ActionRejected("missing power aux '" + key + "' (state captured without it?)");
        }
        return v;
    }

    private static void playTopOfDrawPile(SimState state, int aliveIndex) {
        if (nestedPlays >= MAX_NESTED_PLAYS) {
            throw new ActionRejected("nested autoplay too deep (Mayhem loop?)");
        }
        SimCard top = state.draw.remove(state.draw.size() - 1);
        SimMonster target = aliveAt(state, aliveIndex);
        int targetIndex = target == null ? -1 : state.monsters.indexOf(target);
        nestedPlays++;
        try {
            CardPlayer.playFromPile(state, top, targetIndex);
        } finally {
            nestedPlays--;
        }
    }
}
