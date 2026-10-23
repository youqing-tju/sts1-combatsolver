package io.github.youqing.sts1solver.search;

import io.github.youqing.sts1solver.mirror.CardPlayer;
import io.github.youqing.sts1solver.sim.CombatFlow;
import io.github.youqing.sts1solver.sim.SimCard;
import io.github.youqing.sts1solver.sim.SimMonster;
import io.github.youqing.sts1solver.sim.SimState;
import org.junit.jupiter.api.Test;

import java.util.Collections;

/**
 * 离线估值 A/B 台架（**确定性**，默认不跑，别进 CI）。
 *
 * <p>⚠️ 2026-10-23 教训：上一版把「每次决策的时间预算」当变量，同一配置两次跑出不同结果
 * （5ms/60ms/300ms 三档的差异大半是机器负载噪声，还出现过「预算越大越早死」的假象）。
 * 这里改成固定「宽度 × 深度」，同一个输入必然给出同一个输出，A/B 才有意义。
 *
 * <p>跑法：临时去掉 {@code @Disabled}，或
 * {@code gradle test --tests "*ScoreAbTest*" -i} 后看日志里的 {@code [ab]} 行。
 */
class ScoreAbTest {

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
        return s;
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

    private static boolean noAlive(SimState s) {
        for (SimMonster m : s.monsters) {
            if (m.hp > 0) {
                return false;
            }
        }
        return true;
    }

    private static void playTurn(SimState s, BeamSolver solver) {
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

    /** @return "回合 | 血量 | 节点 | 存活" */
    private static String run(SimState s, int width, int depth) {
        BeamSolver solver = new BeamSolver(3, 5, width, depth, 200_000, 32);
        long nodes = 0;
        int turns = 0;
        while (turns < 25 && s.hp > 0 && !noAlive(s)) {
            CombatFlow.endTurn(s, 3, 5);
            if (s.hp <= 0 || noAlive(s)) {
                break;
            }
            playTurn(s, solver);
            nodes += solver.lastNodes;
            turns++;
        }
        return String.format("%3d | %5d | %7d | %s",
                turns, Math.max(0, s.hp), nodes, s.hp > 0 ? "存活" : "DEAD");
    }

    @org.junit.jupiter.api.Disabled("离线实验（约 30 秒）：旧估值 vs 蓝本线性加权，别进 CI")
    @Test
    void deterministicTable() {
        int[][] shapes = {{20, 20}, {60, 60}, {120, 80}};
        String[] scenes = {"双教徒 40+40", "单厚怪 120"};

        System.out.println("[ab] ===== 旧估值（字典序，1 血 = 1000 敌方血） =====");
        System.out.println("[ab] 宽度x深度 | 场面 | 回合 | 血量 | 节点 | 存活");
        Score.legacyScoring = true;
        for (int[] sh : shapes) {
            System.out.println("[ab] " + sh[0] + "x" + sh[1] + " | " + scenes[0] + " | "
                    + run(twoCultists(), sh[0], sh[1]));
            System.out.println("[ab] " + sh[0] + "x" + sh[1] + " | " + scenes[1] + " | "
                    + run(bigSingle(), sh[0], sh[1]));
        }
        Score.resetKnobs();

        System.out.println("[ab] ===== 新估值（蓝本线性加权，1 血 = 3 敌方血） =====");
        System.out.println("[ab] 宽度x深度 | 场面 | 回合 | 血量 | 节点 | 存活");
        for (int[] sh : shapes) {
            System.out.println("[ab] " + sh[0] + "x" + sh[1] + " | " + scenes[0] + " | "
                    + run(twoCultists(), sh[0], sh[1]));
            System.out.println("[ab] " + sh[0] + "x" + sh[1] + " | " + scenes[1] + " | "
                    + run(bigSingle(), sh[0], sh[1]));
        }
    }

    @org.junit.jupiter.api.Disabled("离线实验：能量 Beam 分 / 掉血二次计价 的开关扫")
    @Test
    void knobSweep() {
        System.out.println("[ab] ===== 开关扫（宽度 60 x 深度 60） =====");
        System.out.println("[ab] energy | dmgWeight | 场面 | 回合 | 血量 | 节点 | 存活");
        double[] energies = {60_000d, 0d};
        double[] dmgs = {0d, 1d};
        for (double e : energies) {
            for (double d : dmgs) {
                Score.energyBeamValue = e;
                Score.damageTakenWeight = d;
                System.out.println("[ab] " + (long) e + " | " + d + " | 双教徒 | "
                        + run(twoCultists(), 60, 60));
                System.out.println("[ab] " + (long) e + " | " + d + " | 单厚怪 | "
                        + run(bigSingle(), 60, 60));
            }
        }
        Score.resetKnobs();
    }
}
