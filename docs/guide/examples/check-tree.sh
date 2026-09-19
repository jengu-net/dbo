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

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
COMPOSE="$(docs/guide/examples/tree-world.sh "$WORK/compose.yaml")"

echo "=== the guide, against it"
DBO_GUIDE_COMPOSE="$COMPOSE" bash docs/guide/examples/check.sh
