package uring;

import java.lang.foreign.MemorySegment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * L2 step 6: register a buffer, READ through it with
 * {@code IORING_OP_READ_FIXED} + {@code buf_index}, byte-exact.
 * Also exercises the negative path (bad buf_index -&gt; -EINVAL) and,
 * deliberately, the RLIMIT_MEMLOCK trap (ROADMAP.md §4 step 6).
 */
public final class RegisteredBuffersDemo {

    public static void main(String[] args) throws Exception {
        byte[] sample = "registered buffer READ_FIXED round-trip\n".repeat(60).getBytes();
        Path path = Files.createTempFile("uring-panama-fixed-", ".bin");
        Files.write(path, sample);

        try (Uring ring = Uring.open(4)) {
            int fd = FileOps.open(path.toString(), FileOps.O_RDONLY, 0);
            try {
                MemorySegment buf = ring.allocateBuffer(4096, 4096);
                ring.registerBuffers(buf);
                System.out.println("registerBuffers OK (1 buffer, 4096 B)");

                Sqe.prepReadFixed(ring.sqeSlot(), fd, buf, sample.length, 0, 0x46L /* 'F' */, 0);
                ring.submitAndWait(1);

                int[] res = {0};
                ring.reap((userData, r, flags) -> res[0] = r);
                if (res[0] < 0) {
                    throw new UringException("READ_FIXED", -res[0]);
                }
                if (res[0] != sample.length) {
                    throw new AssertionError("short READ_FIXED: res=" + res[0] + " expected " + sample.length);
                }

                byte[] got = new byte[sample.length];
                MemorySegment.copy(buf, 0, MemorySegment.ofArray(got), 0, sample.length);
                if (!Arrays.equals(got, sample)) {
                    throw new AssertionError("READ_FIXED content mismatch");
                }
                System.out.println("READ_FIXED via registered buffer OK: " + res[0] + " bytes byte-exact");

                // Negative test: bad buf_index. ROADMAP.md §4 step 8 expected -EINVAL, but the kernel on
                // this machine actually returns -EFAULT for an out-of-range fixed buffer index (io_uring's
                // io_import_fixed() checks buf_index against nr_user_bufs and returns -EFAULT, not -EINVAL) —
                // empirical correction, recorded in RESEARCH.md.
                Sqe.prepReadFixed(ring.sqeSlot(), fd, buf, 16, 0, 0x47L /* 'G' */, 7 /* only index 0 is registered */);
                ring.submitAndWait(1);
                int[] badRes = {0};
                ring.reap((userData, r, flags) -> badRes[0] = r);
                if (badRes[0] != -Errno.EFAULT) {
                    throw new AssertionError("expected res == -EFAULT for bad buf_index, got " + badRes[0]);
                }
                System.out.println("PASS: bad buf_index -> res=-EFAULT (not -EINVAL as ROADMAP.md assumed -- kernel reality corrected)");
            } finally {
                FileOps.close(fd);
            }
        } finally {
            Files.deleteIfExists(path);
        }

        memlockTrapDemo();
    }

    /** Deliberately exceeds RLIMIT_MEMLOCK to verify the ENOMEM/EPERM hint path actually fires. */
    private static void memlockTrapDemo() {
        long limit = Syscalls.getrlimitMemlockBytes();
        long oversized = limit * 2;
        System.out.println("RLIMIT_MEMLOCK = " + limit + " bytes; attempting to register " + oversized
                + " bytes to trigger the trap...");
        try (Uring ring = Uring.open(4)) {
            MemorySegment huge = ring.allocateBuffer(oversized, 4096);
            ring.registerBuffers(huge);
            System.out.println("UNEXPECTED: register succeeded despite exceeding RLIMIT_MEMLOCK");
        } catch (UringException e) {
            System.out.println("PASS: register beyond RLIMIT_MEMLOCK failed as expected: " + e.getMessage());
        }
    }
}
