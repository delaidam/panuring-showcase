package uring;

import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;

import static java.lang.foreign.MemoryLayout.PathElement.groupElement;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;

/**
 * {@code struct io_uring_sqe} (kernel {@code linux/io_uring.h}), 64 B.
 * Offsets verified via C {@code offsetof()} on this machine (not
 * transcribed by hand): fd@4, off@8, addr@16, len@24, user_data@32,
 * sizeof=64 — matches ROADMAP.md §2 step 4.
 * The remaining fields (unions for rw_flags/buf_index/...) aren't
 * named individually — L1/L2 don't need them; covered by padding up to 64 B.
 */
public final class Sqe {

    public static final int SIZE = 64;

    public static final int OP_NOP = 0;
    public static final int OP_READV = 1;
    public static final int OP_WRITEV = 2;
    public static final int OP_FSYNC = 3;
    public static final int OP_READ_FIXED = 4;
    public static final int OP_WRITE_FIXED = 5;
    public static final int OP_TIMEOUT = 11;
    public static final int OP_ACCEPT = 13;
    public static final int OP_ASYNC_CANCEL = 14;
    public static final int OP_READ = 22;
    public static final int OP_WRITE = 23;
    public static final int OP_SEND = 26;
    public static final int OP_RECV = 27;
    /** {@code IORING_OP_SEND_ZC}; emits a data CQE and a terminal notification CQE. */
    public static final int OP_SEND_ZC = 47;

    public static final int IOSQE_FIXED_FILE = 1;
    /** Link this SQE to the next one; a failed predecessor cancels its successor. */
    public static final int IOSQE_IO_LINK = 1 << 2;
    /** Select a buffer from the {@code buf_group} instead of using {@code addr}. */
    public static final int IOSQE_BUFFER_SELECT = 1 << 5;
    /** Ask SEND_ZC to report whether the kernel actually used zero-copy. */
    public static final int IORING_SEND_ZC_REPORT_USAGE = 1 << 3;
    /** {@code IORING_ACCEPT_MULTISHOT}, stored in {@code sqe.ioprio}. */
    public static final int IORING_ACCEPT_MULTISHOT = 1;
    /** {@code IORING_RECV_MULTISHOT}, stored in {@code sqe.ioprio}. */
    public static final int IORING_RECV_MULTISHOT = 1 << 1;

    public static final StructLayout LAYOUT = MemoryLayout.structLayout(
            JAVA_BYTE.withName("opcode"),
            JAVA_BYTE.withName("flags"),
            JAVA_SHORT.withName("ioprio"),
            JAVA_INT.withName("fd"),
            JAVA_LONG.withName("off"),
            JAVA_LONG.withName("addr"),
            JAVA_INT.withName("len"),
            JAVA_INT.withName("rw_flags"),
            JAVA_LONG.withName("user_data"),
            JAVA_SHORT.withName("buf_index"),
            JAVA_SHORT.withName("personality"),
            JAVA_INT.withName("file_index"),
            MemoryLayout.paddingLayout(16)
    ).withName("io_uring_sqe");

    public static final long OFF_OPCODE = LAYOUT.byteOffset(groupElement("opcode"));
    public static final long OFF_FLAGS = LAYOUT.byteOffset(groupElement("flags"));
    public static final long OFF_IOPRIO = LAYOUT.byteOffset(groupElement("ioprio"));
    public static final long OFF_FD = LAYOUT.byteOffset(groupElement("fd"));
    public static final long OFF_OFF = LAYOUT.byteOffset(groupElement("off"));
    /** {@code addr2} union alias: {@code socklen_t *} for {@link #OP_ACCEPT}. */
    public static final long OFF_ADDR2 = OFF_OFF;
    public static final long OFF_ADDR = LAYOUT.byteOffset(groupElement("addr"));
    public static final long OFF_LEN = LAYOUT.byteOffset(groupElement("len"));
    /** Union slot used as {@code cancel_flags} for {@link #OP_ASYNC_CANCEL}. */
    public static final long OFF_RW_FLAGS = LAYOUT.byteOffset(groupElement("rw_flags"));
    public static final long OFF_CANCEL_FLAGS = OFF_RW_FLAGS;
    /** Union alias: {@code accept4(2)} flags for {@link #OP_ACCEPT}. */
    public static final long OFF_ACCEPT_FLAGS = OFF_RW_FLAGS;
    /** Union alias: {@code send(2)}/{@code recv(2)} message flags. */
    public static final long OFF_MSG_FLAGS = OFF_RW_FLAGS;
    public static final long OFF_USER_DATA = LAYOUT.byteOffset(groupElement("user_data"));
    public static final long OFF_BUF_INDEX = LAYOUT.byteOffset(groupElement("buf_index"));
    /** Union alias: provided-buffer group ID for {@link #IOSQE_BUFFER_SELECT}. */
    public static final long OFF_BUF_GROUP = OFF_BUF_INDEX;

