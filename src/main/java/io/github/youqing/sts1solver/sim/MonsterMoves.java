package io.github.youqing.sts1solver.sim;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 怪物出招的**镜像表** —— 逐个怪物翻译原版 {@code getMove(int)} + {@code takeTurn()}。
 *
 * <p>规则逐条抄自 {@code lib/desktop-1.0.jar} 的字节码（{@code javap -c}），数值也来自
 * 字节码里的常量（含进阶档）。
 *
 * <p><b>没登记的怪物</b>继续走 {@link IntentLearning}（观测学习）—— 所以覆盖率可以一只一只加，
 * 加之前的行为完全不变。
 *
 * <h2>rollMove 的随机数</h2>
 *
 * <p>原版 {@code AbstractMonster.rollMove()} = {@code getMove(AbstractDungeon.aiRng.random(99))}，
 * 也就是说**每次决策都消耗一次 aiRng**。这里照抄：{@link #next} 每次都先从
 * {@code state.rng(AI)} 抽一个 0..99 的 {@code num}，再交给机器。
 *
 * <p>⚠️ 为什么必须照抄：影子是从真机快照分叉出来的。如果原版在这一步消耗了 aiRng 而我们不消耗，
 * 后面所有用到 aiRng 的地方（怪物内部随机、意图重掷）就会整体错位一格。
 * 现在这一步与蓝本一致，消耗次数也对得上。
 */
public final class MonsterMoves {

    /**
     * 一次决策的上下文（原版 {@code getMove(int num)} 能读到的全部东西）。
     *
     * @param state     影子状态（读 ascension / rng / 怪物组）
     * @param monster   这只怪自己
     * @param history   已经打过的招（按时间顺序，元素是原版 {@code nextMove} 字节值）
     * @param turn      即将到来的回合号
     * @param slot      它在怪物组里的位次（Sentry 这类怪会用 `lastIndexOf(this) % 2`）
     * @param num       {@code aiRng.random(99)} 的结果
     * @param ascension 进阶等级（0 = 无进阶）
     */
    public static final class Context {
        public final SimState state;
        public final SimMonster monster;
        public final List<Integer> history;
        public final int turn;
        public final int slot;
        public final int num;
        public final int ascension;

        Context(SimState state, SimMonster monster, List<Integer> history, int turn, int slot,
                int num, int ascension) {
            this.state = state;
            this.monster = monster;
            this.history = history;
            this.turn = turn;
            this.slot = slot;
            this.num = num;
            this.ascension = ascension;
        }

        /** 原版 {@code lastMove(byte)}。 */
        public boolean lastMove(int id) {
            return !history.isEmpty() && history.get(history.size() - 1).intValue() == id;
        }

        /** 原版 {@code lastTwoMoves(byte)}。 */
        public boolean lastTwoMoves(int id) {
            int size = history.size();
            return size >= 2 && history.get(size - 1).intValue() == id
                    && history.get(size - 2).intValue() == id;
        }

        /** 原版 {@code lastMoveBefore(byte)}：上上招是不是它（历史至少两招）。 */
        public boolean lastMoveBefore(int id) {
            int size = history.size();
            return size >= 2 && history.get(size - 2).intValue() == id;
        }
    }

    /** 一次出招的机器。 */
    public interface Machine {
        MonsterMove next(Context context);
    }

    private static final Map<String, Machine> MACHINES = new HashMap<String, Machine>();

    static {
        MACHINES.put("SphericGuardian", new SphericGuardianMachine());
        MACHINES.put("Cultist", new CultistMachine());
        MACHINES.put("GremlinWarrior", new GremlinWarriorMachine());
        MACHINES.put("GremlinFat", new GremlinFatMachine());
        MACHINES.put("SpikeSlime_S", new SpikeSlimeMachine("SpikeSlime_S", 5, 6));
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

    /** 已镜像的怪物类型（诊断/面板用）。 */
    public static java.util.Set<String> mirroredTypes() {
        return java.util.Collections.unmodifiableSet(MACHINES.keySet());
    }

    /**
     * 算下一招。
     *
     * <p>调用方必须先用 {@link #supported(String)} 确认过；没登记会抛
     * {@link IllegalArgumentException}（不静默回退，避免「以为镜像了其实没跑」）。
     *
     * <p>**每次调用都会消耗一次 aiRng**（见类注释），所以不要在试探性路径上随便调。
     */
    public static MonsterMove next(SimState state, SimMonster monster, int turn, int slot) {
        if (monster == null) {
            throw new IllegalArgumentException("monster is null");
        }
        Machine machine = MACHINES.get(monster.type);
        if (machine == null) {
            throw new IllegalArgumentException("no monster mirror for " + monster.type);
        }
        int num = rollAi(state);
        return machine.next(new Context(state, monster, monster.moveHistory, turn, slot, num,
                state == null ? 0 : state.ascension));
    }

    /** 原版 {@code AbstractDungeon.aiRng.random(99)}：闭区间 [0, 99]，并推进计数器。 */
    static int rollAi(SimState state) {
        if (state == null) {
            return 0;
        }
        RngStream ai = state.rng.get(SimState.RngKind.AI);
        return ai == null ? 0 : ai.random(99);
    }

    // ------------------------------------------------------------------ 各怪物的机器

    /**
     * 球体守卫（SphericGuardian）。
     *
     * <pre>
     * getMove:  firstMove → 2(DEFEND)；secondMove → 4(ATTACK_DEBUFF)
     *           否则 lastMove(1) → 3(ATTACK_DEFEND) : 1(ATTACK, 2 段)
     * takeTurn: 1 → 两段 dmg；2 → 格挡 25(A17:35)；3 → 格挡 15 + 一段 dmg；
     *           4 → 一段 dmg + 玩家 Frail 5
     * 常数:     dmg 10 (A2: 11)
     * </pre>
     */
    static final class SphericGuardianMachine implements Machine {
        @Override
        public MonsterMove next(Context c) {
            int damage = c.ascension >= 2 ? 11 : 10;
            int defendBlock = c.ascension >= 17 ? 35 : 25;
            if (c.history.isEmpty()) {
                return MonsterMove.block(2, defendBlock);
            }
            if (c.history.size() == 1) {
                return MonsterMove.attackDebuff(4, damage, "Frail", 5);
            }
            if (c.lastMove(1)) {
                return MonsterMove.attackBlock(3, damage, 15);
            }
            return MonsterMove.multiAttack(1, damage, 2);
        }
    }

    /**
     * 邪教徒（Cultist）。
     *
     * <pre>
     * getMove:  firstMove → 3(INCANTATION, BUFF)；否则 1(DARK_STRIKE, ATTACK)
     * takeTurn: 3 → 给自己挂 Ritual 3 (A2: 4)；1 → 一段 dmg
     * 常数:     dmg 6、HP 48-54（A7: 50-56）
     * </pre>
     *
     * <p>Ritual 的每回合 +力量由 `CombatFlow.endTurn` ④.5 结算（atEndOfRound）。
     */
    static final class CultistMachine implements Machine {
        @Override
        public MonsterMove next(Context c) {
            if (c.history.isEmpty()) {
                return MonsterMove.buff(3, "Ritual", c.ascension >= 2 ? 4 : 3);
            }
            return MonsterMove.attack(1, 6);
        }
    }

    /**
     * 小鬼战士（GremlinWarrior）。
     *
     * <pre>getMove: 永远 1(ATTACK)；takeTurn: 一段 dmg；常数: dmg 4 (A2: 5)</pre>
     */
    static final class GremlinWarriorMachine implements Machine {
        @Override
        public MonsterMove next(Context c) {
            return MonsterMove.attack(1, c.ascension >= 2 ? 5 : 4);
        }
    }

    /**
     * 小鬼胖子（GremlinFat）。
     *
     * <pre>
     * getMove: 永远 2(ATTACK_DEBUFF)
     * takeTurn: 一段 dmg + 玩家 Weak 1（A17: 2）
     * 常数:    dmg 4 (A2: 5)
     * </pre>
     */
    static final class GremlinFatMachine implements Machine {
        @Override
        public MonsterMove next(Context c) {
            return MonsterMove.attackDebuff(2, c.ascension >= 2 ? 5 : 4, "Weakened",
                    c.ascension >= 17 ? 2 : 1);
        }
    }

    /**
     * 尖刺史莱姆（小）。
     *
     * <pre>getMove: 永远 1(ATTACK)；takeTurn: 一段 dmg；常数: dmg 5 (A2: 6)</pre>
     */
    static final class SpikeSlimeMachine implements Machine {
        private final String type;
        private final int baseDamage;
        private final int ascDamage;

        SpikeSlimeMachine(String type, int baseDamage, int ascDamage) {
            this.type = type;
            this.baseDamage = baseDamage;
            this.ascDamage = ascDamage;
        }

        @Override
        public MonsterMove next(Context c) {
            return MonsterMove.attack(1, c.ascension >= 2 ? ascDamage : baseDamage);
        }

        @Override
        public String toString() {
            return type + "Machine";
        }
    }
}
