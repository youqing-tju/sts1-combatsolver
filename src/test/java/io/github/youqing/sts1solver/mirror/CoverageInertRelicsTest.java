package io.github.youqing.sts1solver.mirror;

import io.github.youqing.sts1solver.sim.SimState;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 未镜像遗物告警的**信噪比**：开局/局外一次性遗物不该长期占着「这一把的 diff 会带噪声」。
 *
 * <p>2026-10-31 真机面板一直挂着 `未镜像遗物 3: Lantern, NeowsBlessing, Pear`，
 * 但那三件在战斗里根本没有持续触发：
 *
 * <ul>
 *   <li>{@code NeowsBlessing}（涅奥的祝福）：{@code atBattleStart} 把敌人血量设成 1，
 *       已体现在捕获的怪物 hp 上；</li>
 *   <li>{@code Pear}（梨）：{@code onEquip} 给 +10 最大生命，已体现在捕获的 {@code maxHp} 上；</li>
 *   <li>{@code Lantern}（提灯）：只在**第一回合**给 1 点能量（{@code atPreBattle} 置
 *       {@code firstTurn}，{@code atTurnStart} 用完即清）。第一回合的能量已由根状态
 *       {@code energy} 捕获，后续回合的能量则必须走 {@code energyPerTurn}
 *       （{@code EnergyManager.energyMaster}）—— 见 {@code SimStateEnergyPerTurnTest}。</li>
 * </ul>
 *
 * <p>这条测试同时守住反方向：**真有未镜像遗物时必须继续告警**，
 * 不能把白名单当成消音器。
 */
class CoverageInertRelicsTest {

    @Test
    void combatStartAndOutOfCombatRelicsAreNotReportedAsUnmirrored() {
        SimState s = new SimState();
        s.relics.put("Lantern", Integer.valueOf(-1));
        s.relics.put("NeowsBlessing", Integer.valueOf(-1));
        s.relics.put("Pear", Integer.valueOf(-1));

        List<String> missing = Coverage.unmirroredRelics(s);

        assertTrue(missing.isEmpty(),
                "这三件在战斗内没有持续触发，不该长期告警，实际报出: " + missing);
    }

    @Test
    void aRelicWithoutAMirrorIsStillReported() {
        SimState s = new SimState();
        s.relics.put("Shuriken", Integer.valueOf(0));       // 已镜像（计数在 RelicHooks 里）
        s.relics.put("Test Relic", Integer.valueOf(-1));    // 没有镜像

        assertEquals(Collections.singletonList("Test Relic"),
                Coverage.unmirroredRelics(s));
    }
}
