package io.github.youqing.sts1solver.sim;

import io.github.youqing.sts1solver.mirror.DamageCalc;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 怪物出招镜像（{@link MonsterMoves}）的第一只：**球体守卫 SphericGuardian**。
 *
 * <p>验收分两层：
 * <ol>
 *   <li>机器本身：给定不同的 {@code moveHistory}，吐出的招与字节码里的 {@code getMove} 分支一致；</li>
 *   <li>接进回合流程：{@code CombatFlow.endTurn} 会按招式的<b>段数</b>逐段打、给怪加格挡、
 *       给玩家上减益，并把这一招记进历史。</li>
 * </ol>
 *
 * <p>另外钉住「没镜像的怪行为不变」这条 —— 否则每加一只怪都可能悄悄改掉别的怪。
 */
class MonsterMovesTest {

    private static SimMonster monster(String type) {
        SimMonster m = new SimMonster(type, 20, 20, 0, Collections.<String, Integer>emptyMap());
        m.intentDmg = 0;
        return m;
    }

    private static ArrayList<Integer> history(Integer... ids) {
        ArrayList<Integer> list = new ArrayList<Integer>();
        for (Integer id : ids) {
            list.add(id);
        }
        return list;
    }

    /** 字节码：firstMove → 2(DEFEND)；secondMove → 4(ATTACK_DEBUFF)；之后按「上一招是不是 1」循环。 */
    @Test
    void sphericGuardianFollowsItsBytecodeCycle() {
        MonsterMove first = MonsterMoves.next("SphericGuardian", history(), 1);
        assertEquals(2, first.id);
        assertEquals(25, first.block, "DEFEND 是格挡 25（A17 才 35）");
        assertFalse(first.attacks());

        MonsterMove second = MonsterMoves.next("SphericGuardian", history(2), 2);
        assertEquals(4, second.id);
        assertEquals(10, second.damage, "基础伤害 10");
        assertEquals("Frail", second.playerDebuff);
        assertEquals(5, second.playerDebuffAmount);

        MonsterMove third = MonsterMoves.next("SphericGuardian", history(2, 4), 3);
        assertEquals(1, third.id);
        assertEquals(2, third.hits, "普通攻击是 2 段");

        MonsterMove fourth = MonsterMoves.next("SphericGuardian", history(2, 4, 1), 4);
        assertEquals(3, fourth.id);
        assertEquals(15, fourth.block, "ATTACK_DEFEND 自带 15 格挡");

        MonsterMove fifth = MonsterMoves.next("SphericGuardian", history(2, 4, 1, 3), 5);
        assertEquals(1, fifth.id, "上一招不是 1 → 回到 2 段攻击");
    }

    /** 回合流程：按段数逐段打、加格挡、上减益、记历史。 */
    @Test
    void endTurnAppliesHitsBlockAndDebuff() {
        SimState s = new SimState();
        s.hp = 50;
        s.maxHp = 80;
        s.energy = 3;
        SimMonster guardian = monster("SphericGuardian");
        // 真实序列里第 3 招才是普通攻击：前面已经打掉 DEFEND(2) 与 ATTACK_DEBUFF(4)
        guardian.moveHistory.add(Integer.valueOf(2));
        guardian.moveHistory.add(Integer.valueOf(4));
        guardian.pendingMove = MonsterMove.multiAttack(1, 10, 2);
        guardian.pendingMoveId = 1;
        s.monsters.add(guardian);
        for (int i = 0; i < 8; i++) {
            s.draw.add(new SimCard("Strike_R", false, 1, 6, "ATTACK"));
        }

        CombatFlow.endTurn(s, 3, 5);

        assertEquals(30, s.hp, "10 × 2 段 = 20 点伤害");
        assertEquals(3, guardian.moveHistory.size(), "打过的招要进历史");
        assertEquals(Integer.valueOf(1), guardian.moveHistory.get(2));
        assertNotNull(guardian.pendingMove, "镜像的怪每回合都要算出下一招");
        assertEquals(3, guardian.pendingMove.id, "上一招是 1 → 下一招 3（ATTACK_DEFEND）");
        assertEquals(10, guardian.intentDmg, "意图伤害 = 单段 10 × 1 段");
        assertTrue(guardian.intentAttack);
    }

