package uring;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * Phase 1 lifecycle regression for {@link RegisteredBufferArena}.
 *
 * <p>This is intentionally a self-checking {@code main}, matching the
 * repository's existing L1–P2 demo-test style. It proves the deterministic
 * portion of the safety contract against a real kernel table:
 *
 * <ol>
 *   <li>a READ_FIXED completes but is deliberately left unreaped;</li>
 *   <li>arena close drains that CQE, unregisters, and makes the held segment
 *       inaccessible;</li>
 *   <li>a fresh arena registers on the same ring and completes another
 *       byte-exact READ_FIXED;</li>
 *   <li>a prepared-but-unpublished SQE prevents close without freeing the
 *       backing memory.</li>
 * </ol>
 *
 * {@link DangerBufferFreeDemo} remains the contrasting, process-isolated
 * empirical misuse experiment; its raw-memory contents are intentionally not
 * an assertion here.
 */
public final class RegisteredBufferArenaDemo {

    private static final int BUFFER_SIZE = 4096;

    public static void main(String[] args) throws Exception {
        byte[] sample = "RegisteredBufferArena drain-unregister-free regression\n".repeat(50).getBytes();
        Path path = Files.createTempFile("uring-panama-managed-fixed-", ".bin");
        Files.write(path, sample);

        try (Uring ring = Uring.open(4)) {
            int fd = FileOps.open(path.toString(), FileOps.O_RDONLY, 0);
            try {
                closeDrainsUnregistersAndFrees(ring, fd, sample);
                pendingSqePreventsUnsafeFree(ring, fd, sample);
                dispatcherAndArenaAreMutuallyExclusive(ring);
            } finally {
                FileOps.close(fd);
            }
        } finally {
            Files.deleteIfExists(path);
        }

        System.out.println("PASS: RegisteredBufferArena lifecycle invariants hold");
    }

    private static void closeDrainsUnregistersAndFrees(Uring ring, int fd, byte[] sample) {
        MemorySegment stale;
        try (RegisteredBufferArena first = ring.openRegisteredBufferArena(1, BUFFER_SIZE, BUFFER_SIZE)) {
            RegisteredBufferArena.Buffer buffer = first.buffer(0);
            stale = buffer.segment();

            expectIllegalState(() -> ring.openRegisteredBufferArena(1, BUFFER_SIZE, BUFFER_SIZE),
                    "a second fixed-buffer table must be rejected");
            MemorySegment legacy = ring.allocateBuffer(BUFFER_SIZE, BUFFER_SIZE);
            expectIllegalState(() -> ring.registerBuffers(legacy),
                    "legacy registration must not steal a managed table");

            first.prepReadFixed(ring.sqeSlot(), fd, buffer, sample.length, 0, 0xA1L);
            ring.submitAndWait(1);
            if (ring.inFlightCount() != 1) {
                throw new AssertionError("expected submitted READ_FIXED to be tracked before close");
            }

            // Deliberately do not reap: close must do that before unregister/free.
            first.close();
            if (!first.isClosed()) {
                throw new AssertionError("arena did not report CLOSED after successful teardown");
            }
            if (ring.inFlightCount() != 0 || ring.reap((userData, res, flags) -> {
            }) != 0) {
                throw new AssertionError("RegisteredBufferArena.close() did not drain the queued CQE");
            }
            if (stale.scope().isAlive()) {
                throw new AssertionError("managed segment scope is still alive after drain -> unregister -> free");
            }
            expectIllegalState(() -> stale.get(ValueLayout.JAVA_BYTE, 0),
                    "held segment must reject Java access after close");
            expectIllegalState(() -> first.buffer(0), "buffer lookup after close must be rejected");
            System.out.println("PASS: close drained CQE, unregistered table, and freed owned Arena");
        }

        // A new registration on the same ring is an observable proof that the
        // previous close performed UNREGISTER_BUFFERS before freeing memory.
        try (RegisteredBufferArena second = ring.openRegisteredBufferArena(1, BUFFER_SIZE, BUFFER_SIZE)) {
            RegisteredBufferArena.Buffer buffer = second.buffer(0);
            second.prepReadFixed(ring.sqeSlot(), fd, buffer, sample.length, 0, 0xA2L);
            ring.submitAndWait(1);
            assertByteExactCompletion(ring, buffer, sample, 0xA2L);
            System.out.println("PASS: a fresh managed table registered on the same ring after close");
        }
    }

    private static void pendingSqePreventsUnsafeFree(Uring ring, int fd, byte[] sample) {
        try (RegisteredBufferArena arena = ring.openRegisteredBufferArena(1, BUFFER_SIZE, BUFFER_SIZE)) {
            RegisteredBufferArena.Buffer buffer = arena.buffer(0);
            MemorySegment retained = buffer.segment();

            // sqeSlot() has incremented Uring.pending, but the kernel has not
            // seen this pointer. Close must fail rather than free a segment a
            // caller could still publish later.
            arena.prepReadFixed(ring.sqeSlot(), fd, buffer, sample.length, 0, 0xA3L);
            expectIllegalState(arena::close, "prepared but unpublished SQE must block close");
            if (!retained.scope().isAlive() || arena.isClosed()) {
                throw new AssertionError("failed close freed or closed an arena with a pending SQE");
            }

            ring.submitAndWait(1);
            assertByteExactCompletion(ring, buffer, sample, 0xA3L);
            arena.close();
            System.out.println("PASS: pending SQE is rejected without freeing its registered buffer");
        }
    }

    private static void dispatcherAndArenaAreMutuallyExclusive(Uring ring) {
        try (Dispatcher dispatcher = new Dispatcher(ring)) {
            expectIllegalState(() -> ring.openRegisteredBufferArena(1, BUFFER_SIZE, BUFFER_SIZE),
                    "managed arena must not race a live Dispatcher CQ reaper");
        }
        System.out.println("PASS: Dispatcher and RegisteredBufferArena are mutually exclusive in this one-shot slice");
    }

    private static void assertByteExactCompletion(Uring ring, RegisteredBufferArena.Buffer buffer,
                                                   byte[] expected, long expectedUserData) {
        long[] userData = {Long.MIN_VALUE};
        int[] result = {Integer.MIN_VALUE};
        int reaped = ring.reap((ud, res, flags) -> {
            userData[0] = ud;
            result[0] = res;
        });
        if (reaped != 1 || userData[0] != expectedUserData || result[0] != expected.length) {
            throw new AssertionError("unexpected READ_FIXED completion: reaped=" + reaped
                    + " userData=0x" + Long.toHexString(userData[0]) + " res=" + result[0]);
        }
        byte[] actual = new byte[expected.length];
        MemorySegment.copy(buffer.segment(), 0, MemorySegment.ofArray(actual), 0, actual.length);
        if (!Arrays.equals(actual, expected)) {
            throw new AssertionError("READ_FIXED content mismatch");
        }
    }

    private static void expectIllegalState(Runnable action, String invariant) {
        try {
            action.run();
        } catch (IllegalStateException expected) {
            return;
        }
        throw new AssertionError("expected IllegalStateException: " + invariant);
    }
}
