# Vendored Qdrant OpenAPI schema

`qdrant-openapi.json` is Qdrant's own OpenAPI document, copied verbatim from the tag Kdrant's contract
tests are pinned to. It is the input to `QdrantContractTest`, which validates every request body the
REST engine builds against the schema Qdrant publishes for that endpoint.

Currently pinned to **v1.18.2**, the same version the integration matrix in
[`ci.yml`](../../../../.github/workflows/ci.yml) runs against.

To move to a newer Qdrant, refresh the file and run the contract tests:

```bash
curl -fsSL https://raw.githubusercontent.com/qdrant/qdrant/v<VERSION>/docs/redoc/master/openapi.json \
  -o kdrant-transport-rest/src/test/resources/qdrant-openapi.json
./gradlew :kdrant-transport-rest:test --tests '*QdrantContractTest*'
```

A failure means Qdrant changed a wire format Kdrant relies on. Fix the engine, then update the pinned
version here and the image list in `ci.yml` in the same change, so the two never disagree about which
Qdrant this client is known to speak to.
