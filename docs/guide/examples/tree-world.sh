#!/usr/bin/env bash
#
# The sample world, on a server built from THIS tree.
#
# Builds the image, writes a compose file naming it, and prints that file's
# path. Two runners want the same thing and neither should build it twice:
# check.sh reads it through DBO_GUIDE_COMPOSE, and so does the JUnit suite.
#
#   compose=$(docs/guide/examples/tree-world.sh)
#
# Generated rather than committed: a second compose file beside the published
# one is a second thing to keep in step, and the only difference is the image.
set -euo pipefail
cd "$(git -C "$(dirname "$0")" rev-parse --show-toplevel)"

TAG="${DBO_TREE_TAG:-dbo-server:tree}"
OUT="${1:?a path to write the compose file to}"

# Everything this prints goes to stderr, because the ONE thing on stdout is
# the path — a caller substitutes this command and a stray echo becomes part
# of the filename.
echo "=== building the server from this tree" >&2
./gradlew :core:dbo-server:installDist -q >&2
docker build -q -f core/dbo-server/Dockerfile -t "$TAG" core/dbo-server >/dev/null 2>&1

python3 - "$TAG" "$OUT" <<'PY' >&2
import pathlib, re, sys
tag, out = sys.argv[1], sys.argv[2]
text = pathlib.Path("docs/guide/examples/compose.yaml").read_text()
text = re.sub(r'image: ghcr\.io/jengu-net/dbo-server:\S+', f'image: {tag}', text)
# The published file names its mounts relative to itself, and this one lives
# somewhere else entirely.
text = text.replace('../../../sample/world', str(pathlib.Path.cwd() / "sample/world"))
pathlib.Path(out).write_text(text)
PY
echo "$OUT"
