#!/bin/sh
# Sample the one thing that silently invalidates a Raspberry Pi benchmark.
#
# A Pi under sustained load throttles within minutes without real cooling, and
# it does not announce it: throughput drifts DOWNWARD inside a single run, so
# the first repetition beats the last and the median is a fiction. Sampled
# alongside every measurement; a run whose throttle flag was ever set is
# marked invalid rather than published.
#
# `vcgencmd get_throttled` returns a bitmask. Bits 0-3 are live conditions,
# bits 16-19 latch "this happened since boot" — the latched bits are the ones
# that matter here, because a run is retrospective.
set -eu

if ! command -v vcgencmd >/dev/null 2>&1; then
    echo '{"available":false,"reason":"vcgencmd not present — not a Pi, or userland missing"}'
    exit 0
fi

RAW=$(vcgencmd get_throttled | cut -d= -f2)
VALUE=$(printf '%d' "$RAW")
TEMP=$(vcgencmd measure_temp | cut -d= -f2 | tr -d "'C")
FREQ=$(vcgencmd measure_clock arm | cut -d= -f2)

bit() { echo $(( (VALUE >> $1) & 1 )); }

cat <<JSON
{
  "available": true,
  "raw": "$RAW",
  "socTempC": $TEMP,
  "armHz": $FREQ,
  "now": {
    "underVoltage": $(bit 0),
    "armFrequencyCapped": $(bit 1),
    "throttled": $(bit 2),
    "softTempLimit": $(bit 3)
  },
  "sinceBoot": {
    "underVoltage": $(bit 16),
    "armFrequencyCapped": $(bit 17),
    "throttled": $(bit 18),
    "softTempLimit": $(bit 19)
  },
  "valid": $( [ $(( (VALUE >> 16) & 0xF )) -eq 0 ] && echo true || echo false )
}
JSON
