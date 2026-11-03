package uring;

import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;

import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * P2 workload building block (ROADMAP.md section 7.2): a cheap,
 * calibratable checksum standing in for "~5us of CPU work" per
 * iteration, so the I/O:CPU ratio is a tunable parameter rather than
 * whatever a specific hash function happens to cost.
 *
 * <p><b>The work is a chain of dependent loads, on purpose.</b> Each load's
 * address comes from the previous load's value, so its cost is set by
 * load-to-use latency on an L1-resident buffer and cannot be changed by
 * unrolling or vectorising. The earlier shape -- an independent sum over the
 * buffer repeated N times -- was none of those things: RESEARCH.md section 27
 * measured the identical source at 450, ~530 and 1,423 ns per iteration
 * depending only on the call site, so the calibrated cost never described what
 * the backends ran. Measured across call sites the walk varies by 1.00x where
 * the old shape varied by 3.16x (section 28).
 *
 * <p>{@code iterations} is therefore a number of dependent steps, not a number
 * of passes over the buffer, and calibrated values are correspondingly larger.
 *
 * <p>Calibrate through the backend that will execute the work, via
 * {@code P2Backend.measureOpNsPerOp}, not through the helpers here.
 */
public final class Workload {

    private static volatile long calibrationSink;

    private Workload() {
    }

    /**
     * Publishes a checksum result so the JIT cannot prove it is dead.
     *
     * <p>{@link #checksum} is pure, so a caller that discards its result lets
     * C2 delete the work the benchmark exists to perform. Backends accumulate
     * into a plain field and hand it here when they close: one volatile write
     * per run rather than per operation, which keeps the hot loop free of
     * memory barriers while still escaping the value.
     */
    public static void consume(long value) {
        calibrationSink = value;
    }

    /**
     * Burns a calibrated amount of CPU by walking {@code iterations}
     * dependent loads through the buffer, each load's address taken from the
     * previous load's value.
     */
    public static long checksum(MemorySegment buf, int len, int iterations) {
        int mask = words(len) - 1;
        int idx = 0;
        long sum = 0;
        for (int step = 0; step < iterations; step++) {
            long v = buf.get(JAVA_LONG, (long) idx << 3);
            idx = (int) (v & mask);
            sum += v;
        }
        return sum;
    }

    /**
     * Number of 8-byte words in {@code len}, rounded down to a power of two so
     * the walk can index with a mask.
     */
    private static int words(int len) {
        int words = Math.max(2, len / 8);
        return Integer.highestOneBit(words);
    }

    /** Same walk over a {@link ByteBuffer}, for the blocking-FileChannel backend. */
    public static long checksum(ByteBuffer buf, int iterations) {
        int base = buf.position();
        int mask = words(buf.remaining()) - 1;
        int idx = 0;
        long sum = 0;
        for (int step = 0; step < iterations; step++) {
            long v = buf.getLong(base + (idx << 3));
            idx = (int) (v & mask);
            sum += v;
        }
        return sum;
    }

    /**
     * Cost of one checksum pass at a given iteration count, for the search in
     * {@link #calibrateWith}. Each implementation calls exactly one
     * {@code checksum} overload, so a calibration never executes a memory API
     * the caller does not go on to measure -- see the class javadoc.
     */
    @FunctionalInterface
    private interface PassCost {
        long nsPerPass(int iterations, int passes);
    }

    /** Finds the iteration count that makes {@link #checksum} take roughly {@code targetNanos} on this machine. */
    public static int calibrate(MemorySegment buf, int len, long targetNanos) {
        return calibrateWith((iterations, passes) -> measureNsPerOp(buf, len, iterations, passes), targetNanos);
    }

    /**
     * Calibrates the {@link ByteBuffer} overload, for backends that measure it.
     *
     * <p>The buffer is read with absolute {@code getLong}, so its position is
     * not disturbed; the caller's window ({@code position}..{@code limit}) is
     * what gets checksummed, exactly as in the measured loop.
     */
    public static int calibrate(ByteBuffer buf, long targetNanos) {
        return calibrateWith((iterations, passes) -> measureNsPerOp(buf, iterations, passes), targetNanos);
    }

    private static int calibrateWith(PassCost cost, long targetNanos) {
        // Force the checksum result to remain observable while the JVM warms
        // the exact buffer kind used by the benchmark.  Starting the search
        // at iteration one before this warm-up measures the interpreter, not
        // the workload that the timed loop will execute.
        long previous = Long.MIN_VALUE;
        int stableWindows = 0;
        final int windowPasses = 100_000;
        for (int window = 0; window < 4 && stableWindows < 2; window++) {
            long nsPerPass = cost.nsPerPass(1, windowPasses);
            if (previous != Long.MIN_VALUE
                    && Math.abs(nsPerPass - previous) <= Math.max(1L, previous / 20)) {
                stableWindows++;
            } else {
                stableWindows = 0;
            }
            previous = nsPerPass;
        }

        long warmNsPerPass = previous > 0 ? previous : cost.nsPerPass(1, 1_000);
        int iterations = (int) Math.max(2L,
                Math.min(1_000_000L, Math.round((double) targetNanos / warmNsPerPass)));

        // Refine from the measured batch cost instead of stopping at the first
        // power of two. A power-of-two search can jump from below the target
        // to more than 2x the target, which is incompatible with the declared
        // ±20% validity gate on machines with different per-pass costs.
        for (int round = 0; round < 3; round++) {
            long measured = cost.nsPerPass(iterations, 1_000);
            int refined = (int) Math.max(2L, Math.min(1_000_000L,
                    Math.round((double) iterations * targetNanos / measured)));
            if (refined == iterations) {
                break;
            }
            iterations = refined;
        }

        // Check a small neighborhood to reduce timer noise and integer-rounding
        // error. P2Harness applies the hard final gate to the selected result.
        int bestIterations = iterations;
        long bestDelta = Long.MAX_VALUE;
        int first = Math.max(2, iterations - 2);
        int last = Math.min(1_000_000, iterations + 2);
        for (int candidate = first; candidate <= last; candidate++) {
            // Use the same long batch scale as the harness gate so candidate
            // selection is not fooled by the short search sample.
            long measured = cost.nsPerPass(candidate, 50_000);
            long delta = Math.abs(measured - targetNanos);
            if (delta < bestDelta) {
                bestIterations = candidate;
                bestDelta = delta;
            }
        }
        return bestIterations;
    }

    /** Measures a warmed checksum batch and returns nanoseconds per checksum pass. */
    public static long measureNsPerOp(MemorySegment buf, int len, int iterations, int passes) {
        if (iterations < 1 || passes < 1) {
            throw new IllegalArgumentException("iterations and passes must be positive");
        }
        long start = System.nanoTime();
        long sum = 0;
        for (int pass = 0; pass < passes; pass++) {
            sum ^= checksum(buf, len, iterations);
        }
        calibrationSink = sum;
        long elapsed = System.nanoTime() - start;
        return Math.max(1L, Math.round((double) elapsed / passes));
    }

    /** {@link ByteBuffer} counterpart of {@link #measureNsPerOp(MemorySegment, int, int, int)}. */
    public static long measureNsPerOp(ByteBuffer buf, int iterations, int passes) {
        if (iterations < 1 || passes < 1) {
            throw new IllegalArgumentException("iterations and passes must be positive");
        }
        long start = System.nanoTime();
        long sum = 0;
        for (int pass = 0; pass < passes; pass++) {
            sum ^= checksum(buf, iterations);
        }
        calibrationSink = sum;
        long elapsed = System.nanoTime() - start;
        return Math.max(1L, Math.round((double) elapsed / passes));
    }
}
