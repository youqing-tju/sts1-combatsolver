package io.github.youqing.sts1solver.search;

import io.github.youqing.sts1solver.sim.SimCard;
import io.github.youqing.sts1solver.sim.SimMonster;
import io.github.youqing.sts1solver.sim.SimState;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 延迟收益（{@code LatentSetupValue}）的启用条件必须与蓝本一致：
 * {@code _isActEndingBoss || _initialEnemyCount > 1}。
 *
 * <p>我们以前只留了「多敌人」那一半，于是**单只 Boss**（正是最该先开能力牌的场合）
 * 手上那张能力牌在 beam 排序里等于 0 分 —— 开它就是白扔 1 点能量一张牌。
 * 2026-11-01 补齐 {@code _isActEndingBoss} 这一半。
 */
class ScoreLatentSetupBossTest {

    private static SimState state(boolean boss) {
        SimState s = new SimState();
        s.hp = 60;
        s.maxHp = 80;
        s.energy = 3;
        s.turn = 1;
        s.boss = boss;
        // 手上留一张没打出去的能力牌 = 延迟收益
        s.hand.add(new SimCard("Feel No Pain", false, 1, -1, "POWER"));
        s.hand.add(new SimCard("Strike_R", false, 1, 6, "ATTACK"));
        SimMonster m = new SimMonster("Boss", 200, 200, 0,
                Collections.<String, Integer>emptyMap());
        m.intentDmg = 10;
        s.monsters.add(m);
        for (int i = 0; i < 6; i++) {
            s.draw.add(new SimCard("Strike_R", false, 1, 6, "ATTACK"));
        }
        return s;
    }

    @Test
    void singleEnemyBossStillCountsLatentSetup() {
        long boss = Score.of(state(true)).beamTotal();
        long plain = Score.of(state(false)).beamTotal();

        assertTrue(boss > plain,
                "单只 Boss 房必须给「还没打出去的能力牌」算延迟收益；"
                        + " boss=" + boss + " plain=" + plain);
    }
}
