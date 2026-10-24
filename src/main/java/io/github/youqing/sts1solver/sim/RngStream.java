package io.github.youqing.sts1solver.sim;

import com.badlogic.gdx.math.RandomXS128;

/**
 * A forkable, snapshot-able RNG stream that behaves exactly like
 * {@code com.megacrit.cardcrawl.random.Random}.
 *
 * <p>Two facts were verified against the shipped bytecode and against a live capture
 * (see {@code RngStreamTest} and {@code docs/dev-notes.md}):
 * <ul>
 *   <li>{@code Random.random(n)} is <b>inclusive</b>: it calls {@code nextInt(n + 1)}.</li>
 *   <li>The whole stream is defined by the two 128-bit states, so a snapshot of
 *       {@code (s0, s1, counter)} can be restored exactly — no object reference needed.</li>
 * </ul>
 *
 * <p>This class owns its own {@link RandomXS128}, so a search branch can advance it on a
 * background thread without touching the live game.
 */
public final class RngStream {

    private final RandomXS128 xs;
    private int counter;

    private RngStream(long state0, long state1, int counter) {
        this.xs = new RandomXS128(state0, state1);
        this.counter = counter;
    }

    /**
     * {@code com.megacrit.cardcrawl.random.Random.randomBoolean()} ——
     * 计数器 +1，底层走 {@code RandomXS128.nextBoolean()}（字节码直读）。
     */
    public boolean randomBoolean() {
        counter++;
        return xs.nextBoolean();
    }

    /**
     * {@code Random.randomBoolean(float chance)} —— 计数器 +1，
     * 判据是 {@code nextFloat() < chance}（字节码：{@code fcmpg; ifge → false}）。
     */
    public boolean randomBoolean(float chance) {
        counter++;
        return xs.nextFloat() < chance;
    }

    /** Rebuild a stream from a snapshot. */
    public static RngStream from(long state0, long state1, int counter) {
        return new RngStream(state0, state1, counter);
    }

    /** Independent copy at the current position; advancing one never moves the other. */
    public RngStream fork() {
        return new RngStream(state0(), state1(), counter);
    }

    /** Same contract as the game: uniform in {@code [0, n]} (inclusive). */
    public int random(int n) {
        counter++;
        return xs.nextInt(n + 1);
    }

    /**
     * {@code com.megacrit.cardcrawl.random.Random.randomLong()} —— 只递增一次计数器，
     * 底层是 {@code RandomXS128.nextLong()}。洗牌就是拿它当种子（见 {@link Shuffle}）。
     */
    public long randomLong() {
        counter++;
        return xs.nextLong();
    }

    public long state0() {
        return xs.getState(0);
    }

    public long state1() {
        return xs.getState(1);
    }

    public int counter() {
        return counter;
    }

    /** Compact form for logs and snapshots. */
    public String snapshot() {
        return state0() + "," + state1() + "," + counter;
    }
}
