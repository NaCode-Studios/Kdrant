# Publishing & repository setup

## Repository metadata (About + topics)

Set the description and topics once with the GitHub CLI (authenticated):

```bash
gh repo edit NaCode-Studios/Kdrant \
  --description "Coroutine-first Kotlin client for the Qdrant vector database — suspend APIs, type-safe DSLs, small footprint." \
  --homepage "https://github.com/NaCode-Studios/Kdrant" \
  --add-topic kotlin --add-topic qdrant --add-topic vector-database \
  --add-topic coroutines --add-topic kotlin-coroutines --add-topic ktor \
  --add-topic kotlinx-serialization --add-topic vector-search --add-topic embeddings \
  --add-topic rag --add-topic semantic-search --add-topic jvm --add-topic sdk
```

## Publishing to Maven Central

Releases are published by `.github/workflows/release.yml` when a `v*` tag is pushed. Configure these
repository secrets (Settings → Secrets and variables → Actions):

| Secret | Description |
| --- | --- |
| `MAVEN_CENTRAL_USERNAME` | Central Portal user-token name. |
| `MAVEN_CENTRAL_PASSWORD` | Central Portal user-token password. |
| `SIGNING_KEY` | ASCII-armored PGP private key (in-memory signing). |
| `SIGNING_KEY_PASSWORD` | Passphrase for the PGP key. |

The `io.github.nacode-studios` namespace must first be verified in the Central Portal via the GitHub
organization. To cut a release:

```bash
git tag v0.1.0
git push origin v0.1.0
```

To validate the artifacts locally without credentials: `./gradlew publishToMavenLocal`.
