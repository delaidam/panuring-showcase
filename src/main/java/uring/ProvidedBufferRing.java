package uring;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.VarHandle;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;

/**
 * An owned userspace {@code IOU_PBUF_RING} buffer group.
 *
 * <p>Every descriptor ring page and every payload buffer comes from one
 * private shared {@link Arena}.  The object registers that descriptor ring
 * with {@code IORING_REGISTER_PBUF_RING}, publishes descriptors by a release
 * store of the native {@code tail}, and on close unregisters before freeing
 * its native memory.  This is the provided-buffer equivalent of the
 * registered-buffer lifetime discipline: callers never supply a segment
 * allocated from an arena they can independently close.
 *
 * <p><b>Ownership boundary.</b> A buffer is kernel-owned while it is in this
 * ring.  After a receive CQE with {@link Completion#hasBuffer()}, call
 * {@link #take(Completion)}; only the returned {@link Buffer} exposes its
 * segment.  Call {@link #recycle(Buffer)} after consuming it, which writes a
 * fresh descriptor then release-publishes the tail.  This class rejects
 * double take/recycle and refuses to close while Java still holds a checked
 * out buffer.
 *
 * <p><b>What this standalone slice cannot observe.</b> Raw buffer-select SQEs
 * can be prepared through escape hatches outside this class, so it cannot
 * discover a receive that is still in flight but has not emitted its CQE.
 * Drain/cancel all such receives before {@link #close()}, and close this
 * object before its {@link Uring}.  A later net-runtime integration will
 * register these operations in the same lifecycle registry as typed tokens.
 */
public final class ProvidedBufferRing implements AutoCloseable {

    /** {@code IORING_REGISTER_PBUF_RING}. */
    public static final int IORING_REGISTER_PBUF_RING = 22;
    /** {@code IORING_UNREGISTER_PBUF_RING}. */
    public static final int IORING_UNREGISTER_PBUF_RING = 23;

    /** Size of {@code struct io_uring_buf}. */
    public static final int BUFFER_DESCRIPTOR_SIZE = 16;
    /** Offset of {@code struct io_uring_buf.addr}. */
    public static final long OFF_BUF_ADDR = 0;
    /** Offset of {@code struct io_uring_buf.len}. */
    public static final long OFF_BUF_LEN = 8;
    /** Offset of {@code struct io_uring_buf.bid}. */
    public static final long OFF_BUF_ID = 12;
    /** Overlay location of {@code struct io_uring_buf_ring.tail}. */
    public static final long OFF_TAIL = 14;
    /** Size of {@code struct io_uring_buf_reg}. */
    public static final int BUF_REG_SIZE = 40;
    /** Userspace rings use a 16-bit head/tail; liburing caps them here too. */
    public static final int MAX_RING_ENTRIES = 1 << 15;
    /** PBUF registration requires a page-aligned descriptor ring address. */
    public static final long RING_ALIGNMENT = 4096;

    private static final long OFF_REG_RING_ADDR = 0;
    private static final long OFF_REG_RING_ENTRIES = 8;
    private static final long OFF_REG_BGID = 12;
    private static final long OFF_REG_FLAGS = 14;
    private static final VarHandle SHORT_VH = JAVA_SHORT.varHandle();

    private enum State {
        OPEN,
        CLOSING,
        /** Kernel lease is gone but an exceptional Arena.close retry remains possible. */
        UNREGISTERED,
        CLOSED
    }

    private enum BufferState {
        IN_KERNEL,
        CHECKED_OUT
    }

    private final Uring ring;
    private final Arena arena;
    private final int bufferGroupId;
    private final int ringEntries;
    private final int ringMask;
    private final MemorySegment nativeRing;
    private final Buffer[] buffers;
    /** Typed multishot RECV tokens that still let the kernel select this group. */
    private final Set<Long> receiveLeases = new HashSet<>();
    /** Logical tail; its low 16 bits are release-published to native memory. */
    private int tail;
    private State state = State.OPEN;

    /** Opens a page-aligned PBUF ring with one equally sized payload buffer per entry. */
    public static ProvidedBufferRing open(Uring ring, int bufferGroupId, int ringEntries,
                                          long bufferSize, long bufferAlignment) {
        return new ProvidedBufferRing(ring, bufferGroupId, ringEntries, bufferSize, bufferAlignment);
    }

    /** Uses 64-byte alignment for the payload buffers. */
    public static ProvidedBufferRing open(Uring ring, int bufferGroupId, int ringEntries, long bufferSize) {
        return open(ring, bufferGroupId, ringEntries, bufferSize, 64);
    }

