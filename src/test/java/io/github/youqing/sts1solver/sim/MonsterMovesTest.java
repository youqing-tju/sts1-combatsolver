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

    /** 造一个只带 rng 的空状态，走新签名（每次决策都消耗一次 aiRng）。 */
    private static MonsterMove next(String type, java.util.List<Integer> history, int turn) {
        SimState state = new SimState();
        state.rng.put(SimState.RngKind.AI, RngStream.from(11L, 22L, 0));
        SimMonster monster = new SimMonster(type, 50, 50, 0, Collections.<String, Integer>emptyMap());
        monster.moveHistory.addAll(history);
        return MonsterMoves.next(state, monster, turn, 0);
    }

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
        MonsterMove first = next("SphericGuardian", history(), 1);
        assertEquals(2, first.id);
        assertEquals(25, first.block, "DEFEND 是格挡 25（A17 才 35）");
        assertFalse(first.attacks());

        MonsterMove second = next("SphericGuardian", history(2), 2);
        assertEquals(4, second.id);
        assertEquals(10, second.damage, "基础伤害 10");
        assertEquals("Frail", second.playerDebuff);
        assertEquals(5, second.playerDebuffAmount);

        MonsterMove third = next("SphericGuardian", history(2, 4), 3);
        assertEquals(1, third.id);
        assertEquals(2, third.hits, "普通攻击是 2 段");

        MonsterMove fourth = next("SphericGuardian", history(2, 4, 1), 4);
        assertEquals(3, fourth.id);
        assertEquals(15, fourth.block, "ATTACK_DEFEND 自带 15 格挡");

        MonsterMove fifth = next("SphericGuardian", history(2, 4, 1, 3), 5);
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
        SimMonster cultist = monster("JawWorm");   // 还没镜像的怪
        cultist.intentDmg = 6;
        s.monsters.add(cultist);
        for (int i = 0; i < 8; i++) {
            s.draw.add(new SimCard("Strike_R", false, 1, 6, "ATTACK"));
        }

        CombatFlow.endTurn(s, 3, 5);

        assertEquals(44, s.hp, "未镜像的怪仍按意图伤害打一下");
        assertNull(cultist.pendingMove, "未镜像的怪不该有镜像招式");
        assertTrue(cultist.moveHistory.isEmpty(), "未镜像的怪不记镜像历史");
        assertFalse(MonsterMoves.supported("JawWorm"));
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

    /** 邪教徒：第一招 INCANTATION（给自己挂 Ritual 3，A2 起 4），之后一直 DARK_STRIKE 6。 */
    @Test
    void cultistCastsIncantationThenAttacks() {
        assertEquals(3, next("Cultist", history(), 1).selfBuffAmount, "Ritual 3");
        assertEquals("Ritual", next("Cultist", history(), 1).selfBuff);

        SimState ascended = new SimState();
        ascended.ascension = 2;
        SimMonster cultist = new SimMonster("Cultist", 50, 50, 0, Collections.<String, Integer>emptyMap());
        assertEquals(4, MonsterMoves.next(ascended, cultist, 1, 0).selfBuffAmount, "A2 起 Ritual 4");

        MonsterMove strike = next("Cultist", history(3), 2);
        assertEquals(1, strike.id);
        assertEquals(6, strike.damage);
    }

    /** Ritual 要真的在回合末变成力量（CombatFlow ④.5 的 atEndOfRound）。 */
    @Test
    void cultistRitualTurnsIntoStrengthAtRoundEnd() {
        SimState s = new SimState();
        s.hp = 50;
        s.maxHp = 80;
        SimMonster cultist = new SimMonster("Cultist", 50, 50, 0,
                Collections.<String, Integer>emptyMap());
        cultist.pendingMove = MonsterMove.buff(3, "Ritual", 3);
        cultist.pendingMoveId = 3;
        s.monsters.add(cultist);

        CombatFlow.endTurn(s, 3, 5);
        assertEquals(Integer.valueOf(3), cultist.powers.get("Ritual"), "INCANTATION 挂 3 层 Ritual");
        // 同一回合的 atEndOfRound 就把它转成力量（原版 RitualPower.atEndOfRound）
        assertEquals(Integer.valueOf(3), cultist.powers.get("Strength"), "round end 把 Ritual 变力量");

        CombatFlow.endTurn(s, 3, 5);
        assertEquals(Integer.valueOf(6), cultist.powers.get("Strength"), "第二回合再 +3");
    }

    /** 小鬼战士：永远 1(ATTACK)，伤害 4（A2 起 5）。 */
    @Test
    void gremlinWarriorAlwaysAttacks() {
        MonsterMove move = next("GremlinWarrior", history(1, 1), 3);
        assertEquals(1, move.id);
        assertEquals(4, move.damage);

        SimState ascended = new SimState();
        ascended.ascension = 2;
        SimMonster warrior = new SimMonster("GremlinWarrior", 24, 24, 0,
                Collections.<String, Integer>emptyMap());
        assertEquals(5, MonsterMoves.next(ascended, warrior, 1, 0).damage, "A2 起伤害 5");
    }

    /** 小鬼胖子：攻击 + Weak（A17 起 2 层）。 */
    @Test
    void gremlinFatAppliesWeak() {
        MonsterMove move = next("GremlinFat", history(), 1);
        assertEquals(4, move.damage);
        assertEquals("Weakened", move.playerDebuff);
        assertEquals(1, move.playerDebuffAmount);

        SimState ascended = new SimState();
        ascended.ascension = 17;
        ascended.ascension = 17;
        SimMonster fat = new SimMonster("GremlinFat", 17, 17, 0,
                Collections.<String, Integer>emptyMap());
        MonsterMove hard = MonsterMoves.next(ascended, fat, 1, 0);
        assertEquals(5, hard.damage, "A2 起伤害 5");
        assertEquals(2, hard.playerDebuffAmount, "A17 起 Weak 2");
    }

    /** 小尖刺史莱姆：伤害 5（A2 起 6）。 */
    @Test
    void smallSpikeSlimeDamageByAscension() {
        assertEquals(5, next("SpikeSlime_S", history(), 1).damage);
        SimState ascended = new SimState();
        ascended.ascension = 2;
        SimMonster slime = new SimMonster("SpikeSlime_S", 14, 14, 0,
                Collections.<String, Integer>emptyMap());
        assertEquals(6, MonsterMoves.next(ascended, slime, 1, 0).damage);
    }

    /** 每次决策消耗一次 aiRng（原版 rollMove = getMove(aiRng.random(99))）。 */
    @Test
    void everyDecisionConsumesOneAiRoll() {
        SimState state = new SimState();
        state.rng.put(SimState.RngKind.AI, RngStream.from(11L, 22L, 0));
        SimMonster monster = new SimMonster("GremlinWarrior", 24, 24, 0,
                Collections.<String, Integer>emptyMap());
        MonsterMoves.next(state, monster, 1, 0);
        assertEquals(1, state.rng.get(SimState.RngKind.AI).counter(), "第一决策消耗 1 次");
        MonsterMoves.next(state, monster, 2, 0);
        assertEquals(2, state.rng.get(SimState.RngKind.AI).counter(), "两次决策消耗 2 次");
    }

    /** 已镜像清单（诊断/面板用）里应当有这五只。 */
    @Test
    void mirroredTypesIncludeTheFirstBatch() {
        java.util.Set<String> types = MonsterMoves.mirroredTypes();
        assertTrue(types.contains("SphericGuardian"));
        assertTrue(types.contains("Cultist"));
        assertTrue(types.contains("GremlinWarrior"));
        assertTrue(types.contains("GremlinFat"));
        assertTrue(types.contains("SpikeSlime_S"));
    }
}
