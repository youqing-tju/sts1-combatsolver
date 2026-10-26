package io.github.youqing.sts1solver.mirror.hooks;

import org.junit.jupiter.api.Test;

import java.util.Collections;

import io.github.youqing.sts1solver.mirror.DamageCalc;
import io.github.youqing.sts1solver.sim.CombatFlow;
import io.github.youqing.sts1solver.sim.MonsterMove;
import io.github.youqing.sts1solver.sim.SimMonster;
import io.github.youqing.sts1solver.sim.SimState;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 遗物第二批：被动 / 回合钩子（纸青蛙 / 鸟居 / 水银沙漏 / 号角夹板 / 舵轮 / 山铜）。
 *
 * <p>数值与 ID 全部按 {@code com.megacrit.cardcrawl.relics.*} 的字节码核实，见
 * {@code mechanics-reference} §33.44。
 */
class RelicHooksTest {

    private static SimState fight() {
        SimState s = new SimState();
        s.hp = 80;
        s.maxHp = 80;
        s.energy = 3;
        SimMonster m = new SimMonster("TestMonster", 100, 100, 0,
                Collections.<String, Integer>emptyMap());
        s.monsters.add(m);
        return s;
    }

    /** 回合末结算需要怪物有招，否则它没法行动。 */
    private static void armedMonster(SimState s) {
        SimMonster m = s.monsters.get(0);
        m.pendingMove = MonsterMove.block(1, 0);
        m.pendingMoveId = 1;
    }

    // ---------------------------------------------------------------- 纸青蛙：易伤倍率

    /**
     * 纸青蛙只放大「玩家打敌人」的易伤倍率（1.5 → 1.75）。
     *
     * <p>🔴 它的类名是 {@code PaperFrog}、ID 是 {@code "Paper Frog"}；按显示名
     * "Paper Phrog" 去查会**永远查不到**（死条目）。
     */
    @Test
    void paperFrogRaisesVulnerableMultiplierForPlayerAttacks() {
        SimState s = fight();
        SimMonster m = s.monsters.get(0);
        m.powers.put("Vulnerable", Integer.valueOf(1));

        assertEquals(15, DamageCalc.attackDamage(s, 10, m), "没遗物：10 × 1.5 = 15");

        s.relics.put(RelicHooks.PAPER_FROG, Integer.valueOf(-1));
        assertEquals(17, DamageCalc.attackDamage(s, 10, m), "有纸青蛙：10 × 1.75 = 17.5 → 17");
    }

    /** 敌人打玩家那条不吃纸青蛙：玩家自己身上有易伤仍是 1.5。 */
    @Test
    void paperFrogDoesNotAffectDamageTakenByThePlayer() {
        SimState s = fight();
        SimMonster m = s.monsters.get(0);
        s.playerPowers.put("Vulnerable", Integer.valueOf(1));
        s.relics.put(RelicHooks.PAPER_FROG, Integer.valueOf(-1));

        assertEquals(15, DamageCalc.monsterAttackDamage(s, 10, m), "玩家挨打仍是 ×1.5");
    }

    // ---------------------------------------------------------------- 鸟居：小额攻击变 1

    /** 鸟居：2..5 点的攻击伤害改成 1；1 点不动，6 点也不动。 */
    @Test
    void toriiCapsSmallAttackDamageAtOne() {
        SimState s = fight();
        s.relics.put(RelicHooks.TORII, Integer.valueOf(-1));
        SimMonster m = s.monsters.get(0);

        CombatFlow.hitPlayer(s, 5, m);
        assertEquals(79, s.hp, "5 → 1");

        CombatFlow.hitPlayer(s, 2, m);
        assertEquals(78, s.hp, "2 → 1");

        CombatFlow.hitPlayer(s, 1, m);
        assertEquals(77, s.hp, "1 不触发（要 > 1）");

        CombatFlow.hitPlayer(s, 6, m);
        assertEquals(71, s.hp, "6 不触发（要 <= 5）");
    }

    /** 鸟居不认「没有攻击者」的掉血（HP_LOSS 类型，例如缠绕、自伤）。 */
    @Test
    void toriiIgnoresDamageWithoutAnAttacker() {
        SimState s = fight();
        s.relics.put(RelicHooks.TORII, Integer.valueOf(-1));

        CombatFlow.hitPlayer(s, 5);
        assertEquals(75, s.hp, "无攻击者 = HP 损失，吃满 5 点");
    }

    // ---------------------------------------------------------------- 水银沙漏

