package io.github.youqing.sts1solver.ui;

import java.util.ArrayList;
import java.util.List;

import io.github.youqing.sts1solver.search.PotionStrategySnapshot;
import io.github.youqing.sts1solver.search.SolverPotionDirective;

/**
 * 药水区的**真实控件模型**（纯数据，可在单测里直接驱动）。
 *
 * <p>从 `StrategyStatusSnapshot` 的槽位视图 + 当前策略算出「每一行显示什么、能不能点」。
 * 面板只负责把 Row 画出来并把点击转成 {@code AutoPilot.cyclePotionDirective}——
 * 布局、命中、语义三者不混在一起，点击穿透一类的问题才不会和策略逻辑缠在一起。
 *
 * <p>三条规则（对应设计 §4.4）：
 * <ol>
 *   <li>只有**药水**是可点的；遗物/成长仍是只读事实行；</li>
 *   <li>不可搜索（被动药水 / 空槽）的槽位**没有可用控件**，而不是「点了没反应」；</li>
 *   <li>自动部署/执行中整体锁定，点击不改变配置（控制器侧也会再拒一次，不只靠变灰）。</li>
 * </ol>
 */
public final class PotionStrategyRows {

    private PotionStrategyRows() {
    }

    /** 一行药水控件。 */
    public static final class Row {
        /** 1 基槽位（与 {@code 药[1]} 文案一致）。 */
        public final int slot;
        public final String potionId;
        public final String text;
        public final SolverPotionDirective directive;
        public final boolean searchable;
        public final boolean locked;

        Row(int slot, String potionId, String text, SolverPotionDirective directive,
            boolean searchable, boolean locked) {
            this.slot = slot;
            this.potionId = potionId;
            this.text = text;
            this.directive = directive;
            this.searchable = searchable;
            this.locked = locked;
        }

        public boolean clickable() {
            return searchable && !locked;
        }

        /** 展示文案：原快照行 + 当前指令 + 可点提示。 */
        public String label() {
            if (!searchable) {
                return text + " · 无控件";
            }
            String suffix;
            if (locked) {
                suffix = " · " + directive.label() + "（AUTO 执行中，锁定）";
            } else {
                suffix = " · " + directive.label() + "（点击：" + nextName(directive) + "）";
            }
            return text + suffix;
        }
    }

    /** 按槽位顺序生成控件行。 */
    public static List<Row> build(List<StrategyStatusSnapshot.PotionSlotView> slots,
                                  PotionStrategySnapshot strategy, boolean locked) {
        List<Row> out = new ArrayList<Row>();
        if (slots == null) {
            return out;
        }
        PotionStrategySnapshot effective = strategy == null
                ? PotionStrategySnapshot.defaults() : strategy;
        for (StrategyStatusSnapshot.PotionSlotView view : slots) {
            // 文案里的槽位是 1 基，策略键是 0 基 —— 转换只在这里做一次
            int strategySlot = view.slot - 1;
            out.add(new Row(view.slot, view.potionId, view.text,
                    effective.directiveFor(strategySlot, view.potionId),
                    view.searchable, locked));
        }
        return out;
    }

    /** 循环：Smart → Disabled → Force → Smart（与设计 §4.1 一致）。 */
    public static SolverPotionDirective cycle(SolverPotionDirective current) {
        if (current == null) {
            return SolverPotionDirective.DISABLED;
        }
        switch (current) {
            case SMART:
                return SolverPotionDirective.DISABLED;
            case DISABLED:
                return SolverPotionDirective.FORCE;
            default:
                return SolverPotionDirective.SMART;
        }
    }

    public static String nextName(SolverPotionDirective current) {
        return cycle(current).label();
    }
}
