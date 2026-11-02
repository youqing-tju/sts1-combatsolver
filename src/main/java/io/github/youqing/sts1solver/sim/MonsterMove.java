package io.github.youqing.sts1solver.sim;

/**
 * 怪物的一次出招（影子层）。
 *
 * <p>对应蓝本 {@code Engine/InCombat/Mirrors} 那一层「把怪物 AI 也镜像出来」的思路：
 * 游戏里怪物每回合由 {@code getMove()} 决定 {@code nextMove}，{@code takeTurn()} 再照着它
 * 产生伤害 / 格挡 / 减益。我们原来只学「意图伤害」一个数（{@code IntentLearning}），
 * 现在逐个怪物翻译 {@code getMove} + {@code takeTurn}，把这几样都建模出来。
 *
 * <p>{@link #id} 就是原版 {@code nextMove} 的字节值 —— 机器要靠它判断「连续两次同一招」。
 *
 * <p>一招能干的六类事，都对应原版一个 Action：
 * <ul>
 *   <li>{@link #damage} × {@link #hits} → {@code DamageAction}
 *   <li>{@link #block} → {@code GainBlockAction(owner, owner, amt)}
 *   <li>{@link #blockRandomAlly} → {@code GainBlockRandomMonsterAction}
 *   <li>{@link #selfBuff} → {@code ApplyPowerAction(owner, owner, power)}
 *   <li>{@link #playerDebuff} → {@code ApplyPowerAction(player, owner, power)}
 *   <li>{@link #statusCard} → {@code MakeTempCardInDiscardAction}
 * </ul>
 */
public final class MonsterMove {

    /** 原版 {@code nextMove} 的字节值（调试与「连续同招」判定用）。 */
    public final int id;
    /** 单段基础伤害（0 = 不攻击）。 */
    public final int damage;
    /** 段数（多段 = 每段独立结算，格挡/荆棘都按段算）。 */
    public final int hits;
    /** 自己获得多少格挡。 */
    public final int block;
    /** 给**随机队友**（不含自己）多少格挡；0 = 不给。原版 {@code GainBlockRandomMonsterAction}。 */
    public final int blockRandomAlly;
    /** 给自己挂的能力（例如 Cultist 的 INCANTATION → Ritual）；{@code null} = 不挂。 */
    public final String selfBuff;
    public final int selfBuffAmount;
    /** 施加给玩家的减益 ID（{@code null} = 不施加）。 */
    public final String playerDebuff;
    public final int playerDebuffAmount;
    /** 第二个玩家减益（Lagavulin 的 Siphon Soul 同时扣力量与敏捷）；{@code null} = 没有。 */
    public final String playerDebuff2;
    public final int playerDebuff2Amount;
    /** 往玩家弃牌堆塞的状态牌 ID（Sentry 的 Dazed）；{@code null} = 不塞。 */
    public final String statusCard;
    public final int statusCount;
    /**
     * 分裂：生成几只什么怪（大史莱姆的 SPLIT）；{@code null} = 不分裂。
     *
     * <p>分裂完原怪自己消失（{@code SuicideAction}）—— 这条由 {@code CombatFlow} 处理，
     * 不是「伤害」也不是「死亡」，所以不算击杀、不结算掉落。
     */
    public final String splitInto;
    public final int splitCount;
    /** 第二个分裂槽（SlimeBoss 一次生成两只**不同**的怪：先刺史莱姆、再酸液史莱姆）。 */
    public final String splitInto2;
    public final int splitCount2;
    /** 这一招是**逃离战斗**（原版 {@code EscapeAction}）—— 怪直接从场上消失，不算击杀。 */
    public final boolean escape;
    /**
     * 这一招是**吸血**（原版 {@code VampireDamageAction}）—— 打掉玩家多少血，自己回多少血。
     *
     * <p>ShelledParasite 的 Fell 就是它（意图显示 ATTACK_BUFF，那个「buff」其实是回血）。
     */
    public final boolean vampire;
    /** 给**所有活着的队友**（含自己）回这么多血（Healer 的 Heal，原版逐只 HealAction）。 */
    public final int healAllies;
    /** 给所有活着的队友挂的能力（Healer 的 Buff，原版逐只 ApplyPowerAction(Strength)）。 */
    public final String buffAlliesPower;
    public final int buffAlliesAmount;

    /**
     * 扩展载荷（召唤 / 给指定类型的队友加格挡）。
     *
     * <p>为什么不直接往构造器上继续加参数：这个类已经有 17 个字段、7 层重载，
     * 每加一个机制就多一层会让「哪一版构造器是权威」变得没法读。新机制收进
     * {@link Extra} 后，构造器参数就定死在这里了。
     */
    public final Extra extra;

    public MonsterMove(int id, int damage, int hits, int block, int blockRandomAlly,
                       String selfBuff, int selfBuffAmount,
                       String playerDebuff, int playerDebuffAmount,
                       String statusCard, int statusCount) {
        this(id, damage, hits, block, blockRandomAlly, selfBuff, selfBuffAmount,
                playerDebuff, playerDebuffAmount, null, 0, statusCard, statusCount);
    }

