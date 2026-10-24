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

        /**
         * 捕获来的卡面伤害第 {@code index} 项；没捕获到就用 {@code fallback}（字节码默认档）。
         *
         * <p>不少怪的伤害是**开战随机**的（虱子 5-7 / A2 起 6-8、奴隶主、大颚虫…），
         * 那是构造器里 {@code MathUtils.random} 抽的，影子复现不出来 —— 所以优先用真机值。
         */
        public int baseDamage(int index, int fallback) {
            return monster != null && index >= 0 && index < monster.damageBase.size()
                    ? monster.damageBase.get(index).intValue()
                    : fallback;
        }

        /** 原版 {@code lastTwoMoves(byte)}。 */
        public boolean lastTwoMoves(int id) {
            int size = history.size();
            return size >= 2 && history.get(size - 1).intValue() == id
                    && history.get(size - 2).intValue() == id;
        }

        /**
         * 原版 {@code AbstractDungeon.aiRng.randomBoolean(chance)}。
         *
         * <p>⚠️ 这是**额外**消耗：{@link #next} 已经抽过一次 {@code num}，
         * 决策里再调它就会再推进一格 aiRng —— 原版也是这么消耗的（JawWorm 的 getMove 里就有）。
         */
        public boolean randomBoolean(float chance) {
            RngStream ai = state.rng.get(SimState.RngKind.AI);
            return ai != null && ai.randomBoolean(chance);
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
        MACHINES.put("Sentry", new SentryMachine());
        MACHINES.put("GremlinTsundere", new GremlinTsundereMachine());
        MACHINES.put("JawWorm", new JawWormMachine());
        MACHINES.put("AcidSlime_S", new AcidSlimeMachine());
        MACHINES.put("SpikeSlime_M", new SpikeSlimeMMachine());
        MACHINES.put("FungiBeast", new FungiBeastMachine());
        MACHINES.put("SlaverBlue", new SlaverBlueMachine());
        MACHINES.put("LouseNormal", new LouseMachine("LouseNormal", true));
        MACHINES.put("LouseDefensive", new LouseMachine("LouseDefensive", false));
        MACHINES.put("GremlinWizard", new GremlinWizardMachine());
    }

    private MonsterMoves() {
    }

    /** 取某只怪的机器（测试用来逐分支钉住；运行期走 {@link #next}）。 */
    static Machine machineFor(String type) {
        Machine machine = MACHINES.get(type);
        if (machine == null) {
            throw new IllegalArgumentException("no monster mirror for " + type);
        }
        return machine;
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

    /**
     * 哨兵（Sentry）。
     *
     * <pre>
     * getMove:  firstMove → 自己在怪物组里的位次 %2==0 ? 3(DEBUFF) : 4(ATTACK, beamDmg)
     *           否则 lastMove(4) ? 3 : 4          // 就是 4/3 交替
     * takeTurn: 3 → 往玩家弃牌堆塞 dazedAmt 张 Dazed；4 → 一段 beamDmg
     * 常数:     beamDmg 9 (A3: 10)、dazedAmt 2 (A18: 3)、HP 38-42 (A8: 39-45)
     * </pre>
     *
     * <p>位次用 {@link Context#slot}（原版 {@code monsters.lastIndexOf(this) % 2}）。
     */
    static final class SentryMachine implements Machine {
        @Override
        public MonsterMove next(Context c) {
            int beam = c.ascension >= 3 ? 10 : 9;
            int dazed = c.ascension >= 18 ? 3 : 2;
            boolean first = c.history.isEmpty();
            boolean debuff = first ? (c.slot % 2 == 0) : c.lastMove(4);
            return debuff ? MonsterMove.status(3, "Dazed", dazed) : MonsterMove.attack(4, beam);
        }
    }

    /**
     * 小鬼傲娇（GremlinTsundere）。
     *
     * <pre>
     * getMove:  永远 1(DEFEND)
     * takeTurn: 1 → 给**随机队友**（不含自己）加 blockAmt 格挡；
     *              然后数活着的怪：还有别人 → 仍排 1；只剩自己 → 排 2(ATTACK, bashDmg)
     * 常数:     blockAmt 7 (A7: 8, A17: 11)、bashDmg 6 (A2: 8)、HP 12-15 (A7: 13-17)
     * </pre>
     *
     * <p>也就是说它是「有队友就一直加格挡、落单才开始打」的怪。原版在 takeTurn 末尾用
     * {@code SetMoveAction} 改 nextMove，这里等价为「按当前活怪数决定」。
     */
    static final class GremlinTsundereMachine implements Machine {
        @Override
        public MonsterMove next(Context c) {
            int blockAmt = c.ascension >= 17 ? 11 : (c.ascension >= 7 ? 8 : 7);
            int bash = c.ascension >= 2 ? 8 : 6;
            int alive = 0;
            for (SimMonster other : c.state.monsters) {
                if (other.hp > 0) {
                    alive++;
                }
            }
            return alive > 1 ? MonsterMove.blockAlly(1, blockAmt) : MonsterMove.attack(2, bash);
        }
    }

    /**
     * 大颚虫（JawWorm）。
     *
     * <pre>
     * getMove:  firstMove → 1(ATTACK, chomp)，然后关掉标志
     *           num &lt; 25:  lastMove(1) ? (aiRng.randomBoolean(0.5625f) ? 2 : 3) : 1
     *           num &lt; 55:  lastTwoMoves(3) ? (aiRng.randomBoolean(0.357f) ? 1 : 2) : 3
     *           否则:       lastMove(2) ? (aiRng.randomBoolean(0.416f) ? 1 : 3) : 2
     * takeTurn: 1 → 一段 chomp；2(Bellow) → 自己 +bellowStr 力量、+bellowBlock 格挡；
     *           3(Thrash) → 一段 thrash + 自己 +thrashBlock 格挡
     * 常数:     chomp 11 (A2: 12)、thrash 7、thrashBlock 5、
     *           bellowStr 3 (A2: 4, A17: 5)、bellowBlock 6 (A17: 9)、HP 40-44 (A7: 42-46)
     * </pre>
     */
    static final class JawWormMachine implements Machine {
        @Override
        public MonsterMove next(Context c) {
            boolean a2 = c.ascension >= 2;
            boolean a17 = c.ascension >= 17;
            int chomp = a2 ? 12 : 11;
            int thrash = 7;
            int thrashBlock = 5;
            int bellowStr = a17 ? 5 : (a2 ? 4 : 3);
            int bellowBlock = a17 ? 9 : 6;
            if (c.history.isEmpty()) {
                return MonsterMove.attack(1, chomp);
            }
            if (c.num < 25) {
                if (c.lastMove(1)) {
                    return c.randomBoolean(0.5625f)
                            ? MonsterMove.buffBlock(2, "Strength", bellowStr, bellowBlock)
                            : MonsterMove.attackBlock(3, thrash, thrashBlock);
                }
                return MonsterMove.attack(1, chomp);
            }
            if (c.num < 55) {
                if (c.lastTwoMoves(3)) {
                    return c.randomBoolean(0.357f)
                            ? MonsterMove.attack(1, chomp)
                            : MonsterMove.buffBlock(2, "Strength", bellowStr, bellowBlock);
                }
                return MonsterMove.attackBlock(3, thrash, thrashBlock);
            }
            if (c.lastMove(2)) {
                return c.randomBoolean(0.416f)
                        ? MonsterMove.attack(1, chomp)
                        : MonsterMove.attackBlock(3, thrash, thrashBlock);
            }
            return MonsterMove.buffBlock(2, "Strength", bellowStr, bellowBlock);
        }
    }

    /**
     * 小酸液史莱姆（AcidSlime_S）。
     *
     * <pre>
     * getMove:  A17+: lastTwoMoves(1) ? 1 : 2；否则 aiRng.randomBoolean() ? 1 : 2
     * takeTurn: 1 → 一段 dmg，然后 SetMoveAction(2, DEBUFF)
     *           2 → 玩家 Weak 1，然后 SetMoveAction(1, ATTACK, dmg)
     * 常数:     dmg 3 (A2: 4)、HP 8-12 (A7: 9-13)
     * </pre>
     *
     * <p>🔴 关键：它的 takeTurn **自带 SetMoveAction**，每回合结束根本不调 {@code getMove}
     * （字节码里没有 RollMoveAction）。所以真实序列是死循环的 1→2→1→2，
     * {@code getMove} 只决定开场第一招。机器按 takeTurn 的口径写，不按 {@code getMove}。
     */
    static final class AcidSlimeMachine implements Machine {
        @Override
        public MonsterMove next(Context c) {
            int damage = c.ascension >= 2 ? 4 : 3;
            return c.lastMove(1)
                    ? MonsterMove.attackDebuff(2, 0, "Weakened", 1)
                    : MonsterMove.attack(1, damage);
        }
    }

    /**
     * 中刺史莱姆（SpikeSlime_M）。
     *
     * <pre>
     * getMove:  num &lt; 30: lastTwoMoves(1) ? 4(DEBUFF) : 1(ATTACK, dmg)
     *           否则:     A17+ 看 lastMove(4)、其余看 lastTwoMoves(4) → 是则 1、否则 4
     * takeTurn: 1 → 一段 dmg + 往玩家弃牌堆塞 1 张 Slimed；4(Lick) → 玩家 Frail 1
     * 常数:     dmg 8 (A2: 10)、HP 28-32 (A7: 29-34)
     * </pre>
     */
    static final class SpikeSlimeMMachine implements Machine {
        @Override
        public MonsterMove next(Context c) {
            int damage = c.ascension >= 2 ? 10 : 8;
            MonsterMove attack = MonsterMove.attackStatus(1, damage, "Slimed", 1);
            MonsterMove lick = MonsterMove.attackDebuff(4, 0, "Frail", 1);
            if (c.num < 30) {
                return c.lastTwoMoves(1) ? lick : attack;
            }
            boolean repeatedLick = c.ascension >= 17 ? c.lastMove(4) : c.lastTwoMoves(4);
            return repeatedLick ? attack : lick;
        }
    }

    /**
     * 菌类怪兽（FungiBeast）。
     *
     * <pre>
     * getMove:  num &lt; 60: lastTwoMoves(1) ? 2(Grow) : 1(Bite)
     *           否则:     lastMove(2) ? 1 : 2
     * takeTurn: 1 → 一段 bite；2(Grow) → 自己 +strAmt 力量（A17: strAmt+1）
     * 常数:     bite 6（卡面随机档）、strAmt 3 (A2: 4)、HP 22-28 (A7: 24-28)
     * </pre>
     */
    static final class FungiBeastMachine implements Machine {
        @Override
        public MonsterMove next(Context c) {
            int bite = c.baseDamage(0, 6);
            int strAmt = c.ascension >= 2 ? 4 : 3;
            MonsterMove grow = MonsterMove.buff(2, "Strength", c.ascension >= 17 ? strAmt + 1 : strAmt);
            if (c.num < 60) {
                return c.lastTwoMoves(1) ? grow : MonsterMove.attack(1, bite);
            }
            return c.lastMove(2) ? MonsterMove.attack(1, bite) : grow;
        }
    }

    /**
     * 蓝奴隶主（SlaverBlue）。
     *
     * <pre>
     * getMove:  num &gt;= 40 且不是连续两招 1 → 1(Stab)
     *           否则 A17+: lastMove(4) ? 1 : 4；其余: lastTwoMoves(4) ? 1 : 4
     * takeTurn: 1 → 一段 stab；4(Rake) → 一段 rake + 玩家 Weak（A17: 2）
     * 常数:     stab 12 (A2: 13)、rake 7 (A2: 8)、weakAmt 1、HP 46-50 (A7: 48-52)
     * </pre>
     */
    static final class SlaverBlueMachine implements Machine {
        @Override
        public MonsterMove next(Context c) {
            int stab = c.baseDamage(0, c.ascension >= 2 ? 13 : 12);
            int rake = c.baseDamage(1, c.ascension >= 2 ? 8 : 7);
            int weak = c.ascension >= 17 ? 2 : 1;
            if (c.num >= 40 && !c.lastTwoMoves(1)) {
                return MonsterMove.attack(1, stab);
            }
            boolean repeatedRake = c.ascension >= 17 ? c.lastMove(4) : c.lastTwoMoves(4);
            return repeatedRake
                    ? MonsterMove.attack(1, stab)
                    : MonsterMove.attackDebuff(4, rake, "Weakened", weak);
        }
    }

    /**
     * 虱子（FuzzyLouseNormal / FuzzyLouseDefensive）—— 两只只差「4 号招干什么」。
     *
     * <pre>
     * getMove:  A17+: num &lt; 25 ? (lastMove(4) ? 3 : 4) : (lastTwoMoves(3) ? 4 : 3)
     *           其余: num &lt; 25 ? (lastTwoMoves(4) ? 3 : 4) : (lastTwoMoves(3) ? 4 : 3)
     * takeTurn: 3(Bite) → 一段 bite
     *           4 → 普通虱子：自己 +3 力量（A17: 4）＝Grow
     *               防御虱子：玩家 Weak 2＝Web
     * 常数:     bite 5-7（A2: 6-8，**开战随机**，用捕获值）、HP 10-15 / 11-17（A7: 11-16 / 12-18）
     * </pre>
     */
    static final class LouseMachine implements Machine {
        private final String type;
        private final boolean grows;

        LouseMachine(String type, boolean grows) {
            this.type = type;
            this.grows = grows;
        }

        @Override
        public MonsterMove next(Context c) {
            int bite = c.baseDamage(0, c.ascension >= 2 ? 7 : 6);
            MonsterMove four = grows
                    ? MonsterMove.buff(4, "Strength", c.ascension >= 17 ? 4 : 3)
                    : MonsterMove.attackDebuff(4, 0, "Weakened", 2);
            if (c.num < 25) {
                boolean repeatedFour = c.ascension >= 17 ? c.lastMove(4) : c.lastTwoMoves(4);
                return repeatedFour ? MonsterMove.attack(3, bite) : four;
            }
            return c.lastTwoMoves(3) ? four : MonsterMove.attack(3, bite);
        }

        @Override
        public String toString() {
            return type + "Machine";
        }
    }

    /**
     * 小鬼法师（GremlinWizard）。
     *
     * <pre>
     * getMove:  永远 2(Charging)
     * takeTurn: 2 → currentCharge++；到 3 → 排 1(Ultimate Blast)，否则继续 2
     *           1 → currentCharge = 0；打 damage[0]；A17+ 再排一次 1（连轰），否则排 2
     * 常数:     blast 25 (A2: 30)、HP 21-25 (A7: 22-26)、currentCharge 初值 1
     * </pre>
     *
     * <p>蓄力计数从 {@code moveHistory} 反推：开场那次算 1 点（构造器初值），
     * 之后每打一次 2 就 +1；打过一次大招（1）之后清零。
     */
    static final class GremlinWizardMachine implements Machine {
        @Override
        public MonsterMove next(Context c) {
            int blast = c.baseDamage(0, c.ascension >= 2 ? 30 : 25);
            int charge = c.history.contains(Integer.valueOf(1)) ? 0 : 1;
            for (int i = c.history.size() - 1; i >= 0 && c.history.get(i).intValue() == 2; i--) {
                charge++;
            }
            // A17 起放完大招还会再来一发（字节码：打完之后 SetMove 又是 1）
            if (c.ascension >= 17 && c.lastMove(1) && !c.lastMoveBefore(1)) {
                return MonsterMove.attack(1, blast);
            }
            return charge >= 2 ? MonsterMove.attack(1, blast) : MonsterMove.none(2);
        }
    }
}
