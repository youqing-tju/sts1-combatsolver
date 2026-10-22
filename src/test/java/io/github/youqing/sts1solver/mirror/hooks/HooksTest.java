package io.github.youqing.sts1solver.mirror.hooks;

import org.junit.jupiter.api.Test;

import java.util.Collections;

import io.github.youqing.sts1solver.mirror.CardPlayer;
import io.github.youqing.sts1solver.mirror.DamageCalc;
import io.github.youqing.sts1solver.mirror.Effects;
import io.github.youqing.sts1solver.mirror.MirrorRegistry;
import io.github.youqing.sts1solver.sim.CombatFlow;
import io.github.youqing.sts1solver.sim.RngStream;
import io.github.youqing.sts1solver.sim.SimCard;
import io.github.youqing.sts1solver.sim.SimMonster;
import io.github.youqing.sts1solver.sim.SimState;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回合钩子引擎的行为验证。
 *
 * <p>每条用例都对应一个原版方法（见 {@link HookPhase} 的注释），
 * 这样以后改镜像时能立刻看出「哪一条语义被动了」。
 */
class HooksTest {

    private static SimState fight() {
        SimState s = new SimState();
        s.hp = 80;
        s.maxHp = 80;
        s.energy = 3;
        s.monsters.add(new SimMonster("AcidSlime_S", 40, 40, 0,
                Collections.<String, Integer>emptyMap()));
        s.rng.put(SimState.RngKind.CARD, RngStream.from(1L, 2L, 0));
        return s;
    }

    // ---------------------------------------------------------------- 回合末

    /** MetallicizePower.atEndOfTurnPreEndTurnCards → GainBlockAction(裸加，不吃敏捷)。 */
    @Test
    void metallicizeGainsRawBlock() {
        SimState s = fight();
        s.monsters.get(0).intentDmg = 10;
        s.playerPowers.put("Metallicize", 3);
        s.playerPowers.put(DamageCalc.DEXTERITY, 5);

        CombatFlow.endTurn(s, 3, 5);

        // 只有 3 点裸格挡能挡；若敏捷生效会挡 8 点，这里就会变成 72
        assertEquals(73, s.hp, "metallicize block must not be boosted by dexterity");
        assertEquals(0, s.block, "block is cleared once the enemy turn is over");
    }

    /** CombustPower.atEndOfTurn → 先自伤 1 点，再对全体造成层数伤害。 */
    @Test
    void combustHurtsOwnerAndAllEnemies() {
        SimState s = fight();
        s.monsters.add(new SimMonster("JawWorm", 40, 40, 0,
                Collections.<String, Integer>emptyMap()));
        s.playerPowers.put("Combust", 5);

        CombatFlow.endTurn(s, 3, 5);

        assertEquals(79, s.hp, "combust costs 1 HP");
        assertEquals(1, s.hpLossCount, "the HP loss is recorded for Blood for Blood");
        assertEquals(35, s.monsters.get(0).hp, "5 damage to every enemy");
        assertEquals(35, s.monsters.get(1).hp, "5 damage to every enemy");
    }

    /** Combust 的伤害是 THORNS：不吃力量、不吃易伤。 */
    @Test
    void combustDamageIgnoresStrengthAndVulnerable() {
        SimState s = fight();
        s.playerPowers.put(DamageCalc.STRENGTH, 9);
        s.monsters.get(0).powers.put(DamageCalc.VULNERABLE, 2);
        s.playerPowers.put("Combust", 5);

        CombatFlow.endTurn(s, 3, 5);

        assertEquals(35, s.monsters.get(0).hp, "combust is unpowered damage");
    }

    /** TheBombPower.atEndOfTurn → 倒计时到 1 时爆炸并移除。 */
    @Test
    void theBombCountsDownThenExplodes() {
        SimState s = fight();
        s.playerPowers.put("TheBomb", 3);
        s.powerAux.put("TheBomb.damage", 40);

        CombatFlow.endTurn(s, 3, 5);
        assertEquals(2, (int) s.playerPowers.get("TheBomb"), "3 → 2");
        assertEquals(40, s.monsters.get(0).hp, "no damage yet");

        CombatFlow.endTurn(s, 3, 5);
        CombatFlow.endTurn(s, 3, 5);
        assertNull(s.playerPowers.get("TheBomb"), "power is consumed on the last turn");
        assertEquals(0, s.monsters.get(0).hp, "40 damage lands on turn 3");
    }

