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
            // 类型与面板伤害都填上：真实捕获里它们是有值的，缺了会让「攻击牌投影」算成 0
            boolean attack = id.startsWith("Strike") || id.startsWith("Twin") || id.startsWith("Bash")
                    || id.startsWith("Pommel") || id.startsWith("Cleave") || id.startsWith("Anger")
                    || id.startsWith("Sword");
            s.hand.add(new SimCard(id, false, 1, attack ? 6 : -1, attack ? "ATTACK" : "SKILL"));
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

    // ------------------------------------------ 战略血量赤字：战后回血 / 死亡豁免

    private static SearchNode nodeOf(SimState s) {
        return new SearchNode(s, PlanAction.endTurn(), null, 1, 0L);
    }

    /** 打赢的路线，战后遗物回的血要从赤字里减掉；没打赢就一分不给。 */
    @Test
    void postCombatHealReducesTheStrategicDeficitOnlyOnVictory() {
        SimState root = state(40, 0, "Strike_R");
        root.hp = 80;
        root.maxHp = 80;
        root.relics.put(io.github.youqing.sts1solver.mirror.PostCombatRelicHeal.BURNING_BLOOD, -1);

        SimState won = root.copy();
        won.monsters.get(0).hp = 0;
        won.hp = 74;                       // 掉了 6 血

        SimState lost = root.copy();       // 同样剩 74 血，但怪还活着
        lost.hp = 74;

        BeamSolver solver = new BeamSolver(3, 5, 8, 5);
        solver.bindRoot(root);

        // 打赢 → 燃烧之血会补 6，净掉血全被抵消 → 赤字 0
        assertEquals(0, solver.strategicHpDeficit(nodeOf(won)), "打赢：回血抵消掉 6 点掉血");
        // 没打赢 → 一分不给 → 赤字仍是 6
        assertEquals(6, solver.strategicHpDeficit(nodeOf(lost)), "没打赢：不给回血");
    }

    /** 带骨肉的阈值回血**不能**进赤字 —— 它会破坏「结束血更多永不更差」的不变式。 */
    @Test
    void meatOnTheBoneThresholdHealIsExcludedFromTheDeficit() {
        SimState root = state(40, 0, "Strike_R");
        root.hp = 80;
        root.maxHp = 80;
        root.relics.put(io.github.youqing.sts1solver.mirror.PostCombatRelicHeal.MEAT_ON_THE_BONE, -1);

        SimState won = root.copy();
        won.monsters.get(0).hp = 0;
        won.hp = 40;                       // 卡在阈值上，原版会触发 +12

        BeamSolver solver = new BeamSolver(3, 5, 8, 5);
        solver.bindRoot(root);

        assertEquals(40, solver.strategicHpDeficit(nodeOf(won)),
                "阈值回血不进赤字，40 点掉血就是 40");
    }

    /** 死亡豁免（蜥蜴尾巴）的溢价要在赤字里额外收一笔 —— 10 倍回血量。 */
    @Test
    void deathSaveSpikesTheStrategicDeficit() {
        SimState root = state(40, 0, "Strike_R");
        root.hp = 80;
        root.maxHp = 80;

        SimState revived = root.copy();
        revived.monsters.get(0).hp = 0;
        revived.hp = 60;
        revived.deathSaveUsed = true;
        revived.deathSaveHpRestored = 40;      // 复活回了 40 血

        BeamSolver solver = new BeamSolver(3, 5, 8, 5);
        solver.bindRoot(root);

        // 净掉血 20 + deathSaveBeamCost(40) = 400 → 420
        assertEquals(20 + 400, solver.strategicHpDeficit(nodeOf(revived)),
                "复活回的血不算挣的，还要连本带溢价扣回去");
    }
}
