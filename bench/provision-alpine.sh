#!/bin/sh
# Prepare a Raspberry Pi 5, booted diskless into Alpine from a USB stick, to
# run a dbo benchmark. Plain Postgres, plain JVM, no container runtime — the
# edge does not have one, and a container layer is one more thing between the
# measurement and the truth.
#
# Run once per boot. Diskless Alpine forgets everything, which is the point:
# every run starts from the same machine.
set -eu

PROFILE="${1:-ram}"          # ram | nvme
PGVER=16
BENCH_DIR=/tmp/dbo-bench

case "$PROFILE" in
    ram|nvme) ;;
    *) echo "usage: $0 [ram|nvme]" >&2; exit 2 ;;
esac

echo "==> packages"
apk update >/dev/null
apk add --no-cache \
    "postgresql${PGVER}" "postgresql${PGVER}-contrib" \
    openjdk21-jre-headless \
    curl jq >/dev/null

# ---------------------------------------------------------------- PGDATA
# The profile IS the storage decision, and it is the difference between a
# number worth comparing and a number worth quoting.
if [ "$PROFILE" = "ram" ]; then
    PGDATA=/tmp/pgdata
    echo "==> PGDATA on tmpfs ($PGDATA)"
    echo "    NOTE: fsync is free here. Write throughput from this profile is"
    echo "    a CPU ceiling, not a storage figure. Do not quote it as one."
    # Guard the ceiling that kills a run rather than slowing it: when tmpfs
    # fills, Postgres stops. Refuse up front instead of dying at minute nine.
    AVAIL_MB=$(df -m /tmp | awk 'NR==2 {print $4}')
    if [ "$AVAIL_MB" -lt 4096 ]; then
        echo "    refusing: ${AVAIL_MB}MB free on /tmp, want >= 4096MB for 10 tenants" >&2
        echo "    (either free RAM, use fewer tenants, or run the nvme profile)" >&2
        exit 1
    fi
else
    PGDATA=/mnt/nvme/pgdata
    echo "==> PGDATA on NVMe ($PGDATA)"
    if ! mountpoint -q /mnt/nvme; then
        echo "    refusing: /mnt/nvme is not mounted" >&2
        exit 1
    fi
fi

mkdir -p "$PGDATA" "$BENCH_DIR"
chown postgres:postgres "$PGDATA"

echo "==> initdb"
su postgres -c "initdb -D $PGDATA -E UTF8 --no-locale" >/dev/null

# Ten tenants means ten databases and ten pools. The default 100 connections
# is not a tuning choice here, it is a wall the run would hit.
cat >> "$PGDATA/postgresql.conf" <<CONF
listen_addresses = '127.0.0.1'
max_connections = 400
shared_buffers = 512MB
CONF

su postgres -c "pg_ctl -D $PGDATA -l $BENCH_DIR/postgres.log start"
until su postgres -c "pg_isready -q"; do sleep 1; done
echo "==> postgres up"

# ------------------------------------------------------- the machine record
# Written now, while the machine is quiet. A result without this is
# unattributable: a Pi 4 and a Pi 5 differ by two to three times.
cat > "$BENCH_DIR/machine.json" <<JSON
{
  "model": "$(tr -d '\0' < /proc/device-tree/model 2>/dev/null || echo unknown)",
  "memoryKb": $(awk '/MemTotal/ {print $2}' /proc/meminfo),
  "kernel": "$(uname -r)",
  "profile": "$PROFILE",
  "pgdata": "$PGDATA",
  "postgres": "$(su postgres -c 'postgres --version' | awk '{print $3}')",
  "jdk": "$(java -version 2>&1 | head -1 | sed 's/"/\\"/g')"
}
JSON

echo "==> ready. machine record:"
cat "$BENCH_DIR/machine.json"
echo
echo "Next: bench/run.sh --profile $PROFILE --tenants 10 --duration 120"
