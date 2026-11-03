# PanUring

PanUring is an experimental Java library for Linux `io_uring`, built with the Foreign Function & Memory API (FFM). It explores how Java code can submit asynchronous I/O and keep native memory alive until the kernel has finished using it. It also includes a small benchmark for the library's tracing code and notes from a separate read-path study.

**Status:** a research and learning project, not a production-ready I/O library. The measurements described below are specific to one machine and workload. They are not a general ranking of Java I/O options.

## Why this exists

With `io_uring`, a program places requests in a shared submission queue and receives results through a completion queue. A read can still be in progress after the code that submitted it has returned. The buffer must therefore remain valid until the completion arrives. Cancellation and operations that produce multiple completions make that lifetime less obvious.

The library experiments with typed operation tokens, registered buffers and completion handling to make these relationships explicit. The core uses the JDK's FFM API and has no external Java dependencies. Some features depend on kernel support and may be disabled by the host or container.

## Try it

You need Linux with `io_uring` enabled and JDK 25. The following compiles the core and submits a no-op request to a real ring:

```sh
mkdir -p out
javac -d out $(find src/main/java -name '*.java')
java --enable-native-access=ALL-UNNAMED -cp out uring.NopDemo
```

Native access allows the program to call Linux functions through FFM. If the host blocks `io_uring`, the demo may fail even though compilation succeeds. The examples are executable checks, not an exhaustive test suite. More examples, including operation lifetime and cancellation, are in [`src/main/java/uring`](src/main/java/uring).

## A benchmark and what it measures

[`bench/java`](bench/java) contains a JMH microbenchmark of the optional in-process `RingTracer`: no tracer, a disabled tracer, and an enabled tracer. JMH is a Java benchmarking tool that handles warmup and repeated measurements. This benchmark isolates tracing operations; it does **not** measure end-to-end `io_uring` throughput or establish the cost of tracing in an application. Its README gives build and run commands and interpretation limits.

## What the larger study taught me

The most useful lesson was that a benchmark can measure its own setup as much as the interface being compared. In one resident-data experiment, changing how read buffers shared a memory session increased throughput by about 71% for both platform-thread and virtual-thread blocking reads. The experiment showed a difference between configurations; it did not establish which low-level mechanism caused it.

In another experiment, an application cache using direct I/O led its corresponding page-cache path in three comparisons on one memory-limited machine. A fourth comparison was unresolved under the study's pre-set validity rule, so the four-comparison hypothesis was not supported as a whole. Those observations do not show that direct I/O is generally faster. [The study notes](docs/findings.md) explain the setup and limits.

The experiments behind these observations used a separate, larger harness. The JMH tracer benchmark in this snapshot does not reproduce those results. The original raw records and research archive are not included in this curated snapshot, so the reported numbers cannot be independently recomputed from it alone.

## License

The code is provided under the [Apache License 2.0](LICENSE).
