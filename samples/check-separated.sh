#!/usr/bin/env bash
#
# The clinic's application and its worker as two JVMs, the way a reader
# starts them by hand, and one step performed across the gap.
#
# The stories run the two embedded, in one context, which is a testing economy
# rather than the only shape: the worker application also runs on its own,
# under `edge` (an HTTP lane into one tenant) or `substrate` (the deployment's
# own database). Nothing in the stories can show that, because nothing in one
# JVM crosses a process boundary — so this does, once per profile.
#
# For each profile: the server starts under `separated`, which issues the
# worker's credentials and leaves its own copy of the steps still; the worker
# starts beside it; a step is asked of `hogwarts` over its door; and the run
# must come back completed. The server never starting its own worker is
# checked too, or the step could have been performed in the wrong JVM.
#
#   samples/check-separated.sh                 # both profiles
#   samples/check-separated.sh substrate       # one of them
set -euo pipefail
cd "$(git -C "$(dirname "$0")" rev-parse --show-toplevel)"

PROFILES=("$@")
[ ${#PROFILES[@]} -eq 0 ] && PROFILES=(edge substrate)

SERVER_HOME=samples/spring-boot-server-app
WORKER_HOME=samples/spring-boot-worker-app
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

echo "=== building both applications"
./gradlew -q :samples:spring-boot-server-app:installDist :samples:spring-boot-worker-app:installDist
SERVER_BIN="$PWD/$SERVER_HOME/build/install/spring-boot-server-app/bin/spring-boot-server-app"
WORKER_BIN="$PWD/$WORKER_HOME/build/install/spring-boot-worker-app/bin/spring-boot-worker-app"

echo "=== a database for the deployment"
# Above Postgres' default of a hundred because of what the pools MAY grow to,
# not what they hold. Each holds one connection while idle and opens more
# under load: eight tenants at eight each, a fleet step's substrate at four,
# the serving substrate at three per door plus two, and the worker's lane at
# five come to about a hundred between the two applications — the default
# exactly, with nothing to spare for the check's own psql. What they hold is
# well under it: sampled once a second, the peak was sixty-three, while the
# substrate profile's tenants came up.
docker run -d --name "$DB" -p 127.0.0.1::5432 -e POSTGRES_PASSWORD=sample \
    postgres:17-alpine postgres -c max_connections=400 >/dev/null
PG_PORT="$(docker port "$DB" 5432/tcp | head -1 | sed 's/.*://')"
until_true "postgres never answered" 120 docker exec "$DB" pg_isready -U postgres
# The substrate is the deployment's own database, beside the tenants'.
until_true "the substrate database could not be made" 60 \
    docker exec "$DB" createdb -U postgres dbo_substrate
JDBC="jdbc:postgresql://127.0.0.1:$PG_PORT"

free_port() {
    python3 -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1", 0)); print(s.getsockname()[1])'
}

# The work credential the server had the tenant issue, signed in for at the
# tenant's own authority as any participant signs in. Issued once the tenant
# is serving, a moment after its door first answers; a server that has exited
# is not slow, it is gone.
TOKEN=""
signed_in() {
    kill -0 "$SERVER_PID" 2>/dev/null || fail "the clinic's application exited"
    TOKEN="$(curl -s -X POST "$1/oidc/token" \
        -H 'Content-Type: application/x-www-form-urlencoded' \
        --data 'grant_type=client_credentials&client_id=sample-admissions-worker&client_secret=a-sample-secret-for-the-clinics-worker' \
        | field access_token)"
    [ -n "$TOKEN" ]
}

# One top-level field of a JSON answer, or nothing.
field() {
    python3 -c 'import json,sys
try: print(json.load(sys.stdin).get(sys.argv[1]) or "")
except Exception: print("")' "$1"
}

run_one() {
    local profile="$1" port base token asked run state body
    port="$(free_port)"
    base="http://127.0.0.1:$port/t/hogwarts"
    echo "=== $profile: the clinic's application on $port, its worker in a JVM of its own"

    local substrate=()
    if [ "$profile" = substrate ]; then
        # The worker makes its keys and keeps the private halves; the public
        # halves land where the server's separated profile reads them.
        (cd "$WORKER_HOME" && java -cp "build/install/spring-boot-worker-app/lib/*" \
            cloud.jengu.dbo.samples.worker.MintingAnEnrolment build/enrolment hogwarts) \
            >/dev/null
        substrate=(--dbo.substrate.url="$JDBC/dbo_substrate"
            --dbo.substrate.user=postgres --dbo.substrate.password=sample)
    fi

    (cd "$SERVER_HOME" && JAVA_OPTS="-Xmx3g" exec "$SERVER_BIN" \
        --server.port="$port" \
        --dbo.admin.jdbc-url="$JDBC/postgres" --dbo.admin.user=postgres \
        --dbo.admin.password=sample \
        --dbo.auth.kek=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA= \
        --spring.profiles.active=separated ${substrate[@]+"${substrate[@]}"}) \
        >"$WORK/server-$profile.log" 2>&1 &
    SERVER_PID=$!
    until_true "hogwarts never issued the worker's credential" 900 signed_in "$base"
    token="$TOKEN"

    (cd "$WORKER_HOME" && JAVA_OPTS="-Xmx512m" \
        DBO_TENANT_BASE="$base/" DBO_SUBSTRATE_URL="$JDBC/dbo_substrate" \
        DBO_SUBSTRATE_USER=postgres DBO_SUBSTRATE_PASSWORD=sample \
        exec "$WORKER_BIN" --spring.profiles.active="$profile") \
        >"$WORK/worker-$profile.log" 2>&1 &
    WORKER_PID=$!

    asked="$(curl -s -X POST "$base/step/hogwarts.admission.register" \
        -H "Authorization: Bearer $token" -H 'Content-Type: application/json' \
        --data "{\"inputs\":{\"patient\":{\"resourceType\":\"Patient\",
            \"identifier\":[{\"system\":\"urn:rl:nid\",\"value\":\"separated-$profile-$$-$RANDOM\"}],
            \"name\":[{\"family\":\"Apart\"}]}}}")"
    run="$(printf '%s' "$asked" | field run)"
    [ -n "$run" ] || fail "$profile: no run was started: $asked"

    local giveUp=$((SECONDS + 300))
    state=""
    while [ $SECONDS -lt $giveUp ]; do
        body="$(curl -s "$base/run/$run" -H "Authorization: Bearer $token")"
        state="$(printf '%s' "$body" | field status)"
        [ "$state" = completed ] || [ "$state" = failed ] && break
        sleep 2
    done
    [ "$state" = completed ] || fail "$profile: the run came to '$state': $body"

    # Performed in the other JVM: the server's own worker never started.
    if grep -q "starting: component=dbo-worker" "$WORK/server-$profile.log"; then
        fail "$profile: the clinic's application started its own worker, so the step may "\
"have been performed there"
    fi
    kill -0 "$WORKER_PID" 2>/dev/null || fail "$profile: the worker is not running"
    echo "    the run completed, performed by the worker on the $profile lane"
    stop
}

for profile in "${PROFILES[@]}"; do
    run_one "$profile"
done
echo "=== both applications, separated: ${PROFILES[*]}"
