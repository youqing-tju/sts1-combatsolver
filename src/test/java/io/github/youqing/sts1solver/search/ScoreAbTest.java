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
 * <p>跑法：{@code gradle test -Pexperiments -i}，然后看日志里的 {@code [ab]} 行
 * （CI 不传 {@code -Pexperiments} 就不会跑）。
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

    @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named = "sts1.experiments", matches = "true", disabledReason = "离线实验（gradle test -Pexperiments 才跑）")
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

    /** 宽度扫：树在宽度 60 就搜完了，加宽还有没有收益？ */
    @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named = "sts1.experiments", matches = "true", disabledReason = "离线实验（gradle test -Pexperiments 才跑）")
    @Test
    void widthSweep() {
        System.out.println("[ab] ===== 宽度扫（最多 16 回合，节点上限 20 万） =====");
        System.out.println("[ab] 宽度 | 场面 | 回合 | 血量 | 节点 | 存活");
        int[] widths = {30, 60, 135, 240, 512};
        for (int w : widths) {
            System.out.println("[ab] " + w + " | 双教徒 | " + run(twoCultists(), w, 16));
            System.out.println("[ab] " + w + " | 单厚怪 | " + run(bigSingle(), w, 16));
        }
    }

    /** 预算用量：回合层不设上限后，一次决策到底用掉多少预算、树有没有被搜完。 */
    @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named = "sts1.experiments", matches = "true", disabledReason = "离线实验（gradle test -Pexperiments 才跑）")
    @Test
    void budgetUsage() {
        System.out.println("[ab] ===== 预算用量（回合层不设上限，节点上限 12 万，标准档宽度 60） =====");
        System.out.println("[ab] 宽度 | 场面 | 回合层 | 展开 | 生成状态 | 上限命中 | ms");
        for (int width : new int[]{60, 135, 240}) {
            for (int scene = 0; scene < 2; scene++) {
                SimState s = scene == 0 ? twoCultists() : bigSingle();
                CombatFlow.endTurn(s, 3, 5);
                BeamSolver solver = new BeamSolver(3, 5, width,
                        BeamSolver.SAFETY_MAX_TURN_LAYERS, 120_000, 32);
                solver.bestFirstAction(s, Long.MAX_VALUE);
                System.out.printf("[ab] %4d | %s | %5d | %6d | %8d | %s | %4d%n",
                        width, scene == 0 ? "双教徒" : "单厚怪",
                        solver.lastDepth, solver.lastNodes, solver.lastStates,
                        solver.hitNodeLimit ? "是" : "否", solver.lastMillis);
            }
        }
    }

    /** Boss 房把「保留回合层数」从 4 抬到 8：看每层节点预算变小后覆盖有什么变化。 */
    @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named = "sts1.experiments", matches = "true", disabledReason = "离线实验（gradle test -Pexperiments 才跑）")
    @Test
    void bossReservedLayers() {
        System.out.println("[ab] ===== Boss 房保留层数（4 → 8） =====");
        System.out.println("[ab] boss | 节点上限 | 场面 | 回合层 | 展开 | ms");
        for (boolean boss : new boolean[]{false, true}) {
            for (int limit : new int[]{2_000, 10_000}) {
                SimState s = twoCultists();
                s.boss = boss;
                CombatFlow.endTurn(s, 3, 5);
                BeamSolver solver = new BeamSolver(3, 5, 60, 16, limit, 32);
                solver.bestFirstAction(s, Long.MAX_VALUE);
                System.out.printf("[ab] %s | %6d | 双教徒 | %2d | %6d | %4d%n",
                        boss, limit, solver.lastDepth, solver.lastNodes, solver.lastMillis);
            }
        }
    }

    /** 带「能力牌 + 成长攻击」的牌组：验证 Beam 项与「掉血二次计价」的交互。 */
    @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named = "sts1.experiments", matches = "true", disabledReason = "离线实验（gradle test -Pexperiments 才跑）")
    @Test
    void beamTermsWithPowerDeck() {
        System.out.println("[ab] ===== Beam 项 / 掉血二次计价 A/B（牌组含恶魔形态 / 暴走 / 愤怒） =====");
        System.out.println("[ab] baseOnly | dmgWeight | 场面 | 回合 | 血量 | 恶魔形态 | 存活");
        for (boolean baseOnly : new boolean[]{true, false}) {
            for (double dmg : new double[]{0d, 1d}) {
                Score.baseScoreOnly = baseOnly;
                Score.damageTakenWeight = dmg;
                for (int scene = 0; scene < 2; scene++) {
                    SimState s = scene == 0 ? powerDeck(true) : powerDeck(false);
                    System.out.println("[ab] " + baseOnly + " | " + dmg + " | "
                            + (scene == 0 ? "双教徒" : "单厚怪") + " | " + run(s, 60, 16)
                            + " | 恶魔 " + s.playerPowers.getOrDefault("Demon Form", 0));
                }
            }
        }
        Score.resetKnobs();
    }

    /** 卡组：标准起手 + 恶魔形态 + 暴走 + 愤怒。 */
    private static SimState powerDeck(boolean multi) {
        SimState s = multi ? twoCultists() : bigSingle();
        s.draw.add(new SimCard("Demon Form", false, 3, 0, "POWER"));
        s.draw.add(new SimCard("Rampage", false, 1, 8, "ATTACK"));
        s.draw.add(new SimCard("Anger", false, 0, 6, "ATTACK"));
        return s;
    }

    /** 全场里「暴走」的卡面伤害（打过就会涨），用来判断 AI 有没有反复用成长攻击。 */
    private static int rampageDamage(SimState s) {
        int best = 0;
        for (java.util.List<SimCard> pile
                : java.util.Arrays.asList(s.hand, s.draw, s.discard, s.exhaust)) {
            for (SimCard c : pile) {
                if ("Rampage".equals(c.id)) {
                    best = Math.max(best, c.baseDamage);
                }
            }
        }
        return best;
    }

    /** 回合层覆盖：给定节点预算，搜索能推进到第几个回合层、花多少毫秒。 */
    @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named = "sts1.experiments", matches = "true", disabledReason = "离线实验（gradle test -Pexperiments 才跑）")
    @Test
    void turnLayerCoverage() {
        System.out.println("[ab] ===== 回合层覆盖（单次决策，宽度 60，最多 16 回合） =====");
        System.out.println("[ab] 节点上限 | 场面 | 回合层 | 展开 | 生成状态 | ms");
        int[] limits = {2_000, 10_000, 40_000, 120_000};
        for (int limit : limits) {
            for (int scene = 0; scene < 2; scene++) {
                SimState s = scene == 0 ? twoCultists() : bigSingle();
                CombatFlow.endTurn(s, 3, 5);
                BeamSolver solver = new BeamSolver(3, 5, 60, 16, limit, 32);
                solver.bestFirstAction(s, Long.MAX_VALUE);
                System.out.printf("[ab] %8d | %s | %5d | %6d | %8d | %4d%n",
                        limit, scene == 0 ? "双教徒" : "单厚怪",
                        solver.lastDepth, solver.lastNodes, solver.lastStates,
                        solver.lastMillis);
            }
        }
    }

    @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named = "sts1.experiments", matches = "true", disabledReason = "离线实验（gradle test -Pexperiments 才跑）")
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
