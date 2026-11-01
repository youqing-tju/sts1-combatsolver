package io.github.youqing.sts1solver.verify;

/**
 * 战斗内「已承诺卖血」与累计掉血的**主线程**记账
 * （蓝本 {@code Runtime/BattleDamageTracker.cs:7-128}）。
 *
 * <p>为什么放在执行层而不是搜索层：这些数来自**真实发生**的血量变化，
 * 只有主线程能看见；后台搜索只接收一份不可变快照（{@link Snapshot}），不读真机对象。
 *
 * <p>它补的是「跨回合重搜会忘账」这个洞：每回合重新搜索时，{@code FutureSoldHp} 从零开始，
 * 于是「上一回合已经卖掉的 12 点血」被同一场战斗里的下一次决策当成没发生过。
 * 蓝本的做法是记住回合开始时 HP 与那条路线计划卖多少，回合真正翻页时
 * 结算 {@code min(planned, actual)} 并累加进 committed。
 *
 * <p>翻译方式：蓝本直接读 {@code CombatState} 与战斗历史；我们把它拆成一个
 * 「喂数据」的状态机（{@link #observe(int, int, int)}），这样单测不需要真机、主线程只做转发。
 */
public final class BattleDamageTracker {

    private static Integer lastObservedHp;
    private static Integer combatStartHp;
    private static int hpLostSoFar;
    private static int soldHpCommitted;
    private static Integer plannedTurn;
    private static int plannedTurnStartHp;
    private static int plannedSoldHp;
    private static int potionsUsedSoFar;
    private static int hpRecoveredOrGainedSoFar;

    private BattleDamageTracker() {
    }

    /** 一场战斗开始（蓝本 {@code Begin}）：把上一场的账本清干净再记起点血量。 */
    public static void begin(int startHp) {
        reset();
        combatStartHp = Integer.valueOf(startHp);
        lastObservedHp = Integer.valueOf(startHp);
    }

    /** 新战斗 / 离开房间时清零（蓝本 {@code Reset}）。 */
    public static void reset() {
        lastObservedHp = null;
        combatStartHp = null;
        hpLostSoFar = 0;
        soldHpCommitted = 0;
        plannedTurn = null;
        plannedTurnStartHp = 0;
        plannedSoldHp = 0;
        potionsUsedSoFar = 0;
        hpRecoveredOrGainedSoFar = 0;
    }

    /**
     * 主线程每帧喂一次真实局面。
     *
     * @param currentHp   玩家当前血量
     * @param turn        当前回合号
     * @param potionsUsed 本场已经喝掉的药水数（调用方从腰带/历史里数）
     */
    public static Snapshot observe(int currentHp, int turn, int potionsUsed) {
        if (combatStartHp == null) {
            begin(currentHp);
        }
        if (plannedTurn != null && turn > plannedTurn.intValue()) {
            int actualLoss = Math.max(0, plannedTurnStartHp - currentHp);
            int committed = Math.min(plannedSoldHp, actualLoss);
            soldHpCommitted += committed;
            clearPlan();
        }
        int observedDrop = lastObservedHp == null ? 0
                : Math.max(0, lastObservedHp.intValue() - currentHp);
        hpLostSoFar += observedDrop;
        lastObservedHp = Integer.valueOf(currentHp);
        potionsUsedSoFar = Math.max(potionsUsedSoFar, potionsUsed);
        hpRecoveredOrGainedSoFar =
                recoveredOrGainedForDisplay(combatStartHp.intValue(), currentHp, hpLostSoFar);
        return new Snapshot(hpLostSoFar, soldHpCommitted, potionsUsedSoFar,
                hpRecoveredOrGainedSoFar);
    }

    /** 登记本回合那条计划**打算**卖多少血（蓝本 {@code RegisterPlan}）。 */
    public static void registerPlan(int turn, int plannedSoldHpForTurn, int turnStartHp) {
        plannedTurn = Integer.valueOf(turn);
        plannedTurnStartHp = turnStartHp;
        plannedSoldHp = Math.max(0, plannedSoldHpForTurn);
    }

    /** 清掉「本回合计划」这一组状态（蓝本 {@code ClearPlan}）。 */
    private static void clearPlan() {
        plannedTurn = null;
        plannedTurnStartHp = 0;
        plannedSoldHp = 0;
    }

    /**
     * 蓝本 {@code RecoveredOrGainedForDisplay}：净回血 = 累计掉血 + 当前血 − 起始血。
     *
     * <p>它是**展示**口径（含涨最大生命顺带加的血），不参与排序。
     */
    static int recoveredOrGainedForDisplay(int startHp, int currentHp, int observedLoss) {
        return Math.max(0, observedLoss + currentHp - startHp);
    }

    /** 不可变快照：后台 / 最终排序只读这一份。 */
    public static final class Snapshot {
        public final int hpLostSoFar;
        public final int soldHpCommitted;
        public final int potionsUsedSoFar;
        public final int hpRecoveredOrGainedSoFar;

        Snapshot(int hpLostSoFar, int soldHpCommitted, int potionsUsedSoFar,
                 int hpRecoveredOrGainedSoFar) {
            this.hpLostSoFar = hpLostSoFar;
            this.soldHpCommitted = soldHpCommitted;
            this.potionsUsedSoFar = potionsUsedSoFar;
            this.hpRecoveredOrGainedSoFar = hpRecoveredOrGainedSoFar;
        }

        @Override
        public String toString() {
            return "BattleDamageSnapshot{lost=" + hpLostSoFar + " sold=" + soldHpCommitted
                    + " potions=" + potionsUsedSoFar + " recovered=" + hpRecoveredOrGainedSoFar
                    + "}";
        }
    }
}
