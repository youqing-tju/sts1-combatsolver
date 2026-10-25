package io.github.youqing.sts1solver.sim;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 镜像表的**体检**：23 只怪的机器名一旦写错（拼错、多空格、映射到别的包），
 * 那只怪会静默退回 `IntentLearning` —— 不报错、不崩溃，只是「以为镜像了其实没跑」。
 *
 * <p>所以这里做两件事：
 * <ol>
 *   <li>每个登记的类型名必须能在游戏 jar 里找到对应的类（说明不是笔误）；</li>
 *   <li>每个机器在**空历史**下都得能吐出一招（战斗第一回合就会走到这条路，
 *       抛异常就等于那只怪进场即崩）。</li>
 * </ol>
 */
class MonsterRegistryHealthTest {

    private static final String[] PACKAGES = {
            "com.megacrit.cardcrawl.monsters.exordium.",
            "com.megacrit.cardcrawl.monsters.city.",
            "com.megacrit.cardcrawl.monsters.beyond.",
            "com.megacrit.cardcrawl.monsters.ending.",
    };

    /**
     * 只问「这个类在不在」，**不初始化**它。
     *
     * <p>🔴 必须用 {@code loadClass} / {@code forName(name, false, cl)}：
     * 怪物类的静态初始化块会去读游戏本地化（{@code CardCrawlGame.languagePack}），
     * 在测试环境里直接炸 {@code ExceptionInInitializerError}。
     */
    private static boolean classExists(String type) {
        for (String pkg : PACKAGES) {
            try {
                Class.forName(pkg + type, false, MonsterRegistryHealthTest.class.getClassLoader());
                return true;
            } catch (ClassNotFoundException ignored) {
                // 换下一个包
            }
        }
        return false;
    }

    /** 登记的类型名必须对得上真实类名 —— 拼错一个字就是「静默不生效」。 */
    @Test
    void everyRegisteredTypeMatchesARealMonsterClass() {
        List<String> missing = new ArrayList<String>();
        for (String type : MonsterMoves.mirroredTypes()) {
            if (!classExists(type)) {
                missing.add(type);
            }
        }
        assertTrue(missing.isEmpty(),
                "这些类型名在游戏 jar 里找不到对应类（多半是拼错了）：" + missing);
    }

    /** 每只怪在空历史下都要能出招（第一回合会走这条路）。 */
    @Test
    void everyMachineCanProduceAMoveOnAnEmptyHistory() {
        for (String type : MonsterMoves.mirroredTypes()) {
            SimState state = new SimState();
            state.rng.put(SimState.RngKind.AI, RngStream.from(11L, 22L, 0));
            SimMonster monster = new SimMonster(type, 50, 50, 0,
                    Collections.<String, Integer>emptyMap());
            state.monsters.add(monster);
            MonsterMove move = MonsterMoves.machineFor(type).next(
                    new MonsterMoves.Context(state, monster, monster.moveHistory, 1, 0, 50, 0));
            assertNotNull(move, type + " 在空历史下没有出招");
        }
    }

    /** 镜像表里不该出现未登记就调用的类型（`next` 必须显式拒绝）。 */
    @Test
    void nextRejectsUnknownTypes() {
        SimState state = new SimState();
        SimMonster monster = new SimMonster("NotAMonster", 10, 10, 0,
                Collections.<String, Integer>emptyMap());
        assertTrue(!MonsterMoves.supported("NotAMonster"));
        try {
            MonsterMoves.next(state, monster, 1, 0);
            throw new AssertionError("未登记的类型必须抛 IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("no monster mirror"),
                    "错误信息要说清是没镜像：" + expected.getMessage());
        }
    }
}
