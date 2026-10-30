package io.github.youqing.sts1solver.mirror;

import org.junit.jupiter.api.Test;

import java.util.Collections;

import io.github.youqing.sts1solver.sim.CombatFlow;
import io.github.youqing.sts1solver.sim.MonsterMove;
import io.github.youqing.sts1solver.sim.SimCard;
import io.github.youqing.sts1solver.sim.SimMonster;
import io.github.youqing.sts1solver.sim.SimState;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真机对拍里反复出现、每回合差一整手的两个世界模型错误。
 *
 * <p>两个都是拿字节码核过的：
 * <ul>
 *   <li>{@code DrawCardAction.update()} 开头 {@code if (player.hasPower("No Draw")) return;}
 *       —— 战斗专注之后本回合一张都不能再抽；</li>
 *   <li>{@code AbstractCard.canUpgrade()} 对 {@code CURSE} / {@code STATUS} 直接返回 false
 *       —— 军备+ 不会把「黏液」升成 Slimed+。</li>
 * </ul>
 * 少了这两条，影子每回合手牌张数都对不上，搜索算出来的「最优路线」建立在一个假世界上。
 */
class WorldModelDriftTest {

    private static SimState fight() {
        SimState s = new SimState();
        s.hp = 80;
        s.maxHp = 80;
        s.energy = 3;
        SimMonster m = new SimMonster("TestMonster", 200, 200, 0,
                Collections.<String, Integer>emptyMap());
        m.pendingMove = MonsterMove.block(1, 0);
        m.pendingMoveId = 1;
        s.monsters.add(m);
        for (int i = 0; i < 10; i++) {
            s.draw.add(new SimCard("Strike_R", false, 1, 6, "ATTACK"));
        }
        return s;
    }

    /** 战斗专注之后抽牌不该生效（amount 恒为 -1，判据必须是 containsKey）。 */
    @Test
    void noDrawBlocksEveryDrawThisTurn() {
        SimState s = fight();
        s.playerPowers.put("No Draw", Integer.valueOf(-1));

        int before = s.hand.size();
        CombatFlow.drawCards(s, 3);

        assertEquals(before, s.hand.size(), "No Draw 在本回合内必须完全封住抽牌");
    }

    /** 回合翻页后 No Draw 要自动消失，否则下一回合永远抽不到牌。 */
    @Test
    void noDrawExpiresAtEndOfTurn() {
        SimState s = fight();
        s.playerPowers.put("No Draw", Integer.valueOf(-1));
        s.hand.add(new SimCard("Defend_R", false, 1));

        CombatFlow.endTurn(s, 3, 5);

        assertFalse(s.playerPowers.containsKey("No Draw"), "回合结束应当移除 No Draw");
        assertEquals(5, s.hand.size(), "下一回合正常抽满 5 张");
    }

    /** 军备+：诅咒 / 状态牌升不了（原版 canUpgrade 的硬判据）。 */
    @Test
    void armamentsDoesNotUpgradeStatusOrCurse() {
        SimState s = fight();
        s.hand.clear();
        s.hand.add(new SimCard("Slimed", false, 1, -1, "STATUS"));
        s.hand.add(new SimCard("Pain", false, -2, -1, "CURSE"));
        s.hand.add(new SimCard("Strike_R", false, 1, 6, "ATTACK"));

        Effects.upgradeAllHand().applyTo(s, -1, null);

        assertFalse(s.hand.get(0).upgraded, "状态牌不能升级");
        assertFalse(s.hand.get(1).upgraded, "诅咒牌不能升级");
        assertTrue(s.hand.get(2).upgraded, "普通牌照常升级");
    }
}
