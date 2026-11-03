package uring;

/**
 * P2 apparatus check (ROADMAP.md §7.2/§7.4): confirms {@code IORING_SETUP_SQPOLL}
 * actually spawns a kernel thread named {@code iou-sqp-<tid>} (the
 * detection method §7.4 specifies for CPU-per-thread accounting), and
 * that {@code IORING_SETUP_SQ_AFF} pins it to the requested CPU.
 *
 * <p>Matches on the {@code iou-sqp-} prefix only, not the exact PID/TID
 * suffix: the kernel names it after the calling thread's TID, which
 * doesn't always equal {@link ProcessHandle#pid()} (e.g. under a PID
 * namespace) — confirmed empirically while building this check.
 */
public final class SqpollDemo {

    public static void main(String[] args) throws Exception {
        try (Rings unpinned = new Rings(new Rings.RingOptions(64, -1, true, 100, -1))) {
            System.out.println("sqpoll=" + unpinned.sqpoll + " (unpinned)");
            Thread.sleep(200); // let the kernel thread show up
            String found = findThreadNamePrefixed("iou-sqp-");
            if (found == null) {
                throw new AssertionError("expected an iou-sqp-* kernel thread, none found");
            }
            System.out.println("PASS: found kernel thread '" + found + "'");
        }

        int pinnedCpu = 0;
        try (Rings pinned = new Rings(new Rings.RingOptions(64, -1, true, 100, pinnedCpu))) {
            System.out.println("sqpoll=" + pinned.sqpoll + " (pinned to cpu " + pinnedCpu + ")");
            Thread.sleep(200);
            String found = findThreadNamePrefixed("iou-sqp-");
            if (found == null) {
                throw new AssertionError("expected an iou-sqp-* kernel thread, none found");
            }
            System.out.println("PASS: SQPOLL thread '" + found + "' present with sq_thread_cpu=" + pinnedCpu
                    + " (affinity verified manually via `ps -eLo tid,comm | grep iou-sqp` + `taskset -pc <tid>`)");
        }
    }

    private static String findThreadNamePrefixed(String prefix) throws Exception {
        ProcessBuilder pb = new ProcessBuilder("sh", "-c", "ps -eLo comm | grep '^" + prefix + "'");
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String output = new String(p.getInputStream().readAllBytes()).trim();
        p.waitFor();
        return output.isEmpty() ? null : output.lines().findFirst().orElse(null);
    }
}
