package uring;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Real SEND_ZC lifecycle regression. It proves that a managed source buffer
 * remains borrowed across the data CQE and becomes reusable only after the
 * terminal notification CQE.
 */
public final class SendZcDemo {

    private static final long ACCEPT_USER_DATA = 0x5A_CE_0001L;

    private SendZcDemo() {
    }

    public static void main(String[] args) {
        byte[] expected = "SEND_ZC terminal notification owns this buffer".getBytes(StandardCharsets.UTF_8);
        int listener = -1;
        int client = -1;
        int accepted = -1;
        try (Uring ring = Uring.open(8); Arena clientArena = Arena.ofConfined()) {
            UringProbe probe = UringProbe.query(ring);
            if (!probe.supports(Sqe.OP_SEND_ZC)) {
                throw new UnsupportedOperationException("kernel probe does not support IORING_OP_SEND_ZC");
            }
            listener = SocketOps.tcpSocket();
            SocketOps.setReuseAddress(listener, true);
            SocketOps.bindLoopback(listener, 0);
            SocketOps.listen(listener, 8);
            int port = SocketOps.localPort(listener);
            client = SocketOps.tcpSocket();
            SocketOps.connectLoopback(client, port);

            Sqe.prepAccept(ring.sqeSlot(), listener, MemorySegment.NULL, MemorySegment.NULL,
                    SocketOps.SOCK_CLOEXEC, ACCEPT_USER_DATA);
            accepted = accept(ring);

            try (RegisteredBufferArena buffers = ring.openRegisteredBufferArena(1, 4096, 64)) {
                RegisteredBufferArena.Buffer source = buffers.buffer(0);
                MemorySegment.copy(MemorySegment.ofArray(expected), 0, source.segment(), 0, expected.length);
                OpToken send = ring.submit(MultiShotOp.sendZc(buffers, accepted, source, expected.length,
                        SocketOps.MSG_NOSIGNAL));
                if (!source.isBorrowed()) {
                    throw new AssertionError("SEND_ZC source was not borrowed after typed submission");
                }

                Completion terminal = ring.await(send);
                if (send.completionCount() < 2 || !terminal.notification() || terminal.more()) {
                    throw new AssertionError("SEND_ZC did not produce terminal notification: completions="
                            + send.completionCount() + " terminal=" + terminal);
                }
                Completion data = send.awaitNext();
                Completion notification = send.awaitNext();
                if (data == null || notification == null || !data.more() || data.res() != expected.length
                        || !notification.notification() || notification.more()) {
                    throw new AssertionError("unexpected SEND_ZC CQE sequence data=" + data
                            + " notification=" + notification);
                }
                if (source.isBorrowed()) {
                    throw new AssertionError("SEND_ZC source remained borrowed after terminal notification");
                }

                MemorySegment clientRead = clientArena.allocate(expected.length, 1);
                SocketOps.receiveExactly(client, clientRead, expected.length);
                byte[] actual = new byte[expected.length];
                MemorySegment.copy(clientRead, 0, MemorySegment.ofArray(actual), 0, actual.length);
                if (!Arrays.equals(expected, actual)) {
                    throw new AssertionError("SEND_ZC client payload differs from source");
                }
                System.out.println("PASS: SEND_ZC data CQE + terminal notification preserve buffer lifetime"
                        + " (notification flags=0x" + Integer.toHexString(notification.flags()) + ")");
            }
        } finally {
            closeQuietly(accepted);
            closeQuietly(client);
            closeQuietly(listener);
        }
    }

    private static int accept(Uring ring) {
        int submitted = ring.submitAndWait(1);
        if (submitted != 1) {
            throw new AssertionError("kernel consumed " + submitted + " ACCEPT SQEs");
        }
        int[] result = {Integer.MIN_VALUE};
        int reaped = ring.reap((userData, res, flags) -> {
            if (userData != ACCEPT_USER_DATA || Cqe.hasMore(flags)) {
                throw new AssertionError("unexpected ACCEPT CQE user_data=0x" + Long.toHexString(userData)
                        + " flags=0x" + Integer.toHexString(flags));
            }
            result[0] = res;
        });
        if (reaped != 1 || result[0] < 0) {
            throw new AssertionError("ACCEPT failed: reaped=" + reaped + " result=" + result[0]);
        }
        return result[0];
    }

    private static void closeQuietly(int fd) {
        if (fd >= 0) {
            try {
                FileOps.close(fd);
            } catch (RuntimeException ignored) {
                // Preserve the primary assertion/call failure.
            }
        }
    }
}
