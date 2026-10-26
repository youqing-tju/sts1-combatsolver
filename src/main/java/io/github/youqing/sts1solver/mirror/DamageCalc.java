package io.github.youqing.sts1solver.mirror;

import io.github.youqing.sts1solver.sim.SimMonster;
import io.github.youqing.sts1solver.sim.SimState;

/**
 * The vanilla damage pipeline, reduced to what a single-player Ironclad fight needs.
 *
 * <p>Order matters and follows the game: base → Strength → Weak (attacker, ×0.75) →
 * Vulnerable (target, ×1.5), flooring at each multiplicative step. Getting the order or
 * the rounding wrong produces plausible-looking but wrong numbers, which is the single
 * hardest class of bug to notice later, so each step is unit-tested.
 */
public final class DamageCalc {

    public static final String STRENGTH = "Strength";
    public static final String DEXTERITY = "Dexterity";
    public static final String VULNERABLE = "Vulnerable";
    public static final String WEAKENED = "Weakened";
    public static final String FRAIL = "Frail";

    private DamageCalc() {
    }

    /** Damage an attack card deals after attacker and target modifiers. */
    public static int attackDamage(SimState state, int base, SimMonster target) {
        int dmg = attackDamageWithoutRelics(state, base, target);
        // 笔尖：计数为 9 时打出的这一击（= 第 10 击）翻倍。原版是 atDamageGive，
        // 作用在力量/虚弱/易伤之后，所以放在最后一步。
        Integer penNib = state.relics.get("Pen Nib");
        if (penNib != null && penNib == 9) {
            dmg = (int) Math.floor(dmg * 2.0f);
        }
        return Math.max(0, dmg);
    }

    private static int attackDamageWithoutRelics(SimState state, int base, SimMonster target) {
        int dmg = base + power(state, STRENGTH);
        if (power(state, WEAKENED) > 0) {
            dmg = (int) Math.floor(dmg * 0.75f);
        }
        if (target != null && powers(target).containsKey(VULNERABLE)
                && powers(target).get(VULNERABLE) > 0) {
            // 纸青蛙把「玩家打敌人」的易伤倍率从 1.5 提到 1.75
            // （敌人打玩家那条仍是 1.5 —— 见 monsterAttackDamage）
            dmg = (int) Math.floor(dmg
                    * io.github.youqing.sts1solver.mirror.hooks.RelicHooks
                            .vulnerableMultiplier(state));
        }
        // SlowPower.atDamageReceive：玩家这回合每打一张牌，这只怪受到的伤害 +10%
        // （amount 在 onAfterUseCard 里 +1，atEndOfRound 清零；只有 NORMAL 类型吃这个）
        if (target != null) {
            Integer slow = powers(target).get("Slow");
            if (slow != null && slow.intValue() > 0) {
                dmg = (int) Math.floor(dmg * (1.0f + slow.intValue() * 0.1f));
            }
        }
        return Math.max(0, dmg);
    }

    /**
     * 怪物打玩家的伤害（原版 {@code DamageInfo.applyPowers} 的出手方是怪物那种情况）。
     *
     * <p>顺序与玩家向完全一致，只是「谁的力量 / 谁的虚弱 / 谁的易伤」对调：
     * <pre>
     * base → + 怪物力量 → ×0.75（怪物虚弱）→ ×1.5（玩家易伤）
     * </pre>
     * 每步都是 float 乘完再截断（与原版一致，不是四舍五入）。
     */
    public static int monsterAttackDamage(SimState state, int base, SimMonster attacker) {
        int dmg = base;
        if (attacker != null) {
            Integer strength = attacker.powers.get(STRENGTH);
            dmg += strength == null ? 0 : strength.intValue();
            Integer weak = attacker.powers.get(WEAKENED);
            if (weak != null && weak.intValue() > 0) {
                dmg = (int) Math.floor(dmg * 0.75f);
            }
        }
        Integer vulnerable = state.playerPowers.get(VULNERABLE);
        if (vulnerable != null && vulnerable.intValue() > 0) {
            dmg = (int) Math.floor(dmg * 1.5f);
        }
        return Math.max(0, dmg);
    }

