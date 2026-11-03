package uring;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Phase 2 raw-ring baseline: a loopback TCP client sends one message, while
 * the server accepts, receives, and sends the echo entirely through raw
 * {@code io_uring} SQEs. No {@link Dispatcher} or typed operation API is
 * involved here; it is deliberately the small wire-format proof on which the
 * later multishot/virtual-thread service is built.
 */
public final class SocketEchoDemo {

    private static final long ACCEPT_USER_DATA = 0xACCE_0001L;
    private static final long RECV_USER_DATA_BASE = 0xACCE_1000L;
    private static final long SEND_USER_DATA_BASE = 0xACCE_2000L;

    private SocketEchoDemo() {
    }

    public static void main(String[] args) {
        byte[] expected = "raw io_uring loopback echo via Panama".getBytes(StandardCharsets.UTF_8);
        int listener = -1;
        int client = -1;
        int accepted = -1;

        try (Uring ring = Uring.open(8); Arena clientArena = Arena.ofConfined()) {
            listener = SocketOps.tcpSocket();
            SocketOps.setReuseAddress(listener, true);
            SocketOps.bindLoopback(listener, 0);
            SocketOps.listen(listener, 8);
            int port = SocketOps.localPort(listener);

            // A listener's completed backlog lets the synchronous setup
            // client connect before the ring posts its accept SQE.
            client = SocketOps.tcpSocket();
            SocketOps.connectLoopback(client, port);
            MemorySegment clientWrite = clientArena.allocate(expected.length, 1);
            MemorySegment.copy(MemorySegment.ofArray(expected), 0, clientWrite, 0, expected.length);
            SocketOps.sendAll(client, clientWrite, expected.length);

            Sqe.prepAccept(ring.sqeSlot(), listener, MemorySegment.NULL, MemorySegment.NULL,
                    SocketOps.SOCK_CLOEXEC, ACCEPT_USER_DATA);
            accepted = requireNonNegative("ACCEPT", submitOne(ring, ACCEPT_USER_DATA));

            MemorySegment serverBuffer = ring.allocateBuffer(expected.length, 8);
            receiveViaRing(ring, accepted, serverBuffer, expected.length);
            sendViaRing(ring, accepted, serverBuffer, expected.length);

            MemorySegment clientRead = clientArena.allocate(expected.length, 1);
            SocketOps.receiveExactly(client, clientRead, expected.length);
            byte[] actual = new byte[expected.length];
            MemorySegment.copy(clientRead, 0, MemorySegment.ofArray(actual), 0, actual.length);
            if (!Arrays.equals(expected, actual)) {
                throw new AssertionError("loopback echo payload differs from input");
            }

            System.out.printf("PASS: SocketEchoDemo port=%d accepted_fd=%d bytes=%d raw ACCEPT/RECV/SEND byte-exact%n",
                    port, accepted, actual.length);
        } finally {
            closeQuietly(accepted);
            closeQuietly(client);
            closeQuietly(listener);
        }
    }

    private static void receiveViaRing(Uring ring, int socketFd, MemorySegment buffer, int expectedBytes) {
        int received = 0;
        while (received < expectedBytes) {
            int remaining = expectedBytes - received;
            long userData = RECV_USER_DATA_BASE + received;
            Sqe.prepRecv(ring.sqeSlot(), socketFd, buffer.asSlice(received, remaining), remaining, 0, userData);
            int result = requireNonNegative("RECV", submitOne(ring, userData));
            if (result == 0) {
                throw new AssertionError("peer closed during ring receive after " + received + " bytes");
            }
            if (result > remaining) {
                throw new AssertionError("RECV returned " + result + " for only " + remaining + " requested bytes");
            }
            received += result;
        }
    }

    private static void sendViaRing(Uring ring, int socketFd, MemorySegment buffer, int byteCount) {
        int sent = 0;
        while (sent < byteCount) {
            int remaining = byteCount - sent;
            long userData = SEND_USER_DATA_BASE + sent;
            Sqe.prepSend(ring.sqeSlot(), socketFd, buffer.asSlice(sent, remaining), remaining,
                    SocketOps.MSG_NOSIGNAL, userData);
            int result = requireNonNegative("SEND", submitOne(ring, userData));
            if (result == 0) {
                throw new AssertionError("SEND returned zero before all echo bytes were written");
            }
            if (result > remaining) {
                throw new AssertionError("SEND returned " + result + " for only " + remaining + " requested bytes");
            }
            sent += result;
        }
    }

    private static int submitOne(Uring ring, long expectedUserData) {
        int submitted = ring.submitAndWait(1);
        if (submitted != 1) {
            throw new AssertionError("expected one submitted SQE, kernel consumed " + submitted);
        }
        int[] result = {Integer.MIN_VALUE};
        int[] flags = {0};
        int reaped = ring.reap((userData, res, cqeFlags) -> {
            if (userData != expectedUserData) {
                throw new AssertionError("CQE user_data=0x" + Long.toHexString(userData)
                        + " expected=0x" + Long.toHexString(expectedUserData));
            }
            result[0] = res;
            flags[0] = cqeFlags;
        });
        if (reaped != 1) {
            throw new AssertionError("expected exactly one CQE, got " + reaped);
        }
        if (Cqe.hasMore(flags[0])) {
            throw new AssertionError("raw one-shot socket baseline unexpectedly received IORING_CQE_F_MORE");
        }
        return result[0];
    }

    private static int requireNonNegative(String operation, int result) {
        if (result < 0) {
            throw new UringException(operation, -result);
        }
        return result;
    }

    private static void closeQuietly(int fd) {
        if (fd >= 0) {
            try {
                FileOps.close(fd);
            } catch (RuntimeException ignored) {
                // Preserve the original demo failure; all happy-path fds are
                // closed before control reaches here.
            }
        }
    }
}
