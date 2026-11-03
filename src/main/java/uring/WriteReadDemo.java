package uring;

import java.lang.foreign.MemorySegment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * L2 step 4: WRITE a sample through the ring into a fresh file, then
 * READ it back through the ring, byte-exact. No FSYNC between the two
 * (ROADMAP.md §4 step 4 marks it optional) — same fd's page cache
 * already serializes the write before the read on the same process.
 */
public final class WriteReadDemo {

    public static void main(String[] args) throws Exception {
        byte[] sample = "write-then-read through io_uring, pure Java/FFM\n".repeat(80).getBytes();
        Path path = Files.createTempFile("uring-panama-write-", ".bin");
        Files.deleteIfExists(path); // O_CREAT recreates it below

        try (Uring ring = Uring.open(4)) {

            int writeFd = FileOps.open(path.toString(), FileOps.O_WRONLY | FileOps.O_CREAT | FileOps.O_TRUNC, 0600);
            MemorySegment writeBuf = ring.allocateBuffer(4096, 4096);
            MemorySegment.copy(MemorySegment.ofArray(sample), 0, writeBuf, 0, sample.length);

            Sqe.prepWrite(ring.sqeSlot(), writeFd, writeBuf, sample.length, 0, 0x57L /* 'W' */);
            ring.submitAndWait(1);

            int[] writeRes = {0};
            ring.reap((userData, res, flags) -> writeRes[0] = res);
            if (writeRes[0] < 0) {
                throw new UringException("WRITE", -writeRes[0]);
            }
            if (writeRes[0] != sample.length) {
                throw new AssertionError("short write: res=" + writeRes[0] + " expected " + sample.length);
            }
            FileOps.close(writeFd);

            int readFd = FileOps.open(path.toString(), FileOps.O_RDONLY, 0);
            MemorySegment readBuf = ring.allocateBuffer(4096, 4096);

            Sqe.prepRead(ring.sqeSlot(), readFd, readBuf, sample.length, 0, 0x72L /* 'r' */);
            ring.submitAndWait(1);

            int[] readRes = {0};
            ring.reap((userData, res, flags) -> readRes[0] = res);
            if (readRes[0] < 0) {
                throw new UringException("READ", -readRes[0]);
            }
            if (readRes[0] != sample.length) {
                throw new AssertionError("short read-back: res=" + readRes[0] + " expected " + sample.length);
            }
            FileOps.close(readFd);

            byte[] got = new byte[sample.length];
            MemorySegment.copy(readBuf, 0, MemorySegment.ofArray(got), 0, sample.length);
            if (!Arrays.equals(got, sample)) {
                throw new AssertionError("read-back content does not match what was written");
            }

            System.out.println("WRITE+READ round-trip via ring OK: " + sample.length + " bytes byte-exact");
        } finally {
            Files.deleteIfExists(path);
        }
    }
}
