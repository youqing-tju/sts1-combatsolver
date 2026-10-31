package io.github.youqing.sts1solver.ui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import io.github.youqing.sts1solver.mirror.Coverage;
import io.github.youqing.sts1solver.mirror.PotionValuation;
import io.github.youqing.sts1solver.mirror.Potions;
import io.github.youqing.sts1solver.sim.SimState;

/**
 * 「策略现状（只读）」区的数据快照：**不可变、纯数据、只由 {@link SimState} 生成**。
 *
 * <p>⚠️ 它<b>不是</b>蓝本 {@code SolverPotionStrategyPanel} / {@code SolverRelicStrategyPanel} /
 * {@code SolverGrowthStrategyPanel} 的复刻。那三个是<b>控制器</b>（Smart / Disabled / Force、
 * 遗物目标计数、成长 HP 额度，改完会发回控制层改变搜索），本轮按已批准设计只做
 * <b>STS1 当前状态的展示适配</b>（见 {@code docs/plans/2026-10-31-ui-parity-design.md} §1.1、§4）。
 * 所以这里没有 setter、没有开关、没有「额度」这类不存在的设置 —— 只有事实。
 *
 * <h2>三块内容</h2>
 * <ol>
 *   <li><b>药水</b>：按槽位顺序，空槽聚合一行。HP 门槛取自
 *       {@link PotionValuation#strategicHpCost(String)}，文案是「门槛」——
 *       它回答「喝掉它至少要少掉这么多血才划算」，<b>不是</b>「这一把实际省了几点血」
 *       （后者要 {@code AsyncSearch.Result} 里的药水对账遥测，本轮没有）。</li>
 *   <li><b>遗物</b>：{@code id=counter} 原样显示，{@code -1} 不做翻译
 *       （原版里 -1 对不同遗物含义不同，替玩家解释只会误导）。另附
 *       {@link Coverage#unmirroredRelics} 的数据干净度告警。</li>
 *   <li><b>成长</b>：{@code hp/maxHp} 与已有的正向关键层数。只列
 *       {@code Strength} / {@code Dexterity} / {@code Demon Form} 且 {@code amount > 0} 的项；
 *       负层数（临时敏捷削减等）不显示，避免把「还账」当成「成长」。</li>
 * </ol>
 *
 * <h2>为什么是快照而不是现算</h2>
 * <p>{@link SimState#capture()} 要扫四个牌堆 + 能力 + 遗物，每帧调用会吃掉帧率。
 * 面板每帧都渲染，所以这里只在<b>主线程动作稳定后</b>生成一次，渲染只读缓存行
 * （见 {@code AutoPilot#publishTurnSnapshot} / {@code AutoPilot#refreshStrategySnapshotIfSettled}）。
 */
public final class StrategyStatusSnapshot {

    /** 成长行固定顺序与展示文案（顺序固定 = 同一局面永远同一行，便于肉眼比对）。 */
    private static final String[] GROWTH_POWERS = {"Strength", "Dexterity", "Demon Form"};

    /**
     * 空药水槽的占位 ID。
     *
     * <p>🔴 原版 {@code AbstractPlayer.potions} 里的空槽**不是 null**，而是一个
     * {@code PotionSlot} 实例，它的 {@code ID} 就是 {@code "Potion Slot"}
     * （{@code PotionSlot} 构造器 {@code super(NAME, "Potion Slot", PLACEHOLDER, ...)}，
     * javap 核实）。所以只判 null 会把空槽显示成「一瓶叫 Potion Slot 的主动药水，
     * 门槛 9 HP」—— 2026-10-31 真机截图里就是这个假药水。
     */
    private static final String EMPTY_SLOT_ID = "Potion Slot";

    private final List<String> lines;

    private StrategyStatusSnapshot(List<String> lines) {
        this.lines = Collections.unmodifiableList(new ArrayList<String>(lines));
    }

    /**
     * 从影子局面生成只读行。
     *
     * @param state 主线程捕获的 {@link SimState}；{@code null} 返回空快照（面板只显示标题）
     */
    public static StrategyStatusSnapshot from(SimState state) {
        List<String> out = new ArrayList<String>();
        if (state == null) {
            return new StrategyStatusSnapshot(out);
        }
        appendPotions(state, out);
        appendRelics(state, out);
        appendGrowth(state, out);
        return new StrategyStatusSnapshot(out);
    }

    /** 只读行，按展示顺序。 */
    public List<String> lines() {
        return lines;
    }

    // ------------------------------------------------------------------ 药水

    private static void appendPotions(SimState state, List<String> out) {
        // 药水槽是定长列表，空格是 null；potionSlots 与 potions.size() 正常一致，
        // 取最大值是为了「刚构造的 SimState（没 capture 过）」也能显示满槽。
        int slots = Math.max(state.potions.size(), state.potionSlots);
        int empty = 0;
        for (int i = 0; i < slots; i++) {
            String id = i < state.potions.size() ? state.potions.get(i) : null;
            if (id == null || EMPTY_SLOT_ID.equals(id)) {
                empty++;
                continue;
            }
            out.add(potionLine(i + 1, id));
        }
        if (empty > 0) {
            out.add("药空槽 ×" + empty);
        }
    }

    private static String potionLine(int slot, String id) {
        if (!Potions.canUse(id)) {
            // 被动药（瓶中仙子）：原版 canUse() 直接 false，点不动。
            // 🔴 不能显示默认 9 HP 门槛 —— 那会让人以为「喝掉它能省 9 血」。
            return "药[" + slot + "] " + id + " · 被动 · 无主动使用门槛";
        }
        String shape;
        if (Potions.needsTarget(id)) {
            shape = "主动/目标";
        } else if (Potions.needsChoice(id)) {
            shape = "主动/选牌";
        } else {
            shape = "主动";
        }
        return "药[" + slot + "] " + id + " · " + shape
                + " · 门槛 " + PotionValuation.strategicHpCost(id) + " HP";
    }

    // ------------------------------------------------------------------ 遗物

    private static void appendRelics(SimState state, List<String> out) {
        if (state.relics.isEmpty()) {
            out.add("遗物 无");
        } else {
            StringBuilder sb = new StringBuilder("遗物 ");
            boolean first = true;
            for (java.util.Map.Entry<String, Integer> e : state.relics.entrySet()) {
                if (!first) {
                    sb.append(" · ");
                }
                first = false;
                sb.append(e.getKey()).append('=').append(e.getValue());
            }
            out.add(sb.toString());
        }
        // 数据干净度告警：未镜像遗物会在战斗里自行触发，让后面的每条 diff 都变噪声。
        // 这是诊断，不是蓝本的「遗物目标策略」。
        List<String> missing = Coverage.unmirroredRelics(state);
        if (missing != null && !missing.isEmpty()) {
            StringBuilder sb = new StringBuilder("未镜像遗物 ")
                    .append(missing.size()).append(": ");
            for (int i = 0; i < missing.size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(missing.get(i));
            }
            out.add(sb.toString());
        }
    }

    // ------------------------------------------------------------------ 成长

    private static void appendGrowth(SimState state, List<String> out) {
        StringBuilder sb = new StringBuilder("成长 hp/max ")
                .append(state.hp).append('/').append(state.maxHp);
        int shown = 0;
        for (String power : GROWTH_POWERS) {
            Integer amount = state.playerPowers.get(power);
            if (amount == null || amount <= 0) {
                continue;
            }
            sb.append(" · ").append(power).append('=').append(amount);
            shown++;
        }
        if (shown == 0) {
            sb.append(" · 无关键成长层数");
        }
        out.add(sb.toString());
    }
}
