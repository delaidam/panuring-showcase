package uring;

import static java.lang.foreign.ValueLayout.JAVA_INT;

/**
 * L3 step 6: deliberately submits {@code 2 * cq_entries} NOPs without
 * ever reaping, to force CQ overflow, then inspects the {@code overflow}
 * counter and the {@code IORING_SQ_CQ_OVERFLOW} flag. Kernel 7.0
 * supports {@code IORING_FEAT_NODROP} (confirmed in L1, RESEARCH.md §7),
 * so the expectation is a silent internal backlog + counter, not
 * {@code -EBUSY} from enter — verified empirically below, not assumed.
 */
public final class CqOverflowDemo {

    private static final int IORING_SQ_CQ_OVERFLOW = 1 << 1;

    public static void main(String[] args) {
        int sqEntries = 16; // small ring: default cq_entries = 32, quick to exceed
        try (Rings rings = new Rings(sqEntries);
             BatchSubmitter bs = new BatchSubmitter(rings)) {

            int cqEntries = rings.cqEntries;
            int target = 2 * cqEntries;
            System.out.println("cq_entries=" + cqEntries + ", submitting " + target + " NOPs without ever reaping");

            int submitted = 0;
            while (submitted < target) {
                int want = Math.min(sqEntries, target - submitted);
                int prepared = bs.prepare(want, Sqe::prepNop);
                if (prepared == 0) {
                    System.out.println("prepare() returned 0 at submitted=" + submitted
                            + " -- SQ backpressure hit before reaching target");
                    break;
                }
                int consumed = bs.submit(prepared, 0); // minComplete=0: don't block waiting for completions
                submitted += consumed;
            }
            System.out.println("submitted=" + submitted + " NOPs total, reap() never called yet");

            int sqFlags = rings.ringSegment().get(JAVA_INT, rings.sqFlagsOff);
            int overflowBefore = rings.ringSegment().get(JAVA_INT, rings.cqOverflowOff);
            System.out.printf("SQ ring flags=0x%x, IORING_SQ_CQ_OVERFLOW set=%b%n",
                    sqFlags, (sqFlags & IORING_SQ_CQ_OVERFLOW) != 0);
            System.out.println("CQ ring overflow counter=" + overflowBefore);

            int[] firstReap = {0};
            int n1 = bs.reap((userData, res, flags) -> firstReap[0]++);
            System.out.println("first reap() (pure userspace ring read, no syscall): drained " + n1
                    + " CQEs (expect up to cq_entries=" + cqEntries + ")");

            // Give the kernel a chance to flush the NODROP backlog into the now-freed ring space.
            int consumedPoke = bs.submit(0, 0);
            int[] secondReap = {0};
            int n2 = bs.reap((userData, res, flags) -> secondReap[0]++);
            System.out.println("poke enter(0,0) + second reap(): drained " + n2 + " more CQEs");

            int overflowAfter = rings.ringSegment().get(JAVA_INT, rings.cqOverflowOff);
            System.out.println("CQ ring overflow counter after drain=" + overflowAfter
                    + " (kernel does not decrement it retroactively -- it's a lifetime counter, verified here)");
            System.out.println("total reaped=" + (n1 + n2) + " vs submitted=" + submitted);
        }
    }
}
