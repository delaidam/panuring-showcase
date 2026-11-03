# Java microbenchmarks

`bench.RingTracerBench` is the bounded Phase-4 tracer-overhead measurement.
It measures the Java instrumentation path only, with one JMH thread and one
tracer per thread:

- `absentTracerSubmitComplete` mirrors the normal `Uring` configuration with
  no tracer installed: the volatile optional-sink check and no event work.
- `disabledTracerSubmitComplete` measures an installed `RingTracer` after
  `disable()`, which is useful for diagnosing the cost of a dynamically paused
  diagnostic sink but is not the default no-tracer configuration.
- `enabledTracerSubmitComplete` records one terminal NOP event through
  `submit` and `completeAndContinue`, including both timestamps and bounded
  atomic-slot updates.

The tracer has a fixed capacity of 4,096 events. Each iteration completes its
event before the next one, so slot wrap is normal bounded-retention behavior,
not an event-loss test.

## Reproducible run

From the repository root:

```bash
cd bench/java
mvn -q -DskipTests package
mkdir -p ../../results
java --enable-native-access=ALL-UNNAMED -jar target/benchmarks.jar \
  'bench.RingTracerBench.*' -t 1 -rf json -rff ../../results/ring-tracer-jmh.json
```

The benchmark class supplies five forks and 10 one-second warmup and
measurement iterations. Record the JDK build, CPU governor/frequency policy,
affinity, background load, and the generated JSON with any reported result.
For a quick build-and-discovery smoke check only (not publishable data), use:

```bash
java --enable-native-access=ALL-UNNAMED -jar target/benchmarks.jar \
  'bench.RingTracerBench.*' -t 1 -f 1 -wi 1 -i 1
```

To inspect allocation pressure separately, repeat the canonical command with
`-prof gc`; do not compare a GC-profiler score directly with an unprofiled
score.

## Interpretation limits

These scores isolate the in-process tracer path. They do **not** measure
end-to-end `io_uring` throughput, syscall cost, CQ polling, cross-thread
contention, or eBPF overhead; therefore this benchmark alone cannot establish
the Phase-4 “less than 5% when enabled” target for a particular workload.
Use it as the component measurement, then compare an otherwise identical real
ring/P2 workload with and without `Uring.installTracer(...)`. No result values
are committed in this repository.
