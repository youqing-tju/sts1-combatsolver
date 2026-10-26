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

    /** 空的怪物能力表（构造 {@link SimMonster} 用）。 */
    private static final java.util.Map<String, Integer> NO_POWERS =
            java.util.Collections.emptyMap();

    /** 造一个只带 rng 的空状态，走新签名（每次决策都消耗一次 aiRng）。 */
    private static MonsterMove next(String type, java.util.List<Integer> history, int turn) {
        SimState state = new SimState();
        state.rng.put(SimState.RngKind.AI, RngStream.from(11L, 22L, 0));
        SimMonster monster = new SimMonster(type, 50, 50, 0, NO_POWERS);
        monster.moveHistory.addAll(history);
        return MonsterMoves.next(state, monster, turn, 0);
    }

    /**
     * 把一个小整数摊成一个像样的 64 位种子。
     *
     * <p>🔴 {@code RandomXS128(state0, state1)} 是**直接当状态用**的，不是种子混合器：
     * 拿 {@code seed * 7919} 这种小整数当 state0 时，前若干个 {@code nextFloat()} 几乎恒为 0，
     * 于是 {@code randomBoolean(0.5625f)} 永远返回 true —— 这是本测试踩过的坑。
     */
    private static long mix(long seed) {
        long z = seed + 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /** 带卡面伤害的 Context（模拟捕获来的 {@code damageBase}）。 */
    private static MonsterMove decideWithBase(String type, java.util.List<Integer> history, int num,
                                              int ascension, int... damageBase) {
        SimState state = new SimState();
        state.ascension = ascension;
        state.rng.put(SimState.RngKind.AI, RngStream.from(11L, 22L, 0));
        SimMonster monster = new SimMonster(type, 40, 40, 0, NO_POWERS);
        monster.moveHistory.addAll(history);
        for (int base : damageBase) {
            monster.damageBase.add(Integer.valueOf(base));
        }
        return MonsterMoves.machineFor(type).next(
                new MonsterMoves.Context(state, monster, monster.moveHistory, 1, 0, num, ascension));
    }

    /** 整副牌里有多少张 Wound。 */
    private static int countWounds(SimState s) {
        int n = 0;
        for (java.util.List<SimCard> pile : java.util.Arrays.asList(s.hand, s.draw, s.discard)) {
            for (SimCard c : pile) {
                if ("Wound".equals(c.id)) {
                    n++;
                }
            }
        }
        return n;
    }

    /** 整副牌里有多少张 Dazed。 */
    private static int countDazed(SimState s) {
        int n = 0;
        for (java.util.List<SimCard> pile : java.util.Arrays.asList(s.hand, s.draw, s.discard)) {
            for (SimCard c : pile) {
                if ("Dazed".equals(c.id)) {
                    n++;
                }
            }
        }
        return n;
    }

    /** 找出一张 Dazed（在哪一堆都行）。 */
    private static SimCard findDazed(SimState s) {
        for (java.util.List<SimCard> pile : java.util.Arrays.asList(s.hand, s.draw, s.discard)) {
            for (SimCard c : pile) {
                if ("Dazed".equals(c.id)) {
                    return c;
                }
            }
        }
        throw new AssertionError("牌堆里没有 Dazed");
    }

    private static SimMonster monster(String type) {
        SimMonster m = new SimMonster(type, 20, 20, 0, NO_POWERS);
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
        SimMonster cultist = monster("TestMonster");   // 永远不存在的类型名，不会被镜像
        cultist.intentDmg = 6;
        s.monsters.add(cultist);
        for (int i = 0; i < 8; i++) {
            s.draw.add(new SimCard("Strike_R", false, 1, 6, "ATTACK"));
        }

        CombatFlow.endTurn(s, 3, 5);

        assertEquals(44, s.hp, "未镜像的怪仍按意图伤害打一下");
        assertNull(cultist.pendingMove, "未镜像的怪不该有镜像招式");
        assertTrue(cultist.moveHistory.isEmpty(), "未镜像的怪不记镜像历史");
        assertFalse(MonsterMoves.supported("TestMonster"));
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
        SimMonster cultist = new SimMonster("Cultist", 50, 50, 0, NO_POWERS);
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
                NO_POWERS);
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
                NO_POWERS);
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
                NO_POWERS);
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
                NO_POWERS);
        assertEquals(6, MonsterMoves.next(ascended, slime, 1, 0).damage);
    }

    /** 每次决策消耗一次 aiRng（原版 rollMove = getMove(aiRng.random(99))）。 */
    @Test
    void everyDecisionConsumesOneAiRoll() {
        SimState state = new SimState();
        state.rng.put(SimState.RngKind.AI, RngStream.from(11L, 22L, 0));
        SimMonster monster = new SimMonster("GremlinWarrior", 24, 24, 0,
                NO_POWERS);
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

    // ============================ 第三批：Sentry / Tsundere / JawWorm / 史莱姆 ============================

    /** 直接构造 Context：可以指定 num / slot / ascension，钉住每条分支。 */
    private static MonsterMove decide(String type, java.util.List<Integer> history, int num,
                                      int slot, int ascension) {
        SimState state = new SimState();
        state.ascension = ascension;
        state.rng.put(SimState.RngKind.AI, RngStream.from(11L, 22L, 0));
        SimMonster monster = new SimMonster(type, 40, 40, 0, NO_POWERS);
        monster.moveHistory.addAll(history);
        return MonsterMoves.machineFor(type).next(
                new MonsterMoves.Context(state, monster, monster.moveHistory, 1, slot, num, ascension));
    }

    /** 哨兵：首招看位次（偶数位先塞 Dazed、奇数位先打），之后 4/3 交替。 */
    @Test
    void sentryFirstMoveDependsOnSlotThenAlternates() {
        assertEquals(3, decide("Sentry", history(), 50, 0, 0).id, "偶数位先 Disintegrate");
        assertEquals(4, decide("Sentry", history(), 50, 1, 0).id, "奇数位先打");
        assertEquals(3, decide("Sentry", history(4), 50, 1, 0).id, "打完接塞牌");
        assertEquals(4, decide("Sentry", history(3), 50, 0, 0).id, "塞完接打");
        assertEquals(9, decide("Sentry", history(3), 50, 0, 0).damage, "beam 9");
        assertEquals(10, decide("Sentry", history(3), 50, 0, 3).damage, "A3 起 beam 10");
        assertEquals(2, decide("Sentry", history(4), 50, 0, 0).statusCount, "Dazed 2");
        assertEquals(3, decide("Sentry", history(4), 50, 0, 18).statusCount, "A18 起 Dazed 3");
        assertEquals("Dazed", decide("Sentry", history(4), 50, 0, 0).statusCard);
    }

    /** 哨兵塞的 Dazed 要真的落到**弃牌堆**且类型是 STATUS（Evolve 会数它）。 */
    @Test
    void sentryPutsDazedIntoDiscardPile() {
        SimState s = new SimState();
        s.hp = 50;
        s.maxHp = 80;
        SimMonster sentry = monster("Sentry");
        sentry.pendingMove = MonsterMove.status(3, "Dazed", 2);
        sentry.pendingMoveId = 3;
        s.monsters.add(sentry);

        assertEquals(0, countDazed(s), "开局一张 Dazed 都没有");
        CombatFlow.endTurn(s, 3, 5);
        // ⚠️ 不能只看 discard：回合末抽牌会因为抽牌堆空而把弃牌堆洗回去，
        //    所以按「整副牌（手牌 + 抽牌堆 + 弃牌堆）里的 Dazed 张数」断言。
        assertEquals(2, countDazed(s), "两张 Dazed 进牌堆");
        SimCard added = findDazed(s);
        assertEquals("Dazed", added.id);
        assertEquals("STATUS", added.type, "类型必须是 STATUS，否则 Evolve 数不到");
        assertEquals(50, s.hp, "塞牌不造成伤害");
    }

    /** 傲娇鬼：有队友时给随机队友（不是自己）加格挡；落单才开打。 */
    @Test
    void gremlinTsundereBlocksAnAllyThenAttacksWhenAlone() {
        SimState s = new SimState();
        s.hp = 50;
        s.maxHp = 80;
        s.rng.put(SimState.RngKind.AI, RngStream.from(11L, 22L, 0));
        SimMonster tsundere = monster("GremlinTsundere");
        tsundere.pendingMove = MonsterMove.blockAlly(1, 7);
        tsundere.pendingMoveId = 1;
        SimMonster ally = monster("GremlinWarrior");
        s.monsters.add(tsundere);
        s.monsters.add(ally);

        CombatFlow.endTurn(s, 3, 5);
        assertEquals(0, tsundere.block, "格挡不给自己");
        assertEquals(7, ally.block, "给唯一的队友");
        assertEquals(1, tsundere.pendingMove.id, "队友还在 → 继续 Protect");

        // 队友死了：下一轮决策必须是攻击（takeTurn 里 alive > 1 不成立 → 2）
        ally.hp = 0;
        tsundere.pendingMove = MonsterMove.blockAlly(1, 7);
        tsundere.pendingMoveId = 1;
        CombatFlow.endTurn(s, 3, 5);
        assertEquals(2, tsundere.pendingMove.id, "落单改打");
        assertEquals(6, tsundere.pendingMove.damage);
        assertEquals(7, tsundere.block, "没有队友时格挡落到自己身上");

        // ⚠️ decide() 造的状态里只有它自己一只怪，所以这里只能验「落单分支」的数值，
        //    「有队友 → Protect」由上面的 endTurn 断言覆盖。
        assertEquals(2, decide("GremlinTsundere", history(), 50, 0, 0).id, "落单打 Shield Bash");
        assertEquals(8, decide("GremlinTsundere", history(), 50, 0, 2).damage, "A2 起 bash 8");
        // A17 的 blockAmt 要「有队友」才看得见：单独一只时它只会打
        SimState duo = new SimState();
        duo.ascension = 17;
        duo.rng.put(SimState.RngKind.AI, RngStream.from(11L, 22L, 0));
        SimMonster a = new SimMonster("GremlinTsundere", 20, 20, 0, NO_POWERS);
        duo.monsters.add(a);
        duo.monsters.add(new SimMonster("GremlinWarrior", 20, 20, 0, NO_POWERS));
        assertEquals(11, MonsterMoves.machineFor("GremlinTsundere")
                        .next(new MonsterMoves.Context(duo, a, a.moveHistory, 1, 0, 50, 17))
                        .blockRandomAlly,
                "A17 起 blockAmt 11");
    }

    /** 大颚虫：首招必定 Chomp；num 分三档，各自的分支按字节码走。 */
    @Test
    void jawWormFollowsTheThreeNumBands() {
        assertEquals(1, decide("JawWorm", history(), 50, 0, 0).id, "首招 Chomp");
        assertEquals(11, decide("JawWorm", history(), 50, 0, 0).damage);
        assertEquals(12, decide("JawWorm", history(), 50, 0, 2).damage, "A2 起 Chomp 12");

        // num < 25：上一招是 Chomp → 抛硬币在 Bellow / Thrash 里选（aiRng 决定，两个都可能）
        java.util.Set<Integer> afterChomp = new java.util.HashSet<Integer>();
        for (int seed = 1; seed <= 20; seed++) {
            SimState s = new SimState();
            s.rng.put(SimState.RngKind.AI, RngStream.from(mix(seed), 0x9E3779B97F4A7C15L, 0));
            SimMonster worm = new SimMonster("JawWorm", 40, 40, 0,
                    NO_POWERS);
            worm.moveHistory.add(Integer.valueOf(1));
            afterChomp.add(Integer.valueOf(MonsterMoves.machineFor("JawWorm").next(
                    new MonsterMoves.Context(s, worm, worm.moveHistory, 1, 0, 10, 0)).id));
        }
        assertTrue(afterChomp.contains(Integer.valueOf(2)), "可能 Bellow");
        assertTrue(afterChomp.contains(Integer.valueOf(3)), "可能 Thrash");

        // num < 25 且上一招不是 Chomp → 必定 Chomp
        assertEquals(1, decide("JawWorm", history(3), 10, 0, 0).id);

        // num 30..54 且连续两招 Thrash → 只有 1 / 2 两种
        java.util.Set<Integer> afterThrash = new java.util.HashSet<Integer>();
        for (int seed = 1; seed <= 20; seed++) {
            SimState s = new SimState();
            s.rng.put(SimState.RngKind.AI, RngStream.from(mix(seed + 1000), 0x9E3779B97F4A7C15L, 0));
            SimMonster worm = new SimMonster("JawWorm", 40, 40, 0,
                    NO_POWERS);
            worm.moveHistory.add(Integer.valueOf(3));
            worm.moveHistory.add(Integer.valueOf(3));
            afterThrash.add(Integer.valueOf(MonsterMoves.machineFor("JawWorm").next(
                    new MonsterMoves.Context(s, worm, worm.moveHistory, 1, 0, 40, 0)).id));
        }
        assertTrue(afterThrash.contains(Integer.valueOf(1)));
        assertTrue(afterThrash.contains(Integer.valueOf(2)));

        // num >= 55：上一招是 Bellow → 1/3；否则必定 Bellow
        assertEquals(2, decide("JawWorm", history(1), 80, 0, 0).id);
        MonsterMove bellow = decide("JawWorm", history(1), 80, 0, 0);
        assertEquals(3, bellow.selfBuffAmount, "默认 Bellow 力量 3");
        assertEquals(6, bellow.block, "默认 Bellow 格挡 6");
    }

    /** 小酸史莱姆：takeTurn 自带 SetMoveAction，所以是死循环 1→2→1。 */
    @Test
    void smallAcidSlimeAlternatesAttackAndWeak() {
        assertEquals(1, decide("AcidSlime_S", history(2), 50, 0, 0).id, "喷完接打");
        assertEquals(3, decide("AcidSlime_S", history(2), 50, 0, 0).damage, "默认 3 伤");
        assertEquals(4, decide("AcidSlime_S", history(2), 50, 0, 2).damage, "A2 起 4 伤");
        MonsterMove lick = decide("AcidSlime_S", history(1), 50, 0, 0);
        assertEquals(2, lick.id, "打完接喷");
        assertEquals("Weakened", lick.playerDebuff);
        assertEquals(1, lick.playerDebuffAmount);
        assertEquals(0, lick.damage, "喷不造成伤害");
    }

    /** 中刺史莱姆：攻击会往弃牌堆塞 Slimed；Lick 上 Frail 1。 */
    @Test
    void mediumSpikeSlimeAddsSlimedAndFrail() {
        MonsterMove attack = decide("SpikeSlime_M", history(), 10, 0, 0);
        assertEquals(1, attack.id);
        assertEquals(8, attack.damage);
        assertEquals("Slimed", attack.statusCard);
        assertEquals(1, attack.statusCount);
        assertEquals(10, decide("SpikeSlime_M", history(), 10, 0, 2).damage, "A2 起 10 伤");

        MonsterMove lick = decide("SpikeSlime_M", history(), 90, 0, 0);
        assertEquals(4, lick.id);
        assertEquals("Frail", lick.playerDebuff);

        // 连续两招攻击后 num<30 必定 Lick
        assertEquals(4, decide("SpikeSlime_M", history(1, 1), 10, 0, 0).id);
    }

    // ============================ 第四批：FungiBeast / SlaverBlue / Louse ×2 / GremlinWizard ============================

    /** 菌菇：连打两下才 Grow；num>=60 时反过来。 */
    @Test
    void fungiBeastGrowsAfterTwoBites() {
        assertEquals(1, decide("FungiBeast", history(), 10, 0, 0).id, "第一招 Bite");
        assertEquals(1, decide("FungiBeast", history(1), 10, 0, 0).id, "只打一下还是 Bite");
        MonsterMove grow = decide("FungiBeast", history(1, 1), 10, 0, 0);
        assertEquals(2, grow.id, "连打两下 → Grow");
        assertEquals("Strength", grow.selfBuff);
        assertEquals(3, grow.selfBuffAmount, "默认 +3 力量");
        assertEquals(4, decide("FungiBeast", history(1, 1), 10, 0, 2).selfBuffAmount, "A2 起 +4");
        assertEquals(5, decide("FungiBeast", history(1, 1), 10, 0, 17).selfBuffAmount, "A17 起 +5");

        assertEquals(1, decide("FungiBeast", history(2), 90, 0, 0).id, "num>=60 且刚 Grow → Bite");
        assertEquals(2, decide("FungiBeast", history(1), 90, 0, 0).id, "num>=60 且刚 Bite → Grow");
    }

    /** 菌菇的伤害用捕获值（开战随机 5-7），不是写死的常数。 */
    @Test
    void fungiBeastUsesCapturedBaseDamage() {
        assertEquals(7, decideWithBase("FungiBeast", history(), 10, 0, 7).damage, "捕获 7 → 打 7");
        assertEquals(6, decide("FungiBeast", history(), 10, 0, 0).damage, "没捕获 → 退回默认档 6");
    }

    /** 蓝奴隶主：num>=40 先刺一下，否则 Rake（带 Weak）。 */
    @Test
    void slaverBlueStabsOrRakes() {
        assertEquals(1, decide("SlaverBlue", history(), 50, 0, 0).id, "num>=40 → Stab");
        assertEquals(12, decide("SlaverBlue", history(), 50, 0, 0).damage);
        assertEquals(13, decide("SlaverBlue", history(), 50, 0, 2).damage, "A2 起 13");

        MonsterMove rake = decide("SlaverBlue", history(), 10, 0, 0);
        assertEquals(4, rake.id, "num<40 → Rake");
        assertEquals(7, rake.damage);
        assertEquals("Weakened", rake.playerDebuff);
        assertEquals(1, rake.playerDebuffAmount);
        assertEquals(2, decide("SlaverBlue", history(), 10, 0, 17).playerDebuffAmount, "A17 起 Weak 2");

        // 连续两招 Stab 之后，即使 num>=40 也要换 Rake
        assertEquals(4, decide("SlaverBlue", history(1, 1), 50, 0, 0).id);
    }

    /** 普通虱子：4 号招给自己加力量（A17 起 +4）。 */
    @Test
    void normalLouseGrowsWithStrength() {
        MonsterMove grow = decide("LouseNormal", history(), 10, 0, 0);
        assertEquals(4, grow.id);
        assertEquals("Strength", grow.selfBuff);
        assertEquals(3, grow.selfBuffAmount);
        assertEquals(4, decide("LouseNormal", history(), 10, 0, 17).selfBuffAmount, "A17 起 +4");

        assertEquals(3, decide("LouseNormal", history(), 90, 0, 0).id, "num>=25 且没连打 → Bite");
        assertEquals(4, decide("LouseNormal", history(3, 3), 90, 0, 0).id, "连打两下 → Grow");
        // 连续两次 Grow 之后必须打（num<25 分支）
        assertEquals(3, decide("LouseNormal", history(4, 4), 10, 0, 0).id);
    }

    /** 防御虱子：4 号招是 Web（玩家 Weak 2），不给自己加力量。 */
    @Test
    void defensiveLouseWebsThePlayer() {
        MonsterMove web = decide("LouseDefensive", history(), 10, 0, 0);
        assertEquals(4, web.id);
        assertEquals("Weakened", web.playerDebuff);
        assertEquals(2, web.playerDebuffAmount);
        assertEquals(0, web.selfBuffAmount, "不给自己加力量");
    }

    /** 小鬼法师：蓄力两次放大招；放完重新蓄力（A17 起连放两发）。 */
    @Test
    void gremlinWizardChargesThenBlasts() {
        assertEquals(2, decide("GremlinWizard", history(), 50, 0, 0).id, "开场蓄力");
        assertEquals(1, decide("GremlinWizard", history(2), 50, 0, 0).id, "蓄力两次 → 放大招");
        assertEquals(25, decide("GremlinWizard", history(2), 50, 0, 0).damage);
        assertEquals(30, decide("GremlinWizard", history(2), 50, 0, 2).damage, "A2 起 30");
        assertEquals(2, decide("GremlinWizard", history(2, 1), 50, 0, 0).id, "放完重新蓄力");
        assertEquals(2, decide("GremlinWizard", history(2, 1, 2), 50, 0, 0).id, "还要再蓄一次");
        assertEquals(1, decide("GremlinWizard", history(2, 1, 2, 2), 50, 0, 0).id, "蓄够再放");
        // A17：放完大招直接再来一发
        assertEquals(1, decide("GremlinWizard", history(2, 1), 50, 0, 17).id, "A17 连轰");
        assertEquals(2, decide("GremlinWizard", history(2, 1, 1), 50, 0, 17).id, "连轰完才重新蓄力");
    }

    /** 红奴隶主：首招必刺；num>=75 且没缠过 → 缠身；之后 Scrape 带易伤。 */
    @Test
    void slaverRedEntanglesThenScrapes() {
        assertEquals(1, decide("SlaverRed", history(), 50, 0, 0).id, "首招 Stab");
        assertEquals(13, decide("SlaverRed", history(), 50, 0, 0).damage);

        MonsterMove entangle = decide("SlaverRed", history(1), 80, 0, 0);
        assertEquals(2, entangle.id, "num>=75 → Entangle");
        assertEquals("Entangled", entangle.playerDebuff);
        assertEquals(0, entangle.damage, "缠身那回合不打人");

        assertEquals(1, decide("SlaverRed", history(1, 2), 80, 0, 0).id,
                "缠过一次就不再缠（num>=55 且有历史 → Stab）");

        MonsterMove scrape = decide("SlaverRed", history(1), 10, 0, 0);
        assertEquals(3, scrape.id);
        assertEquals(8, scrape.damage);
        assertEquals("Vulnerable", scrape.playerDebuff);
        assertEquals(1, scrape.playerDebuffAmount);
        assertEquals(2, decide("SlaverRed", history(1), 10, 0, 17).playerDebuffAmount, "A17 起易伤 2");
        assertEquals(1, decide("SlaverRed", history(3, 3), 10, 0, 0).id, "连续两下 Scrape → Stab");
    }

    /** 端到端：红奴隶主真的把「缠身」挂上玩家，然后攻击牌打不出、技能牌还能打。 */
    @Test
    void entangleActuallyBlocksAttackCards() {
        SimState s = new SimState();
        s.hp = 50;
        s.maxHp = 80;
        s.energy = 3;
        SimMonster slaver = monster("SlaverRed");
        slaver.pendingMove = MonsterMove.attackDebuff(2, 0, "Entangled", 1);
        slaver.pendingMoveId = 2;
        s.monsters.add(slaver);

        CombatFlow.endTurn(s, 3, 5);
        assertEquals(Integer.valueOf(1), s.playerPowers.get("Entangled"), "缠身挂到玩家身上");

        s.energy = 3;
        s.hand.add(new SimCard("Strike_R", false, 1, 6, "ATTACK"));
        try {
            io.github.youqing.sts1solver.mirror.CardPlayer.play(s, 0, 0);
            throw new AssertionError("缠身期间攻击牌必须打不出");
        } catch (io.github.youqing.sts1solver.mirror.ActionRejected expected) {
            assertTrue(expected.getMessage().contains("Entangled"), "拒绝理由要说清是缠身：" + expected.getMessage());
        }
        s.hand.add(new SimCard("Defend_R", false, 1, -1, "SKILL"));
        io.github.youqing.sts1solver.mirror.CardPlayer.play(s, 1, -1);
        assertTrue(s.block > 0, "技能牌不受缠身影响");
    }

    // ============================ 第五批：Lagavulin（第一章精英） ============================

    /** 睡 3 回合 → 醒；醒后砍两下换吸血（同时扣力量与敏捷）。 */
    @Test
    void lagavulinSleepsThenAwakesThenSiphons() {
        assertEquals(5, decide("Lagavulin", history(), 50, 0, 0).id, "睡着就一直睡");
        assertEquals(5, decide("Lagavulin", history(5, 5), 50, 0, 0).id, "睡第 2 回合");
        MonsterMove wake = decide("Lagavulin", history(5, 5, 5), 50, 0, 0);
        assertEquals(3, wake.id, "睡满 3 回合 → 醒并开打");
        assertEquals(18, wake.damage);
        assertEquals(20, decide("Lagavulin", history(5, 5, 5), 50, 0, 3).damage, "A3 起 20");

        assertEquals(3, decide("Lagavulin", history(5, 5, 5, 3), 50, 0, 0).id, "第二下还是砍");
        MonsterMove siphon = decide("Lagavulin", history(5, 5, 5, 3, 3), 50, 0, 0);
        assertEquals(1, siphon.id, "连砍两下 → Siphon Soul");
        assertEquals("Strength", siphon.playerDebuff);
        assertEquals("Dexterity", siphon.playerDebuff2, "同时扣敏捷");
        assertEquals(-1, siphon.playerDebuffAmount, "原版 debuff 字段带符号：-1");
        assertEquals(-2, decide("Lagavulin", history(5, 5, 5, 3, 3), 50, 0, 18).playerDebuffAmount,
                "A18 起各 -2（不是 +2 —— 影子按增量存，负数才是减益）");
    }

    /** 睡着时挨打 → 立刻醒并晕一回合（原版 damage() 重写），金属化同时被拿掉。 */
    @Test
    void lagavulinWakesWhenDamaged() {
        SimState s = new SimState();
        s.hp = 50;
        s.maxHp = 80;
        SimMonster lag = new SimMonster("Lagavulin", 110, 110, 0, NO_POWERS);
        lag.powers.put("Metallicize", Integer.valueOf(8));
        lag.pendingMove = MonsterMove.none(5);
        lag.pendingMoveId = 5;
        s.monsters.add(lag);

        DamageCalc.hit(s, lag, 10);

        assertEquals(100, lag.hp, "掉 10 血");
        assertEquals(4, lag.pendingMove.id, "醒来当回合改成 STUN");
        assertNull(lag.powers.get("Metallicize"), "OPEN 会把金属化拿掉（ReducePowerAction 8）");

        // 已经醒了再挨打不会又变 STUN
        lag.pendingMove = MonsterMove.attack(3, 18);
        lag.pendingMoveId = 3;
        DamageCalc.hit(s, lag, 5);
        assertEquals(3, lag.pendingMove.id, "醒着挨打不改招");
    }

    /** 端到端：睡着的 Lagavulin 每回合吃金属化的格挡；打它一下就换成 STUN（这回合不打人）。 */
    @Test
    void sleepingLagavulinGainsBlockAndStunsAfterBeingHit() {
        SimState s = new SimState();
        s.hp = 60;
        s.maxHp = 80;
        s.energy = 3;
        SimMonster lag = new SimMonster("Lagavulin", 110, 110, 0, NO_POWERS);
        lag.powers.put("Metallicize", Integer.valueOf(8));
        lag.pendingMove = MonsterMove.none(5);
        lag.pendingMoveId = 5;
        s.monsters.add(lag);

        CombatFlow.endTurn(s, 3, 5);
        assertEquals(8, lag.block, "金属化每回合 +8 格挡");
        assertEquals(60, s.hp, "睡着不打人");
        assertEquals(5, lag.pendingMove.id, "还没被打，继续睡（不过已经睡着 1 回合了）");

        // 打它一下：立刻醒（STUN），这回合它不会攻击。
        // ⚠️ 伤害必须先打穿 8 点格挡 —— 原版判据是「currentHealth 真的变了」，
        //    全被格挡吸收时**不会醒**（Lagavulin.damage 的 if_icmpeq 分支）。
        assertEquals(5, lag.pendingMove.id, "只被格挡吸收的话还没醒");
        DamageCalc.hit(s, lag, 8);
        assertEquals(5, lag.pendingMove.id, "8 点全被格挡吸收 → 不醒");
        DamageCalc.hit(s, lag, 20);
        assertEquals(4, lag.pendingMove.id);
        int hpBefore = s.hp;
        CombatFlow.endTurn(s, 3, 5);
        assertEquals(hpBefore, s.hp, "STUN 那回合不造成伤害");
        assertEquals(0, lag.block, "它自己的回合开始会清格挡");
    }

    // ============================ 第六批：GremlinNob（精英）/ AcidSlime_M ============================

    /** 小鬼头目：先 Bellow（挂激怒），之后 Skull Bash 与 Rush 交替。 */
    @Test
    void gremlinNobBellowsThenAlternates() {
        MonsterMove bellow = decide("GremlinNob", history(), 50, 0, 0);
        assertEquals(3, bellow.id, "首招 Bellow");
        assertEquals("Enrage", bellow.selfBuff, "挂的是激怒");
        assertEquals(2, bellow.selfBuffAmount);
        assertEquals(3, decide("GremlinNob", history(), 50, 0, 18).selfBuffAmount, "A18 起 3 层");

        // 低进阶时「打不打 Skull Bash」看 num（<33 打），所以这里必须给 num=10
        MonsterMove bash = decide("GremlinNob", history(3), 10, 0, 0);
        assertEquals(2, bash.id, "Bellow 之后 num<33 → Skull Bash");
        assertEquals("Vulnerable", bash.playerDebuff);
        assertEquals(2, bash.playerDebuffAmount);
        assertEquals(6, bash.damage);
        assertEquals(8, decide("GremlinNob", history(3), 10, 0, 3).damage, "A3 起 8");
        assertEquals(1, decide("GremlinNob", history(3), 50, 0, 0).id, "num>=33 → Rush");

        assertEquals(1, decide("GremlinNob", history(3, 2), 50, 0, 0).id, "打过头 → Rush");
        assertEquals(14, decide("GremlinNob", history(3, 2), 50, 0, 0).damage);
        assertEquals(1, decide("GremlinNob", history(3, 2, 1), 50, 0, 0).id, "不能连着 Rush");
        assertEquals(2, decide("GremlinNob", history(3, 2, 1, 1), 50, 0, 0).id,
                "连续两下 Rush 之后必须 Skull Bash");

        // 低进阶看 num：num<33 一律 Skull Bash
        assertEquals(2, decide("GremlinNob", history(3, 2), 10, 0, 0).id);
        assertEquals(1, decide("GremlinNob", history(3, 2), 90, 0, 0).id);
    }

    /** 激怒：玩家每打一张技能牌，头目 +力量；攻击牌不触发。 */
    @Test
    void enrageTriggersOnSkillCardsOnly() {
        SimState s = new SimState();
        s.hp = 50;
        s.maxHp = 80;
        s.energy = 5;
        SimMonster nob = new SimMonster("GremlinNob", 82, 86, 0, NO_POWERS);
        nob.powers.put("Enrage", Integer.valueOf(2));
        s.monsters.add(nob);
        s.hand.add(new SimCard("Defend_R", false, 1, -1, "SKILL"));
        s.hand.add(new SimCard("Strike_R", false, 1, 6, "ATTACK"));

        io.github.youqing.sts1solver.mirror.CardPlayer.play(s, 0, -1);
        assertEquals(Integer.valueOf(2), nob.powers.get("Strength"), "打技能牌 → +2 力量");

        io.github.youqing.sts1solver.mirror.CardPlayer.play(s, 0, 0);
        assertEquals(Integer.valueOf(2), nob.powers.get("Strength"), "打攻击牌不触发");
    }

    /** 中酸液史莱姆：三档 num，喷吐会塞 Slimed。 */
    @Test
    void mediumAcidSlimePicksByNumBands() {
        MonsterMove spit = decide("AcidSlime_M", history(), 10, 0, 0);
        assertEquals(1, spit.id, "num<30 → Corrosive Spit");
        assertEquals(7, spit.damage);
        assertEquals("Slimed", spit.statusCard);

        assertEquals(2, decide("AcidSlime_M", history(), 50, 0, 0).id, "30..69 → Tackle");
        assertEquals(10, decide("AcidSlime_M", history(), 50, 0, 0).damage);
        assertEquals(12, decide("AcidSlime_M", history(), 50, 0, 2).damage, "A2 起 12");

        MonsterMove lick = decide("AcidSlime_M", history(), 90, 0, 0);
        assertEquals(4, lick.id, "num>=70 且没连着舔 → Lick");
        assertEquals("Weakened", lick.playerDebuff);
        assertEquals(1, lick.playerDebuffAmount);

        // 连着两下 Tackle 之后，num 仍在中档 → 只能在 1 / 4 里选
        java.util.Set<Integer> afterTackle = new java.util.HashSet<Integer>();
        for (int seed = 1; seed <= 20; seed++) {
            SimState st = new SimState();
            st.rng.put(SimState.RngKind.AI, RngStream.from(mix(seed), 0x9E3779B97F4A7C15L, 0));
            SimMonster m = new SimMonster("AcidSlime_M", 30, 30, 0, NO_POWERS);
            m.moveHistory.add(Integer.valueOf(2));
            m.moveHistory.add(Integer.valueOf(2));
            afterTackle.add(Integer.valueOf(MonsterMoves.machineFor("AcidSlime_M").next(
                    new MonsterMoves.Context(st, m, m.moveHistory, 1, 0, 50, 0)).id));
        }
        assertTrue(afterTackle.contains(Integer.valueOf(1)), "可能喷吐");
        assertTrue(afterTackle.contains(Integer.valueOf(4)), "可能舔");
    }

    // ============================ 第七批：大史莱姆的分裂 ============================

    /** 大酸液史莱姆：三档 num（30/70 或 40/80），喷吐塞 **2 张** Slimed，Lick 是 Weak 2。 */
    @Test
    void largeAcidSlimeThrowsTwoSlimed() {
        MonsterMove spit = decide("AcidSlime_L", history(), 10, 0, 0);
        assertEquals(1, spit.id);
        assertEquals(11, spit.damage);
        assertEquals("Slimed", spit.statusCard);
        assertEquals(2, spit.statusCount, "大史莱姆喷两张");
        assertEquals(12, decide("AcidSlime_L", history(), 10, 0, 2).damage, "A2 起 12");

        assertEquals(2, decide("AcidSlime_L", history(), 50, 0, 0).id, "30..69 → Tackle");
        assertEquals(16, decide("AcidSlime_L", history(), 50, 0, 0).damage);
        assertEquals(18, decide("AcidSlime_L", history(), 50, 0, 2).damage, "A2 起 18");

        MonsterMove lick = decide("AcidSlime_L", history(), 90, 0, 0);
        assertEquals(4, lick.id);
        assertEquals("Weakened", lick.playerDebuff);
        assertEquals(2, lick.playerDebuffAmount, "Lick 是 Weak 2");
    }

    /** 半血挨打 → 下一招变成 SPLIT（3），并且不会重复排队。 */
    @Test
    void largeSlimeQueuesSplitAtHalfHp() {
        SimState s = new SimState();
        s.hp = 50;
        s.maxHp = 80;
        SimMonster slime = new SimMonster("AcidSlime_L", 66, 66, 0, NO_POWERS);
        slime.pendingMove = MonsterMove.attack(1, 11);
        slime.pendingMoveId = 1;
        s.monsters.add(slime);

        DamageCalc.hit(s, slime, 5);
        assertEquals(61, slime.hp);
        assertEquals(1, slime.pendingMove.id, "还在一半以上，不分裂");

        DamageCalc.hit(s, slime, 28);
        assertEquals(33, slime.hp, "正好半血（66/2）");
        assertEquals(3, slime.pendingMove.id, "半血 → 下一招是 SPLIT");
        assertEquals("AcidSlime_M", slime.pendingMove.splitInto);
        assertEquals(2, slime.pendingMove.splitCount);

        DamageCalc.hit(s, slime, 1);
        assertEquals(3, slime.pendingMove.id, "已经排了 SPLIT，不重复排");
    }

    /** 分裂真的发生：父体消失、两只小史莱姆血量 = 父体**分裂那一刻**的血量、下一轮才行动。 */
    @Test
    void splitSpawnsTwoChildrenWithParentsHp() {
        SimState s = new SimState();
        s.hp = 50;
        s.maxHp = 80;
        SimMonster slime = new SimMonster("AcidSlime_L", 66, 66, 0, NO_POWERS);
        slime.hp = 30;
        slime.pendingMove = MonsterMove.split(3, "AcidSlime_M", 2);
        slime.pendingMoveId = 3;
        s.monsters.add(slime);

        int hpBefore = s.hp;
        CombatFlow.endTurn(s, 3, 5);

        assertEquals(0, slime.hp, "父体自杀");
        assertEquals(3, s.monsters.size(), "多出两只");
        SimMonster first = s.monsters.get(1);
        SimMonster second = s.monsters.get(2);
        assertEquals("AcidSlime_M", first.type);
        assertEquals(30, first.hp, "小怪血量 = 父体分裂时的血量（不是减半）");
        assertEquals(30, second.hp);
        assertNotNull(first.pendingMove, "小怪一出生就有招");
        assertEquals(hpBefore, s.hp, "分裂那回合不打人");
    }

    /** 大刺史莱姆：num<30 打，否则舔；舔是 Frail 2（A17: 3）。 */
    @Test
    void largeSpikeSlimeTacklesOrLicks() {
        MonsterMove bite = decide("SpikeSlime_L", history(), 10, 0, 0);
        assertEquals(1, bite.id);
        assertEquals(16, bite.damage);
        assertEquals("Slimed", bite.statusCard);
        assertEquals(1, bite.statusCount, "大刺史莱姆只塞 1 张");
        assertEquals(18, decide("SpikeSlime_L", history(), 10, 0, 2).damage, "A2 起 18");

        MonsterMove lick = decide("SpikeSlime_L", history(), 90, 0, 0);
        assertEquals(4, lick.id);
        assertEquals("Frail", lick.playerDebuff);
        assertEquals(2, lick.playerDebuffAmount);
        assertEquals(3, decide("SpikeSlime_L", history(), 90, 0, 17).playerDebuffAmount, "A17 起 3");

        assertEquals(4, decide("SpikeSlime_L", history(1, 1), 10, 0, 0).id, "连着两下 → 舔");
    }

    // ============================ 第九批：SlimeBoss ============================

    /** 史莱姆王：Goop → Preparing → Slam → Goop；Goop 塞 3 张 Slimed（A19: 5）。 */
    @Test
    void slimeBossLoopsGoopPrepareSlam() {
        MonsterMove goop = decide("SlimeBoss", history(), 50, 0, 0);
        assertEquals(4, goop.id, "首招 Goop Spray");
        assertEquals("Slimed", goop.statusCard);
        assertEquals(3, goop.statusCount);
        assertEquals(5, decide("SlimeBoss", history(), 50, 0, 19).statusCount, "A19 起 5 张");

        MonsterMove prep = decide("SlimeBoss", history(4), 50, 0, 0);
        assertEquals(2, prep.id, "喷完先蓄力");
        assertEquals(0, prep.damage, "蓄力那回合不打人");

        MonsterMove slam = decide("SlimeBoss", history(4, 2), 50, 0, 0);
        assertEquals(1, slam.id, "蓄力完砸");
        assertEquals(35, slam.damage);
        assertEquals(38, decide("SlimeBoss", history(4, 2), 50, 0, 4).damage, "A4 起 38");

        assertEquals(4, decide("SlimeBoss", history(4, 2, 1), 50, 0, 0).id, "砸完回去喷");
    }

    /** 半血分裂成**两只不同的**：1 只大刺 + 1 只大酸，血量都等于父体当时血量。 */
    @Test
    void slimeBossSplitsIntoTwoDifferentSlimes() {
        SimState s = new SimState();
        s.hp = 50;
        s.maxHp = 80;
        SimMonster boss = new SimMonster("SlimeBoss", 140, 140, 0, NO_POWERS);
        boss.hp = 70;
        boss.pendingMove = MonsterMove.attack(1, 35);
        boss.pendingMoveId = 1;
        s.monsters.add(boss);

        DamageCalc.hit(s, boss, 1);
        assertEquals(3, boss.pendingMove.id, "正好半血 → 排 SPLIT");
        assertEquals("SpikeSlime_L", boss.pendingMove.splitInto);
        assertEquals("AcidSlime_L", boss.pendingMove.splitInto2);

        CombatFlow.endTurn(s, 3, 5);
        assertEquals(0, boss.hp, "父体自杀");
        assertEquals(3, s.monsters.size());
        assertEquals("SpikeSlime_L", s.monsters.get(1).type, "先刺史莱姆");
        assertEquals("AcidSlime_L", s.monsters.get(2).type, "再酸液史莱姆");
        assertEquals(69, s.monsters.get(1).hp, "血量 = 父体分裂时的血量");
        assertEquals(69, s.monsters.get(2).hp);
    }

    // ============================ 第十批：TheGuardian（第一章 boss） ============================

    /** 攻势循环：ChargeUp → FierceBash → VentSteam → Whirlwind → ChargeUp。 */
    @Test
    void guardianOffensiveLoop() {
        MonsterMove charge = decide("TheGuardian", history(), 50, 0, 0);
        assertEquals(6, charge.id, "首招 Charge Up");
        assertEquals(9, charge.block, "自己 +9 格挡");

        assertEquals(2, decide("TheGuardian", history(6), 50, 0, 0).id, "蓄力完 Fierce Bash");
        assertEquals(32, decide("TheGuardian", history(6), 50, 0, 0).damage);
        assertEquals(36, decide("TheGuardian", history(6), 50, 0, 4).damage, "A4 起 36");

        MonsterMove vent = decide("TheGuardian", history(6, 2), 50, 0, 0);
        assertEquals(7, vent.id, "Bash 完喷蒸汽");
        assertEquals("Weakened", vent.playerDebuff);
        assertEquals(2, vent.playerDebuffAmount);
        assertEquals("Vulnerable", vent.playerDebuff2, "同时上易伤");

        MonsterMove whirl = decide("TheGuardian", history(6, 2, 7), 50, 0, 0);
        assertEquals(5, whirl.id, "蒸汽完旋风");
        assertEquals(4, whirl.hits, "旋风 4 段");
        assertEquals(5, whirl.damage, "每段 5");

        assertEquals(6, decide("TheGuardian", history(6, 2, 7, 5), 50, 0, 0).id, "旋风完回蓄力");
    }

    /** 防御循环：CloseUp → RollAttack → TwinSmash → Whirlwind。 */
    @Test
    void guardianDefensiveLoop() {
        // 兜底：历史里没有可识别的招时按 getMove 走 —— 没有 Mode Shift = 防御姿态 → 3
        SimState bare = new SimState();
        bare.rng.put(SimState.RngKind.AI, RngStream.from(11L, 22L, 0));
        SimMonster guardian = new SimMonster("TheGuardian", 240, 240, 0, NO_POWERS);
        guardian.moveHistory.add(Integer.valueOf(9));
        assertEquals(3, MonsterMoves.machineFor("TheGuardian")
                .next(new MonsterMoves.Context(bare, guardian, guardian.moveHistory, 1, 0, 50, 0)).id,
                "没有 Mode Shift → 防御姿态兜底 Roll Attack");

        MonsterMove roll = decide("TheGuardian", history(1), 50, 0, 0);
        assertEquals(3, roll.id, "CloseUp 完 Roll Attack");
        assertEquals(9, roll.damage);
        assertEquals(10, decide("TheGuardian", history(1), 50, 0, 4).damage, "A4 起 10");

        MonsterMove twin = decide("TheGuardian", history(1, 3), 50, 0, 0);
        assertEquals(4, twin.id, "Roll 完 Twin Smash");
        assertEquals(2, twin.hits);
        assertEquals(8, twin.damage);

        assertEquals(5, decide("TheGuardian", history(1, 3, 4), 50, 0, 0).id, "Twin 完旋风");
    }

    /** 伤害累计到阈值 → 切防御姿态：加 20 格挡、下一招变 CloseUp（19 格挡 + Sharp Hide 3）。 */
    @Test
    void guardianShiftsToDefensiveAtThreshold() {
        SimState s = new SimState();
        s.hp = 50;
        s.maxHp = 80;
        SimMonster guardian = new SimMonster("TheGuardian", 240, 240, 0, NO_POWERS);
        guardian.powers.put("Mode Shift", Integer.valueOf(30));
        guardian.pendingMove = MonsterMove.attack(2, 32);
        guardian.pendingMoveId = 2;
        s.monsters.add(guardian);

        DamageCalc.hit(s, guardian, 20);
        assertEquals(Integer.valueOf(10), guardian.powers.get("Mode Shift"), "还差 10 点");
        assertEquals(2, guardian.pendingMove.id, "还没切姿态");

        DamageCalc.hit(s, guardian, 15);
        assertNull(guardian.powers.get("Mode Shift"), "切防御后移除 Mode Shift");
        assertEquals(20, guardian.block, "切姿态立即 +20 格挡");
        assertEquals(1, guardian.pendingMove.id, "下一招变 Close Up");
        assertEquals(19, guardian.pendingMove.block);
        assertEquals("Sharp Hide", guardian.pendingMove.selfBuff);
        assertEquals(3, guardian.pendingMove.selfBuffAmount);
    }

    /** 塞进牌堆的状态牌必须带 STATUS 类型，否则 Evolve / 火吐数不到（静默漏算）。 */
    @Test
    void statusCardsAddedToPilesCarryTheirType() {
        SimState s = new SimState();
        io.github.youqing.sts1solver.mirror.Effects.addStatusToDiscard("Burn", 1)
                .applyTo(s, -1, null);
        io.github.youqing.sts1solver.mirror.Effects.addStatusToHand("Wound", 1)
                .applyTo(s, -1, null);

        assertEquals("STATUS", s.discard.get(s.discard.size() - 1).type, "弃牌堆的 Burn 要带类型");
        assertEquals("STATUS", s.hand.get(s.hand.size() - 1).type, "手牌里的 Wound 要带类型");
    }

    // ============================ 第十一批：GremlinThief / Looter ============================

    /** 小鬼小偷其实不偷钱也不逃 —— 就是每回合打 9（A2: 10）。 */
    @Test
    void gremlinThiefJustAttacks() {
        MonsterMove hit = decide("GremlinThief", history(), 50, 0, 0);
        assertEquals(1, hit.id);
        assertEquals(9, hit.damage);
        assertEquals(10, decide("GremlinThief", history(), 50, 0, 2).damage, "A2 起 10");
        assertEquals(1, decide("GremlinThief", history(1, 1, 1), 50, 0, 0).id, "一直打");
    }

    /** 掠夺者：抹两次 → 掷硬币（烟雾弹/突袭）→ 烟雾弹 → 逃跑。 */
    @Test
    void looterMugsThenEscapes() {
        MonsterMove mug = decide("Looter", history(), 50, 0, 0);
        assertEquals(1, mug.id);
        assertEquals(10, mug.damage);
        assertEquals(11, decide("Looter", history(), 50, 0, 2).damage, "A2 起 11");
        assertEquals(1, decide("Looter", history(1), 50, 0, 0).id, "第一次之后继续抹");

        // 第二次抹完掷硬币：要么 2(烟雾弹) 要么 4(突袭)
        java.util.Set<Integer> afterTwoMugs = new java.util.HashSet<Integer>();
        for (int seed = 1; seed <= 20; seed++) {
            SimState st = new SimState();
            st.rng.put(SimState.RngKind.AI, RngStream.from(mix(seed), 0x9E3779B97F4A7C15L, 0));
            SimMonster m = new SimMonster("Looter", 46, 48, 0, NO_POWERS);
            m.moveHistory.add(Integer.valueOf(1));
            m.moveHistory.add(Integer.valueOf(1));
            afterTwoMugs.add(Integer.valueOf(MonsterMoves.machineFor("Looter").next(
                    new MonsterMoves.Context(st, m, m.moveHistory, 1, 0, 50, 0)).id));
        }
        assertTrue(afterTwoMugs.contains(Integer.valueOf(2)), "可能烟雾弹");
        assertTrue(afterTwoMugs.contains(Integer.valueOf(4)), "可能突袭");

        MonsterMove lunge = decide("Looter", history(1, 1, 4), 50, 0, 0);
        assertEquals(2, lunge.id, "突袭完必是烟雾弹");
        assertEquals(6, lunge.block);

        MonsterMove escape = decide("Looter", history(1, 1, 4, 2), 50, 0, 0);
        assertEquals(3, escape.id, "烟雾弹之后逃跑");
        assertTrue(escape.escape, "这一招是逃离战斗");
    }

    /** 逃跑真的把它从场上拿掉（不算击杀，但也不再打人）。 */
    @Test
    void escapingMonsterLeavesTheFight() {
        SimState s = new SimState();
        s.hp = 50;
        s.maxHp = 80;
        SimMonster looter = monster("Looter");
        looter.pendingMove = MonsterMove.escape(3);
        looter.pendingMoveId = 3;
        s.monsters.add(looter);

        CombatFlow.endTurn(s, 3, 5);
        assertEquals(0, looter.hp, "逃跑后离场");
        assertEquals(50, s.hp, "逃跑那回合不打人");
    }

    /** 道歉史莱姆：掷硬币决定打人还是上 Weak（两个分支都可能）。 */
    @Test
    void apologySlimeFlipsACoin() {
        java.util.Set<Integer> moves = new java.util.HashSet<Integer>();
        for (int seed = 1; seed <= 20; seed++) {
            SimState st = new SimState();
            st.rng.put(SimState.RngKind.AI, RngStream.from(mix(seed), 0x9E3779B97F4A7C15L, 0));
            SimMonster m = new SimMonster("ApologySlime", 10, 12, 0, NO_POWERS);
            moves.add(Integer.valueOf(MonsterMoves.machineFor("ApologySlime").next(
                    new MonsterMoves.Context(st, m, m.moveHistory, 1, 0, 50, 0)).id));
        }
        assertTrue(moves.contains(Integer.valueOf(1)), "可能直接打 3");
        assertTrue(moves.contains(Integer.valueOf(2)), "可能上 Weak");
    }

    // ============================ 第十二批：第二章 · SnakePlant ============================

    /** 蛇草：连咬两下就上减益；最近上过减益就咬。 */
    @Test
    void snakePlantBitesThreeTimesOrDebuffs() {
        MonsterMove chomp = decide("SnakePlant", history(), 10, 0, 0);
        assertEquals(1, chomp.id);
        assertEquals(3, chomp.hits, "3 段");
        assertEquals(7, chomp.damage);
        assertEquals(8, decide("SnakePlant", history(), 10, 0, 2).damage, "A2 起 8");

        assertEquals(2, decide("SnakePlant", history(1, 1), 10, 0, 0).id, "连咬两下 → 减益");
        MonsterMove debuff = decide("SnakePlant", history(1, 1), 10, 0, 0);
        assertEquals("Frail", debuff.playerDebuff);
        assertEquals(2, debuff.playerDebuffAmount);
        assertEquals("Weakened", debuff.playerDebuff2);

        assertEquals(1, decide("SnakePlant", history(2), 90, 0, 0).id, "刚上过减益 → 咬");
        assertEquals(2, decide("SnakePlant", history(1), 90, 0, 0).id, "没上过 → 减益");
        // A17+：最近两招里有 2 就咬
        assertEquals(1, decide("SnakePlant", history(1, 2, 1), 90, 0, 17).id);
    }

    // ============================ 第十三批：Byrd（飞/落地） ============================

    /** 鸟：开场掷硬币（Caw / Peck），飞着按 num 分档，落地后 Stun → Headbutt → 再起飞。 */
    @Test
    void byrdFlyGroundCycle() {
        // 开场两个分支都可能
        java.util.Set<Integer> firsts = new java.util.HashSet<Integer>();
        for (int seed = 1; seed <= 20; seed++) {
            SimState st = new SimState();
            st.rng.put(SimState.RngKind.AI, RngStream.from(mix(seed), 0x9E3779B97F4A7C15L, 0));
            SimMonster m = new SimMonster("Byrd", 25, 31, 0, NO_POWERS);
            firsts.add(Integer.valueOf(MonsterMoves.machineFor("Byrd").next(
                    new MonsterMoves.Context(st, m, m.moveHistory, 1, 0, 50, 0)).id));
        }
        assertTrue(firsts.contains(Integer.valueOf(1)), "可能 Peck");
        assertTrue(firsts.contains(Integer.valueOf(6)), "可能 Caw");

        // 飞着 num<50：上一招不是 Peck → Peck（history 为空走的是开场掷硬币，见上）
        MonsterMove peck = decide("Byrd", history(2), 10, 0, 0);
        assertEquals(1, peck.id);
        assertEquals(5, peck.hits, "默认 5 段");
        assertEquals(6, decide("Byrd", history(2), 10, 0, 2).hits, "A2 起 6 段");

        // 飞着 num 50..69：没刚 Swoop 过 → Swoop
        MonsterMove swoop = decide("Byrd", history(1), 60, 0, 0);
        assertEquals(3, swoop.id);
        assertEquals(12, swoop.damage);
        assertEquals(14, decide("Byrd", history(1), 60, 0, 2).damage, "A2 起 14");

        // 飞着 num>=70：没刚 Caw 过 → Caw（+1 力量）
        MonsterMove caw = decide("Byrd", history(1), 90, 0, 0);
        assertEquals(6, caw.id);
        assertEquals("Strength", caw.selfBuff);
        assertEquals(1, caw.selfBuffAmount);

        // 落地后：Stun(4) → Headbutt(5) → 起飞(2)
        assertEquals(5, decide("Byrd", history(2, 1, 4), 10, 0, 0).id, "STUN 完 Headbutt");
        assertEquals(3, decide("Byrd", history(2, 1, 4), 10, 0, 0).damage, "Headbutt 3 伤");
        MonsterMove fly = decide("Byrd", history(2, 1, 4, 5), 10, 0, 0);
        assertEquals(2, fly.id, "Headbutt 完再起飞");
        assertEquals("Flight", fly.selfBuff);
        assertEquals(3, fly.selfBuffAmount, "默认 Flight 3");
        assertEquals(4, decide("Byrd", history(2, 1, 4, 5), 10, 0, 17).selfBuffAmount,
                "A17 起 Flight 4");
    }

    /** 落地：每段挨打掉 1 层 Flight，掉光 → 下一招变 STUN；格挡全吃掉不掉层。 */
    @Test
    void byrdLosesOneFlightPerHit() {
        SimState s = new SimState();
        s.hp = 50;
        s.maxHp = 80;
        // 血量给足：中途被打死的话（hp 归零）后续断言就没意义了
        SimMonster byrd = new SimMonster("Byrd", 100, 100, 0, NO_POWERS);
        byrd.powers.put("Flight", Integer.valueOf(3));
        byrd.pendingMove = MonsterMove.attack(3, 12);
        byrd.pendingMoveId = 3;
        s.monsters.add(byrd);

        // 飞着的时候伤害**减半**（FlightPower.calculateDamageTakenAmount），
        // 所以 1 点伤害会被砍成 0 —— 那一击既不掉血也不掉 Flight。
        DamageCalc.hit(s, byrd, 1);
        assertEquals(Integer.valueOf(3), byrd.powers.get("Flight"), "减半后 0 伤 → 不掉层");

        DamageCalc.hit(s, byrd, 2);
        assertEquals(Integer.valueOf(2), byrd.powers.get("Flight"), "减半后 1 伤 → 掉 1 层");
        DamageCalc.hit(s, byrd, 60);
        assertEquals(Integer.valueOf(1), byrd.powers.get("Flight"), "一发 30 伤也只掉 1 层");
        assertEquals(3, byrd.pendingMove.id, "还没掉光，原招不变");

        byrd.block = 10;               // 20 伤害先被减半成 10，正好全吃掉
        DamageCalc.hit(s, byrd, 20);
        assertEquals(Integer.valueOf(1), byrd.powers.get("Flight"), "被格挡吃光不掉层");

        byrd.block = 0;
        int hp = byrd.hp;
        DamageCalc.hit(s, byrd, 2);
        assertNull(byrd.powers.get("Flight"), "掉光就移除");
        assertEquals(4, byrd.pendingMove.id, "掉光 → STUN");
        assertEquals(hp - 1, byrd.hp);
    }

    // ============================ 第十四批：Chosen（Hex） ============================

    /** 被选中者：低进阶先双击，然后必放 Hex，之后按 num 与上一招分档。 */
    @Test
    void chosenOpensWithPokeOrHexThenAlternates() {
        MonsterMove poke = decide("Chosen", history(), 50, 0, 0);
        assertEquals(5, poke.id, "低进阶第一招是双击");
        assertEquals(2, poke.hits);
        assertEquals(5, poke.damage);
        assertEquals(6, decide("Chosen", history(), 50, 0, 2).damage, "A2 起每段 6");

        MonsterMove hex = decide("Chosen", history(5), 50, 0, 0);
        assertEquals(4, hex.id, "第二招必放 Hex");
        assertEquals("Hex", hex.playerDebuff);

        assertEquals(4, decide("Chosen", history(), 50, 0, 17).id, "A18+ 开局直接 Hex");

        MonsterMove debilitate = decide("Chosen", history(5, 4), 10, 0, 0);
        assertEquals(3, debilitate.id);
        assertEquals(10, debilitate.damage);
        assertEquals("Vulnerable", debilitate.playerDebuff);
        assertEquals(2, debilitate.playerDebuffAmount);
        assertEquals(12, decide("Chosen", history(5, 4), 10, 0, 2).damage, "A2 起 12");

        MonsterMove zap = decide("Chosen", history(5, 4), 90, 0, 0);
        assertEquals(2, zap.id);
        assertEquals("Weakened", zap.playerDebuff);
        assertEquals(3, zap.playerDebuffAmount);
        assertEquals("Strength", zap.selfBuff, "削志同时给自己加力量");
        assertEquals(3, zap.selfBuffAmount);

        // 上一招是 3（或 2）时换攻击：num<40 重击、否则双击
        assertEquals(1, decide("Chosen", history(5, 4, 3), 10, 0, 0).id);
        assertEquals(18, decide("Chosen", history(5, 4, 3), 10, 0, 0).damage);
        assertEquals(21, decide("Chosen", history(5, 4, 3), 10, 0, 2).damage, "A2 起 21");
        assertEquals(5, decide("Chosen", history(5, 4, 3), 90, 0, 0).id, "num>=40 → 双击");
        assertEquals(5, decide("Chosen", history(5, 4, 2), 90, 0, 0).id,
                "上一招是 2 也算「刚削过」，走攻击分支；num>=40 → 双击");
    }

    /** Hex：玩家打非攻击牌就塞 Dazed 进抽牌堆；打攻击牌不触发。 */
    @Test
    void hexShufflesDazedOnNonAttackCards() {
        SimState s = new SimState();
        s.hp = 50;
        s.maxHp = 80;
        s.energy = 5;
        s.playerPowers.put("Hex", Integer.valueOf(1));
        // 攻击牌要有个目标，不然 CardPlayer.play 会拒（顺手证明这局不是空场）
        s.monsters.add(new SimMonster("TestMonster", 200, 200, 0, NO_POWERS));
        s.hand.add(new SimCard("Defend_R", false, 1, -1, "SKILL"));
        s.hand.add(new SimCard("Strike_R", false, 1, 6, "ATTACK"));

        assertEquals(0, countDazed(s));
        io.github.youqing.sts1solver.mirror.CardPlayer.play(s, 0, -1);
        assertEquals(1, countDazed(s), "打技能牌塞 1 张 Dazed");

        io.github.youqing.sts1solver.mirror.CardPlayer.play(s, 0, 0);
        assertEquals(1, countDazed(s), "打攻击牌不触发");
    }

    // ============================ 第十五批：ShelledParasite（吸血 + 破甲硬直） ============================

    /** 甲壳寄生虫：开场掷硬币（双击/吸血重击），之后三档 num；A17+ 开场直接吸血。 */
    @Test
    void shelledParasiteOpensThenAlternates() {
        java.util.Set<Integer> firsts = new java.util.HashSet<Integer>();
        for (int seed = 1; seed <= 20; seed++) {
            SimState st = new SimState();
            st.rng.put(SimState.RngKind.AI, RngStream.from(mix(seed), 0x9E3779B97F4A7C15L, 0));
            SimMonster m = new SimMonster("ShelledParasite", 70, 70, 0, NO_POWERS);
            firsts.add(Integer.valueOf(MonsterMoves.machineFor("ShelledParasite").next(
                    new MonsterMoves.Context(st, m, m.moveHistory, 1, 0, 50, 0)).id));
        }
        assertTrue(firsts.contains(Integer.valueOf(2)), "可能双击");
        assertTrue(firsts.contains(Integer.valueOf(3)), "可能吸血重击");

        MonsterMove suck = decide("ShelledParasite", history(), 50, 0, 17);
        assertEquals(1, suck.id, "A18+ 开场直接吸");
        assertEquals(12, suck.damage, "A2 起 12");
        assertEquals("Frail", suck.playerDebuff);
        assertEquals(2, suck.playerDebuffAmount);

        MonsterMove doubleStrike = decide("ShelledParasite", history(1), 30, 0, 0);
        assertEquals(2, doubleStrike.id);
        assertEquals(2, doubleStrike.hits);
        assertEquals(6, doubleStrike.damage);
        assertEquals(7, decide("ShelledParasite", history(1), 30, 0, 2).damage, "A2 起 7");

        MonsterMove fell = decide("ShelledParasite", history(1), 80, 0, 0);
        assertEquals(3, fell.id);
        assertEquals(18, fell.damage);
        assertTrue(fell.vampire, "Fell 是吸血一击");
        assertEquals(21, decide("ShelledParasite", history(1), 80, 0, 2).damage, "A2 起 21");

        assertEquals(3, decide("ShelledParasite", history(1, 2, 2), 30, 0, 0).id,
                "连着两次双击 → 换吸血");
        assertEquals(2, decide("ShelledParasite", history(1, 3, 3), 80, 0, 0).id,
                "连着两次吸血 → 换双击");
    }

    /** num<20 且上一招就是吸 → 原版递归重掷（aiRng.random(20,99)），结果必落在 20..99 那两档。 */
    @Test
    void shelledParasiteRerollsWhenSuckRepeats() {
        java.util.Set<Integer> moves = new java.util.HashSet<Integer>();
        for (int seed = 1; seed <= 20; seed++) {
            SimState st = new SimState();
            st.rng.put(SimState.RngKind.AI, RngStream.from(mix(seed), 0x9E3779B97F4A7C15L, 0));
            SimMonster m = new SimMonster("ShelledParasite", 70, 70, 0, NO_POWERS);
            m.moveHistory.add(Integer.valueOf(1));
            moves.add(Integer.valueOf(MonsterMoves.machineFor("ShelledParasite").next(
                    new MonsterMoves.Context(st, m, m.moveHistory, 1, 0, 10, 0)).id));
        }
        assertFalse(moves.contains(Integer.valueOf(1)), "重掷之后不会再是吸（num 已 ≥20）");
        assertTrue(moves.contains(Integer.valueOf(2)) || moves.contains(Integer.valueOf(3)));
    }

    /** 吸血：打掉玩家多少血就回多少（不超过上限）。 */
    @Test
    void shelledParasiteHealsByDamageDealt() {
        SimState s = new SimState();
        s.hp = 60;
        s.maxHp = 80;
        SimMonster parasite = new SimMonster("ShelledParasite", 70, 70, 0, NO_POWERS);
        parasite.hp = 40;
        parasite.pendingMove = MonsterMove.vampireAttack(3, 18);
        parasite.pendingMoveId = 3;
        s.monsters.add(parasite);

        CombatFlow.endTurn(s, 3, 5);
        assertEquals(42, s.hp, "玩家掉 18");
        assertEquals(58, parasite.hp, "怪回 18");

        // 玩家有格挡时只回「真掉的血」
        s.hp = 60;
        s.block = 10;
        parasite.hp = 40;
        parasite.pendingMove = MonsterMove.vampireAttack(3, 18);
        parasite.pendingMoveId = 3;
        CombatFlow.endTurn(s, 3, 5);
        assertEquals(52, s.hp);
        assertEquals(48, parasite.hp, "格挡挡掉 10 → 只回 8");
    }

    /** 破甲（Plated Armor 归零）→ ARMOR_BREAK → 下一招变 STUN。 */
    @Test
    void plateArmorBreakStunsShelledParasite() {
        SimState s = new SimState();
        s.hp = 50;
        s.maxHp = 80;
        SimMonster parasite = new SimMonster("ShelledParasite", 70, 70, 0, NO_POWERS);
        parasite.powers.put("Plated Armor", Integer.valueOf(2));
        parasite.pendingMove = MonsterMove.multiAttack(2, 6, 2);
        parasite.pendingMoveId = 2;
        s.monsters.add(parasite);

        DamageCalc.hit(s, parasite, 1);
        assertEquals(Integer.valueOf(1), parasite.powers.get("Plated Armor"));
        assertEquals(2, parasite.pendingMove.id, "还有装甲，不改招");

        DamageCalc.hit(s, parasite, 1);
        assertNull(parasite.powers.get("Plated Armor"), "装甲掉光");
        assertEquals(4, parasite.pendingMove.id, "破甲 → STUN");
    }

    // ============================ 第十六批：Centurion / Healer（看队友的招） ============================

    /** 在给定场面里决策（这两只看「场上还有几只怪」，decide() 的单怪场不够用）。 */
    private static MonsterMove decideIn(SimState state, String type, SimMonster self,
                                        java.util.List<Integer> history, int num, int ascension) {
        state.ascension = ascension;
        state.rng.put(SimState.RngKind.AI, RngStream.from(11L, 22L, 0));
        self.moveHistory.clear();
        self.moveHistory.addAll(history);
        return MonsterMoves.machineFor(type).next(
                new MonsterMoves.Context(state, self, self.moveHistory, 1, 0, num, ascension));
    }

    /** 百夫长：有队友时优先加格挡，落单才怒击。 */
    @Test
    void centurionDefendsWithAlliesAndRagesAlone() {
        SimState duo = new SimState();
        SimMonster centurion = new SimMonster("Centurion", 80, 80, 0, NO_POWERS);
        duo.monsters.add(centurion);
        duo.monsters.add(new SimMonster("Healer", 50, 50, 0, NO_POWERS));

        MonsterMove defend = decideIn(duo, "Centurion", centurion, history(), 90, 0);
        assertEquals(2, defend.id, "有队友 → 加格挡");
        assertEquals(15, defend.blockRandomAlly);
        assertEquals(20, decideIn(duo, "Centurion", centurion, history(), 90, 17).blockRandomAlly,
                "A17 起 20");

        SimState solo = new SimState();
        SimMonster alone = new SimMonster("Centurion", 80, 80, 0, NO_POWERS);
        solo.monsters.add(alone);
        MonsterMove rage = decideIn(solo, "Centurion", alone, history(), 90, 0);
        assertEquals(3, rage.id, "落单 → 怒击");
        assertEquals(3, rage.hits);
        assertEquals(6, rage.damage);
        assertEquals(7, decideIn(solo, "Centurion", alone, history(), 90, 2).damage, "A2 起 7");

        MonsterMove slash = decideIn(solo, "Centurion", alone, history(), 10, 0);
        assertEquals(1, slash.id, "num<65 且没连着挥 → 挥击");
        assertEquals(12, slash.damage);
        assertEquals(14, decideIn(solo, "Centurion", alone, history(), 10, 2).damage, "A2 起 14");
        assertEquals(2, decideIn(duo, "Centurion", centurion, history(1, 1), 10, 0).id,
                "连挥两下 + 有队友 → 格挡");
    }

    /** 治疗师：队友掉血够多就治疗全队；否则全队加力量 / 攻击。 */
    @Test
    void healerHealsThenBuffsThenStrikes() {
        SimState duo = new SimState();
        SimMonster healer = new SimMonster("Healer", 50, 56, 0, NO_POWERS);
        healer.hp = 40;                     // 自己缺 16
        duo.monsters.add(healer);

        MonsterMove heal = decideIn(duo, "Healer", healer, history(), 10, 0);
        assertEquals(2, heal.id, "缺 16 血 > 15 → 治疗");
        assertEquals(16, heal.healAllies);
        // A17 起门槛抬到「缺损 > 20」，所以要先掉够血才会治
        healer.hp = 30;
        assertEquals(20, decideIn(duo, "Healer", healer, history(), 10, 17).healAllies,
                "A17 起治 20");
        assertEquals(2, decideIn(duo, "Healer", healer, history(), 10, 17).id,
                "缺 26 > A17 门槛 20 → 仍然治疗");

        // 只要缺损 ≤ 门槛就不治了（16 < 20）
        healer.hp = 40;
        assertEquals(3, decideIn(duo, "Healer", healer, history(), 10, 17).id,
                "A17 下缺 16 不达门槛 → 改成全队加力量");

        // 回满之后不再治疗：num>=40 且没连着打 → 攻击
        healer.hp = 56;
        MonsterMove strike = decideIn(duo, "Healer", healer, history(), 90, 0);
        assertEquals(1, strike.id);
        assertEquals(8, strike.damage);
        assertEquals(9, decideIn(duo, "Healer", healer, history(), 90, 2).damage, "A2 起 9");
        assertEquals("Frail", strike.playerDebuff);
        assertEquals(2, strike.playerDebuffAmount);

        MonsterMove buff = decideIn(duo, "Healer", healer, history(1), 10, 0);
        assertEquals(3, buff.id, "num<40 → 全队加力量");
        assertEquals("Strength", buff.buffAlliesPower);
        assertEquals(2, buff.buffAlliesAmount);
        assertEquals(4, decideIn(duo, "Healer", healer, history(1), 10, 17).buffAlliesAmount,
                "A17 起 +4");
    }

    /** 治疗/增益真的落到**所有活着的队友**身上（死掉的不算）。 */
    @Test
    void healerEffectsReachEveryLivingAlly() {
        SimState s = new SimState();
        s.hp = 50;
        s.maxHp = 80;
        SimMonster healer = new SimMonster("Healer", 50, 56, 0, NO_POWERS);
        healer.hp = 30;
        healer.pendingMove = MonsterMove.healAll(2, 16);
        healer.pendingMoveId = 2;
        SimMonster centurion = new SimMonster("Centurion", 80, 80, 0, NO_POWERS);
        centurion.hp = 60;
        SimMonster dead = new SimMonster("Centurion", 80, 80, 0, NO_POWERS);
        dead.hp = 0;
        s.monsters.add(healer);
        s.monsters.add(centurion);
        s.monsters.add(dead);

        CombatFlow.endTurn(s, 3, 5);
        assertEquals(46, healer.hp, "自己回 16");
        assertEquals(76, centurion.hp, "队友也回 16");
        assertEquals(0, dead.hp, "死掉的不治");

        healer.pendingMove = MonsterMove.buffAll(3, "Strength", 3);
        healer.pendingMoveId = 3;
        CombatFlow.endTurn(s, 3, 5);
        assertEquals(Integer.valueOf(3), healer.powers.get("Strength"));
        assertEquals(Integer.valueOf(3), centurion.powers.get("Strength"), "全队 +3 力量");
        assertNull(dead.powers.get("Strength"), "死掉的不加");
    }

    // ============================ 第十七批：Snecko（Confusion 改费用） ============================

    /** 蛇颈龙：开场挂混乱，之后 Glare / Bite 交替。 */
    @Test
    void sneckoAppliesConfusionThenAlternates() {
        MonsterMove whip = decide("Snecko", history(), 50, 0, 0);
        assertEquals(1, whip.id);
        assertEquals("Confused", whip.playerDebuff, "开场挂混乱");
        assertEquals(0, whip.damage, "Tail Whip 本体不造成伤害（原版那一下在 takeTurn 之外）");

        MonsterMove glare = decide("Snecko", history(1), 10, 0, 0);
        assertEquals(3, glare.id);
        assertEquals(8, glare.damage);
        assertEquals("Vulnerable", glare.playerDebuff);
        assertEquals(2, glare.playerDebuffAmount);
        assertNull(glare.playerDebuff2, "低进阶只上易伤");

        MonsterMove hardGlare = decide("Snecko", history(1), 10, 0, 17);
        assertEquals("Weakened", hardGlare.playerDebuff2, "A17 起再加 Weak 2");
        assertEquals(2, hardGlare.playerDebuff2Amount);

        MonsterMove bite = decide("Snecko", history(1), 90, 0, 0);
        assertEquals(2, bite.id);
        assertEquals(15, bite.damage);
        assertEquals(18, decide("Snecko", history(1), 90, 0, 2).damage, "A2 起 18");
        assertEquals(3, decide("Snecko", history(2, 2), 90, 0, 0).id, "连咬两口 → Glare");
    }

    /** 混乱：每抽一张牌都消耗一次 cardRandomRng，并把费用改成掷出来的值。 */
    @Test
    void confusionRerollsEveryDrawnCardCost() {
        SimState s = new SimState();
        s.hp = 50;
        s.maxHp = 80;
        s.energy = 3;
        s.playerPowers.put("Confused", Integer.valueOf(1));
        s.rng.put(SimState.RngKind.CARD, RngStream.from(11L, 22L, 0));
        for (int i = 0; i < 5; i++) {
            s.draw.add(new SimCard("Strike_R", false, 1, 6, "ATTACK"));
        }

        CombatFlow.endTurn(s, 3, 5);      // 回合末抽 5 张

        assertEquals(5, s.rng.get(SimState.RngKind.CARD).counter(), "每张牌消耗一次");
        boolean changed = false;
        for (SimCard card : s.hand) {
            assertTrue(card.cost >= 0 && card.cost <= 3, "费用必须落在 0..3：" + card.cost);
            if (card.cost != 1) {
                changed = true;
            }
        }
        assertTrue(changed, "至少有一张被改成别的费用（否则等于没建模）");
    }

    // ============================ 第十八批：BookOfStabbing / Mugger / Taskmaster ============================

    /** 刺击之书：多刺段数随历史增长；重刺一下很痛；A18 起重刺也会让下一发多一段。 */
    @Test
    void bookOfStabbingGrowsItsMultiStab() {
        // num>=15 且没连着多刺 → 多刺（段数 = 1 + 这一发自增 = 2）
        MonsterMove first = decide("BookOfStabbing", history(), 50, 0, 0);
        assertEquals(1, first.id, "空历史 num>=15 → 多刺");
        assertEquals(2, first.hits);
        assertEquals(6, first.damage);

        // num<15 且上一招不是重刺 → 重刺
        MonsterMove big = decide("BookOfStabbing", history(), 10, 0, 0);
        assertEquals(2, big.id, "num<15 且上一招不是 2 → 重刺");
        assertEquals(21, big.damage);
        assertEquals(24, decide("BookOfStabbing", history(), 10, 0, 3).damage, "A3 起 24");

        // 重刺之后（num<15）接多刺；打过两发 1 → 3 段
        assertEquals(1, decide("BookOfStabbing", history(2), 10, 0, 0).id);
        assertEquals(3, decide("BookOfStabbing", history(2, 1), 50, 0, 0).hits, "两次自增 → 3 段");
        // num>=15 且连着两发多刺 → 重刺
        assertEquals(2, decide("BookOfStabbing", history(1, 1), 50, 0, 0).id);
        // A18：重刺也算一次自增
        assertEquals(3, decide("BookOfStabbing", history(2), 10, 0, 18).hits,
                "A18 起重刺也自增 → 1+1+1 = 3 段");
    }

    /** 苦痛之刺：每一段攻击都往弃牌堆塞一张 Wound。 */
    @Test
    void painfulStabsAddsWoundPerHit() {
        SimState s = new SimState();
        s.hp = 60;
        s.maxHp = 80;
        SimMonster book = new SimMonster("BookOfStabbing", 170, 170, 0, NO_POWERS);
        book.powers.put("Painful Stabs", Integer.valueOf(1));
        book.pendingMove = MonsterMove.multiAttack(1, 6, 3);
        book.pendingMoveId = 1;
        s.monsters.add(book);

        CombatFlow.endTurn(s, 3, 5);
        assertEquals(3, countWounds(s), "3 段 → 3 张 Wound");
    }

    /** 强盗：抹两次 → 掷硬币（烟雾弹/突袭）→ 烟雾弹 → 逃跑。 */
    @Test
    void muggerMugsThenEscapes() {
        MonsterMove mug = decide("Mugger", history(), 50, 0, 0);
        assertEquals(1, mug.id);
        assertEquals(10, mug.damage);
        assertEquals(11, decide("Mugger", history(), 50, 0, 2).damage, "A2 起 11");

        java.util.Set<Integer> afterTwo = new java.util.HashSet<Integer>();
        for (int seed = 1; seed <= 20; seed++) {
            SimState st = new SimState();
            st.rng.put(SimState.RngKind.AI, RngStream.from(mix(seed), 0x9E3779B97F4A7C15L, 0));
            SimMonster m = new SimMonster("Mugger", 50, 54, 0, NO_POWERS);
            m.moveHistory.add(Integer.valueOf(1));
            m.moveHistory.add(Integer.valueOf(1));
            afterTwo.add(Integer.valueOf(MonsterMoves.machineFor("Mugger").next(
                    new MonsterMoves.Context(st, m, m.moveHistory, 1, 0, 50, 0)).id));
        }
        assertTrue(afterTwo.contains(Integer.valueOf(2)));
        assertTrue(afterTwo.contains(Integer.valueOf(4)));

        MonsterMove smoke = decide("Mugger", history(1, 1, 4), 50, 0, 0);
        assertEquals(2, smoke.id);
        assertEquals(11, smoke.block, "烟雾弹 11 格挡");
        assertEquals(17, decide("Mugger", history(1, 1, 4), 50, 0, 17).block, "A17 起 17");

        MonsterMove flee = decide("Mugger", history(1, 1, 4, 2), 50, 0, 0);
        assertEquals(3, flee.id);
        assertTrue(flee.escape);
    }

    /** 工头：永远那一鞭——伤害 + 塞 Wound（A18 起 3 张并自己 +1 力量）。 */
    @Test
    void taskmasterWhipsWithWounds() {
        MonsterMove whip = decide("Taskmaster", history(), 50, 0, 0);
        assertEquals(2, whip.id);
        assertEquals(7, whip.damage);
        assertEquals("Wound", whip.statusCard);
        assertEquals(2, whip.statusCount);
        assertNull(whip.selfBuff);

        MonsterMove hard = decide("Taskmaster", history(), 50, 0, 18);
        assertEquals(3, hard.statusCount, "A18 起塞 3 张");
        assertEquals("Strength", hard.selfBuff, "A18 起自己也涨 1 力量");
        assertEquals(1, hard.selfBuffAmount);
        assertEquals(2, decide("Taskmaster", history(2, 2), 50, 0, 0).id, "每回合都是同一招");
    }

    /** 尖头强盗：永远两段 5（A2: 6）。 */
    @Test
    void banditPointyAlwaysDoubleStabs() {
        MonsterMove hit = decide("BanditPointy", history(), 50, 0, 0);
        assertEquals(2, hit.hits);
        assertEquals(5, hit.damage);
        assertEquals(6, decide("BanditPointy", history(), 50, 0, 2).damage, "A2 起 6");
        assertEquals(1, decide("BanditPointy", history(1, 1, 1), 50, 0, 0).id, "永远同一招");
    }

    // ============================ 第十七批：召唤（GremlinLeader / TheCollector / BronzeAutomaton） ============================

    /** 造一个只有指定怪在场的状态。 */
    private static SimState field(SimMonster... monsters) {
        SimState state = new SimState();
        for (SimMonster m : monsters) {
            state.monsters.add(m);
        }
        return state;
    }

    /** 小鬼头目：没有小鬼时按 num 在「召唤」与「三连刺」之间选。 */
    @Test
    void gremlinLeaderRalliesWhenNoGremlinsAreAlive() {
        SimMonster leader = new SimMonster("GremlinLeader", 148, 148, 0, NO_POWERS);
        SimState solo = field(leader);

        MonsterMove rally = decideIn(solo, "GremlinLeader", leader, history(), 10, 0);
        assertEquals(2, rally.id, "num<75 且上一招不是 2 → Rally");
        assertNotNull(rally.extra);
        assertNotNull(rally.extra.summon, "Rally 是召唤");
        assertEquals(3, rally.extra.summon.cap, "gremlins[] 只有 3 个槽");
        assertEquals(8, rally.extra.summon.pool.length, "池子 8 项（带重复）");
        assertTrue(rally.extra.summon.minion, "小鬼要挂 Minion");

        assertEquals(4, decideIn(solo, "GremlinLeader", leader, history(2), 10, 0).id,
                "上一招已经是 Rally → 三连刺");
        MonsterMove stab = decideIn(solo, "GremlinLeader", leader, history(2), 10, 0);
        assertEquals(3, stab.hits, "STAB_AMT = 3 段");
        assertEquals(6, stab.damage, "STAB_DMG = 6，不随进阶变");

        assertEquals(4, decideIn(solo, "GremlinLeader", leader, history(), 75, 0).id,
                "num≥75 且上一招不是 4 → 三连刺");
        assertEquals(2, decideIn(solo, "GremlinLeader", leader, history(4), 90, 0).id,
                "num≥75 但上一招是 4 → Rally");
    }

    /** 小鬼头目：场上有 2 只小鬼时只在「喊话（3）」与「三连刺（4）」之间循环。 */
    @Test
    void gremlinLeaderAlternatesShoutAndStabWithTwoGremlins() {
        SimMonster leader = new SimMonster("GremlinLeader", 148, 148, 0, NO_POWERS);
        SimState trio = field(leader,
                new SimMonster("GremlinWarrior", 24, 24, 0, NO_POWERS),
                new SimMonster("GremlinFat", 17, 17, 0, NO_POWERS));

        MonsterMove shout = decideIn(trio, "GremlinLeader", leader, history(), 10, 0);
        assertEquals(3, shout.id, "num<66 且上一招不是 3 → 喊话");
        assertEquals("Strength", shout.buffAlliesPower, "全队（含自己）+力量");
        assertEquals(3, shout.buffAlliesAmount);
        assertEquals(6, shout.extra.blockAllies, "只有**别人**加格挡");

        assertEquals(4, decideIn(trio, "GremlinLeader", leader, history(3), 10, 0).id,
                "上一招是喊话 → 三连刺");
        assertEquals(4, decideIn(trio, "GremlinLeader", leader, history(), 66, 0).id,
                "num≥66 且上一招不是 4 → 三连刺");
        assertEquals(3, decideIn(trio, "GremlinLeader", leader, history(4), 66, 0).id,
                "num≥66 但上一招是 4 → 喊话");
        assertEquals(5, decideIn(trio, "GremlinLeader", leader, history(), 10, 18).buffAlliesAmount,
                "A18 起 +5 力量");
        assertEquals(10, decideIn(trio, "GremlinLeader", leader, history(), 10, 18)
                .extra.blockAllies, "A18 起队友 +10 格挡");
    }

    /**
     * 小鬼头目：「只剩 1 只小鬼」档里 num&lt;50 且上一招是 Rally 时，
     * 原版会 {@code getMove(aiRng.random(50,99))} **再抽一次** aiRng。
     */
    @Test
    void gremlinLeaderRecursesAndBurnsASecondAiRoll() {
        SimMonster leader = new SimMonster("GremlinLeader", 148, 148, 0, NO_POWERS);
        SimState duo = field(leader, new SimMonster("GremlinWarrior", 24, 24, 0, NO_POWERS));
        duo.rng.put(SimState.RngKind.AI, RngStream.from(mix(5), mix(6), 0));

        leader.moveHistory.addAll(history(2));
        int before = duo.rng.get(SimState.RngKind.AI).counter();
        MonsterMove move = MonsterMoves.machineFor("GremlinLeader").next(
                new MonsterMoves.Context(duo, leader, leader.moveHistory, 1, 0, 10, 0));
        int after = duo.rng.get(SimState.RngKind.AI).counter();
        assertTrue(after > before, "递归里又抽了一次 aiRng（消耗 " + (after - before) + " 次）");
        assertTrue(move.id == 4 || move.id == 3, "递归后的分支只可能出 3/4：" + move);
    }

    /**
     * Rally 的落点：只剩 1 只小鬼时一次补齐到 3 只（两个槽各填一只），
     * 而且新来的两只都挂 {@code Minion}、血量取自 {@code monsterHpRng}。
     */
    @Test
    void gremlinLeaderRallyFillsTheTwoFreeSlots() {
        SimState s = new SimState();
        s.rng.put(SimState.RngKind.AI, RngStream.from(mix(1), mix(2), 0));
        s.rng.put(SimState.RngKind.MONSTER_HP, RngStream.from(mix(3), mix(4), 0));
        SimMonster leader = new SimMonster("GremlinLeader", 148, 148, 0, NO_POWERS);
        s.monsters.add(leader);
        s.monsters.add(new SimMonster("GremlinWarrior", 24, 24, 0, NO_POWERS));

        MonsterMove rally = decideIn(s, "GremlinLeader", leader, history(), 10, 0);
        assertEquals(2, rally.id, "只剩 1 只小鬼 + num<50 → Rally");
        leader.pendingMove = rally;
        leader.pendingMoveId = rally.id;
        CombatFlow.endTurn(s, 3, 5);

        int gremlins = 0;
        int minions = 0;
        for (SimMonster m : s.monsters) {
            if (m.hp > 0 && m != leader) {
                gremlins++;
                if (m.powers.containsKey("Minion")) {
                    minions++;
                }
            }
        }
        assertEquals(3, gremlins, "1 只 + 召唤 2 只 = 3 只（gremlins[] 的三个槽）");
        assertEquals(2, minions, "新召的两只带 Minion（开场那只没在影子层补挂）");
    }

    /** 一只小鬼都没有时 Rally 只造 2 只（原版只排了两次 SummonGremlinAction）。 */
    @Test
    void gremlinLeaderRallySpawnsTwoWhenAlone() {
        SimState s = new SimState();
        s.rng.put(SimState.RngKind.AI, RngStream.from(mix(9), mix(10), 0));
        s.rng.put(SimState.RngKind.MONSTER_HP, RngStream.from(mix(11), mix(12), 0));
        SimMonster leader = new SimMonster("GremlinLeader", 148, 148, 0, NO_POWERS);
        s.monsters.add(leader);

        MonsterMove rally = decideIn(s, "GremlinLeader", leader, history(), 10, 0);
        assertEquals(2, rally.id);
        leader.pendingMove = rally;
        leader.pendingMoveId = rally.id;
        CombatFlow.endTurn(s, 3, 5);

        assertEquals(3, s.monsters.size(), "首领 + 2 只小鬼");
    }

    /** 收藏家：开场召唤、之后按 num / 大招 / 补召唤的次序走。 */
    @Test
    void collectorOpensWithTorchHeadsThenRotates() {
        MonsterMove opening = decide("TheCollector", history(), 50, 0, 0);
        assertEquals(1, opening.id, "开场招固定是 1");
        assertNotNull(opening.extra.summon);
        assertEquals("TorchHead", opening.extra.summon.pool[0].type);
        assertEquals(2, opening.extra.summon.count, "一次造两只");
        assertEquals(38, opening.extra.summon.pool[0].hpMin);
        assertEquals(40, opening.extra.summon.pool[0].hpMax);

        assertEquals(2, decide("TheCollector", history(1), 50, 0, 0).id,
                "turnsTaken=1、num≤70 → 一段 rake");
        assertEquals(18, decide("TheCollector", history(1), 50, 0, 0).damage, "rakeDmg 18");
        assertEquals(21, decide("TheCollector", history(1), 50, 0, 4).damage, "A4 起 21");

        MonsterMove ult = decide("TheCollector", history(1, 2, 3), 50, 0, 0);
        assertEquals(4, ult.id, "turnsTaken≥3 且大招没用过 → Mega Debuff");
        assertEquals("Weakened", ult.playerDebuff);
        assertEquals(3, ult.playerDebuffAmount);
        assertEquals("Vulnerable", ult.playerDebuff2);
        assertEquals("Frail", ult.extra.playerDebuff3, "第三个减益（载荷里额外带一个）");

        assertEquals(2, decide("TheCollector", history(1, 2, 3, 4), 50, 0, 0).id,
                "大招只用一次，之后照常");
    }

    /** 收藏家 3 号招：自己加格挡 + 全队力量；A19 起格挡再 +5。 */
    @Test
    void collectorBuffGivesBlockAndTeamStrength() {
        // num>70 才会跳到「3 / 2」那条尾巴（num≤70 时优先 rake）
        MonsterMove buff = decide("TheCollector", history(1, 2), 80, 0, 0);
        assertEquals(3, buff.id);
        assertEquals(15, buff.block);
        assertEquals("Strength", buff.buffAlliesPower);
        assertEquals(3, buff.buffAlliesAmount);
        MonsterMove hard = decide("TheCollector", history(1, 2), 80, 0, 19);
        assertEquals(18 + 5, hard.block, "A19 起格挡 18+5");
        assertEquals(5, hard.buffAlliesAmount);
    }

    /** 收藏家 5 号招：有火把头死掉、num≤25、上一招不是 5 时才补。 */
    @Test
    void collectorResummonsOnlyWhenATorchHeadDied() {
        SimState s = field(new SimMonster("TheCollector", 282, 282, 0, NO_POWERS),
                new SimMonster("TorchHead", 0, 40, 0, NO_POWERS),
                new SimMonster("TorchHead", 40, 40, 0, NO_POWERS));
        SimMonster collector = s.monsters.get(0);
        MonsterMove resummon = decideIn(s, "TheCollector", collector, history(1, 2), 10, 0);
        assertEquals(5, resummon.id, "有死掉的火把头 → 补召唤");
        assertEquals(2, resummon.extra.summon.count);
        assertEquals(2, resummon.extra.summon.cap, "enemySlots 只有两个槽");

        SimState none = field(new SimMonster("TheCollector", 282, 282, 0, NO_POWERS),
                new SimMonster("TorchHead", 40, 40, 0, NO_POWERS),
                new SimMonster("TorchHead", 40, 40, 0, NO_POWERS));
        assertEquals(2, decideIn(none, "TheCollector", none.monsters.get(0),
                history(1, 2), 10, 0).id, "没死就不补，正常出招");
    }

    /** 火把头：永远一下 7 点。 */
    @Test
    void torchHeadAlwaysTackles() {
        MonsterMove hit = decide("TorchHead", history(), 0, 0, 0);
        assertEquals(1, hit.id);
        assertEquals(7, hit.damage);
        assertEquals(1, hit.hits);
        assertEquals(1, decide("TorchHead", history(1, 1, 1), 0, 0, 20).id, "永远同一招");
    }

    /** 铜球：Stasis 只放一次；给主人加格挡；否则两下一循环。 */
    @Test
    void bronzeOrbStasisOnceThenFluxOrBeam() {
        assertEquals(3, decide("BronzeOrb", history(), 25, 0, 0).id, "num≥25 且没用过 Stasis");
        assertEquals(1, decide("BronzeOrb", history(), 24, 0, 0).id, "num<24 不放大招");
        assertEquals(1, decide("BronzeOrb", history(3), 25, 0, 0).id,
                "Stasis 用过就不再放（这个标志在 getMove 里就置位）");
        assertEquals(1, decide("BronzeOrb", history(3), 25, 0, 0).id);
        assertEquals(2, decide("BronzeOrb", history(3), 80, 0, 0).id, "num≥70 → 给主人格挡");
        MonsterMove flux = decide("BronzeOrb", history(3), 80, 0, 0);
        assertEquals("BronzeAutomaton", flux.extra.blockAllyType);
        assertEquals(12, flux.extra.blockAllyTypeAmount);
        assertEquals(1, decide("BronzeOrb", history(3, 2, 2), 80, 0, 0).id,
                "连续两招 2 之后不能再来 2");
    }

    /** 铜球 2 号招的落点：格挡加在 BronzeAutomaton 身上，不是自己。 */
    @Test
    void bronzeOrbFluxShieldsItsMaster() {
        SimState s = new SimState();
        s.rng.put(SimState.RngKind.AI, RngStream.from(mix(7), mix(8), 0));
        SimMonster automaton = new SimMonster("BronzeAutomaton", 300, 300, 0, NO_POWERS);
        SimMonster orb = new SimMonster("BronzeOrb", 58, 58, 0, NO_POWERS);
        s.monsters.add(automaton);
        s.monsters.add(orb);
        MonsterMove flux = MonsterMove.blockTypedAlly(2, "BronzeAutomaton", 12);
        orb.pendingMove = flux;
        orb.pendingMoveId = 2;
        CombatFlow.endTurn(s, 3, 5);
        assertEquals(12, automaton.block, "格挡给主人");
        assertEquals(0, orb.block, "自己不加");
    }

    /** 铜制自动机：4/1/5/1/5/2/3 的固定循环（getMove 完全不看 num）。 */
    @Test
    void bronzeAutomatonFollowsItsFixedLoop() {
        java.util.List<Integer> h = new ArrayList<Integer>();
        StringBuilder seq = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            MonsterMove move = decide("BronzeAutomaton", h, 50, 0, 0);
            seq.append(move.id);
            h.add(Integer.valueOf(move.id));
        }
        assertEquals("41515231", seq.toString(),
                "BOOST → FLAIL → 强化 → FLAIL → 强化 → BEAM → STUN → FLAIL");

        MonsterMove boost = decide("BronzeAutomaton", history(), 50, 0, 0);
        assertNotNull(boost.extra.summon, "4 号招是召唤两只铜球");
        assertEquals("BronzeOrb", boost.extra.summon.pool[0].type);
        assertEquals(2, boost.extra.summon.count);

        MonsterMove stun = decide("BronzeAutomaton", history(4, 1, 5, 1, 5, 2), 50, 0, 0);
        assertEquals(3, stun.id, "A20 以下打完 BEAM 是 STUN");
        assertEquals(0, stun.damage, "STUN 这回合不打人");
        assertEquals(5, decide("BronzeAutomaton", history(4, 1, 5, 1, 5, 2), 50, 0, 19).id,
                "A19 起改成强化（格挡 + 力量）");
    }

    /** numTurns 的重放：它只依赖历史，必须与逐条模拟一致。 */
    @Test
    void bronzeAutomatonNumTurnsReplayMatchesHistory() {
        assertEquals(0, MonsterMoves.replayNumTurns(history()));
        assertEquals(0, MonsterMoves.replayNumTurns(history(4)), "首招不加");
        assertEquals(1, MonsterMoves.replayNumTurns(history(4, 1)), "FLAIL 之后 +1");
        assertEquals(2, MonsterMoves.replayNumTurns(history(4, 1, 5)));
        assertEquals(3, MonsterMoves.replayNumTurns(history(4, 1, 5, 1)));
        assertEquals(4, MonsterMoves.replayNumTurns(history(4, 1, 5, 1, 5)),
                "到 4 时下一次决策就是 BEAM");
        assertEquals(0, MonsterMoves.replayNumTurns(history(4, 1, 5, 1, 5, 2)), "BEAM 后清零");
        assertEquals(0, MonsterMoves.replayNumTurns(history(4, 1, 5, 1, 5, 2, 3)),
                "STUN 那条路径不自增");
        assertEquals(1, MonsterMoves.replayNumTurns(history(4, 1, 5, 1, 5, 2, 3, 1)));
    }

    // ============================ 第十八批：Champ（半血三阶段） ============================

    /** 勇士：开场按 num 分档（姿态 / 自夸 / 扇脸 / 重斩）。 */
    @Test
    void champOpensWithStanceGloatSlapOrSlash() {
        MonsterMove stance = decide("Champ", history(), 10, 0, 0);
        assertEquals(2, stance.id, "num≤15 且姿态没用满 → Defensive Stance");
        assertEquals(15, stance.block);
        assertEquals("Metallicize", stance.selfBuff, "Forge = 金属化");
        assertEquals(5, stance.selfBuffAmount);

        MonsterMove gloat = decide("Champ", history(), 20, 0, 0);
        assertEquals(5, gloat.id, "num∈(15,30] → Gloat");
        assertEquals("Strength", gloat.selfBuff);
        assertEquals(2, gloat.selfBuffAmount, "A4 以下 +2 力量");

        MonsterMove slap = decide("Champ", history(), 40, 0, 0);
        assertEquals(4, slap.id, "num∈(30,55] → Face Slap");
        assertEquals(12, slap.damage, "A4 以下 slapDmg 12");
        assertEquals("Weakened", slap.playerDebuff);
        assertEquals("Vulnerable", slap.playerDebuff2);

        MonsterMove slash = decide("Champ", history(), 60, 0, 0);
        assertEquals(1, slash.id, "num>55 → Heavy Slash");
        assertEquals(16, slash.damage, "A4 以下 slashDmg 16");
    }

    /** 勇士：姿态最多两次（forgeThreshold=2），A19 起才放宽 num 门槛到 30。 */
    @Test
    void champStopsForgingAfterTwoStances() {
        assertEquals(4, decide("Champ", history(2), 10, 0, 0).id,
                "上一招就是姿态（!lastMove(2) 拦住了）→ 不能连着打");
        assertEquals(2, decide("Champ", history(2, 5), 10, 0, 0).id,
                "上一次姿态之后隔了一招 → 还能再打（forgeTimes 才 1）");
        // 注意 history 长度会推进 numTurns，第 4 次决策会被 Taunt 抢走，所以这里多用一招避开
        assertEquals(5, decide("Champ", history(2, 5, 2, 4), 10, 0, 0).id,
                "两次姿态用满（forgeThreshold=2）→ num 再小也不打姿态，改打 Gloat");
        assertEquals(5, decide("Champ", history(), 20, 0, 0).id, "A19 以下 num>15 不打姿态 → Gloat");
        assertEquals(2, decide("Champ", history(), 20, 0, 19).id, "A19 起门槛放宽到 30 → 仍可打姿态");
    }

    /** 勇士：第 4 次决策是 Taunt（玩家 Weak 2 + Vulnerable 2，不打人）。 */
    @Test
    void champTauntsOnTheFourthDecision() {
        assertEquals(4, MonsterMoves.champNumTurns(history(1, 2, 5)), "第三次决策后是 4");
        MonsterMove taunt = decide("Champ", history(1, 2, 5), 10, 0, 0);
        assertEquals(6, taunt.id, "numTurns==4 → Taunt，优先于姿态");
        assertEquals(0, taunt.damage, "Taunt 这回合不打人");
        assertEquals("Weakened", taunt.playerDebuff);
        assertEquals("Vulnerable", taunt.playerDebuff2);
        assertEquals(1, MonsterMoves.champNumTurns(history(1, 2, 5, 6)), "Taunt 之后清零");
    }

    /** 勇士：掉到半血以下第一件事是 Anger（清自身减益 + 3 倍力量）。 */
    @Test
    void champEnragesBelowHalfHp() {
        SimMonster champ = new SimMonster("Champ", 200, 420, 0, NO_POWERS);
        SimState s = field(champ);
        MonsterMove anger = decideIn(s, "Champ", champ, history(), 10, 0);
        assertEquals(7, anger.id, "200 < 420/2 → Anger");
        assertEquals("Strength", anger.selfBuff);
        assertEquals(6, anger.selfBuffAmount, "A4 以下 strAmt 2 × 3");
        assertEquals(12, decideIn(s, "Champ", champ, history(), 10, 19).selfBuffAmount,
                "A19 起 strAmt 4 × 3");
        assertTrue(anger.extra.cleanseSelf, "RemoveDebuffsAction");

        champ.powers.put("Weakened", Integer.valueOf(2));
        anger = decideIn(s, "Champ", champ, history(), 10, 0);
        champ.pendingMove = anger;
        champ.pendingMoveId = anger.id;
        CombatFlow.endTurn(s, 3, 5);
        assertNull(champ.powers.get("Weakened"), "Anger 会清掉自己身上的减益");
    }

    /** 勇士：半血之后优先处决（两段），但连续两招不能都是处决。 */
    @Test
    void champExecutesAfterEnrage() {
        SimMonster champ = new SimMonster("Champ", 200, 420, 0, NO_POWERS);
        SimState s = field(champ);
        MonsterMove execute = decideIn(s, "Champ", champ, history(7), 10, 0);
        assertEquals(3, execute.id, "半血且前两招都不是 3 → Execute");
        assertEquals(2, execute.hits, "EXEC_COUNT = 2");
        assertEquals(10, execute.damage);
        assertFalse(decideIn(s, "Champ", champ, history(7, 3), 10, 0).id == 3,
                "刚处决过就不能再处决");
    }

    // ============================ 第十九批：六火（Hexaghost）============================

    /** 六火：开场 Activate（不打人），紧接着被 takeTurn 硬设成 Divider（6 段 × 玩家血/12+1）。 */
    @Test
    void hexaghostActivatesThenDivides() {
        MonsterMove activate = decide("Hexaghost", history(), 50, 0, 0);
        assertEquals(5, activate.id, "开场先 Activate");
        assertEquals(0, activate.damage, "Activate 不打人");

        SimState s = new SimState();
        SimMonster ghost = new SimMonster("Hexaghost", 250, 250, 0, NO_POWERS);
        s.monsters.add(ghost);
        s.hp = 120;
        MonsterMove divider = decideIn(s, "Hexaghost", ghost, history(5), 50, 0);
        assertEquals(1, divider.id, "Activate 的 takeTurn 里 setMove(1, ...)");
        assertEquals(6, divider.hits, "Divider 固定 6 段");
        assertEquals(11, divider.damage, "120/12+1 = 11");
    }

    /** 六火：Divider 的伤害在 Activate 那一刻算好（整数除法向下取整）。 */
    @Test
    void hexaghostDividerRoundsDown() {
        SimState s = new SimState();
        SimMonster ghost = new SimMonster("Hexaghost", 250, 250, 0, NO_POWERS);
        s.monsters.add(ghost);
        s.hp = 119;
        assertEquals(10, decideIn(s, "Hexaghost", ghost, history(5), 50, 0).damage, "119/12 = 9 → 10");
        s.hp = 12;
        assertEquals(2, decideIn(s, "Hexaghost", ghost, history(5), 50, 0).damage, "12/12+1 = 2");
        s.hp = 1;
        assertEquals(1, decideIn(s, "Hexaghost", ghost, history(5), 50, 0).damage, "1/12+1 = 1");
    }

    /** 六火：orbActiveCount 决定一切 —— 一场固定的 7 招循环。 */
    @Test
    void hexaghostFollowsTheOrbCycle() {
        MonsterMove sear = decide("Hexaghost", history(5, 1), 50, 0, 0);
        assertEquals(4, sear.id, "Divider 之后 Deactivate → 0 → Sear");
        assertEquals(6, sear.damage, "Sear 6 点");
        assertEquals("Burn", sear.statusCard, "Sear 往弃牌堆塞 Burn");

        MonsterMove tackle = decide("Hexaghost", history(5, 1, 4), 50, 0, 0);
        assertEquals(2, tackle.id, "orb 1 → Tackle");
        assertEquals(2, tackle.hits, "Tackle 两段");
        assertEquals(4, decide("Hexaghost", history(5, 1, 4, 2), 50, 0, 0).id, "orb 2 → Sear 是 4");

        MonsterMove strengthen = decide("Hexaghost", history(5, 1, 4, 2, 4), 50, 0, 0);
        assertEquals(3, strengthen.id, "orb 3 → Strengthen");
        assertEquals(12, strengthen.block, "强化自带 12 格挡");
        assertEquals("Strength", strengthen.selfBuff, "强化给的是力量");

        assertEquals(4, decide("Hexaghost", history(5, 1, 4, 2, 4, 3, 2), 50, 0, 0).id, "orb 5 → Sear");
        MonsterMove inferno = decide("Hexaghost", history(5, 1, 4, 2, 4, 3, 2, 4), 50, 0, 0);
        assertEquals(6, inferno.id, "orb 6 → Inferno");
        assertEquals(6, inferno.hits, "Inferno 6 段");
        assertEquals(4, decide("Hexaghost", history(5, 1, 4, 2, 4, 3, 2, 4, 6), 50, 0, 0).id,
                "Inferno 之后 Deactivate → 循环从头开始（不会再来一次 Divider）");
    }

    /** 六火：orb 计数重放的三个清零点（Activate 置 6 / Divider 与 Inferno 置 0）。 */
    @Test
    void hexaghostOrbCountReplaysFromHistory() {
        assertEquals(6, MonsterMoves.hexaghostOrbs(history(5)), "Activate → 6");
        assertEquals(0, MonsterMoves.hexaghostOrbs(history(5, 1)), "Divider → Deactivate");
        assertEquals(1, MonsterMoves.hexaghostOrbs(history(5, 1, 4)), "Sear +1");
        assertEquals(3, MonsterMoves.hexaghostOrbs(history(5, 1, 4, 2, 4)), "Sear/Tackle/Sear 各 +1");
        assertEquals(0, MonsterMoves.hexaghostOrbs(history(5, 1, 4, 2, 4, 3, 2, 4, 6)), "Inferno 清零");
    }

    /** 六火：进阶档（A4 起重击 6 / 地狱 3；A19 起力量 3、Sear 塞 2 张）。 */
    @Test
    void hexaghostAscensionTiers() {
        java.util.List<Integer> tackle = history(5, 1, 4);
        assertEquals(5, decide("Hexaghost", tackle, 50, 0, 0).damage, "A0：Tackle 5");
        assertEquals(6, decide("Hexaghost", tackle, 50, 0, 4).damage, "A4：Tackle 6");

        java.util.List<Integer> inferno = history(5, 1, 4, 2, 4, 3, 2, 4);
        assertEquals(2, decide("Hexaghost", inferno, 50, 0, 3).damage, "A0：Inferno 每段 2");
        assertEquals(3, decide("Hexaghost", inferno, 50, 0, 4).damage, "A4：Inferno 每段 3");

        assertEquals(1, decide("Hexaghost", history(5, 1), 50, 0, 18).statusCount, "A19 以下 Sear 塞 1 张");
        assertEquals(2, decide("Hexaghost", history(5, 1), 50, 0, 19).statusCount, "A19 起塞 2 张");
        assertEquals(3, decide("Hexaghost", history(5, 1, 4, 2, 4), 50, 0, 19).selfBuffAmount, "A19 强化 +3");
    }

    // ============================ 第二十批：面具强盗的熊与头目 ============================

    /** 熊：getMove 只给开局那一招，之后全靠 takeTurn 里的 SetMove 推着走。 */
    @Test
    void banditBearStunsThenAlternatesSlashAndMaul() {
        MonsterMove stun = decide("BanditBear", history(), 50, 0, 0);
        assertEquals(2, stun.id, "开局 Bear Stun");
        assertEquals(0, stun.damage, "Stun 不打人");
        assertEquals("Dexterity", stun.playerDebuff);
        assertEquals(-2, stun.playerDebuffAmount, "原版 con_reduction = -2（负增量）");
        assertEquals(-4, decide("BanditBear", history(), 50, 0, 17).playerDebuffAmount, "A17 起 -4");

        MonsterMove slash = decide("BanditBear", history(2), 50, 0, 0);
        assertEquals(3, slash.id, "Stun 之后是 Bear Slash");
        assertEquals(9, slash.damage, "A0：9 伤");
        assertEquals(9, slash.block, "自带 9 格挡（不随进阶变）");
        assertEquals(10, decide("BanditBear", history(2), 50, 0, 2).damage, "A2 起 10 伤");

        MonsterMove maul = decide("BanditBear", history(2, 3), 50, 0, 0);
        assertEquals(1, maul.id, "Slash 之后是 Maul");
        assertEquals(18, maul.damage, "A0：18 伤（单段）");
        assertEquals(1, maul.hits);
        assertEquals(20, decide("BanditBear", history(2, 3), 50, 0, 2).damage, "A2 起 20");
        assertEquals(3, decide("BanditBear", history(2, 3, 1), 50, 0, 0).id, "Maul 之后回到 Slash");
    }

    /** 熊的 Stun 在真实结算里必须**扣**玩家敏捷（写正数就会变成加敏捷，格挡算反）。 */
    @Test
    void banditBearStunLowersPlayerDexterityInCombat() {
        SimMonster bear = new SimMonster("BanditBear", 40, 40, 0, NO_POWERS);
        SimState s = field(bear);
        MonsterMove stun = decideIn(s, "BanditBear", bear, history(), 50, 0);
        bear.pendingMove = stun;
        bear.pendingMoveId = stun.id;
        CombatFlow.endTurn(s, 3, 5);
        assertEquals(-2, s.playerPowers.get("Dexterity").intValue(), "Bear Stun 落地是 -2 敏捷");
    }

    /** 头目：喊话 → Agonize（伤害 + Weak）→ Slash，A17 起允许连刺两下。 */
    @Test
    void banditLeaderTauntsThenAgonizesThenSlashes() {
        MonsterMove taunt = decide("BanditLeader", history(), 50, 0, 0);
        assertEquals(2, taunt.id, "开场喊话");
        assertEquals(0, taunt.damage, "喊话不打人");

        MonsterMove agonize = decide("BanditLeader", history(2), 50, 0, 0);
        assertEquals(3, agonize.id, "喊话之后 Agonize");
        assertEquals(10, agonize.damage, "A0：10 伤");
        assertEquals("Weakened", agonize.playerDebuff);
        assertEquals(2, agonize.playerDebuffAmount, "Weaken 2");
        assertEquals(12, decide("BanditLeader", history(2), 50, 0, 2).damage, "A2 起 12 伤");
        assertEquals(3, decide("BanditLeader", history(2), 50, 0, 17).playerDebuffAmount, "A17 起 Weak 3");

        MonsterMove slash = decide("BanditLeader", history(2, 3), 50, 0, 0);
        assertEquals(1, slash.id, "Agonize 之后 Slash");
        assertEquals(15, slash.damage, "A0：15 伤");
        assertEquals(17, decide("BanditLeader", history(2, 3), 50, 0, 2).damage, "A2 起 17 伤");
        assertEquals(3, decide("BanditLeader", history(2, 3, 1), 50, 0, 16).id,
                "A17 以下 Slash 之后回到 Agonize");
    }

    /** 头目 A17 起可以连刺两下，但第三下必须停（lastTwoMoves(1)）。 */
    @Test
    void banditLeaderDoubleStabsOnlyOnAscension17() {
        assertEquals(1, decide("BanditLeader", history(2, 3, 1), 50, 0, 17).id,
                "A17：刚刺过一下 → 还能再刺");
        assertEquals(3, decide("BanditLeader", history(2, 3, 1, 1), 50, 0, 17).id,
                "连刺两下之后必须回 Agonize");
        assertEquals(3, decide("BanditLeader", history(2, 3, 1, 1), 50, 0, 0).id,
                "A17 以下不会连刺");
    }

    // ============================ 第二十一批：第三章先头（OrbWalker / Maw / Transient / Repulsor）============================

    /** 观测者：两招互斥，num 只决定「先打哪一招」。 */
    @Test
    void orbWalkerAlternatesClawAndLaser() {
        MonsterMove laser = decide("OrbWalker", history(), 10, 0, 0);
        assertEquals(2, laser.id, "num<40 → 先打 2 号招");
        assertEquals(15, laser.damage, "A0：15 伤");
        assertEquals(16, decide("OrbWalker", history(), 10, 0, 2).damage, "A2 起 16");

        MonsterMove claw = decide("OrbWalker", history(2, 2), 10, 0, 0);
        assertEquals(1, claw.id, "连着两次 2 号招 → 换 1 号招（带 Burn）");
        assertEquals(10, claw.damage, "A0：10 伤");
        assertEquals("Burn", claw.statusCard);
        assertEquals(1, claw.statusCount);

        assertEquals(1, decide("OrbWalker", history(), 60, 0, 0).id, "num>=40 → 先打带 Burn 的那招");
        assertEquals(2, decide("OrbWalker", history(1, 1), 60, 0, 0).id, "连着两次 → 换另一招");
    }

    /** 大嘴：先吼，之后 Nom（段数随回合涨）/ Bellow / Slam 三选一。 */
    @Test
    void mawRoarsThenEscalates() {
        MonsterMove roar = decide("Maw", history(), 50, 0, 0);
        assertEquals(2, roar.id, "第一招必是 Roar");
        assertEquals("Weakened", roar.playerDebuff);
        assertEquals(3, roar.playerDebuffAmount, "A17 以下 Weak 3");
        assertEquals("Frail", roar.playerDebuff2);
        assertEquals(3, roar.playerDebuff2Amount);
        assertEquals(5, decide("Maw", history(), 50, 0, 17).playerDebuffAmount, "A17 起 Weak 5");

        MonsterMove nom = decide("Maw", history(2), 10, 0, 0);
        assertEquals(5, nom.id, "num<50 → Nom");
        assertEquals(5, nom.damage, "每段 5");
        assertEquals(1, nom.hits, "第 2 次决策 turnCount=3 → 3/2 = 1 段");
        assertEquals(2, decide("Maw", history(2, 5, 3), 10, 0, 0).hits, "第 4 次决策 turnCount=5 → 2 段");

        MonsterMove slam = decide("Maw", history(2), 60, 0, 0);
        assertEquals(3, slam.id, "num>=50 且上一招不是 3/5 → Slam");
        assertEquals(25, slam.damage, "A0：25 伤");
        assertEquals(30, decide("Maw", history(2), 60, 0, 2).damage, "A2 起 30");

        MonsterMove bellow = decide("Maw", history(2, 3, 5), 60, 0, 0);
        assertEquals(4, bellow.id, "上一招是 Slam/Nom → Bellow");
        assertEquals("Strength", bellow.selfBuff);
        assertEquals(3, bellow.selfBuffAmount, "A17 以下 +3");
        assertEquals(5, decide("Maw", history(2, 3, 5), 60, 0, 17).selfBuffAmount, "A17 起 +5");
    }

    /** 瞬息：伤害每回合 +10，且 Fading 到点会自己消失。 */
    @Test
    void transientRampsUpDamage() {
        assertEquals(30, decide("Transient", history(), 50, 0, 0).damage, "A0：30 起");
        assertEquals(40, decide("Transient", history(), 50, 0, 2).damage, "A2 起 40");
        assertEquals(40, decide("Transient", history(1), 50, 0, 0).damage, "每出一招 +10");
        assertEquals(50, decide("Transient", history(1, 1), 50, 0, 0).damage);
    }

    /** 瞬息：Fading 递减到 0 时**在出招前**死掉（不是打完再死）。 */
    @Test
    void transientFadesAwayBeforeActing() {
        SimMonster t = new SimMonster("Transient", 999, 999, 0, NO_POWERS);
        t.powers.put("Fading", 2);
        SimState s = field(t);
        MonsterMove atk = decideIn(s, "Transient", t, history(), 50, 0);
        t.pendingMove = atk;
        t.pendingMoveId = atk.id;
        CombatFlow.endTurn(s, 3, 5);
        assertEquals(1, t.powers.get("Fading").intValue(), "Fading 2 → 1，还活着");
        int hpAfterFirst = s.hp;
        t.pendingMove = decideIn(s, "Transient", t, history(1), 50, 0);
        t.pendingMoveId = t.pendingMove.id;
        CombatFlow.endTurn(s, 3, 5);
        assertEquals(0, t.hp, "Fading 到 0 → 消散");
        assertEquals(hpAfterFirst, s.hp, "消散那一回合不再出招，玩家不掉血");
    }

    /** 排斥者：大概率塞 Dazed（进抽牌堆），不会连着打两下。 */
    @Test
    void repulsorDazesInsteadOfAttacking() {
        MonsterMove hit = decide("Repulsor", history(), 10, 0, 0);
        assertEquals(2, hit.id, "num<20 → 打一下");
        assertEquals(11, hit.damage, "A0：11 伤");
        assertEquals(13, decide("Repulsor", history(), 10, 0, 2).damage, "A2 起 13");

        MonsterMove daze = decide("Repulsor", history(), 60, 0, 0);
        assertEquals(1, daze.id, "num>=20 → Daze");
        assertEquals("Dazed", daze.extra.statusDrawCard);
        assertEquals(2, daze.extra.statusDrawCount, "两张");
        assertEquals(1, decide("Repulsor", history(2), 10, 0, 0).id, "刚打过就不能再打");
    }

    /** 排斥者的 Dazed 真的进**抽牌堆**（不是弃牌堆）。 */
    @Test
    void repulsorDazedGoesIntoDrawPile() {
        SimMonster repulsor = new SimMonster("Repulsor", 35, 35, 0, NO_POWERS);
        SimState s = field(repulsor);
        s.draw.add(new SimCard("Strike", false, 1, 6, "ATTACK"));
        s.draw.add(new SimCard("Defend", false, 1, -1, "SKILL"));
        MonsterMove daze = decideIn(s, "Repulsor", repulsor, history(), 60, 0);
        repulsor.pendingMove = daze;
        repulsor.pendingMoveId = daze.id;
        CombatFlow.endTurn(s, 3, 5);
        // 注意 endTurn 末尾会**抽新的一手牌**，所以 Dazed 可能已经进了手牌 —— 三堆一起数
        int dazed = 0;
        for (java.util.List<SimCard> pile : java.util.Arrays.asList(s.hand, s.draw, s.discard)) {
            for (SimCard card : pile) {
                if ("Dazed".equals(card.id)) {
                    dazed++;
                }
            }
        }
        assertEquals(2, dazed, "两张 Dazed 进了牌堆（不是弃牌堆之外的地方）");
    }

    /** 尖刺者：多数时候打人，偶尔磨刺；磨满 6 次就只打不磨。 */
    @Test
    void spikerSharpensUpToSixTimes() {
        MonsterMove hit = decide("Spiker", history(), 10, 0, 0);
        assertEquals(1, hit.id, "num<50 → 打");
        assertEquals(7, hit.damage, "A0：7 伤");
        assertEquals(9, decide("Spiker", history(), 10, 0, 2).damage, "A2 起 9");

        MonsterMove sharpen = decide("Spiker", history(1), 60, 0, 0);
        assertEquals(2, sharpen.id, "num>=50 → 磨刺");
        assertEquals("Thorns", sharpen.selfBuff);
        assertEquals(2, sharpen.selfBuffAmount, "每次 +2 荆棘");

        assertEquals(2, decide("Spiker", history(1, 1), 10, 0, 0).id, "刚打过就不能连打 → 只能磨刺");
        MonsterMove late = decide("Spiker", history(1, 2, 2, 2, 2, 2, 2), 60, 0, 0);
        assertEquals(1, late.id, "thornsCount 到 6 之后哪怕 num 很大也只打");
    }

    /** 尖刺者的荆棘反伤：玩家打它 → 玩家掉血（走 DamageCalc.applyThorns）。 */
    @Test
    void spikerThornsHurtsThePlayer() {
        SimMonster spiker = new SimMonster("Spiker", 50, 50, 0, NO_POWERS);
        spiker.powers.put("Thorns", 3);
        SimState s = field(spiker);
        s.hp = 70;
        int before = s.hp;
        io.github.youqing.sts1solver.mirror.DamageCalc.hit(s, spiker, 10);
        assertEquals(40, spiker.hp, "50 - 10 = 40（没格挡）");
        assertEquals(before - 3, s.hp, "玩家吃 3 点荆棘反伤");
    }
}