    /** 水银沙漏：新回合开始对全体 3 点纯伤（不吃力量/易伤）。 */
    @Test
    void mercuryHourglassDealsThreePureDamageEachTurn() {
        SimState s = fight();
        armedMonster(s);
        s.relics.put(RelicHooks.MERCURY_HOURGLASS, Integer.valueOf(-1));
        s.playerPowers.put("Strength", Integer.valueOf(5));
        s.monsters.get(0).powers.put("Vulnerable", Integer.valueOf(2));

        CombatFlow.endTurn(s, 3, 5);

        assertEquals(97, s.monsters.get(0).hp, "固定 3 点：力量/易伤都不参与");
    }

    /**
     * 沙漏是**玩家回合开始**结的，而怪物的格挡在它自己的回合开始就清掉了 ——
     * 所以玩家回合开始时怪物身上的格挡一定是 0，沙漏的 3 点必然全打在血上。
     *
     * <p>这条钉的是「顺序」：如果谁把沙漏挪到怪物回合之前结算，这条会红。
     */
    @Test
    void mercuryHourglassHitsAfterMonsterBlockIsAlreadyCleared() {
        SimState s = fight();
        armedMonster(s);
        s.relics.put(RelicHooks.MERCURY_HOURGLASS, Integer.valueOf(-1));
        s.monsters.get(0).block = 2;

        CombatFlow.endTurn(s, 3, 5);

        assertEquals(97, s.monsters.get(0).hp, "怪物格挡在它回合开始就没了，3 点全落地");
        assertEquals(0, s.monsters.get(0).block);
    }

    // ---------------------------------------------------------------- 号角夹板 / 舵轮

    /** 号角夹板：第 2 个回合开始 +14 格挡，触发一次后 counter = -1 并冻住。 */
    @Test
    void hornCleatGivesBlockOnTurnTwoOnly() {
        SimState s = fight();
        armedMonster(s);
        s.relics.put(RelicHooks.HORN_CLEAT, Integer.valueOf(1));   // 第 1 回合已经过完了

        CombatFlow.endTurn(s, 3, 5);
        assertEquals(14, s.block, "第 2 回合开始 +14");
        assertEquals(Integer.valueOf(-1), s.relics.get(RelicHooks.HORN_CLEAT), "置 -1（grayscale）");

        // 第 3 回合不再触发（原版 grayscale 之后 counter 连自增都停了）
        s.block = 0;
        CombatFlow.endTurn(s, 3, 5);
        assertEquals(0, s.block, "只触发一次");
    }

    /** 舵轮：第 3 个回合开始 +18 格挡。 */
    @Test
    void captainsWheelGivesBlockOnTurnThree() {
        SimState s = fight();
        armedMonster(s);
        s.relics.put(RelicHooks.CAPTAINS_WHEEL, Integer.valueOf(2));

        CombatFlow.endTurn(s, 3, 5);
        assertEquals(18, s.block);
        assertEquals(Integer.valueOf(-1), s.relics.get(RelicHooks.CAPTAINS_WHEEL));
    }

    // ---------------------------------------------------------------- 山铜

    /**
     * 山铜：回合结束**一点格挡都没有**才 +6，而且这 6 点要顶住怪物这一轮。
     *
     * <p>断言看「血有没有掉」而不是「结束时 block 多少」：{@code endTurn} 返回时已经翻到
     * 下一个玩家回合，格挡按规矩清空了。
     */
    @Test
    void orichalcumGivesSixBlockOnlyWhenBlockless() {
        SimState s = fight();
        s.monsters.get(0).pendingMove = MonsterMove.attack(2, 5);
        s.monsters.get(0).pendingMoveId = 2;
        s.relics.put(RelicHooks.ORICHALCUM, Integer.valueOf(-1));

        int before = s.hp;
        CombatFlow.endTurn(s, 3, 5);
        assertEquals(before, s.hp, "6 点格挡吃掉 5 点攻击，血不掉");

        // 没有山铜：同样的 5 点攻击要掉血
        SimState s2 = fight();
        s2.monsters.get(0).pendingMove = MonsterMove.attack(2, 5);
        s2.monsters.get(0).pendingMoveId = 2;
        int before2 = s2.hp;
        CombatFlow.endTurn(s2, 3, 5);
        assertEquals(before2 - 5, s2.hp, "没有遗物就掉 5 点");

        // 已经有格挡时山铜不给：4 点格挡 + 5 点攻击 → 掉 1 点
        SimState s3 = fight();
        s3.monsters.get(0).pendingMove = MonsterMove.attack(2, 5);
        s3.monsters.get(0).pendingMoveId = 2;
        s3.relics.put(RelicHooks.ORICHALCUM, Integer.valueOf(-1));
        s3.block = 4;
        int before3 = s3.hp;
        CombatFlow.endTurn(s3, 3, 5);
        assertEquals(before3 - 1, s3.hp, "有格挡就不触发，只挡掉 4 点");
    }
}
