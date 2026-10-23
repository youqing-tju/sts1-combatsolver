package io.github.youqing.sts1solver.search;

import io.github.youqing.sts1solver.sim.SimCard;
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

    /**
     * 之前「打能力牌 = 少能量少手牌 → 分数下降」，AI 几乎不会主动打成长牌。
     * 现在成长性能力按层计分，必须压过一张普通攻击带来的敌方掉血。
     */
    @Test
    void scalingPowersAreWorthMoreThanOneAttack() {
        SimState plain = state(40);
        SimState powered = state(40);
        powered.playerPowers.put("Demon Form", 2);

        assertTrue(Score.of(powered).total() > Score.of(plain).total(),
                "恶魔形态 2 层应该让局面更值钱");
        assertEquals(2, Score.of(powered).scalingPowers);
    }

    @Test
    void strengthAndEnemyDebuffsCount() {
        SimState base = state(40);
        long plain = Score.of(base).total();

        SimState strong = state(40);
        strong.playerPowers.put("Strength", 3);
        assertTrue(Score.of(strong).total() > plain, "力量应该加分");

        // 易伤是「攻击倍率」，价值按吃得到它的攻击量算 —— 牌堆里得有攻击牌才有意义
        SimState debuffed = state(40);
        debuffed.monsters.get(0).powers.put("Vulnerable", 2);
        debuffed.hand.add(new SimCard("Strike_R", false, 1, 6, "ATTACK"));
        debuffed.hand.add(new SimCard("Strike_R", false, 1, 6, "ATTACK"));
        assertTrue(Score.of(debuffed).total() > plain, "敌人易伤应该加分");

        SimState weakened = state(40);
        weakened.monsters.get(0).powers.put("Weakened", 2);
        assertTrue(Score.of(weakened).total() > plain, "敌人虚弱应该加分");
    }

    /**
     * 蓝本最重要的那条汇率：{@code Hp = 30_000}、{@code EnemyHp = -10_000}，
     * 即**保住 1 点血 ≈ 造成 3 点伤害**。
     *
     * <p>旧实现是「1 血 = 1000 点敌方血」（相差 333 倍），AI 才会放着 120 血的怪磨 20 回合。
     */
    @Test
    void onePlayerHpIsWorthAboutThreeEnemyHp() {
        SimState threeLess = state(30);
        SimState oneHpLess = state(33);
        oneHpLess.hp = 51;

        assertEquals(Score.of(threeLess).baseTotal(), Score.of(oneHpLess).baseTotal(),
                "3 点敌方血应该正好等于 1 点自己血（30,000 : 10,000）");
        assertTrue(Score.of(threeLess).baseTotal() > Score.of(state(33)).baseTotal(),
                "同样血量下，敌方血越少越好");
    }

    /** 胜负必须压过任何战斗内收益：赢 1 血也不该输给「活着但满血没打死」。 */
    @Test
    void victoryDominatesEveryCombatTrade() {
        SimState winAtOneHp = state(0);
        winAtOneHp.hp = 1;

        SimState aliveFullHp = state(999);
        aliveFullHp.hp = 999;
        aliveFullHp.maxHp = 999;

        assertTrue(Score.of(winAtOneHp).baseTotal() > Score.of(aliveFullHp).baseTotal(),
                "赢了就是赢了");
    }

    /** 死亡必须输给任何活着的局面，且「死得浅」要好于「死得深」（连续化，不靠随机决胜）。 */
    @Test
    void deathLosesAndIsOrderedByDepth() {
        SimState shallow = state(10);
        shallow.hp = -1;
        SimState deep = state(10);
        deep.hp = -20;

        assertTrue(Score.of(shallow).baseTotal() > Score.of(deep).baseTotal(),
                "少亏一点也是好一点");
        assertTrue(Score.of(shallow).baseTotal() < Score.of(state(10)).baseTotal(),
                "死了一切都白搭");
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
    void excessBlockIsNotRewarded() {
        SimState enough = state(10);
        enough.block = 5;
        enough.monsters.get(0).intentDmg = 5;

        SimState overkill = state(10);
        overkill.block = 20;          // 远超 5 点威胁，多余格挡毫无价值
        overkill.monsters.get(0).intentDmg = 5;

        assertEquals(Score.of(enough).total(), Score.of(overkill).total(),
                "block beyond the incoming damage must not add score");
    }

    /**
     * 集火：同样的伤害量，**打死一个**比「两个都残血」强 —— 因为少了一个敌人的意图。
     *
     * <p>这个性质不靠专门加分项，走的是 {@code projectedHp}：敌人少了，本回合挨的就少了。
     */
    @Test
    void prefersKillingAnEnemyOverSpreading() {
        SimState killed = state(10);
        killed.block = 0;
        killed.monsters.clear();
        killed.monsters.add(new SimMonster("A", 0, 10, 0, Collections.<String, Integer>emptyMap()));
        SimMonster survivor = new SimMonster("B", 14, 14, 0,
                Collections.<String, Integer>emptyMap());
        survivor.intentDmg = 5;
        killed.monsters.add(survivor);

        SimState spread = state(10);
        spread.block = 0;
        spread.monsters.clear();
        for (String name : new String[]{"A", "B"}) {
            SimMonster m = new SimMonster(name, 7, 10, 0,
                    Collections.<String, Integer>emptyMap());
            m.intentDmg = 5;
            spread.monsters.add(m);
        }

        assertEquals(Score.of(killed).enemyHp, Score.of(spread).enemyHp, "总血量相同");
        assertTrue(Score.of(killed).baseTotal() > Score.of(spread).baseTotal(),
                "打死一个 = 少一个意图，必须更优");
    }

    /**
     * 蓝本 {@code LatentSetupValue}：手里捏着还没打的能力牌算「延迟收益」，
     * 但**只在多敌人（或 Boss）时启用** —— 单敌人小怪不值得为长期成长拖延。
     */
    @Test
    void latentPowerCardsCountOnlyInMultiEnemyFights() {
        SimState one = state(40);
        one.hand.add(new SimCard("Demon Form", false, 3, 0, "POWER"));
        SimState onePlain = state(40);
        assertEquals(Score.of(onePlain).total(), Score.of(one).total(),
                "单敌人时不吃延迟收益");

        SimState two = state(40);
        two.monsters.add(new SimMonster("U", 40, 40, 0, Collections.<String, Integer>emptyMap()));
        two.hand.add(new SimCard("Demon Form", false, 3, 0, "POWER"));
        SimState twoPlain = state(40);
        twoPlain.monsters.add(new SimMonster("U", 40, 40, 0,
                Collections.<String, Integer>emptyMap()));
        assertTrue(Score.of(two).total() > Score.of(twoPlain).total(),
                "多敌人时手里的能力牌应该算分");
    }

    /**
     * 蓝本 {@code RetainedAttackGrowth}：牌堆里的攻击总量比开局更多就加分。
     *
     * <p>作用不是「打得疼」，而是**别把可复用的攻击牌烧掉换一点即时伤害**：
     * 消耗一张好攻击 = 攻击总量下降 = 掉分。
     */
    @Test
    void growingAttackValueInTheDeckIsWorthSomething() {
        SimState rootState = state(40);
        rootState.hand.add(new SimCard("Strike_R", false, 1, 6, "ATTACK"));
        Score.Root root = new Score.Root(rootState);        // 开局攻击总量 = 6

        SimState kept = state(40);
        kept.hand.add(new SimCard("Strike_R", false, 1, 6, "ATTACK"));

        SimState grown = state(40);
        grown.hand.add(new SimCard("Searing Blow", false, 2, 20, "ATTACK"));

        assertTrue(Score.of(grown, root, 0).total() > Score.of(kept, root, 0).total(),
                "牌堆里的攻击总量比开局高应该加分（暴走/愤怒/武装）");

        // 蓝本用 max(0, 当前 - 开局)：**只奖励成长，不惩罚损失**。
        SimState burnt = state(40);                         // 攻击牌被消耗光了
        assertEquals(Score.of(kept, root, 0).total(), Score.of(burnt, root, 0).total(),
                "消耗掉攻击牌只掉成长分，不该额外倒扣");
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