    /** Block gained from a skill card. */
    public static int blockAmount(SimState state, int base) {
        // NoBlockPower.modifyBlockLast 直接 return 0.0f —— 有它就完全不能获得格挡
        Integer noBlock = state.playerPowers.get(NO_BLOCK);
        if (noBlock != null && noBlock > 0) {
            return 0;
        }
        int amount = base + power(state, DEXTERITY);
        if (power(state, FRAIL) > 0) {
            amount = (int) Math.floor(amount * 0.75f);
        }
        return Math.max(0, amount);
    }

    /**
     * Applies damage to a monster: block soaks first, the rest hits HP.
     * Dead monsters are removed afterwards (the game keeps them during the death
     * animation, so expect a difference if you diff mid-animation).
     *
     * <p>打完还要结算**荆棘反伤**（{@code ThornsPower.onAttacked}）：
     * 有攻击者、且不是 THORNS/HP_LOSS 类型时，把 {@code amount} 点伤害反弹给攻击者。
     * 反伤走玩家的格挡/血量，会触发死亡豁免。
     */
    public static void hit(SimState state, SimMonster target, int damage) {
        int hpBefore = target.hp;
        damage = capByInvincible(target, damage);
        hitAndReturnDealt(target, damage);
        // Lagavulin.damage(DamageInfo) 被重写过：**血量真的变了** 且还没醒过 → 立刻醒并晕一回合。
        // 这是它作为精英的核心机制（睡着的 3 回合是可以白打的窗口，一旦动手就换它先手）。
        if (target.hp != hpBefore) {
            maybeHalfKillDarkling(state, target);
            wakeSleepingLagavulin(target);
            maybeQueueSplit(target);
            // TheGuardian：Mode Shift 的量与 dmgTaken 同步递减，归零就切防御姿态
            Integer modeShift = target.powers.get("Mode Shift");
            if (modeShift != null) {
                int dealt = hpBefore - target.hp;
                target.powers.put("Mode Shift",
                        Integer.valueOf(modeShift.intValue() - dealt));
                io.github.youqing.sts1solver.sim.MonsterMoves.maybeShiftGuardian(target);
            }
        }
        applyThorns(state, target);
        applyMalleable(target);
        maybeRerollCompulsive(state, target, damage);
        maybeAwaken(target);
    }

    /**
     * 觉醒者换形态（{@code AwakenedOne.damage} 重写）：血量归零且还没「半死」过时，
     * 原版做四件事 ——
     * <ol>
     *   <li>{@code halfDead = true}（这一下不算死；房间 {@code cannotLose = true}）；</li>
     *   <li>摘掉所有 DEBUFF，外加 {@code Curiosity} / {@code Unawakened} / {@code Shackled}
     *       （所以二形态不再「你打能力牌它加力量」）；</li>
     *   <li>{@code setMove(3, UNKNOWN)} —— 下一招固定是 REBIRTH；</li>
     *   <li>{@code firstTurn = true; form1 = false}。</li>
     * </ol>
     *
     * <p>影子里的等价写法：血留 **1**（同 Darkling 的理由 —— 0 血会被当尸体跳过）、
     * 打 {@code Half Dead} 标记、摘能力和减益、把下一招直接钉成 {@code none(3)}。
     * REBIRTH 的**回满血**在 {@code CombatFlow.takeTurn}（原版在
     * {@code changeState("REBIRTH")}：{@code currentHealth = maxHealth} + CanLoseAction）。
     */
    private static void maybeAwaken(SimMonster target) {
        if (!"AwakenedOne".equals(target.type) || target.hp > 0
                || !target.powers.containsKey("Unawakened")) {
            return;
        }
        target.powers.put("Half Dead", Integer.valueOf(1));
        target.hp = 1;
        target.powers.remove("Unawakened");
        target.powers.remove("Curiosity");
        target.powers.remove("Shackled");
        for (String debuff : io.github.youqing.sts1solver.sim.CombatFlow.SELF_DEBUFFS) {
            target.powers.remove(debuff);
        }
        target.pendingMove = io.github.youqing.sts1solver.sim.MonsterMove.none(3);
        target.pendingMoveId = 3;
    }

