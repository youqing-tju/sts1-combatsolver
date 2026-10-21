package io.github.youqing.sts1solver.search;

import io.github.youqing.sts1solver.sim.SimMonster;
import io.github.youqing.sts1solver.sim.SimState;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScoreAndTranspositionTest {

    private static SimState state(int enemyHp) {
        SimState s = new SimState();
        s.hp = 50;
        s.maxHp = 80;
        s.energy = 2;
        s.block = 7;
        if (enemyHp > 0) {
            s.monsters.add(new SimMonster("T", enemyHp, enemyHp, 0,
                    Collections.<String, Integer>emptyMap()));
        }
        return s;
    }

    @Test
    void blockOnlyCountsWhileEnemiesRemain() {
        long withEnemy = Score.of(state(10)).total();
        long cleared = Score.of(state(10)).total();
        SimState dead = state(0);
        long withoutEnemy = Score.of(dead).total();

        assertTrue(cleared == withEnemy);
        assertTrue(withoutEnemy > withEnemy, "killing outweighs any defensive bonus");
    }

    @Test
    void scoreIsExplainablePerDimension() {
        String text = Score.of(state(10)).explain();
        assertTrue(text.contains("hp=50"), text);
        assertTrue(text.contains("enemyHp=10"), text);
        assertTrue(text.contains("block=7"), text);
        assertTrue(text.contains("energy=2"), text);
    }

    @Test
    void projectionPenalisesWalkingIntoALethalHit() {
        SimState safe = state(10);
        safe.hp = 5;
        safe.monsters.get(0).intentDmg = 0;

        SimState lethal = state(10);
        lethal.hp = 5;
        lethal.block = 0;          // 辅助方法默认给了 7 点格挡，这里显式清零
        lethal.monsters.get(0).intentDmg = 10;

        assertEquals(-5, Score.of(lethal).projectedHp);
        assertTrue(Score.of(safe).total() > Score.of(lethal).total(),
                "walking into a lethal hit must score lower than taking none");
    }

    @Test
    void projectionCountsBlockAsAbsorption() {
        SimState blocked = state(10);
        blocked.hp = 5;
        blocked.block = 5;
        blocked.monsters.get(0).intentDmg = 10;

        assertEquals(0, Score.of(blocked).projectedHp, "5 block absorbs 5 of the 10");
    }

    @Test
    void transpositionDropsDominatedStates() {
        TranspositionTable tt = new TranspositionTable();
        assertFalse(tt.dominated("k", 100), "first sighting must be kept");
        assertTrue(tt.dominated("k", 90), "worse score is dominated");
        assertFalse(tt.dominated("k", 110), "better score replaces the entry");
        assertEquals(2, tt.hits());
        assertEquals(1, tt.misses());
    }
}
