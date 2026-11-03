package uring;

import java.lang.foreign.MemorySegment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * L2 step 7: register a file descriptor with the ring
 * ({@code IORING_REGISTER_FILES}) and address it by index with
 * {@code IOSQE_FIXED_FILE} instead of a real fd.
 */
public final class RegisteredFilesDemo {

    public static void main(String[] args) throws Exception {
        byte[] sample = "registered file, addressed by index not fd\n".repeat(70).getBytes();
        Path path = Files.createTempFile("uring-panama-regfile-", ".bin");
        Files.write(path, sample);

        try (Uring ring = Uring.open(4)) {
            int fd = FileOps.open(path.toString(), FileOps.O_RDONLY, 0);
            try {
                ring.registerFiles(fd);
                System.out.println("registerFiles OK (1 fd -> index 0)");

                MemorySegment buf = ring.allocateBuffer(4096, 4096);
                int fileIndex = 0;

                MemorySegment slot = ring.sqeSlot();
                Sqe.prepRead(slot, fileIndex, buf, sample.length, 0, 0x49L /* 'I' */);
                Sqe.markFixedFile(slot);
                ring.submitAndWait(1);

                int[] res = {0};
                ring.reap((userData, r, flags) -> res[0] = r);
                if (res[0] < 0) {
                    throw new UringException("READ via IOSQE_FIXED_FILE", -res[0]);
                }
                if (res[0] != sample.length) {
                    throw new AssertionError("short read: res=" + res[0] + " expected " + sample.length);
                }

                byte[] got = new byte[sample.length];
                MemorySegment.copy(buf, 0, MemorySegment.ofArray(got), 0, sample.length);
                if (!Arrays.equals(got, sample)) {
                    throw new AssertionError("content mismatch via registered file");
                }
                System.out.println("READ via registered file (IOSQE_FIXED_FILE) OK: " + res[0] + " bytes byte-exact");
            } finally {
                FileOps.close(fd);
            }
        } finally {
            Files.deleteIfExists(path);
        }
    }
}