    /** OmegaPower.atEndOfTurn → 层数即伤害。 */
    @Test
    void omegaHitsAllEnemiesEveryTurn() {
        SimState s = fight();
        s.playerPowers.put("OmegaPower", 50);

        CombatFlow.endTurn(s, 3, 5);

        assertEquals(0, s.monsters.get(0).hp, "50 damage to all");
    }

    // ---------------------------------------------------------------- 回合初

    /** DemonFormPower.atStartOfTurnPostDraw → 抽牌后加力量。 */
    @Test
    void demonFormGrantsStrengthAfterDraw() {
        SimState s = fight();
        s.playerPowers.put("Demon Form", 2);
        s.draw.add(new SimCard("Strike_R", false, 1));

        CombatFlow.endTurn(s, 3, 1);

        assertEquals(2, (int) s.playerPowers.get(DamageCalc.STRENGTH), "+2 strength");
        assertEquals(1, s.hand.size(), "and the draw still happened");
    }

    /** PanachePower.atStartOfTurn → 计数重置为 5。 */
    @Test
    void panacheCounterResetsAtTurnStart() {
        SimState s = fight();
        s.playerPowers.put("Panache", 2);
        s.powerAux.put("Panache.damage", 10);

        CombatFlow.endTurn(s, 3, 5);

        assertEquals(5, (int) s.playerPowers.get("Panache"), "counter back to 5");
    }

    // ---------------------------------------------------------------- 出牌

    /** PanachePower.onUseCard → 每 5 张牌对全体造成伤害。 */
    @Test
    void panacheFiresEveryFiveCards() {
        SimState s = fight();
        s.energy = 10;
        s.playerPowers.put("Panache", 5);
        s.powerAux.put("Panache.damage", 10);
        for (int i = 0; i < 5; i++) {
            s.hand.add(new SimCard("Defend_R", false, 1, -1, "SKILL"));
        }

        for (int i = 0; i < 4; i++) {
            CardPlayer.play(s, 0, -1);
        }
        assertEquals(40, s.monsters.get(0).hp, "no damage before the 5th card");
        assertEquals(1, (int) s.playerPowers.get("Panache"), "counter 5 → 1");

        CardPlayer.play(s, 0, -1);
        assertEquals(30, s.monsters.get(0).hp, "10 damage on the 5th card");
        assertEquals(5, (int) s.playerPowers.get("Panache"), "counter resets to 5");
    }

    /** JuggernautPower.onGainedBlock → 获得格挡就对随机敌人造成伤害。 */
    @Test
    void juggernautPunishesBlockGain() {
        SimState s = fight();
        s.energy = 3;
        s.playerPowers.put("Juggernaut", 5);
        s.hand.add(new SimCard("Defend_R", false, 1, -1, "SKILL"));

        CardPlayer.play(s, 0, -1);

        assertEquals(5, s.block, "defend still blocks");
        assertEquals(35, s.monsters.get(0).hp, "juggernaut deals its amount");
    }

    /** SadisticPower.onApplyPower → 挂减益时造成伤害。 */
    @Test
    void sadisticNaturePunishesDebuffs() {
        SimState s = fight();
        s.energy = 3;
        s.playerPowers.put("Sadistic", 5);
        s.hand.add(new SimCard("Bash", false, 2));

        CardPlayer.play(s, 0, 0);

        // 8（Bash 本体）+ 5（残虐天性）
        assertEquals(27, s.monsters.get(0).hp, "bash 8 + sadistic 5");
    }

    /** 施加增益不触发残虐天性。 */
    @Test
    void sadisticIgnoresBuffs() {
        SimState s = fight();
        s.energy = 3;
        s.playerPowers.put("Sadistic", 5);

        Hooks.onPowerApplied(s, DamageCalc.STRENGTH, 2, s.monsters.get(0));

        assertEquals(40, s.monsters.get(0).hp, "buffing the enemy must not hurt it");
    }

