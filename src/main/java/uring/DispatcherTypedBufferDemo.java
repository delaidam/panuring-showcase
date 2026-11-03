package uring;

import java.lang.foreign.MemorySegment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * Real fixed-buffer/Dispatcher bridge regression. The arena is opened before
 * the dispatcher attaches; the dispatcher then routes the typed READ_FIXED
 * terminal CQE that releases the borrow, and only then may the arena be
 * safely closed/unregistered.
 */
public final class DispatcherTypedBufferDemo {

    private DispatcherTypedBufferDemo() {
    }

    public static void main(String[] args) throws Exception {
        byte[] expected = "dispatcher typed fixed-buffer lifetime".repeat(32).getBytes();
        Path file = Files.createTempFile("panuring-dispatcher-fixed-", ".bin");
        Files.write(file, expected);
        try (Uring ring = Uring.open(16)) {
            int fd = FileOps.open(file.toString(), FileOps.O_RDONLY, 0);
            try (RegisteredBufferArena buffers = ring.openRegisteredBufferArena(1, 4096, 64)) {
                RegisteredBufferArena.Buffer buffer = buffers.buffer(0);
                try (Dispatcher dispatcher = new Dispatcher(ring)) {
                    OpToken read = dispatcher.submit(OneShotOp.readFixed(buffers, fd, buffer, expected.length, 0));
                    if (!buffer.isBorrowed()) {
                        throw new AssertionError("typed Dispatcher READ_FIXED did not borrow its managed buffer");
                    }
                    Completion completion = dispatcher.await(read);
                    if (completion.res() != expected.length || !read.isTerminal() || buffer.isBorrowed()) {
                        throw new AssertionError("unexpected typed Dispatcher READ_FIXED completion: " + completion);
                    }
                    byte[] actual = new byte[expected.length];
                    MemorySegment.copy(buffer.segment(), 0, MemorySegment.ofArray(actual), 0, actual.length);
                    if (!Arrays.equals(expected, actual)) {
                        throw new AssertionError("typed Dispatcher READ_FIXED data differs from file");
                    }
                    System.out.println("PASS: Dispatcher routes typed READ_FIXED terminal CQE before managed buffer reuse");
                }
            } finally {
                FileOps.close(fd);
            }
        } finally {
            Files.deleteIfExists(file);
        }
    }
}
