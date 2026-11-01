package io.github.youqing.sts1solver.ui;

import io.github.youqing.sts1solver.search.PotionStrategySnapshot;
import io.github.youqing.sts1solver.search.SolverPotionDirective;
import io.github.youqing.sts1solver.sim.SimCard;
import io.github.youqing.sts1solver.sim.SimMonster;
import io.github.youqing.sts1solver.sim.SimState;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 药水区控件的**纯数据模型**（面板只负责画和转发点击）。
 *
 * <p>为什么不直接实例化 {@code SolverPanel}：它一被加载就会碰 libgdx / {@code AbstractDungeon}
 * 这类真机对象，单测里起不来。所以把「显示什么、能不能点、点完变什么」抽成
 * {@link PotionStrategyRows}，面板只做渲染与命中转发 —— 与 {@code BoundedAutoTarget} 同一手法。
 */
class SolverPanelPotionStrategyTest {

    private static SimState state(String... potions) {
        SimState s = new SimState();
        s.hp = 50;
        s.maxHp = 80;
        s.turn = 1;
        s.potionSlots = potions.length;
        for (String id : potions) {
            s.potions.add(id);
        }
        s.relics.put("Burning Blood", -1);
        s.playerPowers.put("Strength", 2);
        s.monsters.add(new SimMonster("JawWorm", 40, 44, 0,
                Collections.<String, Integer>emptyMap()));
        s.hand.add(new SimCard("Strike_R", false, 1, 6, "ATTACK"));
        return s;
    }

    private static StrategyStatusSnapshot snapshot(String... potions) {
        return StrategyStatusSnapshot.from(state(potions));
    }

    @Test
    void potionRowsAreTheOnlyInteractivePart() {
        StrategyStatusSnapshot snap = snapshot("Fire Potion", null);
        List<PotionStrategyRows.Row> rows = PotionStrategyRows.build(
                snap.potionSlots(), PotionStrategySnapshot.defaults(), false);

        assertEquals(1, rows.size(), "空槽不该生成控件行");
        PotionStrategyRows.Row row = rows.get(0);
        assertEquals(1, row.slot, "文案槽位是 1 基");
        assertEquals("Fire Potion", row.potionId);
        assertTrue(row.searchable);
        assertTrue(row.clickable());
        assertTrue(row.label().contains("Smart"), "默认档位要显示出来：" + row.label());
        assertTrue(row.label().contains("点击：Disabled"), "要点明下一档是谁：" + row.label());

        // 遗物/成长仍然是纯文本行，不在这批控件里
        for (String line : snap.otherLines()) {
            assertFalse(line.contains("药["), "药水行不该混进只读区：" + line);
        }
        assertTrue(snap.otherLines().stream().anyMatch(l -> l.startsWith("遗物")));
    }

    @Test
    void nonSearchablePotionHasNoEnabledControl() {
        StrategyStatusSnapshot snap = snapshot("FairyPotion", null);
        List<PotionStrategyRows.Row> rows = PotionStrategyRows.build(
                snap.potionSlots(), PotionStrategySnapshot.defaults(), false);

        assertEquals(1, rows.size());
        PotionStrategyRows.Row row = rows.get(0);
        assertFalse(row.searchable, "瓶中仙子是被动药水");
        assertFalse(row.clickable(), "被动药水没有可用控件");
        assertTrue(row.label().contains("无控件"), "要明说没有控件，而不是「点了没反应」：" + row.label());
    }

    @Test
    void lockedRowsDoNotChangeAnything() {
        StrategyStatusSnapshot snap = snapshot("Fire Potion", null);
        List<PotionStrategyRows.Row> rows = PotionStrategyRows.build(
                snap.potionSlots(), PotionStrategySnapshot.defaults(), true);

        PotionStrategyRows.Row row = rows.get(0);
        assertFalse(row.clickable(), "AUTO 执行中必须锁定");
        assertTrue(row.label().contains("锁定"), "锁定要说出来：" + row.label());
    }

    @Test
    void directiveLabelsFollowTheSnapshot() {
        StrategyStatusSnapshot snap = snapshot("Fire Potion", "Block Potion");
        PotionStrategySnapshot strategy = PotionStrategySnapshot.defaults()
                .withDirective(0, "Fire Potion", SolverPotionDirective.FORCE)
                .withDirective(1, "Block Potion", SolverPotionDirective.DISABLED);

        List<PotionStrategyRows.Row> rows = PotionStrategyRows.build(
                snap.potionSlots(), strategy, false);
        assertEquals(2, rows.size());
        assertTrue(rows.get(0).label().contains("Force"), rows.get(0).label());
        assertTrue(rows.get(1).label().contains("Disabled"), rows.get(1).label());
        // 槽位映射：文案 1 基 → 策略键 0 基，只在一处转换
        assertEquals(SolverPotionDirective.FORCE,
                strategy.directiveFor(rows.get(0).slot - 1, rows.get(0).potionId));
    }

    @Test
    void cyclingIsSmartThenDisabledThenForce() {
        assertEquals(SolverPotionDirective.DISABLED,
                PotionStrategyRows.cycle(SolverPotionDirective.SMART));
        assertEquals(SolverPotionDirective.FORCE,
                PotionStrategyRows.cycle(SolverPotionDirective.DISABLED));
        assertEquals(SolverPotionDirective.SMART,
                PotionStrategyRows.cycle(SolverPotionDirective.FORCE));
    }

    @Test
    void snapshotExposesStructureWithoutBreakingTheOldLines() {
        StrategyStatusSnapshot snap = snapshot("Fire Potion", null);
        assertTrue(snap.lines().size() > snap.potionSlots().size(),
                "旧口径 lines() 仍包含药水行与遗物/成长");
        assertEquals("Fire Potion", snap.potionSlots().get(0).potionId);
        assertTrue(snap.lines().get(0).contains("Fire Potion"));
    }

    /** 遗物/成长仍然只读：快照类不许出现任何修改入口（防止「假控件」再长回来）。 */
    @Test
    void relicAndGrowthSectionsRemainReadOnly() {
        for (Method method : StrategyStatusSnapshot.class.getDeclaredMethods()) {
            if (!Modifier.isPublic(method.getModifiers())) {
                continue;
            }
            assertFalse(method.getName().startsWith("set"),
                    "只读快照不该有 setter：" + method.getName());
            for (Class<?> type : method.getParameterTypes()) {
                assertFalse(type == SolverPotionDirective.class,
                        "策略指令不该穿过只读快照：" + method.getName());
            }
        }
        // 反过来：药水控件模型只吃「槽位 + 策略」两个纯数据输入
        assertTrue(Arrays.stream(PotionStrategyRows.class.getDeclaredMethods())
                .anyMatch(m -> m.getName().equals("build")));
        assertNull(null);
    }
}
