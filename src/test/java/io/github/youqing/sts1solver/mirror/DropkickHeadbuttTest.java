package io.github.youqing.sts1solver.mirror;

import org.junit.jupiter.api.Test;

import java.util.Collections;

import io.github.youqing.sts1solver.sim.SimCard;
import io.github.youqing.sts1solver.sim.SimMonster;
import io.github.youqing.sts1solver.sim.SimState;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真机 2026-10-30 的两簇失配（同一局 7 次里占 6 次）。
 *
 * <ol>
 *   <li><b>飞身踢 Dropkick</b>：镜像原来写成「无条件抽 1 + 易伤时回 2 能量再抽 1」。
 *       原版 {@code DropkickAction} 构造器里 draw 与 gainEnergy 都在
 *       {@code if (target.hasPower("Vulnerable"))} 之内，且能量是 1 点
 *       （卡面 {@code UPGRADE_DESCRIPTION} 为空，升级只加伤害）。</li>
 *   <li><b>头槌 Headbutt</b>：既需要目标、又需要从弃牌堆挑一张。搜索展开时
 *       一律传 {@code targetIndex = -1}，效果层 {@code requireTarget} 抛拒绝，
 *       整张牌一条分支都没有 —— 真机「1 能量 + 只有头槌能打」就被判成无事可做。</li>
 * </ol>
 */
class DropkickHeadbuttTest {

    private static SimState combat() {
        SimState s = new SimState();
        s.hp = 80;
        s.maxHp = 80;
        s.energy = 3;
        s.monsters.add(new SimMonster("AcidSlime_S", 40, 40, 0,
                Collections.<String, Integer>emptyMap()));
        return s;
    }

    private static void fillDraw(SimState s, int n) {
        for (int i = 0; i < n; i++) {
            s.draw.add(new SimCard("Strike_R", false, 1, 1, "ATTACK"));
        }
    }

    /** 目标**没有**易伤：飞身踢不抽牌、不回能量。 */
    @Test
    void dropkickDoesNothingExtraWithoutVulnerable() {
        SimState s = combat();
        fillDraw(s, 5);
        s.hand.add(new SimCard("Dropkick", false, 1, 1, "ATTACK"));

        int drawBefore = s.draw.size();
        int energyBefore = s.energy;
        CardPlayer.play(s, 0, 0);

        assertEquals(drawBefore, s.draw.size(), "没有易伤就不该抽牌（原版 draw 在 if 里面）");
        assertEquals(energyBefore - 1, s.energy, "只扣自己的 1 费，不回能量");
        assertEquals(0, s.hand.size(), "手牌只少了打出去的那张");
    }

    /** 目标有易伤：飞身踢回 **1** 能量 + 抽 1 张。 */
    @Test
    void dropkickGainsOneEnergyAndDrawsOneWhenVulnerable() {
        SimState s = combat();
        fillDraw(s, 5);
        s.hand.add(new SimCard("Dropkick", true, 1, 1, "ATTACK"));
        s.monsters.get(0).powers.put("Vulnerable", Integer.valueOf(2));

        int drawBefore = s.draw.size();
        int energyBefore = s.energy;
        CardPlayer.play(s, 0, 0);

        assertEquals(drawBefore - 1, s.draw.size(), "易伤时抽 1 张");
        assertEquals(energyBefore - 1 + 1, s.energy, "易伤时回 1 能量（不是 2）");
    }

    /** 头槌：既要目标、又要选牌 —— 两面都得标出来，否则搜索不会枚举目标。 */
    @Test
    void headbuttIsBothTargetedAndNeedsChoice() {
        CardMirror m = MirrorRegistry.lookup("Headbutt");
        assertTrue(m.targeted, "头槌是攻击牌，必须枚举目标");
        assertTrue(m.needsChoice, "头槌还要从弃牌堆挑一张");
        CardMirror upgraded = MirrorRegistry.lookup("Headbutt+");
        assertTrue(upgraded.targeted && upgraded.needsChoice, "升级态一样");
    }

    /** 头槌带目标打出去：造成伤害，并把弃牌堆顶上那张放回抽牌堆顶。 */
    @Test
    void headbuttWithTargetAndChoiceWorks() {
        SimState s = combat();
        s.hand.add(new SimCard("Headbutt", false, 1, 1, "ATTACK"));
        s.discard.add(new SimCard("Defend_R", false, 1, 1, "SKILL"));

        CardPlayer.play(s, 0, 0, new int[]{0});

        assertEquals(40 - 9, s.monsters.get(0).hp, "头槌打 9 伤（需真目标）");
        assertEquals(1, s.draw.size(), "挑中的那张回到抽牌堆");
        assertEquals("Defend_R", s.draw.get(s.draw.size() - 1).id, "放在抽牌堆顶（下一张就抽到）");
        assertEquals(1, s.discard.size(), "弃牌堆里只剩打出去的头槌自己");
        assertEquals("Headbutt", s.discard.get(0).id, "被挑走的那张已经不在弃牌堆");
    }
}