    public MonsterMove(int id, int damage, int hits, int block, int blockRandomAlly,
                       String selfBuff, int selfBuffAmount,
                       String playerDebuff, int playerDebuffAmount,
                       String playerDebuff2, int playerDebuff2Amount,
                       String statusCard, int statusCount) {
        this(id, damage, hits, block, blockRandomAlly, selfBuff, selfBuffAmount,
                playerDebuff, playerDebuffAmount, playerDebuff2, playerDebuff2Amount,
                statusCard, statusCount, null, 0);
    }

    public MonsterMove(int id, int damage, int hits, int block, int blockRandomAlly,
                       String selfBuff, int selfBuffAmount,
                       String playerDebuff, int playerDebuffAmount,
                       String playerDebuff2, int playerDebuff2Amount,
                       String statusCard, int statusCount,
                       String splitInto, int splitCount) {
        this(id, damage, hits, block, blockRandomAlly, selfBuff, selfBuffAmount,
                playerDebuff, playerDebuffAmount, playerDebuff2, playerDebuff2Amount,
                statusCard, statusCount, splitInto, splitCount, null, 0);
    }

    public MonsterMove(int id, int damage, int hits, int block, int blockRandomAlly,
                       String selfBuff, int selfBuffAmount,
                       String playerDebuff, int playerDebuffAmount,
                       String playerDebuff2, int playerDebuff2Amount,
                       String statusCard, int statusCount,
                       String splitInto, int splitCount,
                       String splitInto2, int splitCount2) {
        this(id, damage, hits, block, blockRandomAlly, selfBuff, selfBuffAmount,
                playerDebuff, playerDebuffAmount, playerDebuff2, playerDebuff2Amount,
                statusCard, statusCount, splitInto, splitCount, splitInto2, splitCount2, false);
    }

    public MonsterMove(int id, int damage, int hits, int block, int blockRandomAlly,
                       String selfBuff, int selfBuffAmount,
                       String playerDebuff, int playerDebuffAmount,
                       String playerDebuff2, int playerDebuff2Amount,
                       String statusCard, int statusCount,
                       String splitInto, int splitCount,
                       String splitInto2, int splitCount2, boolean escape) {
        this(id, damage, hits, block, blockRandomAlly, selfBuff, selfBuffAmount,
                playerDebuff, playerDebuffAmount, playerDebuff2, playerDebuff2Amount,
                statusCard, statusCount, splitInto, splitCount, splitInto2, splitCount2,
                escape, false);
    }

    public MonsterMove(int id, int damage, int hits, int block, int blockRandomAlly,
                       String selfBuff, int selfBuffAmount,
                       String playerDebuff, int playerDebuffAmount,
                       String playerDebuff2, int playerDebuff2Amount,
                       String statusCard, int statusCount,
                       String splitInto, int splitCount,
                       String splitInto2, int splitCount2, boolean escape, boolean vampire) {
        this(id, damage, hits, block, blockRandomAlly, selfBuff, selfBuffAmount,
                playerDebuff, playerDebuffAmount, playerDebuff2, playerDebuff2Amount,
                statusCard, statusCount, splitInto, splitCount, splitInto2, splitCount2,
                escape, vampire, 0, null, 0);
    }

    public MonsterMove(int id, int damage, int hits, int block, int blockRandomAlly,
                       String selfBuff, int selfBuffAmount,
                       String playerDebuff, int playerDebuffAmount,
                       String playerDebuff2, int playerDebuff2Amount,
                       String statusCard, int statusCount,
                       String splitInto, int splitCount,
                       String splitInto2, int splitCount2, boolean escape, boolean vampire,
                       int healAllies, String buffAlliesPower, int buffAlliesAmount) {
        this(id, damage, hits, block, blockRandomAlly, selfBuff, selfBuffAmount,
                playerDebuff, playerDebuffAmount, playerDebuff2, playerDebuff2Amount,
                statusCard, statusCount, splitInto, splitCount, splitInto2, splitCount2,
                escape, vampire, healAllies, buffAlliesPower, buffAlliesAmount, null);
    }

