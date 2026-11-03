package uring;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Runnable Phase-2 registration/lifecycle smoke test.  It exercises a real
 * REGISTER_PROBE plus REGISTER_PBUF_RING → release-published initial tail →
 * a real loopback buffer-select receive → UNREGISTER_PBUF_RING sequence.
 */
public final class ProvidedBufferRingDemo {

    private ProvidedBufferRingDemo() {
    }

    public static void main(String[] args) {
        try (Uring ring = Uring.open(8)) {
            UringProbe probe = UringProbe.query(ring);
            require(probe.supports(Sqe.OP_ACCEPT), "kernel probe does not report ACCEPT support");
            require(probe.supports(Sqe.OP_RECV), "kernel probe does not report RECV support");
            require(probe.supports(Sqe.OP_SEND), "kernel probe does not report SEND support");
            System.out.printf("probe last_op=%d ops_len=%d accept=%s recv=%s send=%s%n",
                    probe.lastOp(), probe.reportedOpsLength(), probe.supports(Sqe.OP_ACCEPT),
                    probe.supports(Sqe.OP_RECV), probe.supports(Sqe.OP_SEND));

            ProvidedBufferRing pbuf = ProvidedBufferRing.open(ring, 73, 8, 1024);
            try {
                require(pbuf.publishedTail() == 8,
                        "initial descriptors were not release-published (tail=" + pbuf.publishedTail() + ")");
                require(pbuf.checkedOutCount() == 0, "fresh ring unexpectedly has checked-out buffers");

                // This synthetic CQE only drives Java-side ownership state;
                // registration/unregistration above are the real kernel test.
                Completion selected = new Completion(0x701L, 17, Cqe.FLAG_BUFFER | (3 << 16));
                ProvidedBufferRing.Buffer buffer = pbuf.take(selected);
                require(buffer.id() == 3 && buffer.isCheckedOut(), "CQE buffer-id ownership transfer failed");
                expectIllegalState(pbuf::close, "close accepted a Java-checked-out buffer");
                MemorySegment bytes = buffer.segment();
                bytes.set(java.lang.foreign.ValueLayout.JAVA_BYTE, 0, (byte) 0x5a);
                pbuf.recycle(buffer);
                require(!buffer.isCheckedOut() && pbuf.checkedOutCount() == 0,
                        "recycle did not return the buffer to the kernel ring");
                require(pbuf.publishedTail() == 9, "recycle did not release-publish one descriptor");
                expectIllegalState(() -> pbuf.recycle(buffer), "double recycle was accepted");
                expectUnsupported(() -> pbuf.take(new Completion(0x702L, 1,
                                Cqe.FLAG_BUFFER | (3 << 16) | (1 << 4))),
                        "incremental PBUF CQE was accepted by whole-buffer API");
                System.out.println("PASS: PBUF ownership transfer + recycle tail publication OK");

                receiveIntoRealProvidedBuffer(ring, pbuf);
            } finally {
                pbuf.close();
            }
            require(pbuf.isClosed(), "PBUF ring did not finish unregister -> free close sequence");
            expectIllegalState(() -> pbuf.publishedTail(), "closed PBUF ring still exposes native ring state");
            System.out.println("PASS: REGISTER_PBUF_RING -> UNREGISTER_PBUF_RING -> Arena close OK");
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

    private static void expectUnsupported(Runnable action, String message) {
        try {
            action.run();
        } catch (UnsupportedOperationException expected) {
            return;
        }
        throw new AssertionError(message);
    }

    /** Proves the buffer-select SQE reaches an actual loopback receive, not just a scratch layout. */
    private static void receiveIntoRealProvidedBuffer(Uring ring, ProvidedBufferRing pbuf) {
        byte[] expected = "provided-buffer ring recv".getBytes(StandardCharsets.UTF_8);
        int listener = -1;
        int client = -1;
        int accepted = -1;
        try (Arena clientArena = Arena.ofConfined()) {
            listener = SocketOps.tcpSocket();
            SocketOps.setReuseAddress(listener, true);
            SocketOps.bindLoopback(listener, 0);
            SocketOps.listen(listener, 8);
            int port = SocketOps.localPort(listener);

            client = SocketOps.tcpSocket();
            SocketOps.connectLoopback(client, port);
            MemorySegment clientBytes = clientArena.allocate(expected.length, 1);
            MemorySegment.copy(MemorySegment.ofArray(expected), 0, clientBytes, 0, expected.length);
            SocketOps.sendAll(client, clientBytes, expected.length);

            long acceptUserData = 0x7301L;
            Sqe.prepAccept(ring.sqeSlot(), listener, MemorySegment.NULL, MemorySegment.NULL,
                    SocketOps.SOCK_CLOEXEC, acceptUserData);
            Completion accept = submitOne(ring, acceptUserData);
            require(accept.res() >= 0, "ACCEPT failed: " + accept);
            accepted = accept.res();

            long recvUserData = 0x7302L;
            pbuf.prepRecvSelect(ring.sqeSlot(), accepted, expected.length, 0, recvUserData);
            Completion recv = submitOne(ring, recvUserData);
            require(recv.res() == expected.length,
                    "provided-buffer RECV returned " + recv.res() + " expected " + expected.length);
            require(recv.hasBuffer(), "provided-buffer RECV did not carry IORING_CQE_F_BUFFER: " + recv);
            ProvidedBufferRing.Buffer buffer = pbuf.take(recv);
            byte[] actual = new byte[expected.length];
            MemorySegment.copy(buffer.segment(), 0, MemorySegment.ofArray(actual), 0, actual.length);
            require(Arrays.equals(expected, actual), "provided-buffer RECV payload differs from input");
            pbuf.recycle(buffer);
            System.out.printf("PASS: real buffer-select RECV group=%d buffer=%d bytes=%d%n",
                    pbuf.bufferGroupId(), buffer.id(), actual.length);
        } finally {
            closeQuietly(accepted);
            closeQuietly(client);
            closeQuietly(listener);
        }
    }

    private static Completion submitOne(Uring ring, long expectedUserData) {
        int submitted = ring.submitAndWait(1);
        require(submitted == 1, "expected one submitted SQE, kernel consumed " + submitted);
        Completion[] completion = {null};
        int reaped = ring.reapCompletions(cqe -> {
            if (cqe.userData() != expectedUserData) {
                throw new AssertionError("CQE user_data=0x" + Long.toHexString(cqe.userData())
                        + " expected=0x" + Long.toHexString(expectedUserData));
            }
            completion[0] = cqe;
        });
        require(reaped == 1 && completion[0] != null, "expected exactly one CQE, got " + reaped);
        require(!completion[0].more(), "one-shot provided-buffer demo unexpectedly received F_MORE");
        return completion[0];
    }

    private static void closeQuietly(int fd) {
        if (fd >= 0) {
            try {
                FileOps.close(fd);
            } catch (RuntimeException ignored) {
                // Preserve the useful test failure. Happy-path descriptors are closed here.
            }
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
