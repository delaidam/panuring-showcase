package uring;

import java.lang.foreign.MemorySegment;
import java.util.ArrayDeque;
import java.util.Objects;

/**
 * Completion and lifetime handle returned by a typed {@link Uring} submit.
 *
 * <p>A token strongly retains the submitted {@link MemorySegment} and, for a
 * {@link RegisteredBufferArena.Buffer}, marks that buffer unavailable through
 * the managed API until a terminal CQE arrives. This is a Java-side safety
 * guarantee: it prevents normal managed-buffer reuse and prevents arena close
 * from unregistering/freeing a borrowed buffer early. It cannot revoke a raw
 * {@code MemorySegment} alias a caller deliberately obtained through the
 * documented escape hatch, nor can it prove a malformed raw SQE did not pass
 * a different address to the kernel.
 *
 * <p>A cancellation CQE is not a terminal event for the target token. The
 * future cancel path therefore records its own control token and waits for
 * this token's terminal completion before releasing a borrow.
 */
public final class OpToken {

    /** Submission category selected by the distinct operation type. */
    public enum Kind {
        ONE_SHOT,
        MULTI_SHOT
    }

    private final long userData;
    private final Kind kind;
    private final RegisteredBufferArena.Buffer buffer;
    /** Strong reference held for the lifetime of the kernel operation. */
    private final MemorySegment borrowedSegment;
    /** Non-null only when a Dispatcher, rather than a direct ring, owns CQ reaping. */
    private Dispatcher dispatcherOwner;
    /** Optional non-buffer resource lease acquired by a typed operation. Guarded by this token. */
    private Runnable lifecycleLeaseRelease;

    private boolean terminal;
    private int completionCount;
    private Completion lastCompletion;
    private boolean oneShotProtocolViolation;
    private RuntimeException lifecycleFailure;
    /**
     * A dispatcher routing failure that woke waiters without claiming that
     * the kernel lifetime has ended.  In particular, this must not release a
     * borrowed buffer: the dispatcher close path may still be draining the
     * target CQE after it has reported its own failure to application code.
     */
    private RuntimeException awaitFailure;
    private final ArrayDeque<Completion> completions = new ArrayDeque<>();

    OpToken(long userData, Kind kind, RegisteredBufferArena.Buffer buffer) {
        this.userData = userData;
        this.kind = Objects.requireNonNull(kind, "kind");
        this.buffer = buffer;
        this.borrowedSegment = buffer == null ? MemorySegment.NULL : buffer.borrow(userData);
    }

    /** Kernel correlation key assigned by {@link Uring}; callers never choose it. */
    public long userData() {
        return userData;
    }

    public Kind kind() {
        return kind;
    }

    /** True after the CQE that clears {@link Cqe#FLAG_MORE}. */
    public synchronized boolean isTerminal() {
        return terminal;
    }

    /** Number of CQEs routed to this token so far. */
    public synchronized int completionCount() {
        return completionCount;
    }

    /** Latest copied completion, or {@code null} before the first CQE. */
    public synchronized Completion lastCompletion() {
        return lastCompletion;
    }

    /** True when a one-shot operation unexpectedly emitted a nonterminal CQE. */
    public synchronized boolean hasOneShotProtocolViolation() {
        return oneShotProtocolViolation;
    }

    /** Internal lifecycle inconsistency, if one was recorded without wedging CQ drain. */
    public synchronized RuntimeException lifecycleFailure() {
        return lifecycleFailure;
    }

