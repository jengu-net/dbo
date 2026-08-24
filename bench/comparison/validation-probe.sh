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

# The ones that separate these three. Type and code errors are caught by
# anything that parses; cardinality, invariants and terminology are caught
# only by a server that consults the StructureDefinition on the write path --
# which is real work, and shows up as throughput.
probe_on() {
    what=$1; type=$2; body=$3
    code=$(curl -s -o /tmp/probe.out -w '%{http_code}' -X POST "$BASE/$type" \
        -H 'Content-Type: application/fhir+json' -d "$body" || echo 000)
    case "$code" in
        2*) verdict="ACCEPTED  (not checked)" ;;
        4*) verdict="REFUSED $code (checked)" ;;
        *)  verdict="$code" ;;
    esac
    printf '  %-26s %s\n' "$what" "$verdict"
}
# Device.udiCarrier.issuer is min=1 in R5. Present but incomplete: only a
# cardinality check on the profile finds this.
probe_on "missing required element" Device \
    '{"resourceType":"Device","udiCarrier":[{"deviceIdentifier":"08717648200274"}]}'
# Observation: an invariant, not a cardinality -- obs-6, dataAbsentReason
# must not be present when value is.
probe_on "violated invariant" Observation \
    '{"resourceType":"Observation","status":"final","code":{"text":"x"},"valueString":"y","dataAbsentReason":{"text":"z"}}'
# A code outside a REQUIRED binding's value set.
probe_on "code outside binding" Observation \
    '{"resourceType":"Observation","status":"not-a-status","code":{"text":"x"}}'
