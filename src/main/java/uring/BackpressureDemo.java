package uring;

import java.util.BitSet;

/**
 * L3 DoD: backpressure check — request a single logical batch (K=64)
 * that exceeds the ring's SQ capacity (8 slots) in one {@link BatchSubmitter#prepare}
 * call. {@code prepare} must return fewer than requested (bounded by
 * free space) rather than losing any of them; the caller loops until
 * every requested op has actually been prepared, submitted and reaped.
 */
public final class BackpressureDemo {

    public static void main(String[] args) {
        int sqCapacity = 8;
        int k = 64; // deliberately > sqCapacity, in one logical request

        try (Rings rings = new Rings(sqCapacity);
             BatchSubmitter bs = new BatchSubmitter(rings)) {

            BitSet completed = new BitSet(k);
            int requested = k;
            int totalSubmitted = 0;
            int rounds = 0;

            while (totalSubmitted < requested) {
                int wanted = requested - totalSubmitted;
                int prepared = bs.prepare(wanted, Sqe::prepNop);
                if (rounds == 0 && prepared >= wanted) {
                    throw new AssertionError("expected the first prepare(" + wanted + ") to be capped below "
                            + wanted + " by the " + sqCapacity + "-slot SQ capacity, got " + prepared);
                }
                int consumed = bs.submit(prepared, prepared);
                totalSubmitted += consumed;
                bs.reap((userData, res, flags) -> {
                    if (res != 0) {
                        throw new AssertionError("NOP res != 0: " + res);
                    }
                    completed.set((int) userData);
                });
                rounds++;
            }

            if (completed.cardinality() != k) {
                throw new AssertionError("expected " + k + " completions, got " + completed.cardinality());
            }
            System.out.println("requested K=" + k + " in one logical batch against SQ capacity=" + sqCapacity
                    + ": needed " + rounds + " prepare/submit rounds, all " + k + " ops completed, none lost");
        }
    }
}