    /**
     * Waits for and consumes the next CQE routed to this token. Returns
     * {@code null} only after its terminal CQE has already been consumed.
     * Direct-ring callers drive completion with {@link Uring#await(OpToken)}
     * or {@link Uring#reap(CqeConsumer)}.  When submitted through a
     * {@link Dispatcher}, its sole CQ poller routes each CQE here and this
     * method simply parks until that routing occurs.
     */
    public Completion awaitNext() {
        boolean interrupted = false;
        synchronized (this) {
            while (completions.isEmpty() && !terminal && awaitFailure == null) {
                try {
                    wait();
                } catch (InterruptedException ignored) {
                    interrupted = true;
                }
            }
            try {
                Completion completion = completions.pollFirst();
                if (completion != null || terminal) {
                    return completion;
                }
                throw awaitFailure;
            } finally {
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    /**
     * Parks until the terminal completion, restoring interrupt status after
     * completion rather than converting an interrupt into a busy park loop.
     * For a direct ring use {@link Uring#await(OpToken)} to drive CQ reaping;
     * for a dispatcher use {@link Dispatcher#await(OpToken)} or this method
     * directly. A failed dispatcher wakes this wait with its routing failure,
     * but does not falsely mark the token terminal or release its resources.
     */
    public Completion awaitTerminal() {
        boolean interrupted = false;
        synchronized (this) {
            while (!terminal && awaitFailure == null) {
                try {
                    wait();
                } catch (InterruptedException ignored) {
                    interrupted = true;
                }
            }
            try {
                if (terminal) {
                    return lastCompletion;
                }
                throw awaitFailure;
            } finally {
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    /**
     * Records a dispatcher failure and wakes token waiters without claiming a
     * terminal kernel CQE. Package-private because only the sole CQ-owner may
     * make that distinction; callers must not use an error as permission to
     * reuse a borrowed buffer.
     */
    void failAwaiters(Throwable failure) {
        Objects.requireNonNull(failure, "failure");
        synchronized (this) {
            if (terminal || awaitFailure != null) {
                return;
            }
            if (failure instanceof RuntimeException runtime) {
                awaitFailure = runtime;
            } else {
                awaitFailure = new IllegalStateException("dispatcher stopped before token 0x"
                        + Long.toHexString(userData) + " reached a terminal CQE", failure);
            }
            notifyAll();
        }
    }

    void onCompletion(Completion completion) {
        if (completion == null) {
            return;
        }
        synchronized (this) {
            if (completion.userData() != userData) {
                lifecycleFailure = new IllegalArgumentException("CQE user_data 0x"
                        + Long.toHexString(completion.userData()) + " routed to token 0x"
                        + Long.toHexString(userData));
                return;
            }
            if (terminal) {
                lifecycleFailure = new IllegalStateException("duplicate terminal CQE for token 0x"
                        + Long.toHexString(userData));
                return;
            }
            completionCount++;
            lastCompletion = completion;
            completions.addLast(completion);
            if (kind == Kind.ONE_SHOT && completion.more()) {
                // Keep the borrow nevertheless: treating this as terminal
                // would turn an unexpected kernel protocol event into UAF.
                oneShotProtocolViolation = true;
            }
            if (!completion.more()) {
                terminal = true;
                // Make the managed buffer observable again before waking a
                // waiter that may read it immediately after awaitTerminal().
                if (buffer != null) {
                    try {
                        buffer.release(userData);
                    } catch (RuntimeException failure) {
                        // Do not throw out of the CQ drain. The arena's
                        // post-drain borrow check will refuse unsafe free.
                        lifecycleFailure = failure;
                    }
                }
                releaseLifecycleLease();
            }
            // Dispatcher.awaitNext() may be waiting for an intermediate
            // multishot F_MORE CQE, not only for terminal resource release.
            // The terminal path still performs buffer/lease release above
            // before this notification makes it observable to an awaiter.
            notifyAll();
        }
    }

    /** Installed by {@link Uring} after an operation acquires a resource keyed by this token. */
    synchronized void installLifecycleLease(Runnable release) {
        if (release == null) {
            return;
        }
        if (terminal) {
            throw new IllegalStateException("cannot install a lifecycle lease on terminal token 0x"
                    + Long.toHexString(userData));
        }
        if (lifecycleLeaseRelease != null) {
            throw new IllegalStateException("token 0x" + Long.toHexString(userData)
                    + " already owns an additional lifecycle lease");
        }
        lifecycleLeaseRelease = release;
    }

    /** Releases a borrow only when preparation failed before SQ tail publication. */
    void abortBeforePublication() {
        synchronized (this) {
            if (terminal) {
                return;
            }
            terminal = true;
            if (buffer != null) {
                buffer.release(userData);
            }
            releaseLifecycleLease();
            notifyAll();
        }
    }

    /** Nonblocking internal dequeue used by {@link Uring#awaitNext(OpToken)}. */
    synchronized Completion pollCompletion() {
        return completions.pollFirst();
    }

    private void releaseLifecycleLease() {
        if (lifecycleLeaseRelease == null) {
            return;
        }
        Runnable release = lifecycleLeaseRelease;
        lifecycleLeaseRelease = null;
        try {
            release.run();
        } catch (RuntimeException failure) {
            // Keep token routing non-throwing. A lease that refuses release
            // remains visible through lifecycleFailure and its owner's close
            // checks rather than wedging the CQ head.
            lifecycleFailure = failure;
        }
    }

    RegisteredBufferArena.Buffer buffer() {
        return buffer;
    }

    MemorySegment borrowedSegment() {
        return borrowedSegment;
    }

    synchronized void attachDispatcherOwner(Dispatcher dispatcher) {
        Objects.requireNonNull(dispatcher, "dispatcher");
        if (dispatcherOwner != null && dispatcherOwner != dispatcher) {
            throw new IllegalStateException("OpToken 0x" + Long.toHexString(userData)
                    + " is already owned by another Dispatcher");
        }
        dispatcherOwner = dispatcher;
    }

    synchronized boolean belongsTo(Dispatcher dispatcher) {
        return dispatcherOwner == dispatcher;
    }
}
