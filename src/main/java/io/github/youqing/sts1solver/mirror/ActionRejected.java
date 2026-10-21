package io.github.youqing.sts1solver.mirror;

/** Thrown when an action cannot be simulated faithfully. Never swallowed silently. */
public class ActionRejected extends RuntimeException {

    public ActionRejected(String reason) {
        super(reason);
    }
}
