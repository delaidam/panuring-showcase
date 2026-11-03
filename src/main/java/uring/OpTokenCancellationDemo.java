package uring;

import java.time.Duration;

/**
 * Real-kernel cancellation regression: a long timeout is submitted, then
 * cancelled by its typed token. The cancel control CQE and target terminal
 * CQE are intentionally awaited separately, because only the latter releases
 * target ownership in the general buffer-bearing case.
 */
public final class OpTokenCancellationDemo {

    public static void main(String[] args) {
        try (Uring ring = Uring.open(8)) {
            OpToken target = ring.submit(ring.timeout(Duration.ofSeconds(20)));
            OpToken cancel = ring.cancel(target);

            Completion cancelCompletion = ring.await(cancel);
            Completion targetCompletion = ring.await(target);

            if (cancelCompletion.res() != 0 && cancelCompletion.res() != -Errno.ENOENT) {
                throw new AssertionError("unexpected ASYNC_CANCEL CQE: " + cancelCompletion);
            }
            if (cancelCompletion.res() == 0 && targetCompletion.res() != -Errno.ECANCELED) {
                throw new AssertionError("matched cancel must end target with -ECANCELED, got " + targetCompletion);
            }
            if (!cancel.isTerminal() || !target.isTerminal()) {
                throw new AssertionError("cancel or target token was not terminal");
            }
            if (ring.inFlightCount() != 0) {
                throw new AssertionError("cancellation left live in-flight state: " + ring.inFlightCount());
            }
            System.out.println("PASS: ASYNC_CANCEL control CQE=" + cancelCompletion.res()
                    + ", target terminal CQE=" + targetCompletion.res());
        }
    }
}
