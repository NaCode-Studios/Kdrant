# kdrant-mcp

An MCP server for Qdrant that is a binary rather than an interpreter.

The way a model reaches a tool is MCP, and the only MCP server for Qdrant is written in Python, so every
agent that searches a collection does it through an interpreter, a virtual environment and a dependency
tree. For a process an agent spawns and kills repeatedly, install footprint and cold start are not
incidental properties: they are most of what distinguishes one server from another.

This is one static binary with no runtime to install on the host, built from the same
`kdrant-transport-rest` the rest of this repository is, so it speaks to a Qdrant the way everything else
here does. Release sizes, measured rather than estimated: 7.9 MB on macOS arm64, 9.1 MB on Windows x64,
16.4 MB on Linux x64 and 15.3 MB on Linux arm64. The Linux binaries are larger because they link libcurl
and its TLS stack statically, which is what gives them TLS with nothing installed.

## Run it

```bash
kdrant-mcp --host localhost --port 6333
kdrant-mcp --allow-writes              # see below before doing this
```

`QDRANT_HOST`, `QDRANT_PORT` and `QDRANT_API_KEY` work too, and the environment is the right place for the
key: a credential on a command line is a credential in the process list.

As an MCP server entry, which is how an agent will actually start it:

```json
{
  "mcpServers": {
    "qdrant": {
      "command": "/usr/local/bin/kdrant-mcp",
      "args": ["--host", "localhost", "--port", "6333"],
      "env": { "QDRANT_API_KEY": "..." }
    }
  }
}
```

## What it exposes, and what it does not

Six tools are on by default, and all six only read:

| Tool | |
| --- | --- |
| `list_collections` | Names, point counts and status. |
| `describe_collection` | Vector configuration, shards, and which payload fields are indexed. |
| `search_points` | Nearest-neighbour search with a dense vector. |
| `scroll_points` | Points in id order, optionally filtered by an exact payload match. |
| `retrieve_points` | Points by id. |
| `count_points` | A count, optionally filtered. |

`upsert_points` and `delete_points` exist and are **off unless you pass `--allow-writes`**. Letting a model
write to somebody's index is a different risk class from letting it read one, and shipping writes on by
default would be that choice made for the operator rather than by them. A server started without the flag
does not register them at all, so they do not appear in `tools/list` and a model cannot call one by
guessing; the instructions it receives at startup say the server is read-only.

There is no tool that creates or drops a collection. A model that can create collections can fill a disk,
and the operations that change what a deployment *is* belong to `kdrant-cli`, where a person is typing.

## Two things worth knowing

**The server writes nothing to stdout except JSON-RPC.** That is the protocol's channel, and its own
diagnostics go to stderr, which is where the MCP convention puts them. This is not free: the MCP SDK logs
through kotlin-logging, whose default on every target here prints to stdout and announces itself with a
banner before the first frame, so a client would see a parse error on the handshake and disconnect. The
binary reconfigures that at startup.

**`describe_collection` before `search_points`.** A query vector of the wrong width is refused, and nothing
a model has seen tells it how wide a collection is until it asks. The instructions say so, and it is the one
piece of ordering worth spending words on.

## How it is tested

[`prove-mcp.sh`](../.github/scripts/prove-mcp.sh) speaks MCP to the binary over stdio against a real
Qdrant: it initializes, lists the tools, describes a collection, completes a search and checks the top hit,
confirms a write is refused on a read-only server and offered with `--allow-writes`, and asserts that every
line on stdout is JSON-RPC. It runs on every push and again at every release, for each platform the release
attaches a binary for.

The tool surface itself is tested without a transport, in `ToolsTest`, because the schemas, the write gating
and the argument messages are what a model meets and none of them need a process.

## Platforms

Linux x64 and arm64, macOS arm64, Windows x64. `macosX64` is missing because the Kotlin MCP SDK does not
publish for it, which is the one place this module reaches less far than `kdrant-cli`.
