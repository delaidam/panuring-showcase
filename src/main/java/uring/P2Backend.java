package uring;

/**
 * One I/O path from ROADMAP.md section 7.3's "I/O backend" factor:
 * (A) {@link BlockingFileChannelBackend}, (B)/(C)/(D) {@link IoUringBackend}
 * over a plain / SQPOLL-unpinned / SQPOLL-pinned {@link Rings}, and (E) the
 * sharded replication of (B). Each
 * instance belongs to exactly one virtual thread's closed loop (a
 * backend may internally share state — an io_uring ring, a dispatcher —
 * across instances; that shared contention is exactly what P2 measures).
 */
public interface P2Backend extends AutoCloseable {

    /** Performs one random page-aligned READ + checksum iteration. */
    void runOneOp();

    /**
     * Re-measures this backend's own checksum overload, over {@code passes}
     * passes, and returns nanoseconds per pass.
     *
     * <p>The harness calls this once <em>after</em> the closed loop has stopped
     * and every virtual thread has joined, so the result carries no scheduling
     * noise, and compares it with what calibration measured before the loop.
     * The two are the same method measured the same way, so a large divergence
     * means the compiled workload is no longer the one that was calibrated --
     * the defect in RESEARCH.md section 25 left backend A running at roughly
     * 4x its calibrated cost in 39% of C1 cells while every existing gate
     * still passed.
     *
     * <p>Implementations must route to the same {@code Workload.checksum}
     * overload and buffer that {@link #runOneOp()} uses; calling a different
     * one would defeat the check it exists to make.
     */
    long measureChecksumNsPerOp(int passes);

    /**
     * Times {@code passes} complete {@link #runOneOp()} calls with the checksum
     * set to {@code iterations}, and returns nanoseconds per operation.
     *
     * <p>This is the only measurement that sees the workload as the closed loop
     * executes it. RESEARCH.md section 27: the same {@code Workload.checksum}
     * source costs 450 ns per iteration inlined into a local loop, ~530 ns
     * timed inside {@code Workload}, and 1,423 ns called from here -- so a
     * calibration performed anywhere else describes a workload the benchmark
     * does not run. Calibrating through this method makes the declared cost the
     * executed cost by construction.
     *
     * <p>Passing {@code iterations = 0} runs the operation with no checksum
     * work at all, through the identical call site, which is the read-only
     * baseline to subtract.
     *
     * <p>Sets the backend's iteration count as a side effect and leaves it at
     * {@code iterations}. Calibration only: never call it once the closed loop
     * has started.
     */
    long measureOpNsPerOp(int iterations, int passes);

}
