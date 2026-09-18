#!/usr/bin/env bash
#
# The guide's commands, against THIS tree.
#
# `check.sh` runs against the pinned image, deliberately: what it proves is
# that the published commands work against the image a reader pulls, which is
# what makes it a guard against the guide rotting. The consequence is that it
# never tests the code in front of you — a change that breaks a documented
# behaviour passes it, right up until the pin moves and it becomes somebody
# else's confusing failure.
#
# This builds the server from the working tree, puts the sample world on it,
# and runs exactly the same script. Same snippets, same assertions, different
# image.
#
#   ./docs/guide/examples/check-tree.sh
#
# It is also the faster feedback loop: a documented behaviour that broke is
# named here in minutes, rather than by the suite in thirty-eight.
set -euo pipefail
cd "$(git -C "$(dirname "$0")" rev-parse --show-toplevel)"

TAG="${DBO_TREE_TAG:-dbo-server:tree}"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

echo "=== building the server from this tree"
./gradlew :core:dbo-server:installDist -q
docker build -q -f core/dbo-server/Dockerfile -t "$TAG" core/dbo-server >/dev/null

# The world the guide describes, on the image just built. Generated rather than
# committed: a second compose file beside the published one is a second thing
# to keep in step, and the only difference is the image.
echo "=== the sample world, on it"
python3 - "$TAG" "$WORK/compose.yaml" <<'PY'
import pathlib, re, sys
tag, out = sys.argv[1], sys.argv[2]
source = pathlib.Path("docs/guide/examples/compose.yaml")
text = source.read_text()
text = re.sub(r'image: ghcr\.io/jengu-net/dbo-server:\S+', f'image: {tag}', text)
# The published file names its mounts relative to itself, and this one lives
# somewhere else entirely.
root = pathlib.Path.cwd()
text = text.replace('../world', str(root / "docs/guide/world"))
pathlib.Path(out).write_text(text)
PY

echo "=== the guide, against it"
DBO_GUIDE_COMPOSE="$WORK/compose.yaml" bash docs/guide/examples/check.sh
