package io.github.youqing.sts1solver.search;

import io.github.youqing.sts1solver.mirror.DamageCalc;
import io.github.youqing.sts1solver.sim.SimCard;
import io.github.youqing.sts1solver.sim.SimMonster;
import io.github.youqing.sts1solver.sim.SimState;

/**
 * 估值：照蓝本 {@code SolverWeights} + {@code CombatBeamSolver.StateEvaluation} 的**线性加权**模型。
 *
 * <p>蓝本不搞字典序（「存活 &gt; 血量 &gt; 敌方血量」那种），它把所有项都换算成**同一个单位**再相加，
 * 单位是「血」：{@code SolverWeights.Hp = 30_000} 表示 1 点血值 30000 分。
 * 于是所有取舍都变成一个可读的汇率问题：
 *
 * <ul>
 *   <li><b>1 点血 = 30,000 分</b>（{@link #HP}）</li>
 *   <li><b>1 点敌方血量 = 10,000 分</b>（{@link #ENEMY_HP}）→ <b>1 血 ≈ 3 点伤害</b>。
 *       这是整套估值最关键的一条汇率。旧实现是「1 血 = 1000 点敌方血」（相差 333 倍），
 *       结果 AI 把「少掉 16 点血」看得比「120 血的怪多活 20 回合」还重 —— 磨洋工的根因。</li>
 *   <li><b>胜利 = 1e10 分</b>（{@link #VICTORY_BONUS}）；<b>死亡 = -1e12 分</b>（{@link #DEATH_PENALTY}）。
 *       两个数量级把「活着打赢」压成不可交换的前提，任何加分项都翻不过去。</li>
 * </ul>
 *
 * <p><b>基础分</b>（{@link #baseTotal()}，决定最终路线）与**Beam 排序分**（{@link #total()}，
 * 决定搜索时哪些半成品路线能活下来）是**分开**的，对应蓝本
 * {@code SimulationSnapshot.Score} 与 {@code BeamRetentionPolicy.BeamRankScore}：
 *
 * <pre>
 * base = 投影血量 * HP                       // 敌人按当前意图打完之后我还剩多少
 *      + 最大生命成长 * HP                  // 吃（Feed）
 *      - 已损血量 * HP（可关，见 damageTakenWeight）
 *      + 胜利加成
 *      + 敌方总血 * ENEMY_HP
 *      + 牌库杂质 * CLUTTER_PENALTY         // 状态/诅咒占着牌堆
 *
 * rank = base
 *      + min(6, 能量) * 60,000              // 还能再打几张：只用于排序，绝不进最终路线
 *      + min(4, 持续成长层) * 150,000
 *      + 易伤层 * min(24, 牌堆攻击总量) * 5,000
 *      + min(16, 敌方虚弱) * 1 * HP
 *      + min(16, 敌方力量削减) * 4 * HP
 *      + 动作数 * (-1)
 * </pre>
 *
 * <p>为什么不合成一个数：蓝本的 {@code CurrentEnergyBeamValue = 60,000} 等于「2 点血」。
 * 如果把它算进最终路线，AI 就会为了「留着能量不打」而放弃一张只值 1.9 点血的牌 —— 恰好就是
 * 2026-10-23 那次「站着不动被打死」的形状。蓝本用「基础分选路线、Beam 分排中间态」把两者隔离，
 * 这里照做。
 */
public final class Score {

    // ===================== 蓝本 SolverWeights（数值逐个照抄） =====================

    /** 死亡惩罚。比任何加分项大 5 个数量级，保证「宁可苟活也不送」。 */
    public static final double DEATH_PENALTY = -1_000_000_000_000d;
    /** 胜利加成。远小于死亡惩罚，远大于任何战斗内收益。 */
    public static final double VICTORY_BONUS = 10_000_000_000d;
    /** 1 点血的分数。整套估值的单位。 */
    public static final double HP = 30_000d;
    /** 1 点敌方血量的分数（负号 = 敌方血越少越好）。Hp/|EnemyHp| = 3，即 1 血 ≈ 3 伤。 */
    public static final double ENEMY_HP = -10_000d;

