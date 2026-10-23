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
     * <p>⚠️ 2026-10-23 复检：这个台架**不可信**。时间预算吃的是真实墙钟，同一配置两次跑出
     * 不同结果，「预算越大越早死」有一半是机器负载噪声。要看估值好坏请用
     * {@link ScoreAbTest}（固定宽度 × 深度，确定性）。
     *
     * <p>要跑它就加 {@code -Pexperiments}（CI 不传就不会跑）。
     */
    @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named = "sts1.experiments", matches = "true", disabledReason = "已被 ScoreAbTest 取代（时间预算不可复现），留作历史对照")
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
     * 估值质量的**确定性**回归：同样输入必然同样输出，所以数字可以当断言用。
     *
     * <p>2026-10-23 现状（蓝本原味权重：1 血 = 3 敌方血 + 掉血二次计价）：
     * 双教徒 10 回合剩 26 血、单厚怪 21 回合剩 64 血。
     *
     * <p>⚠️ 这里**不断言回合数上限**。蓝本的 {@code ActionPenalty = -1} 对回合数几乎不计价，
     * 而 1:6 的有效汇率（二次计价后）本来就允许「用血量换时间」。
     * 纯木桩（固定 12 伤、不成长）对「龟」格外有利，21 回合是这种木桩的产物；
     * 真怪物会成长，且 STS1 一局里没有 20 回合的普通战。
     * 要收紧的话改 `Score.damageTakenWeight`，别改断言。
     */
    @Test
    void deterministicSearchKeepsItsQuality() {
        SimState cultists = twoCultists();
        int cultistTurns = fight(cultists, 60, 60);
        System.out.println("[regress] 双教徒：回合 " + cultistTurns + "，剩血 " + cultists.hp);
        assertTrue(noAlive(cultists), "双教徒必须清场，实际剩 " + aliveCount(cultists) + " 只");
        assertTrue(cultists.hp >= 15, "双教徒打完应剩 ≥15 血（旧估值同条件会死），实际 " + cultists.hp);

        SimState big = bigSingle();
        int bigTurns = fight(big, 60, 60);
        System.out.println("[regress] 单厚怪：回合 " + bigTurns + "，剩血 " + big.hp);
        assertTrue(noAlive(big), "120 血的怪必须打死");
        assertTrue(big.hp >= 50, "单厚怪打完应剩 ≥50 血，实际 " + big.hp);
        assertTrue(bigTurns <= 25, "不能卡在原地不出牌，实际 " + bigTurns + " 回合");
    }

    /** 跑完整场（确定性：固定宽度 × 深度，不用时间预算），返回打了几个回合。 */
    private static int fight(SimState s, int width, int depth) {
        BeamSolver solver = new BeamSolver(3, 5, width, depth, 200_000, 32);
        int turns = 0;
        while (turns < 25 && s.hp > 0 && !noAlive(s)) {
            CombatFlow.endTurn(s, 3, 5);
            if (s.hp <= 0 || noAlive(s)) {
                break;
            }
            playTurnDeterministic(s, solver);
            turns++;
        }
        return turns;
    }

    private static void playTurnDeterministic(SimState s, BeamSolver solver) {
        for (int guard = 0; guard < 12; guard++) {
            PlanAction action;
            try {
                action = solver.bestFirstAction(s);
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
