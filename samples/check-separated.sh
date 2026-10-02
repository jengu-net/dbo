#!/usr/bin/env bash
#
# The clinic's application and its worker, started the way a reader starts
# them by hand, and one step performed in each of the ways they can run.
#
# The stories run the two embedded, in one context, which is a testing economy
# rather than the only shape: the worker application also runs on its own,
# under `edge` (an HTTP lane into one tenant) or `substrate` (the deployment's
# own database). Nothing in the stories can show that, because nothing in one
# JVM crosses a process boundary — so this does, once per mode. And it starts
# the embedded mode from its distribution too, because that is the guide's
# quick start and the stories boot the application as a test does instead.
#
# For each mode: the server starts — under `separated` when the worker runs
# apart, which issues the worker's credentials and leaves its own copy of the
# steps still; the worker starts beside it when it runs apart; a step is asked
# of `hogwarts` over its door; and the run must come back completed. Which JVM
# performed it is checked too, or the step could have been performed in the
# wrong one.
#
# The guide's quick start quotes the marked regions below, so they are written
# to be read: each is a command a reader types, with the check's own variables
# standing for the port and the database.
#
#   samples/check-separated.sh                      # all three
#   samples/check-separated.sh edge substrate       # what CI runs
#   samples/check-separated.sh embedded             # one of them
set -euo pipefail
cd "$(git -C "$(dirname "$0")" rev-parse --show-toplevel)"

