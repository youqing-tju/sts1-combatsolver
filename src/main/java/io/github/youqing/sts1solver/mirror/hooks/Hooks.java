package io.github.youqing.sts1solver.mirror.hooks;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import io.github.youqing.sts1solver.mirror.DamageCalc;
import io.github.youqing.sts1solver.sim.SimCard;
import io.github.youqing.sts1solver.sim.SimMonster;
import io.github.youqing.sts1solver.sim.SimState;

/**
 * 钩子派发器。阶段顺序与派发顺序都照原版，见 {@link HookPhase}。
 *
 * <p>派发顺序 = {@code player.powers} 的**应用顺序**（原版是 {@code for (p : powers)}），
 * 所以影子里的 {@code playerPowers} 必须是保序 Map —— 用 TreeMap 会按 ID 排序，
 * 把「金属化先给格挡、再生后回血」这类顺序语义悄悄改掉。
 */
public final class Hooks {

    /** 减益白名单：只有这些状态被打到敌人身上时才会触发「残虐天性」。 */
    private static final Set<String> DEBUFFS = new HashSet<String>(Arrays.asList(
            "Vulnerable", "Weakened", "Frail", "Poison", "Entangled", "No Draw",
            "Confused", "Constricted", "Hex", "Mark", "Shackled", "Lose Strength",
            "Lose Dexterity", "Slow", "Weak"));

    private Hooks() {
    }

    /** 跑完一个阶段的全部能力钩子。 */
    public static void firePhase(SimState state, HookPhase phase) {
        // 先快照：钩子可能新增 power（恶魔形态 +力量），原版那些是走 action 在循环之后生效的，
        // 直接遍历会被 ConcurrentModificationException 打断，也会让新加的 power 吃到本阶段。
        ArrayList<Map.Entry<String, Integer>> snapshot =
                new ArrayList<Map.Entry<String, Integer>>(state.playerPowers.entrySet());
        HookContext ctx = HookContext.none();
        for (Map.Entry<String, Integer> e : snapshot) {
            PowerHooks.Handler handler = PowerHooks.lookup(e.getKey(), phase);
            if (handler == null) {
                continue;
            }
            // 层数要用「当前」值：前一个钩子可能改写它（炸弹倒计时）
            Integer current = state.playerPowers.get(e.getKey());
            if (current == null) {
                continue;   // 前一个钩子把它移除了
            }
            handler.fire(state, current, ctx);
        }
    }

    /** 一张牌结算完（原版 UseCardAction 末尾）。 */
    public static void onCardUsed(SimState state, SimCard card, int targetIndex) {
        HookContext ctx = HookContext.none();
        ctx.card = card;
        ctx.targetIndex = targetIndex;
        for (Map.Entry<String, Integer> e
                : new ArrayList<Map.Entry<String, Integer>>(state.playerPowers.entrySet())) {
            PowerHooks.Handler handler = PowerHooks.lookup(e.getKey(), HookPhase.USE_CARD);
            Integer current = state.playerPowers.get(e.getKey());
            if (handler != null && current != null) {
                handler.fire(state, current, ctx);
            }
        }
        RelicHooks.onCardUsed(state, card);
    }

    /**
     * 一个状态被施加到怪物身上（原版 ApplyPowerAction → onApplyPower）。
     * 只有减益才触发；未知 ID 一律当增益处理，不猜。
     */
    public static void onPowerApplied(SimState state, String powerId, int amount,
                                      SimMonster target) {
        if (target == null || amount == 0 || !isDebuff(powerId, amount)) {
            return;
        }
        HookContext ctx = HookContext.none();
        ctx.appliedPower = powerId;
        ctx.appliedAmount = amount;
        ctx.appliedTo = target;
        for (Map.Entry<String, Integer> e
                : new ArrayList<Map.Entry<String, Integer>>(state.playerPowers.entrySet())) {
            PowerHooks.Handler handler = PowerHooks.lookup(e.getKey(), HookPhase.APPLY_POWER);
            Integer current = state.playerPowers.get(e.getKey());
            if (handler != null && current != null) {
                handler.fire(state, current, ctx);
            }
        }
    }

    /**
     * 获得格挡的唯一入口：加完立即触发 onGainedBlock（尖刺外壳）。
     * 任何绕过这里的格挡都不会触发尖刺外壳，所以所有加格挡的路径都要走它。
     */
    public static void gainBlock(SimState state, int amount) {
        if (amount <= 0) {
            return;
        }
        state.block += amount;
        HookContext ctx = HookContext.none();
        ctx.gainedBlock = amount;
        for (Map.Entry<String, Integer> e
                : new ArrayList<Map.Entry<String, Integer>>(state.playerPowers.entrySet())) {
            PowerHooks.Handler handler = PowerHooks.lookup(e.getKey(), HookPhase.GAINED_BLOCK);
            Integer current = state.playerPowers.get(e.getKey());
            if (handler != null && current != null) {
                handler.fire(state, current, ctx);
            }
        }
    }

    private static boolean isDebuff(String id, int amount) {
        if (DamageCalc.STRENGTH.equals(id) || DamageCalc.DEXTERITY.equals(id)) {
            return amount < 0;      // 缴械那种「力量 -2」按减益算
        }
        return DEBUFFS.contains(id);
    }
}
