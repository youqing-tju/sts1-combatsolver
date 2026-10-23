package io.github.youqing.sts1solver.search;

import io.github.youqing.sts1solver.sim.SimCard;
import io.github.youqing.sts1solver.sim.SimMonster;
import io.github.youqing.sts1solver.sim.SimState;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 蓝本 {@code BeamRetentionPolicy} 的第一批复刻：
 * <ul>
 *   <li>{@code CompareBeamRankOrder} 第三键（offensiveProgressValue 降序）；</li>
 *   <li>{@code DiversifyOrdinaryBeamBoundary}（beam 边界多样化）。</li>
 * </ul>
 * 证据：{@code StateEvaluation.cs:448-451}（量）、{@code StrategicEffectModel.cs:499}
 * （力量潜力）、{@code CardMechanismFacts.cs:14-19,44-55}（命中段数）、
 * {@code BeamRetentionPolicy.cs:47-165}（多样化）。
 */
class BeamRetentionPolicyTest {

    /** 攻击推进量：力量潜力 + 全身撞击的格挡（延迟/反应伤害铁甲恒 0）。 */
    @Test
    void offensiveProgressCountsStrengthPotentialAndBodySlamBlock() {
        SimState s = new SimState();
        s.hp = 30;
        s.maxHp = 80;
        s.block = 5;
        s.playerPowers.put("Strength", 2);
        // 手牌：双持打击（2 段，伤害 5）+ 防御；抽牌堆：全身撞击 + 两张打击
        s.hand.add(new SimCard("Twin Strike", false, 1, 5, "ATTACK"));
        s.hand.add(new SimCard("Defend_R", false, 1, 0, "SKILL"));
        s.draw.add(new SimCard("Body Slam", false, 1, 0, "ATTACK"));
        s.draw.add(new SimCard("Strike_R", false, 1, 6, "ATTACK"));
        s.draw.add(new SimCard("Strike_R", false, 1, 6, "ATTACK"));
        SimMonster m = new SimMonster("TestMonster", 40, 40, 0,
                Collections.<String, Integer>emptyMap());
        s.monsters.add(m);

        // liveCards = 5，deckSize=5；attackHitCount = 2+1+1+1 = 5；totalAttackValue = 5+0+6+6 = 17
        // cardsPerTurn = 5；estimatedCycles = ceil(40/17) = 3；remainingTurns = 3*ceil(5/5) = 3
        // reachableCards = min(10, 15) = 10；attackHits = ceil(5*10/5) = 10
        // 力量潜力 = 2*10 = 20；全身撞击在 → +max(0, 5)；预期 = 25
        assertEquals(25, BeamSolver.offensiveProgressOf(s));
    }

    /** 没有力量、没有全身撞击 → 推进量为 0（延迟/反应伤害铁甲恒 0）。 */
    @Test
    void offensiveProgressIsZeroWithoutStrengthOrBodySlam() {
        SimState s = new SimState();
        s.hp = 30;
        s.maxHp = 80;
        s.block = 9;
        s.hand.add(new SimCard("Strike_R", false, 1, 6, "ATTACK"));
        SimMonster m = new SimMonster("TestMonster", 10, 10, 0,
                Collections.<String, Integer>emptyMap());
        s.monsters.add(m);

        assertEquals(0, BeamSolver.offensiveProgressOf(s));
    }

    /** 排序第三键：rank 与 actionCount 全同时，攻击推进量高者在前。 */
    @Test
    void prunedBeamBreaksTiesByOffensiveProgressDescending() {
        BeamSolver solver = new BeamSolver(3, 5, 8, 5);
        SearchNode high = node(20);
        SearchNode low = node(5);
        // 相同 rank、相同 depth（主键并列），progress 高者应排前
        List<SearchNode> ordered = solver.prune(new ArrayList<SearchNode>(
                java.util.Arrays.asList(low, high)), 2);

        assertEquals(2, ordered.size());
        assertTrue(ordered.get(0) == high, "同主键时攻击推进量高者排前");
        assertTrue(ordered.get(1) == low);
    }

