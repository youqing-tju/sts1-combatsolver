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
     * 「留着能量什么都不做」的半成品路线排到前面。2026-10-23 在**扁平 beam** 时代实测踩到过：
     * 双教徒场从「9 回合剩 24 血」退化成「9 回合剩 1 血」。
     *
     * <p>改成回合层结构后这条副作用基本消失（EndTurn 和出牌不再在同一个 beam 里比较），
     * 现在关掉它是 8 回合 / 21 血 vs 开着 9 回合 / 23 血 —— 各有胜负，仍按蓝本默认开着。
     * 留成开关便于 A/B。
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
    /**
     * Beam 排序：**还没打出去的能力牌**算「延迟收益」。
     *
     * <p>蓝本 {@code LatentSetupValue}：手牌/抽牌/弃牌里每张能力牌计 {@code LatentCardSetupValue} 点，
     * 普通能力牌默认 {@value #LATENT_POWER_VALUE} 点，再按 {@link #LATENT_SETUP_CAP} 封顶。
     * 蓝本对个别 STS2 能力卡给了更高的分（Echo Form 12 / Buffer 8 / 偏折 6 …），
     * **STS1 没有这些卡**，对应的「哪张能力更值钱」属于需要判断的映射，这里不臆造，
     * 统一走蓝本的默认分支。
     *
     * <p>蓝本只在「多敌人或 Boss」时才启用（{@code _isActEndingBoss || _initialEnemyCount > 1}），
     * 单敌人小怪不值得为长期成长拖延；我们照抄前半条（Boss 判定还没建模）。
     */
    public static final int LATENT_SETUP_CAP = 24;
    public static final double LATENT_SETUP_VALUE = 12_000d;
    public static final int LATENT_POWER_VALUE = 2;
    /**
     * Beam 排序：**牌堆里的攻击总量比开局更多**就加分（蓝本 {@code RetainedAttackGrowth}）。
     *
     * <p>值 = {@code min(16, 当前攻击总量 - 开局攻击总量)}。攻击总量只统计手牌+抽牌+弃牌，
     * 所以「消耗掉一张好攻击换一点即时伤害」会掉分，而「暴走每打一次 +5」「愤怒复制一张」
     * 「武装升级」会加分 —— 这正是蓝本注释说的
     * 「防止一个破坏性选择因为换来一点即时伤害而显得更优」。
     */
    public static final int RETAINED_GROWTH_CAP = 16;
    public static final double RETAINED_GROWTH_VALUE = 20_000d;
    /**
     * 一次性救命遗物（蜥蜴尾巴）用掉的**溢价**：蓝本 {@code DeathSavePremiumPercent = 900}。
     *
     * <p>复活回来的血不是这条路线「挣」的 —— 那是一次性、跨战斗的资源，用掉就没了。
     * 所以基础分里先按 {@code 血量 + 溢价}（= 10 倍）扣一次（{@code DeathSaveBeamCost}），
     * 战略血量赤字里再按 9 倍记一次（{@code DeathSavePremium}），量级远低于胜负但仍高于普通血量。
     */
    public static final int DEATH_SAVE_PREMIUM_PERCENT = 900;

    // ===================== 两个实验开关（默认 = 蓝本口径） =====================

    /**
     * 「已损血量」是否二次计价 —— **照蓝本，默认开（1.0）**。
     *
     * <p>蓝本 {@code StateEvaluation.cs:163-167} 同时计入 {@code projectedHp * Hp} 和
     * {@code -cumulativePlayerHpLost * Hp}：前者是「还剩多少血」，后者是「这一路掉了多少血」。
     * 两者都是增长量，于是**掉 1 点血要收 2 份钱**，有效汇率从 1:3 变成 1:6 ——
     * 与它自己注释里写的「保住 1 HP ≈ 造成 3 点伤害」不一致，但这就是它的实现。
     * 蓝本作者管这叫「战略血量赤字」，好处是「先掉血再回血」不算白掉。
     *
     * <p>⚠️ 我们的 {@code hpLost} 是**净掉血**（{@code 起点血量 - 当前血量}），
     * 蓝本记的是**累计未格挡伤害**（回血不抵扣）。STS1 有 Reaper / 吃这类战斗中回血，
     * 净额口径对它们更友好一点；要更严格可以改成累计值。
     *
     * <p>实测代价（`ScoreAbTest`，固定 12 伤的木桩）：单怪 120 血会从 12 回合拖到 21 回合
     * （剩血 55 → 64）。真怪物会成长，这个木桩对「龟」格外有利，所以按蓝本保留。
     */
    public static volatile double damageTakenWeight = 1d;

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

    /**
     * 蓝本 {@code SolverSearchProfile.BaseScoreOnly}：中途排序只用基础分，不加 Beam 项。
     * 默认关（= 蓝本默认）。留成开关是为了能离线验证「Beam 项到底有没有用」。
     */
    public static volatile boolean baseScoreOnly = false;

    /** 恢复默认（单测之间互不污染）。 */
    public static void resetKnobs() {
        damageTakenWeight = 1d;
        turnPenaltyPerTurn = 0L;
        energyBeamValue = ENERGY_VALUE;
        legacyScoring = false;
        baseScoreOnly = false;
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
    /**
     * 牌堆（手牌+抽牌+弃牌）里的攻击牌总伤害 —— 蓝本 {@code retainedAttackValue}。
     *
     * <p>⚠️ 这个值是**原始值，不封顶**：易伤项用它时取 {@code min(24, ·)}，
     * 而「成长」项要用原始差值（见 {@link #RETAINED_GROWTH_CAP}）。
     * 早先在这里就截断到 24，等于把成长项废掉了。
     */
    public final int retainedAttackValue;
    /** 还没打出去的能力牌值多少点（蓝本 {@code LatentSetupValue}，普通能力牌 2 点/张）。 */
    public final int latentSetupValue;
    /** 牌堆里的状态/诅咒张数（牌库杂质）。 */
    public final int clutter;
    /** 开局时的攻击总量（算「成长」要拿它做差）。 */
    public final int initialRetainedAttackValue;
    /** 开局时的敌人数（蓝本只在多敌人/Boss 时启用 LatentSetup）。 */
    public final int initialEnemyCount;

    public final boolean dead;
    public final boolean won;
    /** 这条路线已经走了几步（搜索深度）。 */
    public final int actions;
    /** 这条路线已经打了几个回合。 */
    public final int turns;
    /** 本场战斗开始前掉了多少血（含被回血补回来的部分）。 */
    public final int hpLost;
    /** 救命遗物这条路线上回了多少血（蓝本 {@code DeathSaveHpRestored}）。 */
    public final int deathSaveHpRestored;
    /** 用掉了几次救命遗物（0/1，蓝本 {@code DeathSaveUseCount}）。 */
    public final int deathSaveUseCount;

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
        this.retainedAttackValue = b.retainedAttackValue;
        this.latentSetupValue = b.latentSetupValue;
        this.clutter = b.clutter;
        this.initialRetainedAttackValue = b.initialRetainedAttackValue;
        this.initialEnemyCount = b.initialEnemyCount;
        this.dead = b.dead;
        this.won = b.won;
        this.actions = b.actions;
        this.turns = b.turns;
        this.hpLost = b.hpLost;
        this.deathSaveHpRestored = b.deathSaveHpRestored;
        this.deathSaveUseCount = b.deathSaveUseCount;
    }

    /** 会「每个回合都变强」的能力：层数越打越值钱（蓝本按能力逐个估 RetentionValue，这里统一按层）。 */
    private static final String[] SCALING = {
            "Metallicize", "Demon Form", "Juggernaut", "Combust", "Feel No Pain",
            "Dark Embrace", "Barricade", "Corruption", "Fire Breathing", "Rage",
            "Thorns", "Plated Armor", "Buffer", "Intangible", "Echo Form", "Brutality",
    };

    // ===================== 构造 =====================

    /**
     * 一条搜索路线的**起点**。蓝本每次 pass 都会重新抓这些值
     * （{@code _run.InitialPersistentBuffValue} / {@code InitialRetainedAttackValue} / {@code root.InitialPlayerMaxHp}），
     * 因为「已损血量」「最大生命成长」「攻击总量成长」都只能相对起点算。
     */
    public static final class Root {
        final int hp;
        final int maxHp;
        final int turn;
        final int retainedAttackValue;
        final int enemyCount;

        public Root(SimState state) {
            this.hp = state.hp;
            this.maxHp = state.maxHp;
            this.turn = state.turn;
            int alive = 0;
            for (SimMonster m : state.monsters) {
                if (m.hp > 0) {
                    alive++;
                }
            }
            this.enemyCount = alive;
            this.retainedAttackValue = attackTotal(state.hand) + attackTotal(state.draw)
                    + attackTotal(state.discard);
        }
    }

    /** 牌堆里的攻击总量（蓝本 {@code retainedAttackValue} 的统计口径：手牌 + 抽牌 + 弃牌）。 */
    private static int attackTotal(java.util.List<SimCard> pile) {
        int total = 0;
        for (SimCard c : pile) {
            if (c.isAttack()) {
                total += Math.max(1, c.baseDamage);
            }
        }
        return total;
    }

    /**
     * 蓝本 {@code ProjectedPlayerHp}：敌人按当前意图打完，我还剩多少血。
     * 战斗已经结束（死了 / 没敌人了）就直接是当前血量 —— 蓝本 {@code won → threat = (CurrentHp, 0, 0)}。
     */
    public static int projectedHpOf(SimState state) {
        int alive = 0;
        int incoming = 0;
        for (SimMonster m : state.monsters) {
            if (m.hp > 0) {
                alive++;
                if (m.intentDmg > 0) {
                    incoming += m.intentDmg;
                }
            }
        }
        if (state.hp <= 0 || alive == 0) {
            return state.hp;
        }
        return state.hp - Math.max(0, incoming - state.block);
    }

    /** 把当前状态当作「路线起点」来估值（单测/面板/回退决策用）。 */
    public static Score of(SimState state) {
        return of(state, new Root(state), 0);
    }

    /**
     * @param root    本条路线的起点（血量/最大生命/回合数/开局攻击总量/开局敌人数）
     * @param actions 本条路线已经走了几步
     */
    public static Score of(SimState state, Root root, int actions) {
        Builder b = new Builder();
        b.playerHp = state.hp;
        b.maxHp = state.maxHp;
        b.maxHpGain = state.maxHp - root.maxHp;
        b.hpLost = Math.max(0, root.hp - state.hp);
        b.deathSaveHpRestored = state.deathSaveHpRestored;
        b.deathSaveUseCount = state.deathSaveUsed ? 1 : 0;
        b.block = state.block;
        b.energy = state.energy;
        b.handSize = state.hand.size();
        b.actions = actions;
        b.turns = Math.max(0, state.turn - root.turn);
        b.initialRetainedAttackValue = root.retainedAttackValue;
        b.initialEnemyCount = root.enemyCount;

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
        // ⚠️ 这里存**原始值**，不封顶 —— 易伤项自己取 min(24, ·)，成长项要原始差值。
        b.retainedAttackValue = attackTotal(state.hand) + attackTotal(state.draw)
                + attackTotal(state.discard);
        cardStats(state.hand, b);
        cardStats(state.draw, b);
        cardStats(state.discard, b);
        return new Score(b);
    }

    /** 统计牌堆里的「延迟收益能力牌」与「牌库杂质」。 */
    private static void cardStats(java.util.List<SimCard> pile, Builder b) {
        for (SimCard c : pile) {
            if ("POWER".equals(c.type)) {
                b.latentSetupValue += LATENT_POWER_VALUE;
            } else if ("STATUS".equals(c.type) || "CURSE".equals(c.type)) {
                b.clutter++;
            }
        }
    }

    private static int amount(Integer value) {
        return value == null || value < 0 ? 0 : value;
    }

    private static int amountRaw(Integer value) {
        return value == null ? 0 : value;
    }

    // ===================== 死亡豁免计价（蓝本 ActEndingBossPolicy） =====================

    /**
     * 蓝本 {@code ActEndingBossPolicy.DeathSavePremium}：复活回血的**溢价**。
     *
     * <pre>
     * DeathSavePremium(hp) = max(0, hp) * 900 / 100 = 9 * hp
     * </pre>
     *
     * <p>用倍数而不是等额，是因为复活买到的**不只是血** —— 两场 boss 连打的战斗里，
     * 场上还压着一百多点「敌方血等价物」的节奏价值，一比一的溢价会输给它。
     * 但 9 倍仍远低于 {@link #VICTORY_BONUS} 和 {@link #DEATH_PENALTY}，
     * 所以「只有死一次才能赢」的路线还是会毫不犹豫地花掉它。
     */
    public static int deathSavePremium(int deathSaveHpRestored) {
        return Math.max(0, deathSaveHpRestored) * DEATH_SAVE_PREMIUM_PERCENT / 100;
    }

    /**
     * 蓝本 {@code ActEndingBossPolicy.DeathSaveBeamCost}：在 **Beam 排序**里，一次死亡豁免的总代价。
     *
     * <pre>
     * DeathSaveBeamCost(hp) = hp + DeathSavePremium(hp) = 10 * hp
     * </pre>
     *
     * <p>为什么是「血量 + 溢价」而不是只有溢价：蓝本 {@code StateEvaluation.cs:163-172} 的
     * {@code projectedHp} 里**已经含着**复活回来的那些血（节点血量就是复活后的值），
     * 所以先把这笔血拿回来（{@code -restored}），再在上面收溢价。
     * 两项合起来才等价于「这条路线实际付出的代价」。
     */
    public static int deathSaveBeamCost(int deathSaveHpRestored) {
        int restored = Math.max(0, deathSaveHpRestored);
        return restored + deathSavePremium(restored);
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
        // 蓝本 StateEvaluation.cs:172：救命遗物回的血要连本带溢价扣回去
        score -= deathSaveBeamCost(deathSaveHpRestored) * HP;
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
        // 蓝本 SolverSearchProfile.BaseScoreOnly：中途排序只用基础分
        if (legacyScoring || baseScoreOnly) {
            return baseTotal();
        }
        double score = baseTotal();
        if (aliveEnemies > 0) {
            // 顺序照蓝本 BeamRankScore：能量 → 持续成长 → 延迟收益 → 攻击总量成长 → 易伤 → 控制
            // 还剩敌人时，手头的资源才有意义；打完这些项全部清零（否则「先防再杀」会靠余量微弱胜出）。
            score += Math.min(ENERGY_CAP, energy) * energyBeamValue;
            // 力量也算持续成长：蓝本把它并进 PersistentBuffValue 的 DamagePotential 里
            int persistent = scalingPowers + strength;
            score += Math.min(PERSISTENT_CAP, persistent) * PERSISTENT_VALUE;
            // 还没打出去的能力牌 = 延迟收益（蓝本：只在多敌人或 Boss 时启用）
            if (initialEnemyCount > 1) {
                score += Math.min(LATENT_SETUP_CAP, latentSetupValue) * LATENT_SETUP_VALUE;
            }
            // 牌堆攻击总量比开局更多（暴走/愤怒/武装）= 未来输出更高
            score += Math.min(RETAINED_GROWTH_CAP,
                    Math.max(0, retainedAttackValue - initialRetainedAttackValue))
                    * RETAINED_GROWTH_VALUE;
            // 易伤是倍率，价值 = 层数 × 吃得到它的攻击量（蓝本 VulnerableAttackWindowCap）
            int vulnWindow = Math.min(VULN_WINDOW_CAP, retainedAttackValue);
            score += focusVulnerable * vulnWindow * VULN_VALUE;
            score += Math.max(0, enemyVulnerable - focusVulnerable)
                    * vulnWindow * VULN_VALUE_OFF_TARGET;
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
                + " deathSave=" + deathSaveHpRestored + "(x" + deathSaveUseCount + ")"
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
                + " window=" + retainedAttackValue
                + " latent=" + latentSetupValue
                + " growth=" + Math.max(0, retainedAttackValue - initialRetainedAttackValue)
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
        int retainedAttackValue;
        int latentSetupValue;
        int initialRetainedAttackValue;
        int initialEnemyCount;
        int clutter;
        boolean dead;
        boolean won;
        int actions;
        int turns;
        int hpLost;
        int deathSaveHpRestored;
        int deathSaveUseCount;
    }
}
