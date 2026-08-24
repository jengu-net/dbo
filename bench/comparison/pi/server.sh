#!/bin/sh
# Bring exactly ONE FHIR server up on the bench Pi, and prove the others are
# gone before it starts.
#
# Not "the others are idle" -- gone. An idle JVM still holds a heap and still
# owns page cache, and on an 8 GB Pi that is the difference between a server
# reading from memory and reading from NVMe. A comparison where the loser was
# competing for RAM with the winner is not a comparison.
#
#   server.sh start dbo|hapi|fhirest
#   server.sh stop
#   server.sh reset dbo|hapi|fhirest     # drop and recreate its database
set -eu

BENCH=/data/bench
PIDFILE=$BENCH/server.pid
WHICHFILE=$BENCH/server.which
LOG=$BENCH/server.log
PGPASS=benchpass

port_of() {
    case "$1" in
        dbo)     echo 8090 ;;
        hapi)    echo 8091 ;;
        fhirest) echo 8181 ;;
        *) echo "unknown server: $1" >&2; exit 2 ;;
    esac
}

base_of() {
    case "$1" in
        dbo)     echo "http://127.0.0.1:8090/t/bench/fhir" ;;
        hapi)    echo "http://127.0.0.1:8091/fhir" ;;
        fhirest) echo "http://127.0.0.1:8181/fhir" ;;
    esac
}

stop_all() {
    # By pidfile first, then by pattern: a pidfile that lost its process is
    # exactly the case where a stale JVM survives and quietly eats the RAM
    # the next server was measured with.
    if [ -f "$PIDFILE" ]; then
        kill "$(cat $PIDFILE)" 2>/dev/null || true
        i=0
        while kill -0 "$(cat $PIDFILE)" 2>/dev/null && [ $i -lt 200 ]; do
            sleep 0.1; i=$((i+1))
        done
        kill -9 "$(cat $PIDFILE)" 2>/dev/null || true
        rm -f "$PIDFILE"
    fi
    pkill -f 'felix.jar' 2>/dev/null || true
    pkill -f 'hapi.war' 2>/dev/null || true
    pkill -f 'fhirest.jar' 2>/dev/null || true
    i=0
    while pgrep -f 'felix.jar|hapi.war|fhirest.jar' >/dev/null 2>&1 && [ $i -lt 100 ]; do
        sleep 0.1; i=$((i+1))
    done
    pkill -9 -f 'felix.jar|hapi.war|fhirest.jar' 2>/dev/null || true
    rm -f "$WHICHFILE"
    # Drop the page cache so the next server does not inherit the last one's
    # warm file pages. Without this the server measured second looks faster
    # for a reason that has nothing to do with the server.
    sync; echo 3 > /proc/sys/vm/drop_caches 2>/dev/null || true
}

reset_db() {
    case "$1" in
        dbo)
            for d in $(su postgres -c "psql -tAc \"SELECT datname FROM pg_database WHERE datname LIKE 'tenant_%'\""); do
                su postgres -c "psql -c \"DROP DATABASE IF EXISTS $d WITH (FORCE)\"" >/dev/null
            done
            ;;
        hapi)
            su postgres -c "psql -c \"DROP DATABASE IF EXISTS hapi WITH (FORCE)\"" >/dev/null
            su postgres -c "psql -c \"CREATE DATABASE hapi OWNER hapi\"" >/dev/null
            ;;
        fhirest)
            su postgres -c "psql -c \"DROP DATABASE IF EXISTS fhirestdb WITH (FORCE)\"" >/dev/null
            su postgres -c "psql -c \"CREATE DATABASE fhirestdb OWNER fhirest_admin\"" >/dev/null
            ;;
    esac
}

start_one() {
    WHICH=$1
    stop_all
    PORT=$(port_of "$WHICH")
    case "$WHICH" in
        dbo)
            cd /opt/dbo
            DBO_TENANT_DIR=/opt/dbo/tenants DBO_HTTP_PORT=$PORT \
            DBO_ADMIN_JDBC_URL=jdbc:postgresql://127.0.0.1:5432/postgres \
            DBO_ADMIN_USER=dbobench DBO_ADMIN_PASSWORD=$PGPASS \
            DBO_AUTH_DISABLED=true DBO_LOG_LEVEL=warn \
            nohup bin/dbo-server > "$LOG" 2>&1 &
            echo $! > "$PIDFILE"
            ;;
        hapi)
            cd $BENCH/servers
            nohup java -Xmx3g -jar hapi.war \
                --spring.config.location=file:$BENCH/servers/hapi-application.yaml \
                > "$LOG" 2>&1 &
            echo $! > "$PIDFILE"
            ;;
        fhirest)
            cd $BENCH/servers
            # DB_POOL_SIZE matters: fhirest ships a default of ONE connection,
            # which would make it look catastrophically bad at 50 threads for a
            # reason that is configuration rather than architecture.
            DB_URL=jdbc:postgresql://127.0.0.1:5432/fhirestdb \
            DB_APP_USER=fhirest_app DB_APP_PASSWORD=$PGPASS \
            DB_ADMIN_USER=fhirest_admin DB_ADMIN_PASSWORD=$PGPASS \
            DB_POOL_SIZE=10 \
            nohup java -Xmx3g -jar fhirest.jar > "$LOG" 2>&1 &
            echo $! > "$PIDFILE"
            ;;
    esac
    echo "$WHICH" > "$WHICHFILE"

    BASE=$(base_of "$WHICH")
    START=$(date +%s)
    until curl -sf -o /dev/null "$BASE/metadata"; do
        if ! kill -0 "$(cat $PIDFILE)" 2>/dev/null; then
            echo "$WHICH exited before answering; tail of $LOG:" >&2
            tail -25 "$LOG" >&2
            exit 1
        fi
        if [ $(( $(date +%s) - START )) -ge 300 ]; then
            echo "$WHICH did not answer in 300s; tail of $LOG:" >&2
            tail -25 "$LOG" >&2
            exit 1
        fi
        sleep 0.3
    done
    echo "$WHICH up in $(( $(date +%s) - START ))s at $BASE"
}

case "${1:-}" in
    start) start_one "$2" ;;
    stop)  stop_all; echo "all stopped" ;;
    reset) reset_db "$2"; echo "$2 database reset" ;;
    which) cat "$WHICHFILE" 2>/dev/null || echo none ;;
    *) echo "usage: server.sh start|stop|reset|which [dbo|hapi|fhirest]" >&2; exit 2 ;;
esac
