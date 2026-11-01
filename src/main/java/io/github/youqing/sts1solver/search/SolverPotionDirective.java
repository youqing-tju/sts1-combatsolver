package io.github.youqing.sts1solver.search;

/**
 * 单个药水槽的指令（蓝本 {@code SolverPotionDirective}）。
 *
 * <p>{@link #SMART} 是「没有覆盖」的状态：持久化时会**删掉**该槽的记录，
 * 而不是存一条 SMART —— 否则同一格换了药水之后，旧记录会被误读成「用户设过什么」。
 */
public enum SolverPotionDirective {
    SMART,
    DISABLED,
    FORCE;

    public static SolverPotionDirective parse(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        for (SolverPotionDirective directive : values()) {
            if (directive.name().equalsIgnoreCase(trimmed)) {
                return directive;
            }
        }
        return null;
    }
}