    static {
        if (LAYOUT.byteSize() != SIZE) {
            throw new AssertionError("io_uring_sqe layout size = " + LAYOUT.byteSize() + ", expected " + SIZE);
        }
    }

    private Sqe() {
    }

    /** Reads back the opcode of an already-prepped slot (for in-flight bookkeeping/diagnostics). */
    public static int opcode(MemorySegment sqe) {
        return Byte.toUnsignedInt(sqe.get(JAVA_BYTE, OFF_OPCODE));
    }

    /** Reads back the user_data of an already-prepped slot (for in-flight bookkeeping/diagnostics). */
    public static long userData(MemorySegment sqe) {
        return sqe.get(JAVA_LONG, OFF_USER_DATA);
    }

    /** Zeroes the whole slot then writes opcode=NOP, fd=-1, user_data=marker. */
    public static void prepNop(MemorySegment sqe, long userData) {
        sqe.fill((byte) 0);
        sqe.set(JAVA_BYTE, OFF_OPCODE, (byte) OP_NOP);
        sqe.set(JAVA_INT, OFF_FD, -1);
        sqe.set(JAVA_LONG, OFF_USER_DATA, userData);
    }

    /** Zeroes the whole slot then prepares a READ on the given fd/offset/buffer. */
    public static void prepRead(MemorySegment sqe, int fd, MemorySegment buf, int len, long fileOffset, long userData) {
        sqe.fill((byte) 0);
        sqe.set(JAVA_BYTE, OFF_OPCODE, (byte) OP_READ);
        sqe.set(JAVA_INT, OFF_FD, fd);
        sqe.set(JAVA_LONG, OFF_OFF, fileOffset);
        sqe.set(JAVA_LONG, OFF_ADDR, buf.address());
        sqe.set(JAVA_INT, OFF_LEN, len);
        sqe.set(JAVA_LONG, OFF_USER_DATA, userData);
    }

    /**
     * Zeroes the whole slot then prepares a READ against a *registered*
     * buffer ({@code IORING_OP_READ_FIXED}) — {@code buf} must be one of
     * the segments previously passed to {@code Uring.registerBuffers},
     * and {@code bufIndex} its position in that array.
     */
    public static void prepReadFixed(MemorySegment sqe, int fd, MemorySegment buf, int len, long fileOffset,
                                      long userData, int bufIndex) {
        sqe.fill((byte) 0);
        sqe.set(JAVA_BYTE, OFF_OPCODE, (byte) OP_READ_FIXED);
        sqe.set(JAVA_INT, OFF_FD, fd);
        sqe.set(JAVA_LONG, OFF_OFF, fileOffset);
        sqe.set(JAVA_LONG, OFF_ADDR, buf.address());
        sqe.set(JAVA_INT, OFF_LEN, len);
        sqe.set(JAVA_LONG, OFF_USER_DATA, userData);
        sqe.set(JAVA_SHORT, OFF_BUF_INDEX, (short) bufIndex);
    }

    /**
     * Prepares {@code IORING_OP_TIMEOUT}. {@code timespec} is a live 16-byte
     * {@code struct __kernel_timespec { s64 tv_sec; s64 tv_nsec; }} and must
     * outlive the timeout's terminal CQE. {@code count=0} requests one shot.
     */
    public static void prepTimeout(MemorySegment sqe, MemorySegment timespec, long userData) {
        sqe.fill((byte) 0);
        sqe.set(JAVA_BYTE, OFF_OPCODE, (byte) OP_TIMEOUT);
        sqe.set(JAVA_INT, OFF_FD, -1);
        sqe.set(JAVA_LONG, OFF_ADDR, timespec.address());
        // io_uring_prep_timeout() encodes the timespec-array length here;
        // the number of CQEs to wait for is the (zeroed) off field.  A
        // zero len is rejected by the kernel with -EINVAL rather than
        // arming the timeout.
        sqe.set(JAVA_INT, OFF_LEN, 1);
        sqe.set(JAVA_LONG, OFF_USER_DATA, userData);
    }