    /**
     * 强迫症（{@code ReactivePower}，ID {@code "Compulsive"}，缠绕蔓的「挨打改招」）。
     *
     * <p>字节码 {@code onAttacked(info, damage)}：
     * {@code info.owner != null && type != HP_LOSS && type != THORNS && damage > 0
     * && damage < owner.currentHealth} → {@code addToBot(new RollMoveAction(owner))}。
     * 也就是「挨了一下、但这下没打死它」就**立刻重掷下一招**（消耗一次 {@code aiRng.random(99)}，
     * 加上 getMove 内部的递归随机）。多段攻击每一段都会各触发一次。
     *
     * <p>⚠️ 我们拿不到 {@code DamageInfo.type}：{@code hit()} 只接伤害数值。
     * 现存的调用点都是玩家攻击（普通类型），所以按普通类型处理；THORNS/HP_LOSS 那两类
     * （例如玩家荆棘反伤）会被多算一次重掷，方向是「多消耗一点随机」，不影响数值结算。
     */
    private static void maybeRerollCompulsive(SimState state, SimMonster target, int damage) {
        if (damage <= 0 || target.hp <= 0 || state == null) {
            return;
        }
        if (!target.powers.containsKey("Compulsive")
                || !io.github.youqing.sts1solver.sim.MonsterMoves.supported(target.type)) {
            return;
        }
        int slot = state.monsters.indexOf(target);
        io.github.youqing.sts1solver.sim.MonsterMove next =
                io.github.youqing.sts1solver.sim.MonsterMoves
                        .next(state, target, state.turn, slot < 0 ? 0 : slot);
        target.pendingMove = next;
        target.pendingMoveId = next.id;
    }

    /**
     * 无懈可击（{@code InvinciblePower.onAttackedToChangeDamage}，腐化之心）：
     * **每回合最多掉 amount 点**，超出的部分直接不算。原版逐次扣减同一个 {@code amount}，
     * 所以是「一回合累计上限」而不是「单次上限」；`atStartOfTurn` 把 amount 重置成 maxAmt。
     *
     * @return 实际会落地的伤害
     */
    private static int capByInvincible(SimMonster target, int damage) {
        if (target == null || damage <= 0) {
            return damage;
        }
        Integer remaining = target.powers.get("Invincible");
        if (remaining == null) {
            return damage;
        }
        int left = Math.max(0, remaining.intValue());
        int capped = Math.min(damage, left);
        target.powers.put("Invincible", Integer.valueOf(left - capped));
        return capped;
    }

    /**
     * 韧性（{@code MalleablePower.onAttacked}）    /**
     * 韧性（{@code MalleablePower.onAttacked}）：被打中一次 → 加 {@code amount} 点格挡，
     * 然后 {@code amount++}（所以同一回合第二下挡得更多）。回合末 {@code atEndOfTurn}
     * 把 {@code amount} 重置回 {@code basePower}（默认 3，见 {@code CombatFlow} ④.5）。
     *
     * <p>⚠️ 这里有悖直觉：字节码确实是「挡 = amount，然后 amount+1」，
     * 不是「固定挡 3、最多三次」。照字节码抄，别照 wiki 印象改。
     */
    private static void applyMalleable(SimMonster target) {
        Integer malleable = target.powers.get("Malleable");
        if (malleable == null || malleable.intValue() <= 0) {
            return;
        }
        target.block += malleable.intValue();
        target.powers.put("Malleable", Integer.valueOf(malleable.intValue() + 1));
    }

