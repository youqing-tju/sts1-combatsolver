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

    private Score(int aliveEnemies, int playerHp, int enemyHp, int block, int energy,
                  int handSize, int projectedHp) {
        this.aliveEnemies = aliveEnemies;
        this.playerHp = playerHp;
        this.enemyHp = enemyHp;
        this.block = block;
        this.energy = energy;
        this.handSize = handSize;
        this.projectedHp = projectedHp;
    }

    public static Score of(SimState state) {
        int alive = 0;
        int enemyHp = 0;
        for (SimMonster m : state.monsters) {
            if (m.hp > 0) {
                alive++;
                enemyHp += m.hp;
            }
        }
        int incoming = 0;
        for (SimMonster m : state.monsters) {
            if (m.hp > 0 && m.intentDmg > 0) {
                incoming += m.intentDmg;
            }
        }
        int projected = state.hp - Math.max(0, incoming - state.block);
        return new Score(alive, state.hp, enemyHp, state.block, state.energy,
                state.hand.size(), projected);
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
            score += (long) block * 10L;
            score += (long) energy * 5L;
            score += handSize;
            // 一阶前瞻：敌人打完后的血量也要计入，且死了要重罚。
            // 权重取 0.2 倍血量：小于「少一个敌人」(1e9)，大于普通血量波动。
            score += (long) projectedHp * 200_000L;
            if (projectedHp <= 0) {
                score -= 300_000_000L;
            }
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
                + " total=" + total();
    }
}
