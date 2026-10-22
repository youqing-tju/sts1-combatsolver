package io.github.youqing.sts1solver.search;

import io.github.youqing.sts1solver.sim.SimMonster;
import io.github.youqing.sts1solver.sim.SimState;

/**
 * 六维估值（计划书 M2-4）。
 *
 * <p>每一维都能单独打印，便于解释「AI 为什么选了这一步」——
 * 蓝本同样把估值拆成多项（战略血量赤字、回合数、资源、控制…），
 * 只是我们有六项，够用且可读。
 *
 * <p>权重按「越靠前越不可交换」设计：少一个敌人 > 血量 > 敌方血量 > 格挡 > 能量 > 手牌。
 */
public final class Score {

    public final int aliveEnemies;
    public final int playerHp;
    public final int enemyHp;
    public final int block;
    public final int energy;
    public final int handSize;
    /**
     * 投影血量：本回合结束后、敌方按当前意图打完，我还剩多少血（蓝本的 ProjectedPlayerHp）。
     * 这是「一阶前瞻」——不需要回放，就能知道这一步会不会走进死亡。
     */
    public final int projectedHp;
    /**
     * 最脆的那个敌人还剩多少有效血量（hp + 格挡）。
     * 用来鼓励集火：把伤害堆到一个敌人身上比平均撒更容易减员。
     * 蓝本对应概念见 {@code EnemyDurabilityVectorBuilder}（按每个敌人的耐久建向量）。
     */
    public final int easiestKill;
    /**
     * 玩家的**成长性能力**层数（恶魔形态/金属化/主宰/燃烧/无痛/黑暗拥抱/壁垒…）。
     *
     * <p>之前这些完全不计分，结果是「打能力牌 = 少能量少手牌 → 分数反而下降」，
     * AI 几乎不会主动打成长牌。蓝本把这类状态算进战略血量赤字。
     */
    public final int scalingPowers;
    /** 玩家力量层数（每层放大之后每一次攻击）。 */
    public final int strength;
    /** 敌人身上的易伤层数合计（放大我们的输出）。 */
    public final int enemyVulnerable;
    /** 敌人身上的虚弱层数合计（压低对方输出）。 */
    public final int enemyWeak;

    private Score(int aliveEnemies, int playerHp, int enemyHp, int block, int energy,
                  int handSize, int projectedHp, int easiestKill, int scalingPowers,
                  int strength, int enemyVulnerable, int enemyWeak) {
        this.aliveEnemies = aliveEnemies;
        this.playerHp = playerHp;
        this.enemyHp = enemyHp;
        this.block = block;
        this.energy = energy;
        this.handSize = handSize;
        this.projectedHp = projectedHp;
        this.easiestKill = easiestKill;
        this.scalingPowers = scalingPowers;
        this.strength = strength;
        this.enemyVulnerable = enemyVulnerable;
        this.enemyWeak = enemyWeak;
    }

    /** 会「每个回合都变强」的能力：层数越打越值钱。 */
    private static final String[] SCALING = {
            "Metallicize", "Demon Form", "Juggernaut", "Combust", "Feel No Pain",
            "Dark Embrace", "Barricade", "Corruption", "Fire Breathing", "Rage",
            "Thorns", "Plated Armor", "Buffer", "Intangible", "Echo Form", "Brutality",
    };

    public static Score of(SimState state) {
        int alive = 0;
        int enemyHp = 0;
        int easiest = Integer.MAX_VALUE;
        for (SimMonster m : state.monsters) {
            if (m.hp > 0) {
                alive++;
                enemyHp += m.hp;
                easiest = Math.min(easiest, m.hp + m.block);
            }
        }
        if (easiest == Integer.MAX_VALUE) {
            easiest = 0;
        }
        int incoming = 0;
        int vuln = 0;
        int weak = 0;
        for (SimMonster m : state.monsters) {
            if (m.hp > 0) {
                if (m.intentDmg > 0) {
                    incoming += m.intentDmg;
                }
                vuln += amount(m.powers.get(io.github.youqing.sts1solver.mirror.DamageCalc.VULNERABLE));
                weak += amount(m.powers.get(io.github.youqing.sts1solver.mirror.DamageCalc.WEAKENED));
            }
        }
        int projected = state.hp - Math.max(0, incoming - state.block);

        int scaling = 0;
        for (String id : SCALING) {
            scaling += amount(state.playerPowers.get(id));
        }
        int strength = amount(state.playerPowers.get(
                io.github.youqing.sts1solver.mirror.DamageCalc.STRENGTH));

        return new Score(alive, state.hp, enemyHp, state.block, state.energy,
                state.hand.size(), projected, easiest, scaling, strength, vuln, weak);
    }

    private static int amount(Integer value) {
        return value == null || value < 0 ? 0 : value;
    }

    /**
     * 总分，越大越好。
     *
     * <p>格挡/能量/手牌只在「场上还有敌人」时计分：战斗已结束时这些都没有价值，
     * 否则「先防再杀」会因为残留资源而微弱胜出（曾经真的发生过）。
     */
    public long total() {
        long score = 0;
        score -= (long) aliveEnemies * 1_000_000_000L;
        score += (long) playerHp * 1_000_000L;
        score -= (long) enemyHp * 1_000L;
        if (aliveEnemies > 0) {
            // ⚠️ 不再按「格挡多少」加分：超出本回合伤害的格挡是浪费，
            // 会让 AI 无脑防御（实测：不该防的时候也打 Defend）。
            // 格挡的价值只通过 projectedHp 体现，天然被「本回合真实伤害」封顶。
            score += (long) energy * 5L;
            score += handSize;
            // 集火倾向：最脆的敌人越接近死亡越好（权重高于敌方总血，低于「少一个敌人」）
            score -= (long) easiestKill * 5_000L;
            // 一阶前瞻：敌人打完后的血量也要计入，且死了要重罚。
            // 权重取 0.2 倍血量：小于「少一个敌人」(1e9)，大于普通血量波动。
            score += (long) projectedHp * 200_000L;
            if (projectedHp <= 0) {
                score -= 300_000_000L;
            }
            // 成长与减益：都以「1 点敌方血量 = 1000」为单位折算成未来收益，
            // 免得 AI 因为「打能力牌少一张手牌」而永远不打开局面。
            score += (long) scalingPowers * 6_000L;
            score += (long) strength * 3_000L;
            score += (long) enemyVulnerable * 1_500L;
            score += (long) enemyWeak * 1_500L;
        }
        return score;
    }

    /** 人类可读的分项，用于调试与日志。 */
    public String explain() {
        return "alive=" + aliveEnemies
                + " hp=" + playerHp
                + " enemyHp=" + enemyHp
                + " block=" + block
                + " energy=" + energy
                + " hand=" + handSize
                + " projHp=" + projectedHp
                + " easiest=" + easiestKill
                + " scaling=" + scalingPowers
                + " str=" + strength
                + " vuln=" + enemyVulnerable
                + " weak=" + enemyWeak
                + " total=" + total();
    }
}
