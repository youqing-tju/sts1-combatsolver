package io.github.youqing.sts1solver.search;

import io.github.youqing.sts1solver.mirror.PotionValuation;
import io.github.youqing.sts1solver.sim.SimCard;
import io.github.youqing.sts1solver.sim.SimMonster;
import io.github.youqing.sts1solver.sim.SimState;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 药水对账闸（蓝本 {@code PotionUsePolicy.StrategicHpCost} 的最小切片）。
 *
 * <p>规则：喝掉一瓶药，这条路线相比「不喝药」至少要多省下 {@code strategicHpCost} 点血，
 * 否则退回不喝药的路线。没有这道闸，搜索会拿药水去换「早一回合结束」——
 * 最终排序里 {@code CombatEndedTurn} 排在敌方血之前，早结束本身就能赢下平手。
 */
class PotionCostGateTest {

    private static SimState state(int monsterHp, int intentDmg) {
        SimState s = new SimState();
        s.hp = 30;
        s.maxHp = 80;
        s.energy = 1;
        s.hand.add(new SimCard("Strike_R", false, 1, 6, "ATTACK"));
        for (int i = 0; i < 8; i++) {
            s.draw.add(new SimCard("Strike_R", false, 1, 6, "ATTACK"));
        }
        SimMonster m = new SimMonster("TestMonster", monsterHp, monsterHp, 0,
                Collections.<String, Integer>emptyMap());
        m.intentDmg = intentDmg;
        s.monsters.add(m);
        s.potions.add("Fire Potion");       // 20 点单体伤害，基准档 = 9 血
        s.potions.add(null);
        return s;
    }

    private static boolean routeUsesPotion(BeamSolver solver) {
        for (PlanAction action : solver.lastRoute) {
            if (action.kind == PlanAction.Kind.USE_POTION) {
                return true;
            }
        }
        return false;
    }

    /** 药水档位：基准 9 / 溢价 14 / 高价值 18，数值照蓝本 SolverWeights。 */
    @Test
    void tierCostsFollowTheBlueprintWeights() {
        assertEquals(9, PotionValuation.strategicHpCost("Fire Potion"));
        assertEquals(14, PotionValuation.strategicHpCost("LiquidMemories"));
        assertEquals(18, PotionValuation.strategicHpCost("Fruit Juice"));
        assertEquals(18, PotionValuation.strategicHpCost("GamblersBrew"));
        assertEquals(9, PotionValuation.strategicHpCost("随便一个没登记的 ID"));
        assertEquals(9, PotionValuation.strategicHpCost(null));
    }

    /**
     * 喝火药只是「早一回合结束」、一点血都没省 → 必须退回不喝药的路线。
     *
     * <p>局面：怪 20 血、**意图 0**（这回合怎么打都不掉血）、能量 1、手里一张 Strike。
     * 不喝药：打 6 → 下回合再补刀，2 回合结束、0 掉血。
     * 喝火药：20 伤直接打死，1 回合结束、同样 0 掉血 —— 省血 0 &lt; 成本 9，闸必须挡掉。
     */
    @Test
    void rejectsAPotionThatBuysNoHp() {
        SimState s = state(20, 0);

        BeamSolver gated = new BeamSolver(3, 5, 8, 6);
        gated.bestFirstAction(s.copy());
        assertFalse(routeUsesPotion(gated), "省不到 9 点血就不该喝这瓶火药");
        assertTrue(gated.lastPotionSpendRejected, "遥测要记下「因买不回成本被退回」");

        // 关掉的只是「额外跑一趟无药基线」；**资格本身是无条件的**（FinalPlanOrdering 内建），
        // 所以任何配置下这条买不回血的药水都不该被喝。旧实现里这条对照组会喝 ——
        // 那正是「先选一条再补救」留下的洞。
        BeamSolver ungated = new BeamSolver(3, 5, 8, 6).withPotionCostGate(false);
        ungated.bestFirstAction(s.copy());
        assertFalse(routeUsesPotion(ungated),
                "资格链无条件生效：买不回血的药水在不跑基线时同样不喝");
    }

    /**
     * 喝了能救命的场面：闸必须放行。
     *
     * <p>怪 20 血、意图 25（这回合不杀它就死），能量 0、手里没有能打出的牌。
     * 喝火药 20 伤正好打死 —— 省下的血远超 9，当然要喝。
     */
    @Test
    void keepsAPotionThatActuallySavesHp() {
        SimState s = state(20, 25);
        s.energy = 0;
        s.hand.clear();

        BeamSolver solver = new BeamSolver(3, 5, 8, 6);
        PlanAction action = solver.bestFirstAction(s);

        assertNotNull(action);
        assertEquals(PlanAction.Kind.USE_POTION, action.kind, "不喝就死，必须喝");
        assertTrue(solver.lastPotionHpSaved >= 9,
                "省下的血应当远超成本，实际 " + solver.lastPotionHpSaved);
    }

    /** 一滴药没喝的路线不该触发对账（也就不该多跑一趟基线搜索）。 */
    @Test
    void doesNotRunTheBaselineWhenNoPotionWasUsed() {
        SimState s = state(20, 0);
        s.potions.clear();

        BeamSolver solver = new BeamSolver(3, 5, 8, 6);
        solver.bestFirstAction(s);

        assertEquals(0, solver.lastPotionStrategicCost);
        assertFalse(solver.lastPotionSpendRejected);
    }
}