    /**
     * Prepares {@code IORING_OP_ASYNC_CANCEL} keyed by the target operation's
     * {@code user_data}. The cancel request has its own distinct
     * {@code cancelUserData} completion key. Lifecycle code must still wait
     * for the original operation's terminal CQE before freeing its buffer;
     * a successful cancellation CQE alone is not a lifetime release.
     */
    public static void prepAsyncCancel(MemorySegment sqe, long targetUserData, long cancelUserData) {
        sqe.fill((byte) 0);
        sqe.set(JAVA_BYTE, OFF_OPCODE, (byte) OP_ASYNC_CANCEL);
        sqe.set(JAVA_INT, OFF_FD, -1);
        sqe.set(JAVA_LONG, OFF_ADDR, targetUserData);
        sqe.set(JAVA_INT, OFF_CANCEL_FLAGS, 0); // default: match target user_data
        sqe.set(JAVA_LONG, OFF_USER_DATA, cancelUserData);
    }

    /**
     * Prepares one {@code IORING_OP_ACCEPT}. Both peer-address arguments may
     * be {@link MemorySegment#NULL} when the caller intentionally discards
     * the peer address (the usual fast-path for an echo service). When
     * supplied, {@code peerAddressLength} must point to a live native
     * {@code socklen_t} through the CQE.
     */
    public static void prepAccept(MemorySegment sqe, int listenerFd,
                                  MemorySegment peerAddress, MemorySegment peerAddressLength,
                                  int acceptFlags, long userData) {
        sqe.fill((byte) 0);
        sqe.set(JAVA_BYTE, OFF_OPCODE, (byte) OP_ACCEPT);
        sqe.set(JAVA_INT, OFF_FD, listenerFd);
        sqe.set(JAVA_LONG, OFF_ADDR, peerAddress.address());
        sqe.set(JAVA_LONG, OFF_ADDR2, peerAddressLength.address());
        sqe.set(JAVA_INT, OFF_ACCEPT_FLAGS, acceptFlags);
        sqe.set(JAVA_LONG, OFF_USER_DATA, userData);
    }

    /**
     * Prepares a multishot accept without peer-address storage. Each accepted
     * descriptor arrives in a CQE carrying {@link Cqe#FLAG_MORE}; cancel the
     * parent and wait for its non-MORE terminal CQE before closing the ring.
     */
    public static void prepAcceptMultishot(MemorySegment sqe, int listenerFd,
                                            int acceptFlags, long userData) {
        sqe.fill((byte) 0);
        sqe.set(JAVA_BYTE, OFF_OPCODE, (byte) OP_ACCEPT);
        sqe.set(JAVA_SHORT, OFF_IOPRIO, (short) IORING_ACCEPT_MULTISHOT);
        sqe.set(JAVA_INT, OFF_FD, listenerFd);
        sqe.set(JAVA_INT, OFF_ACCEPT_FLAGS, acceptFlags);
        sqe.set(JAVA_LONG, OFF_USER_DATA, userData);
    }

    /** Prepares one {@code IORING_OP_RECV}; {@code buffer} must outlive its CQE. */
    public static void prepRecv(MemorySegment sqe, int socketFd, MemorySegment buffer,
                                int len, int messageFlags, long userData) {
        requireBufferRange(buffer, len, "recv buffer");
        sqe.fill((byte) 0);
        sqe.set(JAVA_BYTE, OFF_OPCODE, (byte) OP_RECV);
        sqe.set(JAVA_INT, OFF_FD, socketFd);
        sqe.set(JAVA_LONG, OFF_ADDR, buffer.address());
        sqe.set(JAVA_INT, OFF_LEN, len);
        sqe.set(JAVA_INT, OFF_MSG_FLAGS, messageFlags);
        sqe.set(JAVA_LONG, OFF_USER_DATA, userData);
    }

