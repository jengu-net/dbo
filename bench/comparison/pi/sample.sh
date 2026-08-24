#!/bin/sh
# Sample the machine while a cell runs. TSV, one line every 2s.
#
# JVM and Postgres RSS are kept APART: "the server used 2 GB" says nothing
# when Postgres is half of it, and which half a server spends its memory in
# is one of the more interesting differences between these three.
set -eu
OUT=$1
echo "t	tempC	throttled	cpuBusyPct	memAvailMB	jvmRssMB	pgRssMB	diskReadKB	diskWriteKB" > "$OUT"

prev_total=0; prev_idle=0; prev_r=0; prev_w=0
while :; do
    set -- $(awk '/^cpu /{print $2+$3+$4+$5+$6+$7+$8, $5}' /proc/stat)
    total=$1; idle=$2
    busy=0
    if [ "$prev_total" -ne 0 ]; then
        dt=$((total - prev_total)); di=$((idle - prev_idle))
        [ "$dt" -gt 0 ] && busy=$(( (dt - di) * 100 / dt ))
    fi
    prev_total=$total; prev_idle=$idle

    temp=$(awk '{printf "%.1f", $1/1000}' /sys/class/thermal/thermal_zone0/temp)
    thr=$(vcgencmd get_throttled 2>/dev/null | cut -d= -f2 || echo NA)
    memav=$(awk '/MemAvailable/{print int($2/1024)}' /proc/meminfo)
    jvm=$(ps -o rss= -p "$(pgrep -f 'felix.jar|hapi.war|fhirest.jar' | head -1)" 2>/dev/null | awk '{print int($1/1024)}')
    [ -z "$jvm" ] && jvm=0
    pg=$(ps -o rss= -C postgres 2>/dev/null | awk '{s+=$1} END{print int(s/1024)}')
    [ -z "$pg" ] && pg=0
    set -- $(awk '$3=="nvme0n1"{print $6, $10}' /proc/diskstats)
    r=${1:-0}; w=${2:-0}
    dr=0; dw=0
    if [ "$prev_r" -ne 0 ]; then dr=$(( (r - prev_r) / 2 )); dw=$(( (w - prev_w) / 2 )); fi
    prev_r=$r; prev_w=$w

    printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
        "$(date +%s)" "$temp" "$thr" "$busy" "$memav" "$jvm" "$pg" "$dr" "$dw" >> "$OUT"
    sleep 2
done