    /**
     * 小黑（{@code Darkling.damage}）：**第一次被打死不算死**。
     *
     * <p>原版：{@code super.damage(info); if (currentHealth <= 0 && !halfDead) { halfDead = true; ... }}，
     * 半死期间它照样站着（战斗不能结束，`cannotLose = true`），下一回合先「…」再复活回半血。
     *
     * <p>影子里的等价写法：把血量抬到 **1**（而不是 0）并打上 {@code Half Dead} 标记 ——
     * 这样它不会被当成尸体跳过、也不会触发胜利判定。原版这一窗口里血量显示 0 但确实打不死，
     * 差 1 点血量可以忽略。
     *
     * <p>**三只联动**：全部半死时它们才真的全灭（原版在同一段里干掉所有 Darkling）。
     */
    private static void maybeHalfKillDarkling(SimState state, SimMonster target) {
        if (!"Darkling".equals(target.type) || target.hp > 0
                || target.powers.containsKey("Half Dead")) {
            return;
        }
        target.powers.put("Half Dead", Integer.valueOf(1));
        target.hp = 1;
        for (SimMonster m : state.monsters) {
            if ("Darkling".equals(m.type) && m.hp > 0 && !m.powers.containsKey("Half Dead")) {
                return;                       // 还有活蹦乱跳的小黑 → 战斗继续
            }
        }
        for (SimMonster m : state.monsters) {
            if ("Darkling".equals(m.type)) {
                m.hp = 0;                     // 三只全半死 → 一起真死
            }
        }
    }

    /**
     * 大史莱姆半血分裂（{@code AcidSlime_L.damage} / {@code SpikeSlime_L.damage} 的重写）。
     *
     * <p>原版判据四条：{@code !isDying && currentHealth <= maxHealth/2
     * && nextMove != 3 && !splitTriggered}，满足就把下一招改成 3(SPLIT)。
     * 「还没分裂过」用「当前排的不是 3」等价（改过之后就不会再触发）。
     */
    private static void maybeQueueSplit(SimMonster target) {
        if (target.hp <= 0 || target.hp * 2 > target.maxHp) {
            return;
        }
        if (target.pendingMove != null && target.pendingMove.id == 3) {
            return;
        }
        io.github.youqing.sts1solver.sim.MonsterMove split =
                io.github.youqing.sts1solver.sim.MonsterMoves.splitMove(target.type);
        if (split == null) {
            return;
        }
        target.pendingMove = split;
        target.pendingMoveId = 3;
    }

    /**
     * 睡着的 Lagavulin 一挨打就醒：{@code setMove((byte)4, Intent.STUN)} +
     * {@code ChangeState("OPEN")}（后者会 {@code ReducePowerAction(Metallicize, 8)}）。
     *
     * <p>判据用「当前排的招还是 5(SLEEP)」—— 原版用 {@code isOutTriggered} 布尔，
     * 但那个布尔在影子里的等价物就是「还没被改过招」；改过一次之后 pendingMove 就不是 5 了，
     * 所以不会重复触发。
     */
    private static void wakeSleepingLagavulin(SimMonster target) {
        if (!"Lagavulin".equals(target.type)
                || target.pendingMove == null
                || target.pendingMove.id != 5) {
            return;
        }
        target.pendingMove = io.github.youqing.sts1solver.sim.MonsterMove.none(4);   // STUN
        target.pendingMoveId = 4;
        Integer metallicize = target.powers.get("Metallicize");
        if (metallicize != null) {
            int left = metallicize.intValue() - 8;
            if (left > 0) {
                target.powers.put("Metallicize", Integer.valueOf(left));
            } else {
                target.powers.remove("Metallicize");
            }
        }
    }

    /**
     * 荆棘反伤（{@code ThornsPower.onAttacked}）。
     *
     * <p>原版判据是 {@code info.owner != null && info.owner != owner
     * && type != THORNS && type != HP_LOSS} —— 也就是**只有普通攻击**会触发反伤，
     * 荆棘打荆棘、自伤都不反。我们这条路径只有普通攻击（自伤走 {@code Effects.loseHp}、
     * 药水走 THORNS 类型），所以恒过。
     *
     * <p>反伤也走 {@code hitPlayer}（吃玩家格挡），所以会触发死亡豁免。
     */
    private static void applyThorns(SimState state, SimMonster target) {
        Integer thorns = target.powers.get(THORNS);
        if (thorns == null || thorns <= 0) {
            return;
        }
        io.github.youqing.sts1solver.sim.CombatFlow.hitPlayer(state, thorns);
    }

