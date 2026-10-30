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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Beam 宽度组合（蓝本 {@code BeamWidthPortfolio.cs} + {@code BeamWidthPortfolioGate.cs}）。
 *
 * <p>这套东西的价值全在「成员清单对不对」和「准入门算术对不对」：
 * 清单错了等于白跑几趟，门控错了要么永远不精炼（等于没搬）、要么每次都把时间吃光。
 * 两者都是纯算术，所以可以逐条钉住。
 */
class BeamWidthPortfolioTest {

    @Test
    void productionMembersMatchTheBlueprintList() {
        List<BeamWidthPortfolio.Member> members = BeamWidthPortfolio.productionMembers(135);
        assertEquals(5, members.size(), "蓝本默认是五个成员：基线 + 窄 + 宽 + 次段 + 基础分");
        assertEquals(135, members.get(0).beamWidth);
        assertFalse(members.get(0).secondRankBand || members.get(0).baseScoreOnly,
                "首个成员必须是无修饰的基线（逐位等于单趟搜索）");
        assertEquals(90, members.get(1).beamWidth, "135 × 2/3 = 90");
        assertEquals(203, members.get(2).beamWidth, "135 × 3/2 = 202.5，四舍五入 203");
        assertEquals(135, members.get(3).beamWidth);
        assertTrue(members.get(3).secondRankBand, "第四个是次段成员");
        assertEquals(135, members.get(4).beamWidth);
        assertTrue(members.get(4).baseScoreOnly, "第五个是基础分成员");
    }

    @Test
    void scaledWidthRoundsAwayFromZeroAndKeepsAtLeastOne() {
        assertEquals(90, BeamWidthPortfolio.scaledWidth(135, 2d / 3d));
        assertEquals(203, BeamWidthPortfolio.scaledWidth(135, 3d / 2d));
        assertEquals(40, BeamWidthPortfolio.scaledWidth(60, 2d / 3d));
        assertEquals(1, BeamWidthPortfolio.scaledWidth(1, 2d / 3d), "最小是 1，不能是 0");
    }

    @Test
    void estimateCostUsesWidthRatioAndSafetyFactor() {
        // 1000ms × (90/135) × 3/2 = 1000
        assertEquals(1000, BeamWidthPortfolio.estimateMemberCost(1000, 135, 90));
        // 1000 × (203/135) × 1.5 = 2255.6 -> 2256
        assertEquals(2256, BeamWidthPortfolio.estimateMemberCost(1000, 135, 203));
    }

    @Test
    void gateRejectsWhenBaselineAlreadyProvedZeroDamage() {
        assertEquals(BeamWidthPortfolio.SKIP_ZERO_DAMAGE,
                BeamWidthPortfolio.rejectRefinement(true, true, 100, 135, 90,
                        1_000_000, 100_000, 300_000, 1000));
    }

    @Test
    void gateRejectsWhenBaselineWasTruncated() {
        assertEquals(BeamWidthPortfolio.SKIP_NOT_EXHAUSTED,
                BeamWidthPortfolio.rejectRefinement(false, false, 100, 135, 90,
                        1_000_000, 100_000, 300_000, 1000),
                "基线被节点帽截断时，预算要留给它自己");
    }

    @Test
    void gateRejectsWhenBaselineAteMoreThanAQuarterOfTheTime() {
        // 300000/4 = 75000，基线用了 80000 -> 拒
        assertEquals(BeamWidthPortfolio.SKIP_TIME_SHARE,
                BeamWidthPortfolio.rejectRefinement(true, false, 80_000, 135, 90,
                        1_000_000, 200_000, 300_000, 1000));
        // 用了 70000 -> 份额够，继续看余量
        assertNull(BeamWidthPortfolio.rejectRefinement(true, false, 70_000, 135, 90,
                1_000_000, 200_000, 300_000, 1000));
    }

    @Test
    void gateRejectsWhenNodeOrTimeHeadroomIsShort() {
        assertEquals(BeamWidthPortfolio.SKIP_NODE_HEADROOM,
                BeamWidthPortfolio.rejectRefinement(true, false, 100, 135, 90,
                        500, 100_000, 300_000, 1000),
                "剩余节点还不够跑一趟基线体量");
        assertEquals(BeamWidthPortfolio.SKIP_TIME_HEADROOM,
                BeamWidthPortfolio.rejectRefinement(true, false, 1000, 135, 135,
                        1_000_000, 1200, 300_000, 1000),
                "按宽度外推估出来 1500ms > 剩余 1200ms");
    }

    private static SimState combat() {
        SimState s = new SimState();
        s.hp = 70;
        s.maxHp = 80;
        s.energy = 3;
        s.turn = 1;
        s.hand.add(new SimCard("Strike_R", false, 1, 6, "ATTACK"));
        s.hand.add(new SimCard("Defend_R", false, 1, -1, "SKILL"));
        s.draw.add(new SimCard("Bash", false, 2, 8, "ATTACK"));
        s.draw.add(new SimCard("Strike_R", false, 1, 6, "ATTACK"));
        s.monsters.add(new SimMonster("Cultist", 45, 50, 0,
                Collections.<String, Integer>emptyMap()));
        return s;
    }

    @Test
    void solverWithPortfolioRunsSeveralMembersAndStillAnswers() {
        BeamSolver solver = new BeamSolver(3, 5, 135, 8, 200_000, 72).withPortfolio();
        PlanAction action = solver.bestFirstAction(combat(), 20_000L);

        assertNotNull(action, "组合必须给出动作");
        assertFalse(solver.lastPortfolioMembers.isEmpty(), "成员清单要留下来（诊断/面板用）");
        assertTrue(solver.lastPortfolioMembers.size() >= 2,
                "至少基线 + 一个精炼成员: " + solver.lastPortfolioMembers);
        assertFalse(solver.lastPortfolioAdopted.isEmpty(), "要记下采纳了哪个成员");
    }

    @Test
    void portfolioOffByDefault() {
        BeamSolver solver = new BeamSolver(3, 5, 135, 8, 200_000, 72);
        solver.bestFirstAction(combat(), 20_000L);
        assertTrue(solver.lastPortfolioMembers.isEmpty(), "不调 withPortfolio() 就不该跑组合");
    }
}
