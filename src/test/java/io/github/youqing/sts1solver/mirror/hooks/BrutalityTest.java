package io.github.youqing.sts1solver.mirror.hooks;

import io.github.youqing.sts1solver.sim.CombatFlow;
import io.github.youqing.sts1solver.sim.RngStream;
import io.github.youqing.sts1solver.sim.SimCard;
import io.github.youqing.sts1solver.sim.SimMonster;
import io.github.youqing.sts1solver.sim.SimState;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Brutality：回合初（**抽完牌之后**）先抽 amount 张，再掉 amount 点血。
 *
 * <p>原版 `BrutalityPower.atStartOfTurnPostDraw`（javap 可核对方法名）：
 * <pre>
 * addToBot(new DrawCardAction(owner, amount));
 * addToBot(new LoseHPAction(owner, owner, amount));
 * </pre>
 *
 * <p>两个容易写错的地方，各有一条用例钉住：
 * <ol>
 *   <li>抽牌必须发生在**正常抽牌之后**（否则会被算进 5 张里，或者被回能阶段吞掉）；</li>
 *   <li>掉血必须走 {@code hitPlayer(HP_LOSS)}，不能 {@code hp -= amount} ——
 *       否则自成形黏土、Rupture 这些受伤钩子全不触发（真机现场：`hp -1` 时
 *       我们多掉了 1 血，黏土却因此多给 3 格挡）。</li>
 * </ol>
 */
class BrutalityTest {

    private static SimState fight(int brutality) {
        SimState s = new SimState();
        s.hp = 80;
        s.maxHp = 80;
        s.turn = 1;
        s.energy = 3;
        s.energyPerTurn = 3;
        for (int i = 0; i < 5; i++) {
            s.hand.add(new SimCard("Strike_R", false, 1, 6, "ATTACK"));
        }
        for (int i = 0; i < 12; i++) {
            s.draw.add(new SimCard("Defend_R", false, 1, -1, "SKILL"));
        }
        s.monsters.add(new SimMonster("AcidSlime_S", 40, 40, 0,
                Collections.<String, Integer>emptyMap()));
        s.rng.put(SimState.RngKind.CARD, RngStream.from(11L, 22L, 0));
        s.rng.put(SimState.RngKind.SHUFFLE, RngStream.from(33L, 44L, 0));
        if (brutality > 0) {
            Map<String, Integer> powers = new LinkedHashMap<String, Integer>();
            powers.put("Brutality", Integer.valueOf(brutality));
            s.playerPowers.putAll(powers);
        }
        return s;
    }

    /** 先抽 2 再掉 2：手牌 5 张起手 → 弃掉 → 正常抽 5 → Brutality 再抽 2 = 7 张。 */
    @Test
    void drawsFirstThenLosesHpAtStartOfTurnPostDraw() {
        SimState s = fight(2);

        CombatFlow.endTurn(s, 3, 5);

        assertEquals(7, s.hand.size(),
                "正常 5 张 + Brutality 2 张；若挂在被回能覆盖的阶段或抽牌之前，这里会对不上");
        assertEquals(78, s.hp, "掉 2 血必须落在回合初（不是回合末）");
    }

    /** 掉血要走受伤钩子：自成形黏土 → Next Turn Block +3。 */
    @Test
    void hpLossStillFiresSelfFormingClay() {
        SimState s = fight(2);
        s.relics.put(RelicHooks.SELF_FORMING_CLAY, Integer.valueOf(-1));

        CombatFlow.endTurn(s, 3, 5);

        assertEquals(78, s.hp);
        assertEquals(3, intOf(s.playerPowers.get(RelicHooks.NEXT_TURN_BLOCK)),
                "LoseHPAction 也是掉血，黏土必须照常触发");
    }

    /** 掉血要走受伤钩子：Rupture → 力量 +层数（原版 wasHPLost 不分伤害来源）。 */
    @Test
    void hpLossStillFiresRupture() {
        SimState s = fight(2);
        s.playerPowers.put("Rupture", Integer.valueOf(2));

        CombatFlow.endTurn(s, 3, 5);

        assertEquals(78, s.hp);
        assertEquals(2, intOf(s.playerPowers.get("Strength")),
                "Rupture 的 wasHPLost 在唯一扣血口统一派发");
    }

    /** 0 层 Brutality 不该抽牌也不该掉血（power 可能被削减到 0 但还没移除）。 */
    @Test
    void zeroStackBrutalityDoesNothing() {
        SimState s = fight(0);

        CombatFlow.endTurn(s, 3, 5);

        assertEquals(5, s.hand.size());
        assertEquals(80, s.hp);
    }

    private static int intOf(Integer value) {
        return value == null ? 0 : value.intValue();
    }
}
