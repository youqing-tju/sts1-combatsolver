package io.github.youqing.sts1solver.mirror;

import io.github.youqing.sts1solver.sim.SimCard;
import io.github.youqing.sts1solver.sim.SimMonster;
import io.github.youqing.sts1solver.sim.SimState;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 啃食（{@code Feed}）的成长记账。
 *
 * <p>原版证据（javap {@code Feed.use} → {@code FeedAction.update}）：伤害结算后，
 * 只有「目标将死或已死」**且**「{@code !halfDead}」**且**「没有 {@code Minion} 能力」时，
 * 才 `increaseMaxHp(magicNumber, true)`（第二个参数 = 连当前血量一起加）。
 *
 * <p>为什么这两条守卫必须建模：觉醒者一阶段是 {@code halfDead}，地精首领的小地精带 {@code Minion} ——
 * 少了守卫，影子会以为「打死它们能涨最大生命」，于是把「多花两回合去喂」算成收益。
 */
class FeedGrowthTest {

    /** 打死 → 最大生命 +3、当前血量 +3、成长账本 +1（普通 10 点，升级 12/+4）。 */
    @Test
    void fatalKillGrowsMaxHpAndRecordsTheReward() {
        SimState state = combat("Feed", 10);
        CardPlayer.play(state, 0, 0);

        assertTrue(state.monsters.get(0).hp <= 0, "先确认它真的死了，其余断言才有意义");
        assertEquals(83, state.maxHp, "80 + 3");
        assertEquals(83, state.hp, "increaseMaxHp(amount, true) 同时回这 3 点");
        assertEquals(1, state.feedGrowth);
        assertEquals(1, state.growthValues().get(io.github.youqing.sts1solver.search.GrowthValues.Source.FEED));
    }

    /** 升级版是 +4。 */
    @Test
    void upgradedFeedGrowsByFour() {
        SimState state = combat("Feed+", 10);
        CardPlayer.play(state, 0, 0);

        assertEquals(84, state.maxHp);
        assertEquals(1, state.feedGrowth);
    }

    /** 没打死就什么都不记 —— 不许「打了一下就算成长」。 */
    @Test
    void nonFatalHitRecordsNothing() {
        SimState state = combat("Feed", 30);
        CardPlayer.play(state, 0, 0);

        assertEquals(20, state.monsters.get(0).hp);
        assertEquals(80, state.maxHp);
        assertEquals(0, state.feedGrowth);
        assertFalse(state.growthValues().isEnabled());
    }

    /** 召唤物（{@code Minion}）不给成长：原版 `FeedAction` 显式挡掉。 */
    @Test
    void minionsGiveNoGrowth() {
        SimState state = combat("Feed", 10);
        state.monsters.get(0).powers.put("Minion", Integer.valueOf(1));

        CardPlayer.play(state, 0, 0);

        assertTrue(state.monsters.get(0).hp <= 0, "它照样会死");
        assertEquals(80, state.maxHp, "Minion 不给最大生命");
        assertEquals(0, state.feedGrowth);
    }

    /** 半死（觉醒者一阶段）不给成长：原版 `FeedAction` 显式挡掉。 */
    @Test
    void halfDeadEnemiesGiveNoGrowth() {
        SimState state = combat("Feed", 10);
        state.monsters.get(0).powers.put("Half Dead", Integer.valueOf(1));

        CardPlayer.play(state, 0, 0);

        assertEquals(80, state.maxHp);
        assertEquals(0, state.feedGrowth);
    }

    private static SimState combat(String cardKey, int monsterHp) {
        SimState state = new SimState();
        state.hp = 80;
        state.maxHp = 80;
        state.energy = 3;
        boolean upgraded = cardKey.endsWith("+");
        String id = upgraded ? cardKey.substring(0, cardKey.length() - 1) : cardKey;
        state.hand.add(new SimCard(id, upgraded, 1));
        state.monsters.add(new SimMonster("AcidSlime_S", monsterHp, 30, 0,
                new TreeMap<String, Integer>()));
        return state;
    }
}
