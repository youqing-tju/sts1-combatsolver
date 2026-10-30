package io.github.youqing.sts1solver.search;

import io.github.youqing.sts1solver.sim.CombatFlow;
import io.github.youqing.sts1solver.sim.MonsterMove;
import io.github.youqing.sts1solver.sim.MonsterMoves;
import io.github.youqing.sts1solver.sim.RngStream;
import io.github.youqing.sts1solver.sim.SimCard;
import io.github.youqing.sts1solver.sim.SimMonster;
import io.github.youqing.sts1solver.sim.SimState;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 第二部分战斗的**搜索体量**体检（不是断言测试，先看数字）。
 *
 * <p>蓝本（STS2 CombatSolver）实测：第一章每步几十毫秒，**第二章起几步就是几秒到几十秒**，
 * 因为分支、回合数、怪物血量同时上去，250k 节点会被吃满。
 * 我们这边实测全是「几十毫秒 + 2% 节点预算 + tree exhausted」——
 * 说明搜索在第二章**根本没铺开**。这份测试把每一场第二章遭遇的
 * 「节点数 / 状态数 / 搜到第几回合 / 耗时 / 是否撞节点帽」打出来，用来定位原因。
 */
class ActTwoSearchSizeTest {

    private static final int ENERGY = 3;
    private static final int HAND_SIZE = 5;

    /** 一场遭遇：类型 + 血量。 */
    private static final class Encounter {
        final String name;
        final String[] types;
        final int[] hp;

        Encounter(String name, String... typeHp) {
            this.name = name;
            this.types = new String[typeHp.length];
            this.hp = new int[typeHp.length];
            for (int i = 0; i < typeHp.length; i++) {
                int at = typeHp[i].lastIndexOf(':');
                this.types[i] = typeHp[i].substring(0, at);
                this.hp[i] = Integer.parseInt(typeHp[i].substring(at + 1));
            }
        }
    }

    /** 第二章真实遭遇（血量取原版区间代表值）。 */
    private static final Encounter[] ACT_TWO = {
            new Encounter("哨卫球 SphericGuardian", "SphericGuardian:20"),
            new Encounter("被选者 Chosen", "Chosen:95"),
            new Encounter("蛇草 SnakePlant", "SnakePlant:78"),
            new Encounter("甲壳寄生体 ShelledParasite", "ShelledParasite:70"),
            new Encounter("鸟 ×3 Byrd", "Byrd:28", "Byrd:28", "Byrd:28"),
            new Encounter("百夫长+神秘术士", "Centurion:78", "Healer:50"),
            new Encounter("书呆子（精英）", "BookOfStabbing:160"),
            new Encounter("地精头目（精英）", "GremlinLeader:145"),
            new Encounter("奴隶主（精英）", "SlaverRed:48", "Taskmaster:58", "SlaverBlue:48"),
            new Encounter("冠军（Boss）", "Champ:420"),
    };

    /** 第一章打完的典型牌组（25 张，含 3 张升级）。 */
    private static List<SimCard> deck() {
        List<SimCard> cards = new ArrayList<SimCard>();
        for (int i = 0; i < 4; i++) {
            cards.add(new SimCard("Strike_R", false, 1, 6, "ATTACK"));
        }
        for (int i = 0; i < 4; i++) {
            cards.add(new SimCard("Defend_R", false, 1, -1, "SKILL"));
        }
        cards.add(new SimCard("Bash", false, 2, 8, "ATTACK"));
        cards.add(new SimCard("Pommel Strike", true, 1, 10, "ATTACK"));
        cards.add(new SimCard("Shrug It Off", false, 1, -1, "SKILL"));
        cards.add(new SimCard("Iron Wave", false, 1, 5, "ATTACK"));
        cards.add(new SimCard("Uppercut", false, 2, 13, "ATTACK"));
        cards.add(new SimCard("Anger", false, 0, 6, "ATTACK"));
        cards.add(new SimCard("Cleave", true, 1, 11, "ATTACK"));
        cards.add(new SimCard("Headbutt", false, 1, 9, "ATTACK"));
        cards.add(new SimCard("Battle Trance", false, 0, -1, "SKILL"));
        cards.add(new SimCard("Inflame", false, 1, -1, "POWER"));
        cards.add(new SimCard("Metallicize", false, 1, -1, "POWER"));
        cards.add(new SimCard("Searing Blow", false, 2, 12, "ATTACK"));
        cards.add(new SimCard("True Grit", true, 1, -1, "SKILL"));
        cards.add(new SimCard("Dark Embrace", false, 2, -1, "POWER"));
        cards.add(new SimCard("Dropkick", false, 1, 5, "ATTACK"));
        cards.add(new SimCard("Body Slam", true, 0, -1, "ATTACK"));
        cards.add(new SimCard("Sever Soul", false, 2, 16, "ATTACK"));
        return cards;
    }

