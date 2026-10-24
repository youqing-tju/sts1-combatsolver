package io.github.youqing.sts1solver.sim;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 指纹换成「免拼字符串的直接哈希」后的**口径对拍**（2026-10-24）。
 *
 * <p>为什么这么测：换算法本身很安全，危险的是**漏字段** —— 漏一个就是静默撞库，
 * 两条不同的路线被当成同一条，搜索会莫名其妙丢分支，而且不报错、不崩。
 * 所以判据不是「哈希值相等」（算法变了本来就不同），而是**等价类相等**：
 * 对语料里的任意两个状态，老口径 {@code fingerprintOf(toJson())} 说「同一个」⟺ 新口径也说「同一个」。
 *
 * <p>语料覆盖 {@code toJson} 里的每一个字段（meta / player 九项 / 两个 map / 四个牌堆 /
 * relics / potions / monsters 五项 / rng 三段），每个字段都造「改值」或「换序」的变体。
 */
class SimStateFingerprintTest {

    private static final String FIRE = "Fire Potion";
    private static final String BLOCK = "Block Potion";

    private static SimState base() {
        SimState s = new SimState();
        s.act = 2;
        s.floor = 17;
        s.ascension = 10;
        s.hp = 54;
        s.maxHp = 80;
        s.block = 6;
        s.energy = 3;
        s.hand.add(new SimCard("Strike_R", false, 1, 6, "ATTACK"));
        s.hand.add(new SimCard("Bash", true, 2, 10, "ATTACK"));
        s.draw.add(new SimCard("Defend_R", false, 1, -1, "SKILL"));
        s.draw.add(new SimCard("Strike_R", false, 1, 6, "ATTACK"));
        s.discard.add(new SimCard("Pommel Strike", false, 1, 9, "ATTACK"));
        s.exhaust.add(new SimCard("Offering", false, 0, -1, "SKILL"));
        s.playerPowers.put("Strength", 2);
        s.powerAux.put("TheBomb.damage", 40);
        s.relics.put("Burning Blood", 1);
        s.potions.add(null);
        s.potions.add(FIRE);
        SimMonster first = new SimMonster("Cultist", 44, 50, 0,
                Collections.<String, Integer>emptyMap());
        first.intentDmg = 6;
        SimMonster second = new SimMonster("Louse", 12, 15, 2,
                Collections.<String, Integer>emptyMap());
        second.intentDmg = 5;
        s.monsters.add(first);
        s.monsters.add(second);
        s.rng.put(SimState.RngKind.MONSTER, RngStream.from(11L, 22L, 3));
        return s;
    }

    private static SimState copy() {
        return base().copy();
    }

    private static List<SimState> corpus() {
        List<SimState> states = new ArrayList<SimState>();
        states.add(base());
        states.add(copy());                                  // 内容完全相同 → 必须同指纹

        SimState s;
        s = copy(); s.act = 3; states.add(s);
        s = copy(); s.floor = 18; states.add(s);
        s = copy(); s.ascension = 11; states.add(s);
        s = copy(); s.hp = 53; states.add(s);
        s = copy(); s.maxHp = 81; states.add(s);
        s = copy(); s.block = 9; states.add(s);
        s = copy(); s.energy = 4; states.add(s);
        s = copy(); s.deathSaveUsed = true; states.add(s);
        s = copy(); s.deathSaveUseCount = 1; states.add(s);
        s = copy(); s.deathSaveHpRestored = 7; states.add(s);

        s = copy(); s.playerPowers.put("Strength", 3); states.add(s);
        s = copy(); s.playerPowers.put("Dexterity", 1); states.add(s);
        s = copy();                                          // 同两项、换施加顺序 → 老口径也不同
        s.playerPowers.clear();
        s.playerPowers.put("Dexterity", 1);
        s.playerPowers.put("Strength", 2);
        states.add(s);

        s = copy(); s.powerAux.put("TheBomb.damage", 50); states.add(s);
        s = copy(); s.powerAux.put("Panache.damage", 10); states.add(s);

        s = copy(); s.hand.set(0, new SimCard("Strike_R", true, 1, 9, "ATTACK")); states.add(s);
        s = copy();                                          // 换手牌顺序
        Collections.swap(s.hand, 0, 1);
        states.add(s);
        s = copy(); s.hand.remove(1); states.add(s);
        s = copy(); s.draw.remove(0); states.add(s);
        s = copy(); s.discard.add(new SimCard("Bash", false, 2, 8, "ATTACK")); states.add(s);
        s = copy(); s.exhaust.clear(); states.add(s);

        s = copy(); s.relics.put("Vajra", 1); states.add(s);
        s = copy(); s.relics.put("Burning Blood", 2); states.add(s);

        s = copy(); s.potions.clear(); s.potions.add(FIRE); s.potions.add(null); states.add(s);
        s = copy(); s.potions.clear(); s.potions.add(FIRE); states.add(s);
        s = copy(); s.potions.set(0, BLOCK); states.add(s);
        s = copy(); s.potions.set(1, null); states.add(s);

        s = copy(); s.monsters.get(0).hp = 43; states.add(s);
        s = copy();
        s.monsters.set(0, new SimMonster("Cultist", 44, 51, 0,      // maxHp 是 final，换一只
                Collections.<String, Integer>emptyMap()));
        states.add(s);
        s = copy(); s.monsters.get(0).block = 4; states.add(s);
        s = copy(); s.monsters.get(0).powers.put("Vulnerable", 2); states.add(s);
        s = copy();
        s.monsters.set(0, new SimMonster("Cultist2", 44, 50, 0,     // type 也是 final
                Collections.<String, Integer>emptyMap()));
        states.add(s);
        s = copy(); s.monsters.get(0).hp = 44; s.monsters.get(1).hp = 13; states.add(s);
        s = copy();                                          // 两只怪换序
        Collections.swap(s.monsters, 0, 1);
        states.add(s);

        s = copy(); s.rng.put(SimState.RngKind.SHUFFLE, RngStream.from(7L, 9L, 1)); states.add(s);
        s = copy();
        s.rng.put(SimState.RngKind.SHUFFLE, RngStream.from(7L, 9L, 2));
        states.add(s);
        s = copy();
        s.rng.put(SimState.RngKind.MONSTER, RngStream.from(11L, 22L, 4));
        states.add(s);
        s = copy(); s.rng.clear(); states.add(s);

        // 2026-10-24 补进指纹的三个字段（原来只在 toJson 之外，会被误判成同一局面）
        s = copy(); s.turn = 7; states.add(s);
        s = copy(); s.hpLossCount = 3; states.add(s);
        s = copy(); s.potionSlots = 3; states.add(s);
        return states;
    }

