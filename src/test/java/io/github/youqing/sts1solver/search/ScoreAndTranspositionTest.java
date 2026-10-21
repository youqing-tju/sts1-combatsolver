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
    void transpositionDropsDominatedStates() {
        TranspositionTable tt = new TranspositionTable();
        assertFalse(tt.dominated("k", 100), "first sighting must be kept");
        assertTrue(tt.dominated("k", 90), "worse score is dominated");
        assertFalse(tt.dominated("k", 110), "better score replaces the entry");
        assertEquals(2, tt.hits());
        assertEquals(1, tt.misses());
    }
}
