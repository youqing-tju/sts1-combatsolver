package io.github.youqing.sts1solver.sim;

import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 每回合回能必须是 {@code EnergyManager.energyMaster}，不能拿「第一回合的当前能量」顶。
 *
 * <p>2026-10-31 的镜像缺口：提灯（{@code Lantern}）只在第一回合给 1 点能量
 * （{@code atPreBattle} 置 {@code firstTurn}，{@code atTurnStart} 用完即清），
 * 而 {@code CombatCapture.baseEnergy()} 当时取的是捕获时刻的 {@code EnergyPanel.totalCount}
 * —— 带提灯时首回合是 4，于是**仿真从第二回合起每回合都多 1 点能量**。
 *
 * <p>现在拆成两个字段：{@code energy}（当前能量，含首回合临时加成）与
 * {@code energyPerTurn}（{@code energyMaster}，后续回合的充值量）。
 */
class SimStateEnergyPerTurnTest {

    private static SimState fight() {
        SimState s = new SimState();
        s.hp = 80;
        s.maxHp = 80;
        SimMonster m = new SimMonster("TestMonster", 200, 200, 0,
                Collections.<String, Integer>emptyMap());
        m.pendingMove = MonsterMove.block(1, 0);
        m.pendingMoveId = 1;
        s.monsters.add(m);
        return s;
    }

    @Test
    void firstTurnLanternBonusDoesNotBecomeThePerTurnRecharge() {
        SimState s = fight();
        s.energy = 4;            // 第一回合：3 + 提灯 1
        s.energyPerTurn = 3;     // energyMaster

        assertEquals(3, s.energyPerTurnOrEnergy());

        CombatFlow.endTurn(s, s.energyPerTurnOrEnergy(), 5);

        assertEquals(3, s.energy, "第二回合应该只有 3 点，提灯不再生效");
    }

    @Test
    void unknownPerTurnFallsBackToCapturedEnergy() {
        SimState s = new SimState();
        s.energy = 4;
        s.energyPerTurn = 0;     // 没捕获到（老快照 / 不在战斗里）

        assertEquals(4, s.energyPerTurnOrEnergy(), "拿不到 energyMaster 时退回当前能量，不能变成 0");
    }
}
