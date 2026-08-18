#!/bin/sh
# Run one benchmark on the Pi. Assumes provision-alpine.sh already ran this
# boot: Postgres up, PGDATA where the profile says, machine record written.
#
# Cold start is measured HERE rather than inside the runner, because the thing
# worth timing is a process that does not exist yet — a JVM cannot time its own
# birth.
set -eu

PROFILE=ram
TENANTS=10
DURATION=120
BENCH_DIR=/tmp/dbo-bench
DIST="${DIST:-./bench/runner/build/install/runner}"
SERVER_DIST="${SERVER_DIST:-./core/dbo-server/build/install/dbo-server}"

while [ $# -gt 0 ]; do
    case "$1" in
        --profile)  PROFILE=$2; shift 2 ;;
        --tenants)  TENANTS=$2; shift 2 ;;
        --duration) DURATION=$2; shift 2 ;;
        *) echo "usage: $0 [--profile ram|nvme] [--tenants N] [--duration SECONDS]" >&2; exit 2 ;;
    esac
done

mkdir -p "$BENCH_DIR"
DBO_VERSION="$(cat "$BENCH_DIR/version" 2>/dev/null || echo unknown)"

# ---------------------------------------------------------------- cold start
# Boot to first 200 on a current schema. The claim in the README is about a
# server; this is the number an edge deployment actually waits for.
COLD_START_MS=null
if [ -x "$SERVER_DIST/bin/dbo-server" ]; then
    echo "==> cold start"
    START=$(date +%s%N)
    "$SERVER_DIST/bin/dbo-server" > "$BENCH_DIR/coldstart.log" 2>&1 &
    SERVER_PID=$!
    # Poll rather than sleep: a fixed sleep measures the sleep.
    until curl -sf -o /dev/null http://127.0.0.1:8090/fhir/metadata 2>/dev/null; do
        if ! kill -0 "$SERVER_PID" 2>/dev/null; then
            echo "    server exited before answering; see $BENCH_DIR/coldstart.log" >&2
            break
        fi
        sleep 0.1
    done
    if kill -0 "$SERVER_PID" 2>/dev/null; then
        COLD_START_MS=$(( ( $(date +%s%N) - START ) / 1000000 ))
        echo "    ${COLD_START_MS}ms"
        kill "$SERVER_PID" 2>/dev/null || true
        wait "$SERVER_PID" 2>/dev/null || true
    fi
else
    echo "==> cold start skipped (no dbo-server dist at $SERVER_DIST)"
fi

# ------------------------------------------------------------------ the run
echo "==> bench: profile=$PROFILE tenants=$TENANTS duration=${DURATION}s"
"$DIST/bin/runner" \
    --profile "$PROFILE" \
    --tenants "$TENANTS" \
    --duration "$DURATION" \
    --dbo-version "$DBO_VERSION" \
    --out "$BENCH_DIR/result.json"

# Fold the cold start in: the runner cannot measure it, but it belongs in the
# same document as everything else about this run.
if [ "$COLD_START_MS" != "null" ] && command -v jq >/dev/null 2>&1; then
    jq --argjson ms "$COLD_START_MS" '.measurements.coldStartMs = $ms' \
        "$BENCH_DIR/result.json" > "$BENCH_DIR/result.tmp" \
        && mv "$BENCH_DIR/result.tmp" "$BENCH_DIR/result.json"
fi

echo
echo "==> $BENCH_DIR/result.json"
if command -v jq >/dev/null 2>&1; then
    jq '{valid, tenants, thermal, measurements}' "$BENCH_DIR/result.json"
fi
echo
echo "Pull it off with:  scp <pi>:$BENCH_DIR/result.json bench/results/"