    /**
     * Prepares {@code IORING_OP_RECV} with {@code IOSQE_BUFFER_SELECT}.
     * The kernel chooses one descriptor from the matching
     * {@link ProvidedBufferRing} and reports its ID through
     * {@link Cqe#bufferId(int)} with {@link Cqe#FLAG_BUFFER}.  There is no
     * userspace {@code addr}: the provided buffer ring owns it.
     *
     * <p>The selected buffer must be returned through
     * {@link ProvidedBufferRing#recycle(ProvidedBufferRing.Buffer)} only
     * after its CQE payload has been consumed.  {@code maxLen} is the
     * requested receive limit, not a Java segment length.
     */
    public static void prepRecvSelect(MemorySegment sqe, int socketFd, int maxLen,
                                      int messageFlags, int bufferGroup, long userData) {
        if (maxLen < 0) {
            throw new IllegalArgumentException("maxLen must be non-negative: " + maxLen);
        }
        if (bufferGroup < 0 || bufferGroup > 0xffff) {
            throw new IllegalArgumentException("bufferGroup must fit u16: " + bufferGroup);
        }
        sqe.fill((byte) 0);
        sqe.set(JAVA_BYTE, OFF_OPCODE, (byte) OP_RECV);
        sqe.set(JAVA_BYTE, OFF_FLAGS, (byte) IOSQE_BUFFER_SELECT);
        sqe.set(JAVA_INT, OFF_FD, socketFd);
        sqe.set(JAVA_INT, OFF_LEN, maxLen);
        sqe.set(JAVA_INT, OFF_MSG_FLAGS, messageFlags);
        sqe.set(JAVA_LONG, OFF_USER_DATA, userData);
        sqe.set(JAVA_SHORT, OFF_BUF_GROUP, (short) bufferGroup);
    }

    /**
     * Prepares a provided-buffer multishot receive. The supplied group must
     * remain registered until the parent token's terminal CQE, and every
     * selected buffer must be {@link ProvidedBufferRing#recycle}d separately.
     */
    public static void prepRecvMultishotSelect(MemorySegment sqe, int socketFd, int maxLen,
                                               int messageFlags, int bufferGroup, long userData) {
        prepRecvSelect(sqe, socketFd, maxLen, messageFlags, bufferGroup, userData);
        sqe.set(JAVA_SHORT, OFF_IOPRIO, (short) IORING_RECV_MULTISHOT);
    }

    /** Prepares one {@code IORING_OP_SEND}; {@code buffer} must outlive its CQE. */
    public static void prepSend(MemorySegment sqe, int socketFd, MemorySegment buffer,
                                int len, int messageFlags, long userData) {
        requireBufferRange(buffer, len, "send buffer");
        sqe.fill((byte) 0);
        sqe.set(JAVA_BYTE, OFF_OPCODE, (byte) OP_SEND);
        sqe.set(JAVA_INT, OFF_FD, socketFd);
        sqe.set(JAVA_LONG, OFF_ADDR, buffer.address());
        sqe.set(JAVA_INT, OFF_LEN, len);
        sqe.set(JAVA_INT, OFF_MSG_FLAGS, messageFlags);
        sqe.set(JAVA_LONG, OFF_USER_DATA, userData);
    }

    /**
     * Prepares {@code IORING_OP_SEND_ZC}. Unlike an ordinary send, this
     * operation has a data CQE with {@link Cqe#FLAG_MORE} followed by a
     * terminal {@link Cqe#FLAG_NOTIF} CQE; use a {@link MultiShotOp} token so
     * the source buffer cannot be reused before that notification lands.
     *
     * @param zeroCopyFlags SEND_ZC usage flags stored in {@code sqe.ioprio}
     */
    public static void prepSendZc(MemorySegment sqe, int socketFd, MemorySegment buffer,
                                  int len, int messageFlags, int zeroCopyFlags, long userData) {
        requireBufferRange(buffer, len, "send_zc buffer");
        if (zeroCopyFlags < 0 || zeroCopyFlags > 0xffff) {
            throw new IllegalArgumentException("send_zc flags must fit u16: " + zeroCopyFlags);
        }
        sqe.fill((byte) 0);
        sqe.set(JAVA_BYTE, OFF_OPCODE, (byte) OP_SEND_ZC);
        sqe.set(JAVA_SHORT, OFF_IOPRIO, (short) zeroCopyFlags);
        sqe.set(JAVA_INT, OFF_FD, socketFd);
        sqe.set(JAVA_LONG, OFF_ADDR, buffer.address());
        sqe.set(JAVA_INT, OFF_LEN, len);
        sqe.set(JAVA_INT, OFF_MSG_FLAGS, messageFlags);
        sqe.set(JAVA_LONG, OFF_USER_DATA, userData);
    }