    /** Beam 排序：本回合剩余能量（封顶 6 点，避免溢出能量刷分）。 */
    public static final int ENERGY_CAP = 6;
    public static final double ENERGY_VALUE = 60_000d;
    /**
     * 能量 Beam 分的**实际取值**（默认 = 蓝本 60,000，即 2 点血）。
     *
     * <p>⚠️ 这是唯一一项「**不打牌反而加分**」的项：出牌会让能量减少，于是排序会把
     * 「留着能量什么都不做」的半成品路线排到前面。2026-10-23 实测就踩到了 ——
     * 双教徒场从「9 回合剩 24 血」退化成「9 回合剩 1 血 / 直接阵亡」。
     * 蓝本敢用这么大的值，是因为它还有「按回合层分配节点 + 分道保留」兜住，
     * 我们只有单层 beam，所以这一项要么调小要么关掉。留成开关便于 A/B。
     */
    public static volatile double energyBeamValue = ENERGY_VALUE;
    /** Beam 排序：持续成长（能力牌层数）—— 标准战封顶 4 层。 */
    public static final int PERSISTENT_CAP = 4;
    public static final double PERSISTENT_VALUE = 150_000d;
    /** Beam 排序：易伤是「攻击倍率」，价值要按**吃得到它的攻击量**封顶。 */
    public static final int VULN_WINDOW_CAP = 24;
    public static final double VULN_VALUE = 5_000d;
    /** 非集火目标身上的易伤：吃不到多少攻击，只值 1/5。 */
    public static final double VULN_VALUE_OFF_TARGET = 1_000d;
    /** Beam 排序：敌方虚弱，每层大约等于少挨 1 点伤害。 */
    public static final int ENEMY_WEAK_CAP = 16;
    public static final int ENEMY_WEAK_HP_SAVED = 1;
    /** Beam 排序：削减敌方力量，按「还能打几个回合」折算。 */
    public static final int ENEMY_STR_CAP = 16;
    public static final int ENEMY_STR_HORIZON = 4;
    /** 牌堆里每张状态/诅咒的惩罚（蓝本数值）。小于 1 点即时伤害，用来留住「净化」这类路线。 */
    public static final double CLUTTER_PENALTY = -8_000d;
    /** 每个动作扣 1 分：等价于「同样好就选更短的那条」，量级上可忽略。 */
    public static final double ACTION_PENALTY = -1d;

    // ===================== 两个实验开关（默认 = 蓝本口径） =====================

    /**
     * 「已损血量」是否二次计价：蓝本 {@code StateEvaluation} 同时计入
     * {@code projectedHp * Hp} 和 {@code -cumulativePlayerHpLost * Hp}，等于**掉 1 点血收 2 份钱**
     * （有效汇率变成 1 血 = 6 伤），与它自己注释里写的「保住 1 HP ≈ 造成 3 点伤害」矛盾。
     *
     * <p>STS1 还有 Reaper / 吃（Feed）这类**战斗中回血**，二次计价会把回血算成零收益，
     * 所以默认取 {@code 0}（只按剩余血量算），跑 A/B 时再开成 {@code 1} 对比。
     */
    public static volatile double damageTakenWeight = 0d;

    /**
     * 每推进一个回合扣多少分。蓝本没有这一项（只有 {@code ActionPenalty = -1}），
     * 默认 {@code 0} 保持原味；实验档位 {@link #TURN_PENALTY_HALF_HP} 用来验证
     * 「磨洋工」到底该不该单独罚。（0.5 点血/回合）
     */
    public static volatile long turnPenaltyPerTurn = 0L;

    /** 实验档位：每回合 0.5 点血。 */
    public static final long TURN_PENALTY_HALF_HP = (long) (HP / 2);

    /**
     * 旧版估值的对照开关（**只用于离线 A/B，默认关**）。
     *
     * <p>旧版是字典序硬编码：{@code 存活*1e9 > 血量*1e6 > 敌方血*1e3}，
     * 也就是「1 点血 = 1000 点敌方血」。留着它，是为了能随时在同一台机器、同一份
     * 确定性搜索上跑出「改前 / 改后」的对照，而不是靠记忆里的数字。
     */
    public static volatile boolean legacyScoring = false;

    /** 恢复默认（单测之间互不污染）。 */
    public static void resetKnobs() {
        damageTakenWeight = 0d;
        turnPenaltyPerTurn = 0L;
        energyBeamValue = ENERGY_VALUE;
        legacyScoring = false;
    }

