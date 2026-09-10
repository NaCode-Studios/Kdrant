# Vendored Qdrant OpenAPI schema

`qdrant-openapi.json` is Qdrant's own OpenAPI document, copied verbatim from the tag this client is
pinned to. It is the input to `QdrantContractTest`, which validates every request body the REST engine
builds against the schema Qdrant publishes for that endpoint.

The tag is `qdrantVersion` in `gradle.properties`. It is deliberately not restated here, and it cannot
be recovered from the document: Qdrant ships `"version": "master"` under `info` at every released tag,
v1.19.1 included, so the file carries no evidence of where it came from. That is how this copy came to
be a `master` snapshot taken before 1.19.0 shipped while the line above it claimed v1.18.2, and it left
the contract test validating against fields no released server has. `verifyVendoredQdrant` supplies the
evidence the document lacks, by fetching the pinned tag and comparing byte for byte.

To move to a newer Qdrant, raise `qdrantVersion` and run:

```bash
./gradlew refreshVendoredQdrant
./gradlew :kdrant-transport-rest:jvmTest --tests '*QdrantContractTest*'
```

A contract failure means Qdrant changed a wire format this client relies on. Fix the engine in the same
change as the refresh, so the pinned schema and the engine never disagree about what the server accepts.
