# Kdrant benchmarks

JMH end-to-end latency benchmarks for `upsert` and `search`, run against a real Qdrant.

```bash
# Start Qdrant (or point QDRANT_HOST / QDRANT_PORT at an existing one)
docker run -p 6333:6333 qdrant/qdrant

# Run the benchmarks
./gradlew :benchmarks:jmh
```

`SampleTime` mode reports the p50 / p90 / p99 latency distribution — the numbers behind the
performance claims in the top-level [README](../README.md#footprint-vs-the-official-client).

## Kdrant against the official Java client

`OfficialClientComparisonBenchmark` runs Kdrant and `io.qdrant:client` against the same server, the
same collection and the same JVM, over the four operations that dominate real traffic: a single
search, a batch search, an upsert of a large batch, and a full scroll.

```bash
./gradlew :benchmarks:jmh -Pjmh.includes=OfficialClientComparison
```

It exists because every other number here compares Kdrant to Kdrant. Those answer questions somebody
has after choosing this client; the question asked before choosing it is whether suspending functions,
a typed filter DSL and a no-boxing hot path cost anything against the client a team could use from
Kotlin today. An unmeasured suspicion is stronger than a measured deficit, because the reader gets to
pick its size.

Two things about it that a reader should know before comparing rows. The official client speaks gRPC
and is measured over gRPC; Kdrant is measured over REST, which is its default. A gap between those two
rows is a protocol difference before it is a client difference. And both are driven from a blocking
benchmark thread, which measures end-to-end latency of one operation and measures neither client's
concurrency.

**The results are published from a workflow run, never from a laptop, and the write-up names the rows
Kdrant loses.** A benchmark whose author wins every row is read as a benchmark whose author chose the
rows. Dispatch the [`Benchmarks` workflow](../.github/workflows/benchmarks.yml) and record what it
reports here, with its run id, the way the latency table below does.

## Where a publishable number comes from

A latency measured on a laptop with a browser open is not a number worth quoting, so the harness also
runs from the [`Benchmarks` workflow](../.github/workflows/benchmarks.yml): dispatch it, pick the Qdrant
image, and it runs the same harness against that version on a clean runner and uploads the JMH output.
Numbers published anywhere in this repository must say which Qdrant version and which run they came
from, or they are unfalsifiable and should not be there.

## Measured latency

`SampleTime`, ~18 000 samples per benchmark, from
[run 30616391635](https://github.com/NaCode-Studios/Kdrant/actions/runs/30616391635).

| | p50 | p90 | p99 | max |
| --- | --- | --- | --- | --- |
| `search`, top 10 of 1 000 points | 1.97 ms | 4.66 ms | 5.40 ms | 16.8 ms |
| `upsert`, one point, `wait = true` | 3.37 ms | 3.99 ms | 9.81 ms | 28.5 ms |

**What these numbers are, exactly.** One 768-dimension dense vector per point, cosine distance, a
collection seeded with 1 000 points, Qdrant `v1.18.2` in a service container on the same host as the
client, JDK 17, one JMH fork with 3 warmup and 5 measurement iterations, on a shared GitHub-hosted
`ubuntu-latest` runner.

**What they are not.** There is no network between client and server here, so this is the client's own
cost plus a loopback round trip, not the latency of a real deployment — add your own network. A
1 000-point collection is small enough that HNSW is barely working, so the search figure is a floor,
not a capacity number. Nothing here measures throughput under concurrency, which is where gRPC and
HTTP/2 win and where the official client is the better tool. The run's `AverageTime` rows are omitted
on purpose: with 5 iterations their error bars are wider than their scores, so they say nothing.
