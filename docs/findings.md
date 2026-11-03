# Notes from the read-path study

These notes describe measurements from a separate experimental harness, not the tracer microbenchmark included here. The original research archive and raw runs are not included in this snapshot. The numbers are useful for understanding the work, but this snapshot alone is not sufficient to independently audit or reproduce them.

## A buffer choice changed the result

The study compared file-reading approaches on a dataset already resident in memory. In an early setup, many reads shared both a file channel and the memory session behind their buffers. After changing to one channel per thread, the measured mapping-to-blocking-read throughput ratio fell from about 7.9 to 4.28. A later paired test changed the buffers from a shared session to separate sessions per request slot. Blocking-read throughput increased by a geometric mean of 1.711 times for platform threads and 1.707 for virtual threads, across eight analysed pairs per comparison. A later resident comparison measured a mapping-to-platform-thread ratio of 2.508. These numbers come from different configurations and sessions, and the first is an initial measurement rather than a paired estimate.

A shared session tracks the lifetime of memory used by multiple threads. Contention in that tracking is a plausible explanation for the paired result. The experiment also changed allocation and memory layout, however, and did not profile the cause. It shows that the benchmark configuration mattered, not that a particular JDK mechanism caused the full difference.

## A cache comparison under a memory limit

A separate synthetic lookup workload read from 128 GiB of data with a Zipf(0.99) access distribution, meaning some blocks were requested much more often than others. On one laptop with a 7 GiB container memory limit, a 5 GiB application cache with direct-I/O misses was compared with corresponding buffered reads. Three registered comparisons showed throughput ratios of 1.191, 1.240 and 1.271. A fourth showed 1.155 among six valid runs, fewer than the eight required in advance. The registered four-comparison outcome was therefore **not supported**; the fourth result remains unresolved, not a confirmed win.

A descriptive comparison with a 12 GiB limit and the same 5 GiB cache showed a ratio of 1.014 for one request shape. A buffered read-and-drop control and reclaim counters provided context, but they did not isolate the cause of the direct-cache difference. These are observations for one host, one cache implementation and one access distribution. They do not establish a universal recommendation for direct I/O.

The practical lesson is to state what a benchmark changes and what it leaves uncertain. In particular, keep invalid or unstable runs visible, distinguish a pre-set test from a later observation, and check whether the benchmark's own resource sharing affects the answer.
