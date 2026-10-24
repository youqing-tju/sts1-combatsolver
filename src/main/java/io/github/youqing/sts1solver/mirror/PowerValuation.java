package io.github.youqing.sts1solver.mirror;

import io.github.youqing.sts1solver.sim.SimCard;
import io.github.youqing.sts1solver.sim.SimMonster;
import io.github.youqing.sts1solver.sim.SimState;

/**
 * 能力（Power）的战略估值 —— 照蓝本 {@code StrategicEffectModel}（{@code src/Search/StrategicEffectModel.cs}）。
 *
 * <p>蓝本给每层能力算一个**五维向量**，加起来就是它的「保留价值」（{@code RetentionValue}）：
 *
 * <pre>
 * DamagePotential      这层能力最终能多打出多少伤害（按敌方总血封顶）
 * PreventionPotential  它能挡掉多少伤害（按「敌方意图伤害 × 最多 2 回合」封顶）
 * ResourcePotential    它省/赚多少能量（1 能量 ≈ 一张牌的平均价值）
 * CardAccessPotential  它多给你多少张可打的牌（同样折成牌价值）
 * ScalingPotential     上面四项都算不进去时的兜底（每层值 1 点）
 * RetentionValue = clamp(五项之和, 0, MAX)
 * </pre>
 *
 * <p>逐 power 的公式抄自 {@code StrategicEffectModel.Evaluate}（`:476-560`）。蓝本里
 * **没有专门分支**的能力一律走默认 {@code Scaling(amount)} —— 这是蓝本自己的兜底，
 * 不是我们自创（STS2 没有 Combust / Metallicize / FireBreathing 这几个能力，
 * grep 全库 0 命中，所以它们在这里也只能走默认档）。
 *
 * <h2>STS1 Ironclad 有对应分支的能力</h2>
 *
 * <table>
 *   <tr><th>能力</th><th>蓝本分支</th><th>公式</th></tr>
 *   <tr><td>Gain Strength / Strength</td><td>{@code StrengthPower}</td>
 *       <td>{@code Damage(amount × AttackHits)}</td></tr>
 *   <tr><td>Dexterity</td><td>{@code DexterityPower}</td>
 *       <td>{@code Prevention(amount × BlockSkillPlays)}</td></tr>
 *   <tr><td>Demon Form</td><td>{@code DemonFormPower}</td>
 *       <td>{@code Damage(amount × ⌊AttackPlays/RemainingTurns⌋ × T(T+1)/2)}</td></tr>
 *   <tr><td>Feel No Pain</td><td>{@code FeelNoPainPower}</td>
 *       <td>{@code Prevention(amount × ExhaustPlays)}</td></tr>
 *   <tr><td>Dark Embrace</td><td>{@code DarkEmbracePower}</td>
 *       <td>{@code CardAccess(amount × ExhaustPlays × AverageCardValue)}</td></tr>
 *   <tr><td>Corruption</td><td>{@code CorruptionPower}</td>
 *       <td>{@code Resource(SkillEnergySpend × max(3, AverageCardValue))}</td></tr>
 *   <tr><td>Thorns</td><td>{@code ThornsPower}</td>
 *       <td>{@code Damage(amount × IncomingHitCount)}</td></tr>
 *   <tr><td>其余（Metallicize / Combust / Juggernaut / Rupture / Barricade / Berserk /
 *       Brutality / Evolve / Fire Breathing / Flame Barrier / Rage / Double Tap /
 *       Mayhem / Magnetism / Sadistic / Panache / …）</td><td>默认档</td>
 *       <td>{@code Scaling(amount)}</td></tr>
 * </table>
 *
 * <h2>⚠️ 两处近似（记在 mechanics-reference §32）</h2>
 *
 * <ul>
 *   <li>{@code IncomingHitCount}：蓝本数的是**本回合会打过来的段数**（连击算多段）。
 *       我们没有怪物招式段数，退化成「有意图伤害的怪物只数」。</li>
 *   <li>{@code AverageCardValue}：蓝本用 {@code CardChoiceSupport.CardValue}（含效果价值）。
 *       我们用牌堆攻击牌的平均面板伤害，没有攻击牌时退化成 1。</li>
 * </ul>
 */
public final class PowerValuation {

    /** 蓝本 {@code StrategicEffectContext} 里我们算得动的那些项。 */
    public static final class Projection {
        public int enemyHp;
        public int incomingDamage;
        public int incomingHitCount;
        public int remainingTurns;
        public int attackPlays;
        public int blockSkillPlays;
        public int exhaustPlays;
        public int attackHits;
        public int averageCardValue;
        public int skillEnergySpend;
        public int energyUnit;
    }

