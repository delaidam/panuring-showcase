package uring;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * End-to-end Phase-2 multishot lifecycle regression: one accept parent and
 * one provided-buffer receive parent each emit an F_MORE CQE, then are
 * cancelled and observed through their own terminal CQEs before resources are
 * closed/recycled.
 */
public final class MultiShotSocketDemo {

    private MultiShotSocketDemo() {
    }

    public static void main(String[] args) {
        byte[] expected = "multishot provided-buffer receive".getBytes(StandardCharsets.UTF_8);
        int listener = -1;
        int client = -1;
        int accepted = -1;
        try (Uring ring = Uring.open(32); Arena clientArena = Arena.ofConfined()) {
            UringProbe probe = UringProbe.query(ring);
            require(probe.supports(Sqe.OP_ACCEPT) && probe.supports(Sqe.OP_RECV),
                    "kernel probe lacks multishot accept/recv opcodes");

            listener = SocketOps.tcpSocket();
            SocketOps.setReuseAddress(listener, true);
            SocketOps.bindLoopback(listener, 0);
            SocketOps.listen(listener, 8);
            OpToken accept = ring.submit(MultiShotOp.accept(listener, SocketOps.SOCK_CLOEXEC));

            client = SocketOps.tcpSocket();
            SocketOps.connectLoopback(client, SocketOps.localPort(listener));
            Completion acceptedCqe = ring.awaitNext(accept);
            require(acceptedCqe != null && acceptedCqe.more() && acceptedCqe.res() >= 0,
                    "multishot ACCEPT did not emit an accepted F_MORE CQE: " + acceptedCqe);
            accepted = acceptedCqe.res();

            try (ProvidedBufferRing buffers = ProvidedBufferRing.open(ring, 91, 8, 1024)) {
                MemorySegment clientWrite = clientArena.allocate(expected.length, 1);
                MemorySegment.copy(MemorySegment.ofArray(expected), 0, clientWrite, 0, expected.length);
                SocketOps.sendAll(client, clientWrite, expected.length);

                OpToken receive = ring.submit(MultiShotOp.recvSelect(buffers, accepted, 1024, 0));
                require(buffers.activeReceiveCount() == 1, "typed multishot RECV did not lease PBUF ring");
                expectIllegalState(buffers::close,
                        "PBUF close accepted a ring with a live multishot receive lease");

                Completion receivedCqe = ring.awaitNext(receive);
                require(receivedCqe != null && receivedCqe.more() && receivedCqe.hasBuffer()
                                && receivedCqe.res() == expected.length,
                        "multishot provided RECV did not emit expected F_MORE/buffer CQE: " + receivedCqe);
                ProvidedBufferRing.Buffer buffer = buffers.take(receivedCqe);
                byte[] actual = new byte[expected.length];
                MemorySegment.copy(buffer.segment(), 0, MemorySegment.ofArray(actual), 0, actual.length);
                require(Arrays.equals(expected, actual), "provided-buffer multishot payload differs from client bytes");
                buffers.recycle(buffer);

                cancelAndAwaitTerminal(ring, receive, "RECV");
                require(buffers.activeReceiveCount() == 0,
                        "PBUF lease survived terminal multishot receive CQE");
            }

            cancelAndAwaitTerminal(ring, accept, "ACCEPT");
            System.out.println("PASS: multishot ACCEPT/RECV retain resources through F_MORE and release after cancel terminal CQE");
        } finally {
            closeQuietly(accepted);
            closeQuietly(client);
            closeQuietly(listener);
        }
        verifyUringCloseCancelsTypedMultishot();
    }

    private static void cancelAndAwaitTerminal(Uring ring, OpToken target, String label) {
        OpToken cancel = ring.cancel(target);
        Completion cancelCqe = ring.await(cancel);
        if (cancelCqe.res() != 0 && cancelCqe.res() != -Errno.ENOENT) {
            throw new AssertionError(label + " cancel CQE failed: " + cancelCqe);
        }
        Completion terminal = ring.await(target);
        if (terminal.more()) {
            throw new AssertionError(label + " await returned nonterminal CQE: " + terminal);
        }
        if (cancelCqe.res() == 0 && terminal.res() != -Errno.ECANCELED) {
            throw new AssertionError(label + " matched cancel terminal result=" + terminal.res());
        }
    }

    /** Typed parents cannot make direct {@link Uring#close()} block forever. */
    private static void verifyUringCloseCancelsTypedMultishot() {
        int listener = -1;
        Uring ring = Uring.open(8);
        try {
            listener = SocketOps.tcpSocket();
            SocketOps.setReuseAddress(listener, true);
            SocketOps.bindLoopback(listener, 0);
            SocketOps.listen(listener, 1);
            OpToken accept = ring.submit(MultiShotOp.accept(listener, SocketOps.SOCK_CLOEXEC));
            ring.close(); // must enqueue ASYNC_CANCEL then drain terminal CQE
            require(accept.isTerminal() && accept.lastCompletion() != null && !accept.lastCompletion().more(),
                    "Uring.close did not cancel/drain typed multishot accept");
            System.out.println("PASS: Uring.close auto-cancels a typed multishot parent before unmapping ring memory");
        } finally {
            // close is idempotent if an assertion above ran after it.
            ring.close();
            closeQuietly(listener);
        }
    }

    private static void expectIllegalState(Runnable action, String message) {
        try {
            action.run();
        } catch (IllegalStateException expected) {
            return;
        }
        throw new AssertionError(message);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void closeQuietly(int fd) {
        if (fd >= 0) {
            try {
                FileOps.close(fd);
            } catch (RuntimeException ignored) {
                // Preserve the lifecycle assertion above.
            }
        }
    }
}