    /** 权威构造器：所有字段都在这儿落地，其它重载一律转发到这里。 */
    public MonsterMove(int id, int damage, int hits, int block, int blockRandomAlly,
                       String selfBuff, int selfBuffAmount,
                       String playerDebuff, int playerDebuffAmount,
                       String playerDebuff2, int playerDebuff2Amount,
                       String statusCard, int statusCount,
                       String splitInto, int splitCount,
                       String splitInto2, int splitCount2, boolean escape, boolean vampire,
                       int healAllies, String buffAlliesPower, int buffAlliesAmount,
                       Extra extra) {
        this.id = id;
        this.damage = Math.max(0, damage);
        this.hits = Math.max(0, hits);
        this.block = Math.max(0, block);
        this.blockRandomAlly = Math.max(0, blockRandomAlly);
        this.selfBuff = selfBuff;
        this.selfBuffAmount = Math.max(0, selfBuffAmount);
        this.playerDebuff = playerDebuff;
        // 🔴 玩家减益**允许负数**：原版 ApplyPowerAction 的 amt 是**带符号增量**
        //    （Lagavulin 的 Siphon Soul、BanditBear 的 Bear Stun 都是 new DexterityPower(player, -1)）。
        //    这里原来用 Math.max(0, ...) 夹住，负增量会被静默改成 0 —— 减益根本没落地，
        //    而且因为「编译器不报错、测试只看字段名」很难发现。别再加回 clamp。
        this.playerDebuffAmount = playerDebuffAmount;
        this.playerDebuff2 = playerDebuff2;
        this.playerDebuff2Amount = playerDebuff2Amount;
        this.statusCard = statusCard;
        this.statusCount = Math.max(0, statusCount);
        this.splitInto = splitInto;
        this.splitCount = Math.max(0, splitCount);
        this.splitInto2 = splitInto2;
        this.splitCount2 = Math.max(0, splitCount2);
        this.escape = escape;
        this.vampire = vampire;
        this.healAllies = Math.max(0, healAllies);
        this.buffAlliesPower = buffAlliesPower;
        this.buffAlliesAmount = Math.max(0, buffAlliesAmount);
        this.extra = extra;
    }

    public boolean attacks() {
        return damage > 0 && hits > 0;
    }

    public static MonsterMove attack(int id, int damage) {
        return new MonsterMove(id, damage, 1, 0, 0, null, 0, null, 0, null, 0);
    }

    public static MonsterMove multiAttack(int id, int damage, int hits) {
        return new MonsterMove(id, damage, hits, 0, 0, null, 0, null, 0, null, 0);
    }

    /** 什么也不做（GremlinWizard 的 Charging：只是把蓄力计数 +1）。 */
    public static MonsterMove none(int id) {
        return new MonsterMove(id, 0, 0, 0, 0, null, 0, null, 0, null, 0);
    }

    public static MonsterMove block(int id, int block) {
        return new MonsterMove(id, 0, 0, block, 0, null, 0, null, 0, null, 0);
    }

    /** 给随机队友加格挡（GremlinTsundere 的 DEFEND）。 */
    public static MonsterMove blockAlly(int id, int blockRandomAlly) {
        return new MonsterMove(id, 0, 0, 0, blockRandomAlly, null, 0, null, 0, null, 0);
    }

    public static MonsterMove attackBlock(int id, int damage, int block) {
        return new MonsterMove(id, damage, 1, block, 0, null, 0, null, 0, null, 0);
    }

    public static MonsterMove attackDebuff(int id, int damage, String debuff, int amount) {
        return new MonsterMove(id, damage, 1, 0, 0, null, 0, debuff, amount, null, 0);
    }

    /**
     * 只给玩家上减益、自己不打也不挡（BanditBear 的 Bear Stun）。
     *
     * <p>⚠️ 减益量按原版 {@code Power} 的**带符号增量**写：扣敏捷是 {@code "Dexterity", -2}，
     * 扣力量是 {@code "Strength", -2}。影子把 {@code playerPowers} 当增量表用，
     * 写正数会变成「加敏捷」，格挡公式 {@code base + Dexterity} 会算反。
     */
    public static MonsterMove debuff(int id, String power, int amount) {
        return new MonsterMove(id, 0, 0, 0, 0, null, 0, power, amount, null, 0, null, 0);
    }

    /** 给自己挂能力（Cultist 的 INCANTATION / 各种 BUFF 招）。 */
    public static MonsterMove buff(int id, String power, int amount) {
        return new MonsterMove(id, 0, 0, 0, 0, power, amount, null, 0, null, 0);
    }

    /** 攻击 + 塞状态牌 + 自己挂能力（Taskmaster：7 伤 + 2 张 Wound + A18 起自己 +1 力量）。 */
    public static MonsterMove attackStatusBuff(int id, int damage, String card, int count,
                                               String buff, int buffAmount) {
        return new MonsterMove(id, damage, 1, 0, 0, buff, buffAmount, null, 0, null, 0,
                card, count);
    }

    /** 攻击 + 两个玩家减益（Snecko 的 Tail Whip：伤害 + Vulnerable 2 + Weak 2）。 */
    public static MonsterMove attackDoubleDebuff(int id, int damage,
                                                 String first, int firstAmount,
                                                 String second, int secondAmount) {
        return new MonsterMove(id, damage, 1, 0, 0, null, 0, first, firstAmount,
                second, secondAmount, null, 0);
    }

    /** 给玩家上减益 + 自己挂能力（Chosen 的「削志」：玩家 Weak 3、自己 +3 力量）。 */
    public static MonsterMove debuffAndBuff(int id, String playerDebuff, int playerDebuffAmount,
                                            String selfBuff, int selfBuffAmount) {
        return new MonsterMove(id, 0, 0, 0, 0, selfBuff, selfBuffAmount,
                playerDebuff, playerDebuffAmount, null, 0, null, 0);
    }