    private static void requireBufferRange(MemorySegment buffer, int len, String label) {
        if (buffer == null) {
            throw new NullPointerException(label);
        }
        if (len < 0 || len > buffer.byteSize()) {
            throw new IllegalArgumentException(label + " length=" + len + " outside segment size="
                    + buffer.byteSize());
        }
    }

    /**
     * Marks an already-prepped SQE to address its file through the
     * registered-files array instead of a real fd. Call the normal
     * {@code prepXxx} first with the registered index in place of the
     * fd argument (ROADMAP.md §4 step 7: "fd polje = indeks"), then
     * this to set {@code IOSQE_FIXED_FILE}.
     */
    public static void markFixedFile(MemorySegment sqe) {
        byte flags = sqe.get(JAVA_BYTE, OFF_FLAGS);
        sqe.set(JAVA_BYTE, OFF_FLAGS, (byte) (flags | IOSQE_FIXED_FILE));
    }

    /**
     * Links this SQE to the next SQE in the same published batch. Used by the
     * retrieval prefetch path so a dependent read chain has one explicit
     * kernel ordering boundary rather than relying on accidental CQ order.
     */
    public static void markIoLink(MemorySegment sqe) {
        byte flags = sqe.get(JAVA_BYTE, OFF_FLAGS);
        sqe.set(JAVA_BYTE, OFF_FLAGS, (byte) (flags | IOSQE_IO_LINK));
    }

    /** Zeroes the whole slot then prepares a WRITE on the given fd/offset/buffer. */
    public static void prepWrite(MemorySegment sqe, int fd, MemorySegment buf, int len, long fileOffset, long userData) {
        sqe.fill((byte) 0);
        sqe.set(JAVA_BYTE, OFF_OPCODE, (byte) OP_WRITE);
        sqe.set(JAVA_INT, OFF_FD, fd);
        sqe.set(JAVA_LONG, OFF_OFF, fileOffset);
        sqe.set(JAVA_LONG, OFF_ADDR, buf.address());
        sqe.set(JAVA_INT, OFF_LEN, len);
        sqe.set(JAVA_LONG, OFF_USER_DATA, userData);
    }