    /**
     * 烈焰屏障（{@code FlameBarrierPower.onAttacked}）：玩家**受击时**反弹
     * {@code amount} 点给攻击者。
     *
     * <p>原版判据 {@code info.owner != null && type != THORNS && type != HP_LOSS
     * && info.owner != owner} —— 与荆棘同口径（只有普通攻击触发，自伤/荆棘不触发）。
     *
     * <p>这是**玩家的能力**，所以挂在 {@code hitPlayer} 上，不是打怪那条路径。
     * 反弹走怪物的格挡/血量。
     */
    public static void applyFlameBarrier(SimState state, SimMonster attacker) {
        if (attacker == null || attacker.hp <= 0) {
            return;
        }
        Integer flame = state.playerPowers.get(FLAME_BARRIER);
        if (flame == null || flame <= 0) {
            return;
        }
        hitAndReturnDealt(attacker, flame);
    }

    /** 同上，但返回**实际扣掉的血量**（吸血类卡牌需要）。 */
    public static int hitAndReturnDealt(SimMonster target, int damage) {
        int hpBefore = target.hp;
        int remaining = intangibleAdjusted(target.powers, flightAdjusted(target, damage));
        if (target.block > 0) {
            int absorbed = Math.min(target.block, remaining);
            target.block -= absorbed;
            remaining -= absorbed;
        }
        target.hp -= remaining;
        if (target.hp < 0) {
            target.hp = 0;
        }
        tickFlight(target, remaining, hpBefore);
        tickPlatedArmor(target, damage);
        tickAngry(target, damage);
        tickCurlUp(target, damage, hpBefore);
        return remaining;
    }

    /**
     * 蜷缩（{@code CurlUpPower.onAttacked}）：受伤时加 {@code amount} 格挡，**只触发一次**。
     *
     * <p>原版判据：{@code !triggered && damageAmount < owner.currentHealth
     * && damageAmount > 0 && info.owner != null && info.type == NORMAL}
     * —— 也就是**没打死、且是普通攻击**才蜷缩。虱虫（Louse）有。
     *
     * <p>{@code triggered} 在我们这里是「把 Curl Up 从 powers 里移除」来表达
     * （一次性能力用完即弃，和飞行/装甲的层数制不同）。
     */
    private static void tickCurlUp(SimMonster target, int damage, int hpBefore) {
        if (damage <= 0 || damage >= hpBefore) {
            return;
        }
        Integer curl = target.powers.get("Curl Up");
        if (curl == null || curl <= 0) {
            return;
        }
        target.block += curl;
        target.powers.remove("Curl Up");
    }

    /**
     * 愤怒（{@code AngryPower.onAttacked}）：受击时 +amount 点力量。
     *
     * <p>原版判据：{@code info.owner != null && damageAmount > 0
     * && type != HP_LOSS && type != THORNS} —— 也就是**只有普通攻击**触发。
     * 0 伤不触发（和装甲同一条）。
     */
    private static void tickAngry(SimMonster target, int damage) {
        if (damage <= 0) {
            return;
        }
        Integer angry = target.powers.get("Angry");
        if (angry == null || angry <= 0) {
            return;
        }
        Integer str = target.powers.get("Strength");
        target.powers.put("Strength", (str == null ? 0 : str) + angry);
    }

    /** 飞行（{@code FlightPower}）：普通攻击伤害减半，HP_LOSS / THORNS 不减。 */
    public static final String FLIGHT = "Flight";
    /** 无实体：所有伤害上限 1（{@code IntangiblePower}）。 */
    public static final String INTANGIBLE = "Intangible";
    /** 不能获得格挡（{@code NoBlockPower.modifyBlockLast} 直接返回 0）。 */
    public static final String NO_BLOCK = "No Block";
    /** 烈焰屏障：受击时反弹 {@code amount} 点给攻击者（{@code FlameBarrierPower}）。 */
    public static final String FLAME_BARRIER = "Flame Barrier";