    private static SimState open(Encounter encounter) {
        SimState s = new SimState();
        s.act = 2;
        s.floor = 25;
        s.hp = 55;
        s.maxHp = 80;
        s.energy = ENERGY;
        s.turn = 1;
        s.draw.addAll(deck());
        s.rng.put(SimState.RngKind.AI, RngStream.from(20261030L, 11L, 0));
        s.rng.put(SimState.RngKind.CARD, RngStream.from(20261030L, 22L, 0));
        s.rng.put(SimState.RngKind.SHUFFLE, RngStream.from(20261030L, 33L, 0));
        s.rng.put(SimState.RngKind.MISC, RngStream.from(20261030L, 44L, 0));
        s.rng.put(SimState.RngKind.MONSTER, RngStream.from(20261030L, 55L, 0));
        s.rng.put(SimState.RngKind.MONSTER_HP, RngStream.from(20261030L, 66L, 0));
        for (int i = 0; i < encounter.types.length; i++) {
            SimMonster m = new SimMonster(encounter.types[i], encounter.hp[i], encounter.hp[i], 0,
                    Collections.<String, Integer>emptyMap());
            s.monsters.add(m);
            MonsterMove first = MonsterMoves.next(s, m, 1, i);
            m.pendingMove = first;
            m.pendingMoveId = first.id;
        }
        CombatFlow.drawCards(s, HAND_SIZE);
        return s;
    }

    /** 用运行时的「极高」档参数：宽 135 / 节点 50 万 / 每节点 72 分支。 */
    @Test
    void reportActTwoSearchVolume() {
        StringBuilder out = new StringBuilder("\n=== 第二章搜索体量（宽 135，节点帽 500k，分支 72）===\n");
        out.append(String.format("%-26s %8s %8s %6s %8s %s%n",
                "遭遇", "节点", "状态", "回合", "毫秒", "撞帽"));
        for (Encounter e : ACT_TWO) {
            SimState s = open(e);
            BeamSolver solver = new BeamSolver(ENERGY, HAND_SIZE, 135, 500, 500_000, 72);
            solver.bestFirstAction(s, 300_000L);
            out.append(String.format("%-26s %8d %8d %6d %8d %s%n",
                    e.name, solver.lastNodes, solver.lastStates, solver.lastDepth,
                    solver.lastMillis, solver.hitNodeLimit ? "是" : "否(树耗尽)"));
        }
        System.out.println(out);
    }

    /**
     * 探顶：宽度放到远超蓝本，看「束宽内整棵树」到底有多大。
     *
     * <p>默认关掉 —— 宽度 2000 那一档单场要 2~6 秒，会拖慢每次全量测试。
     * 要复核「光靠加宽能不能吃满 25 万节点」时手动打开。
     */
    @org.junit.jupiter.api.Disabled("诊断用，会拖慢全量测试")
    @Test
    void reportBeamCeiling() {
        int[] widths = {135, 512, 2000};
        System.out.println("\n=== 束宽探顶（书呆子精英 / 冠军 Boss）===");
        System.out.println(String.format("%-22s %8s %10s %8s %6s %8s",
                "遭遇", "宽度", "节点", "毫秒", "回合", "撞帽"));
        for (int w : widths) {
            for (int idx : new int[]{6, 9}) {
                Encounter e = ACT_TWO[idx];
                SimState s = open(e);
                BeamSolver solver = new BeamSolver(ENERGY, HAND_SIZE, w, 500, 200_000, 72);
                solver.bestFirstAction(s, 120_000L);
                System.out.println(String.format("%-22s %8d %10d %8d %6d %8s",
                        e.name, w, solver.lastNodes, solver.lastMillis, solver.lastDepth,
                        solver.hitNodeLimit ? "是" : "否"));
                System.out.flush();
            }
        }
    }
}