    /** 旧版（2026-10-23 之前）的字典序硬编码估值，仅作 A/B 对照。 */
    private long legacyTotal() {
        long score = 0;
        score -= (long) aliveEnemies * 1_000_000_000L;
        score += (long) playerHp * 1_000_000L;
        score -= (long) enemyHp * 1_000L;
        if (aliveEnemies > 0) {
            score += (long) energy * 5L;
            score += handSize;
            score -= (long) easiestKill * 5_000L;
            score += (long) projectedHp * 200_000L;
            if (projectedHp <= 0) {
                score -= 300_000_000L;
            }
            score += (long) scalingPowers * 6_000L;
            score += (long) strength * 3_000L;
            score += (long) enemyVulnerable * 1_500L;
            score += (long) enemyWeak * 1_500L;
        }
        return score;
    }

    // ===================== 观测量 =====================

    public final int aliveEnemies;
    public final int playerHp;
    public final int maxHp;
    public final int maxHpGain;
    /** 敌方**有效**血量合计（hp + 格挡）。打在有格挡的敌人身上不推进进度。 */
    public final int enemyHp;
    public final int enemyBlock;
    public final int block;
    public final int energy;
    public final int handSize;
    /**
     * 投影血量：本回合结束后、敌方按当前意图打完，我还剩多少血（蓝本 {@code ProjectedPlayerHp}）。
     * 这是「一阶前瞻」——不回放也能知道这一步会不会走进死亡。
     */
    public final int projectedHp;
    /** 最脆的那个敌人还剩多少有效血量（仅诊断，不参与计分）。 */
    public final int easiestKill;
    /** 玩家的**成长性能力**层数（恶魔形态/金属化/主宰/燃烧/无痛/黑暗拥抱/壁垒…）。 */
    public final int scalingPowers;
    /** 玩家力量层数（每层放大之后每一次攻击）。 */
    public final int strength;
    /** 敌人身上的易伤层数合计（放大我们的输出）。 */
    public final int enemyVulnerable;
    /** 集火目标（有效血量最低的敌人）身上的易伤层数——只有它吃得到满倍率。 */
    public final int focusVulnerable;
    /** 敌人身上的虚弱层数合计（压低对方输出）。 */
    public final int enemyWeak;
    /** 敌方力量被削掉的层数合计（负数力量）。 */
    public final int enemyStrengthDown;
    /** 牌堆（手牌+抽牌+弃牌）里的攻击牌总伤害，用来给易伤封顶。蓝本 {@code retainedAttackValue}。 */
    public final int attackWindow;
    /** 牌堆里的状态/诅咒张数（牌库杂质）。 */
    public final int clutter;

    public final boolean dead;
    public final boolean won;
    /** 这条路线已经走了几步（搜索深度）。 */
    public final int actions;
    /** 这条路线已经打了几个回合。 */
    public final int turns;
    /** 本场战斗开始前掉了多少血（含被回血补回来的部分）。 */
    public final int hpLost;

    private Score(Builder b) {
        this.aliveEnemies = b.aliveEnemies;
        this.playerHp = b.playerHp;
        this.maxHp = b.maxHp;
        this.maxHpGain = b.maxHpGain;
        this.enemyHp = b.enemyHp;
        this.enemyBlock = b.enemyBlock;
        this.block = b.block;
        this.energy = b.energy;
        this.handSize = b.handSize;
        this.projectedHp = b.projectedHp;
        this.easiestKill = b.easiestKill;
        this.scalingPowers = b.scalingPowers;
        this.strength = b.strength;
        this.enemyVulnerable = b.enemyVulnerable;
        this.focusVulnerable = b.focusVulnerable;
        this.enemyWeak = b.enemyWeak;
        this.enemyStrengthDown = b.enemyStrengthDown;
        this.attackWindow = b.attackWindow;
        this.clutter = b.clutter;
        this.dead = b.dead;
        this.won = b.won;
        this.actions = b.actions;
        this.turns = b.turns;
        this.hpLost = b.hpLost;
    }

    /** 会「每个回合都变强」的能力：层数越打越值钱（蓝本按能力逐个估 RetentionValue，这里统一按层）。 */
    private static final String[] SCALING = {
            "Metallicize", "Demon Form", "Juggernaut", "Combust", "Feel No Pain",
            "Dark Embrace", "Barricade", "Corruption", "Fire Breathing", "Rage",
            "Thorns", "Plated Armor", "Buffer", "Intangible", "Echo Form", "Brutality",
    };

