package uring;

import java.lang.foreign.MemoryLayout;
import java.lang.foreign.StructLayout;

import static java.lang.foreign.MemoryLayout.PathElement.groupElement;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * Layout of {@code struct io_uring_params} (kernel {@code linux/io_uring.h}, l. 607-618),
 * 120 B total: 7 x u32 + resv[3] (= 40 B) + io_sqring_offsets (40 B) + io_cqring_offsets (40 B).
 * All offsets are read from the layout via {@code byteOffset(groupElement(...))} — never hardcoded.
 */
public final class IoUringParams {

    public static final StructLayout SQ_OFFSETS = MemoryLayout.structLayout(
            JAVA_INT.withName("head"),
            JAVA_INT.withName("tail"),
            JAVA_INT.withName("ring_mask"),
            JAVA_INT.withName("ring_entries"),
            JAVA_INT.withName("flags"),
            JAVA_INT.withName("dropped"),
            JAVA_INT.withName("array"),
            JAVA_INT.withName("resv1"),
            JAVA_LONG.withName("user_addr")
    ).withName("io_sqring_offsets");

    public static final StructLayout CQ_OFFSETS = MemoryLayout.structLayout(
            JAVA_INT.withName("head"),
            JAVA_INT.withName("tail"),
            JAVA_INT.withName("ring_mask"),
            JAVA_INT.withName("ring_entries"),
            JAVA_INT.withName("overflow"),
            JAVA_INT.withName("cqes"),
            JAVA_INT.withName("flags"),
            JAVA_INT.withName("resv1"),
            JAVA_LONG.withName("user_addr")
    ).withName("io_cqring_offsets");

    public static final StructLayout LAYOUT = MemoryLayout.structLayout(
            JAVA_INT.withName("sq_entries"),
            JAVA_INT.withName("cq_entries"),
            JAVA_INT.withName("flags"),
            JAVA_INT.withName("sq_thread_cpu"),
            JAVA_INT.withName("sq_thread_idle"),
            JAVA_INT.withName("features"),
            JAVA_INT.withName("wq_fd"),
            MemoryLayout.sequenceLayout(3, JAVA_INT).withName("resv"),
            SQ_OFFSETS.withName("sq_off"),
            CQ_OFFSETS.withName("cq_off")
    ).withName("io_uring_params");

    // io_uring_params top-level fields
    public static final long OFF_SQ_ENTRIES = LAYOUT.byteOffset(groupElement("sq_entries"));
    public static final long OFF_CQ_ENTRIES = LAYOUT.byteOffset(groupElement("cq_entries"));
    public static final long OFF_FLAGS = LAYOUT.byteOffset(groupElement("flags"));
    public static final long OFF_SQ_THREAD_CPU = LAYOUT.byteOffset(groupElement("sq_thread_cpu"));
    public static final long OFF_SQ_THREAD_IDLE = LAYOUT.byteOffset(groupElement("sq_thread_idle"));
    public static final long OFF_FEATURES = LAYOUT.byteOffset(groupElement("features"));
    public static final long OFF_WQ_FD = LAYOUT.byteOffset(groupElement("wq_fd"));
    public static final long OFF_SQ_OFF = LAYOUT.byteOffset(groupElement("sq_off"));
    public static final long OFF_CQ_OFF = LAYOUT.byteOffset(groupElement("cq_off"));

    // sq_off.* — absolute offsets within io_uring_params
    public static final long SQ_OFF_HEAD = LAYOUT.byteOffset(groupElement("sq_off"), groupElement("head"));
    public static final long SQ_OFF_TAIL = LAYOUT.byteOffset(groupElement("sq_off"), groupElement("tail"));
    public static final long SQ_OFF_RING_MASK = LAYOUT.byteOffset(groupElement("sq_off"), groupElement("ring_mask"));
    public static final long SQ_OFF_RING_ENTRIES = LAYOUT.byteOffset(groupElement("sq_off"), groupElement("ring_entries"));
    public static final long SQ_OFF_FLAGS = LAYOUT.byteOffset(groupElement("sq_off"), groupElement("flags"));
    public static final long SQ_OFF_DROPPED = LAYOUT.byteOffset(groupElement("sq_off"), groupElement("dropped"));
    public static final long SQ_OFF_ARRAY = LAYOUT.byteOffset(groupElement("sq_off"), groupElement("array"));

    // cq_off.* — absolute offsets within io_uring_params
    public static final long CQ_OFF_HEAD = LAYOUT.byteOffset(groupElement("cq_off"), groupElement("head"));
    public static final long CQ_OFF_TAIL = LAYOUT.byteOffset(groupElement("cq_off"), groupElement("tail"));
    public static final long CQ_OFF_RING_MASK = LAYOUT.byteOffset(groupElement("cq_off"), groupElement("ring_mask"));
    public static final long CQ_OFF_RING_ENTRIES = LAYOUT.byteOffset(groupElement("cq_off"), groupElement("ring_entries"));
    public static final long CQ_OFF_OVERFLOW = LAYOUT.byteOffset(groupElement("cq_off"), groupElement("overflow"));
    public static final long CQ_OFF_CQES = LAYOUT.byteOffset(groupElement("cq_off"), groupElement("cqes"));
    public static final long CQ_OFF_FLAGS = LAYOUT.byteOffset(groupElement("cq_off"), groupElement("flags"));

    static {
        if (LAYOUT.byteSize() != 120) {
            throw new AssertionError("io_uring_params layout size = " + LAYOUT.byteSize() + ", expected 120");
        }
        if (SQ_OFFSETS.byteSize() != 40 || CQ_OFFSETS.byteSize() != 40) {
            throw new AssertionError("sq/cq offsets layout size mismatch");
        }
    }

    private IoUringParams() {
    }

    /** Step 2 check: layout sizes and a few key offsets. */
    public static void main(String[] args) {
        System.out.println("io_uring_params size = " + LAYOUT.byteSize() + " (expect 120)");
        System.out.println("sq_off @ " + OFF_SQ_OFF + " (expect 40)");
        System.out.println("cq_off @ " + OFF_CQ_OFF + " (expect 80)");
        System.out.println("sq_off.array @ " + SQ_OFF_ARRAY);
        System.out.println("cq_off.cqes @ " + CQ_OFF_CQES);
        System.out.println("PASS: IoUringParams layout OK");
    }
}