    /** 荆棘（{@code ThornsPower}）：受击反弹 {@code amount} 点给攻击者。 */
    public static final String THORNS = "Thorns";

    /**
     * 装甲（{@code PlatedArmorPower}）：回合末加 {@code amount} 格挡，
     * 每次受伤掉 1 层（{@code wasHPLost}）。
     *
     * <p>原版 {@code wasHPLost} 的判据是 {@code info.owner != null && info.owner != owner
     * && type != HP_LOSS && type != THORNS && damageAmount > 0} ——
     * 也就是**只有普通攻击**掉层。我们这条路径只有普通攻击，所以恒过。
     */
    public static final String PLATED_ARMOR = "Plated Armor";

    /**
     * 飞行减伤（{@code FlightPower.calculateDamageTakenAmount}）：
     * {@code type == HP_LOSS || type == THORNS ? dmg : dmg / 2.0f}，
     * 最后 {@code f2i}（向零截断）。
     *
     * <p>我们只在**普通攻击**这条路径上调用，所以恒走减半分支；
     * 自伤/荆棘那两条不经过这里。
     */
    private static int flightAdjusted(SimMonster target, int damage) {
        Integer flight = target.powers.get(FLIGHT);
        if (flight == null || flight <= 0) {
            return damage;
        }
        return (int) (damage / 2.0f);
    }

    /**
     * 无实体减伤（{@code IntangiblePower.atDamageFinalReceive}）：
     * {@code if (dmg > 1.0f) return 1.0f; return dmg;} —— **所有伤害上限 1**。
     *
     * <p>在**最终收到的伤害**上生效（所有修正之后、扣格挡之前）：
     * 所以 1 点伤害仍会被格挡完全吸收。
     *
     * <p>注意这是「封顶」不是「归零」：0 伤保持 0，1 伤保持 1。
     * 玩家和怪物共用这一条（原版是同一个 power 类）。
     */
    public static int intangibleAdjusted(java.util.Map<String, Integer> powers, int damage) {
        if (powers == null) {
            return damage;
        }
        Integer intangible = powers.get(INTANGIBLE);
        if (intangible == null || intangible <= 0) {
            return damage;
        }
        return Math.min(damage, 1);
    }

    /**
     * 「刚挂上、还没 tick 过」的标记键：{@code IntangiblePower} 的 {@code justApplied} 字段。
     *
     * <p>它不是真能力，只是 {@code powers} 表里的一个 1，任何按 ID 查 handler / 估值的
     * 地方都查不到它；{@link #tickIntangible} 消费掉它就删。
     */
    public static final String INTANGIBLE_FRESH = "Intangible#fresh";

    /** 挂无实体时打上 {@code justApplied}（原版构造函数里就是 {@code justApplied = true}）。 */
    public static void markIntangibleFresh(java.util.Map<String, Integer> powers, String id) {
        if (powers != null && INTANGIBLE.equals(id)) {
            powers.put(INTANGIBLE_FRESH, Integer.valueOf(1));
        }
    }

    /**
     * 无实体回合末递减（{@code IntangiblePower.atEndOfTurn}）：{@code amount--}，到 0 移除。
     *
     * <p>原版的 {@code justApplied} 保护：**刚挂上的那一次 tick 直接跳过**（只把标志清掉）。
     * 这条很关键 —— 不建的话，玩家当回合开的「幽魂形态 2」会在同一回合末掉一层，
     * 少算一整回合无实体；怪物的无实体（Nemesis 每回合都挂）则会在玩家回合开始前就消失。
     */
    public static void tickIntangible(java.util.Map<String, Integer> powers) {
        if (powers == null) {
            return;
        }
        Integer intangible = powers.get(INTANGIBLE);
        if (intangible == null || intangible <= 0) {
            powers.remove(INTANGIBLE_FRESH);
            return;
        }
        if (powers.remove(INTANGIBLE_FRESH) != null) {
            return;                     // 刚挂上：跳过这次 tick
        }
        if (intangible <= 1) {
            powers.remove(INTANGIBLE);
        } else {
            powers.put(INTANGIBLE, intangible - 1);
        }
    }

