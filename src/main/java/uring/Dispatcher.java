package uring;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

/**
 * One-CQ-reaper dispatcher for many submitting virtual threads.
 *
 * <p>The dispatcher has an explicit lifecycle: {@code OPEN} admits new
 * operations; {@code CLOSING} rejects new work, submits an
 * {@code IORING_OP_ASYNC_CANCEL} for every live request, and drains their
 * terminal CQEs; {@code FAILED} rejects new work and wakes every waiter with
 * the captured failure; {@code CLOSED} has detached from its {@link Uring}.
 * That state machine is deliberately stronger than a boolean {@code running}:
 * a poller failure or a close racing a submit can otherwise leave a parked
 * virtual thread with nobody left able to unpark it.
 *
 * <p><b>Lock-scope discipline:</b> {@link #submitLock} covers only Java ring
 * bookkeeping, SQE reservation/preparation, publication, and CQ reaping.
 * Every {@code io_uring_enter} runs after releasing it. This preserves the P2
 * experiment's SQPOLL-vs-non-SQPOLL comparison: a blocking enter syscall is
 * never accidentally included in the submit critical section.
 */
public final class Dispatcher implements AutoCloseable {

    private static final int IORING_ENTER_GETEVENTS = 1;
    /** Reserved control CQE key; normal dispatcher keys are strictly positive. */
    private static final long WAKEUP_USER_DATA = -1L;

    private enum State {
        OPEN,
        CLOSING,
        FAILED,
        CLOSED
    }

    /** A parked submitter and its one terminal outcome. */
    private static final class Entry {
        private record Outcome(int result, Throwable failure) {
        }

        final long userData;
        /** Non-null for a typed operation whose CQEs are delivered to this token. */
        final OpToken token;
        /** Non-null only for the legacy synchronous raw-SQE surface. */
        final Thread waiter;
        /** Guarded by Dispatcher.submitLock. */
        boolean cancellationRequested;
        private volatile Outcome outcome;

        Entry(long userData, Thread waiter) {
            this(userData, waiter, null);
        }

        Entry(OpToken token) {
            this(Objects.requireNonNull(token, "token").userData(), null, token);
        }

        private Entry(long userData, Thread waiter, OpToken token) {
            this.userData = userData;
            this.waiter = waiter;
            this.token = token;
        }

        void complete(int result) {
            if (token != null) {
                // Uring.reap has already routed the full Completion to the
                // token before Dispatcher.onCompleteLocked is invoked.
                return;
            }
            complete(new Outcome(result, null));
        }

        void fail(Throwable failure) {
            if (token != null) {
                // Waking a typed awaiter must not pretend its kernel request
                // reached a terminal CQE: OpToken preserves its borrow until
                // the eventual real terminal event is drained.
                token.failAwaiters(failure);
                return;
            }
            complete(new Outcome(0, Objects.requireNonNull(failure, "failure")));
        }

        private void complete(Outcome proposed) {
            synchronized (this) {
                if (outcome != null) {
                    return;
                }
                // Publish the outcome before unpark. The volatile write also
                // covers the lock-free fast path in await().
                outcome = proposed;
            }
            LockSupport.unpark(waiter);
        }

        int await() {
            if (token != null) {
                throw new IllegalStateException("typed Dispatcher entries are awaited through OpToken");
            }
            boolean interrupted = false;
            for (;;) {
                Outcome terminal = outcome;
                if (terminal != null) {
                    if (interrupted) {
                        Thread.currentThread().interrupt();
                    }
                    if (terminal.failure() != null) {
                        throw asUnchecked(terminal.failure());
                    }
                    return terminal.result();
                }
                LockSupport.park(this);
                // Leave an interrupt non-fatal for an operation whose kernel
                // lifetime still needs a terminal CQE. Clearing it here avoids
                // a busy park loop; restore it when the outcome is observed.
                if (Thread.interrupted()) {
                    interrupted = true;
                }
            }
        }
    }

    /** Control SQE bookkeeping, kept separate from user waiters. */
    private record CancelRequest(Entry target) {
    }

