#!/usr/bin/env bash
# Runs every kdrant subcommand against a Qdrant that is already up on 127.0.0.1:6333.
#
# It lives in a script rather than inside a workflow because two workflows run it. 2.2.0 took three
# release attempts and all three failed here, on defects any push could have caught: migrate could not
# create the collection it migrated into, and the step that proved the binary called docker on a macOS
# runner. Nothing below the release workflow had ever started the binary, so the release was where the
# tool ran for the first time. Now CI runs this on every push and the release runs it again.
#
# Usage: prove-cli.sh <kotlin-native-target>
set -euo pipefail

TARGET="${1:?usage: prove-cli.sh <target>, e.g. linuxX64}"
OUT="${RUNNER_TEMP:-/tmp}"

BINARY="kdrant-cli/build/bin/$TARGET/kdrantReleaseExecutable/kdrant.kexe"
[ -f "$BINARY" ] || BINARY="kdrant-cli/build/bin/$TARGET/kdrantReleaseExecutable/kdrant.exe"
[ -f "$BINARY" ] || { echo "::error::no kdrant binary for $TARGET"; exit 1; }

say() { echo; echo "== $* =="; }

say "health"
# It exits 1 on a node that is not ready, which is the point of it, so a failure here is a real one.
"$BINARY" health

say "collection lifecycle"
"$BINARY" collection create cli-source --size 4 --distance dot
"$BINARY" collection describe cli-source
"$BINARY" collections

say "seed"
curl -fsS -X PUT "http://127.0.0.1:6333/collections/cli-source/points?wait=true" \
  -H 'content-type: application/json' \
  -d '{"points":[{"id":1,"vector":[1,0,0,0]},{"id":2,"vector":[0,1,0,0]}]}' > /dev/null

say "migrate"
"$BINARY" migrate cli-source cli-target --checkpoint "$OUT/cli.checkpoint"
"$BINARY" scroll cli-target --limit 5

say "collection snapshot round trip"
SNAPSHOT="$("$BINARY" snapshot create cli-target | cut -f1)"
"$BINARY" snapshot list cli-target
"$BINARY" snapshot download cli-target "$SNAPSHOT" --out "$OUT/cli.snapshot"
[ -s "$OUT/cli.snapshot" ] || { echo "::error::the snapshot came back empty"; exit 1; }
"$BINARY" snapshot delete cli-target "$SNAPSHOT"

say "shard snapshot round trip"
SHARD_SNAPSHOT="$("$BINARY" snapshot create cli-target --shard 0 | cut -f1)"
"$BINARY" snapshot list cli-target --shard 0
"$BINARY" snapshot download cli-target "$SHARD_SNAPSHOT" --shard 0 --out "$OUT/cli-shard.snapshot"
[ -s "$OUT/cli-shard.snapshot" ] || { echo "::error::the shard snapshot came back empty"; exit 1; }
"$BINARY" snapshot delete cli-target "$SHARD_SNAPSHOT" --shard 0

say "storage snapshot round trip"
STORAGE_SNAPSHOT="$("$BINARY" storage-snapshot create | cut -f1)"
"$BINARY" storage-snapshot list
"$BINARY" storage-snapshot download "$STORAGE_SNAPSHOT" --out "$OUT/cli-storage.snapshot"
[ -s "$OUT/cli-storage.snapshot" ] || { echo "::error::the storage snapshot came back empty"; exit 1; }
"$BINARY" storage-snapshot delete "$STORAGE_SNAPSHOT"

# `snapshot restore` is deliberately not here. It takes a location the *server* resolves, so a file://
# URL pointing at what this script just downloaded names a path inside the runner rather than inside the
# container, and an http:// one would need somewhere to serve it from. Restoring is covered against a
# real server by the shared client contract, which runs in the same process as the node it talks to.

say "delete refuses without --yes"
if "$BINARY" collection delete cli-source 2>/dev/null; then
  echo "::error::collection delete dropped a collection without --yes"; exit 1
fi
"$BINARY" collection delete cli-source --yes
"$BINARY" collection delete cli-target --yes

say "every command ran"
