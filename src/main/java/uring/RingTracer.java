package uring;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * Bounded, lock-free diagnostic trace of ring submissions and completions.
 *
 * <p>A normal ring has no tracer attached, so its hot path pays only the
 * caller's null/disabled branch. When enabled, this tracer stores immutable
 * records in a fixed-size atomic ring: it never blocks a submitter or CQ
 * reaper, and intentionally counts records lost to wrap-around rather than
 * turning an overloaded diagnosis path into a workload stall.
 *
 * <p>The submission side retains the sequence returned by {@link #submit}; a
 * completion is correlated through that sequence, not through a second map
 * keyed by user data. This matters for multi-CQE operations, whose one user
 * data maps to several trace events.
 */
public final class RingTracer {

    /** Sentinel used by a disabled tracer; {@link #complete} ignores it. */
    public static final long NO_SEQUENCE = -1L;

    /** Immutable copy suitable for JSON/text export after a run. */
    public record Event(long sequence, long submittedNanos, long completedNanos,
                        long userData, int opcode, int result, int flags) {
        /** True after a CQE has been recorded for this event. */
        public boolean completed() {
            return completedNanos != 0;
        }

        /** CQE-to-submission delay, or {@code -1} while still pending. */
        public long latencyNanos() {
            return completed() ? completedNanos - submittedNanos : -1;
        }
    }

    private final AtomicReferenceArray<Event> slots;
    private final int mask;
    private final AtomicLong nextSequence = new AtomicLong();
    private final AtomicLong lostEvents = new AtomicLong();
    private volatile boolean enabled;

    /** Creates an enabled tracer with a power-of-two event capacity. */
    public RingTracer(int capacity) {
        if (capacity < 2 || Integer.bitCount(capacity) != 1) {
            throw new IllegalArgumentException("trace capacity must be a power of two >= 2: " + capacity);
        }
        slots = new AtomicReferenceArray<>(capacity);
        mask = capacity - 1;
        enabled = true;
    }

    /** Stops recording immediately; existing snapshots remain available. */
    public void disable() {
        enabled = false;
    }

    /** Enables recording after a temporary diagnostic pause. */
    public void enable() {
        enabled = true;
    }

    public boolean enabled() {
        return enabled;
    }

    /**
     * Records a just-published SQE and returns its bounded trace sequence.
     * A caller should store that sequence beside its in-flight operation.
     */
    public long submit(long userData, int opcode) {
        if (!enabled) {
            return NO_SEQUENCE;
        }
        long sequence = nextSequence.getAndIncrement();
        int slot = (int) sequence & mask;
        Event previous = slots.getAndSet(slot,
                new Event(sequence, System.nanoTime(), 0, userData, opcode, 0, 0));
        if (previous != null && !previous.completed()) {
            lostEvents.incrementAndGet();
        }
        return sequence;
    }

    /**
     * Adds one CQE to the supplied submission sequence. A multishot parent
     * may call this more than once; each CQE gets the next ring sequence so
     * no intermediate event is overwritten just because user_data repeats.
     */
    public long complete(long sequence, long userData, int opcode, int result, int flags) {
        if (!enabled || sequence == NO_SEQUENCE) {
            return NO_SEQUENCE;
        }
        int slot = (int) sequence & mask;
        Event submitted = slots.get(slot);
        if (submitted == null || submitted.sequence() != sequence) {
            lostEvents.incrementAndGet();
            return NO_SEQUENCE;
        }
        Event completion = new Event(sequence, submitted.submittedNanos(), System.nanoTime(),
                userData, opcode, result, flags);
        if (slots.compareAndSet(slot, submitted, completion)) {
            return sequence;
        }
        lostEvents.incrementAndGet();
        return NO_SEQUENCE;
    }

    /**
     * Starts a distinct event for a later CQE of the same multi-CQE SQE.
     * The supplied opcode is retained because completion routing already has
     * it in the in-flight record.
     */
    public long completeAndContinue(long sequence, long userData, int opcode, int result, int flags) {
        complete(sequence, userData, opcode, result, flags);
        return Cqe.hasMore(flags) ? submit(userData, opcode) : NO_SEQUENCE;
    }

    /** Number of records dropped because a bounded slot wrapped or raced. */
    public long lostEvents() {
        return lostEvents.get();
    }

    /** Returns a consistent-enough, sequence-ordered diagnostic snapshot. */
    public List<Event> snapshot() {
        List<Event> events = new ArrayList<>(slots.length());
        for (int index = 0; index < slots.length(); index++) {
            Event event = slots.get(index);
            if (event != null) {
                events.add(event);
            }
        }
        events.sort(Comparator.comparingLong(Event::sequence));
        return List.copyOf(events);
    }
}
