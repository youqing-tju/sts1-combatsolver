package io.github.youqing.sts1solver.search;

import io.github.youqing.sts1solver.mirror.CardPlayer;
import io.github.youqing.sts1solver.sim.CombatFlow;
import io.github.youqing.sts1solver.sim.SimCard;
import io.github.youqing.sts1solver.sim.SimMonster;
import io.github.youqing.sts1solver.sim.SimState;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 离线自对弈：不碰游戏，直接让「规划 → 执行 → 结束回合」跑完一整场。
 *
 * <p>为什么需要它：之前只测「单步决策对不对」，测不出宏观行为。
 * 2026-10-23 那次「站着被打死」就是这类：每一步都合法，整场打下来必输。
 *
 * <p>判据很简单也很难——**必须赢**。输了说明模型或估值哪里漏了一块。
 */
class SelfPlayTest {

    /** 起手牌组：铁甲战士的典型开局（10 张）。 */
    private static SimState duel(int monsterHp, int intentDmg, String monster) {
        SimState s = new SimState();
        s.hp = 80;
        s.maxHp = 80;
        s.energy = 3;
        s.turn = 1;
        for (int i = 0; i < 5; i++) {
            s.draw.add(new SimCard("Strike_R", false, 1));
        }
        for (int i = 0; i < 4; i++) {
            s.draw.add(new SimCard("Defend_R", false, 1));
        }
        s.draw.add(new SimCard("Bash", false, 2));
        SimMonster m = new SimMonster(monster, monsterHp, monsterHp, 0,
                Collections.<String, Integer>emptyMap());
        m.intentDmg = intentDmg;
        s.monsters.add(m);
        // 首回合抽牌由 CombatFlow 负责，这里先把牌堆洗成「已抽」前的状态
        return s;
    }

    /** 打满一回合（每题重规划，模拟 AUTO 的逐张决策）。 */
    private static void playTurn(SimState s, BeamSolver solver, long budgetMillis) {
        for (int guard = 0; guard < 12; guard++) {
            PlanAction action;
            try {
                action = solver.bestFirstAction(s, budgetMillis);
            } catch (Throwable t) {
                return;
            }
            if (action == null || action.kind == PlanAction.Kind.END_TURN) {
                return;
            }
            if (action.cardIndex < 0 || action.cardIndex >= s.hand.size()) {
                return;
            }
            try {
                CardPlayer.play(s, action.cardIndex, action.targetIndex, action.choiceIndex);
            } catch (Throwable rejected) {
                return;
            }
        }
    }

    private static boolean noAlive(SimState s) {
        for (SimMonster m : s.monsters) {
            if (m.hp > 0) {
                return false;
            }
        }
        return true;
    }

    /** 单怪：80 血打 45 血、每回合挨 8 点。必须赢。 */
    @Test
    void winsAgainstASingleMonster() {
        SimState s = duel(45, 8, "JawWorm");
        BeamSolver solver = new BeamSolver(3, 5, 60, 60, 120_000, 32);

        int turns = 0;
        while (turns < 20 && s.hp > 0 && !noAlive(s)) {
            CombatFlow.endTurn(s, 3, 5);   // 回合开始：弃手牌 → 敌人行动 → 抽牌
            if (s.hp <= 0 || noAlive(s)) {
                break;
            }
            playTurn(s, solver, 40);
            turns++;
        }

        System.out.println("[selfplay] 单怪 45 血：剩余血量 " + s.hp
                + "，怪物 " + s.monsters.get(0).hp + "，回合 " + turns);
        assertTrue(noAlive(s), "应该打死 45 血的怪，实际怪物剩 " + s.monsters.get(0).hp
                + " 血、自己剩 " + s.hp);
        assertTrue(s.hp > 0);
    }

