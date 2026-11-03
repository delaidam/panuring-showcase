package uring;

import java.util.Objects;

/**
 * Reports a failure after a typed SQE was already release-published.
 *
 * <p>The kernel may have consumed the SQE despite the failed submit-side
 * syscall (for example, an interrupted or otherwise ambiguous enter). The
 * attached {@link #token()} therefore remains the owner of any managed
 * buffer. Do not reuse or free that buffer merely because submission threw;
 * retain the token and drive it to a terminal CQE when the kernel permits.
 * This exception deliberately makes that lifetime obligation visible instead
 * of silently returning a borrowed buffer to the application.
 */
public final class OpSubmissionException extends RuntimeException {

    private final OpToken token;

    OpSubmissionException(OpToken token, RuntimeException cause) {
        super("typed SQE was published, but io_uring_enter did not confirm its outcome; retain token 0x"
                + Long.toHexString(Objects.requireNonNull(token, "token").userData()), cause);
        this.token = token;
    }

    /** The still-live ownership token whose terminal CQE resolves the uncertainty. */
    public OpToken token() {
        return token;
    }
}
