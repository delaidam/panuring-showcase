package uring;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Executable Phase-2 regression for the stream-send ordering invariant.
 *
 * <p>Several virtual threads issue large, distinct sends concurrently while
 * the peer initially refuses to read. That forces requests to accumulate in
 * {@link StreamSocket}'s FIFO. Once the reader is released, this demo proves
 * both that at most one SEND SQE was outstanding and that the peer received
 * every message as one byte-exact, non-interleaved block (in the FIFO order
 * chosen by the concurrent callers).
 */
public final class StreamSocketOrderingDemo {

    private static final int SENDERS = 8;
    private static final int BYTES_PER_SEND = 2 * 1024 * 1024;

    private StreamSocketOrderingDemo() {
    }

    public static void main(String[] args) throws Exception {
        int listener = -1;
        int client = -1;
        int accepted = -1;
        try {
            try (Uring ring = Uring.open(128); Dispatcher dispatcher = new Dispatcher(ring)) {
                listener = SocketOps.tcpSocket();
                SocketOps.setReuseAddress(listener, true);
                SocketOps.bindLoopback(listener, 0);
                SocketOps.listen(listener, SENDERS);
                int port = SocketOps.localPort(listener);

                client = SocketOps.tcpSocket();
                SocketOps.connectLoopback(client, port);
                accepted = acceptViaDispatcher(dispatcher, listener);

                StreamSocket stream = new StreamSocket(dispatcher, accepted);
                try {
                    expectDuplicateWrapperRejected(dispatcher, accepted);
                    byte[][] messages = messages();
                    CountDownLatch ready = new CountDownLatch(SENDERS);
                    CountDownLatch go = new CountDownLatch(1);
                    AtomicReference<Throwable> senderFailure = new AtomicReference<>();
                    Thread[] senders = new Thread[SENDERS];
                    for (int i = 0; i < SENDERS; i++) {
                        final byte[] message = messages[i];
                        senders[i] = Thread.ofVirtual().name("stream-send-" + (i + 1)).start(() -> {
                            ready.countDown();
                            try {
                                if (!go.await(10, TimeUnit.SECONDS)) {
                                    throw new AssertionError("send start gate timed out");
                                }
                                // Every caller deliberately supplies a
                                // confined segment. The FIFO worker runs on a
                                // different vthread, so this also proves the
                                // public path copied the bytes into its owned
                                // shared Arena before queuing work.
                                try (Arena sourceArena = Arena.ofConfined()) {
                                    MemorySegment source = sourceArena.allocate(message.length, 1);
                                    MemorySegment.copy(MemorySegment.ofArray(message), 0,
                                            source, 0, message.length);
                                    int sent = stream.send(source, message.length);
                                    if (sent != message.length) {
                                        throw new AssertionError("send returned " + sent + " for "
                                                + message.length + " bytes");
                                    }
                                }
                            } catch (Throwable failure) {
                                senderFailure.compareAndSet(null, failure);
                            }
                        });
                    }
                    if (!ready.await(10, TimeUnit.SECONDS)) {
                        throw new AssertionError("concurrent senders did not start");
                    }
                    go.countDown();

                    // Do not read yet: at least two callers must be accepted
                    // into the FIFO while the first eventually backpressures
                    // on TCP.
                    if (!stream.awaitQueuedCalls(2, Duration.ofSeconds(10))) {
                        throw new AssertionError("concurrent send calls never queued; stats=" + stream.sendStats());
                    }

                    AtomicReference<byte[]> received = new AtomicReference<>();
                    AtomicReference<Throwable> receiverFailure = new AtomicReference<>();
                    final int peerFd = client;
                    Thread reader = Thread.ofVirtual().name("stream-loopback-reader").start(() -> {
                        try (Arena arena = Arena.ofConfined()) {
                            int totalBytes = Math.multiplyExact(SENDERS, BYTES_PER_SEND);
                            MemorySegment target = arena.allocate(totalBytes, 1);
                            SocketOps.receiveExactly(peerFd, target, totalBytes);
                            byte[] copy = new byte[totalBytes];
                            MemorySegment.copy(target, 0, MemorySegment.ofArray(copy), 0, totalBytes);
                            received.set(copy);
                        } catch (Throwable failure) {
                            receiverFailure.compareAndSet(null, failure);
                        }
                    });

                    joinAll(senders, "sender");
                    join(reader, "reader");
                    if (senderFailure.get() != null) {
                        throw new AssertionError("concurrent StreamSocket.send failed", senderFailure.get());
                    }
                    if (receiverFailure.get() != null) {
                        throw new AssertionError("loopback receiver failed", receiverFailure.get());
                    }
                    assertWholeMessageBlocks(received.get());

                    StreamSocket.SendStats stats = stream.sendStats();
                    if (stats.maxOutstandingSendSqes() != 1 || stats.outstandingSendSqes() != 0) {
                        throw new AssertionError("overlapping SEND SQE invariant violated: " + stats);
                    }
                    if (stats.acceptedCalls() != SENDERS || stats.completedCalls() != SENDERS
                            || stats.failedCalls() != 0 || stats.queuedCalls() != 0) {
                        throw new AssertionError("unexpected StreamSocket terminal accounting: " + stats);
                    }

                    stream.close();
                    expectClosedSendRejected(stream);
                    StreamSocket replacement = new StreamSocket(dispatcher, accepted);
                    // Closing the original releases the ownership lease; a
                    // later, non-overlapping wrapper is valid.
                    replacement.close();
                    System.out.printf("PASS: StreamSocket FIFO accepted=%d max_outstanding_SEND=%d "
                                    + "and loopback delivered %d byte-exact blocks (%d bytes)%n",
                            stats.acceptedCalls(), stats.maxOutstandingSendSqes(), SENDERS,
                            Math.multiplyExact(SENDERS, BYTES_PER_SEND));
                } finally {
                    stream.close();
                }
            }
        } finally {
            closeQuietly(accepted);
            closeQuietly(client);
            closeQuietly(listener);
        }
    }

