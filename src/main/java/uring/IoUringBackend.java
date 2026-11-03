package uring;

import java.lang.foreign.MemorySegment;
import java.util.concurrent.ThreadLocalRandom;

/**
 * ROADMAP.md section 7.3 backends (B)/(C)/(D) — plain io_uring, SQPOLL
 * unpinned, SQPOLL pinned are all this same class; the difference is
 * entirely in how the shared {@link Rings}/{@link Uring}/{@link Dispatcher}
 * were configured (see {@link Rings.RingOptions}). One instance per
 * virtual thread (its own read buffer), sharing the dispatcher/ring
 * across all of them — that shared contention is what P2 measures.
 */
public final class IoUringBackend implements P2Backend {

    private final Dispatcher dispatcher;
    private final int fd;
    private final MemorySegment buf;
    private final int len;
    private int checksumIterations;
    private final long maxOffset;
    private long checksumSink;

    public IoUringBackend(Dispatcher dispatcher, Uring ring, int fd, int len, int checksumIterations) {
        this(dispatcher, ring, fd, len, checksumIterations, len);
    }

    public IoUringBackend(Dispatcher dispatcher, Uring ring, int fd, int len, int checksumIterations,
                          long fixtureSize) {
        this.dispatcher = dispatcher;
        this.fd = fd;
        this.len = len;
        this.checksumIterations = checksumIterations;
        this.buf = ring.allocateBuffer(len, len);
        if (fixtureSize < len || fixtureSize % len != 0) {
            throw new IllegalArgumentException("fixtureSize must be a multiple of len and at least len");
        }
        this.maxOffset = fixtureSize - len;
    }

    @Override
    public void runOneOp() {
        long offset = ThreadLocalRandom.current().nextLong(maxOffset / len + 1) * len;
        int res = dispatcher.submitAndAwait((sqe, userData) -> Sqe.prepRead(sqe, fd, buf, len, offset, userData));
        if (res != len) {
            throw new RuntimeException("IoUringBackend: expected res=" + len + ", got " + res);
        }
        checksumSink += Workload.checksum(buf, len, checksumIterations);
    }

    @Override
    public long measureOpNsPerOp(int iterations, int passes) {
        if (iterations < 0 || passes < 1) {
            throw new IllegalArgumentException("iterations must be non-negative and passes positive");
        }
        this.checksumIterations = iterations;
        long start = System.nanoTime();
        for (int pass = 0; pass < passes; pass++) {
            runOneOp();
        }
        long elapsed = System.nanoTime() - start;
        Workload.consume(checksumSink);
        return Math.max(1L, elapsed / passes);
    }

    @Override
    public long measureChecksumNsPerOp(int passes) {
        // Same call-site shape as runOneOp; see RESEARCH.md section 27.
        long start = System.nanoTime();
        long sum = 0;
        for (int pass = 0; pass < passes; pass++) {
            sum += Workload.checksum(buf, len, checksumIterations);
        }
        Workload.consume(sum);
        return Math.max(1L, (System.nanoTime() - start) / passes);
    }

    @Override
    public void close() {
        // Escapes the accumulated result: without this the checksum is dead
        // code and C2 is free to delete the workload (RESEARCH.md section 26).
        Workload.consume(checksumSink);
        // buffer lifetime is owned by the shared Uring instance; nothing per-backend to release
    }
}
