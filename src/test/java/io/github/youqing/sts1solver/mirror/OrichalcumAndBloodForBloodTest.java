package io.github.youqing.sts1solver.mirror;

import io.github.youqing.sts1solver.sim.CombatFlow;
import io.github.youqing.sts1solver.sim.MonsterMove;
import io.github.youqing.sts1solver.sim.RngStream;
import io.github.youqing.sts1solver.sim.SimCard;
import io.github.youqing.sts1solver.sim.SimMonster;
import io.github.youqing.sts1solver.sim.SimState;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 两个真机报上来的「机制看起来没生效」：山铜（Orichalcum）与以血还血（Blood for Blood）的费用。
 *
 * <p>真机原话（2026-10-31）：
 * <ul>
 *   <li>「奥利哈刚未被触发」</li>
 *   <li>「以血还血的减费未被识别到，打了个零费的以血还血就不打了」</li>
 * </ul>
 *
 * <p>这两条都必须是**能在离线复现的判据**，否则下次还是靠猜。
 */
class OrichalcumAndBloodForBloodTest {

    private static SimState combat(int block) {
        SimState s = new SimState();
        s.hp = 60;
        s.maxHp = 80;
        s.energy = 3;
        s.turn = 3;
        s.block = block;
        s.rng.put(SimState.RngKind.AI, RngStream.from(20261031L, 11L, 0));
        s.rng.put(SimState.RngKind.CARD, RngStream.from(20261031L, 22L, 0));
        s.rng.put(SimState.RngKind.SHUFFLE, RngStream.from(20261031L, 33L, 0));
        s.rng.put(SimState.RngKind.MISC, RngStream.from(20261031L, 44L, 0));
        s.rng.put(SimState.RngKind.MONSTER, RngStream.from(20261031L, 55L, 0));
        s.relics.put("Orichalcum", Integer.valueOf(-1));
        for (int i = 0; i < 5; i++) {
            s.draw.add(new SimCard("Strike_R", false, 1, 6, "ATTACK"));
        }
        SimMonster jaw = new SimMonster("JawWorm", 44, 44, 0,
                Collections.<String, Integer>emptyMap());
        MonsterMove chomp = io.github.youqing.sts1solver.sim.MonsterMoves
                .next(s, jaw, 3, 0);
        jaw.pendingMove = chomp;
        jaw.pendingMoveId = chomp.id;
        s.monsters.add(jaw);
        return s;
    }

    /** 回合结束**一点格挡都没有** → 山铜 +6，且这 6 点要吃住敌人的这一击。 */
    @Test
    void orichalcumGrantsSixBlockWhenTheTurnEndsWithNoBlock() {
        SimState s = combat(0);
        CombatFlow.endTurn(s, 3, 5);
        // 颚虫 Chomp 11 点，山铜给 6 → 只掉 5 血
        assertEquals(55, s.hp, "山铜的 6 点格挡必须挡住一部分伤害");
    }

    /** 回合结束时还有格挡 → 不触发（原版 `currentBlock != 0` 直接 return）。 */
    @Test
    void orichalcumDoesNotTriggerWhenBlockRemains() {
        SimState s = combat(4);
        CombatFlow.endTurn(s, 3, 5);
        // 原有 4 点格挡 + 敌人 11 → 掉 7（山铜不再加 6）
        assertEquals(53, s.hp, "有格挡时山铜不生效");
    }

    /**
     * 以血还血：**费用以捕获到的卡面为准**（原版 {@code tookDamage()} 会把 {@code cost} 永久 -1），
     * 不能再按 hpLossCount 二次减 —— 那会让搜索以为它更便宜，把能量花在别处。
     */
    @Test
    void bloodForBloodUsesTheCapturedCostNotAnExtraHpLossDiscount() {
        SimState s = combat(0);
        s.hand.add(new SimCard("Blood for Blood", false, 2, 18, "ATTACK"));
        int energyBefore = s.energy;

        CardPlayer.play(s, 0, 0);

        assertEquals(energyBefore - 2, s.energy, "卡面写着 2 费就扣 2，不许再减一次");
        assertEquals(26, s.monsters.get(0).hp, "18 伤害");
    }

    /** 捕获到 0 费时就是 0 费，别再减成负数或倒贴能量。 */
    @Test
    void zeroCostBloodForBloodSpendsNothing() {
        SimState s = combat(0);
        s.hand.add(new SimCard("Blood for Blood", false, 0, 18, "ATTACK"));
        int energyBefore = s.energy;

        CardPlayer.play(s, 0, 0);

        assertEquals(energyBefore, s.energy, "0 费就是 0 费");
    }
}
