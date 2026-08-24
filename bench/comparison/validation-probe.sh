#!/bin/sh
# What does this server actually CHECK on write?
#
# Asked by posting, not by reading a config flag. A flag that says validation
# is off while something still validates puts a different amount of work in
# one server's write path than the others', and the throughput difference then
# gets attributed to architecture. Every probe is a resource that is wrong in
# exactly one way; the answer is whether the server noticed.
set -eu
BASE=$1
NAME=$2

probe() {
    what=$1; body=$2
    code=$(curl -s -o /tmp/probe.out -w '%{http_code}' -X POST "$BASE/Patient" \
        -H 'Content-Type: application/fhir+json' -d "$body" || echo 000)
    case "$code" in
        2*) verdict="ACCEPTED  (not checked)" ;;
        4*) verdict="REFUSED $code (checked)" ;;
        *)  verdict="$code" ;;
    esac
    printf '  %-26s %s\n' "$what" "$verdict"
}

echo "== $NAME =="
printf '  %-26s %s\n' "fhirVersion" \
    "$(curl -s "$BASE/metadata" | grep -o '"fhirVersion":"[^"]*"' | head -1 | cut -d'"' -f4)"

probe "valid resource"        '{"resourceType":"Patient","gender":"male","birthDate":"1980-07-31"}'
probe "invalid code value"    '{"resourceType":"Patient","gender":"wizard"}'
probe "malformed date"        '{"resourceType":"Patient","birthDate":"not-a-date"}'
probe "unknown element"       '{"resourceType":"Patient","quidditchPosition":"seeker"}'
probe "wrong JSON type"       '{"resourceType":"Patient","active":"yes"}'
probe "dangling reference"    '{"resourceType":"Patient","managingOrganization":{"reference":"Organization/does-not-exist"}}'
