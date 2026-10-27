package io.github.youqing.sts1solver.sim;

import io.github.youqing.sts1solver.mirror.ActionRejected;
import io.github.youqing.sts1solver.mirror.CardPlayer;
import io.github.youqing.sts1solver.search.BeamSolver;
import io.github.youqing.sts1solver.search.PlanAction;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 把**每一只**已镜像的怪物丢进真实回合循环里跑一遍。
 *
 * <p>现有的两个测试都覆盖不到这条路：
 * <ul>
 *   <li>{@code MonsterRegistryHealthTest} 只问「空历史能不能吐出一招」——不出招的前置条件
 *       （召唤、分裂、半死、换形态）它一概碰不到；</li>
 *   <li>{@code SelfPlayTest} 会把所有异常 {@code catch (Throwable) { return; }} 吞掉 ——
 *       机器写错时它照样绿。</li>
 * </ul>
 *
 * <p>而这个循环恰恰是 M5「全自动 20 场无异常」的判据所在：怪物的招一旦在某回合抛异常，
 * 真机上就是整场崩。所以这里**不吞任何异常**（只精确放行 {@link ActionRejected} ——
 * 那是「这张牌打不出」的正常信号），失败信息带怪物名，一次跑完能看全所有问题。
 */
class MonsterSmokeTest {

    /** 铁甲战士的典型开局牌组（10 张，全部是简单效果，不引入选牌/多选分支）。 */
    private static void fillDeck(SimState s) {
        for (int i = 0; i < 5; i++) {
            s.draw.add(new SimCard("Strike_R", false, 1));
        }
        for (int i = 0; i < 4; i++) {
            s.draw.add(new SimCard("Defend_R", false, 1));
        }
        s.draw.add(new SimCard("Bash", false, 2));
    }

    /**
     * 一条「什么 RNG 都有」的快照：怪物出招要 AI、召唤要 MONSTER_HP、
     * 洗牌要 SHUFFLE、抽牌带混乱要 CARD。缺哪个都会变成 ActionRejected —— 那是**环境问题**，
     * 不是被测对象的问题，所以这里全部灌好。
     */
    private static SimState state(String monsterType) {
        SimState s = new SimState();
        s.hp = 80;
        s.maxHp = 80;
        s.energy = 3;
        s.turn = 1;
        fillDeck(s);
        s.rng.put(SimState.RngKind.AI, RngStream.from(1234L, 5678L, 0));
        s.rng.put(SimState.RngKind.CARD, RngStream.from(11L, 22L, 0));
        s.rng.put(SimState.RngKind.SHUFFLE, RngStream.from(33L, 44L, 0));
        s.rng.put(SimState.RngKind.MISC, RngStream.from(55L, 66L, 0));
        s.rng.put(SimState.RngKind.MONSTER, RngStream.from(77L, 88L, 0));
        s.rng.put(SimState.RngKind.MONSTER_HP, RngStream.from(99L, 111L, 0));
        // 120 血够大多数怪活过 8 个回合；Boss 本来就更厚，测的是「不崩」不是「打得死」
        s.monsters.add(new SimMonster(monsterType, 120, 120, 0,
                Collections.<String, Integer>emptyMap()));
        return s;
    }

    private static boolean allDead(SimState s) {
        for (SimMonster m : s.monsters) {
            if (m.hp > 0) {
                return false;
            }
        }
        return true;
    }

    private static int firstAlive(SimState s) {
        for (int i = 0; i < s.monsters.size(); i++) {
            if (s.monsters.get(i).hp > 0) {
                return i;
            }
        }
        return -1;
    }

    /** 每回合不搜索，直接「能打就打」（足够触发怪物侧的一切结算）。 */
    private static void playGreedy(SimState s) {
        for (int guard = 0; guard < 20; guard++) {
            int target = firstAlive(s);
            boolean played = false;
            for (int index = 0; index < s.hand.size(); index++) {
                try {
                    CardPlayer.play(s, index, target, -1);
                    played = true;
                    break;
                } catch (ActionRejected rejected) {
                    // 这张打不出（费用不够 / 诅咒 / 状态牌 / 目标非法）—— 换下一张
                }
            }
            if (!played || allDead(s)) {
                return;
            }
        }
    }

