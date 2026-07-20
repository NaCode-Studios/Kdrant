# Kdrant benchmarks

JMH end-to-end latency benchmarks for `upsert` and `search`, run against a real Qdrant.

```bash
# Start Qdrant (or point QDRANT_HOST / QDRANT_PORT at an existing one)
docker run -p 6333:6333 qdrant/qdrant

# Run the benchmarks
./gradlew :benchmarks:jmh
```

`SampleTime` mode reports the p50 / p90 / p99 latency distribution — the numbers behind the
performance claims in the top-level [README](../README.md#footprint-vs-the-official-client). For an
apples-to-apples footprint/throughput comparison, run the same shapes against `io.qdrant:client` (gRPC)
and note where HTTP/2 streaming wins.
