package io.github.youqing.sts1solver.mirror;

import io.github.youqing.sts1solver.sim.SimCard;
import io.github.youqing.sts1solver.sim.SimMonster;
import io.github.youqing.sts1solver.sim.SimState;
import org.junit.jupiter.api.Test;

import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 愤怒（{@code Anger}）的复制牌账本。
 *
 * <p>原版证据（javap {@code Anger.use}）：{@code DamageAction} + {@code VFXAction} +
 * {@code MakeTempCardInDiscardAction(makeStatEquivalentCopy(), 1)} —— 每次打出都往弃牌堆塞一张复制。
 *
 * <p>蓝本 {@code CorePowerSupport.cs:246-254}：真的生成了一张复制牌之后才
 * {@code RecordAngerCopyGenerated()}；并且**只有 Anger 这一个来源**。
 * 这条账本进最终排序第 12 键（升序），所以别的复制效果若冒充它，
 * 「少打 Anger」这条偏好就会被压错方向。
 */
class AngerCopyLedgerTest {

    @Test
    void eachPlayGeneratesOneCopyAndOneLedgerEntry() {
        SimState state = combat("Anger");
        int discardBefore = state.discard.size();

        CardPlayer.play(state, 0, 0);

        // 打出的那张自己也进弃牌堆，所以是 +2：一张本体 + 一张复制
        assertEquals(discardBefore + 2, state.discard.size(), "本体与复制都进弃牌堆");
        assertEquals("Anger", state.discard.get(state.discard.size() - 1).id);
        assertEquals(1, state.angerCopiesGenerated);
    }

    /** 升级版伤害更高，但复制行为一样。 */
    @Test
    void upgradedAngerCopiesToo() {
        SimState state = combat("Anger+");
        CardPlayer.play(state, 0, 0);

        assertEquals(1, state.angerCopiesGenerated);
    }

    /** 别的「复制牌」效果不许冒充 Anger 的账本（蓝本只登记 Anger）。 */
    @Test
    void otherCopyEffectsDoNotTouchTheAngerLedger() {
        SimState state = new SimState();
        state.hp = 80;
        state.maxHp = 80;
        state.energy = 3;
        state.hand.add(new SimCard("Dual Wield", false, 1));
        state.hand.add(new SimCard("Strike_R", false, 1, 6, "ATTACK"));
        state.monsters.add(new SimMonster("AcidSlime_S", 40, 40, 0,
                new TreeMap<String, Integer>()));

        // 打出之后 Dual Wield 已经离开手牌，剩下的第 0 张就是 Strike_R
        CardPlayer.play(state, 0, 0, 0);

        assertEquals(0, state.angerCopiesGenerated,
                "Dual Wield 不是蓝本登记的成长来源，不许记进 Anger 账本");
        assertEquals(2, state.hand.size(), "复制确实发生了：2 张手牌 - 打出 1 张 + 复制 1 张");
    }

    /** 打别的牌当然也不动账本。 */
    @Test
    void unrelatedCardsLeaveTheLedgerAlone() {
        SimState state = combat("Anger");
        state.hand.add(new SimCard("Strike_R", false, 1, 6, "ATTACK"));

        CardPlayer.play(state, 1, 0);

        assertEquals(0, state.angerCopiesGenerated);
    }

    private static SimState combat(String cardKey) {
        SimState state = new SimState();
        state.hp = 80;
        state.maxHp = 80;
        state.energy = 3;
        boolean upgraded = cardKey.endsWith("+");
        String id = upgraded ? cardKey.substring(0, cardKey.length() - 1) : cardKey;
        state.hand.add(new SimCard(id, upgraded, 0));
        state.monsters.add(new SimMonster("AcidSlime_S", 40, 40, 0,
                new TreeMap<String, Integer>()));
        return state;
    }
}
