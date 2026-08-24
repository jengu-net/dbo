#!/bin/sh
# Bring the SoC back to the same starting temperature every cell.
#
# Not "below a ceiling" -- a band. Below 60 lets one cell start at 51 and the
# next at 59, and that difference lands in the numbers. And it is a band the
# machine can actually reach: this Pi idles near 50 C, so the fan is driven to
# maximum by lowering the governor's trip points, which is the only lever that
# holds -- writing cur_state works for one poll and then step_wise takes it
# back, and user_space is not compiled into this kernel.
set -eu
TARGET=${1:-50}
TIMEOUT=${2:-600}
Z=/sys/class/thermal/thermal_zone0

# Floor the trips: the governor itself then wants maximum, so nothing has to
# fight it.
echo 20000 > $Z/trip_point_1_temp; echo 21000 > $Z/trip_point_2_temp
echo 22000 > $Z/trip_point_3_temp; echo 23000 > $Z/trip_point_4_temp

START=$(date +%s)
while :; do
    t=$(awk '{print int($1/1000)}' $Z/temp)
    [ "$t" -le "$TARGET" ] && { echo "cooled to ${t}C in $(( $(date +%s) - START ))s"; break; }
    if [ $(( $(date +%s) - START )) -ge "$TIMEOUT" ]; then
        # Refuse to pretend. A cell that starts hot is not comparable to one
        # that starts cold, and the honest move is to say so and let the
        # matrix mark it rather than proceed quietly.
        echo "TIMEOUT at ${t}C after ${TIMEOUT}s (target ${TARGET}C)" >&2
        exit 1
    fi
    sleep 5
done
# Hand the machine back to its own governor for the run itself.
echo 50000 > $Z/trip_point_1_temp; echo 60000 > $Z/trip_point_2_temp
echo 67500 > $Z/trip_point_3_temp; echo 75000 > $Z/trip_point_4_temp
