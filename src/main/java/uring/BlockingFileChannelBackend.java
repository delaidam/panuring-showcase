package uring;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.ThreadLocalRandom;

/**
 * ROADMAP.md section 7.3 backend (A): blocking {@link FileChannel} reads
 * on a virtual thread — the JVM's own vthread-unmount-on-blocking-syscall
 * baseline (RESEARCH.md section 3.5), no io_uring involved. One
 * independent {@code FileChannel} per instance (per virtual thread) —
 * unlike the io_uring backends, nothing is shared here.
 */
public final class BlockingFileChannelBackend implements P2Backend {

    private final FileChannel channel;
    private final ByteBuffer buf;
    private final int len;
    private int checksumIterations;
    private final long maxOffset;
    private long checksumSink;

    public BlockingFileChannelBackend(Path path, int len, int checksumIterations) throws IOException {
        this(path, len, checksumIterations, len);
    }

    public BlockingFileChannelBackend(Path path, int len, int checksumIterations, long fixtureSize) throws IOException {
        this.channel = FileChannel.open(path, StandardOpenOption.READ);
        this.buf = ByteBuffer.allocateDirect(len);
        this.len = len;
        this.checksumIterations = checksumIterations;
        if (fixtureSize < len || fixtureSize % len != 0) {
            throw new IllegalArgumentException("fixtureSize must be a multiple of len and at least len");
        }
        this.maxOffset = fixtureSize - len;
    }

    @Override
    public void runOneOp() {
        try {
            buf.clear();
            long offset = ThreadLocalRandom.current().nextLong(maxOffset / len + 1) * len;
            int n = channel.read(buf, offset);
            if (n != len) {
                throw new RuntimeException("BlockingFileChannelBackend: expected read=" + len + ", got " + n);
            }
            buf.flip();
            checksumSink += Workload.checksum(buf, checksumIterations);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public long measureOpNsPerOp(int iterations, int passes) {
        if (iterations < 0 || passes < 1) {
            throw new IllegalArgumentException("iterations must be non-negative and passes positive");
        }
        this.checksumIterations = iterations;
        long start = System.nanoTime();
        for (int pass = 0; pass < passes; pass++) {
            runOneOp();
        }
        long elapsed = System.nanoTime() - start;
        Workload.consume(checksumSink);
        return Math.max(1L, elapsed / passes);
    }

    @Override
    public long measureChecksumNsPerOp(int passes) {
        // Deliberately calls Workload.checksum the way runOneOp does -- same
        // call site shape, iteration count read from the same field -- because
        // that is what decides how it compiles. Handing the field to
        // Workload.measureNsPerOp instead turns it into a parameter, which C2
        // can treat very differently; see RESEARCH.md section 27.
        long start = System.nanoTime();
        long sum = 0;
        for (int pass = 0; pass < passes; pass++) {
            sum += Workload.checksum(buf, checksumIterations);
        }
        Workload.consume(sum);
        return Math.max(1L, (System.nanoTime() - start) / passes);
    }

    @Override
    public void close() throws IOException {
        // Escapes the accumulated result: without this the checksum is dead
        // code and C2 is free to delete the workload (RESEARCH.md section 26).
        Workload.consume(checksumSink);
        channel.close();
    }
}
