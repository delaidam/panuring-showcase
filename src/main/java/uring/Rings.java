package uring;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

import static java.lang.foreign.ValueLayout.JAVA_INT;

/**
 * An open io_uring: setup syscall + mmap of the SQ/CQ rings and the SQE array.
 * Assumes {@code IORING_FEAT_SINGLE_MMAP} (true on kernel 7.x — verified in
 * ROADMAP.md §1); the legacy dual-mmap path is deliberately not implemented.
 */
public final class Rings implements AutoCloseable {

    private static final long IORING_OFF_SQ_RING = 0x0L;
    private static final long IORING_OFF_SQES = 0x10000000L;

    private static final int IORING_FEAT_SINGLE_MMAP = 1;
    private static final int IORING_SETUP_SQPOLL = 1 << 1;
    private static final int IORING_SETUP_SQ_AFF = 1 << 2;
    private static final int IORING_SETUP_CQSIZE = 1 << 3;

    /**
     * @param sqEntries      requested SQ ring size
     * @param cqEntries      -1 for kernel default (2x sqEntries), otherwise sets
     *                       {@code IORING_SETUP_CQSIZE} (needed for the P1 sweep up
     *                       to K=1024, where the default CQ size would overflow)
     * @param sqpoll         {@code IORING_SETUP_SQPOLL} -- a kernel thread polls the
     *                       SQ ring so submitters mostly never need to call enter()
     *                       (ROADMAP.md §7); unprivileged since kernel 5.11
     * @param sqThreadIdleMs how long (ms) the SQPOLL thread spins before going idle
     *                       and requiring an {@code IORING_ENTER_SQ_WAKEUP}; ignored
     *                       if {@code sqpoll} is false
     * @param sqThreadCpu    pin the SQPOLL thread to this logical CPU
     *                       ({@code IORING_SETUP_SQ_AFF}), or -1 to leave it unpinned;
     *                       ignored if {@code sqpoll} is false
     */
    public record RingOptions(int sqEntries, int cqEntries, boolean sqpoll, int sqThreadIdleMs, int sqThreadCpu) {
        public static RingOptions basic(int sqEntries) {
            return new RingOptions(sqEntries, -1, false, 0, -1);
        }

        public static RingOptions basic(int sqEntries, int cqEntries) {
            return new RingOptions(sqEntries, cqEntries, false, 0, -1);
        }
    }

    private static final int MMAP_PROT = Syscalls.PROT_READ | Syscalls.PROT_WRITE;
    private static final int MMAP_FLAGS = Syscalls.MAP_SHARED | Syscalls.MAP_POPULATE;

    private final Arena arena;
    private final int fd;
    private final MemorySegment ringSeg;
    private final MemorySegment sqesSeg;

    public final int sqEntries;
    public final int cqEntries;
    public final int features;
    public final boolean sqpoll;

    // absolute byte offsets within ringSeg (from io_uring_params, filled in by the kernel at setup)
    public final long sqHeadOff, sqTailOff, sqRingMaskOff, sqArrayOff, sqFlagsOff, sqDroppedOff;
    public final long cqHeadOff, cqTailOff, cqRingMaskOff, cqesOff, cqOverflowOff;

    public Rings(int requestedEntries) {
        this(RingOptions.basic(requestedEntries));
    }

    /**
     * @param requestedCqEntries if &gt;= 0, sets {@code IORING_SETUP_CQSIZE} and
     *                           requests this exact CQ size (needed for the P1
     *                           sweep up to K=1024, where the default 2x-SQ CQ
     *                           would overflow); pass -1 for the kernel default.
     */
    public Rings(int requestedEntries, int requestedCqEntries) {
        this(RingOptions.basic(requestedEntries, requestedCqEntries));
    }