    private record CloseAdmission(boolean owner, boolean interrupted) {
    }

    private final Uring ring;
    /** Serializes all Java-side interaction with the non-thread-safe ring maps/SQ/CQ. */
    private final Object submitLock = new Object();
    private final Object stateLock = new Object();
    private final Object planCompletionLock = new Object();
    private final Map<Long, Entry> live = new HashMap<>();
    private final Map<Long, CancelRequest> cancelRequests = new HashMap<>();
    /**
     * A plan that has already published an SQ tail but whose submitting thread
     * has not yet returned from enter(). Counted before releasing submitLock so
     * close cannot detach the ring in the small publish-to-enter handoff gap.
     */
    private final AtomicInteger executingPlans = new AtomicInteger();

    private long nextUserData;
    private boolean wakeupQueued;
    private volatile State state = State.OPEN;
    private volatile Throwable failure;
    private boolean closeInProgress;
    private volatile boolean detached;
    private volatile Throwable pollerFailure;
    private final Thread pollerThread;

    public Dispatcher(Uring ring) {
        this.ring = Objects.requireNonNull(ring, "ring");
        this.ring.attachDispatcher(this);
        try {
            this.pollerThread = Thread.ofPlatform().name("dispatcher-poller").start(this::pollLoop);
        } catch (RuntimeException | Error startupFailure) {
            this.ring.detachDispatcher(this);
            throw startupFailure;
        }
    }

    @FunctionalInterface
    public interface SqePrep {
        void prepare(MemorySegment sqe, long userData);
    }

    /**
     * Submits a typed one-shot operation while this Dispatcher remains the
     * ring's sole CQ reaper. The returned token receives its copied terminal
     * CQE through the poller; use {@link #await(OpToken)} to park for it.
     *
     * <p>For a managed fixed buffer, the token's borrow is released by the
     * real terminal CQE before any awaiter is woken. A failed enter after SQ
     * tail publication throws {@link OpSubmissionException} carrying that
     * still-live token rather than silently returning the buffer.
     */
    public OpToken submit(OneShotOp operation) {
        return submitTyped(Objects.requireNonNull(operation, "operation"), OpToken.Kind.ONE_SHOT);
    }

    /**
     * Submits a typed multishot operation while this Dispatcher remains the
     * ring's sole CQ reaper. Every CQE, including each {@code F_MORE} event,
     * is queued on the returned token; {@link #awaitNext(OpToken)} consumes
     * that stream and {@link #await(OpToken)} waits for its terminal CQE.
     *
     * <p>Close issues async cancellation for this parent and does not detach
     * until the non-{@code F_MORE} target CQE has passed through
     * {@link OpToken}. That is what releases a provided-buffer receive lease
     * or a SEND_ZC source-buffer borrow safely.
     */
    public OpToken submit(MultiShotOp operation) {
        return submitTyped(Objects.requireNonNull(operation, "operation"), OpToken.Kind.MULTI_SHOT);
    }

    /** Explicit spelling for call sites that prefer not to overload a typed lambda. */
    public OpToken submitOneShot(OneShotOp operation) {
        return submit(operation);
    }

    /** Explicit spelling for call sites that prefer not to overload a typed lambda. */
    public OpToken submitMultiShot(MultiShotOp operation) {
        return submit(operation);
    }

    /**
     * Parks until a typed token's terminal CQE has been routed by this
     * Dispatcher. This method never reaps the ring itself; invoking
     * {@link Uring#await(OpToken)} while a Dispatcher is active remains
     * deliberately illegal.
     */
    public Completion await(OpToken token) {
        OpToken checked = Objects.requireNonNull(token, "token");
        requireKnownTypedToken(checked);
        return checked.awaitTerminal();
    }

    /**
     * Parks until one queued CQE is available for a typed token. For a
     * multishot token this exposes intermediate {@code F_MORE} completions;
     * it returns {@code null} after the terminal CQE was already consumed.
     * This method also never reaps the ring itself.
     */
    public Completion awaitNext(OpToken token) {
        OpToken checked = Objects.requireNonNull(token, "token");
        requireKnownTypedToken(checked);
        return checked.awaitNext();
    }

