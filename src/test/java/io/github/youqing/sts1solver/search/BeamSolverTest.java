package io.github.youqing.sts1solver.search;

import io.github.youqing.sts1solver.sim.SimCard;
import io.github.youqing.sts1solver.sim.SimMonster;
import io.github.youqing.sts1solver.sim.SimState;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 搜索层的验收：**该防的时候防，该杀的时候杀**。
 *
 * <p>这两条如果不过，说明估值或回合模型有问题——那 AUTO 换用搜索也没有意义。
 */
class BeamSolverTest {

    private static SimState state(int monsterHp, int intentDmg, String... hand) {
        SimState s = new SimState();
        s.hp = 30;
        s.maxHp = 80;
        s.energy = 3;
        for (String id : hand) {
            s.hand.add(new SimCard(id, false, 1));
        }
        SimMonster m = new SimMonster("TestMonster", monsterHp, monsterHp, 0,
                Collections.<String, Integer>emptyMap());
        m.intentDmg = intentDmg;
        s.monsters.add(m);
        // 抽牌堆给几张垫底牌，避免回合开始无牌可抽
        for (int i = 0; i < 8; i++) {
            s.draw.add(new SimCard("Strike_R", false, 1));
        }
        return s;
    }

    /**
     * 没有压力时应该先铺成长，而不是无脑打一张攻击。
     * 这条是「能力牌不再被估值惩罚」的行为验证 —— 之前它必定选攻击。
     */
    @Test
    void playsAScalingPowerWhenThereIsNoPressure() {
        SimState s = state(60, 0, "Demon Form", "Strike_R");
        s.energy = 3;

        PlanAction action = new BeamSolver(3, 5, 6, 4).bestFirstAction(s);

        assertNotNull(action);
        assertEquals(0, action.cardIndex, "空场无压力时应先开恶魔形态");
    }

    /**
     * 攻击会送命、防御能活下来 —— 必须防。
     *
     * <p>判据落在蓝本 `FinalPlanOrdering` 的 `Survives` 键上：
     * {@code !PlayerDead && ProjectedPlayerHp > 0}，也就是**投影血量**，不是当前血量。
     *
     * <p>⚠️ 2026-10-23 调过这组数值，两次都踩到坑：
     * <ol>
     *   <li>原来的「意图 20、血 10、格挡 5」其实**两条路都会死**（挡完还挨 15），
     *       旧实现靠基础分里的死亡连续化选了防御；蓝本的最终排序里没有这一项。</li>
     *   <li>改成「意图 13」后，防御这条**拖到第二回合才死、总共挨了 21 点**，
     *       而攻击这条第一回合就死、只挨 13 点 —— `StrategicHpDeficit` 记的是累计掉血，
     *       于是「早死」反而分更低。这是蓝本的口径（死路之间不比优劣），不是 bug。</li>
     * </ol>
     * 所以最终把怪物血量降到 8：防御线第二回合就能打死对面（真赢），攻击线当场送命。
     * 这样断言的是「赢 &gt; 输」，而不是两个死法谁好看。
     */
    @Test
    void blocksWhenAttackingWouldGetUsKilled() {
        // 手牌：[Strike_R, Defend_R]；怪 8 血、意图 13，我 10 血、能量只够做一件事
        SimState s = state(8, 13, "Strike_R", "Defend_R");
        s.hp = 10;
        s.energy = 1;
        PlanAction action = new BeamSolver(3, 5, 8, 5).bestFirstAction(s);

        assertNotNull(action);
        assertEquals(PlanAction.Kind.PLAY_CARD, action.kind);
        assertEquals(1, action.cardIndex, "should play Defend_R (index 1) instead of Strike_R");
    }

