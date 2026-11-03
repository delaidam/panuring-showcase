package uring;

/**
 * L3 step 8 verification: submits a fixed number of NOPs in batches of
 * K=64 and reports how many batches ran, so an external {@code strace -c}
 * can confirm {@code io_uring_enter} calls == ceil(ops/64) — i.e. that
 * batching actually reduces syscalls proportionally.
 */
public final class BatchCountDemo {

    public static void main(String[] args) {
        int totalOps = 640;
        int k = 64;
        int batches = 0;

        try (Rings rings = new Rings(128);
             BatchSubmitter bs = new BatchSubmitter(rings)) {
            int submitted = 0;
            while (submitted < totalOps) {
                int want = Math.min(k, totalOps - submitted);
                int prepared = bs.prepare(want, Sqe::prepNop);
                int consumed = bs.submit(prepared, prepared);
                submitted += consumed;
                batches++;
                bs.reap((userData, res, flags) -> {
                });
            }
        }
        System.out.println("submitted " + totalOps + " NOPs in " + batches + " batches of K=" + k
                + " (expect " + (totalOps / k) + " io_uring_enter calls)");
    }
}