    /**
     * Submits a typed {@code IORING_OP_ASYNC_CANCEL} for one of this
     * Dispatcher's live typed tokens. The returned control token reports only
     * whether the cancel lookup matched; callers must still await the target
     * token's own terminal CQE before closing/reusing its resources.
     */
    public OpToken cancel(OpToken target) {
        Objects.requireNonNull(target, "target");
        synchronized (submitLock) {
            requireOpenForSubmissionLocked();
            Entry registered = live.get(target.userData());
            if (registered == null || registered.token != target) {
                throw new IllegalArgumentException("target token is not live on this Dispatcher");
            }
            if (target.isTerminal()) {
                throw new IllegalStateException("cannot cancel an already terminal token");
            }
        }
        // A target can finish just after the validation lock is released; an
        // -ENOENT control CQE is then the documented benign race, exactly as
        // it is for Uring.cancel on the direct path.
        return submit((OneShotOp) (sqe, cancelToken) ->
                Sqe.prepAsyncCancel(sqe, target.userData(), cancelToken.userData()));
    }

    /**
     * Typed submission counterpart to {@link #submitAndAwait(SqePrep)}. Its
     * Java-side setup stays under {@link #submitLock}; {@code io_uring_enter}
     * still runs after that lock is released, preserving the P2 lock-scope
     * invariant for the raw benchmark path.
     */
    private OpToken submitTyped(UringOp operation, OpToken.Kind kind) {
        OpToken token = null;
        Entry entry = null;
        Uring.SubmitPlan plan;
        boolean prepared = false;
        boolean published = false;

        synchronized (submitLock) {
            awaitSqeSpaceLocked();
            long userData = reserveUserDataLocked();
            try {
                token = ring.prepareTypedForDispatcher(this, operation, kind, userData);
                prepared = true;
                entry = new Entry(token);
                // Register before the SQ tail is release-published. The
                // poller cannot lose an immediately completed operation.
                live.put(userData, entry);
                plan = ring.publishPreparedTypedForDispatcher(this, token);
                published = true;
                reservePlanExecutionLocked(plan);
            } catch (RuntimeException | Error prepFailure) {
                if (entry != null) {
                    live.remove(userData, entry);
                }
                if (prepared && !published) {
                    ring.discardPreparedTypedForDispatcher(this, token);
                }
                throw prepFailure;
            }
        }

        try {
            executeReservedPlan(plan);
        } catch (RuntimeException enterFailure) {
            // The tail is visible to the kernel. Keep the typed live entry
            // and make its ownership token observable to the caller.
            failDispatcher(new IllegalStateException(
                    "io_uring_enter failed after typed Dispatcher publication; operation outcome is uncertain",
                    enterFailure));
            throw new OpSubmissionException(token, enterFailure);
        } catch (Error enterFailure) {
            failDispatcher(new IllegalStateException(
                    "io_uring_enter failed after typed Dispatcher publication; operation outcome is uncertain",
                    enterFailure));
            throw enterFailure;
        }
        return token;
    }

