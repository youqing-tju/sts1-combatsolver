package io.github.youqing.sts1solver.sim;

/**
 * 怪物的一次出招（影子层）。
 *
 * <p>对应蓝本 {@code Engine/InCombat/Mirrors} 那一层「把怪物 AI 也镜像出来」的思路：
 * 游戏里怪物每回合由 {@code getMove()} 决定 {@code nextMove}，{@code takeTurn()} 再照着它
 * 产生伤害 / 格挡 / 减益。我们原来只学「意图伤害」一个数（{@code IntentLearning}），
 * 现在逐个怪物翻译 {@code getMove} + {@code takeTurn}，把这三样都建模出来。
 *
 * <p>{@link #id} 就是原版 {@code nextMove} 的字节值 —— 机器要靠它判断「连续两次同一招」。
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
    /** 施加给玩家的减益 ID（{@code null} = 不施加）。 */
    public final String playerDebuff;
    public final int playerDebuffAmount;

    public MonsterMove(int id, int damage, int hits, int block,
                       String playerDebuff, int playerDebuffAmount) {
        this.id = id;
        this.damage = Math.max(0, damage);
        this.hits = Math.max(0, hits);
        this.block = Math.max(0, block);
        this.playerDebuff = playerDebuff;
        this.playerDebuffAmount = Math.max(0, playerDebuffAmount);
    }

    public boolean attacks() {
        return damage > 0 && hits > 0;
    }

    public static MonsterMove attack(int id, int damage) {
        return new MonsterMove(id, damage, 1, 0, null, 0);
    }

    public static MonsterMove multiAttack(int id, int damage, int hits) {
        return new MonsterMove(id, damage, hits, 0, null, 0);
    }

    public static MonsterMove block(int id, int block) {
        return new MonsterMove(id, 0, 0, block, null, 0);
    }

    public static MonsterMove attackBlock(int id, int damage, int block) {
        return new MonsterMove(id, damage, 1, block, null, 0);
    }

    public static MonsterMove attackDebuff(int id, int damage, String debuff, int amount) {
        return new MonsterMove(id, damage, 1, 0, debuff, amount);
    }

    @Override
    public String toString() {
        return "MonsterMove#" + id + "{dmg=" + damage + "x" + hits + ", block=" + block
                + (playerDebuff == null ? "" : ", " + playerDebuff + "+" + playerDebuffAmount) + "}";
    }
}
