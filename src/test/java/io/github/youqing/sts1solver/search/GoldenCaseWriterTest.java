package io.github.youqing.sts1solver.search;

import io.github.youqing.sts1solver.sim.CombatFlow;
import io.github.youqing.sts1solver.sim.MonsterMove;
import io.github.youqing.sts1solver.sim.MonsterMoves;
import io.github.youqing.sts1solver.sim.RngStream;
import io.github.youqing.sts1solver.sim.SimCard;
import io.github.youqing.sts1solver.sim.SimMonster;
import io.github.youqing.sts1solver.sim.SimState;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.OutputStreamWriter;
import java.io.FileOutputStream;
import java.util.Collections;

/**
 * 生成「金标准局面库」的初始用例（一次性工具，默认关）。
 *
 * <p>为什么要生成而不是手写 JSON：手写会写错字段，而局面错一个字段，判据就失效。
 * 这里用和测试同源的构造代码造局面，再走 {@link SimState#toJson()} 落盘，
 * 顺便断言「写出去→读回来」指纹不变（= 格式无损），这样库里的局面可以放心当基准。
 *
 * <p>用法：把 {@code @Disabled} 去掉跑一次，文件写到 {@code src/test/resources/golden/}，
 * 之后它们就是仓库里的固定资产，靠 {@link GoldenPositionsTest} 判断改动好坏。
 *
 * <p>真机局面进来只需要一步：把 {@code solver-snapshots/combat-*.json}（新格式）复制进同一个目录。
 */
class GoldenCaseWriterTest {

    private static final int ENERGY = 3;
    private static final int HAND_SIZE = 5;
    private static final File DIR = new File("src/test/resources/golden");

    /** 开局牌组（15 张），与连环战测试同源。 */
    private static void starterDeck(SimState s) {
        for (int i = 0; i < 5; i++) {
            s.draw.add(new SimCard("Strike_R", false, 1, 6, "ATTACK"));
        }
        for (int i = 0; i < 4; i++) {
            s.draw.add(new SimCard("Defend_R", false, 1, -1, "SKILL"));
        }
        s.draw.add(new SimCard("Bash", false, 2, 8, "ATTACK"));
        s.draw.add(new SimCard("Pommel Strike", false, 1, 9, "ATTACK"));
        s.draw.add(new SimCard("Shrug It Off", false, 1, -1, "SKILL"));
        s.draw.add(new SimCard("Iron Wave", false, 1, 5, "ATTACK"));
        s.draw.add(new SimCard("Uppercut", false, 2, 13, "ATTACK"));
    }

    private static SimState open(String... typeHp) {
        SimState s = new SimState();
        s.act = 1;
        s.floor = 1;
        s.hp = 80;
        s.maxHp = 80;
        s.energy = ENERGY;
        s.turn = 1;
        s.draw.addAll(java.util.Arrays.asList());
        starterDeck(s);
        s.rng.put(SimState.RngKind.AI, RngStream.from(20261030L, 11L, 0));
        s.rng.put(SimState.RngKind.CARD, RngStream.from(20261030L, 22L, 0));
        s.rng.put(SimState.RngKind.SHUFFLE, RngStream.from(20261030L, 33L, 0));
        s.rng.put(SimState.RngKind.MISC, RngStream.from(20261030L, 44L, 0));
        s.rng.put(SimState.RngKind.MONSTER, RngStream.from(20261030L, 55L, 0));
        s.rng.put(SimState.RngKind.MONSTER_HP, RngStream.from(20261030L, 66L, 0));
        s.relics.put("Burning Blood", Integer.valueOf(-1));
        for (int i = 0; i < typeHp.length; i++) {
            int at = typeHp[i].lastIndexOf(':');
            SimMonster m = new SimMonster(typeHp[i].substring(0, at),
                    Integer.parseInt(typeHp[i].substring(at + 1)),
                    Integer.parseInt(typeHp[i].substring(at + 1)), 0,
                    Collections.<String, Integer>emptyMap());
            s.monsters.add(m);
            MonsterMove first = MonsterMoves.next(s, m, 1, i);
            m.pendingMove = first;
            m.pendingMoveId = first.id;
        }
        CombatFlow.drawCards(s, HAND_SIZE);
        return s;
    }

    private static void write(String name, SimState s) throws Exception {
        // 无损校验：写出去再读回来，指纹必须一模一样
        String json = s.toJson();
        SimState back = GoldenPositions.read(writeTemp(json));
        if (!back.fingerprint().equals(s.fingerprint())) {
            throw new IllegalStateException(name + ": round-trip fingerprint mismatch\n"
                    + s.fingerprint() + " vs " + back.fingerprint() + "\n" + json);
        }
        if (!DIR.exists() && !DIR.mkdirs()) {
            throw new IllegalStateException("cannot create " + DIR.getAbsolutePath());
        }
        File out = new File(DIR, name + ".json");
        OutputStreamWriter w = new OutputStreamWriter(new FileOutputStream(out), "UTF-8");
        try {
            w.write(json);
        } finally {
            w.close();
        }
        System.out.println("wrote " + out.getPath() + "  " + GoldenPositions.describe(back));
    }

    private static File writeTemp(String json) throws Exception {
        File f = File.createTempFile("golden", ".json");
        f.deleteOnExit();
        OutputStreamWriter w = new OutputStreamWriter(new FileOutputStream(f), "UTF-8");
        try {
            w.write(json);
        } finally {
            w.close();
        }
        return f;
    }

    @org.junit.jupiter.api.Disabled("一次性生成工具：要重建金标准库时手动跑")
    @Test
    void writeGoldenCases() throws Exception {
        write("act1-jawworm-open", open("JawWorm:44"));
        write("act1-cultist-open", open("Cultist:50"));
        write("act1-gremlinnob-open", open("GremlinNob:85"));
        write("act1-sentries-open", open("Sentry:39", "Sentry:39", "Sentry:39"));
    }
}
