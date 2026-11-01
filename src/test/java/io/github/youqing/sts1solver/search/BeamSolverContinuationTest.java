package io.github.youqing.sts1solver.search;

import io.github.youqing.sts1solver.mirror.CardPlayer;
import io.github.youqing.sts1solver.mirror.Potions;
import io.github.youqing.sts1solver.sim.CombatFlow;
import io.github.youqing.sts1solver.sim.SimCard;
import io.github.youqing.sts1solver.sim.SimMonster;
import io.github.youqing.sts1solver.sim.SimState;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 最终 `selected` 与它带出来的 continuation 必须来自**同一条路线**。
 *
 * <p>这条测试存在的理由只有一个：药水闸和 0 费补搜都会**换掉**主搜索的答案。
 * 如果 continuation 是从「闸门之前 / 补搜之前」的候选上摘的，执行层就会捧着一条
 * 搜索并不推荐的世界线往下走 —— 症状是「每回合都命中，但打出来的和面板上的不是一回事」。
 *
 * <p>判定手法是**重放**：按 {@code lastSteps} 在纯数据上一步步走，每到一个回合边界
 * 就拿当时的真实状态去 {@code lookup}，必须精确命中。只要 continuation 是从别的路线来的，
 * 血量/能量/RNG 必然对不上。
 */
class BeamSolverContinuationTest {

    private static final int ENERGY_PER_TURN = 3;
    private static final int HAND_SIZE = 5;

    /** 一条能打三四个回合的普通战斗：怪 46 血、不还手，手上/牌堆都是打击。 */
    private static SimState slowFight() {
        SimState s = new SimState();
        s.turn = 1;
        s.hp = 30;
        s.maxHp = 80;
        s.energy = 1;
        s.energyPerTurn = ENERGY_PER_TURN;
        s.hand.add(new SimCard("Strike_R", false, 1, 6, "ATTACK"));
        for (int i = 0; i < 8; i++) {
            s.draw.add(new SimCard("Strike_R", false, 1, 6, "ATTACK"));
        }
        SimMonster m = new SimMonster("TestMonster", 46, 46, 0,
                Collections.<String, Integer>emptyMap());
        m.intentDmg = 0;
        s.monsters.add(m);
        return s;
    }

    // ------------------------------------------------------------------ replay

    private static void apply(SimState s, RouteStep step) {
        if (step.endTurn) {
            CombatFlow.endTurn(s, ENERGY_PER_TURN, HAND_SIZE);
            return;
        }
        if (step.potion) {
            assertTrue(Potions.use(s, step.cardKey, step.targetIndex, step.choiceIndex),
                    "重放时药水必须能用：" + step);
            return;
        }
        int index = -1;
        for (int i = 0; i < s.hand.size(); i++) {
            if (s.hand.get(i).key().equals(step.cardKey)) {
                index = i;
                break;
            }
        }
        assertTrue(index >= 0, "重放时这张牌必须在手上：" + step);
        CardPlayer.play(s, index, step.targetIndex, step.choiceIndex);
    }

