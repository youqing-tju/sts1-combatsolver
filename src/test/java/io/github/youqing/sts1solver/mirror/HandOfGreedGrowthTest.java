package io.github.youqing.sts1solver.mirror;

import io.github.youqing.sts1solver.sim.SimCard;
import io.github.youqing.sts1solver.sim.SimMonster;
import io.github.youqing.sts1solver.sim.SimState;
import org.junit.jupiter.api.Test;

import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 贪婪之手（{@code HandOfGreed}）的金币与长期资源记账。
 *
 * <p>原版证据（javap {@code HandOfGreed.use} → {@code GreedAction.update} → {@code AbstractPlayer.gainGold}）：
 * <ul>
 *   <li>伤害结算后，只有「将死或已死」且 {@code !halfDead} 且没有 {@code Minion} 才 {@code gainGold}；</li>
 *   <li>{@code gainGold} 遇到 {@code Ectoplasm}（魂灵瓶）**直接返回**，一分钱都不给；</li>
 *   <li>{@code gainGold} 对 {@code amount <= 0} 也不加。</li>
 * </ul>
 *
 * <p>蓝本 {@code CorePowerSupport.cs:138-146} 的记账口径：`gainedGold > 0` 才记长期资源，
 * 但**成长次数无条件 +1** —— 因为「这张牌成功打死了目标」这件事本身已经发生。
 */
class HandOfGreedGrowthTest {

    @Test
    void fatalKillPaysGoldAndRecordsBothLedgers() {
        SimState state = combat("HandOfGreed", 12, 80);
        CardPlayer.play(state, 0, 0);

        assertEquals(100, state.gold, "80 + 20");
        assertEquals(20, state.longTermResourceValue, "长期资源只记**实际到手**的金币");
        assertEquals(1, state.handOfGreedGrowth);
    }

    @Test
    void upgradedVersionPaysTwentyFive() {
        SimState state = combat("HandOfGreed+", 12, 80);
        CardPlayer.play(state, 0, 0);

        assertEquals(105, state.gold);
        assertEquals(25, state.longTermResourceValue);
    }

    @Test
    void nonFatalHitPaysNothing() {
        SimState state = combat("HandOfGreed", 40, 80);
        CardPlayer.play(state, 0, 0);

        assertEquals(80, state.gold);
        assertEquals(0, state.longTermResourceValue);
        assertEquals(0, state.handOfGreedGrowth);
    }

    /** 魂灵瓶（{@code Ectoplasm}）：金币被规则截断 → 长期资源记 0，但成长次数照记。 */
    @Test
    void ectoplasmBlocksGoldButStillCountsAsAGrowthEvent() {
        SimState state = combat("HandOfGreed", 12, 80);
        state.relics.put("Ectoplasm", Integer.valueOf(-1));

        CardPlayer.play(state, 0, 0);

        assertEquals(80, state.gold, "有魂灵瓶就一分不给");
        assertEquals(0, state.longTermResourceValue, "长期资源必须记**实际到手**的值");
        assertEquals(1, state.handOfGreedGrowth, "牌确实打死了目标，成长事件仍然发生");
    }

    /** 召唤物不给钱也不给成长。 */
    @Test
    void minionsPayNothing() {
        SimState state = combat("HandOfGreed", 12, 80);
        state.monsters.get(0).powers.put("Minion", Integer.valueOf(1));

        CardPlayer.play(state, 0, 0);

        assertEquals(80, state.gold);
        assertEquals(0, state.handOfGreedGrowth);
    }

    /** 半死（觉醒者一阶段）同样不给。 */
    @Test
    void halfDeadEnemiesPayNothing() {
        SimState state = combat("HandOfGreed", 12, 80);
        state.monsters.get(0).powers.put("Half Dead", Integer.valueOf(1));

        CardPlayer.play(state, 0, 0);

        assertEquals(80, state.gold);
        assertEquals(0, state.handOfGreedGrowth);
    }

    private static SimState combat(String cardKey, int monsterHp, int gold) {
        SimState state = new SimState();
        state.hp = 80;
        state.maxHp = 80;
        state.energy = 3;
        state.gold = gold;
        boolean upgraded = cardKey.endsWith("+");
        String id = upgraded ? cardKey.substring(0, cardKey.length() - 1) : cardKey;
        state.hand.add(new SimCard(id, upgraded, 2));
        state.monsters.add(new SimMonster("AcidSlime_S", monsterHp, 40, 0,
                new TreeMap<String, Integer>()));
        return state;
    }
}