    /** 一次扣两项（Lagavulin 的 Siphon Soul：力量和敏捷各 -N）。 */
    public static MonsterMove doubleDebuff(int id, String first, int firstAmount,
                                           String second, int secondAmount) {
        return new MonsterMove(id, 0, 0, 0, 0, null, 0, first, firstAmount,
                second, secondAmount, null, 0);
    }

    /** 给自己挂能力 + 自己加格挡（JawWorm 的 Bellow）。 */
    public static MonsterMove buffBlock(int id, String power, int amount, int block) {
        return new MonsterMove(id, 0, 0, block, 0, power, amount, null, 0, null, 0);
    }

    /** 攻击 + 往玩家弃牌堆塞状态牌（SpikeSlime_M 的 Tackle → Slimed）。 */
    public static MonsterMove attackStatus(int id, int damage, String card, int count) {
        return new MonsterMove(id, damage, 1, 0, 0, null, 0, null, 0, card, count);
    }

    /** 分裂成 {@code count} 只 {@code childType}（大史莱姆的 SPLIT）。 */
    public static MonsterMove split(int id, String childType, int count) {
        return new MonsterMove(id, 0, 0, 0, 0, null, 0, null, 0,
                null, 0, null, 0, childType, count);
    }

    /** 给所有活着的队友回血（Healer 的 Heal）。 */
    public static MonsterMove healAll(int id, int amount) {
        return new MonsterMove(id, 0, 0, 0, 0, null, 0, null, 0, null, 0, null, 0,
                null, 0, null, 0, false, false, amount, null, 0);
    }

    /** 给所有活着的队友挂能力（Healer 的 Buff：全队 +N 力量）。 */
    public static MonsterMove buffAll(int id, String power, int amount) {
        return new MonsterMove(id, 0, 0, 0, 0, null, 0, null, 0, null, 0, null, 0,
                null, 0, null, 0, false, false, 0, power, amount);
    }

    /** 吸血一击（原版 {@code VampireDamageAction}）：打掉玩家多少血就回多少。 */
    public static MonsterMove vampireAttack(int id, int damage) {
        return new MonsterMove(id, damage, 1, 0, 0, null, 0, null, 0, null, 0, null, 0,
                null, 0, null, 0, false, true);
    }

    /** 逃离战斗（原版 {@code EscapeAction}）：怪从场上消失，不算击杀。 */
    public static MonsterMove escape(int id) {
        return new MonsterMove(id, 0, 0, 0, 0, null, 0, null, 0, null, 0, null, 0,
                null, 0, null, 0, true);
    }

    /** 分裂成两只**不同**的怪（SlimeBoss：1 只刺史莱姆 + 1 只酸液史莱姆）。 */
    public static MonsterMove splitTwo(int id, String firstType, int firstCount,
                                       String secondType, int secondCount) {
        return new MonsterMove(id, 0, 0, 0, 0, null, 0, null, 0, null, 0, null, 0,
                firstType, firstCount, secondType, secondCount);
    }

    /** 往玩家弃牌堆塞状态牌（Sentry 的 Dazed）。 */
    public static MonsterMove status(int id, String card, int count) {
        return new MonsterMove(id, 0, 0, 0, 0, null, 0, null, 0, card, count);
    }

    /** 打一下然后自己死（Exploder 的爆炸：30 点，THORNS 类型 → 吃格挡）。 */
    public static MonsterMove explode(int id, int damage) {
        return new MonsterMove(id, damage, 1, 0, 0, null, 0, null, 0, null, 0, null, 0,
                null, 0, null, 0, false, false, 0, null, 0, Extra.selfDestruct());
    }

    /** 多段攻击 + 玩家减益（Reptomancer 的 Snake Strike：两段 + Weak 1）。 */
    public static MonsterMove multiAttackDebuff(int id, int damage, int hits,
                                               String debuff, int amount) {
        return new MonsterMove(id, damage, hits, 0, 0, null, 0, debuff, amount, null, 0);
    }

    /** 多段攻击 + 塞状态牌（Deca 的 Beam：2 段 + 2 张 Dazed）。 */
    public static MonsterMove multiAttackStatus(int id, int damage, int hits,
                                                String card, int count) {
        return new MonsterMove(id, damage, hits, 0, 0, null, 0, null, 0, null, 0, card, count);
    }

    /** 自己**和**所有活着的队友都加格挡（Deca 的 Square of Protection）。 */
    public static MonsterMove blockAll(int id, int block) {
        return new MonsterMove(id, 0, 0, block, 0, null, 0, null, 0, null, 0, null, 0,
                null, 0, null, 0, false, false, 0, null, 0, Extra.blockAllies(block));
    }

