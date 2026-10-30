package io.github.youqing.sts1solver.search;

import io.github.youqing.sts1solver.sim.SimCard;
import io.github.youqing.sts1solver.sim.SimMonster;
import io.github.youqing.sts1solver.sim.SimState;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BFWS 新颖度搜索的零件与接线（蓝本 {@code BfwsPackedNovelty} / {@code BfwsBoundedOpen} /
 * {@code RunNoveltyOpen}）。
 *
 * <p>为什么值得单测：新颖度的「1 / 2 / 熟悉」三档决定了哪些子状态能进 open，
 * 算错一档就会变成「把所有状态都当新的」（内存炸）或者「什么都当熟悉的」（搜不动），
 * 而这两种错误在真机上只表现为「快/慢」，不是崩溃，很难从日志里看出来。
 */
class NoveltySearchTest {

    private static SimState state(int energy, int hand) {
        SimState s = new SimState();
        s.hp = 70;
        s.maxHp = 80;
        s.energy = energy;
        s.turn = 1;
        for (int i = 0; i < hand; i++) {
            s.hand.add(new SimCard(i % 2 == 0 ? "Strike_R" : "Defend_R", false, 1));
        }
        s.draw.add(new SimCard("Bash", false, 2));
        s.monsters.add(new SimMonster("Cultist", 50, 50, 0,
                Collections.<String, Integer>emptyMap()));
        return s;
    }

    @Test
    void firstSightingIsUnaryNovelAndRepeatIsFamiliar() {
        NoveltySearch.PackedNovelty novelty = new NoveltySearch.PackedNovelty(2, 10_000);
        SimState s = state(3, 5);
        List<String> facts = NoveltySearch.factsOf(s);
        long partition = NoveltySearch.partitionOf(s, 0);

        // 蓝本的记录是**惰性**的：一元组已经新了(1)就不再记二元组，
        // 所以同一个状态连算三次的序列是 1 -> 2 -> 3（熟悉）
        assertEquals(1, novelty.evaluate(partition, facts), "第一次见到：有没见过的一元组");
        assertEquals(2, novelty.evaluate(partition, facts),
                "一元组都见过了，但配对还是新的 -> 2");
        assertEquals(3, novelty.evaluate(partition, facts), "一元组和配对都见过 = 熟悉(3)");
        novelty.prepareParent(partition, facts);
        assertEquals(3, novelty.evaluate(partition, facts),
                "父节点就是它本人：没有任何「变了的事实」，直接熟悉(3)");
    }

    @Test
    void newFactInANewPartitionIsNovelAgain() {
        NoveltySearch.PackedNovelty novelty = new NoveltySearch.PackedNovelty(2, 10_000);
        SimState s = state(3, 5);
        List<String> facts = NoveltySearch.factsOf(s);
        assertEquals(1, novelty.evaluate(NoveltySearch.partitionOf(s, 0), facts));

        // 敌人少 10 血 -> 换分区：分区之间不共享历史，所以又算「新」
        s.monsters.get(0).hp = 40;
        assertEquals(1, novelty.evaluate(NoveltySearch.partitionOf(s, 0),
                NoveltySearch.factsOf(s)), "换了分区要重新算新");
    }

    @Test
    void energyChangeIsNewButKnownAloneIsNot() {
        NoveltySearch.PackedNovelty novelty = new NoveltySearch.PackedNovelty(2, 10_000);
        SimState a = state(3, 5);
        long partition = NoveltySearch.partitionOf(a, 0);
        assertEquals(1, novelty.evaluate(partition, NoveltySearch.factsOf(a)));

        SimState b = state(2, 5);          // 只少 1 点能量：E2 是没见过的原子
        b.monsters.get(0).hp = a.monsters.get(0).hp;
        assertEquals(1, novelty.evaluate(partition, NoveltySearch.factsOf(b)),
                "出现没见过的一元组 = 1");
    }

    @Test
    void boundedOpenDropsTheWorstAndKeepsTheMostNovel() {
        NoveltySearch.BoundedOpen open = new NoveltySearch.BoundedOpen(2);
        SearchNode a = new SearchNode(state(3, 5), null, null, 0, 10L);
        SearchNode b = new SearchNode(state(3, 5), null, null, 0, 20L);
        SearchNode c = new SearchNode(state(3, 5), null, null, 0, 30L);

        assertNull(open.enqueue(a, 2, a.score, 0), "有两个空位，不挤人");
        open.enqueue(b, 1, b.score, 1);
        // 新颖度 1 比 2 好，所以挤掉的是 a（新颖度 2）
        assertEquals(a, open.enqueue(c, 1, c.score, 2), "满了要丢最差的那条");
        assertEquals(2, open.size());
        assertEquals(c, open.dequeue(), "新颖度同档时分数高的先出（c=30 比 b=20 高）");
        assertEquals(b, open.dequeue());
    }

    @Test
    void escapeBudgetOnlyAdmitsUpToTheAllowance() {
        NoveltySearch.EscapeBudget budget = new NoveltySearch.EscapeBudget(2);
        assertTrue(budget.tryAdmit());
        assertTrue(budget.tryAdmit());
        assertFalse(budget.tryAdmit(), "额度用完就不再放熟悉子节点");
    }

    @Test
    void solverWithNoveltyStillReturnsAPlayableAction() {
        SimState s = state(3, 5);
        BeamSolver solver = new BeamSolver(3, 5, 8, 4, 4_000, 24).withNovelty();
        PlanAction action = solver.bestFirstAction(s, 3_000L);

        assertNotNull(action, "开了新颖度通道也必须给出动作");
        assertFalse("off".equals(solver.lastNoveltyStop),
                "新颖度那趟应该真的跑过（stop 不该是 off）");
        assertTrue(solver.lastNoveltyNodes > 0, "应该有展开");
        assertTrue(solver.lastNoveltyEntries > 0, "新颖度表该记到东西");
    }

    @Test
    void noveltyDisabledByDefault() {
        SimState s = state(3, 5);
        BeamSolver solver = new BeamSolver(3, 5, 8, 4, 4_000, 24);
        solver.bestFirstAction(s, 3_000L);
        assertEquals("off", solver.lastNoveltyStop, "不调 withNovelty() 就不该跑");
        assertEquals(0, solver.lastNoveltyNodes);
    }

    /** 事实集要覆盖四个牌堆、状态、抽牌堆顶 —— 少一个维度就会把不同状态当同一个。 */
    @Test
    void factsDistinguishPiles() {
        SimState s = state(3, 5);
        List<String> before = NoveltySearch.factsOf(s);
        s.discard.add(new SimCard("Anger", false, 0));
        List<String> after = NoveltySearch.factsOf(s);
        assertFalse(new ArrayList<String>(before).equals(after),
                "弃牌堆变了，事实集必须跟着变");
        assertTrue(after.contains("ZxAnger1"), "弃牌堆的事实是 Z + 区 + 牌名 + 张数: " + after);
    }
}