    // ---------------------------------------------------------------- 遗物计数

    /** OrnamentalFan.onUseCard → 每 3 次攻击给 4 格挡。 */
    @Test
    void ornamentalFanCountsAttacks() {
        SimState s = fight();
        s.energy = 10;
        s.relics.put("Ornamental Fan", 0);
        for (int i = 0; i < 3; i++) {
            s.hand.add(new SimCard("Strike_R", false, 1, 6, "ATTACK"));
        }

        CardPlayer.play(s, 0, 0);
        CardPlayer.play(s, 0, 0);
        assertEquals(0, s.block, "no block before the third attack");
        CardPlayer.play(s, 0, 0);

        assertEquals(4, s.block, "4 block on the third attack");
        assertEquals(0, (int) s.relics.get("Ornamental Fan"), "counter resets");
    }

    /** Nunchaku.onUseCard → 每 10 次攻击回 1 能量。 */
    @Test
    void nunchakuGrantsEnergyOnTenthAttack() {
        SimState s = fight();
        s.energy = 20;
        s.relics.put("Nunchaku", 9);
        s.hand.add(new SimCard("Strike_R", false, 1, 6, "ATTACK"));

        CardPlayer.play(s, 0, 0);

        assertEquals(20, s.energy, "cost 1 paid, 1 energy gained back");
        assertEquals(0, (int) s.relics.get("Nunchaku"), "counter wraps to 0");
    }

    /** PenNib：计数为 9 时那一击翻倍。 */
    @Test
    void penNibDoublesTheTenthAttack() {
        SimState s = fight();
        s.energy = 3;
        s.relics.put("Pen Nib", 9);
        s.hand.add(new SimCard("Strike_R", false, 1, 6, "ATTACK"));

        CardPlayer.play(s, 0, 0);

        assertEquals(28, s.monsters.get(0).hp, "6 base doubled to 12");
        assertEquals(0, (int) s.relics.get("Pen Nib"), "counter wraps after the doubled hit");
    }

    // ---------------------------------------------------------------- 随机插入

    /** addToRandomSpot：插入位置必须是 random(size-1)，永远推不到顶。 */
    @Test
    void randomInsertNeverLandsOnTop() {
        SimState s = fight();
        for (int i = 0; i < 3; i++) {
            s.draw.add(new SimCard("Defend_R", false, 1));
        }
        // 用一个独立的分叉算出「游戏会选哪个下标」
        RngStream probe = s.rng.get(SimState.RngKind.CARD).fork();
        int expectedIndex = probe.random(2);

        Effects.insertIntoDrawPileRandom("Dazed", 1).applyTo(s, -1, null);

        assertEquals(4, s.draw.size());
        assertEquals("Dazed", s.draw.get(expectedIndex).id, "inserted at random(size-1)");
        assertFalse("Dazed".equals(s.draw.get(3).id), "never appended on top of the pile");
    }

    /** 空抽牌堆不消耗 RNG（CardGroup.addToRandomSpot 的空堆分支）。 */
    @Test
    void randomInsertOnEmptyDrawPileDoesNotTouchRng() {
        SimState s = fight();
        int before = s.rng.get(SimState.RngKind.CARD).counter();

        Effects.insertIntoDrawPileRandom("Dazed", 1).applyTo(s, -1, null);

        assertEquals(before, s.rng.get(SimState.RngKind.CARD).counter(), "no RNG consumed");
        assertEquals("Dazed", s.draw.get(0).id);
    }

    // ---------------------------------------------------------------- 注册表

    /** 本轮新增的 6 张无色牌都必须能查到镜像。 */
    @Test
    void newColorlessCardsAreRegistered() {
        String[] keys = {"Omega", "The Bomb", "Magnetism", "Mayhem", "Panache",
                "Sadistic Nature"};
        for (String key : keys) {
            assertTrue(MirrorRegistry.supports(key), "missing mirror: " + key);
            assertTrue(MirrorRegistry.supports(key + "+"), "missing mirror: " + key + "+");
        }
    }
}