    public Rings(RingOptions opts) {
        int openFd;
        int feat;
        int sqEnt, cqEnt;
        long sqHead, sqTail, sqMask, sqArray, sqFlags, sqDropped;
        long cqHead, cqTail, cqMask, cqes, cqOverflow;
        long ringSize, sqesSize;

        try (Arena setupArena = Arena.ofConfined()) {
            MemorySegment params = setupArena.allocate(IoUringParams.LAYOUT); // zeroed
            int setupFlags = 0;
            if (opts.cqEntries() >= 0) {
                setupFlags |= IORING_SETUP_CQSIZE;
                params.set(JAVA_INT, IoUringParams.OFF_CQ_ENTRIES, opts.cqEntries());
            }
            if (opts.sqpoll()) {
                setupFlags |= IORING_SETUP_SQPOLL;
                params.set(JAVA_INT, IoUringParams.OFF_SQ_THREAD_IDLE, opts.sqThreadIdleMs());
                if (opts.sqThreadCpu() >= 0) {
                    setupFlags |= IORING_SETUP_SQ_AFF;
                    params.set(JAVA_INT, IoUringParams.OFF_SQ_THREAD_CPU, opts.sqThreadCpu());
                }
            }
            params.set(JAVA_INT, IoUringParams.OFF_FLAGS, setupFlags);

            MemorySegment capture = Syscalls.newCaptureSegment(setupArena);
            long rc = Syscalls.ioUringSetup(capture, opts.sqEntries(), params);
            if (rc < 0) {
                throw new UringException("io_uring_setup", Syscalls.errno(capture));
            }
            openFd = (int) rc;

            feat = params.get(JAVA_INT, IoUringParams.OFF_FEATURES);
            if ((feat & IORING_FEAT_SINGLE_MMAP) == 0) {
                Syscalls.close(openFd);
                throw new UnsupportedOperationException(
                        "kernel does not support IORING_FEAT_SINGLE_MMAP; legacy dual-mmap path not implemented");
            }

            sqEnt = params.get(JAVA_INT, IoUringParams.OFF_SQ_ENTRIES);
            cqEnt = params.get(JAVA_INT, IoUringParams.OFF_CQ_ENTRIES);

            sqHead = params.get(JAVA_INT, IoUringParams.SQ_OFF_HEAD);
            sqTail = params.get(JAVA_INT, IoUringParams.SQ_OFF_TAIL);
            sqMask = params.get(JAVA_INT, IoUringParams.SQ_OFF_RING_MASK);
            sqArray = params.get(JAVA_INT, IoUringParams.SQ_OFF_ARRAY);
            sqFlags = params.get(JAVA_INT, IoUringParams.SQ_OFF_FLAGS);
            sqDropped = params.get(JAVA_INT, IoUringParams.SQ_OFF_DROPPED);

            cqHead = params.get(JAVA_INT, IoUringParams.CQ_OFF_HEAD);
            cqTail = params.get(JAVA_INT, IoUringParams.CQ_OFF_TAIL);
            cqMask = params.get(JAVA_INT, IoUringParams.CQ_OFF_RING_MASK);
            cqes = params.get(JAVA_INT, IoUringParams.CQ_OFF_CQES);
            cqOverflow = params.get(JAVA_INT, IoUringParams.CQ_OFF_OVERFLOW);

            ringSize = Math.max(sqArray + (long) sqEnt * 4, cqes + (long) cqEnt * 16);
            sqesSize = (long) sqEnt * 64;
        }

        this.fd = openFd;
        this.features = feat;
        this.sqpoll = opts.sqpoll();
        this.sqEntries = sqEnt;
        this.cqEntries = cqEnt;
        this.sqHeadOff = sqHead;
        this.sqTailOff = sqTail;
        this.sqRingMaskOff = sqMask;
        this.sqArrayOff = sqArray;
        this.sqFlagsOff = sqFlags;
        this.sqDroppedOff = sqDropped;
        this.cqHeadOff = cqHead;
        this.cqTailOff = cqTail;
        this.cqRingMaskOff = cqMask;
        this.cqesOff = cqes;
        this.cqOverflowOff = cqOverflow;

        this.arena = Arena.ofShared();
        long finalRingSize = ringSize;
        long finalSqesSize = sqesSize;
        MemorySegment ringAddr = Syscalls.mmap(ringSize, MMAP_PROT, MMAP_FLAGS, fd, IORING_OFF_SQ_RING);
        this.ringSeg = ringAddr.reinterpret(ringSize, arena, seg -> Syscalls.munmap(seg, finalRingSize));

        MemorySegment sqesAddr = Syscalls.mmap(sqesSize, MMAP_PROT, MMAP_FLAGS, fd, IORING_OFF_SQES);
        this.sqesSeg = sqesAddr.reinterpret(sqesSize, arena, seg -> Syscalls.munmap(seg, finalSqesSize));
    }

    public int fd() {
        return fd;
    }

    /** The whole mmap'd SQ+CQ region (the single-mmap feature merges both into one). */
    public MemorySegment ringSegment() {
        return ringSeg;
    }

    /** The SQE array (sq_entries * 64 B), a separate mmap at {@code IORING_OFF_SQES}. */
    public MemorySegment sqesSegment() {
        return sqesSeg;
    }

    @Override
    public void close() {
        arena.close(); // munmap both regions (registered via the reinterpret cleanup action)
        Syscalls.close(fd);
    }

    /** Step 3 check: setup + mmap works, feature flag present, sizes look sane. */
    public static void main(String[] args) {
        try (Rings r = new Rings(4)) {
            System.out.printf("fd=%d sq_entries=%d cq_entries=%d features=0x%x%n",
                    r.fd(), r.sqEntries, r.cqEntries, r.features);
            System.out.println("IORING_FEAT_SINGLE_MMAP set: " + ((r.features & IORING_FEAT_SINGLE_MMAP) != 0));
            System.out.println("ringSegment size = " + r.ringSegment().byteSize());
            System.out.println("sqesSegment size = " + r.sqesSegment().byteSize() + " (expect sq_entries*64 = " + (r.sqEntries * 64) + ")");
            System.out.println("PASS: Rings setup+mmap OK");
        }
    }
}
