package uring;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A synchronous-style TCP stream writer which serializes all
 * {@code IORING_OP_SEND} SQEs for one socket.
 *
 * <p>Linux's io_uring documentation warns that multiple outstanding send
 * operations against one stream socket can complete out of submission order.
 * This class makes that unsafe state unreachable through its public API: a
 * call to {@link #send(MemorySegment, int)} is accepted into a per-socket FIFO
 * queue, but the next SEND SQE is not prepared until the previous SEND CQE is
 * terminal. A partial write remains the queue head until all of that call's
 * bytes have been sent. Thus there is at most one outstanding SEND SQE from
 * this {@code StreamSocket} at any instant.
 *
 * <p>This is deliberately the bounded <em>copying</em> send path. Each call
 * copies its source into a request-owned shared {@link Arena}; that supports
 * callers passing confined segments from different virtual threads, and keeps
 * the kernel buffer alive even while another caller's thread drives the FIFO.
 * The copy is freed only after the terminal CQE for its final SEND. A future
 * zero-copy/registered-buffer API must expose an explicit ownership token
 * rather than weaken this guarantee.
 *
 * <p>The wrapper is non-owning with respect to {@code socketFd}. Call
 * {@link #close()} first to stop admission and drain this writer, then close
 * the descriptor only after its {@link Dispatcher} has been cleanly closed or
 * otherwise drained. Only one wrapper may be constructed for a
 * {@code (Dispatcher, fd)} pair at a time; raw {@link Sqe#prepSend} calls or
 * use of the same descriptor through another ring bypass this class and are
 * intentionally outside this guarantee.
 */
public final class StreamSocket implements AutoCloseable {

    /** Prevents accidental double-wrapping through the same sole CQ reaper. */
    private static final Object OWNER_LOCK = new Object();
    private static final Map<Dispatcher, Map<Integer, StreamSocket>> OWNERS = new WeakHashMap<>();

    /** A point-in-time, allocation-light view of the writer's queue discipline. */
    public record SendStats(long acceptedCalls, long completedCalls, long failedCalls,
                            int queuedCalls, int outstandingSendSqes,
                            int maxOutstandingSendSqes) {
    }

    private enum State {
        OPEN,
        CLOSING,
        FAILED,
        CLOSED
    }

    /** One public send call and the shared native copy that backs its SQEs. */
    private static final class SendRequest {
        final Arena arena;
        final MemorySegment bytes;
        final int byteCount;
        private boolean completed;
        private Throwable failure;

        SendRequest(Arena arena, MemorySegment bytes, int byteCount) {
            this.arena = arena;
            this.bytes = bytes;
            this.byteCount = byteCount;
        }

        void complete(Throwable terminalFailure) {
            synchronized (this) {
                if (completed) {
                    return;
                }
                try {
                    // The worker calls this only after its last SEND CQE, or
                    // before the request ever reached the kernel.
                    arena.close();
                } catch (RuntimeException closeFailure) {
                    if (terminalFailure == null) {
                        terminalFailure = closeFailure;
                    } else {
                        terminalFailure.addSuppressed(closeFailure);
                    }
                }
                failure = terminalFailure;
                completed = true;
                notifyAll();
            }
        }

        int await() {
            boolean interrupted = false;
            synchronized (this) {
                while (!completed) {
                    try {
                        wait();
                    } catch (InterruptedException ignored) {
                        // An admitted request owns a native copy that may be
                        // in the kernel. Keep waiting for its terminal CQE,
                        // then restore interrupt status to the caller.
                        interrupted = true;
                    }
                }
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
                if (failure != null) {
                    throw asUnchecked(failure);
                }
                return byteCount;
            }
        }
    }

    private final Dispatcher dispatcher;
    private final int socketFd;
    private final Object sendLock = new Object();
    private final ArrayDeque<SendRequest> queue = new ArrayDeque<>();
    /** A stream wrapper admits one reader; callers must serialize their protocol reads. */
    private final AtomicBoolean readInFlight = new AtomicBoolean();

    private State state = State.OPEN;
    /** True while one virtual thread is the unique FIFO driver. Guarded by {@link #sendLock}. */
    private boolean draining;
    private Thread drainOwner;
    private Throwable terminalFailure;
    private int outstandingSendSqes;
    private int maxOutstandingSendSqes;
    private long acceptedCalls;
    private long completedCalls;
    private long failedCalls;

    /**
     * Creates a non-owning serialized writer for {@code socketFd}.
     * {@code dispatcher} must remain open for every admitted send and must be
     * the sole dispatcher/reaper for its underlying ring.
     */
    public StreamSocket(Dispatcher dispatcher, int socketFd) {
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        if (socketFd < 0) {
            throw new IllegalArgumentException("socketFd must be non-negative: " + socketFd);
        }
        this.socketFd = socketFd;
        registerExclusiveOwner();
    }

    /** Returns the underlying, non-owned Linux file descriptor. */
    public int fd() {
        return socketFd;
    }

    /**
     * Copies and fully sends {@code byteCount} bytes from {@code source}.
     * The calling thread waits for its FIFO request's terminal outcome. The
     * dedicated virtual FIFO worker uses the Dispatcher's park/unpark path for
     * the SEND CQE, while a waiting virtual caller unmounts in {@link Object#wait()}.
     *
     * @return exactly {@code byteCount} on success
     * @throws UringException when a terminal SEND CQE carries a negative errno
     * @throws IllegalStateException when the writer is closing, closed, or
     *                               has failed a prior request
     */
    public int send(MemorySegment source, int byteCount) {
        requireSourceRange(source, byteCount);
        if (byteCount == 0) {
            synchronized (sendLock) {
                requireOpenLocked();
            }
            return 0;
        }

        SendRequest request = copyRequest(source, byteCount);
        try {
            synchronized (sendLock) {
                requireOpenLocked();
                queue.addLast(request);
                try {
                    if (!draining) {
                        startDriverLocked();
                    }
                    acceptedCalls++;
                    sendLock.notifyAll();
                } catch (RuntimeException | Error startupFailure) {
                    if (!queue.removeLastOccurrence(request)) {
                        throw new AssertionError("StreamSocket lost request while starting its send worker", startupFailure);
                    }
                    throw startupFailure;
                }
            }
        } catch (RuntimeException | Error admissionFailure) {
            request.complete(admissionFailure);
            throw admissionFailure;
        }
        return request.await();
    }

    /**
     * Receives up to {@code byteCount} bytes through the Dispatcher's sole CQ
     * reaper and returns the terminal {@code RECV} result. A zero result is
     * EOF. Like {@link #send(MemorySegment, int)}, this is synchronous-style:
     * its virtual-thread caller parks until the CQE arrives.
     *
     * <p>A TCP protocol must have at most one outstanding read for a stream
     * at a time. Rather than let two callers race their receive buffers, this
     * wrapper rejects overlapping reads. It does not retain {@code destination}
     * after this method returns; the caller must keep it alive for the call.
     */
    public int read(MemorySegment destination, int byteCount) {
        requireSourceRange(destination, byteCount);
        synchronized (sendLock) {
            requireOpenLocked();
        }
        if (byteCount == 0) {
            return 0;
        }
        if (!readInFlight.compareAndSet(false, true)) {
            throw new IllegalStateException("overlapping StreamSocket.read on fd=" + socketFd
                    + " is not permitted; serialize the stream protocol");
        }
        try {
            return dispatcher.submitAndAwait((sqe, userData) ->
                    Sqe.prepRecv(sqe, socketFd, destination, byteCount, 0, userData));
        } finally {
            readInFlight.set(false);
        }
    }

    /** A diagnostic snapshot; {@code maxOutstandingSendSqes} must never exceed one. */
    public SendStats sendStats() {
        synchronized (sendLock) {
            return new SendStats(acceptedCalls, completedCalls, failedCalls,
                    queue.size(), outstandingSendSqes, maxOutstandingSendSqes);
        }
    }

    /**
     * Test/diagnostic convenience for observing queue pressure without
     * polling. It does not alter scheduling or submit another SQE.
     */
    public boolean awaitQueuedCalls(int minimum, Duration timeout) {
        if (minimum < 0) {
            throw new IllegalArgumentException("minimum must be non-negative: " + minimum);
        }
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must not be negative: " + timeout);
        }
        long remainingNanos = timeout.toNanos();
        long deadline = System.nanoTime() + remainingNanos;
        boolean interrupted = false;
        synchronized (sendLock) {
            while (queue.size() < minimum && state == State.OPEN && remainingNanos > 0) {
                try {
                    long millis = remainingNanos / 1_000_000L;
                    int nanos = (int) (remainingNanos % 1_000_000L);
                    sendLock.wait(millis, nanos);
                } catch (InterruptedException ignored) {
                    interrupted = true;
                }
                remainingNanos = deadline - System.nanoTime();
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
            return queue.size() >= minimum;
        }
    }

    /**
     * Stops future admission, fails requests which have not reached the FIFO
     * head, and waits for the one current request (if any) to reach a terminal
     * CQE. This method does not close {@link #fd()} itself; see the class
     * lifecycle contract.
     */
    @Override
    public void close() {
        boolean interrupted = false;
        synchronized (sendLock) {
            if (state == State.CLOSED) {
                return;
            }
            if (Thread.currentThread() == drainOwner) {
                throw new IllegalStateException("StreamSocket.close cannot run from its active send driver");
            }
            if (state == State.OPEN) {
                state = State.CLOSING;
                failQueuedLocked(new IllegalStateException("StreamSocket closed before queued send reached the kernel"));
            }
            while (draining) {
                try {
                    sendLock.wait();
                } catch (InterruptedException ignored) {
                    interrupted = true;
                }
            }
            state = State.CLOSED;
            releaseExclusiveOwnerLocked();
            sendLock.notifyAll();
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /** Runs only on the single virtual thread elected by {@link #startDriverLocked()}. */
    private void driveQueue() {
        try {
            while (true) {
                SendRequest request;
                synchronized (sendLock) {
                    if (state != State.OPEN) {
                        return;
                    }
                    request = queue.pollFirst();
                    if (request == null) {
                        // Clear the driver lease while still holding the
                        // queue lock. A caller that arrives just afterwards
                        // elects itself, so no accepted request can be left
                        // behind an idle former driver.
                        releaseDriverLocked();
                        return;
                    }
                    sendLock.notifyAll();
                }

                try {
                    sendFully(request);
                    request.complete(null);
                    synchronized (sendLock) {
                        completedCalls++;
                    }
                } catch (Throwable requestFailure) {
                    request.complete(requestFailure);
                    synchronized (sendLock) {
                        failedCalls++;
                        terminalFailure = requestFailure;
                        state = State.FAILED;
                        failQueuedLocked(requestFailure);
                    }
                    return;
                }
            }
        } finally {
            synchronized (sendLock) {
                // The empty-queue path may already have released this lease;
                // do not clear a new driver's lease in that case.
                if (drainOwner == Thread.currentThread()) {
                    releaseDriverLocked();
                }
            }
        }
    }

    /** Sends one FIFO request, retaining it as queue head through partial CQEs. */
    private void sendFully(SendRequest request) {
        int offset = 0;
        while (offset < request.byteCount) {
            int remaining = request.byteCount - offset;
            MemorySegment slice = request.bytes.asSlice(offset, remaining);
            int result = submitOneSend(slice, remaining);
            if (result < 0) {
                throw new UringException("SEND", -result);
            }
            if (result == 0) {
                throw new IllegalStateException("SEND returned zero before all " + request.byteCount + " bytes were written");
            }
            if (result > remaining) {
                throw new IllegalStateException("SEND completed " + result + " bytes for remaining=" + remaining);
            }
            offset += result;
        }
    }

    /** Exactly one caller can reach here because {@link #driveQueue()} is unique. */
    private int submitOneSend(MemorySegment bytes, int byteCount) {
        synchronized (sendLock) {
            if (outstandingSendSqes != 0) {
                throw new AssertionError("StreamSocket attempted a second outstanding SEND SQE on fd=" + socketFd);
            }
            outstandingSendSqes++;
            maxOutstandingSendSqes = Math.max(maxOutstandingSendSqes, outstandingSendSqes);
        }
        try {
            return dispatcher.submitAndAwait((sqe, userData) ->
                    Sqe.prepSend(sqe, socketFd, bytes, byteCount, SocketOps.MSG_NOSIGNAL, userData));
        } finally {
            synchronized (sendLock) {
                outstandingSendSqes--;
                if (outstandingSendSqes < 0) {
                    throw new AssertionError("StreamSocket SEND accounting underflow on fd=" + socketFd);
                }
                sendLock.notifyAll();
            }
        }
    }

    private SendRequest copyRequest(MemorySegment source, int byteCount) {
        Arena arena = Arena.ofShared();
        try {
            MemorySegment copy = arena.allocate(byteCount, 1);
            MemorySegment.copy(source, 0, copy, 0, byteCount);
            return new SendRequest(arena, copy, byteCount);
        } catch (RuntimeException | Error copyFailure) {
            arena.close();
            throw copyFailure;
        }
    }

    /** Fails every request which has not yet become the unique active head. */
    private void failQueuedLocked(Throwable failure) {
        SendRequest queued;
        while ((queued = queue.pollFirst()) != null) {
            queued.complete(failure);
            failedCalls++;
        }
        sendLock.notifyAll();
    }

    private void releaseDriverLocked() {
        draining = false;
        drainOwner = null;
        sendLock.notifyAll();
    }

    private void registerExclusiveOwner() {
        synchronized (OWNER_LOCK) {
            Map<Integer, StreamSocket> byFd = OWNERS.computeIfAbsent(dispatcher, ignored -> new HashMap<>());
            StreamSocket existing = byFd.putIfAbsent(socketFd, this);
            if (existing != null) {
                throw new IllegalStateException("a StreamSocket already owns fd=" + socketFd
                        + " for this Dispatcher; use that wrapper instead of creating concurrent SEND paths");
            }
        }
    }

    /** Called with sendLock held after every locally admitted send is terminal. */
    private void releaseExclusiveOwnerLocked() {
        synchronized (OWNER_LOCK) {
            Map<Integer, StreamSocket> byFd = OWNERS.get(dispatcher);
            if (byFd == null || byFd.get(socketFd) != this) {
                return;
            }
            byFd.remove(socketFd);
            if (byFd.isEmpty()) {
                OWNERS.remove(dispatcher);
            }
        }
    }

    /** Starts the sole driver while holding {@link #sendLock}; callers never run queued peers themselves. */
    private void startDriverLocked() {
        if (draining) {
            return;
        }
        Thread worker = Thread.ofVirtual().name("stream-send-fd-" + socketFd).unstarted(this::driveQueue);
        draining = true;
        drainOwner = worker;
        try {
            worker.start();
        } catch (RuntimeException | Error startupFailure) {
            releaseDriverLocked();
            throw startupFailure;
        }
    }

    private void requireOpenLocked() {
        if (state != State.OPEN) {
            throw new IllegalStateException("StreamSocket is not accepting sends: " + state, terminalFailure);
        }
    }

    private static void requireSourceRange(MemorySegment source, int byteCount) {
        Objects.requireNonNull(source, "source");
        if (byteCount < 0 || byteCount > source.byteSize()) {
            throw new IllegalArgumentException("byteCount=" + byteCount + " outside source segment size=" + source.byteSize());
        }
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