    private static int acceptViaDispatcher(Dispatcher dispatcher, int listenerFd) {
        int accepted = dispatcher.submitAndAwait((sqe, userData) ->
                Sqe.prepAccept(sqe, listenerFd, MemorySegment.NULL, MemorySegment.NULL,
                        SocketOps.SOCK_CLOEXEC, userData));
        if (accepted < 0) {
            throw new UringException("ACCEPT", -accepted);
        }
        return accepted;
    }

    private static byte[][] messages() {
        byte[][] messages = new byte[SENDERS][];
        for (int i = 0; i < SENDERS; i++) {
            byte[] message = new byte[BYTES_PER_SEND];
            Arrays.fill(message, (byte) (i + 1));
            messages[i] = message;
        }
        return messages;
    }

    /** Every block may be in any caller-admission order, but never interleaved. */
    private static void assertWholeMessageBlocks(byte[] actual) {
        if (actual == null || actual.length != SENDERS * BYTES_PER_SEND) {
            throw new AssertionError("loopback delivered wrong total byte count");
        }
        boolean[] seen = new boolean[SENDERS];
        for (int block = 0; block < SENDERS; block++) {
            int offset = block * BYTES_PER_SEND;
            int marker = Byte.toUnsignedInt(actual[offset]);
            if (marker < 1 || marker > SENDERS || seen[marker - 1]) {
                throw new AssertionError("invalid or duplicate message marker " + marker + " at block " + block);
            }
            for (int i = 0; i < BYTES_PER_SEND; i++) {
                if (actual[offset + i] != (byte) marker) {
                    throw new AssertionError("message marker " + marker + " was interleaved/corrupted at block="
                            + block + " byte=" + i);
                }
            }
            seen[marker - 1] = true;
        }
        for (int i = 0; i < seen.length; i++) {
            if (!seen[i]) {
                throw new AssertionError("missing message marker " + (i + 1));
            }
        }
    }

    private static void joinAll(Thread[] threads, String label) throws InterruptedException {
        for (Thread thread : threads) {
            join(thread, label);
        }
    }

    private static void join(Thread thread, String label) throws InterruptedException {
        thread.join(30_000);
        if (thread.isAlive()) {
            throw new AssertionError(label + " thread did not finish");
        }
    }

    private static void closeQuietly(int fd) {
        if (fd >= 0) {
            try {
                FileOps.close(fd);
            } catch (RuntimeException ignored) {
                // Preserve an earlier failure.
            }
        }
    }

    /** Negative API check: a closed writer cannot reopen a SEND path. */
    private static void expectClosedSendRejected(StreamSocket stream) {
        try {
            stream.send(MemorySegment.ofArray(new byte[]{0x7f}), 1);
        } catch (IllegalStateException expected) {
            return;
        }
        throw new AssertionError("closed StreamSocket admitted another SEND");
    }

    /** Negative API check: a second public wrapper cannot create a parallel SEND path. */
    private static void expectDuplicateWrapperRejected(Dispatcher dispatcher, int fd) {
        try {
            new StreamSocket(dispatcher, fd);
        } catch (IllegalStateException expected) {
            return;
        }
        throw new AssertionError("duplicate StreamSocket wrapper was admitted for fd=" + fd);
    }
}