    /** 全体加格挡 + 全体挂能力（Deca 的 A19 版：16 格挡 + 3 层金属化护甲）。 */
    public static MonsterMove blockAllAndBuffAll(int id, int block, String power, int amount) {
        return new MonsterMove(id, 0, 0, block, 0, null, 0, null, 0, null, 0, null, 0,
                null, 0, null, 0, false, false, 0, power, amount, Extra.blockAllies(block));
    }

    /**
     * 攻击 + 自己获得**等于这次实际打出伤害**的格挡（SpireShield 的 SMASH）。
     *
     * <p>原版写的是 {@code GainBlockAction(this, this, damage.get(1).output)} ——
     * {@code output} 是「算完力量/虚弱之后」的数，不是卡面 {@code base}，所以不能用
     * {@link #attackBlock} 传一个常量。
     */
    public static MonsterMove attackBlockFromDamage(int id, int damage) {
        return new MonsterMove(id, damage, 1, 0, 0, null, 0, null, 0, null, 0, null, 0,
                null, 0, null, 0, false, false, 0, null, 0, Extra.blockFromDamage());
    }

    /** 单段攻击 + 往**抽牌堆**塞状态牌（AwakenedOne 的 SLUDGE：18 伤 + 1 张 Void）。 */
    public static MonsterMove attackStatusToDraw(int id, int damage, String card, int count) {
        return multiAttackStatusToDraw(id, damage, 1, card, count);
    }

    /** 多段攻击 + 往**抽牌堆**塞状态牌（SpireSpear 的 A18 版 Skewer：2 段 + 2 张 Burn）。 */
    public static MonsterMove multiAttackStatusToDraw(int id, int damage, int hits,
                                                      String card, int count) {
        return new MonsterMove(id, damage, hits, 0, 0, null, 0, null, 0, null, 0, null, 0,
                null, 0, null, 0, false, false, 0, null, 0, Extra.statusToDraw(card, count));
    }

    /**
     * 三个玩家减益 + 往抽牌堆逐个塞状态牌（CorruptHeart 的 Debuff：
     * Vulnerable/Weak/Frail 各 2 + Dazed/Slimed/Wound/Burn/Void 各 1）。
     */
    public static MonsterMove debuffThreeAndCards(int id,
                                                  String first, int firstAmount,
                                                  String second, int secondAmount,
                                                  String third, int thirdAmount,
                                                  String... cards) {
        return new MonsterMove(id, 0, 0, 0, 0, null, 0, first, firstAmount,
                second, secondAmount, null, 0, null, 0, null, 0, false, false, 0, null, 0,
                Extra.thirdDebuffAndCards(third, thirdAmount, cards));
    }

    /** 一次挂两个自身能力（CorruptHeart 的 Buff：Strength +2 之外再按 buffCount 给一样）。 */
    public static MonsterMove buffTwo(int id, String power, int amount,
                                      String second, int secondAmount) {
        return new MonsterMove(id, 0, 0, 0, 0, power, amount, null, 0, null, 0, null, 0,
                null, 0, null, 0, false, false, 0, null, 0,
                Extra.secondSelfBuff(second, secondAmount));
    }

    /**
     * 加格挡 + 最多三个玩家减益（TimeEater 的 Ripple：20 格挡 + Vulnerable/Weak，
     * A19 起再加 Frail）。{@code third} 传 {@code null} 表示没有第三样。
     */
    public static MonsterMove blockAndDebuffs(int id, int block,
                                              String first, int firstAmount,
                                              String second, int secondAmount,
                                              String third, int thirdAmount) {
        return new MonsterMove(id, 0, 0, block, 0, null, 0, first, firstAmount,
                second, secondAmount, null, 0, null, 0, null, 0, false, false, 0, null, 0,
                third == null ? null
                        : Extra.thirdPlayerDebuff(third, thirdAmount));
    }

    /** 攻击 + 塞状态牌 + 一个玩家减益（TimeEater 的 Head Slam：伤害 + 2 Slimed + 抽牌 -1）。 */
    public static MonsterMove attackStatusDebuff(int id, int damage, String card, int count,
                                                 String debuff, int amount) {
        return new MonsterMove(id, damage, 1, 0, 0, null, 0, debuff, amount, null, 0,
                card, count);
    }

    /** 给自己回血 + 清自身减益（+ 可选格挡）：TimeEater 的 Haste。 */
    public static MonsterMove healSelfAndCleanse(int id, int heal, int block) {
        return new MonsterMove(id, 0, 0, block, 0, null, 0, null, 0, null, 0, null, 0,
                null, 0, null, 0, false, false, heal, null, 0, Extra.cleanseSelfOnly());
    }

    /** 本体什么都不做、只有 {@link Extra} 载荷（Repulsor 的 Daze：往抽牌堆塞牌）。 */
    public static MonsterMove extra(int id, Extra extra) {
        return new MonsterMove(id, 0, 0, 0, 0, null, 0, null, 0, null, 0, null, 0,
                null, 0, null, 0, false, false, 0, null, 0, extra);
    }