    private ProvidedBufferRing(Uring ring, int bufferGroupId, int ringEntries,
                               long bufferSize, long bufferAlignment) {
        this.ring = Objects.requireNonNull(ring, "ring");
        validateGroupId(bufferGroupId);
        validateEntries(ringEntries);
        validateBufferSize(bufferSize);
        validateAlignment(bufferAlignment);
        this.bufferGroupId = bufferGroupId;
        this.ringEntries = ringEntries;
        this.ringMask = ringEntries - 1;

        Arena allocatedArena = Arena.ofShared();
        MemorySegment allocatedRing = null;
        Buffer[] allocatedBuffers = null;
        boolean registered = false;
        try {
            long ringBytes = Math.multiplyExact((long) ringEntries, BUFFER_DESCRIPTOR_SIZE);
            allocatedRing = allocatedArena.allocate(ringBytes, RING_ALIGNMENT);
            allocatedRing.fill((byte) 0);
            allocatedBuffers = new Buffer[ringEntries];
            for (int id = 0; id < ringEntries; id++) {
                allocatedBuffers[id] = new Buffer(this, id,
                        allocatedArena.allocate(bufferSize, bufferAlignment));
            }

            this.arena = allocatedArena;
            this.nativeRing = allocatedRing;
            this.buffers = allocatedBuffers;
            registerNativeRing();
            registered = true;
            for (Buffer buffer : buffers) {
                publish(buffer);
            }
        } catch (RuntimeException | Error failure) {
            // A registration failure has no kernel lease.  If an exceptional
            // post-registration failure ever occurs, preserve native memory
            // unless unregister succeeds; freeing it first would be unsafe.
            if (registered) {
                try {
                    unregisterNativeRing();
                } catch (RuntimeException cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                    throw failure;
                }
            }
            allocatedArena.close();
            throw failure;
        }
    }

    /** Buffer-group ID written in {@code sqe.buf_group} for buffer-select receives. */
    public int bufferGroupId() {
        return bufferGroupId;
    }

    /** Power-of-two descriptor capacity and number of owned payload buffers. */
    public int ringEntries() {
        return ringEntries;
    }

    /** Number of buffers currently checked out to Java after a receive CQE. */
    public synchronized int checkedOutCount() {
        int count = 0;
        for (Buffer buffer : buffers) {
            if (buffer.state == BufferState.CHECKED_OUT) {
                count++;
            }
        }
        return count;
    }

    /** Number of typed multishot receives that still hold this ring open. */
    public synchronized int activeReceiveCount() {
        return receiveLeases.size();
    }

    /** Low 16 bits of the tail most recently release-published to the kernel. */
    public synchronized int publishedTail() {
        requireOpen();
        return Short.toUnsignedInt(nativeRing.get(JAVA_SHORT, OFF_TAIL));
    }

    /**
     * Prepares one buffer-select receive tied to this object's group ID.
     * It is a raw SQE helper, so callers still must submit/reap it through
     * the same {@link Uring} and drain or cancel it before this object closes.
     */
    public synchronized void prepRecvSelect(MemorySegment sqe, int socketFd, int maxLen,
                                            int messageFlags, long userData) {
        requireOpen();
        if (maxLen < 0 || maxLen > buffers[0].byteSize()) {
            throw new IllegalArgumentException("maxLen=" + maxLen + " is outside provided buffer size="
                    + buffers[0].byteSize());
        }
        Sqe.prepRecvSelect(sqe, socketFd, maxLen, messageFlags, bufferGroupId, userData);
    }

    /** Typed multishot counterpart of {@link #prepRecvSelect}. */
    synchronized void prepRecvMultishot(MemorySegment sqe, int socketFd, int maxLen,
                                        int messageFlags, OpToken token) {
        requireOpen();
        Objects.requireNonNull(token, "token");
        if (!receiveLeases.contains(token.userData())) {
            throw new IllegalStateException("OpToken 0x" + Long.toHexString(token.userData())
                    + " does not hold this provided-buffer receive lease");
        }
        if (maxLen < 0 || maxLen > buffers[0].byteSize()) {
            throw new IllegalArgumentException("maxLen=" + maxLen + " is outside provided buffer size="
                    + buffers[0].byteSize());
        }
        Sqe.prepRecvMultishotSelect(sqe, socketFd, maxLen, messageFlags, bufferGroupId, token.userData());
    }

    /** Acquires the close-blocking lease paired with a typed multishot receive token. */
    synchronized Runnable acquireReceiveLease(OpToken token) {
        requireOpen();
        Objects.requireNonNull(token, "token");
        if (!receiveLeases.add(token.userData())) {
            throw new IllegalStateException("duplicate provided-buffer receive token 0x"
                    + Long.toHexString(token.userData()));
        }
        return () -> releaseReceiveLease(token.userData());
    }

