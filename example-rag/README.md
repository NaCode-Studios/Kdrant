# Kdrant RAG example

A minimal, runnable Retrieval-Augmented-Generation service built on **Kdrant** — the retrieval half of a
RAG pipeline: ingest text, embed it, store it in Qdrant, and retrieve the most similar chunks for a question.

It is intentionally dependency-free and offline: embeddings come from a tiny deterministic char-trigram hash
([`embed`](src/main/kotlin/dev/kdrant/example/rag/Server.kt)). Swap that for a real embedding model — e.g.
OpenAI, or an in-process model wired through [`kdrant-langchain4j`](../kdrant-langchain4j) — for real quality,
then feed the retrieved `contexts` into your LLM prompt to generate the final answer.

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
# -> {"ingested":3}

# Ask a question — returns the most similar stored chunks
curl -s localhost:8080/ask -H 'content-type: application/json' -d '{
  "question": "What is Kdrant?",
  "topK": 2
}'
# -> {"question":"What is Kdrant?","contexts":[{"text":"Kdrant is ...","score":0.9},...]}
```
