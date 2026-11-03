package uring;

import java.lang.foreign.MemorySegment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * L2 step 3: READ 4 KiB through the ring from a real file, byte-exact
 * against a plain {@code Files.readAllBytes} reference. Also exercises
 * the {@code res < 0} == {@code -errno} convention (distinct from the
 * global-errno convention used by {@link Syscalls#ioUringEnter}).
 */
public final class ReadDemo {

    public static void main(String[] args) throws Exception {
        byte[] sample = "hello io_uring from pure Java/FFM\n".repeat(50).getBytes();
        Path path = Files.createTempFile("uring-panama-read-", ".bin");
        Files.write(path, sample);

        try (Uring ring = Uring.open(4)) {
            int fd = FileOps.open(path.toString(), FileOps.O_RDONLY, 0);
            try {
                // 4 KiB, page-aligned from the start — free now, mandatory later for O_DIRECT (P1)
                MemorySegment buf = ring.allocateBuffer(4096, 4096);
                long marker = 0x52; // 'R'

                Sqe.prepRead(ring.sqeSlot(), fd, buf, sample.length, 0, marker);
                ring.submitAndWait(1);

                int[] res = {0};
                long[] userData = {0};
                int reaped = ring.reap((ud, r, flags) -> {
                    userData[0] = ud;
                    res[0] = r;
                });

                if (reaped != 1) {
                    throw new AssertionError("expected 1 CQE, got " + reaped);
                }
                if (userData[0] != marker) {
                    throw new AssertionError("user_data mismatch: 0x" + Long.toHexString(userData[0]));
                }
                if (res[0] < 0) {
                    // res < 0 means -errno, NOT the global errno set by the syscall() wrapper
                    throw new UringException("READ", -res[0]);
                }
                if (res[0] != sample.length) {
                    throw new AssertionError("short read: res=" + res[0] + " expected " + sample.length);
                }

                byte[] got = new byte[sample.length];
                MemorySegment.copy(buf, 0, MemorySegment.ofArray(got), 0, sample.length);
                if (!Arrays.equals(got, sample)) {
                    throw new AssertionError("buffer content does not match the file");
                }

                System.out.println("READ via ring OK: " + res[0] + " bytes, content byte-exact");
            } finally {
                FileOps.close(fd);
            }

            // Negative test: READ on an already-closed fd -> CQE res == -EBADF
            negativeReadOnClosedFd(ring);
        } finally {
            Files.deleteIfExists(path);
        }
    }

    private static void negativeReadOnClosedFd(Uring ring) {
        int fd = FileOps.open("/dev/null", FileOps.O_RDONLY, 0);
        FileOps.close(fd); // fd is now invalid
        MemorySegment buf = ring.allocateBuffer(4096, 4096);
        long marker = 0x53; // 'S'

        Sqe.prepRead(ring.sqeSlot(), fd, buf, 16, 0, marker);
        ring.submitAndWait(1);

        int[] res = {0};
        ring.reap((ud, r, flags) -> res[0] = r);

        if (res[0] != -Errno.EBADF) {
            throw new AssertionError("expected res == -EBADF, got " + res[0]);
        }
        System.out.println("PASS: negative READ on closed fd -> res=-EBADF as expected");
    }
}