    /** 自己加格挡 + 给所有活着的怪（含自己）挂能力（TheCollector 的 Buff：格挡 + 全队力量）。 */
    public static MonsterMove blockAndBuffAll(int id, int block, String power, int amount) {
        return new MonsterMove(id, 0, 0, block, 0, null, 0, null, 0, null, 0, null, 0,
                null, 0, null, 0, false, false, 0, power, amount);
    }

    /** 召唤（GremlinLeader 的 Rally / TheCollector 的 Spawn / BronzeAutomaton 的 BOOST）。 */
    public static MonsterMove summon(int id, Summon summon) {
        return new MonsterMove(id, 0, 0, 0, 0, null, 0, null, 0, null, 0, null, 0,
                null, 0, null, 0, false, false, 0, null, 0,
                Extra.summon(summon));
    }

    /** 给指定类型的队友加格挡（BronzeOrb 的 2 号招：给 BronzeAutomaton +12）。 */
    public static MonsterMove blockTypedAlly(int id, String type, int block) {
        return new MonsterMove(id, 0, 0, 0, 0, null, 0, null, 0, null, 0, null, 0,
                null, 0, null, 0, false, false, 0, null, 0,
                Extra.blockTypedAlly(type, block));
    }

    /**
     * 召唤池里的一项：类型 + 血量区间。
     *
     * <p>血量区间照抄构造器里的 {@code setHp(min,max)} —— 它内部是
     * {@code AbstractDungeon.monsterHpRng.random(min,max)}，所以只要
     * {@link SimState.RngKind#MONSTER_HP} 捕获到了，召唤出来的血是**精确值**而不是中值。
     */
    public static final class Spawn {
        public final String type;
        public final int hpMin;
        public final int hpMax;

        public Spawn(String type, int hpMin, int hpMax) {
            this.type = type;
            this.hpMin = hpMin;
            this.hpMax = hpMax;
        }

        @Override
        public String toString() {
            return type + "(" + hpMin + "-" + hpMax + ")";
        }
    }

    /**
     * 召唤载荷。
     *
     * <p>原版三种召唤写法各异，但落到「能观察到的行为」是同一件事：
     * 找一个**空槽**（新建的或已被打死的），造一只新怪放进去，最多填到某个上限。
     * <ul>
     *   <li>GremlinLeader 的 {@code SummonGremlinAction}：{@code identifySlot} 找 null/isDying 槽，
     *       新怪由 {@code getRandomGremlin} 从 8 项池里 {@code aiRng.random(0,7)} 抽；</li>
     *   <li>TheCollector 的 1/5 号招：{@code enemySlots} 两个槽，1 号招造两只，5 号招把死掉的逐个补；</li>
     *   <li>BronzeAutomaton 的 4 号招：直接 {@code SpawnMonsterAction} 两只 BronzeOrb。</li>
     * </ul>
     * 所以统一成「尝试 {@link #count} 次、该池活着的不超过 {@link #cap} 只」。
     */
    public static final class Summon {
        public final Spawn[] pool;
        public final int count;
        public final int cap;
        /** 召唤物是否挂 {@code MinionPower}（小鬼有，TorchHead/BronzeOrb 没有）。 */
        public final boolean minion;

        public Summon(Spawn[] pool, int count, int cap, boolean minion) {
            this.pool = pool == null ? new Spawn[0] : pool;
            this.count = Math.max(0, count);
            this.cap = cap <= 0 ? this.count : cap;
            this.minion = minion;
        }

        /** 固定类型召唤。 */
        public static Summon of(String type, int hpMin, int hpMax, int count, boolean minion) {
            return new Summon(new Spawn[]{new Spawn(type, hpMin, hpMax)}, count, count, minion);
        }
    }

