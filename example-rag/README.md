# Kdrant RAG example

The retrieval half of a RAG pipeline, small enough to read in one sitting and runnable in two commands:
ingest text, store it in Qdrant, and retrieve the chunks worth putting in an LLM prompt.

It is a demonstration rather than a starter template. There is nothing to configure, and that is
deliberate: a runnable example that grows options stops being readable, which is the only thing it is
for. Both embedders are toys, offline and dependency-free, a hashed character trigram for the dense
vector and a term-frequency map for the sparse one. Swap them for real models and nothing else in
[`Server.kt`](src/main/kotlin/dev/kdrant/example/rag/Server.kt) changes.

## What it shows, and when each arrived

The example is here to be the place somebody checks whether the README's argument survives contact with
code, so it uses what the library actually offers rather than what it offered at `1.x`.

| | |
| --- | --- |
| `ingest`, with the resume token written to a file | `2.2.0` |
| Hybrid retrieval: a dense and a sparse ranking, fused by reciprocal rank | `0.2.0` |
| A sparse vector with `Modifier.IDF`, so the server weights the terms | `0.2.0` |
| Payload indexes created with parameters, including `phraseMatching` | `2.2.0` |
| A failure path that separates retryable from terminal | `2.2.0` |

Ingest goes through `ingest` rather than `upsert` because `upsert` makes the caller own the batching,
the concurrency and the resume, and every service that ingests anything real ends up writing all three
badly. The checkpoint lands in a file, which is what makes a run killed halfway resumable rather than
restartable: send the same documents again and it continues from where the server acknowledged.

Retrieval is hybrid because the two rankings fail differently. A dense vector finds a paraphrase and
misses a product code; a sparse one finds the code and misses the paraphrase. The sparse vector is
declared with `Modifier.IDF`, so the raw term counts go to the server and Qdrant applies the inverse
document frequency from what the collection currently holds. Computing it here would fix it at ingest
time and go stale with the next document.

The text index is created with `phraseMatching = true`. Qdrant matches a phrase only against an index
built for it, so without that parameter the filter is accepted and matches nothing, which is a failure
with no error attached to it.

Failures answer `503` when Qdrant says the condition clears and `502` when it does not, from
`KdrantException.retryable`. Catching everything and answering `500` would throw away the one thing a
caller acts on.

## Run

```bash
# 1. Start Qdrant
docker compose -f example-rag/docker-compose.yml up -d

# 2. Start the service (listens on :8080, talks to Qdrant on localhost:6333)
./gradlew :example-rag:run
```

Override the Qdrant location with the `QDRANT_HOST` / `QDRANT_PORT` environment variables.

## Use

```bash
# Ingest a few documents
curl -s localhost:8080/documents -H 'content-type: application/json' -d '{
  "documents": [
    "Kdrant is a coroutine-first Kotlin client for the Qdrant vector database.",
    "Qdrant stores vectors and runs nearest-neighbour search over them.",
    "Retrieval-augmented generation grounds an LLM answer in retrieved context."
  ]
}'
# -> {"ingested":3,"resumedFrom":0}

# Ask a question, and get back the chunks to put in a prompt
curl -s localhost:8080/ask -H 'content-type: application/json' -d '{
  "question": "What is Kdrant?",
  "topK": 2
}'
# -> {"question":"What is Kdrant?","contexts":[{"text":"Kdrant is ...","score":0.9},...]}

# The same question, restricted to one language: the keyword index answers this
curl -s localhost:8080/ask -H 'content-type: application/json' -d '{
  "question": "What is Kdrant?", "lang": "en"
}'
```

Send the same documents twice and the second call reports `"ingested":0` with a non-zero
`resumedFrom`: the checkpoint file says the server already acknowledged them. Delete
`rag-demo.checkpoint` from the system temp directory to start over.
