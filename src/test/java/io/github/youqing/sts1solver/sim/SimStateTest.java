package io.github.youqing.sts1solver.sim;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SimStateTest {

    private static SimState sample() {
        SimState s = new SimState();
        s.act = 1;
        s.floor = 1;
        s.ascension = 0;
        s.hp = 80;
        s.maxHp = 80;
        s.block = 0;
        s.energy = 4;
        s.playerPowers.put("Strength", 2);
        s.hand.add(new SimCard("Strike_R", false, 1));
        s.hand.add(new SimCard("Bash", true, 2));
        s.draw.add(new SimCard("Defend_R", false, 1));
        s.relics.put("Ectoplasm", -1);
        s.monsters.add(new SimMonster("AcidSlime_S", 12, 12, 0,
                Collections.<String, Integer>emptyMap()));
        s.rng.put(SimState.RngKind.CARD, RngStream.from(1L, 2L, 0));
        return s;
    }

    @Test
    void copyIsDeepAndDiffClean() {
        SimState original = sample();
        SimState copy = original.copy();
        assertTrue(original.diff(copy).isEmpty(), "a fresh copy must be field-identical");
        assertEquals(original.fingerprint(), copy.fingerprint(), "and hash the same");
    }

    @Test
    void mutatingTheCopyNeverTouchesTheOriginal() {
        SimState original = sample();
        SimState copy = original.copy();

        copy.hp = 70;
        copy.energy = 1;
        copy.playerPowers.put("Strength", 5);
        copy.hand.remove(0);
        copy.monsters.get(0).hp = 3;

        assertEquals(80, original.hp, "original hp must stay");
        assertEquals(4, original.energy, "original energy must stay");
        assertEquals(2, (int) original.playerPowers.get("Strength"), "powers are copied");
        assertEquals(2, original.hand.size(), "card list is copied");
        assertEquals(12, original.monsters.get(0).hp, "monster is copied");
    }

    @Test
    void diffNamesEveryChangedField() {
        SimState a = sample();
        SimState b = a.copy();
        b.hp = 71;
        b.draw.add(new SimCard("Strike_R", false, 1));
        b.relics.put("Ectoplasm", 3);
        b.rng.put(SimState.RngKind.CARD, RngStream.from(1L, 2L, 7));

        List<String> diffs = a.diff(b);
        assertTrue(diffs.toString().contains("hp: 80 vs 71"), diffs.toString());
        assertTrue(diffs.toString().contains("draw.size: 1 vs 2"), diffs.toString());
        assertTrue(diffs.toString().contains("relics.Ectoplasm"), diffs.toString());
        assertTrue(diffs.toString().contains("rng.CARD"), diffs.toString());
    }

    @Test
    void fingerprintFollowsContent() {
        SimState a = sample();
        SimState b = sample();
        assertEquals(a.fingerprint(), b.fingerprint(), "same content, same fingerprint");

        b.hp = 79;
        assertNotEquals(a.fingerprint(), b.fingerprint(), "one field changed, fingerprint changes");
    }

    @Test
    void cardKeyCarriesUpgradeState() {
        assertEquals("Bash+", new SimCard("Bash", true, 2).key());
        assertEquals("Bash", new SimCard("Bash", false, 2).key());
    }

    /**
     * 手牌换序**必须**报差（2026-11-02 反向合同，以前这里钉的是「换序不算差」）。
     *
     * <p>原版 `MadnessAction` 按**手牌下标**抽牌（`MathUtils.random(hand.size()-1)`），
     * 同样三张牌换个顺序，被降费的那张就换人了 —— 后续整条世界线不同。
     * 手牌顺序也参与续跑戳（`ContinuationStamp` v2 起保序）。
     * 把它当无序多重集比较，等于把「随机下标落错位置」静默吞掉。
     */
    @Test
    void handOrderIsADifference() {
        SimState a = sample();
        SimState b = a.copy();
        java.util.Collections.reverse(b.hand);

        assertFalse(a.diff(b).isEmpty(),
                "different hand order changes which card Madness picks: " + a.diff(b));
    }

    @Test
    void drawOrderStillIsADifference() {
        SimState a = sample();
        SimState b = a.copy();
        b.draw.add(0, new SimCard("Strike_R", false, 1));

        assertTrue(a.diff(b).toString().contains("draw.size"), "draw pile order/length matters");
    }

    @Test
    void deadMonsterPresenceIsIgnoredAfterNormalisation() {
        // 影子保留 0 血怪物，游戏在死亡动画结束后才移除 —— 两边描述的是同一件事
        SimState shadow = sample();
        shadow.monsters.get(0).hp = 0;

        SimState live = sample();
        live.monsters.clear();

        assertTrue(shadow.diff(live).toString().contains("monsters.size"),
                "raw diff still sees the length difference");
        assertTrue(shadow.dropDeadMonsters().diff(live.dropDeadMonsters()).isEmpty(),
                "after dropping 0-HP monsters the two sides agree");
    }
}
