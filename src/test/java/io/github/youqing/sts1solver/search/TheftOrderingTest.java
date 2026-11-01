package io.github.youqing.sts1solver.search;

import io.github.youqing.sts1solver.sim.SimCard;
import io.github.youqing.sts1solver.sim.SimMonster;
import io.github.youqing.sts1solver.sim.SimState;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 抢錢怪：**宁可掉点血，也不能让它把钱带走**。
 *
 * <p>蓝本 {@code FinalPlanOrdering.cs:253-256} 的词序里，
 * {@code OutstandingStolenResource} 排在 {@code StrategicHpDeficit} **之前**：
 * <pre>
 * .OrderByDescending(c => c.Quality.Won)
 * .ThenBy(c => !c.Quality.Won && !c.Quality.Survives ? 1 : 0)
 * .ThenBy(c => c.Quality.DeathSaveUseCount)
 * .ThenBy(c => theftPolicy == PreserveResources ? c.Quality.OutstandingStolenResource : 0)   // ← 这里
 * .ThenBy(c => c.Quality.StrategicHpDeficit)
 * </pre>
 *
 * <p>所以「多掉 8 点血但把钱追回来」必须赢过「一点血不掉但让贼跑了」。
 * 这条测试就是钉这个次序 —— 它以前不存在，因为影子根本没建模偷钱，
 * 于是 AI 面对掠夺者只会防御，眼睁睁看它摸完就跑。
 */
class TheftOrderingTest {

    private static SimState state(int hp, int gold, int stolen, boolean thiefAlive) {
        SimState s = new SimState();
        s.turn = 2;
        s.hp = hp;
        s.maxHp = 80;
        s.gold = gold;
        s.energy = 3;
        s.energyPerTurn = 3;
        s.hand.add(new SimCard("Strike_R", false, 1, 6, "ATTACK"));
        SimMonster thief = new SimMonster("Looter", thiefAlive ? 0 : 0, 44, 0,
                Collections.<String, Integer>emptyMap());
        thief.hp = thiefAlive ? 12 : 0;
        thief.stolenGold = stolen;
        s.monsters.add(thief);
        SimMonster other = new SimMonster("JawWorm", 40, 44, 0,
                Collections.<String, Integer>emptyMap());
        s.monsters.add(other);
        return s;
    }

    private static SearchNode node(SimState s) {
        return new SearchNode(s, null, null, 1, 0L);
    }

    @Test
    void recoveringTheGoldBeatsSavingEightHp() {
        // 路线 A：打死掠夺者（钱追回来），但为此没格挡，多掉 8 血
        SearchNode killIt = node(state(42, 85, 0, true));
        // 路线 B：让它跑（钱没了），一滴血没掉
        SearchNode letItEscape = node(state(50, 85, 15, true));

        BeamSolver solver = new BeamSolver(3, 5, 8, 6);
        assertTrue(solver.betterInterim(killIt, letItEscape),
                "钱追回来（outstanding 0）必须压过多掉的血 —— 这是蓝本的键序");
        assertFalse(solver.betterInterim(letItEscape, killIt));
    }

    @Test
    void whenNothingWasStolenHpStillDecides() {
        // 两只都不是抢錢怪（stolen 0/0）：这一键不生效，回到「血少的好」
        SearchNode healthier = node(state(50, 100, 0, true));
        SearchNode hurt = node(state(42, 100, 0, true));

        BeamSolver solver = new BeamSolver(3, 5, 8, 6);
        SimState root = state(80, 100, 0, true);       // 开局 80 血：两条路线分别掉 30 / 38
        solver.bindRoot(root);
        assertTrue(solver.betterInterim(healthier, hurt),
                "没有抢錢怪时这一键必须完全隐形，不能改变原有排序");
    }

    @Test
    void sameHpButLessLootOutstandingWins() {
        SearchNode recovered = node(state(42, 85, 0, true));
        SearchNode partially = node(state(42, 85, 5, true));

        BeamSolver solver = new BeamSolver(3, 5, 8, 6);
        assertTrue(solver.betterInterim(recovered, partially), "同血同胜时，追回的越多越好");
    }
}