    private PowerValuation() {
    }

    /** 把当前局面投影成估值需要的上下文（与蓝本 {@code StrategicEffectContext.Build} 同口径）。 */
    public static Projection project(SimState state) {
        Projection p = new Projection();
        int deckSize = Math.max(1, state.hand.size() + state.draw.size() + state.discard.size());
        int attacks = 0;
        int attackHitCount = 0;
        int attackValue = 0;
        int blockSkills = 0;
        int exhausts = 0;
        int skillEnergy = 0;

        for (int pile = 0; pile < 3; pile++) {          // 弃牌堆 + 抽牌堆 + 手牌（同 offensiveProgress 顺序）
            java.util.List<SimCard> cards = pile == 0 ? state.discard : pile == 1 ? state.draw : state.hand;
            for (SimCard card : cards) {
                boolean isSkill = "SKILL".equals(card.type);
                if (card.isAttack()) {
                    attacks++;
                    attackHitCount += attackHitsPerCard(card);
                    attackValue += Math.max(0, card.baseDamage);
                } else if (isSkill) {
                    if (card.effectiveCost() > 0) {
                        skillEnergy += card.effectiveCost();
                    }
                    if (blocks(card.id)) {
                        blockSkills++;
                    }
                }
                if (exhaustsOnItsOwn(card)) {
                    exhausts++;
                }
            }
        }

        for (SimMonster monster : state.monsters) {
            if (monster.hp <= 0) {
                continue;
            }
            p.enemyHp += Math.max(0, monster.hp);
            if (monster.intentDmg > 0) {
                p.incomingDamage += monster.intentDmg;
                p.incomingHitCount++;                 // ⚠️ 近似：蓝本数段数，我们数怪物只数
            }
        }

        p.remainingTurns = estimateRemainingTurns(p.enemyHp, attackValue, deckSize);
        int cardsPerTurn = Math.min(10, Math.max(1, Math.min(5, deckSize)));
        int reachableCards = Math.min(deckSize * 2, p.remainingTurns * cardsPerTurn);
        p.attackPlays = reachable(attacks, deckSize, reachableCards);
        p.blockSkillPlays = reachable(blockSkills, deckSize, reachableCards);
        p.exhaustPlays = reachable(exhausts, deckSize, reachableCards);
        p.attackHits = attackHitCount == 0 ? 0 : reachable(attackHitCount, deckSize, reachableCards);
        p.skillEnergySpend = skillEnergy;
        p.averageCardValue = attacks == 0 ? 1 : Math.max(1, attackValue / attacks);
        p.energyUnit = Math.max(3, p.averageCardValue);
        return p;
    }

    /**
     * 蓝本 {@code StrategicEffectModel.Evaluate}：一层能力 → 保留价值。
     *
     * @param powerId 原版能力 ID（{@code SimState.capture} 用的 {@code pw.ID}）
     */
    public static int retentionValue(String powerId, int amount, Projection p) {
        if (powerId == null || amount <= 0 || p == null) {
            return 0;
        }
        int value = Math.max(1, amount);
        int damage;
        int prevention;
        int resource = 0;
        int cardAccess = 0;
        int scaling = 0;

        if (DamageCalc.STRENGTH.equals(powerId) || "Gain Strength".equals(powerId)) {
            damage = value * Math.max(0, p.attackHits);
        } else if (DamageCalc.DEXTERITY.equals(powerId)) {
            prevention = value * Math.max(0, p.blockSkillPlays);
            return preventionCap(prevention, p);
        } else if ("Demon Form".equals(powerId)) {
            damage = p.attackPlays <= 0 ? 0 : demonFormDamage(value, p);
        } else if ("Feel No Pain".equals(powerId)) {
            prevention = value * Math.max(0, p.exhaustPlays);
            return preventionCap(prevention, p);
        } else if ("Dark Embrace".equals(powerId)) {
            damage = 0;
            cardAccess = value * Math.max(0, p.exhaustPlays) * Math.max(0, p.averageCardValue);
        } else if ("Corruption".equals(powerId)) {
            damage = 0;
            resource = Math.max(0, p.skillEnergySpend) * Math.max(1, p.energyUnit);
        } else if ("Thorns".equals(powerId)) {
            damage = value * Math.max(0, p.incomingHitCount);
        } else {
            // 蓝本默认档 `_ => Scaling(amount)`：没有专门公式的能力每层值 1 点
            return Math.max(0, value);
        }
        return clamp(damageClamp(damage, p) + cardAccess + resource + scaling);
    }

