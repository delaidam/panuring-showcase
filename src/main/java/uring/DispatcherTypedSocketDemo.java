package uring;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Real Phase-1/2 bridge regression: a {@link Dispatcher} is the only CQ
 * reaper for a typed NOP, a multishot ACCEPT, and a multishot provided-buffer
 * RECV. It proves that {@link OpToken} observes every CQE while the PBUF
 * receive lease remains close-blocking through {@code F_MORE} and is released
 * only by the target's terminal CQE after async cancellation.
 */
public final class DispatcherTypedSocketDemo {

    private DispatcherTypedSocketDemo() {
    }

    public static void main(String[] args) {
        byte[] expected = "dispatcher typed multishot provided-buffer receive".getBytes(StandardCharsets.UTF_8);
        int listener = -1;
        int client = -1;
        int accepted = -1;
        try (Uring ring = Uring.open(32); Arena clientArena = Arena.ofConfined()) {
            UringProbe probe = UringProbe.query(ring);
            require(probe.supports(Sqe.OP_ACCEPT) && probe.supports(Sqe.OP_RECV),
                    "kernel probe lacks multishot accept/recv opcodes");

            // Register the PBUF ring before Dispatcher attaches. Its typed
            // receive lease is then released by the Dispatcher-owned CQ path.
            try (ProvidedBufferRing buffers = ProvidedBufferRing.open(ring, 92, 8, 1024);
                 Dispatcher dispatcher = new Dispatcher(ring)) {
                verifyTypedOneShot(dispatcher);

                listener = SocketOps.tcpSocket();
                SocketOps.setReuseAddress(listener, true);
                SocketOps.bindLoopback(listener, 0);
                SocketOps.listen(listener, 8);

                OpToken accept = dispatcher.submit(MultiShotOp.accept(listener, SocketOps.SOCK_CLOEXEC));
                client = SocketOps.tcpSocket();
                SocketOps.connectLoopback(client, SocketOps.localPort(listener));
                Completion acceptEvent = dispatcher.awaitNext(accept);
                require(acceptEvent != null && acceptEvent.more() && acceptEvent.res() >= 0,
                        "Dispatcher multishot ACCEPT did not emit an F_MORE accepted fd: " + acceptEvent);
                accepted = acceptEvent.res();

                OpToken receive = dispatcher.submit(MultiShotOp.recvSelect(buffers, accepted, 1024, 0));
                require(buffers.activeReceiveCount() == 1,
                        "Dispatcher typed RECV did not acquire a PBUF receive lease");
                expectIllegalState(buffers::close,
                        "PBUF close accepted a Dispatcher-owned live multishot receive");

                MemorySegment clientWrite = clientArena.allocate(expected.length, 1);
                MemorySegment.copy(MemorySegment.ofArray(expected), 0, clientWrite, 0, expected.length);
                SocketOps.sendAll(client, clientWrite, expected.length);

                Completion receiveEvent = dispatcher.awaitNext(receive);
                require(receiveEvent != null && receiveEvent.more() && receiveEvent.hasBuffer()
                                && receiveEvent.res() == expected.length,
                        "Dispatcher multishot PBUF RECV did not emit expected F_MORE/buffer CQE: " + receiveEvent);
                ProvidedBufferRing.Buffer buffer = buffers.take(receiveEvent);
                byte[] actual = new byte[expected.length];
                MemorySegment.copy(buffer.segment(), 0, MemorySegment.ofArray(actual), 0, actual.length);
                require(Arrays.equals(expected, actual), "Dispatcher PBUF RECV payload differs from client bytes");
                buffers.recycle(buffer);

                cancelAndAwaitTerminal(dispatcher, receive, "RECV");
                require(buffers.activeReceiveCount() == 0,
                        "Dispatcher terminal RECV CQE did not release the PBUF lease");
                cancelAndAwaitTerminal(dispatcher, accept, "ACCEPT");

                System.out.println("PASS: Dispatcher is sole typed CQ reaper; F_MORE PBUF lease released after target cancel terminal CQE");
            }
        } finally {
            closeQuietly(accepted);
            closeQuietly(client);
            closeQuietly(listener);
        }
    }

    private static void verifyTypedOneShot(Dispatcher dispatcher) {
        OpToken nop = dispatcher.submit(OneShotOp.nop());
        Completion completion = dispatcher.await(nop);
        require(completion.res() == 0 && !completion.more(),
                "Dispatcher typed one-shot NOP failed: " + completion);
        require(nop.completionCount() == 1 && nop.isTerminal(),
                "Dispatcher did not route the typed NOP terminal CQE to its token");
    }

    private static void cancelAndAwaitTerminal(Dispatcher dispatcher, OpToken target, String label) {
        OpToken cancel = dispatcher.cancel(target);
        Completion cancelCompletion = dispatcher.await(cancel);
        if (cancelCompletion.res() != 0 && cancelCompletion.res() != -Errno.ENOENT) {
            throw new AssertionError(label + " cancel CQE failed: " + cancelCompletion);
        }
        Completion terminal = dispatcher.await(target);
        if (terminal.more()) {
            throw new AssertionError(label + " await returned a nonterminal CQE: " + terminal);
        }
        if (cancelCompletion.res() == 0 && terminal.res() != -Errno.ECANCELED) {
            throw new AssertionError(label + " matched cancel terminal result=" + terminal.res());
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
