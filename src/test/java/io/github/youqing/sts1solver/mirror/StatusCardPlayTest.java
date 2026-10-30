package io.github.youqing.sts1solver.mirror;

import org.junit.jupiter.api.Test;

import java.util.Collections;

import io.github.youqing.sts1solver.mirror.hooks.RelicHooks;
import io.github.youqing.sts1solver.sim.SimCard;
import io.github.youqing.sts1solver.sim.SimMonster;
import io.github.youqing.sts1solver.sim.SimState;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 诅咒 / 状态牌的可打出性与「打出去去哪」。
 *
 * <p>真机 2026-10-30 的日志里每轮搜索都在刷
 * {@code SEARCH REJECT: Pain+ / Slimed+ -> no mirror}：军备把状态牌敲成 "+" 版之后，
 * 真实牌名变了、镜像没跟上，搜索就把它们当成「不存在」。这一批把三个坑钉住：
 * <ol>
 *   <li>升级态（{@code X+}）也要有镜像；</li>
 *   <li>「打不出」要报「打不出」，别报「没镜像」（判据顺序）；</li>
 *   <li>蓝蜡烛 / 医疗包打出的诅咒状态**必须进消耗堆**。</li>
 * </ol>
 */
class StatusCardPlayTest {

    private static SimState combat() {
        SimState s = new SimState();
        s.hp = 80;
        s.maxHp = 80;
        s.energy = 3;
        s.monsters.add(new SimMonster("AcidSlime_S", 30, 30, 0,
                Collections.<String, Integer>emptyMap()));
        return s;
    }

    /** 状态牌（黏液）：cost = -2 是打不出，医疗包放行后按 0 费打、进消耗堆。 */
    @Test
    void slimedNeedsMedicalKitAndExhausts() {
        SimState s = combat();
        SimCard slimed = new SimCard("Slimed", false, -2, -1, "STATUS");
        s.hand.add(slimed);

        assertFalse(CardPlayer.playableAtAll(s, slimed),
                "没有医疗包时状态牌打不出（原版 cost = -2）");

        s.relics.put(RelicHooks.MEDICAL_KIT, Integer.valueOf(-1));
        assertTrue(CardPlayer.playableAtAll(s, slimed), "医疗包放行状态牌");

        CardPlayer.play(s, 0, -1);
        assertEquals(1, s.exhaust.size(), "医疗包打出的状态牌必须消耗");
        assertTrue(s.discard.isEmpty(), "不能进弃牌堆（影子和实机会当场分叉）");
        assertEquals(3, s.energy, "cost = -2 按 0 费打，不扣能量");
    }

    /** 军备敲过的状态牌（{@code Slimed+}）：镜像要认，费用按捕获到的 0。 */
    @Test
    void upgradedStatusHasMirror() {
        assertTrue(MirrorRegistry.supports("Slimed+"), "Slimed+ 要有镜像");
        assertTrue(MirrorRegistry.supports("Wound+"), "Wound+ 要有镜像");

        SimState s = combat();
        s.relics.put(RelicHooks.MEDICAL_KIT, Integer.valueOf(-1));
        s.hand.add(new SimCard("Slimed", true, 0, -1, "STATUS"));
        CardPlayer.play(s, 0, -1);
        assertEquals(1, s.exhaust.size(), "Slimed+ 打完同样消耗");
        assertEquals(3, s.energy, "0 费");
    }

    /** 诅咒：蓝蜡烛放行 + 掉 1 血 + 消耗。 */
    @Test
    void curseNeedsBlueCandleAndCostsOneHp() {
        SimState s = combat();
        SimCard pain = new SimCard("Pain", true, -2, -1, "CURSE");
        s.hand.add(pain);

        assertFalse(CardPlayer.playableAtAll(s, pain), "没有蓝蜡烛时诅咒打不出");

        s.relics.put(RelicHooks.BLUE_CANDLE, Integer.valueOf(-1));
        assertTrue(CardPlayer.playableAtAll(s, pain), "蓝蜡烛放行诅咒");
        assertTrue(MirrorRegistry.supports("Pain+"), "Pain+ 要有镜像（否则搜索当它不存在）");

        CardPlayer.play(s, 0, -1);
        assertEquals(79, s.hp, "BlueCandle.onUseCard：失去 1 点生命");
        assertEquals(1, s.exhaust.size(), "打出的诅咒消耗");
    }
}
