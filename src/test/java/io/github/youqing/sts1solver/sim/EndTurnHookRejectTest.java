package io.github.youqing.sts1solver.sim;

import org.junit.jupiter.api.Test;

import java.util.Collections;

import io.github.youqing.sts1solver.search.BeamSolver;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回合初钩子里的 {@code ActionRejected} 不许穿出去。
 *
 * <p>真机 2026-10-30 20:08/20:09 两次「满手牌满能量直接结束回合、白挨一轮打」，
 * 根因就在这条链上：
 * <pre>
 * 乱战（Mayhem）atStartOfTurn → getRandomMonster（没有活怪 → 原版返回 null）
 *   → 我们拿 -1 当目标 → "effect needs a valid monster target"
 *   → ActionRejected 从 CombatFlow.endTurn 穿到后台搜索线程 → 整个搜索崩
 *   → 主线程只看到「空计划」→ 结束回合
 * </pre>
 * 现在两头都堵上：乱战没目标就收手，{@code endTurnOf} 把拒绝降级成「这条线不通」。
 */
class EndTurnHookRejectTest {

    private static SimState fight(int monsterHp) {
        SimState s = new SimState();
        s.hp = 80;
        s.maxHp = 80;
        s.energy = 3;
        SimMonster m = new SimMonster("TestMonster", monsterHp, 30, 0,
                Collections.<String, Integer>emptyMap());
        m.pendingMove = MonsterMove.block(1, 0);
        m.pendingMoveId = 1;
        s.monsters.add(m);
        return s;
    }

    /** 没活怪还留着乱战：以前这里必抛，现在只是「这场结束了，不打了」。 */
    @Test
    void mayhemWithoutLivingMonsterDoesNotThrow() {
        SimState s = fight(0);
        s.playerPowers.put("Mayhem", Integer.valueOf(1));
        s.draw.add(new SimCard("Strike_R", false, 1, 6, "ATTACK"));

        assertDoesNotThrow(() -> CombatFlow.endTurn(s, 3, 5),
                "回合初钩子拒绝不该穿出 endTurn（会把整个后台搜索打死）");
    }

    /** 有活怪时乱战照旧：抽出堆顶那张打出去。 */
    @Test
    void mayhemStillPlaysTopCardAtLivingMonster() {
        SimState s = fight(30);
        s.playerPowers.put("Mayhem", Integer.valueOf(1));
        s.draw.add(new SimCard("Strike_R", false, 1, 6, "ATTACK"));

        CombatFlow.endTurn(s, 3, 5);

        assertTrue(s.monsters.get(0).hp < 30, "乱战应该把堆顶的打击打出去");
        assertTrue(s.draw.isEmpty(), "堆顶那张已经被打掉");
    }

    /** 搜索也不能因为这种局面崩掉（空计划可以，异常不行）。 */
    @Test
    void searchSurvivesTheSameState() {
        SimState s = fight(0);
        s.playerPowers.put("Mayhem", Integer.valueOf(1));
        s.draw.add(new SimCard("Strike_R", false, 1, 6, "ATTACK"));

        assertDoesNotThrow(() -> new BeamSolver(3, 5, 4, 3).bestFirstAction(s));
    }
}