    /** Step 4 check: layout size + NOP prep on a scratch segment. */
    public static void main(String[] args) {
        System.out.println("io_uring_sqe size = " + LAYOUT.byteSize() + " (expect 64)");
        System.out.println("fd@" + OFF_FD + " off@" + OFF_OFF + " addr@" + OFF_ADDR
                + " len@" + OFF_LEN + " user_data@" + OFF_USER_DATA);
        try (var arena = java.lang.foreign.Arena.ofConfined()) {
            MemorySegment slot = arena.allocate(SIZE);
            prepNop(slot, 0x42L);
            byte opcode = slot.get(JAVA_BYTE, OFF_OPCODE);
            int fd = slot.get(JAVA_INT, OFF_FD);
            long userData = slot.get(JAVA_LONG, OFF_USER_DATA);
            System.out.printf("prepNop -> opcode=%d fd=%d user_data=0x%x%n", opcode, fd, userData);
            if (opcode != OP_NOP || fd != -1 || userData != 0x42L) {
                throw new AssertionError("prepNop produced unexpected bytes");
            }
            prepAsyncCancel(slot, 0x43L, 0x44L);
            if (opcode(slot) != OP_ASYNC_CANCEL
                    || slot.get(JAVA_INT, OFF_FD) != -1
                    || slot.get(JAVA_LONG, OFF_ADDR) != 0x43L
                    || slot.get(JAVA_INT, OFF_CANCEL_FLAGS) != 0
                    || userData(slot) != 0x44L) {
                throw new AssertionError("prepAsyncCancel produced unexpected bytes");
            }
            MemorySegment timespec = arena.allocate(16, 8);
            prepTimeout(slot, timespec, 0x45L);
            if (opcode(slot) != OP_TIMEOUT || slot.get(JAVA_INT, OFF_FD) != -1
                    || slot.get(JAVA_LONG, OFF_ADDR) != timespec.address()
                    || slot.get(JAVA_INT, OFF_LEN) != 1 || userData(slot) != 0x45L) {
                throw new AssertionError("prepTimeout produced unexpected bytes");
            }
            MemorySegment payload = arena.allocate(8, 8);
            prepAccept(slot, 7, MemorySegment.NULL, MemorySegment.NULL, 0x80000, 0x46L);
            if (opcode(slot) != OP_ACCEPT || slot.get(JAVA_INT, OFF_FD) != 7
                    || slot.get(JAVA_LONG, OFF_ADDR) != 0
                    || slot.get(JAVA_LONG, OFF_ADDR2) != 0
                    || slot.get(JAVA_INT, OFF_ACCEPT_FLAGS) != 0x80000
                    || userData(slot) != 0x46L) {
                throw new AssertionError("prepAccept produced unexpected bytes");
            }
            prepAcceptMultishot(slot, 7, 0x80800, 0x461L);
            if (opcode(slot) != OP_ACCEPT
                    || Short.toUnsignedInt(slot.get(JAVA_SHORT, OFF_IOPRIO)) != IORING_ACCEPT_MULTISHOT
                    || slot.get(JAVA_LONG, OFF_ADDR) != 0 || slot.get(JAVA_LONG, OFF_ADDR2) != 0
                    || slot.get(JAVA_INT, OFF_ACCEPT_FLAGS) != 0x80800 || userData(slot) != 0x461L) {
                throw new AssertionError("prepAcceptMultishot produced unexpected bytes");
            }
            prepRecv(slot, 8, payload, 8, 0, 0x47L);
            if (opcode(slot) != OP_RECV || slot.get(JAVA_INT, OFF_FD) != 8
                    || slot.get(JAVA_LONG, OFF_ADDR) != payload.address()
                    || slot.get(JAVA_INT, OFF_LEN) != 8 || userData(slot) != 0x47L) {
                throw new AssertionError("prepRecv produced unexpected bytes");
            }
            prepRecvSelect(slot, 8, 4096, 0x40, 0xBEEF, 0x471L);
            if (opcode(slot) != OP_RECV
                    || Byte.toUnsignedInt(slot.get(JAVA_BYTE, OFF_FLAGS)) != IOSQE_BUFFER_SELECT
                    || slot.get(JAVA_INT, OFF_FD) != 8
                    || slot.get(JAVA_LONG, OFF_ADDR) != 0
                    || slot.get(JAVA_INT, OFF_LEN) != 4096
                    || slot.get(JAVA_INT, OFF_MSG_FLAGS) != 0x40
                    || Short.toUnsignedInt(slot.get(JAVA_SHORT, OFF_BUF_GROUP)) != 0xBEEF
                    || userData(slot) != 0x471L) {
                throw new AssertionError("prepRecvSelect produced unexpected bytes");
            }
            prepRecvMultishotSelect(slot, 8, 4096, 0x40, 0xBEEF, 0x472L);
            if (opcode(slot) != OP_RECV || Short.toUnsignedInt(slot.get(JAVA_SHORT, OFF_IOPRIO))
                    != IORING_RECV_MULTISHOT
                    || Byte.toUnsignedInt(slot.get(JAVA_BYTE, OFF_FLAGS)) != IOSQE_BUFFER_SELECT
                    || Short.toUnsignedInt(slot.get(JAVA_SHORT, OFF_BUF_GROUP)) != 0xBEEF
                    || userData(slot) != 0x472L) {
                throw new AssertionError("prepRecvMultishotSelect produced unexpected bytes");
            }
            prepSend(slot, 9, payload, 8, 0x4000, 0x48L);
            if (opcode(slot) != OP_SEND || slot.get(JAVA_INT, OFF_FD) != 9
                    || slot.get(JAVA_INT, OFF_MSG_FLAGS) != 0x4000 || userData(slot) != 0x48L) {
                throw new AssertionError("prepSend produced unexpected bytes");
            }
            prepSendZc(slot, 10, payload, 8, 0x4000, IORING_SEND_ZC_REPORT_USAGE, 0x481L);
            if (opcode(slot) != OP_SEND_ZC || slot.get(JAVA_INT, OFF_FD) != 10
                    || Short.toUnsignedInt(slot.get(JAVA_SHORT, OFF_IOPRIO)) != IORING_SEND_ZC_REPORT_USAGE
                    || slot.get(JAVA_INT, OFF_MSG_FLAGS) != 0x4000 || userData(slot) != 0x481L) {
                throw new AssertionError("prepSendZc produced unexpected bytes");
            }
            prepNop(slot, 0x49L);
            markIoLink(slot);
            if (Byte.toUnsignedInt(slot.get(JAVA_BYTE, OFF_FLAGS)) != IOSQE_IO_LINK) {
                throw new AssertionError("markIoLink did not set IOSQE_IO_LINK");
            }
        }
        System.out.println("PASS: Sqe layout + core/file/socket prep helpers OK");
    }
}
