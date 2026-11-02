package io.github.youqing.sts1solver.mirror;

import io.github.youqing.sts1solver.sim.SimMonster;
import io.github.youqing.sts1solver.sim.SimState;
import org.junit.jupiter.api.Test;

import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 孢子云（{@code SporeCloudPower.onDeath}）：真菌兽死亡时给玩家挂易伤。
 *
 * <p>javap 核实的字节码：
 * <pre>
 * if (AbstractDungeon.getCurrRoom().isBattleEnding()) return;   // 战斗要结束就不触发
 * addToTop(new ApplyPowerAction(player, null,
 *         new VulnerablePower(player, amount, true), amount));  // 第三参 = justApplied
 * </pre>
 *
 * <p>真机现场：两只真菌兽存活时杀死其中一只，live 的玩家易伤 +2，影子没有 ——
 * 于是之后每一击的伤害都被低估 1.5 倍。
 */
class SporeCloudTest {

    private static SimMonster fungiBeast(int hp) {
        TreeMap<String, Integer> powers = new TreeMap<String, Integer>();
        powers.put("SporeCloud", Integer.valueOf(2));
        return new SimMonster("FungiBeast", hp, 22, 0, powers);
    }

    private static SimState fight(int secondHp) {
        SimState s = new SimState();
        s.hp = 80;
        s.maxHp = 80;
        s.turn = 1;
        s.monsters.add(fungiBeast(22));
        s.monsters.add(fungiBeast(secondHp));
        return s;
    }

    /** 还有一只活着 → 死亡的这只触发孢子云，玩家 +2 易伤。 */
    @Test
    void dyingSporeCloudVulnerablesThePlayerWhileTheFightGoesOn() {
        SimState s = fight(22);
        DamageCalc.hitAndReturnDealt(s, s.monsters.get(0), 999);

        assertEquals(0, s.monsters.get(0).hp);
        assertEquals(2, intOf(s.playerPowers.get("Vulnerable")));
        // 原版构造器第三参是 true → 这一轮不递减
        assertEquals(1, intOf(
                s.powerAux.get(io.github.youqing.sts1solver.sim.CombatFlow
                        .JUST_APPLIED_PREFIX + "Vulnerable")));
    }

    /** 这是最后一只 → 战斗要结束，不触发。 */
    @Test
    void lastMonsterDeathDoesNotApplySporeCloud() {
        SimState s = fight(0);
        s.monsters.remove(1);
        DamageCalc.hitAndReturnDealt(s, s.monsters.get(0), 999);

        assertEquals(0, s.monsters.get(0).hp);
        assertEquals(0, intOf(s.playerPowers.get("Vulnerable")));
    }

    /** 没有孢子云的怪死亡不触发（不能全局按怪物类型猜）。 */
    @Test
    void ordinaryMonsterDeathDoesNotApplyAnything() {
        SimState s = new SimState();
        s.hp = 80;
        s.maxHp = 80;
        s.monsters.add(new SimMonster("Cultist", 5, 50, 0,
                new TreeMap<String, Integer>()));
        s.monsters.add(fungiBeast(22));

        DamageCalc.hitAndReturnDealt(s, s.monsters.get(0), 999);

        assertEquals(0, intOf(s.playerPowers.get("Vulnerable")));
    }

    private static int intOf(Integer value) {
        return value == null ? 0 : value.intValue();
    }
}
