#!/usr/bin/env bash
# Speaks MCP to the kdrant-mcp binary over stdio and makes it search a real Qdrant on 127.0.0.1:6333.
#
# This is M72's exit criterion, and it is a script rather than a unit test because the thing worth proving
# is the protocol: that a client can initialize, list the tools and call one, and that nothing but
# JSON-RPC ever reaches stdout. A server whose logging library prints a banner to stdout answers the
# handshake correctly and is unusable, which is how this nearly shipped.
#
# Usage: prove-mcp.sh <kotlin-native-target>
set -euo pipefail

TARGET="${1:?usage: prove-mcp.sh <target>, e.g. linuxX64}"
BINARY="kdrant-mcp/build/bin/$TARGET/kdrant-mcpReleaseExecutable/kdrant-mcp.kexe"
[ -f "$BINARY" ] || BINARY="kdrant-mcp/build/bin/$TARGET/kdrant-mcpReleaseExecutable/kdrant-mcp.exe"
[ -f "$BINARY" ] || { echo "::error::no kdrant-mcp binary for $TARGET"; exit 1; }
OUT="${RUNNER_TEMP:-/tmp}"

echo "== seed a collection to search =="
# Idempotent on purpose: a run that fails partway leaves the collection behind, and the next attempt
# should fail on what it is testing rather than on a 409 from the setup.
curl -fsS -X DELETE http://127.0.0.1:6333/collections/mcp-demo > /dev/null 2>&1 || true
curl -fsS -X PUT http://127.0.0.1:6333/collections/mcp-demo \
  -H 'content-type: application/json' \
  -d '{"vectors":{"size":4,"distance":"Dot"}}' > /dev/null
curl -fsS -X PUT "http://127.0.0.1:6333/collections/mcp-demo/points?wait=true" \
  -H 'content-type: application/json' \
  -d '{"points":[{"id":1,"vector":[1,0,0,0],"payload":{"lang":"en"}},
                 {"id":2,"vector":[0,1,0,0],"payload":{"lang":"it"}}]}' > /dev/null

# One session for the whole conversation: initialize, then the notification, then three calls. A fresh
# process per request would not be MCP, and the tool list is per session.
run_session() {
  {
    printf '%s\n' \
      '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"ci","version":"1"}}}' \
      '{"jsonrpc":"2.0","method":"notifications/initialized"}' \
      '{"jsonrpc":"2.0","id":2,"method":"tools/list"}' \
      '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"describe_collection","arguments":{"collection":"mcp-demo"}}}' \
      '{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"search_points","arguments":{"collection":"mcp-demo","vector":[0.9,0.1,0,0],"limit":1}}}' \
      '{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"upsert_points","arguments":{"collection":"mcp-demo","points":[]}}}'
    sleep 6
  } | "$BINARY" "$@" 2>"$OUT/mcp-stderr.txt"
}

echo "== a read-only session: initialize, list, describe, search, and a refused write =="
run_session > "$OUT/mcp-stdout.txt"

python3 - "$OUT/mcp-stdout.txt" <<'PY'
import json, sys

lines = [l for l in open(sys.argv[1]).read().splitlines() if l.strip()]
if not lines:
    sys.exit("::error::the server wrote nothing to stdout")

# Every line has to be JSON-RPC. A logging library that prints to stdout breaks the protocol, and the
# banner it prints arrives before the handshake, so a client sees a parse error and disconnects.
frames = {}
for line in lines:
    try:
        frame = json.loads(line)
    except json.JSONDecodeError:
        sys.exit(f"::error::stdout is the protocol's channel and this is not JSON-RPC: {line[:120]}")
    if "id" in frame:
        frames[frame["id"]] = frame

def need(request_id, what):
    frame = frames.get(request_id)
    if frame is None:
        sys.exit(f"::error::no answer to {what} (id {request_id})")
    if "error" in frame:
        sys.exit(f"::error::{what} failed: {frame['error']}")
    return frame["result"]

initialize = need(1, "initialize")
print("  protocol:", initialize["protocolVersion"], "| server:", initialize["serverInfo"]["name"],
      initialize["serverInfo"]["version"])

tools = [t["name"] for t in need(2, "tools/list")["tools"]]
print("  tools:", ", ".join(tools))
for expected in ("list_collections", "describe_collection", "search_points", "scroll_points",
                 "retrieve_points", "count_points"):
    if expected not in tools:
        sys.exit(f"::error::tools/list is missing {expected}: {tools}")
for forbidden in ("upsert_points", "delete_points"):
    if forbidden in tools:
        sys.exit(f"::error::a read-only server listed the write tool {forbidden}")

describe = need(3, "describe_collection")
if describe.get("isError"):
    sys.exit(f"::error::describe_collection failed: {describe['content']}")
print("  describe_collection:", json.dumps(describe.get("structuredContent", {}))[:120])

search = need(4, "search_points")
if search.get("isError"):
    sys.exit(f"::error::search_points failed: {search['content']}")
hits = json.loads(search["content"][0]["text"])
if not hits:
    sys.exit("::error::search_points returned no hits against a seeded collection")
if str(hits[0]["id"]) != "1":
    sys.exit(f"::error::the point aligned with the query did not rank first: {hits}")
print("  search_points: top hit", hits[0]["id"], "score", hits[0]["score"])

# A read-only server does not register the write tools, so the SDK rejects the name before any handler
# runs and the answer is a JSON-RPC error rather than an isError result. Either is a refusal; what must
# not happen is a success. What tells the model why is the instructions field, checked below.
refused = frames.get(5)
if refused is None:
    sys.exit("::error::no answer to the write attempt (id 5)")
if "error" not in refused and not refused.get("result", {}).get("isError"):
    sys.exit(f"::error::a read-only server accepted upsert_points: {refused}")
print("  upsert_points refused")

if "read-only" not in initialize["instructions"]:
    sys.exit(f"::error::the instructions do not tell the model the server is read-only: {initialize['instructions']}")
print("  instructions say the server is read-only")
PY

echo "== the diagnostics went to stderr, where MCP expects them =="
grep -q . "$OUT/mcp-stderr.txt" || { echo "::error::nothing on stderr, so the logging went somewhere else"; exit 1; }
head -2 "$OUT/mcp-stderr.txt"

echo "== with --allow-writes the write tools appear =="
run_session --allow-writes > "$OUT/mcp-writes.txt"
python3 - "$OUT/mcp-writes.txt" <<'PY'
import json, sys
frames = {}
for line in open(sys.argv[1]).read().splitlines():
    if not line.strip():
        continue
    frame = json.loads(line)
    if "id" in frame:
        frames[frame["id"]] = frame
tools = [t["name"] for t in frames[2]["result"]["tools"]]
for expected in ("upsert_points", "delete_points"):
    if expected not in tools:
        sys.exit(f"::error::--allow-writes did not offer {expected}: {tools}")
print("  write tools offered:", ", ".join(t for t in tools if t in ("upsert_points", "delete_points")))
PY

curl -fsS -X DELETE http://127.0.0.1:6333/collections/mcp-demo > /dev/null
echo "== an MCP client listed the tools and completed a search =="