    /** 双怪：必须先集火减员，不能一直空过。 */
    @Test
    void winsAgainstTwoMonsters() {
        SimState s = duel(30, 6, "LouseA");
        SimMonster second = new SimMonster("LouseB", 28, 28, 0,
                Collections.<String, Integer>emptyMap());
        second.intentDmg = 6;
        s.monsters.add(second);
        BeamSolver solver = new BeamSolver(3, 5, 60, 60, 120_000, 32);

        int turns = 0;
        while (turns < 20 && s.hp > 0 && !noAlive(s)) {
            CombatFlow.endTurn(s, 3, 5);
            if (s.hp <= 0 || noAlive(s)) {
                break;
            }
            playTurn(s, solver, 40);
            turns++;
        }

        System.out.println("[selfplay] 双怪 30+28 血：剩余血量 " + s.hp
                + "，剩怪 " + aliveCount(s) + "，回合 " + turns);
        assertTrue(noAlive(s), "应该清场，实际还剩 " + aliveCount(s) + " 只");
    }

    /**
     * A/B：把「每次决策的预算」拉长，结果会不会变好？
     *
     * <p>这是用户的核心假设（瓶颈是搜索量）。用几个有取舍的场面各跑三档预算，
     * 看剩余血量 / 回合数 / 总节点。**只打印不断言** —— 让数据说话。
     *
     * <p>标了 {@code @Disabled}：跑完要几十秒，不该进 CI。要复现就临时去掉注解。
     */
    @org.junit.jupiter.api.Disabled("手动实验（跑一次约 1 分钟），别进 CI")
    @Test
    void moreSearchTimeHelps() {
        long[] budgets = {5, 60, 300};
        String[] names = {"双教徒 40+40 / 每回合共 20 伤", "单厚怪 120 血 / 12 伤"};
        System.out.println("[ab] 场面 | 预算ms | 回合 | 剩余血量 | 总节点 | 存活");
        for (int scenario = 0; scenario < names.length; scenario++) {
            for (long budget : budgets) {
                SimState s = scenario == 0 ? twoCultists() : bigSingle();
                BeamSolver solver = new BeamSolver(3, 5, 60, 60, 120_000, 32);
                long nodes = 0;
                int turns = 0;
                while (turns < 25 && s.hp > 0 && !noAlive(s)) {
                    CombatFlow.endTurn(s, 3, 5);
                    if (s.hp <= 0 || noAlive(s)) {
                        break;
                    }
                    playTurn(s, solver, budget);
                    nodes += solver.lastNodes;
                    turns++;
                }
                System.out.printf("[ab] %s | %5d | %3d | %5d | %6d | %s%n",
                        names[scenario], budget, turns, Math.max(0, s.hp), nodes,
                        s.hp > 0 ? "yes" : "DEAD");
            }
        }
    }

    private static SimState twoCultists() {
        SimState s = duel(40, 10, "CultistA");
        SimMonster second = new SimMonster("CultistB", 40, 40, 0,
                Collections.<String, Integer>emptyMap());
        second.intentDmg = 10;
        s.monsters.add(second);
        s.draw.add(new SimCard("Shrug It Off", false, 1));
        s.draw.add(new SimCard("Shrug It Off", false, 1));
        return s;
    }

    private static SimState bigSingle() {
        SimState s = duel(120, 12, "BigHit");
        s.draw.add(new SimCard("Shrug It Off", false, 1));
        s.draw.add(new SimCard("Shrug It Off", false, 1));
        return s;
    }

    private static int aliveCount(SimState s) {
        int n = 0;
        for (SimMonster m : s.monsters) {
            if (m.hp > 0) {
                n++;
            }
        }
        return n;
    }

    /**
     * 反向断言：每回合真的在打牌。
     *
     * <p>「站着不动」是 2026-10-23 的实际故障，这里用「出牌数 > 0」把它钉住。
     */
    @Test
    void actuallyPlaysCardsEachTurn() {
        SimState s = duel(200, 5, "Tank");
        BeamSolver solver = new BeamSolver(3, 5, 60, 60, 120_000, 32);
        CombatFlow.endTurn(s, 3, 5);

        int before = s.hand.size();
        playTurn(s, solver, 40);

        // 打完一轮手牌应该明显变少（至少打出一张）
        assertTrue(s.hand.size() < before,
                "整回合没出任何牌：手牌 " + before + " -> " + s.hand.size()
                        + "，能量 " + s.energy);
        assertEquals(true, s.energy <= 3);
    }
}
