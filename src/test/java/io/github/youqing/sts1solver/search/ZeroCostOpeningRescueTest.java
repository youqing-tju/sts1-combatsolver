package io.github.youqing.sts1solver.search;

import io.github.youqing.sts1solver.sim.SimCard;
import io.github.youqing.sts1solver.sim.SimMonster;
import io.github.youqing.sts1solver.sim.SimState;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 0 费开局补搜（蓝本 {@code RunZeroCostOpeningRescue}）与它的三个前置件。
 *
 * <p>逐项对应蓝本：
 * <ul>
 *   <li>固定前缀回放 —— {@code CombatBeamSolver.Phases.cs:2296-2400 ApplyFixedPrefix}；</li>
 *   <li>免费前缀枚举 —— {@code Expansion.Opening.cs:1028-1064 BuildOpeningNoCostPrefixes}；</li>
 *   <li>药水用量闸 —— {@code ExpansionPlan.cs:146-147 maximumPotionUses}；</li>
 *   <li>触发条件与采纳 —— {@code PostSearch.cs:352-392}。</li>
 * </ul>
 *
 * <p>⚠️ 没覆盖到的：「真的让主搜索误判、再由补搜救回来」那种端到端局面 ——
 * 它依赖 beam 剪枝的具体宽度，换一个宽度就不复现，钉不住。所以这里改钉**采纳逻辑**
 * （喂一个明显更差的 `selected`，补搜必须换成更好的那条）和**各前置件的行为**。
 */
class ZeroCostOpeningRescueTest {

    /** 一张怪、意图 0（不掉血）、抽牌堆垫 8 张 Strike，避免回合开始抽不出牌。 */
    private static SimState state(int monsterHp, int intentDmg) {
        SimState s = new SimState();
        s.hp = 30;
        s.maxHp = 80;
        s.energy = 3;
        SimMonster m = new SimMonster("TestMonster", monsterHp, monsterHp, 0,
                Collections.<String, Integer>emptyMap());
        m.intentDmg = intentDmg;
        s.monsters.add(m);
        for (int i = 0; i < 8; i++) {
            s.draw.add(new SimCard("Strike_R", false, 1, 6, "ATTACK"));
        }
        return s;
    }

    private static SimCard flex() {
        return new SimCard("Flex", false, 0, -1, "SKILL");
    }

    private static SimCard rage() {
        return new SimCard("Rage", false, 0, -1, "SKILL");
    }

    private static SimCard strike() {
        return new SimCard("Strike_R", false, 1, 6, "ATTACK");
    }

    /**
     * 前缀必须被**先回放掉**，再当搜索根。
     *
     * <p>判据落在 {@code lastSteps[0].cardKey}：前缀第一步是 Flex，路线第一步就必须是 Flex，
     * 而且后面的搜索不能再把它算成「待选项」（牌已经打出去了）。
     */
    @Test
    void replaysTheFixedPrefixBeforeSearching() {
        SimState s = state(30, 0);
        s.hand.add(flex());
        s.hand.add(strike());

        BeamSolver solver = new BeamSolver(3, 5, 8, 5)
                .withFixedPrefix(Collections.singletonList(PlanAction.play(0, -1)));
        PlanAction first = solver.bestFirstAction(s);

        assertNotNull(first, "前缀合法时应该有结果");
        assertEquals(PlanAction.Kind.PLAY_CARD, first.kind);
        assertFalse(solver.lastSteps.isEmpty(), "路线不能为空");
        assertEquals("Flex", solver.lastSteps.get(0).cardKey, "路线第一步必须等于前缀那一步");
    }

    /**
     * 前缀打不出就整条作废（蓝本 {@code CanApplyFixedPrefixAction} 为假 → 返回 null）。
     *
     * <p>两档都要拒：能量不够、下标越界。**不许**悄悄换一张牌顶上。
     */
    @Test
    void rejectsAnUnplayablePrefixInsteadOfGuessing() {
        SimState noEnergy = state(30, 0);
        noEnergy.hand.add(strike());
        noEnergy.energy = 0;
        assertNull(new BeamSolver(3, 5, 8, 5)
                        .withFixedPrefix(Collections.singletonList(PlanAction.play(0, -1)))
                        .bestFirstAction(noEnergy),
                "能量不够时前缀必须整条作废");

        SimState outOfRange = state(30, 0);
        outOfRange.hand.add(strike());
        assertNull(new BeamSolver(3, 5, 8, 5)
                        .withFixedPrefix(Collections.singletonList(PlanAction.play(7, -1)))
                        .bestFirstAction(outOfRange),
                "下标越界时前缀必须整条作废");
    }

    /**
     * 药水闸（蓝本 {@code maximumPotionUses}）：0 = 这趟搜索一杯不喝。
     *
     * <p>局面选成「只有喝火药才能活」：怪 20 血、意图 15、我 10 血、能量 0、空手。
     * 不设闸 → 路线必用火药；设成 0 → 路线里一瓶都不能出现。
     */
    @Test
    void potionCapZeroKeepsPotionsOutOfTheRoute() {
        SimState capped = state(20, 15);
        capped.hp = 10;
        capped.energy = 0;
        capped.potions.add("Fire Potion");
        capped.potions.add(null);

        BeamSolver closed = new BeamSolver(3, 5, 8, 5).withMaxPotionUses(0);
        closed.bestFirstAction(capped.copy());
        for (PlanAction action : closed.lastRoute) {
            assertFalse(action.kind == PlanAction.Kind.USE_POTION,
                    "上限 0 时路线里不能出现用药动作：" + action);
        }

        BeamSolver open = new BeamSolver(3, 5, 8, 5);
        open.bestFirstAction(capped.copy());
        boolean usedPotion = false;
        for (PlanAction action : open.lastRoute) {
            if (action.kind == PlanAction.Kind.USE_POTION) {
                usedPotion = true;
            }
        }
        assertTrue(usedPotion, "不限药水时这条路线必须喝火药（不喝必死）");
    }