    /** 招式附带的额外效果（召唤 / 队友格挡 / 第三个玩家减益 / 清自身减益）。 */
    public static final class Extra {
        public final Summon summon;
        /** 给**指定类型**的队友加格挡；{@code null} = 没有。 */
        public final String blockAllyType;
        public final int blockAllyTypeAmount;
        /** 给自己**以外**所有活着的怪加格挡（GremlinLeader 的喊话）；0 = 没有。 */
        public final int blockAllies;
        /**
         * 第三个玩家减益。{@link MonsterMove} 主字段只有两个减益槽（Lagavulin 的
         * Siphon Soul 用满），而 TheCollector 的大招一次上 **Weak + Vulnerable + Frail** 三样。
         */
        public final String playerDebuff3;
        public final int playerDebuff3Amount;
        /** 清掉自己身上的所有减益（Champ 半血的 Anger，原版 {@code RemoveDebuffsAction}）。 */
        public final boolean cleanseSelf;
        /**
         * 往**抽牌堆**塞的状态牌（Repulsor 的 Dazed）。
         *
         * <p>和主字段 {@link #statusCard}（进**弃牌堆**）不是一回事：抽牌堆里的牌这一场
         * 更容易被摸到，所以单独建。
         */
        public final String statusDrawCard;
        public final int statusDrawCount;
        /**
         * 结算完之后**自己死掉**（Exploder 的 {@code ExplosivePower.duringTurn}：
         * 先 {@code SuicideAction} 再打 30 点 —— 顺序上「先死再炸」，但伤害照样落地，
         * 所以影子里等价于「打完就死」）。
         */
        public final boolean selfDestruct;
        /**
         * 「自己没有这个能力时才挂上」（Nemesis 每回合都在 takeTurn 末尾补一层无实体）。
         *
         * <p>和主字段 {@code selfBuff} 的区别：那个是无脑叠加，这个是**缺失才补**。
         */
        public final String absentBuff;
        public final int absentBuffAmount;
        /**
         * 复活（Darkling 的 5 号招 Reincarnate）：把血量拉回 {@code maxHp / 2} 并清掉半死标记。
         * 原版是 {@code HealAction(this, this, maxHealth / 2)}，只治自己、不治队友。
         */
        public final boolean reincarnate;
        /**
         * 自己获得**等于本招实际打出伤害**的格挡（SpireShield 的 SMASH：
         * {@code GainBlockAction(this, this, damage.get(1).output)}）。
         */
        public final boolean blockFromDamage;
        /**
         * 往**抽牌堆**逐个塞这些状态牌（每项 1 张）。CorruptHeart 的 Debuff 一次塞
         * {@code Dazed/Slimed/Wound/Burn/Void} 各 1 张，{@link #statusDrawCard} 只装得下一张。
         */
        public final String[] drawPileCards;
        /**
         * 第二个自身能力。CorruptHeart 的 Buff 一次挂两样
         * （{@code Strength +2} 恒定，另外按 buffCount 再给一个），主字段装不下。
         */
        public final String extraSelfBuff;
        public final int extraSelfBuffAmount;

        private Extra(Summon summon, String blockAllyType, int blockAllyTypeAmount,
                      int blockAllies, String playerDebuff3, int playerDebuff3Amount,
                      boolean cleanseSelf, String statusDrawCard, int statusDrawCount,
                      boolean selfDestruct, String absentBuff, int absentBuffAmount,
                      boolean reincarnate, boolean blockFromDamage) {
            this(summon, blockAllyType, blockAllyTypeAmount, blockAllies, playerDebuff3,
                    playerDebuff3Amount, cleanseSelf, statusDrawCard, statusDrawCount,
                    selfDestruct, absentBuff, absentBuffAmount, reincarnate, blockFromDamage,
                    null, null, 0);
        }

        private Extra(Summon summon, String blockAllyType, int blockAllyTypeAmount,
                      int blockAllies, String playerDebuff3, int playerDebuff3Amount,
                      boolean cleanseSelf, String statusDrawCard, int statusDrawCount,
                      boolean selfDestruct, String absentBuff, int absentBuffAmount,
                      boolean reincarnate, boolean blockFromDamage,
                      String[] drawPileCards, String extraSelfBuff, int extraSelfBuffAmount) {
            this.summon = summon;
            this.blockAllyType = blockAllyType;
            this.blockAllyTypeAmount = Math.max(0, blockAllyTypeAmount);
            this.blockAllies = Math.max(0, blockAllies);
            this.playerDebuff3 = playerDebuff3;
            this.playerDebuff3Amount = Math.max(0, playerDebuff3Amount);
            this.cleanseSelf = cleanseSelf;
            this.statusDrawCard = statusDrawCard;
            this.statusDrawCount = Math.max(0, statusDrawCount);
            this.selfDestruct = selfDestruct;
            this.absentBuff = absentBuff;
            this.absentBuffAmount = Math.max(0, absentBuffAmount);
            this.reincarnate = reincarnate;
            this.blockFromDamage = blockFromDamage;
            this.drawPileCards = drawPileCards == null ? new String[0] : drawPileCards;
            this.extraSelfBuff = extraSelfBuff;
            this.extraSelfBuffAmount = Math.max(0, extraSelfBuffAmount);
        }

        /**
         * 反序列化入口：把 {@link #isEmpty()} / 各工厂写出的全部字段原样装回去。
         *
         * <p>为什么必须有一个全参入口：快照回放要求「写出去的 {@code pendingMove}
         * 能逐字段读回来」（{@code ContinuationStamp} 是逐字段比较的），而现有工厂只覆盖
         * 单一天然组合，拼不出 {@code CorruptHeart} 那种「第三个减益 + 抽牌堆塞 5 张 +
         * 第二个自身能力」的组合。这里**不做任何语义加工**，只赋值；越界裁剪与私有构造器一致，
         * 不新增任何战斗规则。
         */
        public static Extra restore(Summon summon, String blockAllyType, int blockAllyTypeAmount,
                                    int blockAllies, String playerDebuff3, int playerDebuff3Amount,
                                    boolean cleanseSelf, String statusDrawCard, int statusDrawCount,
                                    boolean selfDestruct, String absentBuff, int absentBuffAmount,
                                    boolean reincarnate, boolean blockFromDamage,
                                    String[] drawPileCards, String extraSelfBuff,
                                    int extraSelfBuffAmount) {
            return new Extra(summon, blockAllyType, blockAllyTypeAmount, blockAllies,
                    playerDebuff3, playerDebuff3Amount, cleanseSelf, statusDrawCard,
                    statusDrawCount, selfDestruct, absentBuff, absentBuffAmount, reincarnate,
                    blockFromDamage, drawPileCards, extraSelfBuff, extraSelfBuffAmount);
        }

