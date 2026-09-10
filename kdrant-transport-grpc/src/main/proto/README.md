# Vendored Qdrant protobuf definitions

These are Qdrant's own `.proto` files, copied verbatim from `lib/api/src/grpc/proto` at the tag this
client is pinned to. The tag is `qdrantVersion` in `gradle.properties`, which is the only place in the
repository the version is written: `verifyQdrantPin` fails the build when anything else names a newer
Qdrant than the pin, and the REST engine's contract schema is refreshed from the same tag, so the two
transports cannot end up pinned to different servers.

Nothing here is edited, and that is now checked rather than asked for. A vendored file that has been
touched is a file nobody can diff against upstream, which is what `points.proto` became when it was
hand-edited to carry part of Qdrant 1.19 while this page still said v1.18.2. `verifyVendoredQdrant`
fetches the pinned tag and compares every vendored file byte for byte.

Moving to a newer Qdrant is one command. Raise `qdrantVersion`, then:

```bash
./gradlew refreshVendoredQdrant
./gradlew :kdrant-transport-grpc:build
```

Qdrant marks its superseded RPCs `option deprecated = true`, and protoc carries the annotation into the
generated stubs, so this module drops the `DEPRECATION` diagnostic rather than its
`allWarningsAsErrors` policy. The eight deprecated calls are the pre-Query-API search, recommend and
discover families, none of which this engine uses.

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
