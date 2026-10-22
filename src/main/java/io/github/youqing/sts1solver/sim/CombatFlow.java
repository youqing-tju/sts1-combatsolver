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

        // ③ 弃手牌之后：燃烧 / 炸弹 / 欧米伽
        Hooks.firePhase(state, HookPhase.END_OF_TURN);

        // ④ 敌方行动
        // 🔴 顺序很重要：**先让格挡吸收敌方伤害，再清空格挡**。
        // 反过来写（先清后打）会让格挡完全失效 —— 搜索于是永远不防御，
        // 这正是 BeamSolverTest 抓到的问题。
        for (SimMonster m : state.monsters) {
            if (m.hp > 0 && m.intentDmg > 0) {
                hitPlayer(state, m.intentDmg);
            }
        }

        // ⑤ 敌人打完，剩余格挡清零（壁垒除外）
        if (power(state, "Barricade") <= 0) {
            state.block = 0;
        }

        // ⑥ 进入下一回合：按「意图学习表」刷新怪物意图（查不到就沿用当前值）
        state.turn = state.turn + 1;
        for (SimMonster m : state.monsters) {
            if (m.hp > 0) {
                m.intentDmg = IntentLearning.predict(m.type, state.turn, m.intentDmg);
            }
        }

        // ⑦ 新回合的钩子 → 回能 → 抽牌 → 抽牌后的钩子
        Hooks.firePhase(state, HookPhase.START_OF_TURN);
        state.energy = energyPerTurn;
        draw(state, handSize);
        Hooks.firePhase(state, HookPhase.START_OF_TURN_POST_DRAW);
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