    /**
     * 真机故障回归（2026-10-23）：手里有 0 费的 `Body Slam+` 和 1 点能量，AI 却直接结束回合。
     *
     * <p>根因不是估值 —— 是 `MirrorRegistry` 少了升级态 `Body Slam+`，
     * 查不到就 `ActionRejected`，搜索分支被静默跳过，那张牌在 AI 眼里不存在。
     * 日志原话：`AUTO end turn (search says end) energy=1 hand[Body Slam+(cost=0,turn=0,atk), ...]`
     *
     * <p>这条测试钉住两件事：① 升级态必须能查表（{@code MirrorCoverageTest} 兜全局）；
     * ② 打得出就必须打 —— 0 费、伤害等于格挡，没有理由留着。
     */
    @Test
    void playsTheZeroCostUpgradedBodySlamInsteadOfEndingTheTurn() {
        SimState s = new SimState();
        s.hp = 30;
        s.maxHp = 80;
        s.energy = 1;
        s.block = 10;       // Body Slam 的伤害 = 当前格挡
        s.hand.add(new SimCard("Body Slam", true, 0, 0, "ATTACK"));   // key = "Body Slam+"
        s.hand.add(new SimCard("Defend_R", true, 1, 0, "SKILL"));
        for (int i = 0; i < 8; i++) {
            s.draw.add(new SimCard("Strike_R", false, 1));
        }
        SimMonster m = new SimMonster("TestMonster", 40, 40, 0,
                Collections.<String, Integer>emptyMap());
        m.intentDmg = 10;
        s.monsters.add(m);

        BeamSolver solver = new BeamSolver(3, 5, 8, 5);
        PlanAction action = solver.bestFirstAction(s);

        assertNotNull(action);
        assertTrue(io.github.youqing.sts1solver.mirror.MirrorRegistry.supports("Body Slam+"),
                "升级态必须有镜像，否则搜索看不见这张牌");
        assertEquals(PlanAction.Kind.PLAY_CARD, action.kind, "不该直接结束回合");

        // 顺序不钉死：最优解是「先 Defend 加格挡、再用 0 费 Body Slam 打 18 点」，
        // 所以第一步是 Defend 也算对。钉住的是「本回合的计划里必须用上这张 0 费牌」。
        boolean plannedBodySlam = false;
        for (RouteStep step : solver.lastSteps) {
            if (step.endTurn) {
                break;                      // 只看本回合
            }
            if ("Body Slam+".equals(step.cardKey)) {
                plannedBodySlam = true;
            }
        }
        assertTrue(plannedBodySlam, "本回合的计划里必须用上 0 费的 Body Slam+，实际路线："
                + solver.lastSteps);
    }

    @Test
    void terminalValuePrefersTheStateWhereCombatIsOver() {
        BeamSolver solver = new BeamSolver(3, 5, 4, 3);
        SimState alive = state(40, 8, "Strike_R");
        SimState cleared = state(0, 0, "Strike_R");
        cleared.monsters.clear();

        long valueAlive = solver.terminalValue(alive);
        long valueCleared = solver.terminalValue(cleared);

        assertTrue(valueCleared > valueAlive,
                "a finished fight must always outweigh an ongoing one: "
                        + valueCleared + " vs " + valueAlive);
    }

    @Test
    void picksTheTargetItCanActuallyKill() {
        // 两个怪：[2 血, 40 血]；先杀残血的，场上敌人少一个
        SimState s = state(40, 5, "Strike_R");
        s.monsters.clear();
        SimMonster weak = new SimMonster("Weak", 2, 2, 0,
                Collections.<String, Integer>emptyMap());
        weak.intentDmg = 5;
        SimMonster tank = new SimMonster("Tank", 40, 40, 0,
                Collections.<String, Integer>emptyMap());
        tank.intentDmg = 5;
        s.monsters.add(weak);
        s.monsters.add(tank);

        PlanAction action = new BeamSolver(3, 5, 8, 5).bestFirstAction(s);

        assertNotNull(action);
        assertEquals(PlanAction.Kind.PLAY_CARD, action.kind);
        assertEquals(0, action.targetIndex, "should finish the 2 HP monster first");
    }

    @Test
    void killsInsteadOfBlockingWhenLethalIsAvailable() {
        // 怪只剩 5 血，Strike 6 伤可直接击杀
        SimState s = state(5, 20, "Strike_R", "Defend_R");
        PlanAction action = new BeamSolver(3, 5, 8, 5).bestFirstAction(s);

        assertNotNull(action);
        assertEquals(PlanAction.Kind.PLAY_CARD, action.kind);
        assertEquals(0, action.cardIndex, "should take the kill with Strike_R");
    }
}