    /**
     * 意图必须是个「能落地」的数：负数 ID、负伤害、负段数都说明机器写错了。
     *
     * <p>⚠️ **不能**用「多段攻击但每段 0 伤」当判据：原版里本来就有这种招
     * （{@code SpikeSlime_L} 的 LICK 是 0 伤 + Frail，意图仍是 {@code ATTACK_DEBUFF}；
     * {@code AcidSlime_L} 的 LICK 同理）。所以这里只查范围。
     */
    private static void assertIntentSane(String type, int turn, SimState s, List<String> failures) {
        for (SimMonster m : s.monsters) {
            if (m.hp <= 0) {
                continue;
            }
            MonsterMove move = m.pendingMove;
            if (move == null) {
                // 未镜像的怪走老路径（intentDmg），不算失败
                continue;
            }
            if (move.id < 0) {
                failures.add(type + " 第 " + turn + " 回合：招式 id 为负 " + move.id);
            }
            if (move.damage < 0 || move.hits < 0 || move.block < 0
                    || move.damage > 200 || move.hits > 20 || move.block > 200) {
                failures.add(type + " 第 " + turn + " 回合：载荷离谱 damage=" + move.damage
                        + " hits=" + move.hits + " block=" + move.block + "（id=" + move.id + "）");
            }
            if (m.hp > m.maxHp) {
                failures.add(type + " 第 " + turn + " 回合：血量超过上限 " + m.hp + "/" + m.maxHp);
            }
        }
    }

    /**
     * 每只怪跑 8 个回合：玩家「能打就打」→ 怪物出招 → 翻页。
     *
     * <p>只放行 {@link ActionRejected}；别的异常一律记进失败清单（带怪物名），
     * 这样一次能跑完全部 66 只，而不是第一只就中断。
     */
    @Test
    void everyMonsterSurvivesEightRealTurns() {
        assertTrue(MonsterMoves.mirroredTypes().size() >= 60,
                "镜像表太小（" + MonsterMoves.mirroredTypes().size() + " 只）—— 这份冒烟测试等于没跑");
        List<String> failures = new ArrayList<String>();
        for (String type : MonsterMoves.mirroredTypes()) {
            SimState s = state(type);
            try {
                for (int turn = 1; turn <= 8; turn++) {
                    if (s.hp <= 0 || allDead(s)) {
                        break;
                    }
                    playGreedy(s);
                    if (allDead(s)) {
                        break;
                    }
                    CombatFlow.endTurn(s, 3, 5);
                    assertIntentSane(type, turn, s, failures);
                    if (s.hp <= 0) {
                        // 玩家死了也算正常结局（这不是「必须赢」的测试）
                        break;
                    }
                }
                if (s.monsters.isEmpty()) {
                    failures.add(type + "：怪物列表被清空了（连尸体都不该留空）");
                }
            } catch (Throwable t) {
                failures.add(type + " → " + t.getClass().getSimpleName() + ": " + t.getMessage());
            }
        }
        assertTrue(failures.isEmpty(),
                "有怪物在真实回合循环里崩了（" + failures.size() + " 只）：\n  "
                        + String.join("\n  ", failures));
    }

    /**
     * 每只怪都要能被搜索规划出一手，而且**那一手必须真的打得出去**。
     *
     * <p>「规划出来但执行被拒」是最危险的一类不一致：搜索按能打推演，执行却拒绝，
     * 于是整条路线都建立在假前提上。
     */
    @Test
    void solverFirstActionIsExecutableForEveryMonster() {
        List<String> failures = new ArrayList<String>();
        for (String type : MonsterMoves.mirroredTypes()) {
            SimState s = state(type);
            try {
                BeamSolver solver = new BeamSolver(3, 5, 3, 2, 1500, 0);
                PlanAction action = solver.bestFirstAction(s, 300L);
                assertNotNull(action, "no action");
                if (action.kind != PlanAction.Kind.END_TURN) {
                    if (action.cardIndex < 0 || action.cardIndex >= s.hand.size()) {
                        failures.add(type + "：规划的手牌下标越界 " + action.cardIndex
                                + "（手牌 " + s.hand.size() + " 张）");
                        continue;
                    }
                    CardPlayer.play(s, action.cardIndex, action.targetIndex, action.choiceIndex);
                }
            } catch (Throwable t) {
                failures.add(type + " → " + t.getClass().getSimpleName() + ": " + t.getMessage());
            }
        }
        assertTrue(failures.isEmpty(),
                "有怪物让搜索/执行失败（" + failures.size() + " 只）：\n  "
                        + String.join("\n  ", failures));
    }
}
