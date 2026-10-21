package io.github.youqing.sts1solver.sim;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 意图学习：观测到的回合意图要被记住；没观测到的回合要**老实退回当前值**，不能编。
 */
class IntentLearningTest {

    @BeforeEach
    void reset() {
        IntentLearning.clear();
    }

    @Test
    void remembersObservedIntentPerTurn() {
        IntentLearning.observe("GremlinNob", 1, 8);
        IntentLearning.observe("GremlinNob", 2, 14);

        assertEquals(8, IntentLearning.predict("GremlinNob", 1, 99));
        assertEquals(14, IntentLearning.predict("GremlinNob", 2, 99));
    }

    @Test
    void fallsBackWhenTurnIsUnknown() {
        IntentLearning.observe("GremlinNob", 1, 8);

        assertEquals(42, IntentLearning.predict("GremlinNob", 5, 42),
                "unknown turn must use the fallback, not a guess");
    }

    @Test
    void firstObservationWinsSoNoiseDoesNotOverwrite() {
        IntentLearning.observe("Louse", 1, 6);
        IntentLearning.observe("Louse", 1, 11);

        assertEquals(6, IntentLearning.predict("Louse", 1, 0));
    }

    @Test
    void combatFlowUsesLearnedIntentForNextTurn() {
        IntentLearning.observe("TestMonster", 2, 9);

        SimState s = new SimState();
        s.hp = 50;
        s.maxHp = 80;
        s.energy = 3;
        s.turn = 1;
        SimMonster m = new SimMonster("TestMonster", 40, 40, 0,
                Collections.<String, Integer>emptyMap());
        m.intentDmg = 3;
        s.monsters.add(m);

        CombatFlow.endTurn(s, 3, 5);

        assertEquals(47, s.hp, "turn 1 intent 3 applied");
        assertEquals(2, s.turn);
        assertEquals(9, s.monsters.get(0).intentDmg, "next turn intent comes from the table");
    }
}
