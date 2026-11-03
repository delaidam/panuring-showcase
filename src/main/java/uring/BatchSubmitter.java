package uring;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.VarHandle;

import static java.lang.foreign.ValueLayout.JAVA_INT;

/**
 * Batches N submissions into a single {@code io_uring_enter} call, built
 * directly on {@link Rings} rather than the {@link Uring} facade: L3/P1
 * require the prepare/enter/reap phases to be individually zero-allocation
 * on the hot path (P1 measures exactly those frames under Epsilon GC —
 * see ROADMAP.md §6.5 — where any stray allocation is an OOM, not just
 * noise). {@link Uring}'s HashMap-based in-flight tracking doesn't
 * satisfy that, so this class keeps no per-op bookkeeping of its own:
 * correlation between submit and reap is by sequential {@code user_data}
 * alone (ROADMAP.md §5 step 2), tracked by the caller.
 *
 * <p>Buffers are not this class's concern — {@link #prepare} takes a
 * caller-supplied {@link SqePreparer} so the same submitter works for
 * NOPs (the soak test) or real I/O.
 */
public final class BatchSubmitter implements AutoCloseable {

    private static final int IORING_ENTER_GETEVENTS = 1;

    private static final VarHandle INT_VH = JAVA_INT.varHandle();

    @FunctionalInterface
    public interface SqePreparer {
        void prepare(MemorySegment sqe, long userData);
    }

    @FunctionalInterface
    public interface CqeConsumer {
        void accept(long userData, int res, int flags);
    }

    /** No-op by default; JMH measures without an observer attached (dead-code elimination) — sanity tracing only. */
    public interface SubmitObserver {
        default void onPrepared(long t) {
        }

        default void onEntered(long t) {
        }

        default void onReaped(long t) {
        }
    }

    private static final SubmitObserver NO_OP_OBSERVER = new SubmitObserver() {
    };

    private final Rings rings;
    private final MemorySegment ringSeg;
    private final MemorySegment sqesSeg;
    private final int sqMask;
    private final int cqMask;

    // pre-allocated once so submit() never allocates on the hot path
    private final Arena captureArena = Arena.ofConfined();
    private final MemorySegment captureState = Syscalls.newCaptureSegment(captureArena);

    private long nextUserData = 0;
    private int pending = 0;
    private SubmitObserver observer = NO_OP_OBSERVER;

    public BatchSubmitter(Rings rings) {
        this.rings = rings;
        this.ringSeg = rings.ringSegment();
        this.sqesSeg = rings.sqesSegment();
        this.sqMask = ringSeg.get(JAVA_INT, rings.sqRingMaskOff);
        this.cqMask = ringSeg.get(JAVA_INT, rings.cqRingMaskOff);
    }

    public void observer(SubmitObserver observer) {
        this.observer = observer == null ? NO_OP_OBSERVER : observer;
    }

    /**
     * Fills up to {@code k} SQE slots (bounded by free space: {@code sqEntries - (tail - kernelHead)}),
     * assigning each a monotonically increasing {@code user_data} and
     * handing the slot to {@code preparer} to fill in. Returns the
     * number actually prepared (&lt;= k); the kernel does not see any of
     * them until {@link #submit}. Zero allocations on the hot path
     * (assuming a non-capturing {@code preparer}) — this is the exact
     * frame P1 measures as FFM marshaling cost.
     */
    public int prepare(int k, SqePreparer preparer) {
        int publishedTail = ringSeg.get(JAVA_INT, rings.sqTailOff);
        int kernelHead = (int) INT_VH.getAcquire(ringSeg, rings.sqHeadOff);
        int free = rings.sqEntries - ((publishedTail + pending) - kernelHead);
        int n = Math.min(k, free);
        for (int i = 0; i < n; i++) {
            int idx = (publishedTail + pending + i) & sqMask;
            MemorySegment slot = sqesSeg.asSlice((long) idx * Sqe.SIZE, Sqe.SIZE);
            long userData = nextUserData++;
            preparer.prepare(slot, userData);
            ringSeg.set(JAVA_INT, rings.sqArrayOff + (long) idx * 4, idx);
        }
        pending += n;
        observer.onPrepared(n);
        return n;
    }

    /**
     * Publishes {@code toSubmit} previously prepared SQEs (release-store
     * on SQ tail) and calls {@code io_uring_enter}, waiting for at least
     * {@code minComplete} CQEs. Returns the number of SQEs the kernel
     * consumed — this can be less than {@code toSubmit} if an SQE was
     * invalid; check {@link Rings#sqDroppedOff} in that case (ROADMAP.md
     * §5 step 3). Reuses a pre-allocated capture-state segment, so this
     * does not allocate either.
     */
    public int submit(int toSubmit, int minComplete) {
        if (toSubmit > pending) {
            throw new IllegalArgumentException("toSubmit=" + toSubmit + " exceeds pending=" + pending);
        }
        int tail = ringSeg.get(JAVA_INT, rings.sqTailOff);
        INT_VH.setRelease(ringSeg, rings.sqTailOff, tail + toSubmit);
        pending -= toSubmit;

        long rc = Syscalls.ioUringEnter(captureState, rings.fd(), toSubmit, minComplete, IORING_ENTER_GETEVENTS);
        if (rc < 0) {
            throw new UringException("io_uring_enter", Syscalls.errno(captureState));
        }
        observer.onEntered(rc);
        return (int) rc;
    }

    /**
     * Drains every CQE currently visible, invoking {@code consumer} for
     * each. Does not assume completion order — NOPs happen to complete
     * in-order, file I/O does not (ROADMAP.md §5 step 4); {@code user_data}
     * is the only correlation key.
     */
    public int reap(CqeConsumer consumer) {
        int head = ringSeg.get(JAVA_INT, rings.cqHeadOff);
        int count = 0;
        while (true) {
            int tail = (int) INT_VH.getAcquire(ringSeg, rings.cqTailOff);
            if (head == tail) {
                break;
            }
            long off = rings.cqesOff + (long) (head & cqMask) * Cqe.SIZE;
            MemorySegment cqe = ringSeg.asSlice(off, Cqe.SIZE);
            consumer.accept(Cqe.userData(cqe), Cqe.res(cqe), Cqe.flags(cqe));
            head++;
            count++;
        }
        INT_VH.setRelease(ringSeg, rings.cqHeadOff, head);
        observer.onReaped(count);
        return count;
    }

    @Override
    public void close() {
        captureArena.close();
    }
}
