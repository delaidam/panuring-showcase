package uring;

import java.lang.foreign.MemorySegment;
import java.util.Objects;

/**
 * A typed operation that may produce several CQEs. The token retains every
 * managed buffer borrow while {@link Cqe#FLAG_MORE} is present and releases it
 * only at the terminal CQE. Concrete accept/recv operations arrive with the
 * network runtime; this type exists now so a multishot operation cannot be
 * accidentally represented as a one-shot one.
 */
@FunctionalInterface
public non-sealed interface MultiShotOp extends UringOp {

    @Override
    void prepare(MemorySegment sqe, OpToken token);

    /**
     * A zero-copy send is deliberately modeled as multi-CQE: the first CQE
     * reports the send result and retains {@code F_MORE}; the terminal
     * notification CQE is the only point at which the managed buffer becomes
     * reusable.
     */
    static MultiShotOp sendZc(RegisteredBufferArena arena, int socketFd,
                              RegisteredBufferArena.Buffer buffer, int len, int messageFlags) {
        return sendZc(arena, socketFd, buffer, len, messageFlags, Sqe.IORING_SEND_ZC_REPORT_USAGE);
    }

    static MultiShotOp sendZc(RegisteredBufferArena arena, int socketFd,
                              RegisteredBufferArena.Buffer buffer, int len, int messageFlags,
                              int zeroCopyFlags) {
        return new SendZc(Objects.requireNonNull(arena, "arena"), socketFd,
                Objects.requireNonNull(buffer, "buffer"), len, messageFlags, zeroCopyFlags);
    }

    /** Immutable concrete SEND_ZC operation retained for diagnostics/tests. */
    record SendZc(RegisteredBufferArena arena, int socketFd, RegisteredBufferArena.Buffer buffer,
                  int len, int messageFlags, int zeroCopyFlags) implements MultiShotOp {
        @Override
        public void prepare(MemorySegment sqe, OpToken token) {
            arena.prepSendZc(sqe, socketFd, buffer, len, messageFlags, zeroCopyFlags, token);
        }
    }

    /** Typed multishot accept; each successful CQE {@code res} is an accepted fd. */
    static MultiShotOp accept(int listenerFd, int acceptFlags) {
        return new Accept(listenerFd, acceptFlags);
    }

    /** Typed provided-buffer multishot receive with a close-blocking PBUF lease. */
    static MultiShotOp recvSelect(ProvidedBufferRing buffers, int socketFd, int maxLen, int messageFlags) {
        return new RecvSelect(Objects.requireNonNull(buffers, "buffers"), socketFd, maxLen, messageFlags);
    }

    record Accept(int listenerFd, int acceptFlags) implements MultiShotOp {
        @Override
        public void prepare(MemorySegment sqe, OpToken token) {
            Sqe.prepAcceptMultishot(sqe, listenerFd, acceptFlags, token.userData());
        }
    }

    record RecvSelect(ProvidedBufferRing buffers, int socketFd, int maxLen,
                      int messageFlags) implements MultiShotOp {
        @Override
        public Runnable acquireLifecycleLease(OpToken token) {
            return buffers.acquireReceiveLease(token);
        }

        @Override
        public void prepare(MemorySegment sqe, OpToken token) {
            buffers.prepRecvMultishot(sqe, socketFd, maxLen, messageFlags, token);
        }
    }
}