    // ===================== 构造 =====================

    /** 把当前状态当作「路线起点」来估值（单测/面板/回退决策用）。 */
    public static Score of(SimState state) {
        return of(state, state.hp, state.maxHp, state.turn, 0);
    }

    /**
     * @param initialHp    本条路线起点的血量（算「已损血量」用）
     * @param initialMaxHp 本条路线起点的最大生命（算「最大生命成长」用）
     * @param initialTurn  本条路线起点的回合数
     * @param actions      本条路线已经走了几步
     */
    public static Score of(SimState state, int initialHp, int initialMaxHp,
                           int initialTurn, int actions) {
        Builder b = new Builder();
        b.playerHp = state.hp;
        b.maxHp = state.maxHp;
        b.maxHpGain = state.maxHp - initialMaxHp;
        b.hpLost = Math.max(0, initialHp - state.hp);
        b.block = state.block;
        b.energy = state.energy;
        b.handSize = state.hand.size();
        b.actions = actions;
        b.turns = Math.max(0, state.turn - initialTurn);

        int alive = 0;
        int enemyHp = 0;
        int enemyBlock = 0;
        int easiest = Integer.MAX_VALUE;
        int easiestVuln = 0;
        int incoming = 0;
        int vuln = 0;
        int weak = 0;
        int strDown = 0;
        for (SimMonster m : state.monsters) {
            if (m.hp <= 0) {
                continue;
            }
            alive++;
            // 有效血量 = 血 + 格挡：蓝本 EffectiveEnemyHp，打在有格挡的敌人身上不算推进
            int effective = m.hp + Math.max(0, m.block);
            int monsterVuln = amount(m.powers.get(DamageCalc.VULNERABLE));
            enemyHp += effective;
            enemyBlock += Math.max(0, m.block);
            if (effective < easiest) {
                easiest = effective;
                easiestVuln = monsterVuln;      // 集火目标身上的易伤才吃得到满倍率
            }
            if (m.intentDmg > 0) {
                incoming += m.intentDmg;
            }
            vuln += monsterVuln;
            weak += amount(m.powers.get(DamageCalc.WEAKENED));
            strDown += Math.max(0, -amountRaw(m.powers.get(DamageCalc.STRENGTH)));
        }
        b.aliveEnemies = alive;
        b.enemyHp = enemyHp;
        b.enemyBlock = enemyBlock;
        b.easiestKill = easiest == Integer.MAX_VALUE ? 0 : easiest;
        b.focusVulnerable = easiestVuln;
        b.enemyVulnerable = vuln;
        b.enemyWeak = weak;
        b.enemyStrengthDown = strDown;

        b.dead = state.hp <= 0;
        b.won = !b.dead && alive == 0;
        // 已经结束的战斗不再吃意图（蓝本：won → threat = (CurrentHp, 0, 0)）
        b.projectedHp = (b.dead || b.won)
                ? state.hp
                : state.hp - Math.max(0, incoming - state.block);

        int scaling = 0;
        for (String id : SCALING) {
            scaling += amount(state.playerPowers.get(id));
        }
        b.scalingPowers = scaling;
        b.strength = amount(state.playerPowers.get(DamageCalc.STRENGTH));

        // 蓝本的 retainedAttackValue 统计的是「所有活着的牌」（手牌 + 抽牌 + 弃牌），不是只算手牌：
        // 易伤要跨回合吃满，手里这回合打不出的攻击牌下一回合也能吃到。
        int window = 0;
        window += cardStats(state.hand, b);
        window += cardStats(state.draw, b);
        window += cardStats(state.discard, b);
        b.attackWindow = Math.min(VULN_WINDOW_CAP, window);
        return new Score(b);
    }

    /** 累计牌堆里的攻击总量与杂质张数（抽牌/弃牌堆共用）。 */
    private static int cardStats(java.util.List<SimCard> pile, Builder b) {
        int window = 0;
        for (SimCard c : pile) {
            if (c.isAttack()) {
                window += Math.max(1, c.baseDamage);
            } else if ("STATUS".equals(c.type) || "CURSE".equals(c.type)) {
                b.clutter++;
            }
        }
        return window;
    }

