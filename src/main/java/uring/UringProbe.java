package uring;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;

/**
 * Immutable result of {@code IORING_REGISTER_PROBE}.
 *
 * <p>The setup feature bits in {@link Rings#features} describe ring-level
 * behavior, not which SQE opcodes this particular kernel accepts.  Network
 * code must therefore use this probe before assuming that an opcode such as
 * {@link Sqe#OP_ACCEPT}, {@link Sqe#OP_RECV}, or {@code SEND_ZC} is usable.
 * The native probe record is copied into ordinary Java state before its
 * temporary native allocation is released.
 */
public final class UringProbe {

    /** {@code IORING_REGISTER_PROBE} from {@code linux/io_uring.h}. */
    public static final int IORING_REGISTER_PROBE = 8;
    /** {@code IO_URING_OP_SUPPORTED} from {@code linux/io_uring.h}. */
    public static final int IO_URING_OP_SUPPORTED = 1;

    /** Fixed prefix of {@code struct io_uring_probe}. */
    public static final int HEADER_SIZE = 16;
    /** {@code struct io_uring_probe_op}. */
    public static final int OP_SIZE = 8;
    /** Opcode values and the kernel's {@code last_op} field are unsigned bytes. */
    public static final int MAX_OPCODE = 0xff;
    /** Largest useful request to the current byte-addressed probe ABI. */
    public static final int MAX_PROBE_OPS = MAX_OPCODE + 1;

    private static final long OFF_LAST_OP = 0;
    private static final long OFF_OPS_LEN = 1;
    private static final long OFF_OPS = HEADER_SIZE;
    private static final long OFF_OP_OPCODE = 0;
    private static final long OFF_OP_FLAGS = 2;

    private final int lastOp;
    private final int reportedOpsLength;
    private final int[] flagsByOpcode;

    private UringProbe(int lastOp, int reportedOpsLength, int[] flagsByOpcode) {
        this.lastOp = lastOp;
        this.reportedOpsLength = reportedOpsLength;
        this.flagsByOpcode = flagsByOpcode;
    }

    /**
     * Queries every opcode representable by the current probe ABI.  A kernel
     * can return fewer records; callers must use {@link #supports(int)}
     * rather than infer support from a kernel release number.
     */
    public static UringProbe query(Uring ring) {
        return query(ring, MAX_PROBE_OPS);
    }

    /**
     * Queries up to {@code requestedOps} probe records.  This overload is
     * useful to make a deliberately narrow startup contract visible in a
     * benchmark manifest.
     */
    public static UringProbe query(Uring ring, int requestedOps) {
        Objects.requireNonNull(ring, "ring");
        if (requestedOps <= 0 || requestedOps > MAX_PROBE_OPS) {
            throw new IllegalArgumentException("requestedOps must be in 1.." + MAX_PROBE_OPS
                    + ": " + requestedOps);
        }

        try (Arena arena = Arena.ofConfined()) {
            long bytes = Math.addExact(HEADER_SIZE, Math.multiplyExact((long) requestedOps, OP_SIZE));
            MemorySegment nativeProbe = arena.allocate(bytes, Long.BYTES);
            MemorySegment capture = Syscalls.newCaptureSegment(arena);
            long rc = Syscalls.ioUringRegister(capture, ring.fd(), IORING_REGISTER_PROBE,
                    nativeProbe, requestedOps);
            if (rc < 0) {
                throw new UringException("io_uring_register(REGISTER_PROBE)", Syscalls.errno(capture));
            }

            int lastOp = Byte.toUnsignedInt(nativeProbe.get(JAVA_BYTE, OFF_LAST_OP));
            int reportedOps = Byte.toUnsignedInt(nativeProbe.get(JAVA_BYTE, OFF_OPS_LEN));
            int readableOps = Math.min(reportedOps, requestedOps);
            int[] flags = new int[MAX_PROBE_OPS];
            for (int index = 0; index < readableOps; index++) {
                long offset = OFF_OPS + (long) index * OP_SIZE;
                int opcode = Byte.toUnsignedInt(nativeProbe.get(JAVA_BYTE, offset + OFF_OP_OPCODE));
                int opFlags = Short.toUnsignedInt(nativeProbe.get(JAVA_SHORT, offset + OFF_OP_FLAGS));
                flags[opcode] = opFlags;
            }
            return new UringProbe(lastOp, reportedOps, flags);
        }
    }

    /** Highest opcode the kernel says it knows about (inclusive). */
    public int lastOp() {
        return lastOp;
    }

    /** Number of opcode records returned in the native probe response. */
    public int reportedOpsLength() {
        return reportedOpsLength;
    }

    /** True iff the kernel marked this opcode with {@link #IO_URING_OP_SUPPORTED}. */
    public boolean supports(int opcode) {
        return opcode >= 0 && opcode <= MAX_OPCODE
                && (flagsByOpcode[opcode] & IO_URING_OP_SUPPORTED) != 0;
    }

    /** Raw {@code io_uring_probe_op.flags} for diagnostics, or zero when absent. */
    public int flags(int opcode) {
        return opcode >= 0 && opcode <= MAX_OPCODE ? flagsByOpcode[opcode] : 0;
    }

    /** A deterministic snapshot suitable for recording in a run manifest. */
    public List<Operation> supportedOperations() {
        List<Operation> result = new ArrayList<>();
        for (int opcode = 0; opcode <= MAX_OPCODE; opcode++) {
            if (supports(opcode)) {
                result.add(new Operation(opcode, flagsByOpcode[opcode]));
            }
        }
        return List.copyOf(result);
    }

    /** One supported opcode and its raw kernel flags. */
    public record Operation(int opcode, int flags) {
    }
}
