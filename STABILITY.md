# Stability & versioning

This document is the written stability contract for Kdrant: what "stable" means, what changes are
allowed in which releases, and what a major bump costs you. It complements the [board](https://github.com/orgs/NaCode-Studios/projects/4) (where the
project is going) and the [CHANGELOG](CHANGELOG.md) (what has already shipped).

## Semantic Versioning

Kdrant follows [Semantic Versioning 2.0.0](https://semver.org). Given `MAJOR.MINOR.PATCH`:

- **MAJOR**: incompatible public-API changes.
- **MINOR**: backwards-compatible additions (new operations, new optional parameters, new overloads).
- **PATCH**: backwards-compatible bug fixes.

### What counts as "public API"

The public API is exactly what the [binary-compatibility-validator](https://github.com/Kotlin/binary-compatibility-validator)
tracks in the committed `*.api` files (`kdrant-core/api`, both transport engines, and the ecosystem
modules; the gRPC module's generated protobuf and stub classes are excluded, because their surface is
Qdrant's to change and not ours to promise). Every public/protected symbol is in those dumps; `./gradlew apiCheck` fails the build on any
untracked change, so API breakage is never silent. Anything not in the `*.api` files, meaning
`internal` declarations and symbols annotated `@InternalKdrantApi`, is not public API and may change at
any time.

The **wire behaviour** of an engine (the requests it sends and the responses it parses) is also part of
the contract: a change that alters what goes on the wire for an existing operation is treated as a
breaking change unless it is a bug fix bringing Kdrant in line with Qdrant's documented API. Contract
tests validate every request body the REST engine builds against Qdrant's own OpenAPI document, pinned
to the version the CI matrix runs against, and a shared behavioural suite runs both engines against a
real Qdrant, so a wire change is a failing build rather than a silent difference. From `2.1.0` that
suite also runs from a Linux and a macOS native binary, because an engine that has only ever been
exercised from a JVM has been shown to link rather than to work.

The two engines are held to the same behaviour, with one stated exception. Qdrant serves fourteen
operations over HTTP only: telemetry, Prometheus metrics, the two issues calls, `recoverSnapshot`, the
snapshot and storage-snapshot transfers, and the six shard-scope snapshot operations. On the gRPC engine each
throws an `UnsupportedOperationException` naming itself and naming REST. That list is part of this
contract: an operation leaving it is an additive change, and an operation joining it would be a
breaking one.

### What may still change in a minor

Two Kotlin details are additive to `apiCheck` but not binary-compatible for every caller, and it is
better to say so than to discover it later.

`QdrantClient` and `QdrantTransport` are interfaces you call, not interfaces you implement. Kdrant
adds operations to both as Qdrant grows, and a new member on a Kotlin interface breaks a class that
implemented it against an older jar. If you need a decorator, delegate to the interface (`by transport`)
so the compiler tells you what a new release added; if you need a stub in a test, generate it. A
third-party wire engine is a supported use of `QdrantTransport`, but it is a use that recompiles against
each minor.

Adding a field to a public `data class` changes its generated `copy` and `componentN`. New response
fields arrive as Qdrant returns more, and while the constructor keeps its defaults and source keeps
compiling, code that called `copy()` against an older jar needs recompiling. Kdrant does not add fields
gratuitously and each one is listed in the [CHANGELOG](CHANGELOG.md), but a minor upgrade is a
recompile, not a jar swap.

The same holds for a new optional parameter on a public constructor, and `KdrantConfig` is the one it
happens to: a defaulted parameter appended to it is source-additive and changes the constructor
signature Kotlin emits, so code that called the constructor positionally against an older jar needs
recompiling. The configuration DSL — `Kdrant(host, port) { ... }`, which is the documented way in — is
unaffected, and that is why the parameter goes on the end rather than beside the one it belongs with.

## The guarantee

Within a major version:

- **No breaking public-API change without a major bump.** Source and binary compatibility are maintained
  across a major version.
- **Deprecation policy.** An API is deprecated with `@Deprecated` (with a `ReplaceWith` where possible) for
  at least one minor release before removal, and removal happens only in a major release.
- **Coroutine contract.** Every operation stays a `suspend` function or a `Flow`; cancellation is cooperative
  and `CancellationException` is always propagated.
- **Wire compatibility.** Kdrant tracks Qdrant's stable API; new Qdrant features arrive as additive
  minor releases.

## Upgrading from `2.0`

`2.1.0` is a minor and every `2.0.0` call site compiles unchanged. Two things are worth knowing before
the jar is swapped rather than the build re-run.

`kdrant-transport-rest` is Kotlin Multiplatform now, so its JVM classes are published as
`kdrant-transport-rest-jvm` and the plain coordinate carries Gradle module metadata. This is the move
`kdrant-core` made at `2.0.0`, with the same consequence: a Gradle build reads the metadata and changes
only the version number, and a Maven build naming `kdrant-transport-rest` gets no classes and has to
name `kdrant-transport-rest-jvm`. The gRPC engine and the framework adapters are JVM-only and are
unaffected.

Three signatures changed shape. `Kdrant(...)` and `KdrantGrpc(...)` gained `decorateTransport`, and
`KdrantConfig` gained `bearerToken`. All three parameters are optional and every `2.0.0` call site
compiles unchanged, but a default parameter changes the signature Kotlin emits, so an application
compiled against `2.0.0` that swaps in the `2.1.0` jar without rebuilding will not find them. That is
the case [above](#what-may-still-change-in-a-minor), and it now reaches the entry point rather than a
data class, which is worth stating plainly: `2.1.0` is a rebuild, not a jar swap. Running
`git diff v2.0.0 v2.1.0 -- '*/api/*.api'` shows the seven removed lines and nothing else removed.

Two behaviours changed without any API changing. HTTP 403 and gRPC `PERMISSION_DENIED` now raise
`KdrantException.Forbidden` rather than `Unauthorized`; `Forbidden` is a subclass, so an existing
`catch` still catches it and a `when` over the hierarchy stays exhaustive, but a `when` that branched
on `Unauthorized` to mean "check the API key" now also sees a scoped token being refused. And a
credential is no longer rejected over plaintext HTTP when the host is a loopback address, which only
accepts configurations that were previously refused.

## Upgrading from `1.x`

`2.0.0` breaks two things, and source compatibility is not one of them: every `1.x` call site compiles
unchanged.

The first is where `kdrant-core`'s JVM classes are published. The module is Kotlin Multiplatform now, so its own
coordinate carries Gradle module metadata and the classes live in `kdrant-core-jvm`. Gradle reads that
metadata and resolves the variant, so a Gradle build changes nothing but the version number. Maven does
not read it, so a Maven build naming `kdrant-core` gets no classes and must move to `kdrant-core-jvm`.
Depending on `kdrant-transport-rest` or `kdrant-transport-grpc`, which is what the README recommends, is
unaffected either way.

The second is that `ScrollRequest` and `SearchRequest` gained a `shardKey` parameter. That is the case
[above](#what-may-still-change-in-a-minor) arriving for real: the constructor keeps its defaults and
source keeps compiling, but the generated `copy` and `componentN` changed, so code that called `copy()`
on either type against a `1.x` jar has to be recompiled. Nothing else in the `*.api` dump was removed.

The multiplatform migration itself changed no public API. The dump is identical either side of it,
which is worth stating because it is the part that sounds like it should have broken something. What
broke is the two lines above.

The rest of `2.0.0` is additive: the gRPC engine is a module you do not have yet, and cluster support,
formula and MMR reranking, shard-scope snapshots and the Koog module are new operations.

## Java interoperability

Kdrant is deliberately Kotlin-coroutine-first, which is the wedge (see the [README](README.md)). The
public API is `suspend` functions and `Flow`s, which are callable from Java but not idiomatic there.

There is no bundled `CompletableFuture` facade. Mirroring ~40 suspend operations into a
blocking or future-returning Java API is a large, duplicated surface to maintain, and it is not the audience
Kdrant optimises for. Java callers who need it should bridge with the standard tools:

- `kotlinx-coroutines-jdk8`'s `future { }` to turn a `suspend` call into a `CompletableFuture`, or
- `runBlocking { }` for a simple synchronous call.

A dedicated `kdrant-jdk` facade remains an **on-demand** option if there is real Java demand; it would be
additive and would not change the Kotlin API.
