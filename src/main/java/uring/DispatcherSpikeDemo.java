package uring;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * P2 Gate G2 verification: many virtual threads, each submitting
 * several NOPs through {@link Dispatcher} and parking until woken.
 * If this is stable (every op completes exactly once, no deadlock, no
 * lost wakeup), the gate passes; if not, P2 goes back to the drawing
 * board per ROADMAP.md §7's own criterion.
 */
public final class DispatcherSpikeDemo {

    public static void main(String[] args) throws Exception {
        int nThreads = 2000;
        int opsPerThread = 20;

        try (Uring ring = Uring.open(256);
             Dispatcher dispatcher = new Dispatcher(ring)) {

            AtomicInteger errors = new AtomicInteger(0);
            AtomicLong totalOps = new AtomicLong(0);

            Thread[] vthreads = new Thread[nThreads];
            long start = System.nanoTime();
            for (int i = 0; i < nThreads; i++) {
                vthreads[i] = Thread.ofVirtual().start(() -> {
                    for (int j = 0; j < opsPerThread; j++) {
                        int res = dispatcher.submitAndAwaitNop();
                        if (res != 0) {
                            errors.incrementAndGet();
                        }
                        totalOps.incrementAndGet();
                    }
                });
            }
            for (Thread t : vthreads) {
                t.join();
            }
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;

            long expected = (long) nThreads * opsPerThread;
            System.out.println("total ops completed: " + totalOps.get() + " (expect " + expected + ")");
            System.out.println("errors (non-zero res): " + errors.get());
            System.out.println("elapsed: " + elapsedMs + " ms for " + nThreads + " vthreads x " + opsPerThread + " ops");

            if (totalOps.get() != expected || errors.get() != 0) {
                throw new AssertionError("G2 spike FAILED: totalOps=" + totalOps.get()
                        + " expected=" + expected + " errors=" + errors.get());
            }
            System.out.println("PASS: G2 spike stable -- " + nThreads
                    + " virtual threads, all ops completed correctly via park/unpark dispatcher");
        }
    }
}