    /**
     * Prepares one SQE, publishes it, then parks until its terminal CQE is
     * routed by the sole CQ reaper. Close may return either an ordinary result
     * or {@code -ECANCELED}; a dispatcher failure is surfaced as an
     * {@link IllegalStateException} rather than leaving this thread parked.
     */
    public int submitAndAwait(SqePrep prep) {
        Objects.requireNonNull(prep, "prep");
        Entry entry;
        Uring.SubmitPlan plan;

        synchronized (submitLock) {
            awaitSqeSpaceLocked();
            long userData = reserveUserDataLocked();
            entry = new Entry(userData, Thread.currentThread());
            live.put(userData, entry); // install before publication: an early CQE cannot be lost.

            boolean slotReserved = false;
            boolean published = false;
            try {
                MemorySegment sqe = ring.sqeSlot();
                slotReserved = true;
                prep.prepare(sqe, userData);
                if (Sqe.userData(sqe) != userData) {
                    throw new IllegalStateException("dispatcher SqePrep replaced assigned user_data 0x"
                            + Long.toHexString(userData));
                }
                plan = ring.publishAndPlan();
                published = true;
                reservePlanExecutionLocked(plan);
            } catch (RuntimeException | Error prepFailure) {
                live.remove(userData, entry);
                entry.fail(prepFailure);
                if (slotReserved && !published) {
                    ring.discardUnpublishedDispatcherSqe();
                }
                throw prepFailure;
            }
        }

        try {
            executeReservedPlan(plan);
        } catch (RuntimeException | Error enterFailure) {
            // SQ tail was already published. Keep the live registry intact so
            // close can cancel/drain the operation; merely throwing here would
            // strand all other parked submitters if the poller also fails.
            failDispatcher(new IllegalStateException(
                    "io_uring_enter failed after dispatcher publication; operation outcome is uncertain", enterFailure));
            throw enterFailure;
        }
        return entry.await();
    }

    /** NOP-only convenience wrapper, retained for the G2/P2 smoke checks. */
    public int submitAndAwaitNop() {
        return submitAndAwait(Sqe::prepNop);
    }

    private void pollLoop() {
        try {
            while (true) {
                int reaped;
                boolean stop;
                synchronized (submitLock) {
                    reaped = ring.reap(this::onCompleteLocked);
                    if (reaped > 0) {
                        // A reap frees SQ slots; wake any submitter parked in
                        // awaitSqeSpaceLocked() so it can recheck.
                        submitLock.notifyAll();
                    }
                    stop = shouldStopPollerLocked();
                }
                if (stop) {
                    return;
                }
                if (reaped == 0) {
                    waitForAtLeastOneCompletion();
                }
            }
        } catch (Throwable pollFailure) {
            pollerFailure = pollFailure;
            failDispatcher(new IllegalStateException("dispatcher CQ poller failed", pollFailure));
        }
    }

    /** Called only while submitLock is held, from Uring.reap's consumer. */
    private void onCompleteLocked(long userData, int res, int flags) {
        if (userData == WAKEUP_USER_DATA) {
            wakeupQueued = false;
            return;
        }
        CancelRequest cancellation = cancelRequests.remove(userData);
        if (cancellation != null) {
            if (res < 0) {
                // A failed control request did not establish a durable
                // cancellation lease. Preserve the option for a later close
                // retry rather than permanently marking this live target as
                // already cancelled.
                cancellation.target().cancellationRequested = false;
            }
            // -ENOENT is benign: the target completed before the cancel lookup.
            // Any other failure means close cannot promise that its target will
            // terminate, so release waiters while retaining the live entry for
            // a later safe drain/retry.
            if (res < 0 && res != -Errno.ENOENT) {
                Throwable cancelFailure = markFailed(new IllegalStateException(
                        "async cancellation for user_data=0x" + Long.toHexString(cancellation.target().userData)
                                + " completed with " + Errno.nameOf(-res)));
                failLiveWaitersLocked(cancelFailure);
            }
            return;
        }
        Entry entry = live.get(userData);
        if (entry != null) {
            // The public Dispatcher API currently returns one terminal res,
            // but close must already respect raw multishot SQEs: their first
            // F_MORE CQE is not permission to forget/cancel-free the parent.
            // Phase 2 adds a completion-stream API on top of this registry.
            if (!Cqe.hasMore(flags)) {
                if (entry.token != null && !entry.token.isTerminal()) {
                    throw new IllegalStateException("typed Dispatcher entry 0x"
                            + Long.toHexString(userData)
                            + " reached a terminal CQE before its OpToken was routed");
                }
                live.remove(userData, entry);
                entry.complete(res);
            }
        }
        // Unknown user_data is still intentionally reaped. It can only come
        // from a caller deliberately bypassing Dispatcher; retaining its CQE
        // would wedge the whole ring and make orderly close impossible.
    }

