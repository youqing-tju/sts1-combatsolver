package io.github.youqing.sts1solver.sim;

/**
 * 回合切换的最小模型：结束玩家回合 → 敌方按意图造成伤害 → 新回合回能抽牌。
 *
 * <p>只建模搜索估值需要的那部分。**不知道的一律不假装知道**：
 * 状态牌、易伤/虚弱递减、遗物触发、怪物被动能力都没有模拟，
 * 因此估值只能当作「相对好坏」的参考，不能当作精确血量预测。
 */
public final class CombatFlow {

    /** 手牌上限，同 {@code Effects.HAND_LIMIT}。 */
    private static final int HAND_LIMIT = 10;

    private CombatFlow() {
    }

    /** 结束玩家回合并进入下一回合。 */
    public static void endTurn(SimState state, int energyPerTurn, int handSize) {
        // 手牌进弃牌堆
        state.discard.addAll(state.hand);
        state.hand.clear();

        // 🔴 顺序很重要：**先让格挡吸收敌方伤害，再清空格挡**。
        // 反过来写（先清后打）会让格挡完全失效 —— 搜索于是永远不防御，
        // 这正是 BeamSolverTest 抓到的问题。
        for (SimMonster m : state.monsters) {
            if (m.hp > 0 && m.intentDmg > 0) {
                hitPlayer(state, m.intentDmg);
            }
        }

        // 敌人打完，剩余格挡清零（壁垒除外）
        if (power(state, "Barricade") <= 0) {
            state.block = 0;
        }

        // 新回合
        state.energy = energyPerTurn;
        draw(state, handSize);
    }

    private static void hitPlayer(SimState state, int damage) {
        int remaining = damage;
        if (state.block > 0) {
            int absorbed = Math.min(state.block, remaining);
            state.block -= absorbed;
            remaining -= absorbed;
        }
        state.hp = Math.max(0, state.hp - remaining);
    }

    private static void draw(SimState state, int count) {
        for (int i = 0; i < count; i++) {
            if (state.hand.size() >= HAND_LIMIT) {
                return;
            }
            if (state.draw.isEmpty()) {
                return;   // 洗牌未建模：抽牌堆空了就不再抽，估值会偏保守
            }
            state.hand.add(state.draw.remove(state.draw.size() - 1));
        }
    }

    private static int power(SimState state, String id) {
        Integer v = state.playerPowers.get(id);
        return v == null ? 0 : v;
    }
}
