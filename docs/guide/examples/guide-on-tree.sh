#!/usr/bin/env bash
#
# The guide's chapters as tests, against a server built from THIS tree.
#
# The suite's ordinary run is against the pinned image, deliberately: what it
# proves there is that the published commands work against the image a reader
# pulls. That is also why a test moved here from the harness cannot run there
# — it asserts what the code does now, not what the pin did.
#
#   ./docs/guide/examples/guide-on-tree.sh
set -euo pipefail
cd "$(git -C "$(dirname "$0")" rev-parse --show-toplevel)"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
COMPOSE="$(docs/guide/examples/tree-world.sh "$WORK/compose.yaml")"

echo "=== the guide suite, against it"
DBO_GUIDE_COMPOSE="$COMPOSE" ./gradlew :guide:test "$@"
