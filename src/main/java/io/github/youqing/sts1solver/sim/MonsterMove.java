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
    /** 往玩家弃牌堆塞的状态牌 ID（Sentry 的 Dazed）；{@code null} = 不塞。 */
    public final String statusCard;
    public final int statusCount;

    public MonsterMove(int id, int damage, int hits, int block, int blockRandomAlly,
                       String selfBuff, int selfBuffAmount,
                       String playerDebuff, int playerDebuffAmount,
                       String statusCard, int statusCount) {
        this.id = id;
        this.damage = Math.max(0, damage);
        this.hits = Math.max(0, hits);
        this.block = Math.max(0, block);
        this.blockRandomAlly = Math.max(0, blockRandomAlly);
        this.selfBuff = selfBuff;
        this.selfBuffAmount = Math.max(0, selfBuffAmount);
        this.playerDebuff = playerDebuff;
        this.playerDebuffAmount = Math.max(0, playerDebuffAmount);
        this.statusCard = statusCard;
        this.statusCount = Math.max(0, statusCount);
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

    /** 给自己挂能力（Cultist 的 INCANTATION / 各种 BUFF 招）。 */
    public static MonsterMove buff(int id, String power, int amount) {
        return new MonsterMove(id, 0, 0, 0, 0, power, amount, null, 0, null, 0);
    }

    /** 给自己挂能力 + 自己加格挡（JawWorm 的 Bellow）。 */
    public static MonsterMove buffBlock(int id, String power, int amount, int block) {
        return new MonsterMove(id, 0, 0, block, 0, power, amount, null, 0, null, 0);
    }

    /** 攻击 + 往玩家弃牌堆塞状态牌（SpikeSlime_M 的 Tackle → Slimed）。 */
    public static MonsterMove attackStatus(int id, int damage, String card, int count) {
        return new MonsterMove(id, damage, 1, 0, 0, null, 0, null, 0, card, count);
    }

    /** 往玩家弃牌堆塞状态牌（Sentry 的 Dazed）。 */
    public static MonsterMove status(int id, String card, int count) {
        return new MonsterMove(id, 0, 0, 0, 0, null, 0, null, 0, card, count);
    }

    @Override
    public String toString() {
        return "MonsterMove#" + id + "{dmg=" + damage + "x" + hits + ", block=" + block
                + (blockRandomAlly > 0 ? ", allyBlock=" + blockRandomAlly : "")
                + (selfBuff == null ? "" : ", self " + selfBuff + "+" + selfBuffAmount)
                + (playerDebuff == null ? "" : ", " + playerDebuff + "+" + playerDebuffAmount)
                + (statusCard == null ? "" : ", discard+=" + statusCard + "x" + statusCount) + "}";
    }
}