        public static Extra summon(Summon summon) {
            return new Extra(summon, null, 0, 0, null, 0, false, null, 0, false, null, 0, false,
                    false);
        }

        public static Extra blockTypedAlly(String type, int amount) {
            return new Extra(null, type, amount, 0, null, 0, false, null, 0, false, null, 0, false,
                    false);
        }

        public static Extra blockAllies(int amount) {
            return new Extra(null, null, 0, amount, null, 0, false, null, 0, false, null, 0, false,
                    false);
        }

        public static Extra thirdPlayerDebuff(String power, int amount) {
            return new Extra(null, null, 0, 0, power, amount, false, null, 0, false, null, 0, false,
                    false);
        }

        /** 只清自身减益（Champ 的 Anger 另外还给力量，那部分走主字段 selfBuff）。 */
        public static Extra cleanseSelfOnly() {
            return new Extra(null, null, 0, 0, null, 0, true, null, 0, false, null, 0, false, false);
        }

        /** 复活回半血（Darkling 的 Reincarnate）。 */
        public static Extra reincarnate() {
            return new Extra(null, null, 0, 0, null, 0, false, null, 0, false, null, 0, true, false);
        }

        /** 自己没有这个能力时才挂上（Nemesis 的无实体回补）。 */
        public static Extra onlyIfAbsent(String power, int amount) {
            return new Extra(null, null, 0, 0, null, 0, false, null, 0, false, power, amount, false,
                    false);
        }

        /** 结算完自己死掉（Exploder）。 */
        public static Extra selfDestruct() {
            return new Extra(null, null, 0, 0, null, 0, false, null, 0, true, null, 0, false, false);
        }

        /** 往抽牌堆塞状态牌（Repulsor 的 Dazed）。 */
        public static Extra statusToDraw(String card, int count) {
            return new Extra(null, null, 0, 0, null, 0, false, card, count, false, null, 0, false,
                    false);
        }

        /** 格挡 = 本招实际伤害（SpireShield 的 SMASH）。 */
        public static Extra blockFromDamage() {
            return new Extra(null, null, 0, 0, null, 0, false, null, 0, false, null, 0, false, true);
        }

        /** 第三个玩家减益 + 往抽牌堆逐个塞牌（CorruptHeart 的 Debuff）。 */
        public static Extra thirdDebuffAndCards(String power, int amount, String... cards) {
            return new Extra(null, null, 0, 0, power, amount, false, null, 0, false, null, 0, false,
                    false, cards, null, 0);
        }

        /** 第二个自身能力（CorruptHeart 的 Buff：Strength +2 之外再挂一样）。 */
        public static Extra secondSelfBuff(String power, int amount) {
            return new Extra(null, null, 0, 0, null, 0, false, null, 0, false, null, 0, false, false,
                    null, power, amount);
        }

        public boolean isEmpty() {
            return summon == null && blockAllyType == null && blockAllies <= 0
                    && playerDebuff3 == null && !cleanseSelf && statusDrawCard == null
                    && !selfDestruct && absentBuff == null && !reincarnate && !blockFromDamage
                    && drawPileCards.length == 0 && extraSelfBuff == null;
        }
    }

    @Override
    public String toString() {
        return "MonsterMove#" + id + "{dmg=" + damage + "x" + hits + ", block=" + block
                + (blockRandomAlly > 0 ? ", allyBlock=" + blockRandomAlly : "")
                + (selfBuff == null ? "" : ", self " + selfBuff + "+" + selfBuffAmount)
                + (playerDebuff == null ? "" : ", " + playerDebuff + "+" + playerDebuffAmount)
                + (playerDebuff2 == null ? "" : ", " + playerDebuff2 + "+" + playerDebuff2Amount)
                + (statusCard == null ? "" : ", discard+=" + statusCard + "x" + statusCount)
                + (splitInto == null ? "" : ", split=" + splitInto + "x" + splitCount)
                + (splitInto2 == null ? "" : " + " + splitInto2 + "x" + splitCount2)
                + (extra == null || extra.summon == null ? ""
                        : ", summon=" + java.util.Arrays.toString(extra.summon.pool)
                                + "x" + extra.summon.count + "/cap" + extra.summon.cap)
                + (extra == null || extra.blockAllyType == null ? ""
                        : ", allyBlock[" + extra.blockAllyType + "]=" + extra.blockAllyTypeAmount)
                + (extra == null || extra.blockAllies <= 0 ? ""
                        : ", alliesBlock=" + extra.blockAllies)
                + (escape ? ", ESCAPE" : "") + "}";
    }
}
