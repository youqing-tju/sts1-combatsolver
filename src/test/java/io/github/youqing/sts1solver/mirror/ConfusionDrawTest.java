package io.github.youqing.sts1solver.mirror;

import io.github.youqing.sts1solver.sim.RngStream;
import io.github.youqing.sts1solver.sim.SimCard;
import io.github.youqing.sts1solver.sim.SimState;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 混乱（{@code ConfusionPower}，蛇眼挂的那个）必须在**所有**抽牌路径上生效。
 *
 * <p>为什么单开一条：真机 2026-10-31 一轮里 13 次 `rng.CARD` 失配全来自同一个根因 ——
 * `Effects.draw` 自己写了个搬牌循环，绕过 {@code CombatFlow.draw}，于是「每抽一张牌掷一次
 * cardRandomRng 重定费用」这一步被整个跳过：实机战斗专注+ 抽 4 张走了 4 步 RNG，我们 0 步。
 */
class ConfusionDrawTest {

    private static SimState stateWithConfusion() {
        SimState s = new SimState();
        s.hp = 60;
        s.maxHp = 80;
        s.energy = 3;
        s.turn = 3;
        s.rng.put(SimState.RngKind.CARD, RngStream.from(20261031L, 7L, 0));
        s.playerPowers.put("Confusion", Integer.valueOf(-1));
        s.draw.add(new SimCard("Strike_R", false, 1, 6, "ATTACK"));
        s.draw.add(new SimCard("Defend_R", false, 1, -1, "SKILL"));
        return s;
    }

    @Test
    void cardDrawRollsTheCostAndConsumesOneCardRngStep() {
        SimState s = stateWithConfusion();
        int before = s.rng.get(SimState.RngKind.CARD).counter();

        Effects.draw(1).applyTo(s, -1, null);

        assertEquals(1, s.hand.size(), "抽到 1 张");
        assertEquals(before + 1, s.rng.get(SimState.RngKind.CARD).counter(),
                "每抽一张牌必须消耗一次 cardRandomRng（费用重掷）");
        int cost = s.hand.get(0).cost;
        assertTrue(cost >= 0 && cost <= 3, "Confusion 掷出来的是 0..3，实际 " + cost);
    }

    @Test
    void withoutConfusionTheStreamIsUntouched() {
        SimState s = stateWithConfusion();
        s.playerPowers.remove("Confusion");
        int before = s.rng.get(SimState.RngKind.CARD).counter();

        Effects.draw(2).applyTo(s, -1, null);

        assertEquals(2, s.hand.size());
        assertEquals(before, s.rng.get(SimState.RngKind.CARD).counter(), "没有混乱就不该动 RNG");
        assertEquals(-1, s.hand.get(0).costForTurn, "费用没被改过");
    }

    /** X 费（-1）与打不出（-2）不重掷、也不消耗随机数（原版 `if (card.cost >= 0)`）。 */
    @Test
    void xCostAndUnplayableCardsAreNotRerolled() {
        SimState s = stateWithConfusion();
        s.draw.clear();
        s.draw.add(new SimCard("Whirlwind", false, -1, 5, "ATTACK"));
        s.draw.add(new SimCard("Pain", false, -2, -1, "CURSE"));
        int before = s.rng.get(SimState.RngKind.CARD).counter();

        Effects.draw(2).applyTo(s, -1, null);

        assertEquals(before, s.rng.get(SimState.RngKind.CARD).counter(),
                "-1 / -2 的牌不掷随机数");
        // 抽牌堆的顶在列表末尾，所以先抽到 Pain、再抽到 Whirlwind
        assertEquals(-2, s.hand.get(0).cost, "Pain 的 -2 保持不变");
        assertEquals(-1, s.hand.get(1).cost, "旋风斩的 X 费保持不变");
        assertEquals(Collections.emptyList(), s.discard);
    }
}