    /** 老口径：JSON 字符串再 FNV（换算法之前就是这一段）。 */
    private static String legacyFingerprint(SimState state) {
        return SimState.fingerprintOf(state.toJson());
    }

    /**
     * 核心判据：新口径只能**更细**，不能更粗。
     *
     * <p>2026-10-24 补进 `turn` / `hpLossCount` / `potionSlots` 之后，等价类**故意**变细了
     * （原来「同血量、不同挨打次数」会被当成同一局面，那是静默算错）。所以判据改成单向：
     *
     * <pre>
     * 新口径说「同一个」 ⇒ 老口径也必须说「同一个」
     * </pre>
     *
     * 反过来不成立 —— 只允许在那三个补进去的字段上分得更细，别的地方必须一致。
     */
    @Test
    void newFingerprintIsNeverCoarserThanTheOldOne() {
        List<SimState> states = corpus();
        assertTrue(states.size() >= 30, "语料太小，覆盖不到全部字段：" + states.size());
        for (int i = 0; i < states.size(); i++) {
            for (int j = i + 1; j < states.size(); j++) {
                boolean legacySame = legacyFingerprint(states.get(i))
                        .equals(legacyFingerprint(states.get(j)));
                boolean currentSame = states.get(i).fingerprint()
                        .equals(states.get(j).fingerprint());
                if (currentSame) {
                    assertTrue(legacySame,
                            "状态 #" + i + " 与 #" + j + " 被新口径**错误地合并**了（漏字段）");
                }
            }
        }
    }

    /** 补进去的三个字段必须真的起作用：它们不同 → 指纹必须不同。 */
    @Test
    void turnHpLossCountAndPotionSlotsNowSplitStates() {
        SimState a = copy();
        SimState turnDiffers = copy();
        turnDiffers.turn = 7;
        assertFalse(a.fingerprint().equals(turnDiffers.fingerprint()), "turn 必须进指纹");

        SimState hpLossDiffers = copy();
        hpLossDiffers.hpLossCount = 3;
        assertFalse(a.fingerprint().equals(hpLossDiffers.fingerprint()),
                "hpLossCount 决定浴血奋战的动态费用，必须进指纹");

        SimState slotsDiffer = copy();
        slotsDiffer.potionSlots = 3;
        assertFalse(a.fingerprint().equals(slotsDiffer.fingerprint()), "potionSlots 必须进指纹");
    }

    /** 语料里每个变体都该被区分开（除了显式的「内容相同」那一条）。 */
    @Test
    void everyVariationIsDistinguished() {
        List<SimState> states = corpus();
        java.util.Set<String> current = new java.util.HashSet<String>();
        for (SimState state : states) {
            current.add(state.fingerprint());
        }
        assertEquals(states.size() - 1, current.size(), "只有 base 与 copy 允许撞成同一条");
    }
}
