package io.github.youqing.sts1solver.search;

import io.github.youqing.sts1solver.sim.SimState;
import org.junit.jupiter.api.Test;

import java.io.File;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 药水闸必须是**最后一步**：新颖度通道不能把它否掉的药水又捞回来。
 *
 * <p>真机现场（2026-11-01 日志，7 次喝药里 6 次是这种）：
 *
 * <pre>
 * 14:31:22.722  POTION_GATE cost=9 hp_saved=0 potions=1 rejected=true   ← 闸说「别喝」
 * 14:31:22.730  PLAN steps: Strike | POTION:ColorlessPotion | …        ← 计划里还在喝
 * 14:31:23.363  AUTO potion ColorlessPotion (slot 0)                   ← 真喝了
 * </pre>
 *
 * <p>根因：{@code bestFirstAction} 里顺序是「闸门 → 0 费补搜 → 新颖度二选一」，
 * 新颖度那条候选是在闸门**之前**算的，它带着药水步骤，还能靠 {@code better()} 把闸门
 * 否掉的路线换回来 —— 闸门等于白跑。修法是把二选一做在闸门**前面**，让后处理面对最终答案。
 *
 * <p>夹具 {@code act1-potion-gate-novelty.json} 是 2026-11-01 13:12 真机 A1F7T2
 * （53/80 血、3 只小怪、药水 PowerPotion + EssenceOfSteel）。用真机档位
 * （宽 135 / 节点 50 万 / 分支 72 / 深度 500 / 新颖度 + 宽度组合）跑，闸门会否掉喝药，
 * 而新颖度通道会把带药水的那条捞回来 —— 修前 {@code leaked=4/107} 里的一个。
 */
class PotionGateBeatsNoveltyTest {

    private static final File FIXTURE =
            new File("src/test/resources/golden/act1-potion-gate-novelty.json");

    private static boolean routeUsesPotion(BeamSolver solver) {
        for (PlanAction action : solver.lastRoute) {
            if (action.kind == PlanAction.Kind.USE_POTION) {
                return true;
            }
        }
        return false;
    }

    @Test
    void theNoPotionVerdictSurvivesTheNoveltyPass() throws Exception {
        SimState root = GoldenPositions.read(FIXTURE);

        BeamSolver solver = new BeamSolver(3, 5, 135, BeamSolver.SAFETY_MAX_TURN_LAYERS,
                500_000, 72).withNovelty().withPortfolio();
        solver.bestFirstAction(root.copy(), 300_000L);

        assertTrue(solver.lastPotionSpendRejected,
                "这局喝药买不回 9 点血，闸门应当拒绝（否则本测试没有意义）");
        assertFalse(routeUsesPotion(solver),
                "闸门拒绝之后，最终路线里不允许再出现喝药 —— 新颖度通道不能把它捞回来");
    }
}