    /**
     * 免费前缀枚举（蓝本 {@code BuildOpeningNoCostPrefixes}）：只收「不打掉能量」的出牌，
     * 层数 ≤ 4，条数 ≤ 12，且「动作多的排前面」。
     *
     * <p>手牌 = Flex(0) / Rage(0) / Strike(1)：付费的 Strike 一次都不许出现在前缀里 ——
     * 用「回放这条前缀后看牌名」来判，比看下标可靠（打掉一张牌之后下标会移位）。
     */
    @Test
    void enumeratesOnlyFreePlays() {
        SimState s = state(30, 0);
        s.hand.add(flex());
        s.hand.add(rage());
        s.hand.add(strike());

        BeamSolver solver = new BeamSolver(3, 5, 8, 5);
        List<List<PlanAction>> prefixes = solver.openingNoCostPrefixes(s);

        assertFalse(prefixes.isEmpty(), "手里有两张 0 费牌，必须枚举出前缀");
        assertTrue(prefixes.size() <= BeamSolver.NO_COST_PREFIX_LIMIT,
                "最多 12 条，实际 " + prefixes.size());
        int previousLength = Integer.MAX_VALUE;
        for (List<PlanAction> prefix : prefixes) {
            assertTrue(prefix.size() <= BeamSolver.NO_COST_PREFIX_MAX_DEPTH,
                    "前缀不能超过 4 步：" + prefix);
            assertTrue(prefix.size() <= previousLength, "动作多的必须排前面");
            previousLength = prefix.size();
            for (PlanAction action : prefix) {
                assertEquals(PlanAction.Kind.PLAY_CARD, action.kind, "前缀里只能是出牌：" + action);
            }

            BeamSolver probe = new BeamSolver(3, 5, 8, 5)
                    .withFixedPrefix(prefix)
                    .withMaxPotionUses(0);
            assertNotNull(probe.bestFirstAction(s.copy()), "枚举出来的前缀必须自己能回放");
            for (int i = 0; i < prefix.size(); i++) {
                String key = probe.lastSteps.get(i).cardKey;
                assertTrue("Flex".equals(key) || "Rage".equals(key),
                        "免费前缀里不该出现付费牌，实际出现：" + key);
            }
        }
    }

    /**
     * 采纳逻辑（蓝本 {@code PostSearch.cs:376-381}）：补搜回来**严格更好**才替换。
     *
     * <p>这里直接喂一个「赢了但赔掉 25 血」的假 `selected`（真搜索绝不可能选它），
     * 补搜必须在同一局面里找出更好的路线并替换掉。这样不依赖 beam 宽度的偶然性。
     */
    @Test
    void adoptsAPrefixRouteOnlyWhenItIsStrictlyBetter() {
        SimState root = state(4, 0);      // 怪 4 血、这个回合不掉血
        root.hand.add(flex());
        root.hand.add(strike());

        BeamSolver solver = new BeamSolver(3, 5, 8, 5);
        solver.bindRoot(root);

        SearchNode fake = fakeEndTurnSelection(root, 5);   // 掉到 5 血：赤字 25
        assertTrue(solver.shouldRescueZeroCostOpening(fake), "「赢了但赔惨了、第一步是结束回合」该补搜");

        SearchNode rescued = solver.zeroCostOpeningRescue(root, fake, Long.MAX_VALUE);

        assertNotSame(fake, rescued, "补搜必须换掉这条劣解");
        assertTrue(rescued.state.hp > fake.state.hp, "换来的路线掉血必须更少");
        assertTrue(solver.lastZeroCostImproved, "遥测要记下「采纳了」");
        assertTrue(solver.lastZeroCostAttempts > 0, "遥测要记下派发了几趟");
    }

    /** 第一步就是出牌时不补搜（蓝本判据是 {@code First().Kind == EndTurn}，别的时候不插手）。 */
    @Test
    void doesNotRescueWhenTheBestRouteAlreadyStartsWithACard() {
        SimState root = state(4, 0);
        root.hand.add(flex());
        root.hand.add(strike());

        BeamSolver solver = new BeamSolver(3, 5, 8, 5);
        solver.bindRoot(root);

        SearchNode rootNode = new SearchNode(root.copy(), null, null, 0, 0);
        SimState won = root.copy();
        won.monsters.get(0).hp = 0;
        won.hp = 5;
        SearchNode fake = new SearchNode(won, PlanAction.play(0, -1), rootNode, 1, 0);

        assertFalse(solver.shouldRescueZeroCostOpening(fake));
        assertSame(fake, solver.zeroCostOpeningRescue(root, fake, Long.MAX_VALUE));
        assertEquals(0, solver.lastZeroCostAttempts);
    }

    /** 造一条「第一步结束回合、已经打赢、但赔掉大量血」的选择，用来测采纳逻辑。 */
    private static SearchNode fakeEndTurnSelection(SimState root, int hpLeft) {
        SearchNode rootNode = new SearchNode(root.copy(), null, null, 0, 0);
        SimState won = root.copy();
        won.monsters.get(0).hp = 0;      // 怪清空 = 真打赢
        won.hp = hpLeft;
        return new SearchNode(won, PlanAction.endTurn(), rootNode, 1, 0);
    }
}
