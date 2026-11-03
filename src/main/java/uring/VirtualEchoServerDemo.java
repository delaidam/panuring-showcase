package uring;

import java.io.OutputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.net.InetAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * End-to-end Phase-2 echo workload. The server accepts through a typed
 * multishot {@code ACCEPT}; each accepted descriptor is handled by a virtual
 * thread using synchronous-style {@link StreamSocket#read(MemorySegment, int)}
 * and FIFO {@link StreamSocket#send(MemorySegment, int)} calls. The client
 * side deliberately uses JDK sockets, so it is a load generator rather than
 * a second copy of the system under test.
 *
 * <p>The optional first argument is the connection count (default 10,000).
 * The optional second argument bounds active server handlers (default 1,024),
 * keeping live RECV/SEND SQEs below the ring capacity even when every client
 * connects at once. This is a correctness/load regression, not a latency
 * benchmark; record environment and histogram data separately before making
 * performance claims.
 */
public final class VirtualEchoServerDemo {

    private static final int DEFAULT_CONNECTIONS = 10_000;
    private static final int DEFAULT_ACTIVE_HANDLERS = 1_024;
    private static final int PAYLOAD_BYTES = 96;

    private VirtualEchoServerDemo() {
    }

    public static void main(String[] args) throws Exception {
        int connections = positiveArg(args, 0, DEFAULT_CONNECTIONS, "connection count");
        int activeHandlers = positiveArg(args, 1, DEFAULT_ACTIVE_HANDLERS, "active handler limit");
        if (activeHandlers > 2_048) {
            throw new IllegalArgumentException("active handler limit must not exceed ring capacity 2048: " + activeHandlers);
        }

        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger echoed = new AtomicInteger();
        CountDownLatch handlersDone = new CountDownLatch(connections);
        Semaphore handlerPermits = new Semaphore(activeHandlers);
        int listener = -1;

        try (Uring ring = Uring.open(2_048); Dispatcher dispatcher = new Dispatcher(ring);
             ExecutorService handlerExecutor = Executors.newVirtualThreadPerTaskExecutor();
             ExecutorService clientExecutor = Executors.newVirtualThreadPerTaskExecutor()) {
            requireProbe(ring);
            listener = SocketOps.tcpSocket();
            SocketOps.setReuseAddress(listener, true);
            SocketOps.bindLoopback(listener, 0);
            SocketOps.listen(listener, Math.max(connections, 128));
            int port = SocketOps.localPort(listener);

            OpToken acceptToken = dispatcher.submit(MultiShotOp.accept(listener, SocketOps.SOCK_CLOEXEC));
            List<Future<?>> clients = new ArrayList<>(connections);
            for (int id = 0; id < connections; id++) {
                int clientId = id;
                clients.add(clientExecutor.submit(() -> runClient(port, clientId)));
            }

            for (int id = 0; id < connections; id++) {
                Completion event = dispatcher.awaitNext(acceptToken);
                if (event == null || !event.more() || event.res() < 0) {
                    throw new AssertionError("multishot ACCEPT ended before connection " + id + ": " + event);
                }
                int acceptedFd = event.res();
                accepted.incrementAndGet();
                handlerExecutor.submit(() -> {
                    boolean permitAcquired = false;
                    try {
                        handlerPermits.acquire();
                        permitAcquired = true;
                        echoOne(dispatcher, acceptedFd);
                        echoed.incrementAndGet();
                    } catch (Throwable handlerFailure) {
                        failure.compareAndSet(null, handlerFailure);
                    } finally {
                        if (permitAcquired) {
                            handlerPermits.release();
                        }
                        closeQuietly(acceptedFd);
                        handlersDone.countDown();
                    }
                });
            }

            cancelAndAwait(dispatcher, acceptToken);
            handlersDone.await();
            for (Future<?> client : clients) {
                client.get();
            }
            Throwable firstFailure = failure.get();
            if (firstFailure != null) {
                throw new AssertionError("virtual echo handler failed", firstFailure);
            }
            if (accepted.get() != connections || echoed.get() != connections) {
                throw new AssertionError("accepted=" + accepted + " echoed=" + echoed + " expected=" + connections);
            }
            System.out.printf("PASS: VirtualEchoServerDemo connections=%d active_handlers=%d "
                            + "multishot_accept=%d synchronous_read_send=%d%n",
                    connections, activeHandlers, accepted.get(), echoed.get());
        } finally {
            closeQuietly(listener);
        }
    }

    private static void echoOne(Dispatcher dispatcher, int fd) throws InterruptedException {
        try (StreamSocket stream = new StreamSocket(dispatcher, fd); Arena arena = Arena.ofConfined()) {
            MemorySegment bytes = arena.allocate(PAYLOAD_BYTES, 1);
            int offset = 0;
            while (offset < PAYLOAD_BYTES) {
                int received = stream.read(bytes.asSlice(offset, PAYLOAD_BYTES - offset), PAYLOAD_BYTES - offset);
                if (received < 0) {
                    throw new UringException("RECV", -received);
                }
                if (received == 0) {
                    throw new AssertionError("client closed before sending fixed " + PAYLOAD_BYTES + " byte request");
                }
                offset += received;
            }
            int sent = stream.send(bytes, PAYLOAD_BYTES);
            if (sent != PAYLOAD_BYTES) {
                throw new AssertionError("StreamSocket.send returned " + sent + " instead of " + PAYLOAD_BYTES);
            }
        }
    }

    private static void runClient(int port, int id) {
        byte[] expected = payloadFor(id);
        try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), port)) {
            OutputStream output = socket.getOutputStream();
            output.write(expected);
            output.flush();
            byte[] actual = socket.getInputStream().readNBytes(expected.length);
            if (!java.util.Arrays.equals(expected, actual)) {
                throw new AssertionError("client " + id + " received a mismatched echo");
            }
        } catch (Exception clientFailure) {
            throw new IllegalStateException("client " + id + " failed", clientFailure);
        }
    }

    private static byte[] payloadFor(int id) {
        byte[] bytes = new byte[PAYLOAD_BYTES];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) (id * 31 + i);
        }
        return bytes;
    }

    private static void requireProbe(Uring ring) {
        UringProbe probe = UringProbe.query(ring);
        if (!probe.supports(Sqe.OP_ACCEPT) || !probe.supports(Sqe.OP_RECV) || !probe.supports(Sqe.OP_SEND)) {
            throw new IllegalStateException("kernel probe lacks ACCEPT/RECV/SEND: " + probe);
        }
    }

    private static void cancelAndAwait(Dispatcher dispatcher, OpToken target) {
        OpToken cancel = dispatcher.cancel(target);
        Completion cancelResult = dispatcher.await(cancel);
        if (cancelResult.res() != 0 && cancelResult.res() != -Errno.ENOENT) {
            throw new AssertionError("ACCEPT cancellation failed: " + cancelResult);
        }
        Completion terminal = dispatcher.await(target);
        if (terminal.more()) {
            throw new AssertionError("ACCEPT terminal await returned F_MORE: " + terminal);
        }
    }

    private static int positiveArg(String[] args, int index, int defaultValue, String name) {
        if (args.length <= index) {
            return defaultValue;
        }
        if (args.length > 2) {
            throw new IllegalArgumentException("usage: VirtualEchoServerDemo [connections] [active-handler-limit]");
        }
        int value = Integer.parseInt(args[index]);
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive: " + value);
        }
        return value;
    }

    private static void closeQuietly(int fd) {
        if (fd >= 0) {
            try {
                FileOps.close(fd);
            } catch (RuntimeException ignored) {
                // Preserve the original workload failure.
            }
        }
    }
}
