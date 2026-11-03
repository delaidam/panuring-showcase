package uring;

import java.lang.foreign.MemorySegment;

/**
 * Common typed-operation contract. The two permitted public categories are
 * deliberately distinct: a {@link OneShotOp} has one terminal completion,
 * while a {@link MultiShotOp} may retain kernel ownership through CQEs marked
 * {@link Cqe#FLAG_MORE}.
 *
 * <p>Implementations prepare an SQE only through the token supplied by
 * {@link Uring#submit(OneShotOp)} or {@link Uring#submit(MultiShotOp)}. The
 * token owns the completion correlation and, when applicable, a borrow of the
 * managed buffer until the terminal CQE.
 */
public sealed interface UringOp permits OneShotOp, MultiShotOp {

    /**
     * Managed fixed buffer borrowed by this operation, or {@code null} for an
     * operation such as NOP that owns no user buffer.
     */
    default RegisteredBufferArena.Buffer buffer() {
        return null;
    }

    /**
     * Acquires any additional operation-scoped resource after the token has a
     * stable user-data value and before its SQE is prepared. The returned
     * action is invoked exactly once when the token reaches a terminal CQE or
     * preparation rolls back before publication. This lets a multishot
     * provided-buffer receive keep its buffer ring open without weakening the
     * normal {@link RegisteredBufferArena.Buffer} borrow rule.
     */
    default Runnable acquireLifecycleLease(OpToken token) {
        return null;
    }

    /** Fill the reserved SQE. Implementations must use {@link OpToken#userData()}. */
    void prepare(MemorySegment sqe, OpToken token);
}