    private boolean shouldStopPollerLocked() {
        return state != State.OPEN
                && live.isEmpty()
                && cancelRequests.isEmpty()
                && !wakeupQueued
                && ring.inFlightCount() == 0;
    }

    /** A blocking enter is deliberately outside submitLock. */
    private void waitForAtLeastOneCompletion() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment capture = Syscalls.newCaptureSegment(arena);
            long rc = Syscalls.ioUringEnter(capture, ring.fd(), 0, 1, IORING_ENTER_GETEVENTS);
            if (rc < 0) {
                int errno = Syscalls.errno(capture);
                if (errno == Errno.EINTR) {
                    return;
                }
                throw new UringException("io_uring_enter(dispatcher poll)", errno);
            }
        }
    }

    /**
     * Starts orderly shutdown, wakes a blocked poller, cancels every live
     * request, and waits until all target/control CQEs are drained before
     * detaching from the ring. A failed close deliberately leaves the
     * dispatcher attached and FAILED so a retry cannot free ring memory while
     * a kernel operation is still live.
     */
    @Override
    public void close() {
        if (Thread.currentThread() == pollerThread) {
            throw new IllegalStateException("Dispatcher.close cannot be called from its CQ poller thread");
        }

        CloseAdmission admission = beginClose();
        if (!admission.owner()) {
            if (admission.interrupted()) {
                Thread.currentThread().interrupt();
            }
            return;
        }

        boolean interrupted = admission.interrupted();
        boolean closedCleanly = false;
        Throwable closeFailure = null;
        try {
            synchronized (submitLock) {
                // Wake any submitter parked in awaitSqeSpaceLocked() so it
                // observes the now-CLOSING state instead of waiting for a
                // reap that this shutdown may not otherwise produce.
                submitLock.notifyAll();
            }
            // An initial NOP wakes a poller sleeping in GETEVENTS even when no
            // user operation happened to produce a cancellation CQE.
            submitWakeupNop();
            cancelAllLiveOperations();
            // A late completion can consume the first NOP before all cancel
            // SQEs are published; queueing a second wake is cheap and closes
            // that timing window without ever calling enter under submitLock.
            submitWakeupNop();

            interrupted |= joinPollerUninterruptibly();
            if (needsSynchronousDrain()) {
                drainAfterPollerExit();
            }
            waitForReservedPlanExecutions();

            synchronized (submitLock) {
                if (!live.isEmpty() || !cancelRequests.isEmpty() || wakeupQueued || ring.inFlightCount() != 0) {
                    throw new IllegalStateException("dispatcher poller stopped before all CQEs drained");
                }
            }
            ring.detachDispatcher(this);
            detached = true;
            closedCleanly = true;
        } catch (RuntimeException | Error failure) {
            closeFailure = failure;
            failDispatcher(new IllegalStateException("dispatcher close could not drain every live operation", failure));
        } finally {
            finishClose(closedCleanly);
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }

        if (closeFailure != null) {
            throw asUnchecked(closeFailure);
        }
    }

    /** Queue and submit a control NOP without holding submitLock during enter. */
    private void submitWakeupNop() {
        Uring.SubmitPlan plan;
        synchronized (submitLock) {
            if (wakeupQueued) {
                return;
            }
            boolean reserved = false;
            boolean published = false;
            try {
                MemorySegment sqe = ring.sqeSlot();
                reserved = true;
                Sqe.prepNop(sqe, WAKEUP_USER_DATA);
                plan = ring.publishAndPlan();
                published = true;
                wakeupQueued = true;
                reservePlanExecutionLocked(plan);
            } catch (RuntimeException | Error failure) {
                wakeupQueued = false;
                if (reserved && !published) {
                    ring.discardUnpublishedDispatcherSqe();
                }
                throw failure;
            }
        }
        executeReservedPlan(plan);
    }

    /**
     * Cancels one registered target at a time. Publishing/entering one control
     * SQE per iteration avoids filling the SQ while close races a large vthread
     * fan-out, and each enter remains outside submitLock.
     */
    private void cancelAllLiveOperations() {
        while (true) {
            Uring.SubmitPlan plan;
            synchronized (submitLock) {
                Entry target = nextUncancelledLiveEntryLocked();
                if (target == null) {
                    return;
                }
                plan = enqueueCancellationLocked(target);
            }
            executeReservedPlan(plan);
        }
    }

    private Entry nextUncancelledLiveEntryLocked() {
        for (Entry entry : live.values()) {
            if (!entry.cancellationRequested) {
                return entry;
            }
        }
        return null;
    }

    private Uring.SubmitPlan enqueueCancellationLocked(Entry target) {
        if (live.get(target.userData) != target || target.cancellationRequested) {
            return Uring.SubmitPlan.NONE;
        }
        long cancelUserData = reserveUserDataLocked();
        boolean reserved = false;
        boolean published = false;
        try {
            MemorySegment sqe = ring.sqeSlot();
            reserved = true;
            Sqe.prepAsyncCancel(sqe, target.userData, cancelUserData);
            target.cancellationRequested = true;
            cancelRequests.put(cancelUserData, new CancelRequest(target));
            Uring.SubmitPlan plan = ring.publishAndPlan();
            published = true;
            reservePlanExecutionLocked(plan);
            return plan;
        } catch (RuntimeException | Error failure) {
            cancelRequests.remove(cancelUserData);
            target.cancellationRequested = false;
            if (reserved && !published) {
                ring.discardUnpublishedDispatcherSqe();
            }
            throw failure;
        }
    }

    /** Must run under submitLock before the publishing thread releases it. */
    private void reservePlanExecutionLocked(Uring.SubmitPlan plan) {
        if (plan.requiresEnter()) {
            executingPlans.incrementAndGet();
        }
    }

    /** Runs any enter syscall outside submitLock and releases its close barrier. */
    private void executeReservedPlan(Uring.SubmitPlan plan) {
        if (!plan.requiresEnter()) {
            return;
        }
        try {
            ring.executePlan(plan);
        } finally {
            if (executingPlans.decrementAndGet() == 0) {
                synchronized (planCompletionLock) {
                    planCompletionLock.notifyAll();
                }
            }
        }
    }

    private boolean joinPollerUninterruptibly() {
        boolean interrupted = false;
        while (pollerThread.isAlive()) {
            try {
                pollerThread.join();
            } catch (InterruptedException ignored) {
                interrupted = true;
            }
        }
        return interrupted;
    }

    private boolean needsSynchronousDrain() {
        synchronized (submitLock) {
            return ring.inFlightCount() != 0;
        }
    }

    /**
     * A poller crash is rare, but cannot be allowed to turn into a UAF at
     * detach time. Once join confirms it is gone, close takes over its same
     * reap/wait loop until the in-flight registry is empty.
     */
    private void drainAfterPollerExit() {
        while (true) {
            int reaped;
            boolean done;
            synchronized (submitLock) {
                reaped = ring.reap(this::onCompleteLocked);
                done = live.isEmpty() && cancelRequests.isEmpty() && !wakeupQueued && ring.inFlightCount() == 0;
            }
            if (done) {
                return;
            }
            if (reaped == 0) {
                waitForAtLeastOneCompletion();
            }
        }
    }

    private void waitForReservedPlanExecutions() {
        boolean interrupted = false;
        try {
            while (executingPlans.get() != 0) {
                synchronized (planCompletionLock) {
                    if (executingPlans.get() != 0) {
                        try {
                            planCompletionLock.wait();
                        } catch (InterruptedException ignored) {
                            interrupted = true;
                        }
                    }
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private CloseAdmission beginClose() {
        boolean interrupted = false;
        synchronized (stateLock) {
            while (closeInProgress && state != State.CLOSED) {
                try {
                    stateLock.wait();
                } catch (InterruptedException ignored) {
                    interrupted = true;
                }
            }
            if (state == State.CLOSED) {
                return new CloseAdmission(false, interrupted);
            }
            closeInProgress = true;
            if (state == State.OPEN) {
                state = State.CLOSING;
            }
            return new CloseAdmission(true, interrupted);
        }
    }

    private void finishClose(boolean closedCleanly) {
        synchronized (stateLock) {
            if (closedCleanly) {
                state = State.CLOSED;
            } else if (state != State.CLOSED) {
                state = State.FAILED;
            }
            closeInProgress = false;
            stateLock.notifyAll();
        }
    }

    private void requireOpenForSubmissionLocked() {
        if (state != State.OPEN) {
            Throwable cause = failure;
            throw new IllegalStateException("Dispatcher is not accepting submissions: " + state, cause);
        }
    }

    /**
     * Blocks the calling submitter (holding submitLock) until the ring has
     * room for one more SQE. Many virtual threads share one bounded ring
     * here, so a full SQ is an ordinary, expected condition under load — not
     * a caller error — and must be waited out rather than thrown as an
     * exception up through the submitter. {@link #pollLoop}, {@link #close()},
     * and {@link #failDispatcher} all notify submitLock when space may have
     * opened up or the Dispatcher stops accepting new work.
     */
    private void awaitSqeSpaceLocked() {
        while (state == State.OPEN && !ring.hasSqeSpace()) {
            try {
                submitLock.wait();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        requireOpenForSubmissionLocked();
    }

    /** Rejects an unrelated direct-ring token instead of letting a caller park forever. */
    private void requireKnownTypedToken(OpToken token) {
        if (!token.belongsTo(this)) {
            throw new IllegalArgumentException("token is not owned by this Dispatcher");
        }
        if (token.isTerminal()) {
            return;
        }
        synchronized (submitLock) {
            // The poller may route the terminal CQE between the optimistic
            // check above and this lock acquisition. Recheck before treating
            // a now-removed live entry as an unrelated token.
            if (token.isTerminal()) {
                return;
            }
            Entry entry = live.get(token.userData());
            if (entry == null || entry.token != token) {
                throw new IllegalArgumentException("token is not live on this Dispatcher");
            }
        }
    }

    /** Allocates a positive key that does not collide with any Java/kernel-tracked operation. */
    private long reserveUserDataLocked() {
        for (long attempts = 0; attempts < Long.MAX_VALUE; attempts++) {
            long candidate = ++nextUserData;
            if (candidate <= 0) {
                nextUserData = 0;
                continue;
            }
            if (!live.containsKey(candidate)
                    && !cancelRequests.containsKey(candidate)
                    && !ring.hasInFlightUserData(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("exhausted dispatcher user_data space");
    }

    private void failDispatcher(Throwable proposedFailure) {
        Throwable stableFailure = markFailed(proposedFailure);
        synchronized (submitLock) {
            // Wake any submitter parked in awaitSqeSpaceLocked() so it observes
            // the now-FAILED state instead of waiting for a reap that may never come.
            submitLock.notifyAll();
            failLiveWaitersLocked(stableFailure);
        }
    }

    /** State-only portion: never hold stateLock while waiting on submitLock. */
    private Throwable markFailed(Throwable proposedFailure) {
        synchronized (stateLock) {
            if (state == State.CLOSED) {
                return failure == null ? proposedFailure : failure;
            }
            if (failure == null) {
                failure = Objects.requireNonNull(proposedFailure, "proposedFailure");
            }
            state = State.FAILED;
            return failure;
        }
    }

    /** Called with submitLock held. Live entries remain registered for safe CQ draining. */
    private void failLiveWaitersLocked(Throwable stableFailure) {
        for (Entry entry : live.values()) {
            entry.fail(new IllegalStateException("dispatcher stopped before operation user_data=0x"
                    + Long.toHexString(entry.userData) + " reached a terminal CQE", stableFailure));
        }
    }

    /** Package-private lifecycle probe for the executable regression demo. */
    int liveOperationCount() {
        synchronized (submitLock) {
            return live.size();
        }
    }

    /** Package-private diagnostic for close-failure tests. */
    boolean isDetached() {
        return detached;
    }

    private static RuntimeException asUnchecked(Throwable failure) {
        if (failure instanceof RuntimeException runtime) {
            return runtime;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        return new IllegalStateException(failure);
    }
}
