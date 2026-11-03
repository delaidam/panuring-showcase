package uring;

/**
 * A copied io_uring completion value. Keeping {@code flags} with the result
 * is essential: {@link #more()} determines whether a multishot parent still
 * owns its buffers, and provided-buffer / zero-copy paths need the remaining
 * flag bits as well.
 */
public record Completion(long userData, int res, int flags) {

    /** True when this is not the parent operation's terminal CQE. */
    public boolean more() {
        return Cqe.hasMore(flags);
    }

    /** io_uring reports operation errors as negative errno values in {@code res}. */
    public boolean failed() {
        return res < 0;
    }

    /** True when the upper 16 bits of {@code flags} contain a provided-buffer ID. */
    public boolean hasBuffer() {
        return Cqe.hasBuffer(flags);
    }

    /**
     * Provided-buffer ID from this CQE. Call only when {@link #hasBuffer()} is
     * true; otherwise this value has no meaning.
     */
    public int bufferId() {
        return Cqe.bufferId(flags);
    }

    /** True for the terminal notification CQE of a zero-copy send. */
    public boolean notification() {
        return Cqe.hasNotification(flags);
    }

    /** True when a provided-buffer receive uses the incremental bundle protocol. */
    public boolean bufferMore() {
        return Cqe.hasBufferMore(flags);
    }
}
