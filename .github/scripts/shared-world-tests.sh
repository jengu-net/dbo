#!/usr/bin/env bash
#
# The tests that have been moved onto the shared world, and only those.
#
# The migration's question is whether a converted class still passes beside
# the others sharing its tenant — which is exactly what a single class run in
# isolation cannot answer, and what the whole suite answers far too slowly.
#
# The set is derived rather than listed: a class that takes a shared tenant
# says so in its own source. A hand-kept list would be one more thing to
# forget, and forgetting it here means a conversion that is never run again.
#
#   ./.github/scripts/shared-world-tests.sh            # run them
#   ./.github/scripts/shared-world-tests.sh --list     # just name them
set -euo pipefail
cd "$(git -C "$(dirname "$0")" rev-parse --show-toplevel)"

# Read into an array WITHOUT mapfile: this has to run on the bash macOS
# ships, which is 3.2 and has never had it.
classes=()
while IFS= read -r c; do
    classes+=("$c")
done < <(grep -rl 'SharedTenants\.' core/harness/src/test/java \
    | xargs -n1 basename | sed 's/\.java$//' | grep -v '^SharedTenants$' | sort)

if [ "${#classes[@]}" -eq 0 ]; then
    echo "no class takes a shared tenant yet" >&2
    exit 1
fi

if [ "${1:-}" = "--list" ]; then
    printf '%s\n' "${classes[@]}"
    exit 0
fi

args=()
for c in "${classes[@]}"; do args+=(--tests "*${c}*"); done
echo "=== ${#classes[@]} classes on the shared world" >&2
exec ./gradlew :core:harness:test "${args[@]}" "$@"
