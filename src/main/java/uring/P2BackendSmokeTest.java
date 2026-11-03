package uring;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * P2 apparatus check: each of the four ROADMAP.md section 7.3 I/O
 * backends performs real READ+checksum iterations correctly, from
 * several concurrent virtual threads, before any timing/HdrHistogram
 * harness is layered on top.
 */
public final class P2BackendSmokeTest {

    private static final int LEN = 4096;

    public static void main(String[] args) throws Exception {
        Path fixture = Path.of("/dev/shm", "uring-panama-p2-fixture.bin");
        byte[] sample = new byte[LEN];
        for (int i = 0; i < LEN; i++) {
            sample[i] = (byte) i;
        }
        Files.write(fixture, sample, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

        try {
            runBackendA(fixture);
            runBackendBCD("B (io_uring, no SQPOLL)", new Rings.RingOptions(256, -1, false, 0, -1), fixture);
            runBackendBCD("C (SQPOLL unpinned)", new Rings.RingOptions(256, -1, true, 50, -1), fixture);
            runBackendBCD("D (SQPOLL pinned to cpu 0)", new Rings.RingOptions(256, -1, true, 50, 0), fixture);
            System.out.println("PASS: all four P2 backends produced correct reads under concurrent vthread load");
        } finally {
            Files.deleteIfExists(fixture);
        }
    }

    private static void runBackendA(Path fixture) throws Exception {
        int nThreads = 200;
        int opsPerThread = 20;
        AtomicInteger errors = new AtomicInteger(0);
        Thread[] threads = new Thread[nThreads];
        for (int i = 0; i < nThreads; i++) {
            threads[i] = Thread.ofVirtual().start(() -> {
                try (BlockingFileChannelBackend backend = new BlockingFileChannelBackend(fixture, LEN, 4)) {
                    for (int j = 0; j < opsPerThread; j++) {
                        backend.runOneOp();
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                    e.printStackTrace();
                }
            });
        }
        for (Thread t : threads) {
            t.join();
        }
        report("A (blocking FileChannel)", nThreads, opsPerThread, errors.get());
    }

    private static void runBackendBCD(String label, Rings.RingOptions opts, Path fixture) throws Exception {
        int nThreads = 200;
        int opsPerThread = 20;
        AtomicInteger errors = new AtomicInteger(0);

        try (Uring ring = Uring.open(opts);
             Dispatcher dispatcher = new Dispatcher(ring)) {
            int fd = FileOps.open(fixture.toString(), FileOps.O_RDONLY, 0);
            try {
                Thread[] threads = new Thread[nThreads];
                for (int i = 0; i < nThreads; i++) {
                    threads[i] = Thread.ofVirtual().start(() -> {
                        IoUringBackend backend = new IoUringBackend(dispatcher, ring, fd, LEN, 4);
                        for (int j = 0; j < opsPerThread; j++) {
                            try {
                                backend.runOneOp();
                            } catch (Exception e) {
                                errors.incrementAndGet();
                                e.printStackTrace();
                            }
                        }
                    });
                }
                for (Thread t : threads) {
                    t.join();
                }
            } finally {
                FileOps.close(fd);
            }
        }
        report(label, nThreads, opsPerThread, errors.get());
    }

    private static void report(String label, int nThreads, int opsPerThread, int errors) {
        int total = nThreads * opsPerThread;
        System.out.println(label + ": " + total + " ops, errors=" + errors);
        if (errors != 0) {
            throw new AssertionError(label + " FAILED with " + errors + " errors");
        }
    }
}
