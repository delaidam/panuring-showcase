package uring;

import java.lang.foreign.MemorySegment;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * Executable Phase-1 regression for Dispatcher shutdown: virtual threads park
 * on deliberately long timeouts, close races their submissions, and every
 * admitted waiter must wake from its target CQE (normally {@code -ECANCELED})
 * rather than being left parked after the CQ poller exits.
 */
public final class DispatcherLifecycleDemo {

    private static final int SUBMITTERS = 64;

    private DispatcherLifecycleDemo() {
    }

    public static void main(String[] args) throws Exception {
        try (Uring ring = Uring.open(256)) {
            Dispatcher dispatcher = new Dispatcher(ring);
            try {
                verifyPreparationRollback(dispatcher);
                MemorySegment longTimeout = ring.allocateBuffer(16, 8);
                longTimeout.set(JAVA_LONG, 0, 30L);
                longTimeout.set(JAVA_LONG, 8, 0L);

                CountDownLatch started = new CountDownLatch(SUBMITTERS);
                AtomicInteger cancelled = new AtomicInteger();
                AtomicInteger rejectedAfterClose = new AtomicInteger();
                AtomicReference<Throwable> unexpected = new AtomicReference<>();
                Thread[] submitters = new Thread[SUBMITTERS];

                for (int i = 0; i < SUBMITTERS; i++) {
                    submitters[i] = Thread.ofVirtual().start(() -> {
                        started.countDown();
                        try {
                            int res = dispatcher.submitAndAwait(
                                    (sqe, userData) -> Sqe.prepTimeout(sqe, longTimeout, userData));
                            if (res != -Errno.ECANCELED) {
                                throw new AssertionError("long timeout completed with res=" + res
                                        + ", expected -ECANCELED during Dispatcher.close()");
                            }
                            cancelled.incrementAndGet();
                        } catch (IllegalStateException closed) {
                            // Submission can lose the race to close before it
                            // is admitted. That is the intended CLOSED/CLOSING
                            // admission behavior, not a stranded waiter.
                            if (closed.getMessage() != null
                                    && closed.getMessage().startsWith("Dispatcher is not accepting submissions")) {
                                rejectedAfterClose.incrementAndGet();
                            } else {
                                unexpected.compareAndSet(null, closed);
                            }
                        } catch (Throwable failure) {
                            unexpected.compareAndSet(null, failure);
                        }
                    });
                }

                if (!started.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("submitter startup timed out");
                }
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (dispatcher.liveOperationCount() == 0 && System.nanoTime() < deadline) {
                    Thread.onSpinWait();
                }
                if (dispatcher.liveOperationCount() == 0) {
                    throw new AssertionError("no timeout reached Dispatcher before shutdown test");
                }

                dispatcher.close();
                for (Thread submitter : submitters) {
                    submitter.join(10_000);
                    if (submitter.isAlive()) {
                        throw new AssertionError("Dispatcher.close stranded a submitter thread");
                    }
                }
                if (unexpected.get() != null) {
                    throw new AssertionError("unexpected submitter failure", unexpected.get());
                }
                if (cancelled.get() + rejectedAfterClose.get() != SUBMITTERS) {
                    throw new AssertionError("missing terminal outcome: cancelled=" + cancelled.get()
                            + " rejected=" + rejectedAfterClose.get());
                }
                if (!dispatcher.isDetached()) {
                    throw new AssertionError("clean Dispatcher.close did not detach from Uring");
                }
                expectThrows(() -> dispatcher.submitAndAwaitNop(),
                        "closed Dispatcher must reject post-close submission");

                System.out.println("PASS: Dispatcher close cancelled=" + cancelled.get()
                        + " rejected-before-admission=" + rejectedAfterClose.get()
                        + "; no parked waiter survived shutdown");
            } finally {
                // The regression's assertion is about Dispatcher.close itself;
                // retain normal AutoCloseable cleanup if setup/assertion fails.
                if (!dispatcher.isDetached()) {
                    dispatcher.close();
                }
            }
        }
    }

    private static void expectThrows(Runnable action, String message) {
        try {
            action.run();
        } catch (IllegalStateException expected) {
            return;
        }
        throw new AssertionError(message);
    }

    /** A throwing preparer must not leave an unpublished SQE or a ghost waiter behind. */
    private static void verifyPreparationRollback(Dispatcher dispatcher) {
        try {
            dispatcher.submitAndAwait((sqe, userData) -> {
                throw new IllegalArgumentException("intentional prep failure");
            });
            throw new AssertionError("throwing SQE preparer unexpectedly returned");
        } catch (IllegalArgumentException expected) {
            // expected
        }
        int nop = dispatcher.submitAndAwaitNop();
        if (nop != 0) {
            throw new AssertionError("post-rollback NOP failed with res=" + nop);
        }
        try {
            dispatcher.submitAndAwait((sqe, userData) -> Sqe.prepNop(sqe, userData + 1));
            throw new AssertionError("preparer replacing dispatcher user_data unexpectedly returned");
        } catch (IllegalStateException expected) {
            if (!expected.getMessage().contains("replaced assigned user_data")) {
                throw expected;
            }
        }
        int secondNop = dispatcher.submitAndAwaitNop();
        if (secondNop != 0) {
            throw new AssertionError("post-user_data-validation NOP failed with res=" + secondNop);
        }
    }
}
