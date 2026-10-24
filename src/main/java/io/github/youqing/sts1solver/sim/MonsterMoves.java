package io.github.youqing.sts1solver.sim;

import java.util.HashMap;
import java.util.Map;

/**
 * 怪物出招的**镜像表** —— 逐个怪物翻译原版 {@code getMove(int)} + {@code takeTurn()}。
 *
 * <p>每条机器的输入是「已经打过的招（{@link SimMonster#moveHistory}）+ 当前回合号」，
 * 输出这一次要用的 {@link MonsterMove}。规则逐条抄自 {@code lib/desktop-1.0.jar} 的字节码
 * （{@code javap -c}），数值也来自字节码里的常量。
 *
 * <p><b>没登记的怪物</b>继续走 {@link IntentLearning}（观测学习）—— 所以覆盖率可以一只一只往上加，
 * 加之前的行为完全不变。
 *
 * <h2>为什么不用 {@code getMove} 的 {@code num} 参数</h2>
 *
 * <p>原版 {@code rollMove()} 会调 {@code getMove(AbstractDungeon.aiRng.random(99))}，也就是说
 * 每次决策都会**消耗一次 aiRng**。我们的影子虽然也有 {@code aiRng} 快照，但一旦在别处少消耗一次，
 * 整条随机序列就错位了。所以这里只翻译 {@code num} **不影响分支**的怪物（确定性循环），
 * 带随机分支的留到后面单独处理（要连带对齐 aiRng 的消耗次数）。
 */
public final class MonsterMoves {

    /** 一次出招的机器：{@code history} 是已经执行过的招（按时间顺序）。 */
    public interface Machine {
        MonsterMove next(java.util.List<Integer> history, int turn);
    }

    private static final Map<String, Machine> MACHINES = new HashMap<String, Machine>();

    static {
        MACHINES.put("SphericGuardian", new SphericGuardianMachine());
    }

    private MonsterMoves() {
    }

    public static boolean supported(String type) {
        return type != null && MACHINES.containsKey(type);
    }

    /** 已镜像的怪物数（诊断用）。 */
    public static int size() {
        return MACHINES.size();
    }

    /**
     * 算下一招。调用方必须先用 {@link #supported(String)} 确认过；
     * 没登记会抛 {@link IllegalArgumentException}（不静默回退，避免「以为镜像了其实没跑」）。
     */
    public static MonsterMove next(String type, java.util.List<Integer> history, int turn) {
        Machine machine = MACHINES.get(type);
        if (machine == null) {
            throw new IllegalArgumentException("no monster mirror for " + type);
        }
        return machine.next(history, turn);
    }

    private static boolean lastMove(java.util.List<Integer> history, int id) {
        return !history.isEmpty() && history.get(history.size() - 1) == id;
    }

    private static boolean lastTwoMoves(java.util.List<Integer> history, int id) {
        int size = history.size();
        return size >= 2 && history.get(size - 1) == id && history.get(size - 2) == id;
    }

    /**
     * 球体守卫（SphericGuardian，Act 2 精英随从）。
     *
     * <p>字节码：{@code getMove(int)} 完全不用 {@code num}（确定性循环），数值在构造器与
     * {@code takeTurn()} 里：
     *
     * <pre>
     * getMove:  firstMove → 2(DEFEND)
     *           secondMove → 4(ATTACK_DEBUFF, dmg)
     *           否则 lastMove(1) → 3(ATTACK_DEFEND, dmg) : 1(ATTACK, dmg × 2 段)
     * takeTurn: 1 → 两段 dmg；2 → 格挡 25（A17+ 35）；3 → 格挡 15 + 一段 dmg；
     *           4 → 一段 dmg + 玩家 Frail 5
     * </pre>
     *
     * <p>基础伤害 10（进阶 2 起 11）；血量 20。第一回合在战斗开始时就 roll 掉了（表现为 history 为空）。
     */
    static final class SphericGuardianMachine implements Machine {
        /** 原版 {@code dmg}：10，进阶 2 起 11。我们按无进阶 10（进阶档另开，等 ascension 进影子再说）。 */
        static final int DAMAGE = 10;
        /** move 2 的格挡（普通 25 / 进阶 17 起 35）。 */
        static final int DEFEND_BLOCK = 25;
        /** move 3 自带的格挡。 */
        static final int ATTACK_DEFEND_BLOCK = 15;
        /** move 4 施加的 Frail 层数。 */
        static final int FRAIL = 5;

        @Override
        public MonsterMove next(java.util.List<Integer> history, int turn) {
            if (history.isEmpty()) {
                return MonsterMove.block(2, DEFEND_BLOCK);
            }
            if (history.size() == 1) {
                return MonsterMove.attackDebuff(4, DAMAGE, "Frail", FRAIL);
            }
            if (lastMove(history, 1)) {
                return MonsterMove.attackBlock(3, DAMAGE, ATTACK_DEFEND_BLOCK);
            }
            return MonsterMove.multiAttack(1, DAMAGE, 2);
        }
    }
}
