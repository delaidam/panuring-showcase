package bench;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import uring.RingTracer;
import uring.Sqe;

import java.util.concurrent.TimeUnit;

/**
 * Microbenchmark for the bounded {@link RingTracer} hot path.
 *
 * <p>The benchmark deliberately exercises the same branch shape that
 * {@code Uring} uses: read an optional sink, submit an event, then route a
 * terminal CQE to the sink retained by the in-flight operation. It does not
 * open an io_uring instance, so the score isolates Java-side tracing work from
 * syscall, device, and scheduler variance. See {@code bench/java/README.md}
 * for the canonical run command and the limits of this measurement.
 *
 * <p>Each JMH thread owns its tracer. That models the normal single ring
 * submit/reap owner and intentionally avoids presenting cross-thread atomic
 * contention as an ordinary per-operation tracing cost. The fixed capacity is
 * deliberately bounded; immediate terminal completion means normal slot wrap
 * is expected and must not increment {@link RingTracer#lostEvents()}.
 */
@State(Scope.Thread)
@Threads(1)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 10, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 10, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(value = 5, jvmArgs = {"--enable-native-access=ALL-UNNAMED"})
public class RingTracerBench {

    /** A realistic finite diagnostic history, not an unbounded event store. */
    private static final int TRACE_CAPACITY = 1 << 12;

    /*
     * These are volatile because Uring.tracer is volatile. Keeping that load
     * in all cases prevents this benchmark from quietly measuring a different
     * configuration path than the runtime's install/remove API.
     */
    private volatile RingTracer absentTracer;
    private volatile RingTracer disabledTracer;
    private volatile RingTracer enabledTracer;
    private long nextUserData;

    @Setup(Level.Trial)
    public void setup() {
        RingTracer disabled = new RingTracer(TRACE_CAPACITY);
        disabled.disable();
        disabledTracer = disabled;
        enabledTracer = new RingTracer(TRACE_CAPACITY);
        absentTracer = null;
        nextUserData = 1;
    }

    /** Guard the intended submit-then-terminal-completion workload shape. */
    @TearDown(Level.Trial)
    public void verifyNoUnexpectedLoss() {
        long lost = enabledTracer.lostEvents();
        if (lost != 0) {
            throw new AssertionError("terminal trace benchmark lost " + lost + " event(s)");
        }
    }

    /**
     * Default production configuration: no tracer installed. This measures
     * only the volatile optional-sink branch; it creates no trace event.
     */
    @Benchmark
    public long absentTracerSubmitComplete() {
        RingTracer tracer = absentTracer;
        long userData = nextUserData++;
        long sequence = tracer == null
                ? RingTracer.NO_SEQUENCE
                : tracer.submit(userData, Sqe.OP_NOP);
        if (tracer != null) {
            tracer.completeAndContinue(sequence, userData, Sqe.OP_NOP, 0, 0);
        }
        // Keep user_data live in the JMH result even when sequence is the
        // disabled sentinel, rather than letting a null-path invocation turn
        // into a constant-return benchmark.
        return userData ^ sequence;
    }

    /**
     * Installed but paused diagnostic sink. This is not the default zero-work
     * configuration, but makes the cost of {@link RingTracer#disable()} clear.
     */
    @Benchmark
    public long disabledTracerSubmitComplete() {
        RingTracer tracer = disabledTracer;
        long userData = nextUserData++;
        long sequence = tracer.submit(userData, Sqe.OP_NOP);
        return userData ^ tracer.completeAndContinue(sequence, userData, Sqe.OP_NOP, 0, 0);
    }

    /**
     * One terminal NOP trace event: bounded slot publication plus completion
     * correlation, timestamps, and immutable event construction.
     */
    @Benchmark
    public long enabledTracerSubmitComplete() {
        RingTracer tracer = enabledTracer;
        long userData = nextUserData++;
        long sequence = tracer.submit(userData, Sqe.OP_NOP);
        return userData ^ tracer.completeAndContinue(sequence, userData, Sqe.OP_NOP, 0, 0);
    }
}
