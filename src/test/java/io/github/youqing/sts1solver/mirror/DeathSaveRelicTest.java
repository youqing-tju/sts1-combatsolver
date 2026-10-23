package io.github.youqing.sts1solver.mirror;

import org.junit.jupiter.api.Test;

import java.util.Collections;

import io.github.youqing.sts1solver.search.Score;
import io.github.youqing.sts1solver.sim.CombatFlow;
import io.github.youqing.sts1solver.sim.RngStream;
import io.github.youqing.sts1solver.sim.SimCard;
import io.github.youqing.sts1solver.sim.SimMonster;
import io.github.youqing.sts1solver.sim.SimState;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 一次性死亡豁免（蜥蜴尾巴）—— 对应蓝本 {@code ActEndingBossPolicy.DeathSavePremium} /
 * {@code DeathSaveBeamCost} 与 {@code ProjectedDeathPrevention.TryRevive}。
 *
 * <p>核心语义：复活**真的发生**在仿真里（后续每一条路线因此变得可行），
 * 但那笔血不算这条路线挣的 —— 打分时连本带溢价扣回去。
 */
class DeathSaveRelicTest {

    /** 160 血木桩 + 意图 100 的怪：一拳能把 10 血的玩家打死。 */
    private static SimState doomed(int playerHp) {
        SimState s = new SimState();
        s.hp = playerHp;
        s.maxHp = 80;
        s.energy = 3;
        SimMonster m = new SimMonster("JawWorm", 160, 160, 0,
                Collections.<String, Integer>emptyMap());
        m.intentDmg = 100;
        s.monsters.add(m);
        s.hand.add(new SimCard("Strike_R", false, 1));
        s.rng.put(SimState.RngKind.SHUFFLE, RngStream.from(7L, 8L, 0));
        s.rng.put(SimState.RngKind.MONSTER, RngStream.from(3L, 4L, 0));
        return s;
    }

    /** 有蜥蜴尾巴：怪物一击致死 → 立刻回到 50% 最大生命，并记下回血量。 */
    @Test
    void lizardTailRevivesToHalfMaxHp() {
        SimState s = doomed(10);
        s.relics.put(DeathSaveRelic.LIZARD_TAIL, -1);

        CombatFlow.endTurn(s, 3, 5);

        assertTrue(s.hp > 0, "有蜥蜴尾巴就不该死：实际 " + s.hp);
        assertEquals(40, s.hp, "回到 50% 最大生命（原版截断，不是四舍五入）");
        assertTrue(s.deathSaveUsed, "用过一次就要标记，本场不能再触发");
        assertEquals(40, s.deathSaveHpRestored, "记的是**实际**回复量，供打分扣回");
    }

    /** 没有蜥蜴尾巴：真的死，血量夹到 0。 */
    @Test
    void withoutLizardTailThePlayerJustDies() {
        SimState s = doomed(10);

        CombatFlow.endTurn(s, 3, 5);

        assertEquals(0, s.hp);
        assertFalse(s.deathSaveUsed);
        assertEquals(0, s.deathSaveHpRestored);
    }

    /** 只能救一次 —— 第二次致死就是真的死。 */
    @Test
    void theSaveIsSpentAfterOneUse() {
        SimState s = doomed(10);
        s.relics.put(DeathSaveRelic.LIZARD_TAIL, -1);

        CombatFlow.endTurn(s, 3, 5);
        assertEquals(40, s.hp);

        CombatFlow.endTurn(s, 3, 5);
        assertEquals(0, s.hp, "第二次致死没有第二次救命");
        assertEquals(40, s.deathSaveHpRestored, "回血量不再增加");
    }

    /** 血量还有剩的时候不该触发（否则等于白送半管血）。 */
    @Test
    void aSurvivingHitDoesNotBurnTheSave() {
        SimState s = doomed(70);
        s.relics.put(DeathSaveRelic.LIZARD_TAIL, -1);
        s.monsters.get(0).intentDmg = 20;

        CombatFlow.endTurn(s, 3, 5);

        assertEquals(50, s.hp);
        assertFalse(s.deathSaveUsed);
        assertEquals(0, s.deathSaveHpRestored);
    }

    /** 自伤路径同待遇：放血的代价把自己扣死，尾巴一样救。 */
    @Test
    void selfInflictedLossAlsoConsultsTheSave() {
        SimState s = doomed(5);
        s.relics.put(DeathSaveRelic.LIZARD_TAIL, -1);
        s.monsters.get(0).intentDmg = 0;

        Effects.loseHp(30).applyTo(s, -1, null);

        assertEquals(40, s.hp);
        assertTrue(s.deathSaveUsed);
        assertEquals(40, s.deathSaveHpRestored);
    }

    /**
     * 绽放之印：原版 {@code AbstractPlayer.damage()} 在死亡分支最外层就把它挡掉了 ——
     * 有它时救命分支整段不执行，尾巴**既不触发也不消耗**，玩家直接死。
     */
    @Test
    void markOfTheBloomDisablesTheSaveEntirely() {
        SimState s = doomed(10);
        s.relics.put(DeathSaveRelic.LIZARD_TAIL, -1);
        s.relics.put(DeathSaveRelic.MARK_OF_THE_BLOOM, -1);

        CombatFlow.endTurn(s, 3, 5);

        assertEquals(0, s.hp, "有绽放之印就该真的死");
        assertFalse(s.deathSaveUsed, "而且不该消耗尾巴");
        assertEquals(0, s.deathSaveHpRestored);
    }

    /**
     * 魔法花：{@code AbstractRelic.onPlayerHeal} 链里唯一放大治疗的遗物，
     * 战斗中 {@code round(治疗量 × 1.5)}。40 → 60。
     */
    @Test
    void magicFlowerAmplifiesTheReviveHeal() {
        SimState s = doomed(10);
        s.relics.put(DeathSaveRelic.LIZARD_TAIL, -1);
        s.relics.put(DeathSaveRelic.MAGIC_FLOWER, -1);

        CombatFlow.endTurn(s, 3, 5);

        assertEquals(60, s.hp, "40 × 1.5 = 60");
        assertEquals(60, s.deathSaveHpRestored);
    }

    // ------------------------------------------------------------ 打分口径

    /**
     * 照抄蓝本 {@code UnattendedTestRunner.ControllerSessions.cs:1263-1265} 的三个断言值。
     *
     * <p>溢价是 9 倍回血量、Beam 代价是 10 倍（连本带溢价），两者必须严格对齐蓝本数字 ——
     * 这决定了「活着赢」永远排在「靠复活赢」前面，同时仍远低于胜负分。
     */
    @Test
    void deathSavePricingMatchesTheBlueprint() {
        assertEquals(0, Score.deathSavePremium(0));
        assertEquals(360, Score.deathSavePremium(40));
        assertEquals(400, Score.deathSaveBeamCost(40));
        // 负回血量（没有触发）不能倒算成分数
        assertEquals(0, Score.deathSavePremium(-5));
        assertEquals(0, Score.deathSaveBeamCost(-5));
    }
}
