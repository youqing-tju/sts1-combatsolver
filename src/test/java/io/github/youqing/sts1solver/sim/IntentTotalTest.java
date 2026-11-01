package io.github.youqing.sts1solver.sim;

import io.github.youqing.sts1solver.search.Score;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 意图伤害必须是**本回合总伤害**，不是「其中一段」。
 *
 * <p>2026-11-01 真机事故：玩家 1 血、34 格挡，场上地精首领意图显示 10。影子把 10 当总伤害
 * （10 &lt; 34 判定安全）→ 结束回合 → 实际是 **3 段 × 10 = 30**，再叠加小地精的 5 → 打死。
 *
 * <p>根因是 `AbstractMonster.getIntentDmg()` 只返回 `damage.get(0).output`（javap 核实），
 * 也就是**一段**的伤害；多段攻击的怪在 `takeTurn` 里连排 N 个 {@code DamageAction}。
 * 影子内部其它地方（{@code CombatFlow} ④.6）一律按「perHit × hits」写，两边口径必须一致。
 */
class IntentTotalTest {

    /** 捕获侧的唯一换算：一段的伤害 → 本回合总伤害。 */
    @Test
    void perHitIntentIsScaledToTheTurnTotal() {
        assertEquals(30, SimState.totalIntent(10, Arrays.asList(6, 6, 6)),
                "地精首领 3 段 × 10 = 30，不是 10");
        assertEquals(10, SimState.totalIntent(10, Arrays.asList(10, 12)),
                "Mugger 的两个招式伤害不同 -> 条目数不是段数");
        assertEquals(8, SimState.totalIntent(8, Collections.<Integer>emptyList()));
        assertEquals(0, SimState.totalIntent(-5, Arrays.asList(6, 6, 6)), "负值归零");
    }

    @Test
    void repeatedIdenticalDamageEntriesMeanMultipleHits() {
        // 地精首领：STAB_DMG=6 / STAB_AMT=3 → damage 里三份同值
        assertEquals(3, SimState.identicalHitCount(Arrays.asList(6, 6, 6)));

        // 单段
        assertEquals(1, SimState.identicalHitCount(Collections.singletonList(8)));

        // 不同招式的伤害值不同（Mugger: MUG 10 / BIGSWIPE 12）→ 条目数不是段数
        assertEquals(1, SimState.identicalHitCount(Arrays.asList(10, 12)));

        // 边界：空 / null / 含 null
        assertEquals(1, SimState.identicalHitCount(Collections.<Integer>emptyList()));
        assertEquals(1, SimState.identicalHitCount(null));
        assertEquals(1, SimState.identicalHitCount(Arrays.asList(6, null, 6)));
    }

    /**
     * 口径统一：{@code Score.projectedHpOf} 拿到的 {@code intentDmg} 是总伤害。
     *
     * <p>这条钉的是「1 血、34 格挡、3×10」这个真机现场：总伤害 30 &lt; 34 仍然活着，
     * 而只要有人把它写回「一段 10」，结论就变成「永远安全」—— 那正是被打死的原因。
     */
    @Test
    void projectedHpUsesTheTotalNotASingleHit() {
        SimState s = new SimState();
        s.hp = 1;
        s.maxHp = 85;
        s.block = 20;
        SimMonster leader = new SimMonster("GremlinLeader", 47, 240, 0,
                Collections.<String, Integer>emptyMap());
        leader.intentAttack = true;
        leader.intentDmg = 30;                 // 3 段 × 10
        s.monsters.add(leader);

        assertTrue(Score.projectedHpOf(s) <= 0, "30 伤打 20 挡 -> 掉 10 -> 1 血必死");

        leader.intentDmg = 10;                 // 只算一段 = 事故现场的错算法
        assertEquals(1, Score.projectedHpOf(s), "一段 10 全被格挡，看起来「安全」—— 这就是 bug");
    }

}
