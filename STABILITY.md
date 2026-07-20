# Stability & versioning

This document is the written stability contract for Kdrant — what "stable" means, what changes are allowed
in which releases, and the plan for cutting `1.0`. It complements the [ROADMAP](ROADMAP.md) (where the
project is going) and the [CHANGELOG](CHANGELOG.md) (what has already shipped).

## Semantic Versioning

Kdrant follows [Semantic Versioning 2.0.0](https://semver.org). Given `MAJOR.MINOR.PATCH`:

- **MAJOR** — incompatible public-API changes.
- **MINOR** — backwards-compatible additions (new operations, new optional parameters, new overloads).
- **PATCH** — backwards-compatible bug fixes.

### What counts as "public API"

The public API is exactly what the [binary-compatibility-validator](https://github.com/Kotlin/binary-compatibility-validator)
tracks in the committed `*.api` files (`kdrant-core/api`, `kdrant-transport-rest/api`, and the ecosystem
modules). Every public/protected symbol is in those dumps; `./gradlew apiCheck` fails the build on any
untracked change, so **API breakage is never silent**. Anything not in the `*.api` files — `internal`
declarations, or symbols annotated `@InternalKdrantApi` — is not public API and may change at any time.

The **wire behaviour** of the REST engine (the requests it sends and the responses it parses) is also part
of the contract: a change that alters the bytes on the wire for an existing operation is treated as a
breaking change unless it is a bug fix bringing Kdrant in line with Qdrant's documented API.

## Pre-`1.0` (the `0.x` line)

While Kdrant is `0.x`, the public API may change between **minor** versions. Breaking changes are:

- called out in the [CHANGELOG](CHANGELOG.md) under **Changed** / **Removed**, and
- always visible as a diff in the `*.api` files.

We still avoid gratuitous breakage and prefer additive evolution, but `0.x` minors are the window for
getting the surface right before it is frozen.

## Post-`1.0`

From `1.0.0` onward:

- **No breaking public-API change without a major bump.** Source and binary compatibility are maintained
  across a major version.
- **Deprecation policy.** An API is deprecated with `@Deprecated` (with a `ReplaceWith` where possible) for
  at least one minor release before removal, and removal happens only in a major release.
- **Coroutine contract.** Every operation stays a `suspend` function or a `Flow`; cancellation is cooperative
  and `CancellationException` is always propagated.
- **Wire compatibility.** Kdrant tracks Qdrant's stable REST API; new Qdrant features arrive as additive
  minor releases.

## The road to `1.0`

`1.0` is cut once the **REST core is feature-complete and stable** — it does **not** wait for the optional
gRPC engine (post-`1.0`, see M25). Concretely, the gates are:

1. **Feature completeness (met).** Collections, the modern `/points/query` engine (nearest, hybrid fusion,
   recommend/discover/context, batch, groups, sparse & multi-vectors), payload & vector management, payload
   indexes, collection config, **aliases**, and **snapshots** are all shipped — the operational surface an
   application needs is complete.
2. **Quality gates (met).** ktlint + detekt, a JDK and Qdrant-version CI matrix, dependency review,
   Dependabot, and property-based serialization tests are in place; the public API is tracked.
3. **This stability policy (this document).**
4. **Benchmarks (in progress).** A reproducible JMH harness for upsert/search latency plus a footprint table
   vs `io.qdrant:client`, honest about where gRPC/HTTP2 wins (see [`benchmarks/`](benchmarks/) and the
   footprint table in the [README](README.md#footprint-vs-the-official-client)).

**Target:** cut `1.0.0` after the current `0.x` feature line (M19–M24) settles through one release cycle and
the benchmark numbers are published. The next release off this line is `0.3.0`; `1.0.0` follows once the
above are green and the surface has had a minor to bake.

## Java interoperability

Kdrant is deliberately **Kotlin-coroutine-first** — that is the wedge (see the [README](README.md)). The
public API is `suspend` functions and `Flow`s, which are callable from Java but not idiomatic there.

**Decision for `1.0`: no bundled `CompletableFuture` facade.** Mirroring ~40 suspend operations into a
blocking or future-returning Java API is a large, duplicated surface to maintain, and it is not the audience
Kdrant optimises for. Java callers who need it should bridge with the standard tools:

- `kotlinx-coroutines-jdk8`'s `future { }` to turn a `suspend` call into a `CompletableFuture`, or
- `runBlocking { }` for a simple synchronous call.

A dedicated `kdrant-jdk` facade remains a **post-`1.0`, on-demand** option if there is real Java demand; it
would be additive and would not change the Kotlin API.
