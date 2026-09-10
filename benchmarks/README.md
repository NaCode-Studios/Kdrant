# Kdrant benchmarks

JMH end-to-end latency benchmarks for `upsert` and `search`, run against a real Qdrant.

```bash
# Start Qdrant (or point QDRANT_HOST / QDRANT_PORT at an existing one)
docker run -p 6333:6333 qdrant/qdrant

# Run the benchmarks
./gradlew :benchmarks:jmh
```

`SampleTime` mode reports the p50 / p90 / p99 latency distribution — the numbers behind the
performance claims in the top-level [README](../README.md#compared-with-the-official-client).

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
reports here, with its run id, the way the tables below do.

### The results

`SampleTime` p50, from [run 34505075131](https://github.com/NaCode-Studios/Kdrant/actions/runs/34505075131),
against Qdrant `v1.19.1`. 2 000 seeded points of 384 dimensions, top 10, batches of 10 queries and 500
points, scroll pages of 256.

| | Kdrant, REST | Kdrant, gRPC | Official client, gRPC |
| --- | --- | --- | --- |
| Single search | 1.41 ms | 0.65 ms | 0.56 ms |
| Batch search, 10 queries | 3.38 ms | 1.43 ms | 1.32 ms |
| Upsert, 500 points | 74.6 ms | 10.4 ms | 8.0 ms |
| Full scroll, 2 000 points | 36.0 ms | 9.5 ms | 8.3 ms |

**Kdrant is slower on every row, and the reason is mostly not Kdrant.** Against the official client as
each is normally configured, this client's default REST engine is 2.5x slower on a single search and
9.3x slower on a large upsert. Those are the numbers somebody comparing the two libraries would get, and
they are published first because they are the ones that are true of the default choice.

**The middle column is what makes them readable.** Kdrant over its own gRPC engine closes almost all of
that: 0.65 ms against 0.56 ms on a search, 10.4 ms against 8.0 ms on the upsert. So the gap against the
official client is a gap against HTTP and JSON, not against a Kotlin client with suspending functions
and a typed DSL, and the coroutine machinery costs nothing measurable here.

**Over the same protocol Kdrant is still slower, by 8% to 30%, and the worst row has an explanation
worth checking rather than accepting.** Search is 16% behind, batch search 8%, scroll 15%, and the
500-point upsert 30%. Part of that last one is not serialization at all: Kdrant splits an upsert at 256
points by default, so it sent two requests where the official client sent one. That default exists to
bound the memory a large ingest holds, and it costs a round trip here. Raising `upsertBatchSize` would
narrow the row, and it is left at the default because the benchmark measures what a caller gets rather
than what a tuned caller could get.

**What none of this measures is concurrency.** Both clients are driven from one blocking JMH thread, so
every row is the latency of one operation at a time. HTTP/2 multiplexing is where gRPC's advantage grows
rather than shrinks, and the numbers above will understate it. If throughput under load is the question,
neither this table nor any single-threaded one answers it.

**What to take from it.** If the deployment is latency-sensitive on a hot path, use Kdrant's gRPC engine
and the choice between clients stops being about speed. If it is not, REST is the default for the
reasons in the [engine comparison](../README.md#choosing-an-engine), and these are the numbers that
choice costs.

## Multi-tenancy: what a tenant index is worth

`SampleTime`, from [run 34501181185](https://github.com/NaCode-Studios/Kdrant/actions/runs/34501181185),
against Qdrant `v1.19.1`.

Two collections, 50 tenants of 400 points each, 20 000 points of 768 dimensions, cosine. Both index the
tenant key; one passes `isTenant = true` so Qdrant colocates a tenant's points, the other indexes it as
an ordinary keyword, which is what a caller who did not know about the flag would have written. The same
filtered search runs over both. The third row searches the same collection with no filter at all.

| | p50 | p90 | p99 | mean |
| --- | --- | --- | --- | --- |
| One tenant, `isTenant = true` | 1.63 ms | 3.52 ms | 3.90 ms | 2.398 ms ± 0.022 |
| One tenant, plain keyword index | 1.75 ms | 4.22 ms | 4.89 ms | 2.656 ms ± 0.029 |
| No filter, whole collection | 2.16 ms | 5.06 ms | 5.60 ms | 2.945 ms ± 0.033 |

**The tenant index is faster, and by less than the architecture's reputation suggests.** Ten percent on
the mean, seven at the median, twenty at the 99th percentile. The error bars do not overlap, across
roughly 19 000 samples per row, so the difference is real rather than noise.

**The tail is where it shows, which is the expected shape.** Colocation does not make a comparison
cheaper; it reduces how much of the collection a filtered search has to walk through to find one
tenant's points. That changes the worst case more than the typical one, and p99 moving twice as far as
p50 is what that looks like.

**Twenty thousand points is the wrong size for this to pay, and publishing it anyway is the point.** A
collection that fits in one or two segments has almost nothing to colocate, so this is close to the
floor of what the flag can be worth. Read it as: the layout matters at a size this harness cannot reach,
not that it does not matter. Anyone choosing between one collection per tenant and one collection with a
tenant index should measure at their own size, and this table says what the small end looks like so the
comparison starts somewhere.

**Both filters beat the unfiltered search,** which is worth saying because it is the opposite of the
usual assumption that a filter costs something. Restricting to one tenant of 400 points is less work
than ranking 20 000, and Qdrant's filtered search uses the payload index rather than scanning and
discarding. A filter over a key with no index would be the other way round.

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