    private static boolean anyAlive(SimState s) {
        for (SimMonster m : s.monsters) {
            if (m.hp > 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * 按 {@code lastSteps} 重放，逐个回合边界核对 continuation。
     *
     * @return 命中过几个边界（0 = 这条路线根本跨不过回合，测试夹具选错了）
     */
    private static int assertContinuationsFollowPublishedSteps(SimState root, BeamSolver solver) {
        SimState walk = root.copy();
        int hits = 0;
        for (int i = 0; i < solver.lastSteps.size(); i++) {
            RouteStep step = solver.lastSteps.get(i);
            apply(walk, step);
            boolean boundary = step.endTurn && i + 1 < solver.lastSteps.size()
                    && walk.hp > 0 && anyAlive(walk);
            if (!boundary) {
                continue;
            }
            ContinuationPlan.Lookup hit = solver.lastContinuations.lookup(
                    walk.turn, ContinuationStamp.capturePredicted(walk));
            assertEquals(ContinuationPlan.Lookup.Status.HIT, hit.status(),
                    "T" + walk.turn + " 的边界必须与重放出来的真实状态逐字段相等，实际：" + hit
                            + "，steps=" + solver.lastSteps);
            hits++;
        }
        return hits;
    }

    // ------------------------------------------------------------------- tests

    @Test
    void resultCarriesContinuationsFromFinalSelectedRoute() {
        SimState root = slowFight();
        BeamSolver solver = new BeamSolver(ENERGY_PER_TURN, HAND_SIZE, 8, 6);
        assertNotNull(solver.bestFirstAction(root.copy()));

        assertFalse(solver.lastContinuations.isEmpty(),
                "这场要打四个回合，必须留下回合边界，实际 steps=" + solver.lastSteps);
        for (CachedContinuation cached : solver.lastContinuations.continuations()) {
            assertTrue(cached.startTurnNumber > root.turn, "边界必须在根回合之后");
            assertTrue(cached.forecastOffset < solver.lastSteps.size(),
                    "forecastOffset 必须落在步骤范围内，实际 " + cached.forecastOffset
                            + " / " + solver.lastSteps.size());
            assertEquals(PlanAction.Kind.END_TURN,
                    actionAt(solver.lastRoute, cached.forecastOffset - 1).kind,
                    "边界的上一步必须是 END_TURN");
        }
        assertTrue(assertContinuationsFollowPublishedSteps(root, solver) > 0);
    }

    /**
     * 新颖度通道不许把**资格链否掉的**喝药路线捞回来。
     *
     * <p>旧版靠「闸门拒绝」这个中间状态来断言；现在资格过滤内建在
     * {@code FinalPlanOrdering.select} 里，闸门不再替换答案。改为直接验结果：
     * 把档位设成 {@code Disabled} 之后，任何通道交出的路线都不许喝药，
     * 且 continuation 仍然来自那条被交出去的路线。
     */
    @Test
    void noveltyCannotLeaveContinuationFromRejectedCandidate() {
        SimState root = slowFight();
        root.potions.add("Fire Potion");
        root.potions.add(null);

        BeamSolver solver = new BeamSolver(ENERGY_PER_TURN, HAND_SIZE, 8, 6).withNovelty()
                .withPotionStrategy(PotionStrategySnapshot.defaults()
                        .withGlobalPolicy(SolverPotionPolicy.DISABLED));
        assertNotNull(solver.bestFirstAction(root.copy()));

        for (RouteStep step : solver.lastSteps) {
            assertFalse(step.potion, "Disabled 下不许出现喝药：" + solver.lastSteps);
        }
        assertTrue(assertContinuationsFollowPublishedSteps(root, solver) > 0,
                "最终路线仍要带 continuation，且必须来自这条被交出去的路线");
    }

    @Test
    void potionGateReplacementChangesContinuationSource() {
        SimState root = slowFight();
        root.potions.add("Fire Potion");
        root.potions.add(null);

        BeamSolver solver = new BeamSolver(ENERGY_PER_TURN, HAND_SIZE, 8, 6);
        solver.bestFirstAction(root.copy());

        assertTrue(solver.lastPotionSpendRejected,
                "这个局面（火药只买到早一回合结束、一滴血没省）必须被闸门退回");
        for (RouteStep step : solver.lastSteps) {
            assertFalse(step.potion, "闸门退回后的路线里不该再出现喝药：" + solver.lastSteps);
        }
        assertTrue(assertContinuationsFollowPublishedSteps(root, solver) > 0,
                "退回后的路线仍要带 continuation，且必须来自退回后的那条");
    }

    @Test
    void zeroCostRescueReplacementChangesContinuationSource() {
        SimState root = slowFight();
        root.hand.clear();
        root.hand.add(new SimCard("Flex", false, 0, -1, "SKILL"));

        BeamSolver solver = new BeamSolver(ENERGY_PER_TURN, HAND_SIZE, 8, 5);
        solver.bindRoot(root);

        // 蓝本口径：「第一步就是结束回合、赢了但赔掉一大截血」才补搜。
        // 真搜索不会产出这种劣解，所以直接喂一个（与 ZeroCostOpeningRescueTest 同一手法）。
        SimState won = root.copy();
        won.monsters.get(0).hp = 0;
        won.hp = 5;
        SearchNode rootNode = new SearchNode(root.copy(), null, null, 0, 0L);
        SearchNode fake = new SearchNode(won, PlanAction.endTurn(), rootNode, 1, 0L);

        solver.publishFinalRoute(fake);
        java.util.List<RouteStep> fakeSteps = new java.util.ArrayList<RouteStep>(solver.lastSteps);
        assertEquals(1, fakeSteps.size(), "劣解只有一步 END_TURN");
        assertTrue(solver.lastContinuations.isEmpty(), "终局那条没有可缓存的边界");

        assertTrue(solver.shouldRescueZeroCostOpening(fake));
        SearchNode rescued = solver.zeroCostOpeningRescue(root, fake, Long.MAX_VALUE);
        assertNotSame(fake, rescued, "补搜必须换掉这条劣解");
        assertTrue(solver.lastZeroCostImproved);

        solver.publishFinalRoute(rescued);

        assertNotEquals(fakeSteps.size(), solver.lastSteps.size(),
                "换掉答案之后 steps 必须跟着换（否则 continuation 和 steps 会来自两条路线）");
        assertFalse(solver.lastSteps.get(0).endTurn,
                "补搜采纳的路线第一步是出牌，不该还是 END_TURN");
        assertTrue(assertContinuationsFollowPublishedSteps(root, solver) > 0,
                "补搜换掉答案后，continuation 必须来自补搜那条");
    }

    @Test
    void routeReplayAndNodeChainProduceEquivalentBoundaryStamps() {
        SimState root = slowFight();
        BeamSolver solver = new BeamSolver(ENERGY_PER_TURN, HAND_SIZE, 8, 6);
        solver.bestFirstAction(root.copy());

        // 缓存里的边界戳，必须与「按同一份 steps 重放」得到的状态戳完全一致。
        // 这条如果不过，说明 captureContinuations 回放的不是最终路线，或者搜索与回放用的不是同一套推进。
        assertTrue(solver.lastContinuations.size() >= 2,
                "46 血 / 每回合最多 18 点，至少要跨两个回合边界，实际："
                        + solver.lastContinuations.turns());
        assertEquals(assertContinuationsFollowPublishedSteps(root, solver),
                solver.lastContinuations.size(),
                "每一个缓存边界都必须能在重放里精确命中");
    }

    /**
     * 边界状态来自**回放**，不是节点链上抄下来的快照。
     *
     * <p>这条测试故意造一条「节点链状态是假的」的路线：END_TURN 节点上写着 hp 17，
     * 而从根回放一遍只会掉 0 血（这只怪不还手）。如果继续用节点链的状态，
     * 缓存下来的就是一条永远不会命中的世界线；只有回放捕获才会得到真实的 hp 30。
     */
    @Test
    void bestNodeSelectsBoundariesButFinalReplayCapturesTheirStates() {
        SimState root = slowFight();
        SearchNode rootNode = new SearchNode(root.copy(), null, null, 0, 0L);

        SimState fabricated = root.copy();
        fabricated.turn = 2;
        fabricated.hp = 17;                       // 假的：回放里这只怪一点伤害都不打
        SearchNode endTurn = new SearchNode(fabricated, PlanAction.endTurn(), rootNode, 1, 0L);
        SearchNode played = new SearchNode(fabricated, PlanAction.play(0, 0), endTurn, 2, 0L);

        BeamSolver solver = new BeamSolver(ENERGY_PER_TURN, HAND_SIZE, 8, 6);
        solver.bindRoot(root);
        solver.publishFinalRoute(played);

        // 独立算一遍「从根回放到这个边界」的真实状态
        SimState replayed = root.copy();
        CombatFlow.endTurn(replayed, ENERGY_PER_TURN, HAND_SIZE);
        ContinuationPlan.Lookup hit = solver.lastContinuations.lookup(2,
                ContinuationStamp.capturePredicted(replayed));
        assertEquals(ContinuationPlan.Lookup.Status.HIT, hit.status(),
                "缓存的状态必须来自回放，实际：" + hit + " / " + solver.lastContinuations);

        ContinuationPlan.Lookup stale = solver.lastContinuations.lookup(2,
                ContinuationStamp.capturePredicted(fabricated));
        assertEquals(ContinuationPlan.Lookup.Status.STATE_MISMATCH, stale.status(),
                "节点链上那份假状态绝不能进缓存");
    }

    private static PlanAction actionAt(java.util.List<PlanAction> route, int index) {
        assertTrue(index >= 0 && index < route.size(),
                "下标越界：" + index + " / " + route.size());
        return route.get(index);
    }
}
