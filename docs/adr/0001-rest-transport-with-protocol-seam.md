# ADR 0001 — REST/Ktor transport behind a protocol-independent seam

- Status: Accepted
- Date: 2026-07-10

## Context

Qdrant exposes two wire protocols with equivalent coverage for the core operations
(create/delete collection, upsert, query, scroll, delete): a REST API (HTTP/JSON, port 6333) and
a gRPC API (protobuf, port 6334).

An official Java gRPC client exists, but it is not idiomatic for Kotlin coroutines: its methods
return `ListenableFuture`, requests are built with protobuf builders, and it brings a large
gRPC/Netty dependency tree onto the classpath.

Kdrant aims to be an idiomatic, coroutine-first Kotlin client with a small footprint.

## Decision

Implement the default transport as a REST engine built on the Ktor client and
kotlinx-serialization, rather than wrapping the official gRPC client. Isolate the wire protocol
behind an internal `QdrantTransport` interface so that the public models and DSL never depend on
it.

## Consequences

- The footprint stays small and 100% Kotlin/coroutine-native — no gRPC, Netty, or protobuf on the
  classpath.
- The library owns its DTOs and must track REST API changes. `kotlinx-serialization` is configured
  to ignore unknown fields, so additive server changes do not break existing callers.
- REST caps a single request body at 32 MiB, so `upsert` splits large batches automatically.
- A gRPC transport can be added later behind the same `QdrantTransport` seam without changing the
  public API.
