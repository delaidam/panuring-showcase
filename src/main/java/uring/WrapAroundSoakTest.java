package uring;

import java.util.BitSet;

import static java.lang.foreign.ValueLayout.JAVA_INT;

/**
 * L3 step 5: pushes {@code 20 * ring_entries} NOPs through the ring in
 * batches of K, for K in {1, 8, 64, 256} — enough total volume to wrap
 * the SQ/CQ ring indices around many times over, which is exactly the
 * kind of mask-arithmetic bug that survives small tests. Correlation
 * is by user_data alone (a BitSet); asserts every one completes exactly
 * once and nothing is dropped.
 */
public final class WrapAroundSoakTest {

    public static void main(String[] args) {
        int ringEntries = 256;
        for (int k : new int[] {1, 8, 64, 256}) {
            runSoak(ringEntries, k);
        }
        System.out.println("PASS: wrap-around soak test OK for K in {1, 8, 64, 256}");
    }

    private static void runSoak(int ringEntries, int k) {
        int totalOps = 20 * ringEntries;
        try (Rings rings = new Rings(ringEntries);
             BatchSubmitter bs = new BatchSubmitter(rings)) {

            BitSet completed = new BitSet(totalOps);
            int submitted = 0;

            while (submitted < totalOps) {
                int want = Math.min(k, totalOps - submitted);
                int prepared = bs.prepare(want, Sqe::prepNop);
                if (prepared == 0) {
                    throw new AssertionError("prepare() returned 0 -- ring unexpectedly full");
                }
                // minComplete == prepared: block until everything just submitted is done (ROADMAP.md §5 step 3)
                int consumed = bs.submit(prepared, prepared);
                if (consumed != prepared) {
                    int dropped = rings.ringSegment().get(JAVA_INT, rings.sqDroppedOff);
                    throw new AssertionError("consumed(" + consumed + ") != prepared(" + prepared + "), dropped=" + dropped);
                }
                submitted += consumed;

                int reaped = bs.reap((userData, res, flags) -> {
                    if (res != 0) {
                        throw new AssertionError("NOP res != 0: " + res);
                    }
                    int idx = (int) userData;
                    if (completed.get(idx)) {
                        throw new AssertionError("user_data " + idx + " completed twice");
                    }
                    completed.set(idx);
                });
                if (reaped != consumed) {
                    throw new AssertionError("reaped(" + reaped + ") != consumed(" + consumed + ") right after a blocking submit");
                }
            }

            if (completed.cardinality() != totalOps) {
                throw new AssertionError("expected " + totalOps + " completions, got " + completed.cardinality());
            }
            System.out.println("K=" + k + ": " + totalOps + " NOPs through ring_entries=" + ringEntries
                    + ", all completed exactly once, 0 dropped");
        }
    }
}