    /** True only after the kernel registration is removed and the shared Arena is closed. */
    public synchronized boolean isClosed() {
        return state == State.CLOSED;
    }

    /**
     * Transfers one completion-selected buffer from kernel ownership to Java.
     * The completion must be a normal provided-buffer CQE; incremental
     * provided-buffer bundles need their own {@code BUF_MORE} protocol and
     * are intentionally not exposed by this first slice.
     */
    public Buffer take(Completion completion) {
        Objects.requireNonNull(completion, "completion");
        if (!completion.hasBuffer()) {
            throw new IllegalArgumentException("completion does not carry IORING_CQE_F_BUFFER");
        }
        if (completion.res() < 0) {
            throw new IllegalArgumentException("failed completion cannot transfer a provided buffer: " + completion);
        }
        if (completion.bufferMore()) {
            throw new UnsupportedOperationException("incremental provided-buffer CQE (IORING_CQE_F_BUF_MORE) "
                    + "requires the Phase-2 bundle protocol and cannot be recycled as a whole buffer");
        }
        return take(completion.bufferId());
    }

    /**
     * Transfers a known CQE buffer ID to Java. Prefer {@link #take(Completion)}
     * so the provided-buffer flag is checked at the call boundary.
     */
    public synchronized Buffer take(int bufferId) {
        requireOpen();
        Buffer buffer = buffers[Objects.checkIndex(bufferId, buffers.length)];
        if (buffer.state != BufferState.IN_KERNEL) {
            throw new IllegalStateException("provided buffer " + bufferId + " is already checked out");
        }
        buffer.state = BufferState.CHECKED_OUT;
        return buffer;
    }

    /**
     * Returns a completed receive buffer to the kernel.  Descriptor stores
     * happen before the release store to {@link #OFF_TAIL}; the kernel may not
     * observe this entry before that publication.
     */
    public synchronized void recycle(Buffer buffer) {
        requireOpen();
        requireOwned(buffer);
        if (buffer.state != BufferState.CHECKED_OUT) {
            throw new IllegalStateException("provided buffer " + buffer.id + " is not checked out");
        }
        publish(buffer);
        buffer.state = BufferState.IN_KERNEL;
    }

    @Override
    public void close() {
        State initial;
        synchronized (this) {
            if (state == State.CLOSED) {
                return;
            }
            if (state == State.CLOSING) {
                throw new IllegalStateException("ProvidedBufferRing close is already in progress");
            }
            if (state == State.OPEN && checkedOutCount() != 0) {
                throw new IllegalStateException("cannot close ProvidedBufferRing with " + checkedOutCount()
                        + " buffer(s) checked out; recycle them after consuming their CQEs");
            }
            if (state == State.OPEN && !receiveLeases.isEmpty()) {
                throw new IllegalStateException("cannot close ProvidedBufferRing with " + receiveLeases.size()
                        + " live typed multishot receive(s); cancel/drain their terminal CQEs first");
            }
            initial = state;
            state = State.CLOSING;
        }

        try {
            if (initial == State.OPEN) {
                unregisterNativeRing();
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
                // A failed unregister must leave both registration and
                // backing memory live so a caller can repair ordering/retry.
                if (state == State.CLOSING) {
                    state = initial;
                }
            }
            throw failure;
        }
    }

    private void registerNativeRing() {
        try (Arena callArena = Arena.ofConfined()) {
            MemorySegment registration = callArena.allocate(BUF_REG_SIZE, Long.BYTES);
            registration.set(JAVA_LONG, OFF_REG_RING_ADDR, nativeRing.address());
            registration.set(JAVA_INT, OFF_REG_RING_ENTRIES, ringEntries);
            registration.set(JAVA_SHORT, OFF_REG_BGID, (short) bufferGroupId);
            registration.set(JAVA_SHORT, OFF_REG_FLAGS, (short) 0);
            MemorySegment capture = Syscalls.newCaptureSegment(callArena);
            long rc = Syscalls.ioUringRegister(capture, ring.fd(), IORING_REGISTER_PBUF_RING,
                    registration, 1);
            if (rc < 0) {
                throw new UringException("io_uring_register(REGISTER_PBUF_RING)", Syscalls.errno(capture));
            }
        }
    }

