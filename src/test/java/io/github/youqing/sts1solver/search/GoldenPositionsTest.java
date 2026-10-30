package io.github.youqing.sts1solver.search;

import io.github.youqing.sts1solver.sim.SimCard;
import io.github.youqing.sts1solver.sim.SimMonster;
import io.github.youqing.sts1solver.sim.SimState;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 金标准局面库：对每个真机/回放局面，用**真机同款配置**跑一次搜索，检查第一手是否合格。
 *
 * <p>存在意义：用户抱怨「牌打得臭」时，我们以前只能靠感觉讨论。现在每个局面都是可复现的文件，
 * 改任何机制/权重之后跑一遍，谁的第一手变了、变成什么，一眼就能看到。
 *
 * <p>判据只放**客观可判的**那几条（不猜"高手的打法"）：
 * <ol>
 *   <li>手里有费用够的攻击牌、场上还有活怪时，**不许**第一步就结束回合；</li>
 *   <li>能一刀补掉残血怪时，第一步必须是打牌（且目标就是那只怪）；</li>
 *   <li>跑完把每手的选择打出来 —— 变好变坏都看得见，不靠断言硬编码"标准答案"。</li>
 * </ol>
 */
class GoldenPositionsTest {

    private static final File DIR = new File("src/test/resources/golden");
    private static final int ENERGY = 3;
    private static final int HAND_SIZE = 5;

    /** 与真机 config（极高档）一致：宽 135 / 节点 50 万 / 分支 72，组合与探索都开。 */
    private static BeamSolver realConfig() {
        return new BeamSolver(ENERGY, HAND_SIZE, 135, 500, 500_000, 72)
                .withNovelty()
                .withPortfolio();
    }

    private static List<File> cases() {
        File[] files = DIR.listFiles((dir, name) -> name.endsWith(".json"));
        List<File> out = new ArrayList<File>(files == null
                ? java.util.Collections.<File>emptyList() : Arrays.asList(files));
        java.util.Collections.sort(out);
        return out;
    }

    private static int firstAlive(SimState s) {
        for (int i = 0; i < s.monsters.size(); i++) {
            if (s.monsters.get(i).hp > 0) {
                return i;
            }
        }
        return -1;
    }

    /** 手里有没有「费用够、且是攻击牌」的牌。 */
    private static SimCard playableAttack(SimState s) {
        for (SimCard c : s.hand) {
            int cost = c.costForTurn >= 0 ? c.costForTurn : c.cost;
            if ("ATTACK".equals(c.type) && cost >= 0 && cost <= s.energy) {
                return c;
            }
        }
        return null;
    }

    @Test
    void everyGoldenPositionTakesAReasonableFirstAction() throws Exception {
        List<File> files = cases();
        assertTrue(files.size() >= 4, "金标准库不该是空的：" + DIR.getAbsolutePath());

        StringBuilder report = new StringBuilder("\n=== 金标准局面库（真机配置）===\n");
        for (File file : files) {
            SimState s = GoldenPositions.read(file);
            SimState before = s.copy();
            BeamSolver solver = realConfig();
            PlanAction action = solver.bestFirstAction(s, 60_000L);

            assertNotNull(action, file.getName() + " 没给出动作");
            SimCard attack = playableAttack(before);
            int alive = firstAlive(before);

            if (alive >= 0 && attack != null) {
                assertEquals(PlanAction.Kind.PLAY_CARD, action.kind,
                        file.getName() + "：手里有可打的攻击牌（" + attack.id
                                + "，费 " + attack.cost + "，能量 " + before.energy
                                + "），第一步不该结束回合（" + GoldenPositions.describe(before) + "）");
            }

            // 能补刀就必须补刀（用捕获到的卡面伤害判断，够狠才要求必杀）
            if (alive >= 0 && attack != null) {
                SimMonster target = before.monsters.get(alive);
                int cost = attack.costForTurn >= 0 ? attack.costForTurn : attack.cost;
                boolean lethal = attack.baseDamage > 0 && attack.baseDamage >= target.hp
                        && cost <= before.energy;
                if (lethal) {
                    assertEquals(alive, action.targetIndex,
                            file.getName() + "：能一刀补掉 " + target.type + "（" + target.hp
                                    + " 血），目标却选了 " + action.targetIndex);
                }
            }

            StringBuilder steps = new StringBuilder();
            int shown = 0;
            for (RouteStep step : solver.lastSteps) {
                if (shown++ >= 6) {
                    steps.append(" …");
                    break;
                }
                steps.append(step.endTurn ? "END_TURN"
                        : (step.potion ? "POTION:" + step.cardKey : step.cardKey)).append(" | ");
            }
            report.append(String.format("%-28s %-46s %s%n       首手 %s  %s%n       路线 %s%n",
                    file.getName(), GoldenPositions.describe(before),
                    "nodes=" + solver.lastNodes + " port=" + solver.lastPortfolioAdopted,
                    action.kind, action.kind == PlanAction.Kind.PLAY_CARD
                            ? ("card=" + (action.cardIndex >= 0 && action.cardIndex < before.hand.size()
                                    ? before.hand.get(action.cardIndex).key() : "?")
                            + " -> " + action.targetIndex) : "",
                    steps));
        }
        System.out.println(report);
    }
}