    /** 格挡 + 减益：DEFEND 加 25 格挡，ATTACK_DEBUFF 给玩家 5 层 Frail。 */
    @Test
    void endTurnAppliesBlockAndFrail() {
        SimState s = new SimState();
        s.hp = 50;
        s.maxHp = 80;
        SimMonster guardian = monster("SphericGuardian");
        guardian.pendingMove = MonsterMove.block(2, 25);
        guardian.pendingMoveId = 2;
        s.monsters.add(guardian);

        CombatFlow.endTurn(s, 3, 5);
        assertEquals(25, guardian.block, "DEFEND 给怪自己加 25 格挡");
        assertEquals(50, s.hp, "格挡招不打人");

        guardian.pendingMove = MonsterMove.attackDebuff(4, 10, "Frail", 5);
        guardian.pendingMoveId = 4;
        CombatFlow.endTurn(s, 3, 5);
        assertEquals(40, s.hp, "10 点伤害");
        assertEquals(Integer.valueOf(5), s.playerPowers.get("Frail"), "施加 5 层 Frail");
    }

    /** 怪物力量 / 玩家易伤都要作用在**每一段**上。 */
    @Test
    void perHitDamageIncludesStrengthAndVulnerable() {
        SimState s = new SimState();
        s.hp = 50;
        s.maxHp = 80;
        SimMonster guardian = monster("SphericGuardian");
        guardian.powers.put("Strength", 3);
        guardian.pendingMove = MonsterMove.multiAttack(1, 10, 2);
        guardian.pendingMoveId = 1;
        s.monsters.add(guardian);
        s.playerPowers.put("Vulnerable", 1);

        CombatFlow.endTurn(s, 3, 5);

        // 每段 = floor((10 + 3) × 1.5) = 19，两段 38
        assertEquals(12, s.hp, "10+3=13，×1.5 取整 19，两段 38");
    }

    /** 没镜像的怪：行为与引入镜像之前完全一致（按意图伤害单段结算，意图走学习表）。 */
    @Test
    void unmirroredMonstersKeepTheOldBehaviour() {
        SimState s = new SimState();
        s.hp = 50;
        s.maxHp = 80;
        SimMonster cultist = monster("Cultist");
        cultist.intentDmg = 6;
        s.monsters.add(cultist);
        for (int i = 0; i < 8; i++) {
            s.draw.add(new SimCard("Strike_R", false, 1, 6, "ATTACK"));
        }

        CombatFlow.endTurn(s, 3, 5);

        assertEquals(44, s.hp, "未镜像的怪仍按意图伤害打一下");
        assertNull(cultist.pendingMove, "未镜像的怪不该有镜像招式");
        assertTrue(cultist.moveHistory.isEmpty(), "未镜像的怪不记镜像历史");
        assertFalse(MonsterMoves.supported("Cultist"));
    }

    /** 伤害公式与玩家向同序：力量 → 虚弱 ×0.75 → 易伤 ×1.5，每步 float 截断。 */
    @Test
    void monsterDamageUsesTheSameOrderAsDamageInfo() {
        SimState s = new SimState();
        SimMonster attacker = monster("SphericGuardian");
        assertEquals(10, DamageCalc.monsterAttackDamage(s, 10, attacker));

        attacker.powers.put("Weakened", 1);
        assertEquals(7, DamageCalc.monsterAttackDamage(s, 10, attacker), "10 × 0.75 = 7.5 → 7");

        s.playerPowers.put("Vulnerable", 1);
        assertEquals(10, DamageCalc.monsterAttackDamage(s, 10, attacker), "7 × 1.5 = 10.5 → 10");

        attacker.powers.remove("Weakened");
        assertEquals(15, DamageCalc.monsterAttackDamage(s, 10, attacker), "10 × 1.5 = 15");
    }
}