    /**
     * 边界多样化（蓝本 BeamRetentionPolicy.cs:151-163 轮转语义）：
     * 主键并列段、同药水桶里「部分入选」时，按攻击推进量降序的组轮转占位 ——
     * 高推进组的代表（含被截掉的 D）换进席位，单一推进值不再垄断并列段。
     *
     * <p>手工推演（width=3，rank/depth/potions 全同，rank 全同 + 稳定排序 = 分数序）：
     * rankedPool=[A(10), B(20), C(10), D(20)]，selected=[A,B,C]
     * → 桶0 全体部分入选，组序 [20:{B,D}, 10:{A,C}]
     * → 轮转 B,A,D → selected=[B,A,D]。
     */
    @Test
    void diversifyRotatesPartiallyRetainedTieByProgressGroups() {
        SearchNode a = node(10);
        SearchNode b = node(20);
        SearchNode c = node(10);
        SearchNode d = node(20);
        List<SearchNode> pool = new ArrayList<SearchNode>(
                java.util.Arrays.asList(a, b, c, d));
        List<SearchNode> selected = new BeamSolver(3, 5, 3, 2).prune(pool, 3);

        // width=3 截断后 selected=[a,b,c]，多样化应轮转成 [b,a,d]
        assertEquals(3, selected.size());
        assertTrue(selected.get(0) == b, "高推进组第一代表占第 1 席");
        assertTrue(selected.get(1) == a, "低推进组第一代表占第 2 席");
        assertTrue(selected.get(2) == d, "高推进组第二代表（原被截掉）换入第 3 席");
    }

    /**
     * 不同药水使用数的候选不互相轮转（分桶独立，蓝本 :119-128）。
     *
     * <p>pool=[a(10,p0), b(20,p0), c(10,p1), d(20,p1)]，width=2：
     * 第三键排序后 = [b(20), d(20), a(10), c(10)] → selected=[b,d]；
     * 桶 p0 只有 b 入选、桶 p1 只有 d 入选 —— 都不满足「部分入选」，无轮转发生。
     */
    @Test
    void diversifyDoesNotMixDifferentPotionBuckets() {
        SearchNode a = nodeWith(10, 0);
        SearchNode b = nodeWith(20, 0);
        SearchNode c = nodeWith(10, 1);
        SearchNode d = nodeWith(20, 1);
        List<SearchNode> pool = new ArrayList<SearchNode>(
                java.util.Arrays.asList(a, b, c, d));
        List<SearchNode> selected = new BeamSolver(3, 5, 2, 2).prune(pool, 2);

        assertEquals(2, selected.size());
        assertTrue(selected.get(0) == b, "排序第三键：progress 20 排前");
        assertTrue(selected.get(1) == d, "同 progress 保持稳定序（d 排 a 前）");
    }

    // ===== helpers =====

    /** 每建一个节点 hp 递增 → 指纹互异，避开 prune 的状态去重。 */
    private static int uniqueHp = 100;

    /** depth=0、rank 相同的节点（构成蓝本 SamePrimary 并列段），progress 手工给定、无药水。 */
    private static SearchNode node(int progress) {
        return nodeWith(progress, 0);
    }

    /** 显式指定 progress 与已用药水数（名字带 With，避免与单参重载混淆）。 */
    private static SearchNode nodeWith(int progress, int potionsUsed) {
        SimState s = new SimState();
        s.hp = ++uniqueHp;
        s.maxHp = 80;
        s.energy = 3;
        SimMonster m = new SimMonster("TestMonster", 10, 10, 0,
                Collections.<String, Integer>emptyMap());
        s.monsters.add(m);
        return new SearchNode(s, null, null, 0, 7L, 7L, null, progress, potionsUsed);
    }
}
