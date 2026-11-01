package io.github.youqing.sts1solver.sim;

import io.github.youqing.sts1solver.mirror.DamageCalc;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 抢錢怪（Looter / Mugger）的金币：**被偷 → 还攥在它手里 → 死了才算找回**。
 *
 * <p>原版行为逐条 javap 核实：
 * <ul>
 *   <li>{@code Looter$1.update} / {@code Mugger$1}：每次 Mug/Lunge/BigSwipe
 *       {@code stolenGold += min(goldAmt, player.gold)}；</li>
 *   <li>{@code DamageAction.stealGold}：偷钱发生在**伤害结算之前**，格挡挡不住；</li>
 *   <li>{@code Looter.die} / {@code Mugger.die}：{@code addStolenGoldToRewards(stolenGold)}
 *       —— 打死它，这笔钱回到奖励里；</li>
 *   <li>烟雾弹逃走（{@code EscapeAction}）**不还钱**。</li>
 * </ul>
 *
 * <p>影子只需要一个「还被扣着多少」的数：死了归零、跑了留着。搜索据此判断
 * 「值不值得多掉几点血把它留下」。
 */
class TheftTest {

    private static SimState fight(String monsterType, int monsterHp) {
        SimState s = new SimState();
        s.turn = 1;
        s.hp = 50;
        s.maxHp = 80;
        s.energy = 3;
        s.energyPerTurn = 3;
        s.gold = 100;
        s.hand.add(new SimCard("Strike_R", false, 1, 6, "ATTACK"));
        for (int i = 0; i < 8; i++) {
            s.draw.add(new SimCard("Strike_R", false, 1, 6, "ATTACK"));
        }
        SimMonster m = new SimMonster(monsterType, monsterHp, monsterHp, 0,
                Collections.<String, Integer>emptyMap());
        s.monsters.add(m);
        return s;
    }

    // ------------------------------------------------------------------ 偷钱

    @Test
    void mugTakesGoldFromThePurseAndHoldsItOnTheThief() {
        SimState s = fight("Looter", 44);
        SimMonster looter = s.monsters.get(0);
        looter.pendingMove = MonsterMove.attack(1, 10);      // MUG
        looter.pendingMoveId = 1;

        CombatFlow.endTurn(s, 3, 5);

        assertEquals(85, s.gold, "掠夺者一抓偷 15 金币（goldAmt，A17+ 为 20）");
        assertEquals(15, looter.stolenGold, "偷到的钱先攥在它手里");
        assertEquals(15, s.outstandingStolenGold(), "还没找回，就是「还被扣着」");
    }

    @Test
    void stealingIsCappedByWhatThePlayerActuallyHas() {
        SimState s = fight("Looter", 44);
        s.gold = 5;
        SimMonster looter = s.monsters.get(0);
        looter.pendingMove = MonsterMove.attack(1, 10);
        looter.pendingMoveId = 1;

        CombatFlow.endTurn(s, 3, 5);

        assertEquals(0, s.gold, "身上只有 5 块就只偷 5 块（原版 stealGold 先夹再扣）");
        assertEquals(5, looter.stolenGold);
    }

    @Test
    void blockingTheMugStillLosesTheGold() {
        SimState s = fight("Looter", 44);
        s.block = 30;                                        // 伤害全挡住
        SimMonster looter = s.monsters.get(0);
        looter.pendingMove = MonsterMove.attack(1, 10);
        looter.pendingMoveId = 1;

        CombatFlow.endTurn(s, 3, 5);

        assertEquals(50, s.hp, "格挡吃掉伤害");
        assertEquals(85, s.gold, "但钱照样被偷 —— 原版偷钱在伤害结算之前");
    }

    // ------------------------------------------------------------------ 找回/损失

    @Test
    void killingTheThiefRecoversTheGold() {
        SimState s = fight("Looter", 44);
        SimMonster looter = s.monsters.get(0);
        looter.pendingMove = MonsterMove.attack(1, 10);
        looter.pendingMoveId = 1;
        CombatFlow.endTurn(s, 3, 5);
        assertEquals(15, s.outstandingStolenGold());

        DamageCalc.hitAndReturnDealt(s, looter, 999);

        assertEquals(0, looter.hp);
        assertEquals(0, looter.stolenGold, "打死它就等于把这笔钱拿回来了");
        assertEquals(0, s.outstandingStolenGold());
        assertEquals(85, s.gold, "钱回到的是战斗奖励，不是当场塞回钱包（原版 addStolenGoldToRewards）");
    }

    @Test
    void escapingThiefKeepsTheGoldLost() {
        SimState s = fight("Looter", 44);
        SimMonster looter = s.monsters.get(0);
        looter.stolenGold = 15;                              // 已经偷到手
        looter.pendingMove = MonsterMove.escape(3);

        CombatFlow.endTurn(s, 3, 5);

        assertEquals(0, looter.hp, "逃跑 = 从场上消失（沿用既有口径）");
        assertEquals(15, s.outstandingStolenGold(),
                "跑了就不还钱：这笔仍算「被扣着」，否则搜索会以为它白跑了");
    }

    @Test
    void noThiefMeansNothingOutstanding() {
        SimState s = fight("JawWorm", 40);
        assertEquals(0, s.outstandingStolenGold());
        assertTrue(s.gold == 100);
    }
}
