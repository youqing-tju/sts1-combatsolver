package io.github.youqing.sts1solver.search;

import io.github.youqing.sts1solver.mirror.CardPlayer;
import io.github.youqing.sts1solver.sim.CombatFlow;
import io.github.youqing.sts1solver.sim.MonsterMove;
import io.github.youqing.sts1solver.sim.MonsterMoves;
import io.github.youqing.sts1solver.sim.RngStream;
import io.github.youqing.sts1solver.sim.SimCard;
import io.github.youqing.sts1solver.sim.SimMonster;
import io.github.youqing.sts1solver.sim.SimState;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 离线「第一幕连环战」：一副 15 张的铁甲战士牌组，连着打 6 场第一章遭遇
 * （3 场普通 + 2 场精英 + 3 只哨卫），血量**跨场继承**。
 *
 * <p>为什么要有它：单场 {@code SelfPlayTest} 只能说明「这一场打得过」，
 * 说明不了「战损是多少」。而验收判据 A3 写的是「**20 场战损 ≤ 基线**」——
 * 没有基线就没法判。这份测试就是把基线量出来的地方：它打印每场的回合数与掉血，
 * 并把总战损落在断言里（数字写在 {@link #MAX_TOTAL_HP_LOSS}）。
 *
 * <p>它是**回归网**：任何一次改动让某一环的估值/机制变差，总战损就会涨，
 * 涨过阈值立刻红。判据只有「不崩 + 总战损不超阈值」——
 * 不要求每场都赢（那会把单场的运气写进判据里）。
 *
 * <p>RNG 全部固定种子，所以这个数字是**可复现**的。
 */
class ActOneGauntletTest {

    /**
     * 毛战损上限。**基线 93 血 / 63 净（2026-10-27 首次实测，6 场全胜、余 17/80）**，
     * 这里留 ~13% 余量。
     *
     * <p>它是**回归网**，不是质量线：涨过 105 就说明某处机制/估值退化了，必须查明是哪一项。
     * 反过来，把估值做好了应该让它往下走 —— 到时候同步下调这个数。
     */
    private static final int MAX_TOTAL_HP_LOSS = 105;

    /**
     * 真机默认配置（宽 4 的迷你档 + 宽度组合 + 新颖度）的毛战损上限。
     *
     * <p>2026-10-30 实测 **37 血 / 6 场全胜 / 余 61**（同一份牌组、同一批种子，单趟 Beam 是 93 / 余 17）。
     * 同一份局面把宽度从 4 拉到真机档 135（宽 32 倍、节点 125 倍）实测是 **36 血** ——
     * 说明「再加算力」换不来更好的牌，短板在模型与评估，不在预算。所以这里继续用宽 4 跑得快。
     * 留 ~35% 余量：涨过 50 就说明组合或探索的选路退化了。
     */
    private static final int MAX_TOTAL_HP_LOSS_PORTFOLIO = 50;

    /** 起手 80 血。 */
    private static final int START_HP = 80;

    /** 燃烧之血：每场战斗胜利后回 6 血（原版常量，见 {@code PostCombatRelicHeal}）。 */
    private static final io.github.youqing.sts1solver.mirror.PostCombatRelicHeal POST_COMBAT_HEAL =
            io.github.youqing.sts1solver.mirror.PostCombatRelicHeal.of(
                    java.util.Collections.singletonMap("Burning Blood", Integer.valueOf(-1)));

    private static final int ENERGY = 3;
    private static final int HAND_SIZE = 5;

    /** 一场遭遇：名字 + 每只的 (类型, 血量)。 */
    private static final class Encounter {
        final String name;
        final String[] types;
        final int[] hp;

        Encounter(String name, String... typeHp) {
            this.name = name;
            this.types = new String[typeHp.length];
            this.hp = new int[typeHp.length];
            for (int i = 0; i < typeHp.length; i++) {
                int at = typeHp[i].lastIndexOf(':');
                this.types[i] = typeHp[i].substring(0, at);
                this.hp[i] = Integer.parseInt(typeHp[i].substring(at + 1));
            }
        }
    }

    /**
     * 第一幕真实存在的遭遇，按「普通 → 精英 → 普通」排。
     *
     * <p>数值取原版范围的代表值（虱 10–15、颚虫 40–44、邪教徒 48–54、
     * 真菌兽 22–28、地精暴徒 82–86、哨卫 38–42）。**不自己拼遭遇** ——
     * 第一阶段那份「颚虫 + 双虱」是我编的组合，原版没有，战损会虚高。
     */
    private static final Encounter[] GAUNTLET = {
            new Encounter("邪教徒", "Cultist:50"),
            new Encounter("双虱", "LouseNormal:13", "LouseDefensive:14"),
            new Encounter("颚虫", "JawWorm:44"),
            new Encounter("地精暴徒（精英）", "GremlinNob:85"),
            new Encounter("真菌兽 ×2", "FungiBeast:24", "FungiBeast:26"),
            new Encounter("哨卫 ×3（精英）", "Sentry:39", "Sentry:39", "Sentry:39"),
    };

    /**
     * 一副真实的开局牌组（15 张）：5 打 / 4 防 / 冲击 / 剑柄打击 / 缩身 / 铁斩波 / 上勾拳。
     *
     * <p>全部选「镜像里有、且效果简单」的牌 —— 这份测试量的是**宏观战损**，
     * 不该被某张特效牌的实现细节左右。
     */
    private static List<SimCard> deck() {
        List<SimCard> cards = new ArrayList<SimCard>();
        for (int i = 0; i < 5; i++) {
            cards.add(new SimCard("Strike_R", false, 1, 6, "ATTACK"));
        }
        for (int i = 0; i < 4; i++) {
            cards.add(new SimCard("Defend_R", false, 1, -1, "SKILL"));
        }
        cards.add(new SimCard("Bash", false, 2, 8, "ATTACK"));
        cards.add(new SimCard("Pommel Strike", false, 1, 9, "ATTACK"));
        cards.add(new SimCard("Shrug It Off", false, 1, -1, "SKILL"));
        cards.add(new SimCard("Iron Wave", false, 1, 5, "ATTACK"));
        cards.add(new SimCard("Uppercut", false, 2, 13, "ATTACK"));
        return cards;
    }

    /** 开一场：整套牌进抽牌堆，抽起手 5 张，并按机器掷出每只怪的意图。 */
    private static SimState open(String name, Encounter encounter, int hp) {
        SimState s = new SimState();
        s.hp = hp;
        s.maxHp = START_HP;
        s.energy = ENERGY;
        s.turn = 1;
        s.draw.addAll(deck());
        s.rng.put(SimState.RngKind.AI, RngStream.from(20261027L, 11L, 0));
        s.rng.put(SimState.RngKind.CARD, RngStream.from(20261027L, 22L, 0));
        s.rng.put(SimState.RngKind.SHUFFLE, RngStream.from(20261027L, 33L, 0));
        s.rng.put(SimState.RngKind.MISC, RngStream.from(20261027L, 44L, 0));
        s.rng.put(SimState.RngKind.MONSTER, RngStream.from(20261027L, 55L, 0));
        s.rng.put(SimState.RngKind.MONSTER_HP, RngStream.from(20261027L, 66L, 0));
        for (int i = 0; i < encounter.types.length; i++) {
            SimMonster m = new SimMonster(encounter.types[i], encounter.hp[i], encounter.hp[i], 0,
                    Collections.<String, Integer>emptyMap());
            s.monsters.add(m);
            MonsterMove first = MonsterMoves.next(s, m, 1, i);
            m.pendingMove = first;
            m.pendingMoveId = first.id;
        }
        CombatFlow.drawCards(s, HAND_SIZE);
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

    /** 打一个回合：每题重规划（同 AUTO 的逐张决策）。 */
    private static void playTurn(SimState s, BeamSolver solver, long budgetMillis) {
        for (int guard = 0; guard < 15; guard++) {
            if (allDead(s) || s.hp <= 0) {
                return;
            }
            PlanAction action = solver.bestFirstAction(s, budgetMillis);
            if (action == null || action.kind != PlanAction.Kind.PLAY_CARD) {
                return;
            }
            int target = action.targetIndex;
            if (target < 0 || target >= s.monsters.size() || s.monsters.get(target).hp <= 0) {
                target = firstAlive(s);
            }
            CardPlayer.play(s, action.cardIndex, target, action.choiceIndex);
        }
    }

    /** 结果行：`名字  回合数  毛战损  战后回血  结束时血量  胜负`。 */
    private static String row(Encounter e, int turns, int lost, int healed, int hp, boolean won) {
        return String.format("%-20s %2d 回合  掉 %2d 血  回 %d  余 %2d  %s",
                e.name, turns, lost, healed, hp, won ? "胜" : "败");
    }

    @Test
    void sixActOneCombatsStayWithinTheHpLossBaseline() {
        BeamSolver solver = new BeamSolver(ENERGY, HAND_SIZE, 4, 3, 4000, 0);
        StringBuilder report = new StringBuilder("\n[单趟 Beam]\n");
        int totalLost = runGauntlet(solver, report);
        System.out.println(report);
        assertTrue(totalLost <= MAX_TOTAL_HP_LOSS,
                "总战损 " + totalLost + " 超过基线 " + MAX_TOTAL_HP_LOSS + "：" + report);
    }

    /**
     * 真机默认那条路（宽度组合 + 新颖度探索）跑同一份连环战。
     *
     * <p>为什么要它：组合与探索都会**改变选路**，而第一章的战损基线是「单趟 Beam」量出来的。
     * 上线前必须确认换成真机默认配置后第一章不会变差 —— 否则算得再多也是负收益。
     * 判据比单趟宽 3 血（同分时组合保留基线，理论上不该变差，留一点噪声余量）。
     */
    @Test
    void portfolioAndNoveltyDoNotMakeActOneWorse() {
        BeamSolver solver = new BeamSolver(ENERGY, HAND_SIZE, 4, 3, 4000, 0)
                .withNovelty()
                .withPortfolio();
        StringBuilder report = new StringBuilder("\n[真机默认：beam portfolio + novelty]\n");
        int totalLost = runGauntlet(solver, report);
        System.out.println(report);
        assertTrue(totalLost <= MAX_TOTAL_HP_LOSS_PORTFOLIO,
                "开组合/新颖度后第一章战损 " + totalLost + " 超过 " + MAX_TOTAL_HP_LOSS_PORTFOLIO
                        + "（实测基线 37）：" + report);
    }

    /** 跑一遍连环战，返回毛战损；{@code report} 里逐场写一行。 */
    private static int runGauntlet(BeamSolver solver, StringBuilder report) {
        int hp = START_HP;
        int totalLost = 0;
        int won = 0;
        for (Encounter encounter : GAUNTLET) {
            int hpBefore = hp;
            SimState s = open(encounter.name, encounter, hp);
            int turns = 0;
            while (turns < 30 && !allDead(s) && s.hp > 0) {
                turns++;
                playTurn(s, solver, 40L);
                if (allDead(s) || s.hp <= 0) {
                    break;
                }
                CombatFlow.endTurn(s, ENERGY, HAND_SIZE);
            }
            boolean win = allDead(s);
            // 毛战损：这一场实际掉了多少（不含战斗后回血），报告里就看这个数
            int lost = hpBefore - Math.max(0, s.hp);
            hp = Math.max(0, s.hp);
            int healed = 0;
            if (win) {
                won++;
                // 燃烧之血：每场战斗胜利后回 6（`PostCombatRelicHeal.of` 读遗物表）
                healed = POST_COMBAT_HEAL.healFor(hp, START_HP);
                hp = Math.min(START_HP, hp + healed);
            }
            totalLost += lost;
            report.append(row(encounter, turns, lost, healed, hp, win)).append('\n');
            if (hp <= 0) {
                break;
            }
        }
        report.append("合计：").append(won).append('/').append(GAUNTLET.length)
                .append(" 场获胜，毛战损 ").append(totalLost).append(" 血（战后回血已扣除），余 ")
                .append(hp).append('/').append(START_HP).append(" 血\n");
        assertTrue(won == GAUNTLET.length, "第一幕 6 场没打完：" + report);
        return totalLost;
    }
}
