package uring;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * L2 step 5 — controlled negative experiment (ROADMAP.md §4 step 5).
 * Deliberately violates the invariant "a buffer must not die before its
 * CQE": closes the buffer's arena BEFORE reaping the completion, then
 * inspects what actually happened. Gated behind {@code -Ddanger=true}
 * so it never runs by accident, and meant to be launched as its own
 * process — a JVM crash is a legitimate possible outcome here.
 */
public final class DangerBufferFreeDemo {

    public static void main(String[] args) throws Exception {
        if (!Boolean.getBoolean("danger")) {
            System.out.println("Skipped: pass -Ddanger=true to actually run this experiment.");
            return;
        }

        byte[] sample = "danger-mode fixture content, should NOT silently vanish\n".repeat(40).getBytes();
        Path path = Files.createTempFile("uring-panama-danger-", ".bin");
        Files.write(path, sample);

        try (Uring ring = Uring.open(4)) {
            int fd = FileOps.open(path.toString(), FileOps.O_RDONLY, 0);

            Arena doomed = Arena.ofConfined();
            MemorySegment buf = doomed.allocate(4096, 4096);
            long rawAddr = buf.address(); // captured while still alive, for the raw peek below

            Sqe.prepRead(ring.sqeSlot(), fd, buf, sample.length, 0, 0x44L /* 'D' */);
            ring.submitAndWait(1);

            // THE VIOLATION: close the buffer's arena before reap(), against the documented invariant.
            doomed.close();
            System.out.println("Buffer arena closed BEFORE reap() -- invariant violated on purpose.");

            int[] res = {0};
            ring.reap((userData, r, flags) -> res[0] = r);
            System.out.println("CQE reaped fine (the CQ ring lives in Rings' own arena, untouched by the buffer arena): res=" + res[0]);

            try {
                buf.get(ValueLayout.JAVA_BYTE, 0);
                System.out.println("UNEXPECTED: reading the closed segment did not throw");
            } catch (IllegalStateException e) {
                System.out.println("Expected: Java-side access to the closed segment throws " + e.getClass().getSimpleName());
            }

            // Bypass the Arena scope entirely (unsafe, deliberate) to see what is actually sitting at that address.
            MemorySegment raw = MemorySegment.ofAddress(rawAddr).reinterpret(sample.length);
            byte[] peek = new byte[sample.length];
            MemorySegment.copy(raw, 0, MemorySegment.ofArray(peek), 0, sample.length);
            boolean matches = Arrays.equals(peek, sample);
            System.out.println("Raw memory at the same address, read through an unchecked reinterpret "
                    + "(bypassing the Arena's liveness check): content " + (matches ? "MATCHES" : "DOES NOT MATCH")
                    + " what the kernel was asked to read.");
            System.out.println(matches
                    ? "=> the kernel wrote into memory Java considered freed, and nothing detected it (silent, not a crash)."
                    : "=> content diverged on this run -- see RESEARCH.md for the recorded observation.");
            System.out.println("first 32 bytes at that address now: " + hexDump(peek, 32));

            FileOps.close(fd);
        } finally {
            Files.deleteIfExists(path);
        }
    }

    private static String hexDump(byte[] data, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(n, data.length); i++) {
            sb.append(String.format("%02x ", data[i]));
        }
        return sb.toString().trim();
    }
}