    private void unregisterNativeRing() {
        try (Arena callArena = Arena.ofConfined()) {
            MemorySegment registration = callArena.allocate(BUF_REG_SIZE, Long.BYTES);
            registration.set(JAVA_SHORT, OFF_REG_BGID, (short) bufferGroupId);
            MemorySegment capture = Syscalls.newCaptureSegment(callArena);
            long rc = Syscalls.ioUringRegister(capture, ring.fd(), IORING_UNREGISTER_PBUF_RING,
                    registration, 1);
            if (rc < 0) {
                throw new UringException("io_uring_register(UNREGISTER_PBUF_RING)", Syscalls.errno(capture));
            }
        }
    }

    /** Writes one {@code io_uring_buf}, then release-publishes its tail slot. Caller holds this monitor. */
    private void publish(Buffer buffer) {
        int slot = tail & ringMask;
        long offset = (long) slot * BUFFER_DESCRIPTOR_SIZE;
        nativeRing.set(JAVA_LONG, offset + OFF_BUF_ADDR, buffer.segment.address());
        nativeRing.set(JAVA_INT, offset + OFF_BUF_LEN, (int) buffer.segment.byteSize());
        nativeRing.set(JAVA_SHORT, offset + OFF_BUF_ID, (short) buffer.id);
        tail++;
        SHORT_VH.setRelease(nativeRing, OFF_TAIL, (short) tail);
    }

    private synchronized MemorySegment segmentFor(Buffer buffer) {
        requireOpen();
        requireOwned(buffer);
        if (buffer.state != BufferState.CHECKED_OUT) {
            throw new IllegalStateException("provided buffer " + buffer.id
                    + " is kernel-owned; obtain it from a provided-buffer CQE first");
        }
        return buffer.segment;
    }

    private synchronized boolean isCheckedOut(Buffer buffer) {
        requireOwned(buffer);
        return state == State.OPEN && buffer.state == BufferState.CHECKED_OUT;
    }

    private synchronized void releaseReceiveLease(long tokenUserData) {
        if (!receiveLeases.remove(tokenUserData)) {
            throw new IllegalStateException("provided-buffer receive lease was already released for token 0x"
                    + Long.toHexString(tokenUserData));
        }
    }

    private void requireOwned(Buffer buffer) {
        Objects.requireNonNull(buffer, "buffer");
        if (buffer.owner != this) {
            throw new IllegalArgumentException("buffer belongs to a different ProvidedBufferRing");
        }
    }

    private void requireOpen() {
        if (state != State.OPEN) {
            throw new IllegalStateException("ProvidedBufferRing is " + state.name().toLowerCase());
        }
    }

    private static void validateGroupId(int bufferGroupId) {
        if (bufferGroupId < 0 || bufferGroupId > 0xffff) {
            throw new IllegalArgumentException("bufferGroupId must fit u16: " + bufferGroupId);
        }
    }

    private static void validateEntries(int ringEntries) {
        if (ringEntries <= 0 || ringEntries > MAX_RING_ENTRIES
                || (ringEntries & (ringEntries - 1)) != 0) {
            throw new IllegalArgumentException("ringEntries must be a power of two in 1.."
                    + MAX_RING_ENTRIES + ": " + ringEntries);
        }
    }

    private static void validateBufferSize(long bufferSize) {
        if (bufferSize <= 0 || bufferSize > 0xffff_ffffL) {
            throw new IllegalArgumentException("bufferSize must be in 1..4294967295: " + bufferSize);
        }
    }

    private static void validateAlignment(long alignment) {
        if (alignment <= 0 || (alignment & (alignment - 1)) != 0) {
            throw new IllegalArgumentException("bufferAlignment must be a positive power of two: " + alignment);
        }
    }

    /** One native payload buffer, identified by the u16 ID encoded in a CQE. */
    public static final class Buffer {
        private final ProvidedBufferRing owner;
        private final int id;
        private final MemorySegment segment;
        private BufferState state = BufferState.IN_KERNEL;

        private Buffer(ProvidedBufferRing owner, int id, MemorySegment segment) {
            this.owner = owner;
            this.id = id;
            this.segment = segment;
        }

        /** The provided-buffer ID returned in {@link Completion#bufferId()}. */
        public int id() {
            return id;
        }

        public long byteSize() {
            return segment.byteSize();
        }

        /** True only while Java owns the buffer between {@link #take} and {@link #recycle}. */
        public boolean isCheckedOut() {
            return owner.isCheckedOut(this);
        }

        /**
         * Payload segment while Java owns it.  The segment is intentionally
         * hidden while the kernel owns the buffer, but callers should not
         * retain an old raw segment alias after {@link #recycle(Buffer)}.
         */
        public MemorySegment segment() {
            return owner.segmentFor(this);
        }
    }
}
