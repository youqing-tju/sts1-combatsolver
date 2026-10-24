package io.github.youqing.sts1solver.search;

import io.github.youqing.sts1solver.sim.SimCard;
import io.github.youqing.sts1solver.sim.SimMonster;
import io.github.youqing.sts1solver.sim.SimState;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 战后阈值回血进基础分（蓝本 {@code StateEvaluation.cs:200-207}）。
 *
 * <p>口径要说清楚，否则很容易算重：
 * <ul>
 *   <li>**单调**那部分（燃烧之血 6 / 黑血 12，无条件回血）→ 终局排序的 {@code StrategicHpDeficit}；</li>
 *   <li>**阈值**那部分（带骨肉：结束血量 ≤50% 才回 12）→ 基础分
 *       {@code += (HealFor - MonotoneHealFor) * HP}，且只在**赢了**的时候加。</li>
 * </ul>
 * 两条轴不能互相包含，所以带「燃烧之血 + 带骨肉」的场面必须只补 12 而不是 18。
 */
class PostCombatThresholdHealTest {

    private static final double HP = Score.HP;

    private static SimState state(int hp, int maxHp, boolean monsterAlive) {
        SimState s = new SimState();
        s.hp = hp;
        s.maxHp = maxHp;
        s.energy = 3;
        s.hand.add(new SimCard("Strike_R", false, 1, 6, "ATTACK"));
        for (int i = 0; i < 8; i++) {
            s.draw.add(new SimCard("Strike_R", false, 1, 6, "ATTACK"));
        }
        if (monsterAlive) {
            SimMonster m = new SimMonster("TestMonster", 20, 20, 0,
                    Collections.<String, Integer>emptyMap());
            m.intentDmg = 0;
            s.monsters.add(m);
        } else {
            SimMonster dead = new SimMonster("TestMonster", 0, 20, 0,
                    Collections.<String, Integer>emptyMap());
            s.monsters.add(dead);
        }
        return s;
    }

    private static long base(SimState s) {
        return Score.of(s, new Score.Root(s), 0).baseTotal();
    }

    /** 结束血量正好卡在 50%：阈值回血 12 点必须进分。 */
    @Test
    void creditsTheThresholdHealWhenTheFightIsWonBelowHalfHp() {
        SimState without = state(40, 80, false);
        SimState with = state(40, 80, false);
        with.relics.put("Meat on the Bone", 1);

        assertEquals(12d * HP, (double) (base(with) - base(without)), 1d,
                "带骨肉的 12 点阈值回血应该按血值计入基础分");
    }

    /** 结束血量高于阈值：带骨肉不触发，一分都不该多。 */
    @Test
    void paysNothingAboveTheThreshold() {
        SimState without = state(41, 80, false);
        SimState with = state(41, 80, false);
        with.relics.put("Meat on the Bone", 1);

        assertEquals(0d, (double) (base(with) - base(without)), 1d,
                "41/80 高于 50% 阈值，带骨肉不触发");
    }

    /** 打输了没有战后结算 —— 哪怕血量在阈值以下。 */
    @Test
    void paysNothingWhenTheFightIsNotWon() {
        SimState without = state(40, 80, true);
        SimState with = state(40, 80, true);
        with.relics.put("Meat on the Bone", 1);

        assertEquals(0d, (double) (base(with) - base(without)), 1d,
                "还有怪活着 = 没赢，战后遗物不结算");
    }

    /**
     * 燃烧之血的无条件回血**不能**被算第二遍。
     *
     * <p>它已经在终局排序的赤字里抵过了；这里只补「HealFor - MonotoneHealFor」的差额，
     * 所以带两件遗物时增量仍是 12，不是 18。
     */
    @Test
    void unconditionalHealIsNotCountedTwice() {
        SimState without = state(40, 80, false);
        SimState with = state(40, 80, false);
        with.relics.put("Burning Blood", 1);
        with.relics.put("Meat on the Bone", 1);

        SimState burningOnly = state(40, 80, false);
        burningOnly.relics.put("Burning Blood", 1);

        assertEquals(12d * HP, (double) (base(with) - base(burningOnly)), 1d,
                "叠了燃烧之血之后，增量仍应只有带骨肉的 12 点（无条件那 6 点走赤字轴）");
        assertEquals(0d, (double) (base(without) - base(burningOnly)), 1d,
                "只有带骨肉、不满足阈值时，与无遗物同分");
    }

    /** 回不满就只回到满血：maxHp 20 / hp 10 只剩 10 点空间，只该补 10。 */
    @Test
    void neverCreditsMoreThanTheHeadroomToFullHp() {
        SimState without = state(10, 20, false);
        SimState with = state(10, 20, false);
        with.relics.put("Meat on the Bone", 1);

        assertEquals(10d * HP, (double) (base(with) - base(without)), 1d,
                "只剩 10 点空位，12 点回血只能兑现 10 点");
    }
}
