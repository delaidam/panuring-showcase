package uring;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Online invariant checker for ring integrations.
 *
 * <p>The doctor is opt-in and intentionally records findings instead of
 * throwing from a CQ reaper: an observation failure must not wedge a CQ head
 * or hide the original I/O result. Runtime integrations feed it operation,
 * completion, buffer-return, and pressure events. It can also be used by
 * standalone negative demos before an integration is attached to a ring.
 */
public final class Doctor {

    public enum FindingCode {
        DUPLICATE_USER_DATA,
        UNKNOWN_COMPLETION,
        BUFFER_RETURNED_BEFORE_TERMINAL_CQE,
        OVERLAPPING_STREAM_SEND,
        SQ_PRESSURE,
        CQ_OVERFLOW,
        SQPOLL_NEED_WAKEUP_MISSED
    }

    /** Immutable diagnostic finding; IDs are caller-defined stable handles. */
    public record Finding(long observedNanos, FindingCode code, long userData,
                          long resourceId, String detail) {
    }

    private record LiveOperation(int opcode, long bufferId, long streamId, boolean streamSend) {
    }

    private final ConcurrentHashMap<Long, LiveOperation> live = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Long> buffers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Long> streamSends = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<Finding> findings = new ConcurrentLinkedQueue<>();
    private final int maxFindings;

    /** Retains at most {@code maxFindings}; older records are discarded first. */
    public Doctor(int maxFindings) {
        if (maxFindings <= 0) {
            throw new IllegalArgumentException("maxFindings must be positive: " + maxFindings);
        }
        this.maxFindings = maxFindings;
    }

    /** Registers a submission. Use {@code bufferId=0}/{@code streamId=0} when not applicable. */
    public void onSubmit(long userData, int opcode, long bufferId, long streamId, boolean streamSend) {
        LiveOperation operation = new LiveOperation(opcode, bufferId, streamId, streamSend);
        if (live.putIfAbsent(userData, operation) != null) {
            finding(FindingCode.DUPLICATE_USER_DATA, userData, 0, "user_data is already live");
            return;
        }
        if (bufferId != 0 && buffers.putIfAbsent(bufferId, userData) != null) {
            finding(FindingCode.BUFFER_RETURNED_BEFORE_TERMINAL_CQE, userData, bufferId,
                    "buffer was submitted while still owned by another operation");
        }
        if (streamSend && streamId != 0 && streamSends.putIfAbsent(streamId, userData) != null) {
            finding(FindingCode.OVERLAPPING_STREAM_SEND, userData, streamId,
                    "a second send was submitted before the previous stream send terminated");
        }
    }

    /** Routes one CQE. A MORE CQE deliberately keeps the operation live. */
    public void onCompletion(long userData, int result, int flags) {
        LiveOperation operation = live.get(userData);
        if (operation == null) {
            finding(FindingCode.UNKNOWN_COMPLETION, userData, 0, "no live operation matches CQE res=" + result);
            return;
        }
        if (!Cqe.hasMore(flags) && live.remove(userData, operation)) {
            if (operation.bufferId() != 0) {
                buffers.remove(operation.bufferId(), userData);
            }
            if (operation.streamSend() && operation.streamId() != 0) {
                streamSends.remove(operation.streamId(), userData);
            }
        }
    }

    /** Reports an attempted return/reuse of a managed buffer. */
    public void onBufferReturned(long bufferId, long userData) {
        Long owner = buffers.get(bufferId);
        if (owner != null) {
            finding(FindingCode.BUFFER_RETURNED_BEFORE_TERMINAL_CQE, userData, bufferId,
                    "buffer remains owned by live user_data=0x" + Long.toHexString(owner));
        }
    }

    /** Records a submission-queue pressure observation without changing ownership state. */
    public void onSqPressure(int pending, int entries) {
        if (pending >= entries) {
            finding(FindingCode.SQ_PRESSURE, 0, 0, "pending=" + pending + " entries=" + entries);
        }
    }

    /** Records a kernel-visible CQ overflow counter transition. */
    public void onCqOverflow(long overflowCount) {
        if (overflowCount > 0) {
            finding(FindingCode.CQ_OVERFLOW, 0, overflowCount, "cq overflow=" + overflowCount);
        }
    }

    /** Records an SQPOLL work publication that should have issued SQ_WAKEUP but did not. */
    public void onSqpollNeedWakeupMiss() {
        finding(FindingCode.SQPOLL_NEED_WAKEUP_MISSED, 0, 0, "SQ_NEED_WAKEUP was observed without a wakeup enter");
    }

    public boolean healthy() {
        return findings.isEmpty();
    }

    /** Snapshot and clear are separate so callers may export before reset. */
    public List<Finding> findings() {
        return List.copyOf(new ArrayList<>(findings));
    }

    public void clearFindings() {
        findings.clear();
    }

    private void finding(FindingCode code, long userData, long resourceId, String detail) {
        findings.add(new Finding(System.nanoTime(), code, userData, resourceId, detail));
        while (findings.size() > maxFindings) {
            findings.poll();
        }
    }
}