    private static int amount(Integer value) {
        return value == null || value < 0 ? 0 : value;
    }

    private static int amountRaw(Integer value) {
        return value == null ? 0 : value;
    }

    // ===================== 分数 =====================

    /**
     * 基础分（蓝本 {@code SimulationSnapshot.Score}）—— **最终选哪条路线只看它**。
     */
    public long baseTotal() {
        if (legacyScoring) {
            return legacyTotal();
        }
        double score;
        if (dead || projectedHp <= 0) {
            // 蓝本这里写的是死板的一刀切 DeathPenalty；BeamRankScore 里又给「结束回合但还能救」
            // 的节点做了连续化。这里直接连续化：死了也按「差多少血」排序，
            // 免得所有必死路线同分、只能靠随机项决胜。
            score = DEATH_PENALTY + projectedHp * HP;
        } else {
            score = projectedHp * HP;
        }
        score += maxHpGain * HP;
        score -= hpLost * HP * damageTakenWeight;
        if (won) {
            score += VICTORY_BONUS;
        }
        score += enemyHp * ENEMY_HP;
        score += clutter * CLUTTER_PENALTY;
        return (long) score;
    }

    /**
     * Beam 排序分（蓝本 {@code BeamRankScore}）—— 只决定「哪些半成品路线能活到下一层」。
     */
    public long beamTotal() {
        if (legacyScoring) {
            return legacyTotal();
        }
        double score = baseTotal();
        if (aliveEnemies > 0) {
            // 还剩敌人时，手头的资源才有意义；打完这些项全部清零（否则「先防再杀」会靠余量微弱胜出）。
            score += Math.min(ENERGY_CAP, energy) * energyBeamValue;
            // 力量也算持续成长：蓝本把它并进 PersistentBuffValue 的 DamagePotential 里
            int persistent = scalingPowers + strength;
            score += Math.min(PERSISTENT_CAP, persistent) * PERSISTENT_VALUE;
            // 易伤是倍率，价值 = 层数 × 吃得到它的攻击量（蓝本 VulnerableAttackWindowCap）
            score += focusVulnerable * attackWindow * VULN_VALUE;
            score += Math.max(0, enemyVulnerable - focusVulnerable)
                    * attackWindow * VULN_VALUE_OFF_TARGET;
            score += Math.min(ENEMY_WEAK_CAP, enemyWeak) * ENEMY_WEAK_HP_SAVED * HP;
            score += Math.min(ENEMY_STR_CAP, enemyStrengthDown) * ENEMY_STR_HORIZON * HP;
        }
        score += actions * ACTION_PENALTY;
        score += turns * turnPenaltyPerTurn;
        return (long) score;
    }

    /** 排序用的总分（= Beam 排序分）。旧名字保留，调用方不用改。 */
    public long total() {
        return beamTotal();
    }

    /** 人类可读的分项，用于调试与面板。 */
    public String explain() {
        return "hp=" + playerHp
                + " maxHp=" + maxHp + "(+" + maxHpGain + ")"
                + " projHp=" + projectedHp
                + " hpLost=" + hpLost
                + " enemyHp=" + enemyHp
                + " enemyBlock=" + enemyBlock
                + " block=" + block
                + " energy=" + energy
                + " hand=" + handSize
                + " easiest=" + easiestKill
                + " scaling=" + scalingPowers
                + " str=" + strength
                + " vuln=" + enemyVulnerable + "(" + focusVulnerable + ")"
                + " weak=" + enemyWeak
                + " strDown=" + enemyStrengthDown
                + " window=" + attackWindow
                + " clutter=" + clutter
                + " won=" + won
                + " turns=" + turns
                + " base=" + baseTotal()
                + " total=" + total();
    }

    /** 可变构造器（字段多，避免 20 个参数的构造函数）。 */
    private static final class Builder {
        int aliveEnemies;
        int playerHp;
        int maxHp;
        int maxHpGain;
        int enemyHp;
        int enemyBlock;
        int block;
        int energy;
        int handSize;
        int projectedHp;
        int easiestKill;
        int scalingPowers;
        int strength;
        int enemyVulnerable;
        int focusVulnerable;
        int enemyWeak;
        int enemyStrengthDown;
        int attackWindow;
        int clutter;
        boolean dead;
        boolean won;
        int actions;
        int turns;
        int hpLost;
    }
}
