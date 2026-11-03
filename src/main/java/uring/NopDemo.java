package uring;

/**
 * L1 regression test, now expressed through the {@link Uring} facade:
 * submit one IORING_OP_NOP, reap one CQE. The manual VarHandle
 * acquire/release plumbing lives in {@link Uring} and {@link Rings}
 * now; this class only exercises the public facade.
 */
public final class NopDemo {

    public static void main(String[] args) {
        try (Uring ring = Uring.open(4)) {
            long marker = 0x42L;

            Sqe.prepNop(ring.sqeSlot(), marker);
            ring.submitAndWait(1);

            int[] resHolder = new int[1];
            long[] userDataHolder = new long[1];
            int reaped = ring.reap((userData, res, flags) -> {
                userDataHolder[0] = userData;
                resHolder[0] = res;
            });

            if (reaped != 1) {
                throw new AssertionError("expected exactly 1 CQE, reaped " + reaped);
            }
            if (userDataHolder[0] != marker || resHolder[0] != 0) {
                throw new AssertionError("unexpected CQE: user_data=0x" + Long.toHexString(userDataHolder[0])
                        + " res=" + resHolder[0]);
            }

            System.out.printf("NOP round-trip OK: res=%d user_data=0x%x (sq_entries=%d, features=0x%x)%n",
                    resHolder[0], userDataHolder[0], ring.rings().sqEntries, ring.rings().features);
        }
    }
}