MODES=("$@")
[ ${#MODES[@]} -eq 0 ] && MODES=(embedded edge substrate)

# Kept after the run, so a failure can be read rather than reproduced.
WORK="$PWD/build/check-separated"
rm -rf "$WORK" && mkdir -p "$WORK"
DB="dbo-separated-check-$$"
SERVER_PID=""
WORKER_PID=""

stop() {
    # A process ended by a signal answers its wait with that signal, which is
    # not a failure of this check.
    if [ -n "$WORKER_PID" ]; then kill "$WORKER_PID" 2>/dev/null; wait "$WORKER_PID" 2>/dev/null || true; fi
    if [ -n "$SERVER_PID" ]; then kill "$SERVER_PID" 2>/dev/null; wait "$SERVER_PID" 2>/dev/null || true; fi
    WORKER_PID=""
    SERVER_PID=""
    return 0
}
cleanup() {
    stop
    docker rm -f "$DB" >/dev/null 2>&1 || true
}
trap cleanup EXIT

fail() {
    echo "FAILED: $*" >&2
    for log in "$WORK"/*.log; do
        echo "--- the last of $log" >&2
        tail -40 "$log" >&2
    done
    exit 1
}

# Waits for a command to succeed, saying what for. Liveness bounds, not
# budgets: a cold first start expands whole FHIR versions.
until_true() {
    local what="$1" seconds="$2"
    shift 2
    local giveUp=$((SECONDS + seconds))
    until "$@" >/dev/null; do
        [ $SECONDS -ge $giveUp ] && fail "$what, after ${seconds}s"
        sleep 2
    done
}

# One top-level field of a JSON answer, or nothing.
field() {
    python3 -c 'import json,sys
try: print(json.load(sys.stdin).get(sys.argv[1]) or "")
except Exception: print("")' "$1"
}

free_port() {
    python3 -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1", 0)); print(s.getsockname()[1])'
}

echo "=== building both applications"
# --8<-- [start:build]
./gradlew -q :samples:spring-boot-server-app:installDist :samples:spring-boot-worker-app:installDist
# --8<-- [end:build]
SERVER_BIN="$PWD/samples/spring-boot-server-app/build/install/spring-boot-server-app/bin/spring-boot-server-app"
WORKER_BIN="$PWD/samples/spring-boot-worker-app/build/install/spring-boot-worker-app/bin/spring-boot-worker-app"

echo "=== a database for the deployment"
# Above Postgres' default of a hundred because of what the pools MAY grow to,
# not what they hold. Each holds one connection while idle and opens more
# under load: eight tenants at eight each, a fleet step's substrate at four,
# the serving substrate at three per door plus two, and the worker's lane at
# five come to about a hundred between the two applications — the default
# exactly, with nothing to spare for the check's own psql. What they hold is
# well under it: sampled once a second, the peak was sixty-three, while the
# substrate profile's tenants came up.
# --8<-- [start:database]
docker run -d --name "$DB" -p 127.0.0.1::5432 -e POSTGRES_PASSWORD=sample \
    postgres:17-alpine postgres -c max_connections=400
# --8<-- [end:database]
PG_PORT="$(docker port "$DB" 5432/tcp | head -1 | sed 's/.*://')"
JDBC="jdbc:postgresql://127.0.0.1:$PG_PORT"
until_true "postgres never answered" 120 docker exec "$DB" pg_isready -U postgres
# The substrate is the deployment's own database, beside the tenants'. Asked
# until it is made, because the image restarts its server once after the first
# answer it gives.
substrate_database() {
    # --8<-- [start:substrate-database]
    docker exec "$DB" createdb -U postgres dbo_substrate
    # --8<-- [end:substrate-database]
}
until_true "the substrate database could not be made" 60 substrate_database

# The work credential the server had the tenant issue, signed in for at the
# tenant's own authority as any participant signs in. Issued once the tenant
# is serving, a moment after its door first answers; a server that has exited
# is not slow, it is gone.
TOKEN=""
signed_in() {
    kill -0 "$SERVER_PID" 2>/dev/null || fail "the clinic's application exited"
    local base="$1"
    # --8<-- [start:sign-in]
    TOKEN="$(curl -s -X POST "$base/oidc/token" \
        -H 'Content-Type: application/x-www-form-urlencoded' \
        --data 'grant_type=client_credentials&client_id=sample-admissions-worker&client_secret=a-sample-secret-for-the-clinics-worker' \
        | field access_token)"
    # --8<-- [end:sign-in]
    [ -n "$TOKEN" ]
}

run_one() {
    local mode="$1" port base asked run state body
    port="$(free_port)"
    base="http://127.0.0.1:$port/t/hogwarts"
    echo "=== $mode: the clinic's application on $port"

    local profile=()
    [ "$mode" = embedded ] || profile=(--spring.profiles.active=separated)
    local substrate=()
    if [ "$mode" = substrate ]; then
        # The worker makes its keys and keeps the private halves; the public
        # halves land where the server's separated profile reads them.
        # --8<-- [start:mint]
        (cd samples/spring-boot-worker-app && java -cp "build/install/spring-boot-worker-app/lib/*" \
            cloud.jengu.dbo.samples.worker.MintingAnEnrolment build/enrolment hogwarts)
        # --8<-- [end:mint]
        substrate=(--dbo.substrate.url="$JDBC/dbo_substrate"
            --dbo.substrate.user=postgres --dbo.substrate.password=sample)
    fi

    # The application reads the world from a path relative to its own
    # directory, so it is started there.
    # --8<-- [start:server]
    (cd samples/spring-boot-server-app && JAVA_OPTS="-Xmx3g" exec "$SERVER_BIN" \
        --server.port="$port" \
        --dbo.admin.jdbc-url="$JDBC/postgres" --dbo.admin.user=postgres \
        --dbo.admin.password=sample \
        --dbo.auth.kek=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA= \
        ${profile[@]+"${profile[@]}"} ${substrate[@]+"${substrate[@]}"}) \
        >"$WORK/server-$mode.log" 2>&1 &
    # --8<-- [end:server]
    SERVER_PID=$!
    until_true "hogwarts never issued the worker's credential" 900 signed_in "$base"

    if [ "$mode" != embedded ]; then
        echo "    its worker in a JVM of its own, on the $mode lane"
        # --8<-- [start:worker]
        (cd samples/spring-boot-worker-app && JAVA_OPTS="-Xmx512m" \
            DBO_TENANT_BASE="$base/" DBO_SUBSTRATE_URL="$JDBC/dbo_substrate" \
            DBO_SUBSTRATE_USER=postgres DBO_SUBSTRATE_PASSWORD=sample \
            exec "$WORKER_BIN" --spring.profiles.active="$mode") \
            >"$WORK/worker-$mode.log" 2>&1 &
        # --8<-- [end:worker]
        WORKER_PID=$!
    fi

    # --8<-- [start:ask]
    asked="$(curl -s -X POST "$base/step/hogwarts.admission.register" \
        -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
        --data "{\"inputs\":{\"patient\":{\"resourceType\":\"Patient\",
            \"identifier\":[{\"system\":\"urn:rl:nid\",\"value\":\"by-hand-$mode-$$-$RANDOM\"}],
            \"name\":[{\"family\":\"Apart\"}]}}}")"
    run="$(printf '%s' "$asked" | field run)"
    # --8<-- [end:ask]
    [ -n "$run" ] || fail "$mode: no run was started: $asked"

    local giveUp=$((SECONDS + 300))
    state=""
    while [ $SECONDS -lt $giveUp ]; do
        # --8<-- [start:answer]
        body="$(curl -s "$base/run/$run" -H "Authorization: Bearer $TOKEN")"
        state="$(printf '%s' "$body" | field status)"
        # --8<-- [end:answer]
        [ "$state" = completed ] || [ "$state" = failed ] && break
        sleep 2
    done
    [ "$state" = completed ] || fail "$mode: the run came to '$state': $body"

    if [ "$mode" = embedded ]; then
        # Performed here: the server started its own worker.
        grep -q "starting: component=dbo-worker" "$WORK/server-$mode.log" \
            || fail "embedded: the clinic's application never started its worker"
        echo "    the run completed, performed by the worker embedded in the application"
    else
        # Performed in the other JVM: the server's own worker never started.
        if grep -q "starting: component=dbo-worker" "$WORK/server-$mode.log"; then
            fail "$mode: the clinic's application started its own worker, so the step may "\
"have been performed there"
        fi
        kill -0 "$WORKER_PID" 2>/dev/null || fail "$mode: the worker is not running"
        echo "    the run completed, performed by the worker on the $mode lane"
    fi
    stop
}

for mode in "${MODES[@]}"; do
    run_one "$mode"
done
echo "=== the clinic's application, by hand: ${MODES[*]}"
