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

        /** 原版 {@code Random.randomBoolean()}（无阈值版，走 {@code nextBoolean()}）。 */
        public boolean randomBoolean() {
            RngStream ai = state.rng.get(SimState.RngKind.AI);
            return ai != null && ai.randomBoolean();
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
        MACHINES.put("AcidSlime_M", new AcidSlimeMMachine());
        MACHINES.put("GremlinThief", new GremlinThiefMachine());
        MACHINES.put("Looter", new LooterMachine());
        MACHINES.put("ApologySlime", new ApologySlimeMachine());
        MACHINES.put("SnakePlant", new SnakePlantMachine());
        MACHINES.put("Byrd", new ByrdMachine());
        MACHINES.put("Chosen", new ChosenMachine());
        MACHINES.put("ShelledParasite", new ShelledParasiteMachine());
        MACHINES.put("Centurion", new CenturionMachine());
        MACHINES.put("Healer", new HealerMachine());
        MACHINES.put("Snecko", new SneckoMachine());
        MACHINES.put("BookOfStabbing", new BookOfStabbingMachine());
        MACHINES.put("Mugger", new MuggerMachine());
        MACHINES.put("Taskmaster", new TaskmasterMachine());
        MACHINES.put("AcidSlime_L", new AcidSlimeLMachine());
        MACHINES.put("SpikeSlime_L", new SpikeSlimeLMachine());
        MACHINES.put("SlimeBoss", new SlimeBossMachine());
        MACHINES.put("TheGuardian", new TheGuardianMachine());
        MACHINES.put("SpikeSlime_M", new SpikeSlimeMMachine());
        MACHINES.put("FungiBeast", new FungiBeastMachine());
        MACHINES.put("SlaverBlue", new SlaverBlueMachine());
        MACHINES.put("SlaverRed", new SlaverRedMachine());
        MACHINES.put("Lagavulin", new LagavulinMachine());
        MACHINES.put("GremlinNob", new GremlinNobMachine());
        MACHINES.put("LouseNormal", new LouseMachine("LouseNormal", true));
        MACHINES.put("LouseDefensive", new LouseMachine("LouseDefensive", false));
        MACHINES.put("GremlinWizard", new GremlinWizardMachine());
    }

    private MonsterMoves() {
    }

    /**
     * 半血分裂的那一招（原版大史莱姆 {@code damage()} 重写里塞进来的 3(SPLIT)）。
     *
     * <p>判据在 {@code DamageCalc.hit}：血量真的变了 + 当前 ≤ 一半 + 本回合排的不是 3。
     * 返回 {@code null} 表示这只怪不分裂。
     */
    public static MonsterMove splitMove(String type) {
        if ("AcidSlime_L".equals(type)) {
            return MonsterMove.split(3, "AcidSlime_M", 2);
        }
        if ("SpikeSlime_L".equals(type)) {
            return MonsterMove.split(3, "SpikeSlime_M", 2);
        }
        if ("SlimeBoss".equals(type)) {
            // 先刺史莱姆、再酸液史莱姆，血都是父体当时血量
            return MonsterMove.splitTwo(3, "SpikeSlime_L", 1, "AcidSlime_L", 1);
        }
        return null;
    }

    /**
     * 守卫者（TheGuardian）的「姿态切换」：伤害累计到阈值时进入防御姿态。
     *
     * <p>原版 {@code TheGuardian.damage()} 重写：{@code isOpen && !closeUpTriggered &&
     * hp 变了 && !isDying} → {@code dmgTaken += 伤害}、{@code ModeShift.amount -= 伤害}，
     * 然后 {@code dmgTaken >= dmgThreshold} 就 {@code ChangeState("Defensive Mode")}。
     *
     * <p>两个字段都能从**能力表**读到，不需要额外状态槽：
     * <ul>
     *   <li>{@code dmgTaken} 与 {@code ModeShift.amount} 同步递减（初值都 = dmgThreshold），
     *       所以「累计够了」等价于「Mode Shift 的 amount ≤ 0」；</li>
     *   <li>{@code isOpen} 等价于「身上还有 Mode Shift」—— 进入防御时它会
     *       {@code RemoveSpecificPowerAction(Mode Shift)}。</li>
     * </ul>
     *
     * <p>切姿态时原版还做三件事：加 20 格挡、{@code dmgThreshold += 10}、把下一招改成 1(CloseUp)。
     * 我们照做（阈值增量只影响下次挂 Mode Shift 的初值）。
     */
    public static boolean maybeShiftGuardian(SimMonster guardian) {
        Integer modeShift = guardian.powers.get("Mode Shift");
        if (modeShift == null || guardian.hp <= 0) {
            return false;
        }
        if (modeShift.intValue() > 0) {
            return false;
        }
        guardian.powers.remove("Mode Shift");
        guardian.block += 20;                       // DEFENSIVE_BLOCK
        MonsterMove closeUp = MonsterMove.buffBlock(1, "Sharp Hide", 3, 19);
        guardian.pendingMove = closeUp;
        guardian.pendingMoveId = 1;
        return true;
    }

    /**
     * 守卫者回到攻势姿态：重挂 Mode Shift、Sharp Hide 由 TwinSmash 移除。
     *
     * <p>阈值 = 基础（30 / A9: 35 / A19: 40）+ 10 × **已经进过几次防御姿态**。
     * 次数从历史里数「CloseUp（1 号招）」出现了几次 —— 每次防御姿态都以它开场，
     * 所以不需要额外存 `dmgThreshold` 字段。
     */
    public static void reopenGuardian(SimMonster guardian, int ascension) {
        int base = guardian.ascensionBaseThreshold(ascension);
        int shifts = 0;
        for (Integer id : guardian.moveHistory) {
            if (id.intValue() == 1) {
                shifts++;
            }
        }
        guardian.powers.put("Mode Shift", Integer.valueOf(base + 10 * shifts));
    }

    /**
     * 装甲被打光时的反应（{@code PlatedArmorPower.onRemove} → {@code ChangeStateAction("ARMOR_BREAK")}）。
     *
     * <p>只有少数怪物的 {@code changeState} 认这个状态；目前只有 ShelledParasite
     * 把它翻成 {@code setMove(4, STUN)}（破甲硬直一回合）。别的怪什么也不做。
     */
    public static void onArmorBreak(SimMonster monster) {
        if (!"ShelledParasite".equals(monster.type) || monster.hp <= 0) {
            return;
        }
        monster.pendingMove = MonsterMove.none(4);
        monster.pendingMoveId = 4;
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

    /**
     * 红奴隶主（SlaverRed）。
     *
     * <pre>
     * getMove:  firstTurn → 1(Stab)，关掉标志
     *           num &gt;= 75 且还没缠身 → 2(Entangle)
     *           num &gt;= 55 且已缠身 且非连续两刺 → 1(Stab)
     *           否则 A17+: lastMove(3) ? 1 : 3；其余: lastTwoMoves(3) ? 1 : 3
     * takeTurn: 1 → 一段 stab；2(Entangle) → 玩家 **缠身**（禁攻击牌，回合末自动消）；
     *           3(Scrape) → 一段 scrape + 玩家 Vulnerable 1（A17: 2）
     * 常数:     stab 13 (A2: 14)、scrape 8 (A2: 9)、HP 46-50 (A7: 48-52)
     * </pre>
     *
     * <p>两个类字段都能从历史反推：{@code firstTurn} = 历史为空，
     * {@code usedEntangle} = 历史里出现过 2。
     *
     * <p>「缠身」的禁攻击牌由 {@code CardPlayer.play} 拦（原版可打出判定里查
     * {@code hasPower("Entangled") && type == ATTACK}），回合末移除由
     * {@code PowerHooks} 的 END_OF_TURN 钩子做 —— 两侧都已存在，这里只负责施加。
     */
    static final class SlaverRedMachine implements Machine {
        @Override
        public MonsterMove next(Context c) {
            int stab = c.baseDamage(0, c.ascension >= 2 ? 14 : 13);
            int scrape = c.baseDamage(1, c.ascension >= 2 ? 9 : 8);
            int vuln = c.ascension >= 17 ? 2 : 1;
            if (c.history.isEmpty()) {
                return MonsterMove.attack(1, stab);
            }
            boolean usedEntangle = c.history.contains(Integer.valueOf(2));
            if (c.num >= 75 && !usedEntangle) {
                return MonsterMove.attackDebuff(2, 0, "Entangled", 1);
            }
            if (c.num >= 55 && usedEntangle && !c.lastTwoMoves(1)) {
                return MonsterMove.attack(1, stab);
            }
            boolean repeatedScrape = c.ascension >= 17 ? c.lastMove(3) : c.lastTwoMoves(3);
            return repeatedScrape
                    ? MonsterMove.attack(1, stab)
                    : MonsterMove.attackDebuff(3, scrape, "Vulnerable", vuln);
        }
    }

    /**
     * 拉格瓦林（Lagavulin，第一章精英）。
     *
     * <pre>
     * getMove:  没醒 → 5(SLEEP)
     *           醒了：debuffTurnCount &lt; 2 ? (lastTwoMoves(3) ? 1 : 3) : 1
     * takeTurn: 5(SLEEP) → idleCount++；到 3 就 ChangeState("OPEN") 并排 3(ATTACK)
     *           1(Siphon Soul) → debuffTurnCount = 0；玩家 **力量和敏捷各 -debuff**
     *           3(ATTACK) → debuffTurnCount++；一段 attackDmg
     *           4(STUN) → 只 ChangeState("OPEN")，不打人
     * 常数:     attackDmg 18 (A3: 20)、debuff -1 (A18: -2)、HP 109-111 (A8: 112-115)
     * </pre>
     *
     * <p>三个类字段全部从历史反推：
     * <ul>
     *   <li>{@code isOut} = 历史里出现过 1 / 3 / 4（醒着的招）；</li>
     *   <li>{@code idleCount} = 历史里 5 的个数；</li>
     *   <li>{@code debuffTurnCount} = 末尾连续 3 的个数（Siphon 会把它清零）。</li>
     * </ul>
     *
     * <p>「睡着时被打就醒」不在这里 —— 那是 {@code damage()} 的重写，钩在
     * {@code DamageCalc.hit}（见 {@code wakeSleepingLagavulin}）。
     */
    static final class LagavulinMachine implements Machine {
        @Override
        public MonsterMove next(Context c) {
            int attackDmg = c.ascension >= 3 ? 20 : 18;
            int debuff = c.ascension >= 18 ? 2 : 1;
            boolean awake = c.history.contains(Integer.valueOf(1))
                    || c.history.contains(Integer.valueOf(3))
                    || c.history.contains(Integer.valueOf(4));
            if (!awake) {
                int sleeps = 0;
                for (Integer id : c.history) {
                    if (id.intValue() == 5) {
                        sleeps++;
                    }
                }
                return sleeps >= 3 ? MonsterMove.attack(3, attackDmg) : MonsterMove.none(5);
            }
            int sinceDebuff = 0;
            for (int i = c.history.size() - 1; i >= 0 && c.history.get(i).intValue() == 3; i--) {
                sinceDebuff++;
            }
            MonsterMove siphon = MonsterMove.doubleDebuff(1, "Strength", debuff,
                    "Dexterity", debuff);
            if (sinceDebuff < 2) {
                return c.lastTwoMoves(3) ? siphon : MonsterMove.attack(3, attackDmg);
            }
            return siphon;
        }
    }

    /**
     * 小鬼头目（GremlinNob，第一章精英）。
     *
     * <pre>
     * getMove:  第一次 → 3(Bellow)，并关掉 usedBellow
     *           A18+：最近两招都没出过 2 → 2(Skull Bash)；否则连续两招 1 → 2；否则 1(Rush)
     *           其余：num &lt; 33 → 2；否则连续两招 1 → 2；否则 1
     * takeTurn: 3 → 给自己挂 **Enrage 2**（A18: 3）；2(Skull Bash) → 一段 bash + 玩家易伤 2；
     *           1(Rush) → 一段 rush
     * 常数:     bash 6 (A3: 8)、rush 14 (A3: 16)、HP 82-86 (A8: 85-90)
     * </pre>
     *
     * <p>Enrage 的效果不在这个类里 —— 它挂在 {@code Hooks.onCardUsed}（打技能牌 → 主人 +力量）。
     */
    static final class GremlinNobMachine implements Machine {
        @Override
        public MonsterMove next(Context c) {
            int bash = c.baseDamage(1, c.ascension >= 3 ? 8 : 6);
            int rush = c.baseDamage(0, c.ascension >= 3 ? 16 : 14);
            MonsterMove skullBash = MonsterMove.attackDebuff(2, bash, "Vulnerable", 2);
            if (c.history.isEmpty()) {
                return MonsterMove.buff(3, "Enrage", c.ascension >= 18 ? 3 : 2);
            }
            boolean bashAgain = c.ascension >= 18
                    ? (!c.lastMove(2) && !c.lastMoveBefore(2)) || c.lastTwoMoves(1)
                    : c.num < 33 || c.lastTwoMoves(1);
            return bashAgain ? skullBash : MonsterMove.attack(1, rush);
        }
    }

    /**
     * 中酸液史莱姆（AcidSlime_M）。
     *
     * <pre>
     * getMove:  A17+: num&lt;40 → (连续两招 1 ? randBool() ? 2 : 4 : 1)
     *                 num&lt;80 → (连续两招 2 ? randBool(0.5) ? 1 : 4 : 2)
     *                 否则    → (lastMove(4) ? randBool(0.4) ? 1 : 2 : 4)
     *           其余:  num&lt;30 → (连续两招 1 ? randBool() ? 2 : 4 : 1)
     *                 num&lt;70 → (lastMove(2) ? randBool(0.4) ? 1 : 4 : 2)
     *                 否则    → (连续两招 4 ? randBool(0.4) ? 1 : 2 : 4)
     * takeTurn: 1(Corrosive Spit) → 一段 dmg[0] + 往弃牌堆塞 1 张 Slimed
     *           2(Tackle) → 一段 dmg[1]
     *           4(Lick) → 玩家 Weak 1
     * 常数:     spit 7 (A2: 8)、tackle 10 (A2: 12)、HP 28-32 (A7: 29-34)
     * </pre>
     */
    static final class AcidSlimeMMachine implements Machine {
        @Override
        public MonsterMove next(Context c) {
            int spit = c.baseDamage(0, c.ascension >= 2 ? 8 : 7);
            int tackle = c.baseDamage(1, c.ascension >= 2 ? 12 : 10);
            MonsterMove spitMove = MonsterMove.attackStatus(1, spit, "Slimed", 1);
            MonsterMove tackleMove = MonsterMove.attack(2, tackle);
            MonsterMove lick = MonsterMove.attackDebuff(4, 0, "Weakened", 1);
            boolean a17 = c.ascension >= 17;
            if (c.num < (a17 ? 40 : 30)) {
                if (c.lastTwoMoves(1)) {
                    return c.randomBoolean() ? tackleMove : lick;
                }
                return spitMove;
            }
            boolean midBand = a17 ? c.num < 80 : c.num < 70;
            if (midBand) {
                if (a17 ? c.lastTwoMoves(2) : c.lastMove(2)) {
                    return c.randomBoolean(0.5f) ? spitMove : lick;
                }
                return tackleMove;
            }
            if (a17 ? c.lastMove(4) : c.lastTwoMoves(4)) {
                return c.randomBoolean(0.4f) ? spitMove : tackleMove;
            }
            return lick;
        }
    }

    /**
     * 大酸液史莱姆（AcidSlime_L）。
     *
     * <pre>
     * getMove:  A17+: num&lt;40 → 连续两招 1 ? (randBool(0.6) ? 2 : 4) : 1
     *                 num&lt;70 → 连续两招 2 ? (randBool(0.6) ? 1 : 4) : 2
     *                 否则    → lastMove(4) ? (randBool(0.4) ? 1 : 2) : 4
     *           其余:  num&lt;30 → 连续两招 1 ? (randBool() ? 2 : 4) : 1
     *                 num&lt;70 → lastMove(2) ? (randBool(0.4) ? 1 : 4) : 2
     *                 否则    → 连续两招 4 ? (randBool(0.4) ? 1 : 2) : 4
     * takeTurn: 1(Corrosive Spit) → 一段 dmg[0] + **2 张 Slimed**；
     *           2(Tackle) → 一段 dmg[1]；4(Lick) → 玩家 Weak 2
     * 常数:     spit 11 (A2: 12)、tackle 16 (A2: 18)、HP 65-69 (A7: 68-72)
     * </pre>
     *
     * <p>3(SPLIT) 不在 getMove 里 —— 它由 {@code damage()} 重写在半血时塞进来
     * （见 {@link #splitMove(String)} 与 {@code DamageCalc.hit}）。
     */
    static final class AcidSlimeLMachine implements Machine {
        @Override
        public MonsterMove next(Context c) {
            int spit = c.baseDamage(0, c.ascension >= 2 ? 12 : 11);
            int tackle = c.baseDamage(1, c.ascension >= 2 ? 18 : 16);
            MonsterMove spitMove = MonsterMove.attackStatus(1, spit, "Slimed", 2);
            MonsterMove tackleMove = MonsterMove.attack(2, tackle);
            MonsterMove lick = MonsterMove.attackDebuff(4, 0, "Weakened", 2);
            boolean a17 = c.ascension >= 17;
            if (c.num < (a17 ? 40 : 30)) {
                if (c.lastTwoMoves(1)) {
                    return c.randomBoolean(a17 ? 0.6f : 0.5f) ? tackleMove : lick;
                }
                return spitMove;
            }
            if (c.num < 70) {
                if (a17 ? c.lastTwoMoves(2) : c.lastMove(2)) {
                    return c.randomBoolean(a17 ? 0.6f : 0.4f) ? spitMove : lick;
                }
                return tackleMove;
            }
            boolean repeatedLick = a17 ? c.lastMove(4) : c.lastTwoMoves(4);
            if (repeatedLick) {
                return c.randomBoolean(0.4f) ? spitMove : tackleMove;
            }
            return lick;
        }
    }

    /**
     * 大刺史莱姆（SpikeSlime_L）。
     *
     * <pre>
     * getMove:  A17+: num&lt;30 → 连续两招 1 ? 4(Lick) : 1(Tackle)
     *                 否则    → lastMove(4) ? 1 : 4
     *           其余:  num&lt;30 → 连续两招 1 ? 4 : 1
     *                 否则    → 连续两招 4 ? 1 : 4
     * takeTurn: 1(Tackle) → 一段 dmg + 1 张 Slimed；4(Lick) → 玩家 Frail 2（A17: 3）
     * 常数:     tackle 16 (A2: 18)、HP 64-70 (A7: 67-73)
     * </pre>
     *
     * <p>3(SPLIT) 同样来自 {@code damage()} 重写，不在 getMove 里。
     */
    static final class SpikeSlimeLMachine implements Machine {
        @Override
        public MonsterMove next(Context c) {
            int tackle = c.baseDamage(0, c.ascension >= 2 ? 18 : 16);
            MonsterMove lick = MonsterMove.attackDebuff(4, 0, "Frail", c.ascension >= 17 ? 3 : 2);
            MonsterMove bite = MonsterMove.attackStatus(1, tackle, "Slimed", 1);
            if (c.num < 30) {
                return c.lastTwoMoves(1) ? lick : bite;
            }
            boolean repeatedLick = c.ascension >= 17 ? c.lastMove(4) : c.lastTwoMoves(4);
            return repeatedLick ? bite : lick;
        }
    }

    /**
     * 史莱姆王（SlimeBoss，第一章 boss）。
     *
     * <pre>
     * getMove:  第一回合 → 4(Goop Spray)；**此后什么也不做**
     *           —— 后面每一招都是 takeTurn 末尾用 SetMoveAction 排的：
     *           4 → 2(Preparing) → 1(Slam) → 4 → …
     * takeTurn: 4 → 往弃牌堆塞 Slimed 3 张（A19: 5），排 2；
     *           2 → 不打人，排 1(Slam)；
     *           1 → 一段 slamDmg，排回 4；
     *           3 → 分裂（见 {@link #splitMove(String)}）
     * 常数:     slam 35 (A4: 38)、tackle 9 (A4: 10)（tackle 这版没用到）、HP 140 (A9: 150)
     * </pre>
     *
     * <p>所以机器的「招序」按 takeTurn 的 SetMove 写，不按（几乎为空的）getMove 写 ——
     * 同 {@code AcidSlime_S} 那个坑。
     */
    static final class SlimeBossMachine implements Machine {
        @Override
        public MonsterMove next(Context c) {
            int slam = c.baseDamage(1, c.ascension >= 4 ? 38 : 35);
            if (c.history.isEmpty()) {
                return goop(c);
            }
            if (c.lastMove(4)) {
                return MonsterMove.none(2);              // Preparing：不打人
            }
            if (c.lastMove(2)) {
                return MonsterMove.attack(1, slam);
            }
            return goop(c);
        }

        private MonsterMove goop(Context c) {
            return MonsterMove.status(4, "Slimed", c.ascension >= 19 ? 5 : 3);
        }
    }

    /**
     * 守卫者（TheGuardian，第一章 boss）。
     *
     * <pre>
     * getMove:  isOpen ? 6(Charge Up) : 3(Roll Attack)   —— 只在没有 SetMove 兜底时用到
     * takeTurn 按 nextMove 分派（每招末尾自己排下一招）：
     *   6 ChargeUp  → 自己 +9 格挡，排 2
     *   2 FierceBash→ 一段 32（A4: 36），排 7
     *   7 VentSteam → 玩家 Weak 2 + Vulnerable 2，排 5
     *   5 Whirlwind → 4 段 5，排 6
     *   1 CloseUp   → 自己 +19 格挡 + **Sharp Hide 3**，排 3
     *   3 RollAttack→ 一段 9（A4: 10），排 4
     *   4 TwinSmash → 2 段 8；**移除 Sharp Hide、回攻势姿态**，排 5
     * 常数:     HP 240 (A9: 250)、阈值 30 (A9: 35 / A19: 40)、每次 +10
     * </pre>
     *
     * <p>姿态切换（进入防御）不在机器里 —— 由 {@code damage()} 重写触发，
     * 见 {@link #maybeShiftGuardian}（挂在 {@code DamageCalc.hit}）。
     */
    static final class TheGuardianMachine implements Machine {
        @Override
        public MonsterMove next(Context c) {
            boolean open = c.monster.powers.containsKey("Mode Shift");
            if (c.history.isEmpty()) {
                return chargeUp(c);
            }
            int last = c.history.get(c.history.size() - 1).intValue();
            switch (last) {
                case 6:
                    return fierceBash(c);
                case 2:
                    return ventSteam();
                case 7:
                    return whirlwind(c);
                case 5:
                    return chargeUp(c);
                case 1:
                    return rollAttack(c);
                case 3:
                    return twinSmash(c);
                case 4:
                    return whirlwind(c);
                default:
                    return open ? chargeUp(c) : rollAttack(c);
            }
        }

        private MonsterMove chargeUp(Context c) {
            return MonsterMove.block(6, 9);
        }

        private MonsterMove fierceBash(Context c) {
            return MonsterMove.attack(2, c.baseDamage(0, c.ascension >= 4 ? 36 : 32));
        }

        private MonsterMove ventSteam() {
            return MonsterMove.doubleDebuff(7, "Weakened", 2, "Vulnerable", 2);
        }

        private MonsterMove whirlwind(Context c) {
            return MonsterMove.multiAttack(5, c.baseDamage(2, 5), 4);
        }

        private MonsterMove rollAttack(Context c) {
            return MonsterMove.attack(3, c.baseDamage(1, c.ascension >= 4 ? 10 : 9));
        }

        private MonsterMove twinSmash(Context c) {
            return MonsterMove.multiAttack(4, c.baseDamage(3, 8), 2);
        }
    }

    /**
     * 小鬼小偷（GremlinThief）。
     *
     * <pre>
     * getMove: 永远 1(ATTACK)；takeTurn 1 → 一段 thiefDamage
     *          （代码里还有 `escapeNext ? 99(ESCAPE) : 1` 的分支，但**没有任何地方**
     *          调 `escapeNext()` —— 见类注释）
     * 常数: 9（A2: 10）、HP 10-14（A7: 11-15）
     * </pre>
     *
     * <p>🔴 查证结论：这个类的字节码里**一次都没提过 gold/Steal**，
     * 也就是说它打人**不偷钱**；`escapeNext` 那条分支是死代码（没人置位）。
     * 所以它就是一只「每回合打 9 的普通怪」，不逃逸、不偷钱 —— 照实写就行。
     */
    static final class GremlinThiefMachine implements Machine {
        @Override
        public MonsterMove next(Context c) {
            return MonsterMove.attack(1, c.baseDamage(0, c.ascension >= 2 ? 10 : 9));
        }
    }

    /**
     * 掠夺者（Looter）。
     *
     * <pre>
     * getMove: 永远 1(ATTACK) —— 后面的招都靠 takeTurn 的 SetMove 排
     * takeTurn: 1(Mug)      → 一段 swipe（顺带偷 goldAmt 金币），slashCount++；
     *                         slashCount == 2 时掷硬币：2(Smoke Bomb) 或 4(Lunge)，否则继续 1
     *           4(Lunge)    → 一段 lunge（也偷钱），排 2
     *           2(Smoke Bomb)→ 自己 +6 格挡（escapeDef），排 3
     *           3            → **EscapeAction，逃离战斗**
     * 常数: swipe 10（A2: 11）、lunge 12（A2: 14）、格挡 6、HP 44-48（A7: 46-50）
     * </pre>
     *
     * <p>偷到的金币影子不建模（`SimState` 没有 gold），但**逃逸时机与金币无关** ——
     * 它是自己按招序排的（`escapeNext` 在这个类里一次都没出现），所以不影响战斗推进。
     */
    static final class LooterMachine implements Machine {
        @Override
        public MonsterMove next(Context c) {
            int swipe = c.baseDamage(0, c.ascension >= 2 ? 11 : 10);
            int lunge = c.baseDamage(1, c.ascension >= 2 ? 14 : 12);
            int mugs = 0;
            for (Integer id : c.history) {
                if (id.intValue() == 1) {
                    mugs++;
                }
            }
            if (c.history.isEmpty()) {
                return MonsterMove.attack(1, swipe);
            }
            int last = c.history.get(c.history.size() - 1).intValue();
            switch (last) {
                case 1:
                    if (mugs >= 2) {
                        return c.randomBoolean() ? MonsterMove.block(2, 6)
                                : MonsterMove.attack(4, lunge);
                    }
                    return MonsterMove.attack(1, swipe);
                case 4:
                    return MonsterMove.block(2, 6);
                case 2:
                    return MonsterMove.escape(3);
                default:
                    return MonsterMove.attack(1, swipe);
            }
        }
    }

    /**
     * 道歉史莱姆（ApologySlime）—— 事件/玩笑怪，但行为是确定的。
     *
     * <pre>
     * getMove:  aiRng.randomBoolean() ? 1(ATTACK, damage[0]) : 2(DEBUFF, 玩家 Weak 1)
     * takeTurn: 1 → 一段 tackle；2 → 玩家 Weak 1
     * 常数:     tackle 3、Weak 1、HP 8-12
     * </pre>
     *
     * <p>注意：它的 `getMove` 里**又掷了一次** `aiRng.randomBoolean()` ——
     * 除了 {@link #next} 开头那次 {@code num} 之外再消耗一次，照抄。
     */
    static final class ApologySlimeMachine implements Machine {
        @Override
        public MonsterMove next(Context c) {
            return c.randomBoolean()
                    ? MonsterMove.attack(1, 3)
                    : MonsterMove.attackDebuff(2, 0, "Weakened", 1);
        }
    }

    /**
     * 蛇草（SnakePlant）—— 第二章。
     *
     * <pre>
     * getMove:  A17+: num&lt;65 → 连续两招 1 ? 2(Enfeebled) : 1(Chomp)
     *                 否则    → 最近两招里有 2 ? 1 : 2
     *           其余:  num&lt;65 → 连续两招 1 ? 2 : 1
     *                 否则    → lastMove(2) ? 1 : 2
     * takeTurn: 1(Chomp) → **3 段** rainBlowsDmg；2(Enfeebled) → 玩家 Frail 2 + Weak 2
     * 常数:     rainBlows 7（A2: 8）、HP 75-79（A7: 78-82）
     * </pre>
     */
    static final class SnakePlantMachine implements Machine {
        @Override
        public MonsterMove next(Context c) {
            int dmg = c.baseDamage(0, c.ascension >= 2 ? 8 : 7);
            MonsterMove chomp = MonsterMove.multiAttack(1, dmg, 3);
            MonsterMove enfeebled = MonsterMove.doubleDebuff(2, "Frail", 2, "Weakened", 2);
            if (c.num < 65) {
                return c.lastTwoMoves(1) ? enfeebled : chomp;
            }
            boolean sawDebuff = c.ascension >= 17
                    ? (c.lastMove(2) || c.lastMoveBefore(2))
                    : c.lastMove(2);
            return sawDebuff ? chomp : enfeebled;
        }
    }

    /**
     * 鸟（Byrd）—— 第二章，带「飞/落地」状态机。
     *
     * <pre>
     * getMove:  firstMove → 掷硬币：6(Caw, +1 力量) 或 1(Peck, 1 伤 × peckCount)
     *           飞着（isFlying）：num&lt;50 → 连续两招 1 ? 掷硬币(3 Swoop : 6) : 1
     *                          num&lt;70 → lastMove(3) ? 掷硬币(6 : 1) : 3
     *                          否则    → lastMove(6) ? 掷硬币(3 : 1) : 6
     *           落地（!isFlying）→ 5(Headbutt, 3 伤)
     * takeTurn: 1 → peckCount 段 damage[0]；2 → **起飞**（挂 Flight=flightAmt）；
     *           3 → 一段 damage[1]；4 → 什么也不做（STUN）；5 → 一段 damage[2]，然后排 2（再起飞）；
     *           6 → 自己 +1 力量
     * 常数:     peck 1×5（A2: 1×6）、swoop 12（A2: 14）、headbutt 3、
     *           Flight 3（A17: 4）、HP 25-31（A7: 26-33）
     * </pre>
     *
     * <p>`isFlying` 从历史反推：最近一次「状态招」是 2（起飞）还是 4/5（落地后的招）。
     * 落地本身由 {@code FlightPower} 触发（见 {@code DamageCalc.maybeGroundBird}）——
     * 每次挨打掉 1 层，掉光就把下一招改成 4(STUN)。
     */
    static final class ByrdMachine implements Machine {
        @Override
        public MonsterMove next(Context c) {
            int peck = c.baseDamage(0, 1);
            int peckCount = c.ascension >= 2 ? 6 : 5;
            int swoop = c.baseDamage(1, c.ascension >= 2 ? 14 : 12);
            int headbutt = c.baseDamage(2, 3);
            MonsterMove peckMove = MonsterMove.multiAttack(1, peck, peckCount);
            MonsterMove caw = MonsterMove.buff(6, "Strength", 1);
            MonsterMove swoopMove = MonsterMove.attack(3, swoop);
            if (c.history.isEmpty()) {
                return c.randomBoolean(0.5f) ? caw : peckMove;
            }
            if (!flying(c)) {
                // 落地：先 STUN(4) 再 Headbutt(5)，Headbutt 末尾排 2（再起飞）
                if (c.lastMove(4)) {
                    return MonsterMove.attack(5, headbutt);
                }
                if (c.lastMove(5)) {
                    return fly(c);
                }
                return MonsterMove.attack(5, headbutt);
            }
            if (c.num < 50) {
                if (c.lastTwoMoves(1)) {
                    return c.randomBoolean(0.5f) ? swoopMove : caw;
                }
                return peckMove;
            }
            if (c.num < 70) {
                if (c.lastMove(3)) {
                    return c.randomBoolean(0.5f) ? caw : peckMove;
                }
                return swoopMove;
            }
            if (c.lastMove(6)) {
                return c.randomBoolean(0.5f) ? swoopMove : peckMove;
            }
            return caw;
        }

        private MonsterMove fly(Context c) {
            return MonsterMove.buff(2, "Flight", c.ascension >= 17 ? 4 : 3);
        }

        /** 最近一次「状态招」是 2（起飞）而不是 4/5（落地后的招）→ 现在飞着。 */
        private static boolean flying(Context c) {
            for (int i = c.history.size() - 1; i >= 0; i--) {
                int id = c.history.get(i).intValue();
                if (id == 2) {
                    return true;
                }
                if (id == 4 || id == 5) {
                    return false;
                }
            }
            return true;        // 开场就是飞着的（构造器 isFlying = true）
        }
    }

    /**
     * 被选中者（Chosen）—— 第二章。
     *
     * <pre>
     * getMove:  A18+：没放过诅咒 → 4(Hex)
     *                否则 上一招既不是 3 也不是 2 时：num&lt;50 → 3(毒打) : 2(削志)
     *                否则 num&lt;40 → 1(重击) : 5(双击)
     *           其余：  第一回合 → 5(双击)
     *                没放过诅咒 → 4(Hex)
     *                之后同上
     * takeTurn: 1 → 一段 damage[0]（18 / A2: 21）
     *           2 → 玩家 Weak 3 + **自己 +3 力量**
     *           3 → 一段 damage[1]（10 / A2: 12）+ 玩家 Vulnerable 2
     *           4 → 给玩家挂 **Hex**（每打非攻击牌 → 往抽牌堆塞 1 张 Dazed）
     *           5 → 两段 damage[2]（5 / A2: 6）
     * 常数:     HP 95-99（A7: 98-103）
     * </pre>
     *
     * <p>两个类字段从历史反推：{@code firstTurn} = 历史为空，{@code usedHex} = 历史里有 4。
     * Hex 的生效点不在这个类里 —— 在 {@code Hooks.hex}（玩家每次出牌时检查）。
     */
    static final class ChosenMachine implements Machine {
        @Override
        public MonsterMove next(Context c) {
            int heavy = c.baseDamage(0, c.ascension >= 2 ? 21 : 18);
            int debilitate = c.baseDamage(1, c.ascension >= 2 ? 12 : 10);
            int poke = c.baseDamage(2, c.ascension >= 2 ? 6 : 5);
            boolean usedHex = c.history.contains(Integer.valueOf(4));
            if (c.ascension < 17 && c.history.isEmpty()) {
                return MonsterMove.multiAttack(5, poke, 2);      // 低进阶先双击
            }
            if (!usedHex) {
                return MonsterMove.attackDebuff(4, 0, "Hex", 1);
            }
            if (!c.lastMove(3) && !c.lastMove(2)) {
                return c.num < 50
                        ? MonsterMove.attackDebuff(3, debilitate, "Vulnerable", 2)
                        : MonsterMove.debuffAndBuff(2, "Weakened", 3, "Strength", 3);
            }
            return c.num < 40
                    ? MonsterMove.attack(1, heavy)
                    : MonsterMove.multiAttack(5, poke, 2);
        }
    }

    /**
     * 甲壳寄生虫（ShelledParasite）—— 第二章。
     *
     * <pre>
     * getMove:  第一回合：A18+ → 1(Suck, damage[1])
     *                    否则掷硬币：2(Double Strike, damage[0] ×2) / 3(Fell, damage[2] + 吸血)
     *           num&lt;20：上一招不是 1 → 1；否则**重掷**（aiRng.random(20,99) 再走一遍）
     *           num&lt;60：最近两招不是 2 → 2；否则 3
     *           否则：  最近两招不是 3 → 3；否则 2
     * takeTurn: 1(Suck) → 一段 damage[1] + 玩家 Frail 2
     *           2(Double Strike) → 两段 damage[0]
     *           3(Fell) → ChangeState("ATTACK") + 一段 damage[2]，**打掉多少血自己回多少**
     *           4 → 什么也不做（破甲硬直）
     * 常数:     suck 10（A2: 12）、doubleStrike 6（A2: 7）、fell 18（A2: 21）、
     *           HP 68-72（A7: 70-75）、开场挂 Plated Armor 14
     * </pre>
     *
     * <p>{@code firstMove} 从历史反推（空 = 第一回合）；「破甲即硬直」不在这个类里 ——
     * 由 {@code PlatedArmorPower.onRemove} 触发（见 {@link #onArmorBreak}）。
     */
    static final class ShelledParasiteMachine implements Machine {
        @Override
        public MonsterMove next(Context c) {
            return decide(c, c.num);
        }

        private MonsterMove decide(Context c, int num) {
            int suck = c.baseDamage(1, c.ascension >= 2 ? 12 : 10);
            int doubleStrike = c.baseDamage(0, c.ascension >= 2 ? 7 : 6);
            int fell = c.baseDamage(2, c.ascension >= 2 ? 21 : 18);
            MonsterMove suckMove = MonsterMove.attackDebuff(1, suck, "Frail", 2);
            MonsterMove doubleMove = MonsterMove.multiAttack(2, doubleStrike, 2);
            MonsterMove fellMove = MonsterMove.vampireAttack(3, fell);
            if (c.history.isEmpty()) {
                if (c.ascension >= 17) {
                    return suckMove;
                }
                return c.randomBoolean() ? doubleMove : fellMove;
            }
            if (num < 20) {
                if (!c.lastMove(1)) {
                    return suckMove;
                }
                // 原版就是递归重掷：getMove(aiRng.random(20, 99))
                RngStream ai = c.state.rng.get(SimState.RngKind.AI);
                int reroll = ai == null ? 20 : ai.random(20, 99);
                return decide(c, reroll);
            }
            if (num < 60) {
                return c.lastTwoMoves(2) ? fellMove : doubleMove;
            }
            return c.lastTwoMoves(3) ? doubleMove : fellMove;
        }
    }

    /**
     * 百夫长（Centurion）—— 第二章。
     *
     * <pre>
     * getMove:  num&gt;=65 且最近两招都不是 2/3 → 有队友就 2(格挡) 否则 3(怒击)
     *           最近两招不是 1 → 1(挥击)
     *           否则 → 有队友就 2 否则 3
     * takeTurn: 1 → 一段 slash；2 → **给随机队友**加 blockAmount 格挡（GainBlockRandomMonsterAction）；
     *           3 → furyHits 段 fury
     * 常数:     slash 12（A2: 14）、fury 6（A2: 7）×3、格挡 15（A17: 20）、HP 76-80（A7: 78-83）
     * </pre>
     *
     * <p>「有队友」= 活怪数 &gt; 1（原版数 `!isDying && !isEscaping`）。
     * 和 GremlinTsundere 用的是同一个 Action（`GainBlockRandomMonsterAction`），
     * 所以共用 {@code blockRandomAlly} 口径。
     */
    static final class CenturionMachine implements Machine {
        @Override
        public MonsterMove next(Context c) {
            int slash = c.baseDamage(0, c.ascension >= 2 ? 14 : 12);
            int fury = c.baseDamage(1, c.ascension >= 2 ? 7 : 6);
            int block = c.ascension >= 17 ? 20 : 15;
            MonsterMove defend = MonsterMove.blockAlly(2, block);
            MonsterMove rage = MonsterMove.multiAttack(3, fury, 3);
            boolean withAllies = aliveCount(c) > 1;
            if (c.num >= 65 && !c.lastTwoMoves(2) && !c.lastTwoMoves(3)) {
                return withAllies ? defend : rage;
            }
            if (!c.lastTwoMoves(1)) {
                return MonsterMove.attack(1, slash);
            }
            return withAllies ? defend : rage;
        }
    }

    /**
     * 治疗师（Healer）—— 第二章。
     *
     * <pre>
     * getMove:  全队**缺损血量** &gt; 15（A17: 20）且最近两招不是 2 → 2(治疗)
     *           num&gt;=40 且「没连着打 1」（A17 看 lastMove、其余看 lastTwoMoves）→ 1(攻击)
     *           最近两招不是 3 → 3(全队 +力量)
     *           否则 → 1
     * takeTurn: 1 → 一段 magicDmg + 玩家 Frail 2
     *           2 → **治疗全队**（HealAction 每只 healAmt）
     *           3 → **全队 +strAmt 力量**
     * 常数:     magicDmg 8（A2: 9）、strAmt 2（A2: 3, A17: 4）、healAmt 16（A17: 20）、
     *           HP 48-56（A7: 50-58）
     * </pre>
     */
    static final class HealerMachine implements Machine {
        @Override
        public MonsterMove next(Context c) {
            int magic = c.baseDamage(0, c.ascension >= 2 ? 9 : 8);
            int str = c.ascension >= 17 ? 4 : (c.ascension >= 2 ? 3 : 2);
            int heal = c.ascension >= 17 ? 20 : 16;
            MonsterMove strike = MonsterMove.attackDebuff(1, magic, "Frail", 2);
            int missing = 0;
            for (SimMonster ally : c.state.monsters) {
                if (ally.hp > 0) {
                    missing += Math.max(0, ally.maxHp - ally.hp);
                }
            }
            if (missing > (c.ascension >= 17 ? 20 : 15) && !c.lastTwoMoves(2)) {
                return MonsterMove.healAll(2, heal);
            }
            boolean canStrike = c.num >= 40
                    && !(c.ascension >= 17 ? c.lastMove(1) : c.lastTwoMoves(1));
            if (canStrike) {
                return strike;
            }
            return c.lastTwoMoves(3) ? strike : MonsterMove.buffAll(3, "Strength", str);
        }
    }

    /** 场上还活着的怪数（原版 `!isDying && !isEscaping`）。 */
    private static int aliveCount(Context c) {
        int alive = 0;
        for (SimMonster monster : c.state.monsters) {
            if (monster.hp > 0) {
                alive++;
            }
        }
        return alive;
    }

    /**
     * 蛇颈龙（Snecko）—— 第二章。
     *
     * <pre>
     * getMove:  第一回合 → 1(Tail Whip, STRONG_DEBUFF)
     *           num&lt;40 → 3(Glare, ATTACK_DEBUFF, damage[1])
     *           最近两招是 2 → 3；否则 → 2(Bite, damage[0])
     * takeTurn: 1 → 给玩家挂 **Confusion**（每抽一张牌随机改费用）
     *           2 → 一段 damage[0]（bite）
     *           3 → 一段 damage[1]（tail）+ 玩家 Vulnerable 2（A17 起再加 Weak 2）
     * 常数:     bite 15（A2: 18）、tail 8（A2: 10）、HP 114-120（A7: 120-125）
     * </pre>
     */
    static final class SneckoMachine implements Machine {
        @Override
        public MonsterMove next(Context c) {
            int bite = c.baseDamage(0, c.ascension >= 2 ? 18 : 15);
            int tail = c.baseDamage(1, c.ascension >= 2 ? 10 : 8);
            if (c.history.isEmpty()) {
                return MonsterMove.attackDebuff(1, 0, "Confused", 1);
            }
            MonsterMove glare = c.ascension >= 17
                    ? MonsterMove.attackDoubleDebuff(3, tail, "Vulnerable", 2, "Weakened", 2)
                    : MonsterMove.attackDebuff(3, tail, "Vulnerable", 2);
            if (c.num < 40) {
                return glare;
            }
            return c.lastTwoMoves(2) ? glare : MonsterMove.attack(2, bite);
        }
    }

    /**
     * 刺击之书（BookOfStabbing）—— 第二章精英。
     *
     * <pre>
     * getMove:  num&lt;15：上一招是 2 → stabCount++ 并 1(多刺, damage[0] ×stabCount)
     *                     否则 → 2(重刺, damage[1])；**A18 起 stabCount 也 +1**
     *           否则：  最近两招是 1 → 2(重刺)（A18 起 stabCount +1）
     *                   否则 → stabCount++ 并 1(多刺)
     * takeTurn: 1 → stabCount 段 damage[0]；2 → 一段 damage[1]
     * 常数:     stab 6（A3: 7）、bigStab 21（A3: 24）、stabCount 初值 1
     * </pre>
     *
     * <p>stabCount 从历史反推：初值 1，**每次打 1 都 +1**；A18 起打 2 也 +1
     * （原版在选招时就自增，所以「下一招的段数」在选招那一刻就定了）。
     *
     * <p>开场挂 {@code Painful Stabs}：每次造成攻击伤害往玩家弃牌堆塞 1 张 Wound
     * （落在 {@code CombatFlow.takeTurn} 的逐段结算里）。
     */
    static final class BookOfStabbingMachine implements Machine {
        @Override
        public MonsterMove next(Context c) {
            int stab = c.baseDamage(0, c.ascension >= 3 ? 7 : 6);
            int big = c.baseDamage(1, c.ascension >= 3 ? 24 : 21);
            int count = stabCount(c);
            if (c.num < 15) {
                if (c.lastMove(2)) {
                    return MonsterMove.multiAttack(1, stab, count + 1);
                }
                return MonsterMove.attack(2, big);
            }
            if (c.lastTwoMoves(1)) {
                return MonsterMove.attack(2, big);
            }
            return MonsterMove.multiAttack(1, stab, count + 1);
        }

        /** 初值 1 + 历史里 1 的个数（每次打 1 都自增）；A18 起打 2 也自增。 */
        private int stabCount(Context c) {
            int count = 1;
            for (Integer id : c.history) {
                if (id.intValue() == 1 || (c.ascension >= 18 && id.intValue() == 2)) {
                    count++;
                }
            }
            return count;
        }
    }

    /**
     * 强盗（Mugger）—— 第二章，和 Looter 同构（抹两次 → 掷硬币 → 烟雾弹 → 逃跑）。
     *
     * <pre>
     * getMove: 永远 1（其余靠 takeTurn 的 SetMove 排）
     * takeTurn: 1 → 一段 swipe，slashCount++；slashCount==2 时掷硬币 → 2(烟雾弹) 或 4(突袭)
     *           4 → 一段 bigSwipe，排 2
     *           2 → 自己 +escapeDef 格挡（A17: +escapeDef+6），排 3
     *           3 → EscapeAction 离场
     * 常数:     swipe 10（A2: 11）、bigSwipe 16（A2: 18）、escapeDef 11（A17: 17）
     * </pre>
     *
     * <p>它打人时会偷金币（`StealGoldAction`），影子不建模金币 —— 只影响战利品，
     * 不影响战斗推进（逃跑是招序排的，与偷到多少无关）。
     */
    static final class MuggerMachine implements Machine {
        @Override
        public MonsterMove next(Context c) {
            int swipe = c.baseDamage(0, c.ascension >= 2 ? 11 : 10);
            int big = c.baseDamage(1, c.ascension >= 2 ? 18 : 16);
            int block = c.ascension >= 17 ? 17 : 11;
            if (c.history.isEmpty()) {
                return MonsterMove.attack(1, swipe);
            }
            int last = c.history.get(c.history.size() - 1).intValue();
            if (last == 1) {
                int slashes = 0;
                for (Integer id : c.history) {
                    if (id.intValue() == 1) {
                        slashes++;
                    }
                }
                if (slashes >= 2) {
                    return c.randomBoolean()
                            ? MonsterMove.block(2, block)
                            : MonsterMove.attack(4, big);
                }
                return MonsterMove.attack(1, swipe);
            }
            if (last == 4) {
                return MonsterMove.block(2, block);
            }
            if (last == 2) {
                return MonsterMove.escape(3);
            }
            return MonsterMove.attack(1, swipe);
        }
    }

    /**
     * 工头（Taskmaster）—— 第二章（三奴隶主精英）。
     *
     * <pre>
     * getMove: 永远 2(Scouring Whip)
     * takeTurn: 2 → 一段 damage[1]（7）+ 往玩家弃牌堆塞 woundCount 张 Wound（2 / A18: 3）；
     *               A18 起再给自己 +1 力量
     * 常数:     HP 54-60（A8: 57-64）
     * </pre>
     */
    static final class TaskmasterMachine implements Machine {
        @Override
        public MonsterMove next(Context c) {
            int dmg = c.baseDamage(1, 7);
            int wounds = c.ascension >= 18 ? 3 : 2;
            return c.ascension >= 18
                    ? MonsterMove.attackStatusBuff(2, dmg, "Wound", wounds, "Strength", 1)
                    : MonsterMove.attackStatus(2, dmg, "Wound", wounds);
        }
    }
}
