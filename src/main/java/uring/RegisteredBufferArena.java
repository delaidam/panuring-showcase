package uring;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.Objects;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * An owned, fixed-buffer registration table for one {@link Uring} instance.
 *
 * <p>Unlike {@link Uring#registerBuffers(MemorySegment...)}, this type never
 * adopts caller-owned memory: it allocates every buffer and its {@code iovec}
 * table from a private shared {@link Arena}. That removes the easy Java-side
 * failure mode where a caller closes its own arena while the kernel still owns
 * a registered address.
 *
 * <p><b>Close guarantee.</b> For the current one-shot, exclusive raw-ring
 * API, {@link #close()} performs this exact order:
 *
 * <ol>
 *   <li>rejects prepared but unpublished SQEs, because they are not yet in
 *       Uring's in-flight registry;</li>
 *   <li>drains submitted in-flight CQEs;</li>
 *   <li>unregisters the kernel fixed-buffer table;</li>
 *   <li>closes this Arena, making every {@link Buffer#segment()} inaccessible
 *       to Java.</li>
 * </ol>
 *
 * If draining or unregistering fails, the Arena deliberately remains alive;
 * freeing it in a {@code finally} block would violate the guarantee. A
 * successful close is idempotent.
 *
 * <p><b>Boundary of the guarantee.</b> This slice is exclusive with
 * {@link Dispatcher}, because its dispatcher owns an independent CQ reaper.
 * It also applies only to operations submitted through this {@link Uring};
 * direct {@link Rings} or {@link BatchSubmitter} use and raw SQE escape
 * hatches remain outside the safe API. Typed {@link OpToken} submission now
 * borrows a managed buffer through its terminal CQE; concrete multishot
 * network operations are added later and use the same lease mechanism.
 */
public final class RegisteredBufferArena implements AutoCloseable {

    private enum State {
        OPEN,
        CLOSING,
        /** Kernel table is gone but an exceptional Arena.close retry remains possible. */
        UNREGISTERED,
        CLOSED
    }

    private final Uring ring;
    private final Arena arena;
    private final Buffer[] buffers;
    private State state = State.OPEN;

    static RegisteredBufferArena open(Uring ring, int bufferCount, long bufferSize, long bufferAlignment) {
        return new RegisteredBufferArena(ring, bufferCount, bufferSize, bufferAlignment);
    }

    private RegisteredBufferArena(Uring ring, int bufferCount, long bufferSize, long bufferAlignment) {
        this.ring = Objects.requireNonNull(ring, "ring");
        if (bufferCount <= 0) {
            throw new IllegalArgumentException("bufferCount must be positive: " + bufferCount);
        }
        if (bufferSize <= 0) {
            throw new IllegalArgumentException("bufferSize must be positive: " + bufferSize);
        }
        if (bufferAlignment <= 0 || (bufferAlignment & (bufferAlignment - 1)) != 0) {
            throw new IllegalArgumentException("bufferAlignment must be a positive power of two: " + bufferAlignment);
        }

        arena = Arena.ofShared();
        buffers = new Buffer[bufferCount];
        try {
            MemorySegment iovecs = arena.allocate(Math.multiplyExact(16L, bufferCount), 8);
            for (int index = 0; index < bufferCount; index++) {
                MemorySegment segment = arena.allocate(bufferSize, bufferAlignment);
                buffers[index] = new Buffer(this, index, segment);
                iovecs.set(ADDRESS, (long) index * 16, segment);
                iovecs.set(JAVA_LONG, (long) index * 16 + 8, segment.byteSize());
            }
            ring.installRegisteredBufferArena(this, iovecs, bufferCount);
        } catch (RuntimeException | Error failure) {
            // Registration did not complete, so there is no kernel lease to
            // preserve. Closing here avoids leaking the just-created arena.
            arena.close();
            throw failure;
        }
    }

    /** Number of fixed buffers in this one kernel registration table. */
    public int bufferCount() {
        return buffers.length;
    }

    /**
     * Returns the buffer and its immutable fixed-buffer index. The returned
     * wrapper never exposes this Arena itself; after close starts, asking for
     * its segment throws instead of returning a potentially stale address.
     */
    public Buffer buffer(int index) {
        requireOpen();
        return buffers[Objects.checkIndex(index, buffers.length)];
    }

    /**
     * Safe convenience preparation for {@code IORING_OP_READ_FIXED}; it keeps
     * the registered index coupled to its owned segment. Submission and CQE
     * correlation are still raw one-shot Uring operations in this slice.
     */
    public void prepReadFixed(MemorySegment sqe, int fd, Buffer buffer, int len,
                              long fileOffset, long userData) {
        requireOpen();
        Objects.requireNonNull(sqe, "sqe");
        requireOwned(buffer);
        if (len < 0 || (long) len > buffer.segment.byteSize()) {
            throw new IllegalArgumentException("len=" + len + " is outside registered buffer "
                    + buffer.index + " (size=" + buffer.segment.byteSize() + ")");
        }
        Sqe.prepReadFixed(sqe, fd, buffer.segment(), len, fileOffset, userData, buffer.index);
    }

    /** Package-private typed variant: only the token holding this buffer's borrow may expose its address to the SQE. */
    void prepReadFixed(MemorySegment sqe, int fd, Buffer buffer, int len,
                       long fileOffset, OpToken token) {
        requireOpen();
        Objects.requireNonNull(sqe, "sqe");
        requireOwned(buffer);
        Objects.requireNonNull(token, "token");
        if (token.buffer() != buffer) {
            throw new IllegalArgumentException("token does not borrow this registered buffer");
        }
        if (len < 0 || (long) len > buffer.segment.byteSize()) {
            throw new IllegalArgumentException("len=" + len + " is outside registered buffer "
                    + buffer.index + " (size=" + buffer.segment.byteSize() + ")");
        }
        Sqe.prepReadFixed(sqe, fd, buffer.segmentForKernel(token.userData()), len,
                fileOffset, token.userData(), buffer.index);
    }

    /**
     * Package-private typed SEND_ZC preparation. The buffer need not be used
     * as an io_uring fixed-buffer index for this opcode; its managed borrow is
     * still essential because the kernel can retain the address through the
     * later notification CQE.
     */
    void prepSendZc(MemorySegment sqe, int socketFd, Buffer buffer, int len,
                    int messageFlags, int zeroCopyFlags, OpToken token) {
        requireOpen();
        Objects.requireNonNull(sqe, "sqe");
        requireOwned(buffer);
        Objects.requireNonNull(token, "token");
        if (token.buffer() != buffer) {
            throw new IllegalArgumentException("token does not borrow this registered buffer");
        }
        if (len < 0 || (long) len > buffer.segment.byteSize()) {
            throw new IllegalArgumentException("len=" + len + " is outside registered buffer "
                    + buffer.index + " (size=" + buffer.segment.byteSize() + ")");
        }
        Sqe.prepSendZc(sqe, socketFd, buffer.segmentForKernel(token.userData()), len,
                messageFlags, zeroCopyFlags, token.userData());
    }

    /** True only after this Arena's native memory has been successfully freed. */
    public synchronized boolean isClosed() {
        return state == State.CLOSED;
    }

    /**
     * Drains then unregisters the fixed-buffer table before freeing native
     * memory. A failed drain/unregister leaves this object OPEN and retryable;
     * a rare failed {@link Arena#close()} leaves it UNREGISTERED and retryable
     * without making the segment available again.
     */
    @Override
    public void close() {
        State initial;
        synchronized (this) {
            if (state == State.CLOSED) {
                return;
            }
            if (state == State.CLOSING) {
                throw new IllegalStateException("RegisteredBufferArena close is already in progress");
            }
            initial = state;
            state = State.CLOSING;
        }

        try {
            if (initial == State.OPEN) {
                ring.releaseRegisteredBufferArena(this); // drain -> unregister; may throw while memory remains live
                synchronized (this) {
                    state = State.UNREGISTERED;
                }
            }
            arena.close();
            synchronized (this) {
                state = State.CLOSED;
            }
        } catch (RuntimeException | Error failure) {
            synchronized (this) {
                // A failure before unregister leaves the registration and
                // backing memory valid. Do not silently free it.
                if (state == State.CLOSING) {
                    state = initial;
                }
            }
            throw failure;
        }
    }

    private synchronized void requireOpen() {
        if (state != State.OPEN) {
            throw new IllegalStateException("RegisteredBufferArena is " + state.name().toLowerCase());
        }
    }

    private void requireOwned(Buffer buffer) {
        Objects.requireNonNull(buffer, "buffer");
        if (buffer.owner != this) {
            throw new IllegalArgumentException("buffer belongs to a different RegisteredBufferArena");
        }
    }

    boolean owns(Buffer buffer) {
        return buffer != null && buffer.owner == this;
    }

    boolean hasBorrowedBuffers() {
        for (Buffer buffer : buffers) {
            if (buffer.isBorrowed()) {
                return true;
            }
        }
        return false;
    }

    /** One registered slot. Its index and segment always refer to the same native allocation. */
    public static final class Buffer {
        private static final long NO_BORROWER = Long.MIN_VALUE;

        private final RegisteredBufferArena owner;
        private final int index;
        private final MemorySegment segment;
        private long borrower = NO_BORROWER;

        private Buffer(RegisteredBufferArena owner, int index, MemorySegment segment) {
            this.owner = owner;
            this.index = index;
            this.segment = segment;
        }

        /** Index passed to {@code IORING_OP_READ_FIXED}/{@code WRITE_FIXED}. */
        public int index() {
            return index;
        }

        public long byteSize() {
            return segment.byteSize();
        }

        /**
         * The owned native segment. It is usable only while this arena is
         * OPEN; ordinary Java access also fails once the underlying Arena is
         * closed. Holding this object does not let a caller close the Arena.
         */
        public MemorySegment segment() {
            owner.requireOpen();
            synchronized (this) {
                if (borrower != NO_BORROWER) {
                    throw new IllegalStateException("registered buffer " + index + " is borrowed by OpToken 0x"
                            + Long.toHexString(borrower));
                }
                return segment;
            }
        }

        /** True while a typed operation owns this buffer through its terminal CQE. */
        public synchronized boolean isBorrowed() {
            return borrower != NO_BORROWER;
        }

        MemorySegment borrow(long tokenUserData) {
            owner.requireOpen();
            synchronized (this) {
                if (borrower != NO_BORROWER) {
                    throw new IllegalStateException("registered buffer " + index + " is already borrowed by OpToken 0x"
                            + Long.toHexString(borrower));
                }
                borrower = tokenUserData;
                return segment;
            }
        }

        synchronized MemorySegment segmentForKernel(long tokenUserData) {
            if (borrower != tokenUserData) {
                throw new IllegalStateException("OpToken 0x" + Long.toHexString(tokenUserData)
                        + " does not own registered buffer " + index);
            }
            return segment;
        }

        synchronized void release(long tokenUserData) {
            if (borrower != tokenUserData) {
                throw new IllegalStateException("OpToken 0x" + Long.toHexString(tokenUserData)
                        + " cannot release registered buffer " + index);
            }
            borrower = NO_BORROWER;
        }
    }
}
