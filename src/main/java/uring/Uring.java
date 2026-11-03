package uring;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.VarHandle;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * Reusable facade over {@link Rings}: reserve SQE slots, publish + wait
 * for completions, drain CQEs. Callers fill each reserved slot via a
 * {@code Sqe.prepXxx(slot, ..., userData)} call before the next
 * {@link #submitAndWait(int)} — user_data is mandatory on every prep
 * call and is the only correlation key between submit and reap (L3
 * batching and P1 both build on this).
 *
 * <p>For ordinary unregistered READ/WRITE, buffers should be allocated via
 * {@link #allocateBuffer} rather than a caller-owned arena: the kernel can
 * still be writing after {@code io_uring_enter} returns. For fixed buffers,
 * prefer {@link #openRegisteredBufferArena(int, long, long)}: it owns the
 * allocation and encodes the required drain → unregister → free sequence.
 * {@link #close()} drains all in-flight ops (tracked in {@link #inFlight})
 * before releasing legacy buffer memory — see ROADMAP.md §4 step 5 and the
 * {@link DangerBufferFreeDemo} experiment for what happens when that
 * invariant is violated on purpose.
 */
public final class Uring implements AutoCloseable {

    private static final int IORING_ENTER_GETEVENTS = 1;
    private static final int IORING_ENTER_SQ_WAKEUP = 1 << 1;
    private static final int IORING_SQ_NEED_WAKEUP = 1;

    private static final int IORING_REGISTER_BUFFERS = 0;
    private static final int IORING_UNREGISTER_BUFFERS = 1;
    private static final int IORING_REGISTER_FILES = 2;
    private static final int IORING_UNREGISTER_FILES = 3;

    private static final VarHandle INT_VH = JAVA_INT.varHandle();

    private record InFlightOp(long userData, int opcode, OpToken token,
                              RingTracer tracer, long traceSequence, Doctor doctor) {
    }

    private final Rings rings;
    private final MemorySegment ringSeg;
    private final MemorySegment sqesSeg;
    private final Arena bufferArena = Arena.ofShared();
    private final Map<Long, InFlightOp> inFlight = new HashMap<>();
    /** Typed tokens prepared but not yet translated into {@link #inFlight} by SQ-tail publication. */
    private final Map<Long, OpToken> pendingTokens = new HashMap<>();
    private int pending = 0;
    private long nextTokenUserData = 0x4000_0000_0000_0000L;
    private boolean buffersRegistered = false;
    private boolean filesRegistered = false;
    /** The safe fixed-buffer table, if this ring currently has one. Exactly one table exists in io_uring v1. */
    private RegisteredBufferArena registeredBufferArena;
    /** A live dispatcher owns concurrent access to {@link #inFlight}; see {@link #attachDispatcher(Dispatcher)}. */
    private volatile Dispatcher activeDispatcher;
    /** Optional Phase-4 instrumentation; absent by default so ordinary runs do no trace allocation. */
    private volatile RingTracer tracer;
    /** Optional non-throwing invariant checker for integration diagnostics. */
    private volatile Doctor doctor;
    /** Last cumulative kernel CQ-overflow counter reported to the installed doctor. */
    private long lastReportedCqOverflow;
    private boolean closed = false;

    private Uring(Rings rings) {
        this.rings = rings;
        this.ringSeg = rings.ringSegment();
        this.sqesSeg = rings.sqesSegment();
    }

    public static Uring open(int entries) {
        return new Uring(new Rings(entries));
    }

    public static Uring open(Rings.RingOptions opts) {
        return new Uring(new Rings(opts));
    }

    public int fd() {
        return rings.fd();
    }

    /** Escape hatch for callers that need setup details (register, feature flags, ...). */
    public Rings rings() {
        return rings;
    }

    /**
     * Installs or removes a bounded Phase-4 trace sink. Passing {@code null}
     * restores the normal no-trace fast path; already in-flight operations
     * retain the sink that observed their submission so their CQEs remain
     * correlated even if the configuration changes mid-run.
     */
    public void installTracer(RingTracer tracer) {
        this.tracer = tracer;
    }

    /**
     * Installs or removes the opt-in {@link Doctor} invariant sink. The doctor
     * records findings rather than throwing from a CQ reaper; it is intended
     * for diagnostics, not as a replacement for normal operation errors.
     */
    public void installDoctor(Doctor doctor) {
        this.doctor = doctor;
        lastReportedCqOverflow = 0;
    }

    /**
     * Allocates a buffer whose lifetime is owned by this {@code Uring}
     * instance, not the caller — required for any buffer used in a
     * ring READ/WRITE (see the class-level note on in-flight safety).
     */
    public MemorySegment allocateBuffer(long byteSize, long byteAlignment) {
        ensureOpen();
        return bufferArena.allocate(byteSize, byteAlignment);
    }

    /**
     * Opens the safe fixed-buffer API for this ring. The returned object owns
     * both its shared {@link Arena} and the kernel registration lease. Its
     * {@link RegisteredBufferArena#close()} executes the only valid teardown
     * order: drain in-flight CQEs, unregister the fixed-buffer table, then
     * close the Arena.
     *
     * <p>io_uring currently has one fixed-buffer table per ring, so this is
     * exclusive with {@link #registerBuffers(MemorySegment...)}. It is also
     * deliberately unavailable while a {@link Dispatcher} is already live:
     * registration itself must not race that dispatcher's CQ-owner lifecycle.
     * It may be opened before a Dispatcher is attached; typed Dispatcher
     * operations then retain their buffer borrow until their terminal CQE and
     * the Dispatcher must be closed before this arena is closed.
     */
    public synchronized RegisteredBufferArena openRegisteredBufferArena(
            int bufferCount, long bufferSize, long bufferAlignment) {
        ensureOpen();
        if (activeDispatcher != null) {
            throw new IllegalStateException("cannot open RegisteredBufferArena while a Dispatcher is active");
        }
        if (registeredBufferArena != null || buffersRegistered) {
            throw new IllegalStateException("the ring already has a registered fixed-buffer table");
        }
        return RegisteredBufferArena.open(this, bufferCount, bufferSize, bufferAlignment);
    }

    /**
     * Reserves the next SQE slot (tail + however many are already
     * pending in this batch) and wires it into the submission array.
     * The kernel does not see it until {@link #submitAndWait(int)}
     * publishes the tail.
     */
    public MemorySegment sqeSlot() {
        int tail = ringSeg.get(JAVA_INT, rings.sqTailOff) + pending;
        int kernelHead = (int) INT_VH.getAcquire(ringSeg, rings.sqHeadOff);
        int used = tail - kernelHead;
        Doctor currentDoctor = doctor;
        if (currentDoctor != null && used + 1 >= rings.sqEntries) {
            currentDoctor.onSqPressure(used + 1, rings.sqEntries);
        }
        if (used >= rings.sqEntries) {
            throw new IllegalStateException("submission queue is full: used=" + used
                    + " entries=" + rings.sqEntries + "; reap/submit before reserving another SQE");
        }
        int mask = ringSeg.get(JAVA_INT, rings.sqRingMaskOff);
        int idx = tail & mask;
        MemorySegment slot = sqesSeg.asSlice((long) idx * Sqe.SIZE, Sqe.SIZE);
        ringSeg.set(JAVA_INT, rings.sqArrayOff + (long) idx * 4, idx);
        pending++;
        return slot;
    }

    /**
     * True if {@link #sqeSlot()} can reserve one more entry right now. Lets a
     * caller that holds a lock across many concurrent submitters (see
     * {@link Dispatcher}) wait for space instead of hitting the exception
     * {@code sqeSlot()} throws when the ring is already full.
     */
    boolean hasSqeSpace() {
        int tail = ringSeg.get(JAVA_INT, rings.sqTailOff) + pending;
        int kernelHead = (int) INT_VH.getAcquire(ringSeg, rings.sqHeadOff);
        return (tail - kernelHead) < rings.sqEntries;
    }

    /** Release-publishes every SQE reserved since the last call and registers them as in-flight. Returns the count published. */
    private int publishPending() {
        int toSubmit = pending;
        int tail = ringSeg.get(JAVA_INT, rings.sqTailOff);
        int mask = ringSeg.get(JAVA_INT, rings.sqRingMaskOff);

        // user_data is the sole kernel correlation key. Reject an overwrite
        // before publishing the tail: otherwise a duplicate can make drain
        // believe an older buffer/token has already completed.
        for (int i = 0; i < toSubmit; i++) {
            int idx = (tail + i) & mask;
            MemorySegment slot = sqesSeg.asSlice((long) idx * Sqe.SIZE, Sqe.SIZE);
            long userData = Sqe.userData(slot);
            if (inFlight.containsKey(userData) || appearsEarlierInPendingBatch(tail, mask, i, userData)) {
                throw new IllegalStateException("duplicate live user_data: 0x" + Long.toHexString(userData));
            }
        }
        for (int i = 0; i < toSubmit; i++) {
            int idx = (tail + i) & mask;
            MemorySegment slot = sqesSeg.asSlice((long) idx * Sqe.SIZE, Sqe.SIZE);
            long userData = Sqe.userData(slot);
            OpToken token = pendingTokens.remove(userData);
            int opcode = Sqe.opcode(slot);
            RingTracer currentTracer = tracer;
            long traceSequence = currentTracer == null
                    ? RingTracer.NO_SEQUENCE : currentTracer.submit(userData, opcode);
            Doctor currentDoctor = doctor;
            if (currentDoctor != null) {
                long bufferId = token == null || token.buffer() == null
                        ? 0 : 1L + Integer.toUnsignedLong(System.identityHashCode(token.buffer()));
                currentDoctor.onSubmit(userData, opcode, bufferId, 0, false);
            }
            inFlight.put(userData, new InFlightOp(userData, opcode, token,
                    currentTracer, traceSequence, currentDoctor));
        }
        INT_VH.setRelease(ringSeg, rings.sqTailOff, tail + toSubmit);
        pending = 0;
        return toSubmit;
    }

    /** Allocation-free duplicate check; P2 normally submits one SQE, while raw batches remain small. */
    private boolean appearsEarlierInPendingBatch(int tail, int mask, int exclusiveEnd, long userData) {
        for (int earlier = 0; earlier < exclusiveEnd; earlier++) {
            int earlierIdx = (tail + earlier) & mask;
            MemorySegment earlierSlot = sqesSeg.asSlice((long) earlierIdx * Sqe.SIZE, Sqe.SIZE);
            if (Sqe.userData(earlierSlot) == userData) {
                return true;
            }
        }
        return false;
    }

    /**
     * Publishes every SQE reserved since the last call (release-store on
     * SQ tail) and calls {@code io_uring_enter}, waiting for at least
     * {@code minComplete} CQEs. Returns the number of SQEs the kernel
     * consumed (not the number of CQEs reaped — see {@link #reap}).
     */
    public int submitAndWait(int minComplete) {
        if (pending == 0) {
            throw new IllegalStateException("no SQEs prepared since the last submit");
        }
        int toSubmit = publishPending();

        long rc;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment capture = Syscalls.newCaptureSegment(arena);
            rc = Syscalls.ioUringEnter(capture, fd(), toSubmit, minComplete, IORING_ENTER_GETEVENTS);
            if (rc < 0) {
                throw new UringException("io_uring_enter", Syscalls.errno(capture));
            }
        }
        return (int) rc;
    }

    /**
     * The syscall side of an SQPOLL-aware submit. {@link #publishAndPlan}
     * runs under the caller's ring lock (touches shared Java state);
     * {@link #executePlan} runs OUTSIDE the lock (only touches the fd)
     * so a slow {@code io_uring_enter} does not serialize concurrent
     * submitters. The three cases:
     * <ul>
     *   <li>non-SQPOLL: always {@code enter(fd, toSubmit, 0, GETEVENTS)};</li>
     *   <li>SQPOLL, poller still polling: no syscall at all;</li>
     *   <li>SQPOLL, {@code IORING_SQ_NEED_WAKEUP} set (poller went idle
     *       after {@code sq_thread_idle}): {@code enter(fd, 0, 0, SQ_WAKEUP)}
     *       to nudge it awake. Missing this check is the classic bug:
     *       the ring silently stalls after an idle period (ROADMAP.md §7.2).</li>
     * </ul>
     */
    public record SubmitPlan(int enterFlags, int toSubmit, String label) {
        public static final SubmitPlan NONE = new SubmitPlan(0, 0, "none");

        public boolean requiresEnter() {
            return enterFlags != 0;
        }
    }

    /**
     * Publishes every SQE reserved since the last {@link #sqeSlot} calls
     * (release-store on SQ tail, in-flight bookkeeping) and returns the
     * {@link SubmitPlan} describing what — if any — {@code io_uring_enter}
     * the caller now needs to run. MUST run under the caller's ring lock;
     * the returned plan MUST then be handed to {@link #executePlan} after
     * releasing that lock, so the enter syscall itself does not serialize
     * concurrent submitters (the lock-scope artifact identified in
     * RESEARCH.md §12).
     */
    public SubmitPlan publishAndPlan() {
        if (pending == 0) {
            throw new IllegalStateException("no SQEs prepared since the last submit");
        }
        int toSubmit = publishPending();

        if (!rings.sqpoll) {
            return new SubmitPlan(IORING_ENTER_GETEVENTS, toSubmit, "io_uring_enter");
        }
        int sqFlags = ringSeg.get(JAVA_INT, rings.sqFlagsOff);
        if ((sqFlags & IORING_SQ_NEED_WAKEUP) != 0) {
            return new SubmitPlan(IORING_ENTER_SQ_WAKEUP, 0, "io_uring_enter(SQ_WAKEUP)");
        }
        return SubmitPlan.NONE;
    }

    /**
     * Executes the enter syscall described by {@code plan}, if any.
     * Safe to call OUTSIDE any ring lock: the ring's tail has already
     * been release-stored by {@link #publishAndPlan}, and
     * {@code io_uring_enter} is thread-safe for the same fd, so multiple
     * submitters can be inside it concurrently.
     */
    public void executePlan(SubmitPlan plan) {
        if (!plan.requiresEnter()) {
            return;
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment capture = Syscalls.newCaptureSegment(arena);
            long rc = Syscalls.ioUringEnter(capture, fd(), plan.toSubmit(), 0, plan.enterFlags());
            if (rc < 0) {
                throw new UringException(plan.label(), Syscalls.errno(capture));
            }
        }
    }

    /**
     * Single-threaded convenience: {@link #publishAndPlan} + {@link #executePlan}
     * fused. Not safe under concurrent submitters — for those, split the
     * two calls with the ring lock released between them (see {@link Dispatcher}).
     */
    public void submitSqpollAware() {
        executePlan(publishAndPlan());
    }

    /**
     * Submits a typed one-shot operation and returns a completion/lifetime
     * token. This direct-ring form is intentionally exclusive with the
     * current {@link Dispatcher}; use {@link Dispatcher#submit(OneShotOp)}
     * when that dispatcher owns the CQ reaper.
     */
    public synchronized OpToken submit(OneShotOp operation) {
        return submitTyped(Objects.requireNonNull(operation, "operation"), OpToken.Kind.ONE_SHOT);
    }

    /**
     * Submits a typed potentially-multishot operation. The returned token
     * retains any managed buffer until a CQE without {@link Cqe#FLAG_MORE}.
     * Use {@link Dispatcher#submit(MultiShotOp)} while a dispatcher owns the
     * CQ reaper.
     */
    public synchronized OpToken submit(MultiShotOp operation) {
        return submitTyped(Objects.requireNonNull(operation, "operation"), OpToken.Kind.MULTI_SHOT);
    }

    /**
     * Creates a typed one-shot timeout whose kernel timespec is owned by this
     * ring's legacy buffer arena. It is primarily the deterministic building
     * block for cancellation and lifecycle tests; the segment remains live
     * until {@link #close()} even if a submission syscall reports uncertainty.
     */
    public OneShotOp timeout(Duration duration) {
        ensureOpen();
        Objects.requireNonNull(duration, "duration");
        if (duration.isNegative() || duration.isZero()) {
            throw new IllegalArgumentException("timeout must be positive: " + duration);
        }
        long seconds = duration.getSeconds();
        int nanos = duration.getNano();
        MemorySegment timespec = bufferArena.allocate(16, 8);
        timespec.set(JAVA_LONG, 0, seconds);
        timespec.set(JAVA_LONG, 8, nanos);
        return OneShotOp.timeout(timespec);
    }

    /**
     * Submits a narrowly scoped {@code IORING_OP_ASYNC_CANCEL} for one live
     * typed token. The returned control token's CQE says only whether the
     * cancel request matched; callers must still await the target token's
     * terminal CQE before treating its resources as reusable.
     */
    public synchronized OpToken cancel(OpToken target) {
        Objects.requireNonNull(target, "target");
        if (target.isTerminal()) {
            throw new IllegalStateException("cannot cancel an already terminal token");
        }
        if (!tracks(target)) {
            throw new IllegalArgumentException("target token is not live on this Uring instance");
        }
        return submit((OneShotOp) (sqe, cancelToken) ->
                Sqe.prepAsyncCancel(sqe, target.userData(), cancelToken.userData()));
    }

    private OpToken submitTyped(UringOp operation, OpToken.Kind kind) {
        ensureOpen();
        if (activeDispatcher != null) {
            throw new IllegalStateException("typed direct submission cannot race an active Dispatcher");
        }
        if (pending != 0) {
            throw new IllegalStateException("typed submission requires an empty raw pending-SQE batch");
        }

        RegisteredBufferArena.Buffer buffer = operation.buffer();
        if (buffer != null && (registeredBufferArena == null || !registeredBufferArena.owns(buffer))) {
            throw new IllegalArgumentException("typed operation buffer is not owned by this ring's RegisteredBufferArena");
        }

        long userData = nextTypedUserData();
        OpToken token = new OpToken(userData, kind, buffer);
        boolean reserved = false;
        boolean published = false;
        try {
            token.installLifecycleLease(operation.acquireLifecycleLease(token));
            MemorySegment sqe = sqeSlot();
            reserved = true;
            operation.prepare(sqe, token);
            if (Sqe.userData(sqe) != userData) {
                throw new IllegalStateException("typed operation replaced its token user_data");
            }
            pendingTokens.put(userData, token);
            SubmitPlan plan = publishAndPlan();
            published = true;
            executePlan(plan);
            return token;
        } catch (RuntimeException failure) {
            if (!published) {
                pendingTokens.remove(userData);
                if (reserved) {
                    discardOnlyTypedReservation();
                }
                token.abortBeforePublication();
                throw failure;
            }
            // Tail publication makes ownership ambiguous: do not release the
            // token's managed buffer simply because enter() failed. Returning
            // it in the exception gives callers a concrete object to retain
            // and drive to a terminal CQE.
            throw new OpSubmissionException(token, failure);
        } catch (Error failure) {
            if (!published) {
                pendingTokens.remove(userData);
                if (reserved) {
                    discardOnlyTypedReservation();
                }
                token.abortBeforePublication();
            }
            throw failure;
        }
    }

    /**
     * Dispatcher-only half of a typed submission. The caller must hold its
     * {@code submitLock}: this method prepares exactly one SQE and installs
     * the token in {@link #pendingTokens}, but does not publish the SQ tail.
     * That split lets Dispatcher register its live entry before publication,
     * closing the otherwise possible CQE-before-registry race while keeping
     * every enter syscall outside the P2 submit critical section.
     */
    OpToken prepareTypedForDispatcher(Dispatcher dispatcher, UringOp operation,
                                      OpToken.Kind kind, long userData) {
        ensureOpen();
        requireDispatcherOwner(dispatcher);
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(kind, "kind");
        if (pending != 0) {
            throw new IllegalStateException("typed Dispatcher submission requires an empty pending-SQE batch");
        }
        if (inFlight.containsKey(userData) || pendingTokens.containsKey(userData)) {
            throw new IllegalStateException("duplicate live typed user_data: 0x" + Long.toHexString(userData));
        }

        RegisteredBufferArena.Buffer buffer = operation.buffer();
        if (buffer != null && (registeredBufferArena == null || !registeredBufferArena.owns(buffer))) {
            throw new IllegalArgumentException("typed operation buffer is not owned by this ring's RegisteredBufferArena");
        }

        OpToken token = new OpToken(userData, kind, buffer);
        token.attachDispatcherOwner(dispatcher);
        boolean reserved = false;
        boolean tokenInstalled = false;
        try {
            token.installLifecycleLease(operation.acquireLifecycleLease(token));
            MemorySegment sqe = sqeSlot();
            reserved = true;
            operation.prepare(sqe, token);
            if (Sqe.userData(sqe) != userData) {
                throw new IllegalStateException("typed operation replaced its token user_data");
            }
            pendingTokens.put(userData, token);
            tokenInstalled = true;
            return token;
        } catch (RuntimeException | Error failure) {
            if (tokenInstalled) {
                pendingTokens.remove(userData, token);
            }
            if (reserved) {
                discardOnlyTypedReservation();
            }
            token.abortBeforePublication();
            throw failure;
        }
    }

    /**
     * Dispatcher-only publication half paired with
     * {@link #prepareTypedForDispatcher(Dispatcher, UringOp, OpToken.Kind, long)}.
     * The returned plan must be executed after the Dispatcher releases its
     * submit lock.
     */
    SubmitPlan publishPreparedTypedForDispatcher(Dispatcher dispatcher, OpToken token) {
        requireDispatcherOwner(dispatcher);
        Objects.requireNonNull(token, "token");
        if (pending != 1 || pendingTokens.get(token.userData()) != token) {
            throw new IllegalStateException("Dispatcher typed publication does not own exactly one prepared token SQE");
        }
        return publishAndPlan();
    }

    /** Rolls back an un-published Dispatcher typed SQE and releases its Java-side lease. */
    void discardPreparedTypedForDispatcher(Dispatcher dispatcher, OpToken token) {
        requireDispatcherOwner(dispatcher);
        Objects.requireNonNull(token, "token");
        if (pending != 1 || !pendingTokens.remove(token.userData(), token)) {
            throw new IllegalStateException("Dispatcher typed rollback does not own exactly one prepared token SQE");
        }
        pending = 0;
        token.abortBeforePublication();
    }

    private void requireDispatcherOwner(Dispatcher dispatcher) {
        Objects.requireNonNull(dispatcher, "dispatcher");
        if (activeDispatcher != dispatcher) {
            throw new IllegalStateException("this Dispatcher does not own the ring's CQ lifecycle");
        }
    }

    private long nextTypedUserData() {
        for (long attempts = 0; attempts < Long.MAX_VALUE; attempts++) {
            long candidate = nextTokenUserData++;
            if (candidate == -1L) {
                continue; // Dispatcher shutdown marker, reserved even though direct submit excludes Dispatcher today.
            }
            if (!inFlight.containsKey(candidate) && !pendingTokens.containsKey(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("exhausted typed user_data space");
    }

    /** Rolls back the one unpublished SQE reserved by {@link #submitTyped(UringOp, OpToken.Kind)}. */
    private void discardOnlyTypedReservation() {
        if (pending != 1) {
            throw new IllegalStateException("typed submission rollback found pending=" + pending);
        }
        pending = 0;
    }

    /**
     * Dispatcher-only rollback for a preparer that failed before SQ-tail
     * publication. It is intentionally narrower than a public batch reset:
     * callers cannot use it to erase another submitter's work.
     */
    void discardUnpublishedDispatcherSqe() {
        if (pending != 1) {
            throw new IllegalStateException("dispatcher SQE rollback found pending=" + pending);
        }
        pending = 0;
    }

    /** Package-private collision probe; Dispatcher calls it while holding its ring lock. */
    boolean hasInFlightUserData(long userData) {
        return inFlight.containsKey(userData) || pendingTokens.containsKey(userData);
    }

    /** Drains every CQE currently visible in the ring, invoking {@code consumer} for each. */
    public int reap(CqeConsumer consumer) {
        Objects.requireNonNull(consumer, "consumer");
        int head = ringSeg.get(JAVA_INT, rings.cqHeadOff);
        int mask = ringSeg.get(JAVA_INT, rings.cqRingMaskOff);
        int count = 0;
        while (true) {
            int tail = (int) INT_VH.getAcquire(ringSeg, rings.cqTailOff);
            if (head == tail) {
                break;
            }
            long off = rings.cqesOff + (long) (head & mask) * Cqe.SIZE;
            MemorySegment cqe = ringSeg.asSlice(off, Cqe.SIZE);
            long userData = Cqe.userData(cqe);
            int res = Cqe.res(cqe);
            int flags = Cqe.flags(cqe);
            Completion completion = new Completion(userData, res, flags);
            InFlightOp operation = inFlight.get(userData);
            try {
                if (operation != null) {
                    long nextTraceSequence = operation.traceSequence();
                    if (operation.tracer() != null) {
                        nextTraceSequence = operation.tracer().completeAndContinue(
                                operation.traceSequence(), userData, operation.opcode(), res, flags);
                    }
                    // Record the terminal boundary before token release: a
                    // normal managed-buffer release following this CQE must
                    // not look like a premature return to Doctor.
                    if (operation.doctor() != null) {
                        operation.doctor().onCompletion(userData, res, flags);
                    }
                    if (operation.token() != null) {
                        operation.token().onCompletion(completion);
                    }
                    // A multishot parent keeps ownership of its buffer until
                    // the terminal CQE clears MORE. Keep its trace sequence
                    // current so every later CQE remains observable.
                    if (Cqe.hasMore(flags)) {
                        inFlight.put(userData, new InFlightOp(userData, operation.opcode(), operation.token(),
                                operation.tracer(), nextTraceSequence, operation.doctor()));
                    } else {
                        inFlight.remove(userData);
                    }
                }
                consumer.accept(userData, res, flags);
            } catch (RuntimeException | Error failure) {
                // The CQE has been observed. Publish its head advance even
                // when instrumentation/token routing/user code fails,
                // otherwise every later drain replays it and wedges the CQ.
                head++;
                INT_VH.setRelease(ringSeg, rings.cqHeadOff, head);
                throw failure;
            }
            head++;
            count++;
        }
        INT_VH.setRelease(ringSeg, rings.cqHeadOff, head);
        Doctor currentDoctor = doctor;
        if (currentDoctor != null) {
            long overflow = Integer.toUnsignedLong(ringSeg.get(JAVA_INT, rings.cqOverflowOff));
            if (overflow > lastReportedCqOverflow) {
                lastReportedCqOverflow = overflow;
                currentDoctor.onCqOverflow(overflow);
            }
        }
        return count;
    }

    /** Convenience form that preserves all CQE fields in a copied value for typed-operation code. */
    public int reapCompletions(Consumer<Completion> consumer) {
        Objects.requireNonNull(consumer, "consumer");
        return reap((userData, res, flags) -> consumer.accept(new Completion(userData, res, flags)));
    }

    /**
     * Direct-ring convenience that drives CQ reaping until {@code token}
     * reaches its terminal CQE. This is intentionally unavailable while a
     * {@link Dispatcher} is active, because the dispatcher is then the sole
     * CQ reaper. For a multishot token this returns its final completion; use
     * {@link OpToken#awaitNext()} after externally driven reaps to observe
     * every intermediate completion.
     */
    public Completion await(OpToken token) {
        Objects.requireNonNull(token, "token");
        if (activeDispatcher != null) {
            throw new IllegalStateException("Uring.await cannot race an active Dispatcher");
        }
        if (!token.isTerminal() && !tracks(token)) {
            throw new IllegalArgumentException("token is not live on this Uring instance");
        }
        while (!token.isTerminal()) {
            int reaped = reap((userData, res, flags) -> {
            });
            if (reaped == 0 && !token.isTerminal()) {
                waitForAtLeastOneCompletion("io_uring_enter(await)");
            }
        }
        return token.awaitTerminal();
    }

    /**
     * Drives direct-ring reaping until one CQE for {@code token} is available.
     * Unlike {@link #await(OpToken)}, it returns an intermediate multishot
     * completion and leaves a parent live while {@link Completion#more()} is
     * true. It is unavailable while a Dispatcher owns the CQ reaper.
     */
    public Completion awaitNext(OpToken token) {
        Objects.requireNonNull(token, "token");
        if (activeDispatcher != null) {
            throw new IllegalStateException("Uring.awaitNext cannot race an active Dispatcher");
        }
        if (!token.isTerminal() && !tracks(token)) {
            throw new IllegalArgumentException("token is not live on this Uring instance");
        }
        while (true) {
            Completion next = token.pollCompletion();
            if (next != null) {
                return next;
            }
            if (token.isTerminal()) {
                return null;
            }
            int reaped = reap((userData, res, flags) -> {
            });
            if (reaped == 0 && !token.isTerminal()) {
                waitForAtLeastOneCompletion("io_uring_enter(awaitNext)");
            }
        }
    }

    private boolean tracks(OpToken token) {
        if (pendingTokens.containsValue(token)) {
            return true;
        }
        for (InFlightOp operation : inFlight.values()) {
            if (operation.token() == token) {
                return true;
            }
        }
        return false;
    }

    private void waitForAtLeastOneCompletion(String label) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment capture = Syscalls.newCaptureSegment(arena);
            long rc = Syscalls.ioUringEnter(capture, fd(), 0, 1, IORING_ENTER_GETEVENTS);
            if (rc < 0) {
                throw new UringException(label, Syscalls.errno(capture));
            }
        }
    }

    /**
     * Drains every op submitted but not yet reaped, blocking on
     * {@code io_uring_enter} as needed — called by {@link #close()}
     * before the buffer arena is released so no buffer dies while the
     * kernel might still be writing into it.
     */
    private void drainInFlight() {
        cancelTypedMultishotsForDrain();
        while (!inFlight.isEmpty()) {
            int reaped = reap((userData, res, flags) -> {
            });
            if (reaped == 0) {
                try (Arena arena = Arena.ofConfined()) {
                    MemorySegment capture = Syscalls.newCaptureSegment(arena);
                    long rc = Syscalls.ioUringEnter(capture, fd(), 0, 1, IORING_ENTER_GETEVENTS);
                    if (rc < 0) {
                        throw new UringException("io_uring_enter(drain)", Syscalls.errno(capture));
                    }
                }
            }
        }
    }

    /**
     * A direct multishot parent may otherwise wait forever during close. Raw
     * escape-hatch multishots remain the caller's responsibility, but typed
     * tokens supply enough correlation to issue the required async cancel.
     */
    private void cancelTypedMultishotsForDrain() {
        ArrayList<OpToken> targets = new ArrayList<>();
        for (InFlightOp operation : inFlight.values()) {
            OpToken token = operation.token();
            if (token != null && token.kind() == OpToken.Kind.MULTI_SHOT && !token.isTerminal()) {
                targets.add(token);
            }
        }
        for (OpToken token : targets) {
            if (!token.isTerminal() && tracks(token)) {
                cancel(token);
            }
        }
    }

    /**
     * Registers buffers with the kernel ({@code IORING_REGISTER_BUFFERS})
     * so ring ops can reference them by index via {@code IORING_OP_READ_FIXED}
     * instead of a raw pointer. Registered buffers are pinned in RAM
     * (mlock'd) — bounded by {@code RLIMIT_MEMLOCK}; a failure here is
     * usually that limit, not a bug (see the hint printed below).
     */
    public void registerBuffers(MemorySegment... buffers) {
        ensureOpen();
        Objects.requireNonNull(buffers, "buffers");
        if (registeredBufferArena != null) {
            throw new IllegalStateException("a RegisteredBufferArena owns this ring's fixed-buffer table; close it instead");
        }
        if (buffersRegistered) {
            throw new IllegalStateException("buffers already registered");
        }
        if (buffers.length == 0) {
            throw new IllegalArgumentException("at least one buffer is required");
        }
        MemorySegment iovecs = bufferArena.allocate(16L * buffers.length, 8);
        for (int i = 0; i < buffers.length; i++) {
            Objects.requireNonNull(buffers[i], "buffers[" + i + "]");
            iovecs.set(ADDRESS, i * 16L, buffers[i]);
            iovecs.set(JAVA_LONG, i * 16L + 8, buffers[i].byteSize());
        }
        registerBuffersFromIovecs(iovecs, buffers.length);
        buffersRegistered = true;
    }

    /** Registers an iovec table already owned by a {@link RegisteredBufferArena}. Package-private by design. */
    synchronized void installRegisteredBufferArena(RegisteredBufferArena owner, MemorySegment iovecs, int bufferCount) {
        ensureOpen();
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(iovecs, "iovecs");
        if (activeDispatcher != null) {
            throw new IllegalStateException("cannot install RegisteredBufferArena while a Dispatcher is active");
        }
        if (registeredBufferArena != null || buffersRegistered) {
            throw new IllegalStateException("the ring already has a registered fixed-buffer table");
        }
        registerBuffersFromIovecs(iovecs, bufferCount);
        buffersRegistered = true;
        registeredBufferArena = owner;
    }

    private void registerBuffersFromIovecs(MemorySegment iovecs, int bufferCount) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment capture = Syscalls.newCaptureSegment(arena);
            long rc = Syscalls.ioUringRegister(capture, fd(), IORING_REGISTER_BUFFERS, iovecs, bufferCount);
            if (rc < 0) {
                int errno = Syscalls.errno(capture);
                if (errno == Errno.ENOMEM || errno == Errno.EPERM) {
                    System.err.println("hint: io_uring_register(REGISTER_BUFFERS) failed with " + Errno.nameOf(errno)
                            + " -- registered buffers are pinned in RAM (mlock); current RLIMIT_MEMLOCK = "
                            + Syscalls.getrlimitMemlockBytes() + " bytes (ulimit -l). Raise the limit or "
                            + "register fewer/smaller buffers.");
                }
                throw new UringException("io_uring_register(REGISTER_BUFFERS)", errno);
            }
        }
    }

    /**
     * Low-level escape hatch for legacy callers. It cannot safely unregister
     * a {@link RegisteredBufferArena}; close that owner instead.
     */
    public void unregisterBuffers() {
        if (registeredBufferArena != null) {
            throw new IllegalStateException("a RegisteredBufferArena owns this ring's fixed-buffer table; close it instead");
        }
        unregisterBuffersInternal();
    }

    private void unregisterBuffersInternal() {
        if (!buffersRegistered) {
            return;
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment capture = Syscalls.newCaptureSegment(arena);
            long rc = Syscalls.ioUringRegister(capture, fd(), IORING_UNREGISTER_BUFFERS, MemorySegment.NULL, 0);
            if (rc < 0) {
                throw new UringException("io_uring_register(UNREGISTER_BUFFERS)", Syscalls.errno(capture));
            }
        }
        buffersRegistered = false;
    }

    /**
     * Called only by {@link RegisteredBufferArena#close()}. Do not expose
     * this as a general drain API: it uses the single-threaded raw-ring
     * lifecycle and must never race a {@link Dispatcher} CQ reaper.
     */
    synchronized void releaseRegisteredBufferArena(RegisteredBufferArena owner) {
        if (registeredBufferArena != owner) {
            throw new IllegalStateException("this RegisteredBufferArena does not own the ring's fixed-buffer table");
        }
        if (activeDispatcher != null) {
            throw new IllegalStateException("close the Dispatcher before closing its RegisteredBufferArena");
        }
        if (pending != 0) {
            throw new IllegalStateException("cannot close RegisteredBufferArena with " + pending
                    + " prepared but unpublished SQE(s); submit and resolve them first");
        }
        // Never put the Arena close in finally: if draining or unregistering
        // fails, keeping its memory alive is the only safe outcome.
        drainInFlight();
        if (owner.hasBorrowedBuffers()) {
            throw new IllegalStateException("typed buffer borrow survived ring drain; refusing unregister/free");
        }
        unregisterBuffersInternal();
        registeredBufferArena = null;
    }

    /**
     * A narrow ownership bridge used by Dispatcher to establish the sole CQ
     * reaper. A managed fixed-buffer arena may already be installed: typed
     * Dispatcher submissions route its token before every CQE consumer and
     * therefore preserve the same terminal-borrow rule. The arena still
     * cannot be closed until this Dispatcher has detached.
     */
    synchronized void attachDispatcher(Dispatcher dispatcher) {
        ensureOpen();
        Objects.requireNonNull(dispatcher, "dispatcher");
        if (activeDispatcher != null) {
            throw new IllegalStateException("a Dispatcher is already active for this ring");
        }
        activeDispatcher = dispatcher;
    }

    synchronized void detachDispatcher(Dispatcher dispatcher) {
        if (activeDispatcher == dispatcher) {
            activeDispatcher = null;
        }
    }

    /**
     * Registers file descriptors with the kernel ({@code IORING_REGISTER_FILES})
     * so ring ops can address them by index (with {@code IOSQE_FIXED_FILE})
     * instead of a raw fd — see {@link Sqe#IOSQE_FIXED_FILE}.
     */
    public void registerFiles(int... fds) {
        if (filesRegistered) {
            throw new IllegalStateException("files already registered");
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment fdArray = arena.allocate(4L * fds.length, 4);
            for (int i = 0; i < fds.length; i++) {
                fdArray.set(JAVA_INT, i * 4L, fds[i]);
            }
            MemorySegment capture = Syscalls.newCaptureSegment(arena);
            long rc = Syscalls.ioUringRegister(capture, fd(), IORING_REGISTER_FILES, fdArray, fds.length);
            if (rc < 0) {
                throw new UringException("io_uring_register(REGISTER_FILES)", Syscalls.errno(capture));
            }
        }
        filesRegistered = true;
    }

    public void unregisterFiles() {
        if (!filesRegistered) {
            return;
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment capture = Syscalls.newCaptureSegment(arena);
            long rc = Syscalls.ioUringRegister(capture, fd(), IORING_UNREGISTER_FILES, MemorySegment.NULL, 0);
            if (rc < 0) {
                throw new UringException("io_uring_register(UNREGISTER_FILES)", Syscalls.errno(capture));
            }
        }
        filesRegistered = false;
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        if (activeDispatcher != null) {
            throw new IllegalStateException("close Dispatcher before closing Uring");
        }
        if (registeredBufferArena != null) {
            registeredBufferArena.close(); // drain -> unregister -> its Arena close
        } else {
            drainInFlight();
            unregisterBuffersInternal();
        }
        unregisterFiles();
        bufferArena.close();
        rings.close();
        closed = true;
    }

    /** Package-private diagnostic for the zero-dependency executable lifecycle demos. */
    int inFlightCount() {
        return inFlight.size();
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("Uring is closed");
        }
    }

    @FunctionalInterface
    public interface CqeConsumer {
        void accept(long userData, int res, int flags);
    }
}
