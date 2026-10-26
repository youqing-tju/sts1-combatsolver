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

        /**
         * 再抽一次 {@code AbstractDungeon.aiRng.random(min, max)}。
         *
         * <p>只有少数怪的 {@code getMove} 会**递归调用自己**（GremlinLeader 就是：
         * 某些分支里 {@code getMove(aiRng.random(50,99))} / {@code getMove(aiRng.random(0,80))}），
         * 这时原版会再消耗一次 aiRng。严格照抄的话这里也必须再抽，且**区间要对**——
         * {@code random(50,99)} 是 {@code nextInt(50)}、{@code random(99)} 是 {@code nextInt(100)}，
         * 两者消耗的内部随机位可能不同，混用会让后续序列错位。
         */
        public int rollAiRange(int min, int max) {
            RngStream ai = state == null ? null : state.rng.get(SimState.RngKind.AI);
            return ai == null ? min : ai.random(min, max);
        }

        /** 场上除自己以外还活着的怪数（原版 {@code GremlinLeader.numAliveGremlins} 口径）。 */
        public int aliveOthers() {
            if (state == null) {
                return 0;
            }
            int alive = 0;
            for (SimMonster other : state.monsters) {
                if (other != monster && other.hp > 0) {
                    alive++;
                }
            }
            return alive;
        }

        /** 场上活着的指定类型怪数。 */
        public int aliveOf(String type) {
            if (state == null) {
                return 0;
            }
            int alive = 0;
            for (SimMonster other : state.monsters) {
                if (other.hp > 0 && type.equals(other.type)) {
                    alive++;
                }
            }
            return alive;
        }

        /** 场上有没有该类型的**已死**自体（TheCollector 的 isMinionDead）。 */
        public boolean hasDeadOf(String type) {
            if (state == null) {
                return false;
            }
            for (SimMonster other : state.monsters) {
                if (other.hp <= 0 && type.equals(other.type)) {
                    return true;
                }
            }
            return false;
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
        MACHINES.put("BanditPointy", new BanditPointyMachine());
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
        MACHINES.put("GremlinLeader", new GremlinLeaderMachine());
        MACHINES.put("TorchHead", new TorchHeadMachine());
        MACHINES.put("TheCollector", new TheCollectorMachine());
        MACHINES.put("BronzeOrb", new BronzeOrbMachine());
        MACHINES.put("BronzeAutomaton", new BronzeAutomatonMachine());
        MACHINES.put("Champ", new ChampMachine());
        MACHINES.put("Hexaghost", new HexaghostMachine());
        MACHINES.put("BanditBear", new BanditBearMachine());
        MACHINES.put("BanditLeader", new BanditLeaderMachine());
        MACHINES.put("OrbWalker", new OrbWalkerMachine());
        MACHINES.put("Maw", new MawMachine());
        MACHINES.put("Transient", new TransientMachine());
        MACHINES.put("Repulsor", new RepulsorMachine());
        MACHINES.put("Spiker", new SpikerMachine());
        MACHINES.put("SpireGrowth", new SpireGrowthMachine());
        MACHINES.put("Deca", new DecaMachine());
        MACHINES.put("Donu", new DonuMachine());
        MACHINES.put("Exploder", new ExploderMachine());
        MACHINES.put("GiantHead", new GiantHeadMachine());
        MACHINES.put("Nemesis", new NemesisMachine());
        MACHINES.put("Darkling", new DarklingMachine());
        MACHINES.put("SnakeDagger", new SnakeDaggerMachine());
        MACHINES.put("Reptomancer", new ReptomancerMachine());
        MACHINES.put("SpireShield", new SpireShieldMachine());
        MACHINES.put("SpireSpear", new SpireSpearMachine());
        MACHINES.put("CorruptHeart", new CorruptHeartMachine());
        MACHINES.put("WrithingMass", new WrithingMassMachine());
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
            // 🔴 原版字段 debuff 就是**带符号**的：A18 起 -2，否则 -1
            //    （Siphon Soul = new DexterityPower(player, debuff) / 同理 StrengthPower）
            //    影子把它当成「玩家 Dexterity 的增量」存，所以这里也必须是负数，
            //    否则 DamageCalc 的格挡公式（base + Dexterity）会把减益算成增益。
            int debuff = c.ascension >= 18 ? -2 : -1;
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

    /**
     * 尖头强盗（BanditPointy，面具强盗事件）—— 第二章。
     *
     * <pre>
     * getMove: 永远 1(ATTACK)，两段
     * 常数:    每段 5（A2: 6）、HP 30（A7: 34）
     * </pre>
     */
    static final class BanditPointyMachine implements Machine {
        @Override
        public MonsterMove next(Context c) {
            return MonsterMove.multiAttack(1, c.baseDamage(0, c.ascension >= 2 ? 6 : 5), 2);
        }
    }

    /**
     * 小鬼头目（GremlinLeader）—— 第二章精英。
     *
     * <pre>
     * getMove:
     *   numAliveGremlins() == 0:
     *       num &lt; 75 ? (lastMove(2) ? 4(STAB×3) : 2(RALLY)) : (lastMove(4) ? 2(RALLY) : 4(STAB×3))
     *   numAliveGremlins() &lt; 2:
     *       num &lt; 50 ? (lastMove(2) ? getMove(aiRng.random(50,99)) : 2(RALLY))
     *     : num &lt; 80 ? (lastMove(3) ? 4 : 3(DEFEND_BUFF))
     *     :            (lastMove(4) ? getMove(aiRng.random(0,80)) : 4)
     *   否则（≥2 只小鬼）:
     *       num &lt; 66 ? (lastMove(3) ? 4 : 3) : (lastMove(4) ? 3 : 4)
     * takeTurn:
     *   2 → ChangeState(CALL) + 两次 SummonGremlinAction（各填一个空槽）
     *   3 → 全体活怪 +strAmt 力量；**除自己外**的活怪再 +blockAmt 格挡
     *   4 → 3 段 STAB_DMG
     * 常数: STAB_DMG 6 / STAB_AMT 3（**不随进阶变**）、strAmt 3 (A3: 4, A18: 5)、
     *       blockAmt 6 (A18: 10)、HP 140-148 (A8: 145-155)
     * </pre>
     *
     * <p>🔴 召唤是「每次现造一只随机小鬼」而不是「预置 3 只」：
     * {@code usePreBattleAction} 只是把开场就站着的两只登记进 {@code gremlins[0..1]}、
     * 第 3 槽留 null；{@code SummonGremlinAction} 的 {@code identifySlot} 找一个 null/isDying 槽，
     * {@code getRandomGremlin} 再 {@code aiRng.random(0,7)} 从 8 项池里抽类型现造一只。
     *
     * <p>⚠️ 池子 8 项是**带重复**的：Warrior×2 / Thief×2 / Fat×2 / Tsundere×1 / Wizard×1，
     * 所以抽到战士的概率是 1/4，不是 1/5。
     */
    static final class GremlinLeaderMachine implements Machine {

        /** {@code getRandomGremlin} 的 8 项池（重复项不能合并，它决定概率）。 */
        private static MonsterMove.Spawn[] pool(int ascension) {
            int wMin = ascension >= 7 ? 21 : 20;
            int wMax = ascension >= 7 ? 25 : 24;
            int tMin = ascension >= 7 ? 11 : 10;
            int tMax = ascension >= 7 ? 15 : 14;
            int fMin = ascension >= 7 ? 14 : 13;
            int fMax = ascension >= 7 ? 18 : 17;
            int sMin = ascension >= 7 ? 13 : 12;
            int sMax = ascension >= 7 ? 17 : 15;
            int zMin = ascension >= 7 ? 22 : 21;
            int zMax = ascension >= 7 ? 26 : 25;
            return new MonsterMove.Spawn[]{
                    new MonsterMove.Spawn("GremlinWarrior", wMin, wMax),
                    new MonsterMove.Spawn("GremlinWarrior", wMin, wMax),
                    new MonsterMove.Spawn("GremlinThief", tMin, tMax),
                    new MonsterMove.Spawn("GremlinThief", tMin, tMax),
                    new MonsterMove.Spawn("GremlinFat", fMin, fMax),
                    new MonsterMove.Spawn("GremlinFat", fMin, fMax),
                    new MonsterMove.Spawn("GremlinTsundere", sMin, sMax),
                    new MonsterMove.Spawn("GremlinWizard", zMin, zMax),
            };
        }

        private static MonsterMove rally(Context c) {
            // 两个 SummonGremlinAction：各自找空槽，槽位上限 3
            return MonsterMove.summon(2, new MonsterMove.Summon(pool(c.ascension), 2, 3, true));
        }

        private static MonsterMove stab(Context c) {
            return MonsterMove.multiAttack(4, 6, 3);
        }

        private static MonsterMove defend(Context c) {
            int str = c.ascension >= 18 ? 5 : (c.ascension >= 3 ? 4 : 3);
            int block = c.ascension >= 18 ? 10 : 6;
            return new MonsterMove(3, 0, 0, 0, 0, null, 0, null, 0, null, 0, null, 0,
                    null, 0, null, 0, false, false, 0, "Strength", str,
                    MonsterMove.Extra.blockAllies(block));
        }

        @Override
        public MonsterMove next(Context c) {
            int alive = c.aliveOthers();
            if (alive == 0) {
                if (c.num < 75) {
                    return c.lastMove(2) ? stab(c) : rally(c);
                }
                return c.lastMove(4) ? rally(c) : stab(c);
            }
            if (alive < 2) {
                return lowAlive(c, c.num);
            }
            if (c.num < 66) {
                return c.lastMove(3) ? stab(c) : defend(c);
            }
            return c.lastMove(4) ? defend(c) : stab(c);
        }

        /**
         * 「只剩 1 只小鬼」档 —— 原版在这里会**递归调自己**并再抽一次 aiRng：
         * <ul>
         *   <li>{@code num < 50} 且 {@code lastMove(2)} → {@code getMove(aiRng.random(50,99))}；</li>
         *   <li>{@code num >= 80} 且 {@code lastMove(4)} → {@code getMove(aiRng.random(0,80))}。</li>
         * </ul>
         *
         * <p>⚠️ 后者的新 num 落在 0..79，**可能又 < 50**，于是再递归一次 —— 也就是说
         * 「递归一次就结束」是错的，原版是「抽到不进这两个分支为止」。这里用循环照抄，
         * 不做深度截断（截断会改变 aiRng 的消耗次数，进而让整条序列错位）。
         */
        private MonsterMove lowAlive(Context c, int num) {
            int current = num;
            while (true) {
                if (current < 50) {
                    if (!c.lastMove(2)) {
                        return rally(c);
                    }
                    current = c.rollAiRange(50, 99);
                    continue;
                }
                if (current < 80) {
                    return c.lastMove(3) ? stab(c) : defend(c);
                }
                if (!c.lastMove(4)) {
                    return stab(c);
                }
                current = c.rollAiRange(0, 80);
            }
        }
    }

    /**
     * 火把头（TorchHead）—— TheCollector 召唤的小怪。
     *
     * <pre>
     * getMove: 永远 1(TACKLE)
     * takeTurn: 1 → 一段 damage[0]（7）+ SetMoveAction(1)（等价于永远同一招）
     * 常数:     伤害 7（不随进阶）、HP 38-40（A9: 40-45，走 monsterHpRng）
     * </pre>
     */
    static final class TorchHeadMachine implements Machine {
        @Override
        public MonsterMove next(Context c) {
            return MonsterMove.attack(1, c.baseDamage(0, 7));
        }
    }

    /**
     * 收藏家（TheCollector）—— 第二章 Boss。
     *
     * <pre>
     * getMove:
     *   initialSpawn        → 1（开场招：召唤）
     *   turnsTaken ≥ 3 且 !ultUsed → 4（大招）
     *   num ≤ 25 且 isMinionDead() 且 !lastMove(5) → 5（补一只火把头）
     *   num ≤ 70 且 !lastTwoMoves(2) → 2（一段 rakeDmg）
     *   !lastMove(3) ? 3（格挡 + 全队力量） : 2
     * takeTurn:
     *   1 → 造 2 只 TorchHead（槽 1、2）并置 initialSpawn=false
     *   2 → 一段 damage[0]
     *   3 → 自己 +blockAmt（A19: +5）格挡；所有活怪 +strAmt 力量
     *   4 → 玩家 Weak / Vulnerable / Frail 各 megaDebuffAmt；置 ultUsed=true
     *   5 → 遍历 enemySlots，**每个死槽**补一只 TorchHead
     *   末尾 turnsTaken++
     * 常数: HP 282 (A9: 300)、blockAmt 15 (A9: 18)、rakeDmg 18 (A4: 21, A19: 21)、
     *       strAmt 3 (A4: 4, A19: 5)、megaDebuffAmt 3 (A4: 3, A19: 5)
     * </pre>
     *
     * <p>{@code initialSpawn} 等价于「history 为空」；{@code turnsTaken} 等价于
     * {@code history.size()}（每回合末尾各 +1 一次）；{@code ultUsed} 等价于
     * 「history 里出现过 4」。
     */
    static final class TheCollectorMachine implements Machine {
        @Override
        public MonsterMove next(Context c) {
            int rake = c.baseDamage(0, c.ascension >= 4 ? 21 : 18);
            if (c.history.isEmpty()) {
                return MonsterMove.summon(1,
                        MonsterMove.Summon.of("TorchHead", c.ascension >= 9 ? 40 : 38,
                                c.ascension >= 9 ? 45 : 40, 2, false));
            }
            int turnsTaken = c.history.size();
            boolean ultUsed = c.history.contains(Integer.valueOf(4));
            if (turnsTaken >= 3 && !ultUsed) {
                return megaDebuff(c);
            }
            if (c.num <= 25 && c.hasDeadOf("TorchHead") && !c.lastMove(5)) {
                return MonsterMove.summon(5,
                        MonsterMove.Summon.of("TorchHead", c.ascension >= 9 ? 40 : 38,
                                c.ascension >= 9 ? 45 : 40, 2, false));
            }
            if (c.num <= 70 && !c.lastTwoMoves(2)) {
                return MonsterMove.attack(2, rake);
            }
            return c.lastMove(3) ? MonsterMove.attack(2, rake) : defend(c);
        }

        private static MonsterMove megaDebuff(Context c) {
            int amt = c.ascension >= 19 ? 5 : 3;
            // 一次上三样：Weak + Vulnerable + Frail，各 amt 层
            return new MonsterMove(4, 0, 0, 0, 0, null, 0, "Weakened", amt,
                    "Vulnerable", amt, null, 0, null, 0, null, 0, false, false, 0, null, 0,
                    MonsterMove.Extra.thirdPlayerDebuff("Frail", amt));
        }

        private static MonsterMove defend(Context c) {
            int block = c.ascension >= 9 ? 18 : 15;
            if (c.ascension >= 19) {
                block += 5;
            }
            int str = c.ascension >= 19 ? 5 : (c.ascension >= 4 ? 4 : 3);
            return MonsterMove.blockAndBuffAll(3, block, "Strength", str);
        }
    }

    /**
     * 铜球（BronzeOrb）—— BronzeAutomaton 召唤的球。
     *
     * <pre>
     * getMove:
     *   !usedStasis && num ≥ 25 → 3(STASIS) 且置 usedStasis（**在 getMove 里就置位**）
     *   num ≥ 70 且 !lastTwoMoves(2) → 2(FLUX，给主人加格挡)
     *   !lastTwoMoves(1) → 1(BEAM，一段 8)
     *   否则 → 2
     * takeTurn:
     *   1 → 一段 damage[0]（8）
     *   2 → 给 BronzeAutomaton +12 格挡（不是给自己）
     *   3 → ApplyStasisAction：从玩家弃牌堆/抽牌堆按 RARE→UNCOMMON→COMMON→任意 的顺序
     *       抽走一张牌挂在球上（**这一块没建模**，见下面说明）
     * 常数:     HP 52-58（A9: 54-60，走 monsterHpRng）、伤害 8
     * </pre>
     *
     * <p>⚠️ Stasis 的「偷一张牌」没有建模：它要按稀有度排序在玩家的两个牌堆里挑，
     * 而 {@code SimCard} 目前不带稀有度。偏差方向是**影子高估玩家手牌一张**
     * （搜索会略微乐观），招式节奏本身是对的（3 号招就是「这回合不输出」）。
     */
    static final class BronzeOrbMachine implements Machine {
        @Override
        public MonsterMove next(Context c) {
            boolean usedStasis = c.history.contains(Integer.valueOf(3));
            if (!usedStasis && c.num >= 25) {
                return MonsterMove.none(3);
            }
            if (c.num >= 70 && !c.lastTwoMoves(2)) {
                return MonsterMove.blockTypedAlly(2, "BronzeAutomaton", 12);
            }
            if (!c.lastTwoMoves(1)) {
                return MonsterMove.attack(1, c.baseDamage(0, 8));
            }
            return MonsterMove.blockTypedAlly(2, "BronzeAutomaton", 12);
        }
    }

    /**
     * 铜制自动机（BronzeAutomaton）—— 第二章 Boss。
     *
     * <pre>
     * getMove:
     *   firstTurn       → 4(BOOST) 且 firstTurn=false
     *   numTurns == 4   → 2(BEAM)，并置 numTurns=0
     *   lastMove(2)     → A19 ? 5 : 3(STUN)
     *   lastMove(3) || lastMove(5) || lastMove(4) → 1(FLAIL，两段)
     *   否则            → 5
     *   末尾（只在最后两条路径）numTurns++
     * takeTurn:
     *   1 → 两段 damage[0]（flailDmg）
     *   2 → 一段 damage[1]（beamDmg）
     *   3 → 只有一句 STUNNED 文字，什么也不做
     *   4 → 造 2 只 BronzeOrb
     *   5 → 自己 +blockAmt 格挡、自己 +strAmt 力量
     * 常数: HP 300 (A9: 320)、blockAmt 9 (A9: 12)、flailDmg 7 (A4: 8)、
     *       beamDmg 45 (A4: 50)、strAmt 3 (A4: 4)
     * </pre>
     *
     * <p>{@code firstTurn} 等价于「history 为空」；{@code numTurns} 是个只在特定分支自增的
     * 计数器，但它**只依赖历史**，所以可以从 history 逐条重放出来（见 {@link #replayNumTurns}）。
     */
    static final class BronzeAutomatonMachine implements Machine {
        @Override
        public MonsterMove next(Context c) {
            int flail = c.baseDamage(0, c.ascension >= 4 ? 8 : 7);
            int beam = c.baseDamage(1, c.ascension >= 4 ? 50 : 45);
            if (c.history.isEmpty()) {
                return MonsterMove.summon(4,
                        MonsterMove.Summon.of("BronzeOrb", c.ascension >= 9 ? 54 : 52,
                                c.ascension >= 9 ? 60 : 58, 2, false));
            }
            int numTurns = replayNumTurns(c.history);
            if (numTurns == 4) {
                return MonsterMove.attack(2, beam);
            }
            if (c.lastMove(2)) {
                return c.ascension >= 19 ? defend(c) : MonsterMove.none(3);
            }
            if (c.lastMove(3) || c.lastMove(5) || c.lastMove(4)) {
                return MonsterMove.multiAttack(1, flail, 2);
            }
            return defend(c);
        }

        private static MonsterMove defend(Context c) {
            int block = c.ascension >= 9 ? 12 : 9;
            int str = c.ascension >= 4 ? 4 : 3;
            return MonsterMove.buffBlock(5, "Strength", str, block);
        }
    }

    /**
     * 重放 {@code BronzeAutomaton.numTurns}：它只在「走到 getMove 末尾」的那两条路径自增，
     * 早期 return 的路径（firstTurn / numTurns==4 / lastMove(2)）不增。
     *
     * @param history 已经打过的招（按时间顺序）
     * @return 当前（下一次 getMove 之前）的 numTurns
     */
    static int replayNumTurns(List<Integer> history) {
        int numTurns = 0;
        boolean first = true;
        for (int i = 0; i < history.size(); i++) {
            if (first) {
                first = false;              // 第 1 次决策：4(BOOST)，numTurns 不变
                continue;
            }
            if (numTurns == 4) {
                numTurns = 0;               // 第 N 次决策：2(BEAM)，重置
                continue;
            }
            int prev = i >= 1 ? history.get(i - 1).intValue() : -1;
            if (prev == 2) {
                continue;                   // 打完 BEAM 之后的 STUN/防御，numTurns 不变
            }
            numTurns++;                     // 1(FLAIL) 与 5 这两条路径会自增
        }
        return numTurns;
    }

    /**
     * 勇士（Champ）—— 第二章 Boss。
     *
     * <pre>
     * getMove（💡 第一句永远是 numTurns++）:
     *   hp &lt; maxHp/2 且 !thresholdReached → 7(Anger) 且置 thresholdReached
     *   !lastMove(3) 且 !lastMoveBefore(3) 且 thresholdReached → 3(Execute，2 段)
     *   numTurns == 4 且 !thresholdReached → 6(Taunt)，并把 numTurns 清零
     *   !lastMove(2) 且 forgeTimes &lt; forgeThreshold 且 num ≤ (A19 ? 30 : 15) → 2(Defensive Stance)
     *   !lastMove(5) 且 !lastMove(2) 且 num ≤ 30 → 5(Gloat)
     *   !lastMove(4) 且 num ≤ 55 → 4(Face Slap)
     *   !lastMove(1) ? 1(Heavy Slash) : 4(Face Slap)
     * takeTurn:
     *   1 → 一段 damage[0]（slashDmg）
     *   2 → 自己 +blockAmt 格挡、+forgeAmt 金属化
     *   3 → **两段** damage[1]（EXEC_COUNT = 2）
     *   4 → 一段 damage[2]（slapDmg）+ 玩家 Weak 2 + Vulnerable 2
     *   5 → 自己 +strAmt 力量
     *   6 → 玩家 Weak 2 + Vulnerable 2（这回合不打人）
     *   7 → RemoveDebuffsAction(自己) + 移除 Shackled + 自己 +strAmt **×3** 力量
     * 常数: HP 420 (A9: 440)、forgeThreshold 2；
     *       A19+: slash18/exec10/slap14/str4/forge7/block20；
     *       A9-18: 18/10/14/3/6/18；A4-8: 18/10/14/3/5/15；&lt;A4: 16/10/12/2/5/15
     * </pre>
     *
     * <p>⚠️ {@code damage()} **没有**被重写（字节码里只有动画），所以「三阶段」全部在
     * {@code getMove} 里：半血看 {@code currentHealth < maxHealth/2}，狂暴是 7 号招，
     * 之后每次决策都优先判「能不能处决」。{@code thresholdReached} / {@code forgeTimes}
     * / {@code numTurns} 三个字段全都只依赖历史，可以重放。
     */
    static final class ChampMachine implements Machine {
        private static final int FORGE_THRESHOLD = 2;

        @Override
        public MonsterMove next(Context c) {
            int numTurns = champNumTurns(c.history);
            boolean threshold = c.history.contains(Integer.valueOf(7));
            int forgeTimes = countOf(c.history, 2);
            int str = strAmt(c.ascension);

            if (c.monster != null && c.monster.hp * 2 < c.monster.maxHp && !threshold) {
                return new MonsterMove(7, 0, 0, 0, 0, "Strength", str * 3,
                        null, 0, null, 0, null, 0, null, 0, null, 0, false, false,
                        0, null, 0, MonsterMove.Extra.cleanseSelfOnly());
            }
            if (!c.lastMove(3) && !c.lastMoveBefore(3) && threshold) {
                return MonsterMove.multiAttack(3, c.baseDamage(1, 10), 2);
            }
            if (numTurns == 4 && !threshold) {
                return taunt();
            }
            int stanceThreshold = c.ascension >= 19 ? 30 : 15;
            if (!c.lastMove(2) && forgeTimes < FORGE_THRESHOLD && c.num <= stanceThreshold) {
                return MonsterMove.buffBlock(2, "Metallicize", forgeAmt(c.ascension),
                        blockAmt(c.ascension));
            }
            if (!c.lastMove(5) && !c.lastMove(2) && c.num <= 30) {
                return MonsterMove.buff(5, "Strength", str);
            }
            if (!c.lastMove(4) && c.num <= 55) {
                return faceSlap(c);
            }
            return c.lastMove(1) ? faceSlap(c) : MonsterMove.attack(1, c.baseDamage(0, slash(c.ascension)));
        }

        private static MonsterMove faceSlap(Context c) {
            return MonsterMove.attackDoubleDebuff(4, c.baseDamage(2, slap(c.ascension)),
                    "Weakened", 2, "Vulnerable", 2);
        }

        private static MonsterMove taunt() {
            return MonsterMove.doubleDebuff(6, "Weakened", 2, "Vulnerable", 2);
        }

        private static int slash(int a) {
            return a >= 4 ? 18 : 16;
        }

        private static int slap(int a) {
            return a >= 4 ? 14 : 12;
        }

        private static int strAmt(int a) {
            return a >= 19 ? 4 : (a >= 4 ? 3 : 2);
        }

        private static int forgeAmt(int a) {
            return a >= 19 ? 7 : (a >= 9 ? 6 : 5);
        }

        private static int blockAmt(int a) {
            return a >= 19 ? 20 : (a >= 9 ? 18 : 15);
        }

        private static int countOf(List<Integer> history, int id) {
            int n = 0;
            for (Integer v : history) {
                if (v.intValue() == id) {
                    n++;
                }
            }
            return n;
        }
    }

    /**
     * 重放 {@code Champ.numTurns}：它在 {@code getMove} 的**第一句**无条件自增，
     * 只有「numTurns==4 且还没半血」那条路径会把它清零。
     *
     * @return **下一次**决策时 {@code numTurns} 的值（已经算上开头那次自增）
     */
    static int champNumTurns(List<Integer> history) {
        int numTurns = 0;
        for (Integer id : history) {
            numTurns++;                          // 决策开头无条件 +1
            if (id.intValue() == 6) {            // Taunt 那条路径把它清零
                numTurns = 0;
            }
        }
        return numTurns + 1;                     // 下一次决策开头还要 +1
    }

    /**
     * 六火（{@code Hexaghost}）。
     *
     * <p><b>「开场 6 个 orb + 半血分裂」这个模型是错的</b> —— 字节码里它既没有
     * {@code damage()} 也没有 {@code die()} 重写，6 个 {@code HexaghostOrb} 是构造器里
     * {@code createOrbs()} 造的**纯视觉对象**（没有血、打不掉）。真正驱动出招的是一个 int 字段
     * {@code orbActiveCount}：
     *
     * <ul>
     *   <li>{@code changeState("Activate")}（第 1 回合）→ 6 个 orb 点亮，{@code orbActiveCount = 6}，
     *       并且 {@code takeTurn} 里**硬把下一招设成 Divider**（所以 Divider 不走 {@code getMove}）；</li>
     *   <li>{@code changeState("Activate Orb")}（Sear / Tackle / Strengthen 的收尾）→ 点亮下一个 orb，
     *       {@code orbActiveCount++}；**加到 6 时直接把下一招设成 Inferno**（也绕过 {@code getMove}）；</li>
     *   <li>{@code changeState("Deactivate")}（Divider / Inferno 的收尾）→ 全部熄灭，{@code orbActiveCount = 0}。</li>
     * </ul>
     *
     * <p>所以整场是**确定性循环**，一个随机数都不用（{@code num} 抽了但不看）：
     * <pre>
     * Activate → Divider(6×[玩家血/12+1]) → Sear → Tackle → Sear → Strengthen → Sear → Tackle → Sear
     *          → Inferno(6×2/3) → Sear → Tackle → … （之后每 7 招一轮）
     * </pre>
     *
     * <p>⚠️ 未建模：Sear 塞的 **Burn 在回合末的伤害**（2 / 升级后 4）。那是状态牌机制、
     * 不是怪物 AI，影子目前对所有状态牌都只当「占手牌」。偏差方向 = 影子**低估**玩家受伤。
     * 同理 {@code burnUpgraded}（第一次 Inferno 之后 Burn 变 Burn+）在影子里没有可观测效果。
     */
    static final class HexaghostMachine implements Machine {
        @Override
        public MonsterMove next(Context c) {
            if (c.history.isEmpty()) {
                return MonsterMove.none(5);              // Activate：不打人，下一招被硬设成 Divider
            }
            if (c.lastMove(5)) {
                return divider(c);                       // takeTurn 的 5 号分支里 setMove(1, ...)
            }
            int tackleDmg = c.baseDamage(0, c.ascension >= 4 ? 6 : 5);
            int infernoDmg = c.baseDamage(3, c.ascension >= 4 ? 3 : 2);
            int searDmg = c.baseDamage(1, 6);
            int str = c.ascension >= 19 ? 3 : 2;
            int burns = c.ascension >= 19 ? 2 : 1;
            switch (hexaghostOrbs(c.history)) {
                case 1:                                   // Tackle
                case 4:
                    return MonsterMove.multiAttack(2, tackleDmg, 2);
                case 3:                                   // Strengthen
                    return MonsterMove.buffBlock(3, "Strength", str, 12);
                case 6:                                   // Inferno（takeTurn 里直接设的 6 号招）
                    return MonsterMove.multiAttack(6, infernoDmg, 6);
                default:                                  // 0 / 2 / 5 → Sear
                    return MonsterMove.attackStatus(4, searDmg, "Burn", burns);
            }
        }

        /**
         * Divider：{@code takeTurn} 的 5 号分支先把 {@code damage[2].base} 改成
         * 「玩家**当前**血量 / 12 + 1」，再 {@code applyPowers()}，再 {@code setMove(1, ATTACK, base, 6, true)}。
         * 所以这个数在 Activate 那一刻就定死了，之后玩家掉血也不会变。
         */
        private static MonsterMove divider(Context c) {
            int hp = c.state == null ? 12 : c.state.hp;
            return MonsterMove.multiAttack(1, hp / 12 + 1, 6);
        }
    }

    /**
     * 蛇法师（{@code Reptomancer}，第三章精英）。
     *
     * <pre>
     * 第 1 招固定 2（召唤）：开场先放 {@code daggersPerSpawn} 只蛇匕首（A18: 2，否则 1）
     * num&lt;33 ：上一招不是 1 → 1 Snake Strike（两段 16|18 + Weak 1）；否则**递归** random(33,99)
     * num&lt;66 ：没连打两次 2 且「场上除自己外活怪 &lt; 3」→ 2 再召唤；否则 1
     * 否则    ：上一招不是 3 → 3 Big Bite（34|38）；否则**递归** random(65)
     * </pre>
     *
     * <p>`canSpawn()` 的判据是「除自己以外的活怪数量 < 3」（含别的召唤物）。
     * 召唤物 `SnakeDagger` 已单独镜像（HP 走 `monsterHpRng.random(20,25)`）。
     */
    static final class ReptomancerMachine implements Machine {
        @Override
        public MonsterMove next(Context c) {
            int perSpawn = c.ascension >= 18 ? 2 : 1;
            int snake = c.baseDamage(0, c.ascension >= 3 ? 18 : 16);
            int bite = c.baseDamage(1, c.ascension >= 3 ? 38 : 34);
            if (c.history.isEmpty()) {
                return summon(perSpawn);
            }
            int num = c.num;
            for (int guard = 0; guard < 16; guard++) {
                if (num < 33) {
                    if (!c.lastMove(1)) {
                        return MonsterMove.multiAttackDebuff(1, snake, 2, "Weakened", 1);
                    }
                    num = c.rollAiRange(33, 99);
                    continue;
                }
                if (num < 66) {
                    if (!c.lastTwoMoves(2) && c.aliveOthers() < 3) {
                        return summon(perSpawn);
                    }
                    return MonsterMove.multiAttackDebuff(1, snake, 2, "Weakened", 1);
                }
                if (!c.lastMove(3)) {
                    return MonsterMove.attack(3, bite);
                }
                num = c.rollAiRange(0, 65);
            }
            return MonsterMove.attack(3, bite);
        }

        private static MonsterMove summon(int count) {
            return MonsterMove.summon(2, MonsterMove.Summon.of("SnakeDagger", 20, 25, count, false));
        }
    }

    /**
     * 蛇匕首（{@code SnakeDagger}，第三章 Reptomancer 的召唤物）。
     *
     * <pre>
     * getMove：firstMove（只有第 1 次）→ 1：9 伤 + 塞 1 张 Wound
     *          之后                    → 2：25 伤，然后**自爆自杀**（LoseHPAction 自己）
     * </pre>
     *
     * <p>它的防御手段是 **Malleable**（开场带着，挨打加格挡）—— 那条在
     * {@code DamageCalc.applyMalleable} 与 {@code CombatFlow} ④.5 里，不在这个类里。
     */
    static final class SnakeDaggerMachine implements Machine {
        @Override
        public MonsterMove next(Context c) {
            if (c.history.isEmpty()) {
                return MonsterMove.attackStatus(1, c.baseDamage(0, 9), "Wound", 1);
            }
            return MonsterMove.explode(2, c.baseDamage(1, 25));
        }
    }

    /**
     * 小黑（{@code Darkling}，第三章，一次三只）。
     *
     * <pre>
     * 半死（Half Dead 标记）      → 5 Reincarnate（回半血、清标记；血量恢复在 CombatFlow）
     * 第 1 招：num&lt;50 → 2 Harden（12 格挡；A17 起再 +2 力量）；否则 3 Chomp（10|12）
     * num&lt;40 ：没连打过 1 且**自己在队伍里的位次是偶数** → 1 Nip（两段 7|9）
     *            否则**递归**：再抽 aiRng.random(40,99) 重新走一遍
     * num&lt;70 ：上一招不是 2 → Harden；否则 Chomp
     * 否则    ：没连打两次 3 → Chomp；否则**递归**：再抽 aiRng.random(0,99)
     * </pre>
     *
     * <p>两条递归都要照抄（多消耗一次 aiRng，区间还不同），否则整条随机序列错位。
     * 半死/复活/三只联动在 {@code DamageCalc.maybeHalfKillDarkling} 与
     * {@code CombatFlow}（{@code Extra.reincarnate}）里。
     */
    static final class DarklingMachine implements Machine {
        @Override
        public MonsterMove next(Context c) {
            if (c.monster != null && c.monster.powers.containsKey("Half Dead")) {
                return MonsterMove.extra(5, MonsterMove.Extra.reincarnate());
            }
            int chomp = c.baseDamage(1, c.ascension >= 2 ? 12 : 10);
            int nip = c.baseDamage(0, c.ascension >= 2 ? 9 : 7);
            if (c.history.isEmpty()) {
                return c.num < 50 ? harden(c) : MonsterMove.attack(3, chomp);
            }
            int num = c.num;
            for (int guard = 0; guard < 16; guard++) {
                if (num < 40) {
                    if (!c.lastMove(1) && c.slot % 2 == 0) {
                        return MonsterMove.multiAttack(1, nip, 2);
                    }
                    num = c.rollAiRange(40, 99);
                    continue;
                }
                if (num < 70) {
                    return c.lastMove(2) ? MonsterMove.attack(3, chomp) : harden(c);
                }
                if (!c.lastTwoMoves(3)) {
                    return MonsterMove.attack(3, chomp);
                }
                num = c.rollAiRange(0, 99);
            }
            return MonsterMove.attack(3, chomp);   // 理论到不了；防止死循环
        }

        private static MonsterMove harden(Context c) {
            return c.ascension >= 17 ? MonsterMove.buffBlock(2, "Strength", 2, 12)
                    : MonsterMove.block(2, 12);
        }
    }

    /**
     * 梦魇（{@code Nemesis}，第三章精英）。
     *
     * <p>三段式 num 状态机 + 一个自减冷却 {@code scytheCooldown}（用完巨镰后归 2，
     * 每次决策开头 -1），分支里还会**额外抽 aiRng.randomBoolean()** 硬币：
     *
     * <pre>
     * 第 1 招：num&lt;50 → 2 三连火（6|7 × 3）；否则 4 塞 3 张 Burn（A18: 5）
     * num&lt;30 ：冷却好了就 3 巨镰(45)；否则掷硬币决定「接着打」还是「塞 Burn」
     * num&lt;65 ：没连打两次 2 就打；否则掷硬币 → 冷却没好塞 Burn / 好了上巨镰
     * 否则    ：上一招不是 4 就塞 Burn；否则掷硬币 + 冷却好了 → 巨镰，不然三连火
     * </pre>
     *
     * <p>**每回合末尾它都会补一层 Intangible**（`if (!hasPower("Intangible"))`）——
     * 用 {@code Extra.onlyIfAbsent("Intangible", 1)} 表达，配合
     * {@code IntangiblePower} 的 {@code justApplied}（挂上那回合不 tick），
     * 效果就是「玩家每打它两回合，只有一回合能打出伤害」。
     */
    static final class NemesisMachine implements Machine {
        @Override
        public MonsterMove next(Context c) {
            int fire = c.baseDamage(1, c.ascension >= 3 ? 7 : 6);
            int burns = c.ascension >= 18 ? 5 : 3;
            int scythe = c.baseDamage(0, 45);
            int cooldown = nemesisCooldown(c.history);
            if (c.history.isEmpty()) {
                return c.num < 50 ? fire(fire, burns)
                        : burn(burns);
            }
            if (c.num < 30) {
                if (!c.lastMove(3) && cooldown <= 0) {
                    return scythe(scythe, burns);
                }
                if (c.randomBoolean()) {
                    return c.lastTwoMoves(2) ? burn(burns) : fire(fire, burns);
                }
                return c.lastMove(4) ? fire(fire, burns) : burn(burns);
            }
            if (c.num < 65) {
                if (!c.lastTwoMoves(2)) {
                    return fire(fire, burns);
                }
                if (c.randomBoolean()) {
                    return cooldown > 0 ? burn(burns) : scythe(scythe, burns);
                }
                return burn(burns);
            }
            if (!c.lastMove(4)) {
                return burn(burns);
            }
            if (c.randomBoolean() && cooldown <= 0) {
                return scythe(scythe, burns);
            }
            return fire(fire, burns);
        }

        /** 2 号招：三段火（`damage[1]`），打完补无实体。 */
        private static MonsterMove fire(int dmg, int burns) {
            return withIntangible(new MonsterMove(2, dmg, 3, 0, 0, null, 0, null, 0, null, 0,
                    null, 0, null, 0, null, 0, false, false, 0, null, 0,
                    MonsterMove.Extra.onlyIfAbsent("Intangible", 1)));
        }

        /** 3 号招：巨镰（`damage[0]`）。 */
        private static MonsterMove scythe(int dmg, int burns) {
            return withIntangible(new MonsterMove(3, dmg, 1, 0, 0, null, 0, null, 0, null, 0,
                    null, 0, null, 0, null, 0, false, false, 0, null, 0,
                    MonsterMove.Extra.onlyIfAbsent("Intangible", 1)));
        }

        /** 4 号招：往弃牌堆塞 Burn，另补无实体。 */
        private static MonsterMove burn(int count) {
            return withIntangible(MonsterMove.status(4, "Burn", count));
        }

        /** 给任意招式补上「缺失才挂无实体」的载荷（原版写在 takeTurn 末尾，对所有招生效）。 */
        private static MonsterMove withIntangible(MonsterMove base) {
            return new MonsterMove(base.id, base.damage, base.hits, base.block,
                    base.blockRandomAlly, base.selfBuff, base.selfBuffAmount,
                    base.playerDebuff, base.playerDebuffAmount,
                    base.playerDebuff2, base.playerDebuff2Amount,
                    base.statusCard, base.statusCount, base.splitInto, base.splitCount,
                    base.splitInto2, base.splitCount2, base.escape, base.vampire,
                    base.healAllies, base.buffAlliesPower, base.buffAlliesAmount,
                    MonsterMove.Extra.onlyIfAbsent("Intangible", 1));
        }
    }

    /**
     * 重放 {@code Nemesis.scytheCooldown}：初值 0，每次 {@code getMove} 开头 -1，
     * 打出巨镰（3 号招）后归 2。
     */
    static int nemesisCooldown(List<Integer> history) {
        int cd = 0;
        for (Integer id : history) {
            cd--;
            if (id.intValue() == 3) {
                cd = 2;
            }
        }
        return cd - 1;                    // 即将到来的那次决策开头还要 -1
    }

    /**
     * 巨首（{@code GiantHead}，第三章精英）。
     *
     * <pre>
     * count 初值 5，**每次 getMove 开头 -1**（所以第 k 次决策后 count = 5-k）
     * count(减之前) &gt; 1 → num&lt;50 ? (最近不是连着两次 1 ? 1 Glare : 3 Count 13)
     *                              : (最近不是连着两次 3 ? 3 Count 13 : 1 Glare)
     * count(减之前) ≤ 1 → 2 It's Time：伤害 = startingDeathDmg - count*5
     *                      （takeTurn 里是 damage[1 - count]，count 封底 -6 → 最高 +30）
     * </pre>
     *
     * <p>Glare 只上 **Weak 1**（字节码就是 `iconst_1`，别按印象写成 5）。
     *
     * <p>它真正的威胁是 **Slow**（`usePreBattleAction` 挂 `SlowPower(0)`）：
     * 玩家每打一张牌，它受伤 +10%（回合末清零）—— 那条在
     * {@code Hooks.monsterOnCardUsed} 与 {@code DamageCalc} 里，不在这个类里。
     */
    static final class GiantHeadMachine implements Machine {
        @Override
        public MonsterMove next(Context c) {
            int start = c.baseDamage(1, c.ascension >= 3 ? 40 : 30);
            if (c.history.size() >= 4) {
                int count = Math.max(-6, 4 - c.history.size());
                return MonsterMove.attack(2, start - count * 5);
            }
            int count13 = c.baseDamage(0, 13);
            if (c.num < 50) {
                return c.lastTwoMoves(1) ? MonsterMove.attack(3, count13)
                        : MonsterMove.debuff(1, "Weakened", 1);
            }
            return c.lastTwoMoves(3) ? MonsterMove.debuff(1, "Weakened", 1)
                    : MonsterMove.attack(3, count13);
        }
    }

    /**
     * 自爆者（{@code Exploder}，第三章）。它的「爆炸」**不在 getMove/takeTurn 里**：
     *
     * <pre>
     * usePreBattleAction → ApplyPowerAction(new ExplosivePower(this, 3))
     * ExplosivePower.duringTurn():
     *     if (amount == 1 &amp;&amp; !owner.isDying) {
     *         addToBot(new SuicideAction(owner));
     *         addToBot(new DamageAction(player, new DamageInfo(owner, 30, THORNS)));
     *     }
     *     amount--;                       // 每回合递减
     * </pre>
     *
     * <p>所以它**打两下（turnCount 1、2）之后在第三回合自爆**，
     * 而 {@code takeTurn} 的 2 号分支是空的（`goto`）—— 那一招只是给玩家看的意图。
     * 爆炸 30 点是 **THORNS 类型**（吃格挡），打完自己下场。
     */
    static final class ExploderMachine implements Machine {
        @Override
        public MonsterMove next(Context c) {
            if (c.history.size() >= 2) {
                return MonsterMove.explode(2, 30);
            }
            return MonsterMove.attack(1, c.baseDamage(0, c.ascension >= 2 ? 11 : 9));
        }
    }

    /**
     * 迪卡（{@code Deca}，第三章 boss 之一，与 Donu 成对）。
     *
     * <p>两只共用一套「交替」模型：字段 {@code isAttacking} **初值 true**，
     * 攻击招（id 0）打完后置 false，辅助招（id 2）做完置 true —— 所以出招永远是
     * 攻击 ↔ 辅助 交替，而且第一招一定是攻击。重放规则：{@code attacking = 上一招不是 0}。
     *
     * <pre>
     * 攻击（0）：2 段 × beamDmg（10|12，A4 起）**外加往弃牌堆塞 2 张 Dazed**
     * 辅助（2）：全体（含自己）16 格挡；A19 起再给全体 +3 Plated Armor
     * </pre>
     *
     * <p>开场的 Artifact（A19: 3 / 否则 2）是 {@code usePreBattleAction} 挂的，快照里有。
     */
    static final class DecaMachine implements Machine {
        @Override
        public MonsterMove next(Context c) {
            int beam = c.baseDamage(0, c.ascension >= 4 ? 12 : 10);
            if (alternatingAttacking(c.history)) {
                return MonsterMove.multiAttackStatus(0, beam, 2, "Dazed", 2);
            }
            if (c.ascension >= 19) {
                return MonsterMove.blockAllAndBuffAll(2, 16, "Plated Armor", 3);
            }
            return MonsterMove.blockAll(2, 16);
        }
    }

    /** 多努（{@code Donu}，第三章 boss 之一）：攻击 2 段（10|12）；辅助给**全体 +3 力量**。 */
    static final class DonuMachine implements Machine {
        @Override
        public MonsterMove next(Context c) {
            int beam = c.baseDamage(0, c.ascension >= 4 ? 12 : 10);
            if (alternatingAttacking(c.history)) {
                return MonsterMove.multiAttack(0, beam, 2);
            }
            return MonsterMove.buffAll(2, "Strength", 3);
        }
    }

    /**
     * Deca / Donu 共用的 {@code isAttacking} 重放：初值 true，攻击招（id 0）之后变 false、
     * 辅助招（id 2）之后变 true。所以「下一个是不是攻击」= 「上一招不是 0」。
     */
    static boolean alternatingAttacking(List<Integer> history) {
        return history.isEmpty() || history.get(history.size() - 1).intValue() != 0;
    }

    /**
     * 塔生长物（{@code SpireGrowth}，第三章）。
     *
     * <pre>
     * A17 起且玩家还没有缠绕且上一招不是 2 → 2 Constrict：玩家挂 Constricted 10（A17: 12）
     * num &lt; 50 且最近不是连着两次 1        → 1 快拍（16|18）
     * 玩家还没有缠绕且上一招不是 2          → 2 Constrict
     * 最近不是连着两次 3                    → 3 重砸（22|25）
     * 否则                                  → 1 快拍
     * </pre>
     *
     * <p>缠绕的**伤害**不在这一层：`ConstrictedPower.atEndOfTurn` 由 {@code CombatFlow}
     * 在玩家回合末统一结算（吃格挡）。这里只负责「挂上去」。
     */
    static final class SpireGrowthMachine implements Machine {
        @Override
        public MonsterMove next(Context c) {
            boolean constricted = c.state != null
                    && c.state.playerPowers.get("Constricted") != null
                    && c.state.playerPowers.get("Constricted").intValue() > 0;
            int tackle = c.baseDamage(0, c.ascension >= 2 ? 18 : 16);
            int smash = c.baseDamage(1, c.ascension >= 2 ? 25 : 22);
            int constrict = c.ascension >= 17 ? 12 : 10;
            if (c.ascension >= 17 && !constricted && !c.lastMove(2)) {
                return MonsterMove.debuff(2, "Constricted", constrict);
            }
            if (c.num < 50 && !c.lastTwoMoves(1)) {
                return MonsterMove.attack(1, tackle);
            }
            if (!constricted && !c.lastMove(2)) {
                return MonsterMove.debuff(2, "Constricted", constrict);
            }
            if (!c.lastTwoMoves(3)) {
                return MonsterMove.attack(3, smash);
            }
            return MonsterMove.attack(1, tackle);
        }
    }

    /**
     * 尖刺者（{@code Spiker}，第三章）。
     *
     * <pre>
     * thornsCount &gt; 5                  → 1 打一下（7|9）
     * num &lt; 50 且上一招不是 1          → 1 打一下
     * 否则                             → 2 Sharpen：thornsCount++，自己 Thorns +2
     * </pre>
     *
     * <p>开场自带的 Thorns 3（A2: 4）是 {@code usePreBattleAction} 挂的，**真机快照里已经有了**，
     * 不用我们复现；翻倍的部分才走 {@code selfBuff}。
     * 玩家打它时的荆棘反伤由 {@code DamageCalc.applyThorns}（已存在）负责。
     * {@code thornsCount} 从 0 起、只在 2 号招里 +1 → 直接数历史里 2 的个数。
     */
    static final class SpikerMachine implements Machine {
        @Override
        public MonsterMove next(Context c) {
            int attack = c.baseDamage(0, c.ascension >= 2 ? 9 : 7);
            int sharpenTimes = 0;
            for (Integer id : c.history) {
                if (id.intValue() == 2) {
                    sharpenTimes++;
                }
            }
            if (sharpenTimes > 5) {
                return MonsterMove.attack(1, attack);
            }
            if (c.num < 50 && !c.lastMove(1)) {
                return MonsterMove.attack(1, attack);
            }
            return MonsterMove.buff(2, "Thorns", 2);
        }
    }

    /**
     * 观测者（{@code OrbWalker}，第三章）。两招交替，靠 num 与「连着两次」互斥：
     * <pre>
     * num &lt; 40 且最近不是连着两次 2 → 2 号招（15|16 伤，单段）
     * num &lt; 40 且刚连打两次 2     → 1 号招（10|11 伤 + 塞 1 张 Burn）
     * num ≥ 40 且不是连着两次 1   → 1 号招
     * num ≥ 40 且刚连打两次 1     → 2 号招
     * </pre>
     *
     * <p>⚠️ 原版字段名是反的：{@code damage[0]} 存的是 {@code laserDmg} 但给 1 号招用，
     * {@code damage[1]} 存的是 {@code clawDmg} 却给 2 号招用。照数值抄，别照名字抄。
     *
     * <p>1 号招原版是 {@code MakeTempCardInDiscardAndDeckAction}：同时往弃牌堆和**主牌组**
     * 各塞一张 Burn（主牌组那张是全流程污染，不在战斗层）。这里只建弃牌堆那张。
     */
    static final class OrbWalkerMachine implements Machine {
        @Override
        public MonsterMove next(Context c) {
            int withBurn = c.baseDamage(0, c.ascension >= 2 ? 11 : 10);
            int plain = c.baseDamage(1, c.ascension >= 2 ? 16 : 15);
            boolean low = c.num < 40;
            int heavy = low ? 2 : 1;
            if (!c.lastTwoMoves(heavy)) {
                return heavy == 2 ? MonsterMove.attack(2, plain)
                        : MonsterMove.attackStatus(1, withBurn, "Burn", 1);
            }
            return heavy == 2 ? MonsterMove.attackStatus(1, withBurn, "Burn", 1)
                    : MonsterMove.attack(2, plain);
        }
    }

    /**
     * 大嘴（{@code Maw}，第三章）。
     *
     * <pre>
     * 第 1 次决策（还没吼过）→ 2 Roar：玩家 Weak 3 + Frail 3（A17: 各 5）
     * 之后 num &lt; 50 且上一招不是 5 → 5 Nom：5 伤 × (turnCount / 2) 段
     * 否则上一招是 3 或 5      → 4 Bellow：自己 +力量 3（A17: 5）
     * 否则                      → 3 Slam：25 伤（A2: 30）
     * </pre>
     *
     * <p>{@code turnCount} 从 1 开始、每次 {@code getMove} 开头 +1，所以第 n 次决策时等于
     * {@code n + 1}；历史长度就是已出招次数，于是 {@code turnCount = history.size() + 2}。
     * Nom 的段数是 {@code turnCount / 2}（1、2、3…），越拖越痛。
     */
    static final class MawMachine implements Machine {
        @Override
        public MonsterMove next(Context c) {
            int terrify = c.ascension >= 17 ? 5 : 3;
            int strUp = c.ascension >= 17 ? 5 : 3;
            int slam = c.baseDamage(0, c.ascension >= 2 ? 30 : 25);
            int nom = c.baseDamage(1, 5);
            if (!c.history.contains(Integer.valueOf(2))) {
                return MonsterMove.doubleDebuff(2, "Weakened", terrify, "Frail", terrify);
            }
            int turnCount = c.history.size() + 2;
            if (c.num < 50 && !c.lastMove(5)) {
                return MonsterMove.multiAttack(5, nom, Math.max(1, turnCount / 2));
            }
            if (c.lastMove(3) || c.lastMove(5)) {
                return MonsterMove.buff(4, "Strength", strUp);
            }
            return MonsterMove.attack(3, slam);
        }
    }

    /**
     * 瞬息（{@code Transient}，第三章）。只有一招，但**每回合更痛、而且会自己消散**：
     * <pre>
     * getMove：setMove(1, ATTACK, startingDeathDmg + count * 10)   // 30|40 起
     * takeTurn：打一下 → count++ → 再 setMove(同上)
     * FadingPower：A17 起 6、否则 5，每回合开始 -1，减到 0 当场死
     * </pre>
     *
     * <p>{@code count} 就是已出招次数 = {@code history.size()}。
     * <b>Fading 不是这只怪的私有逻辑</b>，它由 {@code CombatFlow} 在怪物回合开始时统一递减
     * （见那里的注释）—— 不建的话 Transient 会永远打下去。
     */
    static final class TransientMachine implements Machine {
        @Override
        public MonsterMove next(Context c) {
            int base = c.baseDamage(0, c.ascension >= 2 ? 40 : 30);
            return MonsterMove.attack(1, base + c.history.size() * 10);
        }
    }

    /**
     * 排斥者（{@code Repulsor}，第三章）。
     *
     * <pre>
     * num &lt; 20 且上一招不是 2 → 2 打一下（11|13 伤）
     * 否则                     → 1 Daze：往**抽牌堆**塞 2 张 Dazed
     * </pre>
     *
     * <p>它和 Sentry 塞 Dazed 的区别是**进抽牌堆**（会马上摸到），不是弃牌堆。
     */
    static final class RepulsorMachine implements Machine {
        @Override
        public MonsterMove next(Context c) {
            if (c.num < 20 && !c.lastMove(2)) {
                return MonsterMove.attack(2, c.baseDamage(0, c.ascension >= 2 ? 13 : 11));
            }
            return MonsterMove.extra(1, MonsterMove.Extra.statusToDraw("Dazed", 2));
        }
    }

    /**
     * 面具强盗的熊（{@code BanditBear}，事件怪）。
     *
     * <p>{@code getMove} 只有一句 {@code setMove(2, STRONG_DEBUFF)} —— **它只在开局用一次**，
     * 之后的招全是 {@code takeTurn} 里直接 {@code SetMoveAction} 写死的（绕过 getMove）。
     * 所以和 Champ 一样：从 {@code moveHistory} 重放，不读 num。
     *
     * <pre>
     * 2 Bear Stun ：玩家敏捷 -2（A17: -4），不打人        → 下一招 3
     * 3 Bear Slash：9|10 伤 + 自己 9 格挡                → 下一招 1
     * 1 Maul      ：18|20 伤（单段）                     → 下一招 3
     * 循环 2 → 3 → 1 → 3 → 1 → …
     * </pre>
     *
     * <p>⚠️ 它的 {@code damage()} / {@code die()} 重写只有动画，没有机制。
     * 敏捷用**负增量**（原版 {@code con_reduction} 字段本身就是 -2 / -4）。
     */
    static final class BanditBearMachine implements Machine {
        @Override
        public MonsterMove next(Context c) {
            int lunge = c.baseDamage(1, c.ascension >= 2 ? 10 : 9);
            if (c.history.isEmpty()) {
                return MonsterMove.debuff(2, "Dexterity", c.ascension >= 17 ? -4 : -2);
            }
            if (c.lastMove(3)) {
                return MonsterMove.attack(1, c.baseDamage(0, c.ascension >= 2 ? 20 : 18));
            }
            return MonsterMove.attackBlock(3, lunge, 9);
        }
    }

    /**
     * 面具强盗的头目（{@code BanditLeader}，事件怪）。同样是「getMove 只给开局那一招」。
     *
     * <pre>
     * 2 喊话（不打人）                                    → 下一招 3
     * 3 Agonize：12|10 伤 + 玩家 Weak 2（A17: 3）         → 下一招 1
     * 1 Slash  ：17|15 伤                                → A17 起若「最近不是连着两下 1」就再刺一下，否则回 3
     * </pre>
     *
     * <p>所以 A17 以下的节奏是 2 → 3 → 1 → 3 → 1 → …；A17 起变成 3 → 1 → 1 → 3 → 1 → 1 → …
     * （判据是原版的 {@code lastTwoMoves((byte)1)}，正好是「不许连刺三下」）。
     * 熊死时它只会喊话（{@code deathReact} 只有 TalkAction），没有狂暴。
     */
    static final class BanditLeaderMachine implements Machine {
        @Override
        public MonsterMove next(Context c) {
            int agonize = c.baseDamage(1, c.ascension >= 2 ? 12 : 10);
            int slash = c.baseDamage(0, c.ascension >= 2 ? 17 : 15);
            int weak = c.ascension >= 17 ? 3 : 2;
            if (c.history.isEmpty()) {
                return MonsterMove.none(2);
            }
            if (c.lastMove(1) && c.ascension >= 17 && !c.lastTwoMoves(1)) {
                return MonsterMove.attack(1, slash);
            }
            if (c.lastMove(3)) {
                return MonsterMove.attack(1, slash);
            }
            return MonsterMove.attackDebuff(3, agonize, "Weakened", weak);
        }
    }

    /**
     * 重放 {@code Hexaghost.orbActiveCount}（原版只在三个 {@code changeState} 里改它）。
     *
     * @return **下一次** {@code getMove} 看到的 orbActiveCount
     */
    static int hexaghostOrbs(List<Integer> history) {
        int count = 0;
        for (Integer id : history) {
            switch (id.intValue()) {
                case 5:                               // Activate
                    count = 6;
                    break;
                case 1:                               // Divider → Deactivate
                case 6:                               // Inferno → Deactivate
                    count = 0;
                    break;
                case 2:                               // Tackle  ┐
                case 3:                               // Strengthen ├ Activate Orb
                case 4:                               // Sear      ┘
                    count++;
                    break;
                default:
                    break;
            }
        }
        return count;
    }

    // ==================================================================
    // 第二十九批：Act 4「盾与矛」（SpireShield / SpireSpear）
    //
    // 两只共用同一个 `moveCount % 3` 骨架：moveCount 初值 0、**每次 getMove 末尾 +1**，
    // 所以「下一次决策看到的 moveCount」= history.size()（第一次是 0）。
    // 但两只的**分支内容完全不同**，所以各写一个机器，只有这一步共用口径。
    //
    // 开场的 Artifact（A18: 2 / 否则 1）是 usePreBattleAction 挂的，快照里已有；
    // 玩家身上的 Surrounded 只是翻转立绘（javap -p 核实：没有 hook），不用建。
    // ==================================================================

    /**
     * 尖塔之盾（{@code SpireShield}，Act 4 精英双子之一）。HP 110（A8: 125）。
     *
     * <pre>
     * getMove:  moveCount % 3 == 0 → aiRng.randomBoolean() ? 2 FORTIFY : 1 BASH
     *           moveCount % 3 == 1 → !lastMove(1) ? 1 BASH : 2 FORTIFY
     *           moveCount % 3 == 2 → 3 SMASH
     * takeTurn: 1 BASH ：damage[0]（12|A3:14）
     *                    → 玩家 orbs 非空 &amp;&amp; randomBoolean() ? Focus -1 : **力量 -1**
     *                      （铁甲战士没有 orb → 恒走「力量 -1」，而且这条路径**不抽随机**）
     *           2 FORTIFY：**所有怪**（含自己）各 +30 格挡
     *           3 SMASH ：damage[1]（34|A3:38）→ A18 起自己 +99 格挡，
     *                      否则自己 + damage[1].output（= 算完力量/虚弱后的实际伤害）
     * </pre>
     *
     * <p>⚠️ SMASH 的格挡不是常量：{@code DamageInfo.output} 已经吃过了力量/虚弱/玩家易伤，
     * 所以用 {@link MonsterMove#attackBlockFromDamage}，不要写成 {@code attackBlock(…, 34)}。
     */
    static final class SpireShieldMachine implements Machine {
        @Override
        public MonsterMove next(Context c) {
            int bash = c.baseDamage(0, c.ascension >= 3 ? 14 : 12);
            int smash = c.baseDamage(1, c.ascension >= 3 ? 38 : 34);
            switch (c.history.size() % 3) {
                case 0:
                    // 原版这一支真的抽一次 aiRng，不管结果是哪边都要抽，所以先抽再分支。
                    return c.randomBoolean()
                            ? MonsterMove.blockAll(2, 30)
                            : MonsterMove.attackDebuff(1, bash, "Strength", -1);
                case 1:
                    return c.lastMove(1)
                            ? MonsterMove.blockAll(2, 30)
                            : MonsterMove.attackDebuff(1, bash, "Strength", -1);
                default:
                    return c.ascension >= 18
                            ? MonsterMove.attackBlock(3, smash, 99)
                            : MonsterMove.attackBlockFromDamage(3, smash);
            }
        }
    }

    /**
     * 尖塔之矛（{@code SpireSpear}，Act 4 精英双子之一）。HP 160（A8: 180）。
     *
     * <pre>
     * getMove:  moveCount % 3 == 0 → !lastMove(1) ? 1 SKEWER : 2 PREPARE
     *           moveCount % 3 == 1 → 3 BURN STRIKE
     *           moveCount % 3 == 2 → aiRng.randomBoolean() ? 2 PREPARE : 1 SKEWER
     * takeTurn: 1 SKEWER     ：2 段 × damage[0]（5|A3:6）
     *                          → A18 起 2 张 Burn 进**抽牌堆**，否则 2 张进弃牌堆
     *           2 PREPARE    ：**所有怪**（含自己）+2 力量
     *           3 BURN STRIKE：skewerCount 段 × damage[1]（10）  （A3 起 skewerCount 4、否则 3）
     * </pre>
     *
     * <p>⚠️ SKEWER 的段数是**字面量 2**（takeTurn 里 `iconst_2` 的循环），不是 skewerCount
     * ——{@code skewerCount} 只给 BURN STRIKE 用。
     */
    static final class SpireSpearMachine implements Machine {
        @Override
        public MonsterMove next(Context c) {
            int skewer = c.baseDamage(0, c.ascension >= 3 ? 6 : 5);
            int burnStrike = c.baseDamage(1, 10);
            int skewerCount = c.ascension >= 3 ? 4 : 3;
            switch (c.history.size() % 3) {
                case 0:
                    return c.lastMove(1)
                            ? MonsterMove.buffAll(2, "Strength", 2)
                            : skewer(c, skewer);
                case 1:
                    return MonsterMove.multiAttack(3, burnStrike, skewerCount);
                default:
                    return c.randomBoolean()
                            ? MonsterMove.buffAll(2, "Strength", 2)
                            : skewer(c, skewer);
            }
        }

        /** SKEWER：固定 2 段；A18 起改成往抽牌堆塞 2 张 Burn。 */
        private static MonsterMove skewer(Context c, int damage) {
            return c.ascension >= 18
                    ? MonsterMove.multiAttackStatusToDraw(1, damage, 2, "Burn", 2)
                    : MonsterMove.multiAttackStatus(1, damage, 2, "Burn", 2);
        }
    }

    /**
     * 腐化之心（{@code CorruptHeart}，Act 4 boss）。HP 750（A9: 800）。
     *
     * <p>🔴 它的 {@code moveCount} **不是** history.size()：第一招（Debuff）那一支
     * `return` 得早，**没有 +1**。所以「本次决策看到的 moveCount」= {@code history.size() - 1}。
     *
     * <pre>
     * isFirstMove            → 3 Debuff（并且 isFirstMove = false，moveCount 不动）
     * moveCount % 3 == 0     → aiRng.randomBoolean() ? 1 Blood Shots : 2 Echo
     * moveCount % 3 == 1     → !lastMove(2) ? 2 Echo : 1 Blood Shots
     * moveCount % 3 == 2     → 4 Buff
     *
     * 1 Blood Shots：bloodHitCount（12｜A4:15）段 × damage[1]（2）
     * 2 Echo       ：damage[0]（40｜A4:45）单段
     * 3 Debuff     ：玩家 Vulnerable 2 + Weak 2 + Frail 2（都**写死 2**，不随进阶变）
     *                + 往抽牌堆塞 Dazed / Slimed / Wound / Burn / Void 各 1 张
     * 4 Buff       ：先把自己身上的负力量补回来（{@code strGain = -min(0, Strength)}），
     *                然后 {@code Strength + (strGain + 2)}；再按 **buffCount** 追加一样：
     *                0 → Artifact 2｜1 → Beat of Death 1｜2 → Painful Stabs｜
     *                3 → Strength 10｜其余 → Strength 50；末尾 buffCount++
     * </pre>
     *
     * <p>{@code buffCount} = 历史里 4 的个数（只有 Buff 那一支会 +1）。
     *
     * <p>🔴 **没建模**：{@code InvinciblePower}（每回合最多掉 300｜A19:200 点，{@code atEndOfRound}
     * 恢复）。它需要一个「每回合伤害上限」容器，并且要从真机的私有字段 {@code maxAmt} 取上限；
     * 本期没做 —— 影响是「极端一回合爆发」会被高估，正常回合（铁甲战士 <300）无差别。
     */
    static final class CorruptHeartMachine implements Machine {
        @Override
        public MonsterMove next(Context c) {
            if (c.history.isEmpty()) {
                return MonsterMove.debuffThreeAndCards(3,
                        "Vulnerable", 2, "Weakened", 2, "Frail", 2,
                        "Dazed", "Slimed", "Wound", "Burn", "Void");
            }
            int moveCount = c.history.size() - 1;
            switch (moveCount % 3) {
                case 0:
                    return c.randomBoolean() ? bloodShots(c) : echo(c);
                case 1:
                    return c.lastMove(2) ? bloodShots(c) : echo(c);
                default:
                    return buff(c);
            }
        }

        /** Blood Shots：段数是 {@code bloodHitCount}（12｜A4:15），每段 damage[1] = 2。 */
        private static MonsterMove bloodShots(Context c) {
            int per = c.baseDamage(1, 2);
            return MonsterMove.multiAttack(1, per, c.ascension >= 4 ? 15 : 12);
        }

        /** Echo：damage[0] 单段（40｜A4:45）。 */
        private static MonsterMove echo(Context c) {
            return MonsterMove.attack(2, c.baseDamage(0, c.ascension >= 4 ? 45 : 40));
        }

        /** Buff：先补负力量、再 +2；随后按 buffCount 追加一样。 */
        private static MonsterMove buff(Context c) {
            int strGain = 0;
            Integer strength = c.monster == null ? null : c.monster.powers.get("Strength");
            if (strength != null && strength.intValue() < 0) {
                strGain = -strength.intValue();
            }
            int gain = strGain + 2;
            int buffCount = 0;
            for (Integer id : c.history) {
                if (id.intValue() == 4) {
                    buffCount++;
                }
            }
            switch (buffCount) {
                case 0:
                    return MonsterMove.buffTwo(4, "Strength", gain, "Artifact", 2);
                case 1:
                    return MonsterMove.buffTwo(4, "Strength", gain, "Beat of Death", 1);
                case 2:
                    return MonsterMove.buffTwo(4, "Strength", gain, "Painful Stabs", 1);
                case 3:
                    return MonsterMove.buffTwo(4, "Strength", gain, "Strength", 10);
                default:
                    return MonsterMove.buffTwo(4, "Strength", gain, "Strength", 50);
            }
        }
    }

    /**
     * 缠绕蔓（{@code WrithingMass}，第三章）。HP 160（A7: 175）。
     *
     * <p>它的 {@code getMove} 是一棵**递归树**（分支里会再调 {@code getMove(aiRng.random(...))}），
     * 所以这里也写成递归，随机数按原版区间逐个抽（口径见 {@link Context#rollAiRange}）。
     *
     * <pre>
     * firstMove（history 空）：
     *     num &lt; 33 → 1 三连击（3 × damage[1]）
     *     num &lt; 66 → 2 打+挡（damage[2] + 格挡 = damage[2].base）
     *     否则      → 3 打+虚弱 2+易伤 2（normalDebuffAmt，两档进阶都是 2）
     * 之后：
     *     num &lt; 10 → !lastMove(0) ? 0 重击（damage[0]）: getMove(random(10,99))
     *     num &lt; 20 → !usedMegaDebuff &amp;&amp; !lastMove(4) ? 4 植入 : (randomBoolean(0.1) ? 0 重击
     *                                                              : getMove(random(20,99)))
     *     num &lt; 40 → !lastMove(3) ? 3 打+减益 : (randomBoolean(0.4) ? getMove(random(19))
     *                                                              : getMove(random(40,99)))
     *     num &lt; 70 → !lastMove(1) ? 1 三连击 : (randomBoolean(0.3) ? 2 打+挡
     *                                                              : getMove(random(39)))
     *     否则      → !lastMove(2) ? 2 打+挡 : getMove(random(69))
     * </pre>
     *
     * <p>{@code usedMegaDebuff} 在 **takeTurn** 里置 true（不在 getMove），所以从历史里数 4 的个数。
     *
     * <p>4 号招「植入」原版是 {@code AddCardToDeckAction(new Parasite())} —— 污染的是**主牌组**
     * （局外/长线代价），战斗层这一回合什么都不发生。所以这里建的是空招（{@code none(4)}）。
     *
     * <p>「挨打改招」不在这里：那是 {@code ReactivePower}（ID {@code Compulsive}）的
     * {@code onAttacked}，落在 {@code DamageCalc.hit} 里（每挨一下就重掷一次）。
     */
    static final class WrithingMassMachine implements Machine {
        /** 递归深度上限（原版没写，纯防脏数据把栈打穿；正常最多 4 层）。 */
        private static final int MAX_DEPTH = 16;

        @Override
        public MonsterMove next(Context c) {
            return decide(c, c.num, c.history.isEmpty(), 0);
        }

        private static MonsterMove decide(Context c, int num, boolean firstMove, int depth) {
            int bigHit = c.baseDamage(0, c.ascension >= 2 ? 38 : 32);
            int multiPer = c.baseDamage(1, c.ascension >= 2 ? 9 : 7);
            int attackBlock = c.baseDamage(2, c.ascension >= 2 ? 16 : 15);
            int attackDebuff = c.baseDamage(3, c.ascension >= 2 ? 12 : 10);
            if (firstMove) {
                if (num < 33) {
                    return MonsterMove.multiAttack(1, multiPer, 3);
                }
                if (num < 66) {
                    return MonsterMove.attackBlock(2, attackBlock, attackBlock);
                }
                return MonsterMove.attackDoubleDebuff(3, attackDebuff,
                        "Weakened", 2, "Vulnerable", 2);
            }
            if (depth >= MAX_DEPTH) {
                return MonsterMove.attack(0, bigHit);     // 兜底：脏数据下不递归到底
            }
            if (num < 10) {
                if (!c.lastMove(0)) {
                    return MonsterMove.attack(0, bigHit);
                }
                return decide(c, c.rollAiRange(10, 99), false, depth + 1);
            }
            if (num < 20) {
                boolean megaUsed = c.history.contains(Integer.valueOf(4));
                if (!megaUsed && !c.lastMove(4)) {
                    return MonsterMove.none(4);
                }
                if (c.randomBoolean(0.1f)) {
                    return MonsterMove.attack(0, bigHit);
                }
                return decide(c, c.rollAiRange(20, 99), false, depth + 1);
            }
            if (num < 40) {
                if (!c.lastMove(3)) {
                    return MonsterMove.attackDoubleDebuff(3, attackDebuff,
                            "Weakened", 2, "Vulnerable", 2);
                }
                if (c.randomBoolean(0.4f)) {
                    return decide(c, c.rollAiRange(0, 19), false, depth + 1);
                }
                return decide(c, c.rollAiRange(40, 99), false, depth + 1);
            }
            if (num < 70) {
                if (!c.lastMove(1)) {
                    return MonsterMove.multiAttack(1, multiPer, 3);
                }
                if (c.randomBoolean(0.3f)) {
                    return MonsterMove.attackBlock(2, attackBlock, attackBlock);
                }
                return decide(c, c.rollAiRange(0, 39), false, depth + 1);
            }
            if (!c.lastMove(2)) {
                return MonsterMove.attackBlock(2, attackBlock, attackBlock);
            }
            return decide(c, c.rollAiRange(0, 69), false, depth + 1);
        }
    }
}
