package uring;

import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;

import static java.lang.foreign.MemoryLayout.PathElement.groupElement;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/** {@code struct io_uring_cqe} (kernel {@code linux/io_uring.h}), 16 B. Offsets confirmed with C offsetof(). */
public final class Cqe {

    public static final int SIZE = 16;
    /** Upper 16 bits contain a selected provided-buffer ID. */
    public static final int FLAG_BUFFER = 1;
    /** Parent SQE will emit another CQE; this completion is not terminal. */
    public static final int FLAG_MORE = 1 << 1;
    /** A socket receive completed while more data was immediately readable. */
    public static final int FLAG_SOCK_NONEMPTY = 1 << 2;
    /** Terminal notification for an operation such as {@code IORING_OP_SEND_ZC}. */
    public static final int FLAG_NOTIF = 1 << 3;
    /** Incremental provided-buffer completion; the buffer is not whole/recyclable yet. */
    public static final int FLAG_BUF_MORE = 1 << 4;

    public static final StructLayout LAYOUT = MemoryLayout.structLayout(
            JAVA_LONG.withName("user_data"),
            JAVA_INT.withName("res"),
            JAVA_INT.withName("flags")
    ).withName("io_uring_cqe");

    public static final long OFF_USER_DATA = LAYOUT.byteOffset(groupElement("user_data"));
    public static final long OFF_RES = LAYOUT.byteOffset(groupElement("res"));
    public static final long OFF_FLAGS = LAYOUT.byteOffset(groupElement("flags"));

    static {
        if (LAYOUT.byteSize() != SIZE) {
            throw new AssertionError("io_uring_cqe layout size = " + LAYOUT.byteSize() + ", expected " + SIZE);
        }
    }

    private Cqe() {
    }

    public static long userData(MemorySegment cqe) {
        return cqe.get(JAVA_LONG, OFF_USER_DATA);
    }

    /** &gt;= 0: success (exact semantics depend on the opcode); &lt; 0: {@code -errno}. */
    public static int res(MemorySegment cqe) {
        return cqe.get(JAVA_INT, OFF_RES);
    }

    public static int flags(MemorySegment cqe) {
        return cqe.get(JAVA_INT, OFF_FLAGS);
    }

    /** True when the kernel retains the parent SQE for another completion. */
    public static boolean hasMore(int flags) {
        return (flags & FLAG_MORE) != 0;
    }

    public static boolean hasBuffer(int flags) {
        return (flags & FLAG_BUFFER) != 0;
    }

    /** True when a socket operation observed more immediately-readable input. */
    public static boolean hasSocketNonEmpty(int flags) {
        return (flags & FLAG_SOCK_NONEMPTY) != 0;
    }

    /** True when this CQE is a zero-copy notification rather than the data CQE. */
    public static boolean hasNotification(int flags) {
        return (flags & FLAG_NOTIF) != 0;
    }

    /** True when a provided buffer remains incrementally owned by the kernel. */
    public static boolean hasBufferMore(int flags) {
        return (flags & FLAG_BUF_MORE) != 0;
    }

    /** Buffer ID carried in the upper 16 CQE flag bits when {@link #hasBuffer(int)} is true. */
    public static int bufferId(int flags) {
        return flags >>> 16;
    }

    public static void main(String[] args) {
        System.out.println("io_uring_cqe size = " + LAYOUT.byteSize() + " (expect 16)");
        System.out.println("user_data@" + OFF_USER_DATA + " res@" + OFF_RES + " flags@" + OFF_FLAGS);
        int providedBufferFlags = FLAG_BUFFER | (0xBEEF << 16);
        if (!hasMore(FLAG_MORE) || hasMore(0)
                || !hasBuffer(providedBufferFlags) || bufferId(providedBufferFlags) != 0xBEEF
                || !hasSocketNonEmpty(FLAG_SOCK_NONEMPTY) || hasSocketNonEmpty(0)
                || !hasNotification(FLAG_NOTIF) || hasNotification(0)
                || !hasBufferMore(FLAG_BUF_MORE) || hasBufferMore(0)) {
            throw new AssertionError("IORING_CQE_F_MORE flag helper is incorrect");
        }
        System.out.println("PASS: Cqe layout OK");
    }
}