    /**
     * 打中一次后递减装甲层数（{@code PlatedArmorPower.wasHPLost}）。
     *
     * <p>原版判据里有一个 {@code damageAmount > 0}（0 伤不掉层），
     * 以及「有攻击者、非 THORNS/HP_LOSS」—— 我们这条路径只有普通攻击，恒过。
     */
    private static void tickPlatedArmor(SimMonster target, int damage) {
        if (damage <= 0) {
            return;
        }
        Integer plated = target.powers.get(PLATED_ARMOR);
        if (plated == null || plated <= 0) {
            return;
        }
        int left = plated - 1;
        if (left <= 0) {
            target.powers.remove(PLATED_ARMOR);
            // PlatedArmorPower.onRemove：怪物身上的装甲耗尽 → ChangeStateAction("ARMOR_BREAK")。
            // ShelledParasite 的 changeState 把这个状态翻成 setMove(4, STUN)（它的破甲硬直）。
            io.github.youqing.sts1solver.sim.MonsterMoves.onArmorBreak(target);
        } else {
            target.powers.put(PLATED_ARMOR, left);
        }
    }

    /**
     * 打中一次后递减飞行层数（{@code FlightPower.onAttacked}）。
     *
     * <p>原版只在 {@code info.owner != null}（有攻击者）时递减 —— 自伤/荆棘不掉层。
     * 还有一个「减伤后伤害 < 当前血量」的边界判据（{@code willKill}），
     * 只影响「刚好打死」的那一下；这里简化成每次普通攻击命中掉 1 层，
     * 差异只在边界（打死不打死都掉层），已记进 docs §26。
     */
    private static void tickFlight(SimMonster target, int unblocked, int hpBefore) {
        Integer flight = target.powers.get(FLIGHT);
        if (flight == null || flight <= 0) {
            return;
        }
        // FlightPower.onAttacked 的四条与条件（字节码直读）：
        //   info.owner != null && type 不是 HP_LOSS/THORNS && damageAmount > 0
        //   && damageAmount < owner.currentHealth（**这一下打不死**）
        // damageAmount 是**过完格挡之后**的数 —— 被格挡全吃掉就不掉层。
        if (unblocked <= 0 || unblocked >= hpBefore) {
            return;
        }
        int left = flight - 1;
        if (left > 0) {
            target.powers.put(FLIGHT, left);
            return;
        }
        // 掉光 = changeState("GROUNDED")：setMove(4, STUN) + isFlying = false
        target.powers.remove(FLIGHT);
        target.pendingMove = io.github.youqing.sts1solver.sim.MonsterMove.none(4);
        target.pendingMoveId = 4;
    }

    /** 力量按倍率生效（重刃：力量 ×3/×5）。 */
    public static int attackDamageWithStrengthMult(SimState state, int base, int mult,
                                                   SimMonster target) {
        int dmg = base + power(state, STRENGTH) * mult;
        if (power(state, WEAKENED) > 0) {
            dmg = (int) Math.floor(dmg * 0.75f);
        }
        if (target != null && powers(target).containsKey(VULNERABLE)
                && powers(target).get(VULNERABLE) > 0) {
            // 纸青蛙把「玩家打敌人」的易伤倍率从 1.5 提到 1.75
            // （敌人打玩家那条仍是 1.5 —— 见 monsterAttackDamage）
            dmg = (int) Math.floor(dmg
                    * io.github.youqing.sts1solver.mirror.hooks.RelicHooks
                            .vulnerableMultiplier(state));
        }
        return Math.max(0, dmg);
    }

    private static int power(SimState state, String id) {
        Integer v = state.playerPowers.get(id);
        return v == null ? 0 : v;
    }

    private static java.util.Map<String, Integer> powers(SimMonster m) {
        return m.powers;
    }
}
