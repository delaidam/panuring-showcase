package uring;

import java.lang.foreign.MemorySegment;
import java.util.Objects;

/**
 * A typed operation expected to have exactly one terminal completion. If a
 * kernel feature unexpectedly reports {@link Cqe#FLAG_MORE}, the associated
 * {@link OpToken} remains borrowed until the eventual terminal CQE rather
 * than releasing memory early.
 */
@FunctionalInterface
public non-sealed interface OneShotOp extends UringOp {

    @Override
    void prepare(MemorySegment sqe, OpToken token);

    /** A typed NOP, useful for exercising token/completion correlation. */
    static OneShotOp nop() {
        return (sqe, token) -> Sqe.prepNop(sqe, token.userData());
    }

    /**
     * A managed {@code IORING_OP_READ_FIXED}. The buffer is borrowed by the
     * returned token from submission until its terminal CQE; callers use
     * {@link RegisteredBufferArena.Buffer#segment()} only before submission
     * or after that token becomes terminal.
     */
    static OneShotOp readFixed(RegisteredBufferArena arena, int fd,
                               RegisteredBufferArena.Buffer buffer, int len, long fileOffset) {
        Objects.requireNonNull(arena, "arena");
        Objects.requireNonNull(buffer, "buffer");
        return new FixedRead(arena, fd, buffer, len, fileOffset);
    }

    /** A one-shot timeout backed by an Uring-owned {@code __kernel_timespec}. */
    static OneShotOp timeout(MemorySegment timespec) {
        Objects.requireNonNull(timespec, "timespec");
        return (sqe, token) -> Sqe.prepTimeout(sqe, timespec, token.userData());
    }

    /** Concrete immutable operation retained for diagnostics and testability. */
    record FixedRead(RegisteredBufferArena arena, int fd, RegisteredBufferArena.Buffer buffer,
                     int len, long fileOffset) implements OneShotOp {
        public FixedRead {
            Objects.requireNonNull(arena, "arena");
            Objects.requireNonNull(buffer, "buffer");
        }

        @Override
        public void prepare(MemorySegment sqe, OpToken token) {
            arena.prepReadFixed(sqe, fd, buffer, len, fileOffset, token);
        }
    }
}