    /** 整场能力保留价值之和（蓝本 {@code PersistentBuffValue} 的主体）。 */
    public static int persistentBuffValue(SimState state, Projection p) {
        int total = 0;
        for (java.util.Map.Entry<String, Integer> entry : state.playerPowers.entrySet()) {
            total += retentionValue(entry.getKey(), entry.getValue().intValue(), p);
        }
        return total;
    }

    // ------------------------------------------------------------------ 蓝本的封顶与聚合

    /** {@code Damage(value, enemyHp) = min(max(0, enemyHp), max(0, value))}。 */
    private static int damageClamp(int value, Projection p) {
        return Math.min(Math.max(0, p.enemyHp), Math.max(0, value));
    }

    /**
     * {@code Prevention(value, context)}：
     * {@code cap = incomingDamage == 0 ? value : incomingDamage × min(2, RemainingTurns)}。
     */
    private static int preventionCap(int value, Projection p) {
        int cap = p.incomingDamage == 0
                ? value
                : p.incomingDamage * Math.min(2, Math.max(1, p.remainingTurns));
        return Math.min(Math.max(0, cap), Math.max(0, value));
    }

    /**
     * {@code DemonFormPower} 分支：
     * {@code amount × max(1, AttackPlays / max(1, RemainingTurns)) × T × (T + 1) / 2}。
     */
    private static int demonFormDamage(int amount, Projection p) {
        int turns = Math.max(1, p.remainingTurns);
        int perTurn = Math.max(1, p.attackPlays / turns);
        long total = (long) amount * perTurn * turns * (turns + 1L) / 2L;
        return damageClamp((int) Math.min(Integer.MAX_VALUE, total), p);
    }

    private static int clamp(int value) {
        return Math.max(0, value);
    }

    // ------------------------------------------------------------------ 牌堆投影

    /** 蓝本 {@code ReachablePlays}：{@code ceil(matching × reachableCards / deckSize)}。 */
    private static int reachable(int matchingCards, int deckSize, int reachableCards) {
        if (matchingCards <= 0 || reachableCards <= 0) {
            return 0;
        }
        return Math.max(1, (int) Math.ceil((double) matchingCards * reachableCards / deckSize));
    }

    private static int estimateRemainingTurns(int enemyHp, int attackValue, int deckSize) {
        int perCycle = Math.max(1, attackValue);
        int cycles = (int) Math.ceil((double) Math.max(1, enemyHp) / perCycle);
        return Math.max(1, Math.min(16, cycles * Math.max(1, (int) Math.ceil(deckSize / 5.0))));
    }

    /** 每张攻击牌的命中段数（与 {@code BeamSolver.attackHitsPerCard} 同表）。 */
    private static int attackHitsPerCard(SimCard card) {
        if ("Twin Strike".equals(card.id) || "Twin Strike+".equals(card.id)) {
            return 2;
        }
        if ("Sword Boomerang".equals(card.id) || "Sword Boomerang+".equals(card.id)) {
            return 3;
        }
        return 1;
    }

    /** 会自己进消耗堆的牌 —— 直接读镜像表（{@code CardMirror.exhaustsOnPlay}），不另维护名单。 */
    private static boolean exhaustsOnItsOwn(SimCard card) {
        CardMirror mirror = MirrorRegistry.lookup(card.key());
        return mirror != null && mirror.exhaustsOnPlay;
    }

    /**
     * 这条技能是不是「给格挡」型（Dexterity 的 {@code BlockSkillPlays} 要数它们）。
     *
     * <p>蓝本从牌的 {@code DynamicVars} 里读 {@code Block} 变量；我们的 {@link CardMirror}
     * 没有这套元数据，所以用从镜像表抽出来的固定名单（28 条，含攻击型的铁斩波 ——
     * 它会在 {@code isSkill} 那一关被排除，与蓝本「只数技能」同口径）。
     */
    private static boolean blocks(String cardId) {
        return BLOCK_CARDS.contains(cardId);
    }

    private static final java.util.Set<String> BLOCK_CARDS = new java.util.HashSet<String>(
            java.util.Arrays.asList(
                    "Armaments", "Armaments+", "Defend_R", "Defend_R+",
                    "Finesse", "Finesse+", "Flame Barrier", "Flame Barrier+",
                    "Ghostly Armor", "Ghostly Armor+", "Good Instincts", "Good Instincts+",
                    "Impervious", "Impervious+", "Iron Wave", "Iron Wave+",
                    "PanicButton", "PanicButton+", "Power Through", "Power Through+",
                    "Safety", "Safety+", "Sentinel", "Sentinel+",
                    "Shrug It Off", "Shrug It Off+", "True Grit", "True Grit+"));
}
