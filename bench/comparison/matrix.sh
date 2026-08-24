#!/bin/bash
# The comparison matrix. Runs from the dev Mac; drives the Pi and the driver.
#
# One server exists on the Pi at a time -- not idle, gone. An idle JVM still
# holds a heap and still owns page cache, and on an 8 GB Pi that is the
# difference between reading from memory and reading from NVMe.
#
# Repetition-major rather than server-major: rep 1 for all three, then rep 2.
# If anything drifts across a session that lasts hours -- ambient temperature,
# NVMe fill -- server-major would deposit all of it on whichever server ran
# last, and the benchmark would have discovered the order it ran in.
set -u
PI=root@192.168.1.128
MINI=mini
JAVA=/opt/homebrew/opt/openjdk@21/bin/java
RESULTS=${RESULTS:-$HOME/bench-results}
REPS=${REPS:-1}
COOL_TO=${COOL_TO:-50}
mkdir -p "$RESULTS"

base_of() {
    case "$1" in
        dbo)     echo "http://192.168.1.128:8090/t/bench/fhir" ;;
        hapi)    echo "http://192.168.1.128:8091/fhir" ;;
        fhirest) echo "http://192.168.1.128:8181/fhir" ;;
    esac
}

cell() {
    local server=$1 mode=$2 threads=$3 rep=$4
    local tag="${server}-${mode}-t${threads}-r${rep}"
    local base; base=$(base_of "$server")
    echo "=== $tag"

    # Empty store, every cell. It is what makes bytes-on-disk-per-resource
    # mean anything, and it stops one server inheriting another's warm cache.
    ssh $PI "/data/bench/server.sh reset $server" >/dev/null 2>&1
    ssh $PI "/data/bench/server.sh start $server" >/dev/null 2>&1 || {
        echo "    $server would not start -- cell skipped"; return 1; }

    # The directory before the patients: Synthea references organisations
    # conditionally, so they have to be resolvable. Setup, not measurement.
    ssh $MINI "for f in ~/bench/providers/*.json; do curl -s -o /dev/null -X POST '$base' \
        -H 'Content-Type: application/fhir+json' --data-binary @\$f; done" >/dev/null 2>&1

    # Cool AFTER the teardown and the load, because both are work that heats
    # the Pi. Gating before them would let each cell start from whatever they
    # left behind -- which is the drift the gate exists to remove.
    local startTemp
    if ! ssh $PI "/data/bench/cool.sh $COOL_TO 900" >/dev/null 2>&1; then
        echo "    could not reach ${COOL_TO}C -- marking invalid, continuing"
        echo '{"invalid":"thermal gate timeout"}' > "$RESULTS/$tag.invalid.json"
    fi
    startTemp=$(ssh $PI "awk '{printf \"%.1f\", \$1/1000}' /sys/class/thermal/thermal_zone0/temp")

    ssh $PI "nohup /data/bench/sample.sh /data/bench/$tag.tsv >/dev/null 2>&1 & echo \$! > /data/bench/sample.pid" >/dev/null 2>&1
    local before; before=$(ssh $PI "df -k /data | tail -1 | awk '{print \$3}'")
    local dbBefore; dbBefore=$(ssh $PI "su postgres -c \"psql -tAc 'SELECT sum(pg_database_size(datname)) FROM pg_database'\"")

    ssh $MINI "$JAVA -cp ~/bench/driver Driver --base '$base' --mode $mode \
        --threads $threads --cohort ~/bench/cohort --out ~/bench/results/$tag.json \
        --label $tag" 2>&1 | tail -2

    ssh $PI "kill \$(cat /data/bench/sample.pid) 2>/dev/null; rm -f /data/bench/sample.pid" >/dev/null 2>&1
    local after; after=$(ssh $PI "df -k /data | tail -1 | awk '{print \$3}'")
    local dbAfter; dbAfter=$(ssh $PI "su postgres -c \"psql -tAc 'SELECT sum(pg_database_size(datname)) FROM pg_database'\"")
    local thr; thr=$(ssh $PI "vcgencmd get_throttled | cut -d= -f2")

    scp -q $MINI:~/bench/results/$tag.json "$RESULTS/$tag.json" 2>/dev/null
    scp -q $PI:/data/bench/$tag.tsv "$RESULTS/$tag.tsv" 2>/dev/null
    # The machine's side of the cell, joined to the driver's by the tag.
    cat > "$RESULTS/$tag.machine.json" <<JSON
{"tag":"$tag","startTempC":$startTemp,"throttledMask":"$thr",
 "diskUsedKbBefore":$before,"diskUsedKbAfter":$after,
 "dbBytesBefore":${dbBefore:-0},"dbBytesAfter":${dbAfter:-0}}
JSON
    echo "    startTemp=${startTemp}C throttled=$thr dbGrowth=$(( (${dbAfter:-0} - ${dbBefore:-0}) / 1048576 ))MB"
}

for rep in $(seq 1 "$REPS"); do
    for server in dbo hapi fhirest; do
        for mode in batch record; do
            for threads in 1 10 50; do
                cell "$server" "$mode" "$threads" "$rep"
            done
        done
    done
done
ssh $PI "/data/bench/server.sh stop" >/dev/null 2>&1
echo "=== matrix complete: $RESULTS"
