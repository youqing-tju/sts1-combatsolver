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
        SimMonster cultist = monster("Looter");   // 还没镜像的怪
        cultist.intentDmg = 6;
        s.monsters.add(cultist);
        for (int i = 0; i < 8; i++) {
            s.draw.add(new SimCard("Strike_R", false, 1, 6, "ATTACK"));
        }

        CombatFlow.endTurn(s, 3, 5);

        assertEquals(44, s.hp, "未镜像的怪仍按意图伤害打一下");
        assertNull(cultist.pendingMove, "未镜像的怪不该有镜像招式");
        assertTrue(cultist.moveHistory.isEmpty(), "未镜像的怪不记镜像历史");
        assertFalse(MonsterMoves.supported("Looter"));
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
}
