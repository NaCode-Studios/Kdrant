# Vendored Qdrant protobuf definitions

These are Qdrant's own `.proto` files, copied verbatim from
[`lib/api/src/grpc/proto`](https://github.com/qdrant/qdrant/tree/v1.18.2/lib/api/src/grpc/proto) at the
tag this engine is pinned to, currently **v1.18.2** — the same version the REST engine's contract tests
and the CI integration matrix use.

Nothing here is edited. A vendored file that has been touched is a file nobody can diff against
upstream, so the way to move to a newer Qdrant is to re-download, not to patch:

```bash
V=v1.18.2
for f in collections.proto collections_service.proto points.proto points_service.proto \
         snapshots_service.proto health_check.proto json_with_int.proto qdrant_common.proto; do
  curl -fsSL "https://raw.githubusercontent.com/qdrant/qdrant/$V/lib/api/src/grpc/proto/$f" \
    -o "kdrant-transport-grpc/src/main/proto/$f"
done
./gradlew :kdrant-transport-grpc:build
```

## What is deliberately not here

`qdrant.proto` is not vendored. Its only unique content is a `Qdrant.HealthCheck` RPC returning the
server's title, version and commit, which no `QdrantTransport` operation needs, and it imports six
internal services (`*_internal_service.proto`, `raft_service.proto`, `storage_read_service.proto`)
that carry the cluster's internal wire protocol. Vendoring it would drag that whole closure in to gain
one call the seam does not have.

The health probes go through `health_check.proto` instead, which is the standard
[gRPC health checking service](https://github.com/grpc/grpc/blob/master/doc/health-checking.md).

The four services that remain are the client-facing surface: `Collections`, `Points`, `Snapshots`
and `Health`.
