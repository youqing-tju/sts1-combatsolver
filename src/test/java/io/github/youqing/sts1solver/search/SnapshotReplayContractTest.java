package io.github.youqing.sts1solver.search;

import io.github.youqing.sts1solver.sim.MonsterMove;
import io.github.youqing.sts1solver.sim.RngStream;
import io.github.youqing.sts1solver.sim.SimCard;
import io.github.youqing.sts1solver.sim.SimMonster;
import io.github.youqing.sts1solver.sim.SimState;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 快照 round-trip 合同：**写出去的状态读回来必须还是同一个状态**。
 *
 * <p>为什么这条合同是跨回合命中的前提：{@link ContinuationStamp} 逐字段比较，
 * 而它比的一些字段（`turn` / `hpLossCount` / `turnForcedEnd` / `potionSlots` /
 * 怪物 `invincibleMax` / `stolenGold` / `damageBase` / `moveHistory` / `pendingMove` 全载荷）
 * 以前根本没进 {@link SimState#toJson()}。于是「从快照读回局面再预测」与「真机当前局面」
 * 必然不等，续跑永远是 MISS —— 与镜像准不准无关，是证据本身缺字段。
 *
 * <p>这条测试**故意构造每个字段都非默认值**的状态：只要有一个字段没被序列化，
 * 读回来就是默认值，戳就不等，测试立刻红。用全默认状态是测不出来的。
 */
public class SnapshotReplayContractTest {

    @Test
    public void fullSnapshotRoundTripsIntoTheSameContinuationStamp() throws Exception {
        SimState original = richState();
        File file = File.createTempFile("snapshot-contract-", ".json");
        file.deleteOnExit();
        Files.write(file.toPath(), original.toJson().getBytes(StandardCharsets.UTF_8));

        SimState readBack = GoldenPositions.readStrict(file);

        String expected = ContinuationStamp.captureLive(original).canonicalText();
        String actual = ContinuationStamp.captureLive(readBack).canonicalText();
        assertEquals(expected, actual, firstDifference(expected, actual));
    }

    /**
     * 严格模式不许给缺失字段补默认值：少一个 `turn` 就必须失败。
     *
     * <p>「补 0 再算」是这一类 bug 的老家 —— 读回来的局面看起来能用，其实与真机不是同一局。
     */
    @Test
    public void strictReaderRejectsSnapshotMissingV5Fields() throws Exception {
        SimState original = richState();
        String json = original.toJson().replace("\"turn\":7,", "");
        File file = File.createTempFile("snapshot-missing-turn-", ".json");
        file.deleteOnExit();
        Files.write(file.toPath(), json.getBytes(StandardCharsets.UTF_8));

        try {
            GoldenPositions.readStrict(file);
            fail("strict reader accepted a snapshot without player.turn");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("turn"), expected.getMessage());
        }
    }

    /** 老快照（无 v5 字段）仍要能读：路线质量 A/B 的 173 个文件就是这种。 */
    @Test
    public void legacyReaderStillAcceptsOldSnapshotsWithoutV5Fields() throws Exception {
        SimState original = richState();
        String json = original.toJson()
                .replace("\"turn\":7,", "")
                .replace("\"hpLossCount\":3,", "")
                .replace("\"turnForcedEnd\":true,", "");
        File file = File.createTempFile("snapshot-legacy-", ".json");
        file.deleteOnExit();
        Files.write(file.toPath(), json.getBytes(StandardCharsets.UTF_8));

        SimState legacy = GoldenPositions.read(file);
        assertEquals(1, legacy.turn);
        assertEquals(0, legacy.hpLossCount);
    }

    // ------------------------------------------------------------------ fixture

    /** 每个字段都刻意取非默认值：任何一个字段漏序列化，戳就会不等。 */
    private static SimState richState() {
        SimState s = new SimState();
        s.act = 4;
        s.floor = 55;
        s.ascension = 5;
        s.boss = true;
        s.turn = 7;
        s.hpLossCount = 3;
        s.turnForcedEnd = true;
        s.potionSlots = 3;

        s.hp = 41;
        s.maxHp = 80;
        s.block = 13;
        s.energy = 2;
        s.energyPerTurn = 4;
        s.gold = 36;
        s.deathSaveUsed = true;
        s.deathSaveUseCount = 1;
        s.deathSaveHpRestored = 22;
        s.feedGrowth = 2;
        s.handOfGreedGrowth = 1;
        s.longTermResourceValue = 25;
        s.angerCopiesGenerated = 4;
        s.cumulativeHpLost = 21;
        s.recoveredPlayerHp = 6;

        s.playerPowers.put("Strength", Integer.valueOf(18));
        s.playerPowers.put("Brutality", Integer.valueOf(1));
        s.playerPowers.put("Dark Embrace", Integer.valueOf(1));
        s.powerAux.put("Combust", Integer.valueOf(7));

        s.hand.add(new SimCard("Bash", true, 2, 12, "ATTACK", 0));
        s.hand.add(new SimCard("Wound", false, -2, -1, "STATUS", -1));
        s.draw.add(new SimCard("Strike_R", false, 1, 6, "ATTACK", -1));
        s.discard.add(new SimCard("Defend_R", true, 1, -1, "SKILL", -1));
        s.exhaust.add(new SimCard("Intimidate", true, 0, -1, "SKILL", -1));

        s.relics.put("Pen Nib", Integer.valueOf(9));
        s.relics.put("Lizard Tail", Integer.valueOf(-2));

        s.potions.add("Fire Potion");
        s.potions.add(null);
        s.potions.add("Block Potion");

        SimMonster heart = new SimMonster("CorruptHeart", 384, 750, 12,
                new java.util.TreeMap<String, Integer>(java.util.Collections.singletonMap(
                        "BeatOfDeath", Integer.valueOf(1))));
        heart.intentDmg = 45;
        heart.intentAttack = true;
        heart.pendingMoveId = 3;
        heart.invincibleMax = 297;
        heart.stolenGold = 17;
        heart.damageBase.add(Integer.valueOf(45));
        heart.damageBase.add(Integer.valueOf(2));
        heart.moveHistory.add(Integer.valueOf(1));
        heart.moveHistory.add(Integer.valueOf(3));
        heart.pendingMove = new MonsterMove(3, 45, 1, 0, 0,
                "Strength", 2, "Frail", 2, "Vulnerable", 2,
                "Dazed", 2, "SpikeSlime_L", 1, "AcidSlime_L", 2, false, false,
                5, "Ritual", 1,
                MonsterMove.Extra.restore(null, "SpikeSlime_L", 6, 4, "Weak", 3,
                        true, "Burn", 1, true, "Intangible", 1, true, true,
                        new String[]{"Dazed", "Slimed", "Wound", "Burn", "Void"},
                        "Strength", 3));
        s.monsters.add(heart);

        SimMonster looter = new SimMonster("Looter", 44, 44, 0,
                new java.util.TreeMap<String, Integer>());
        looter.intentDmg = 10;
        looter.intentAttack = true;
        looter.pendingMoveId = 2;
        looter.stolenGold = 15;
        s.monsters.add(looter);

        for (SimState.RngKind kind : SimState.RngKind.values()) {
            s.rng.put(kind, RngStream.from(0x1234_5678_9abc_def0L + kind.ordinal(),
                    0x0fed_cba9_8765_4321L - kind.ordinal(), 11 + kind.ordinal()));
        }
        return s;
    }

    /** 失败时给出第一个不同的字段，而不是两段几千字符的文本。 */
    private static String firstDifference(String expected, String actual) {
        String[] a = expected.split(";");
        String[] b = actual.split(";");
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            String left = i < a.length ? a[i] : "(missing)";
            String right = i < b.length ? b[i] : "(missing)";
            if (!left.equals(right)) {
                return "first stamp difference at field #" + i + ": " + left
                        + " vs " + right;
            }
        }
        return "no difference";
    }
}
