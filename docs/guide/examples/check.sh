#!/usr/bin/env bash
#
# Runs every command the guide's first two chapters show, against the sample
# world, exactly as a reader would run them.
#
# The commands are not written twice. Each block between a `[start:name]` and
# an `[end:name]` marker is injected into the chapter by name, so a command
# that changes here changes on the page, and a region that is renamed or
# deleted fails the site build rather than publishing an empty box.
#
# What this proves is that THESE commands work against the pinned image — not
# that the current tree does. It is a check against the guide rotting.
#
#   ./docs/guide/examples/check.sh
#
# Leaves nothing behind: the stack comes down on any exit, including failure
# and including a Ctrl-C.
set -euo pipefail
# Every command below is run from the repository root, because that is where
# the guide tells a reader to run it from.
cd "$(git -C "$(dirname "$0")" rev-parse --show-toplevel)"

COMPOSE="docker compose -f docs/guide/examples/compose.yaml"
step() { printf '\n=== %s\n' "$1"; }
fail() { echo "guide: $1" >&2; exit 1; }
cleanup() { $COMPOSE down --remove-orphans >/dev/null 2>&1 || true; }
trap cleanup EXIT

step "starting the world"
$COMPOSE pull --quiet >/dev/null 2>&1 || true
# --8<-- [start:up]
docker compose -f docs/guide/examples/compose.yaml up -d
# --8<-- [end:up]

# --8<-- [start:bases]
HOGWARTS=http://localhost:8090/t/hogwarts/fhir
GRINGOTTS=http://localhost:8090/t/gringotts/fhir
# --8<-- [end:bases]

step "waiting for the world to be served"
# Six databases are provisioned from nothing, with a terminology baseline
# each. Minutes on a cold start, not seconds.
for _ in $(seq 1 240); do
    if curl -sf -o /dev/null "$HOGWARTS/metadata" \
        && curl -sf -o /dev/null "$GRINGOTTS/metadata"; then break; fi
    sleep 5
done
curl -sf -o /dev/null "$HOGWARTS/metadata" || {
    $COMPOSE logs dbo | tail -40 >&2
    fail "hogwarts never came up"
}
curl -sf -o /dev/null "$GRINGOTTS/metadata" || fail "gringotts never came up"

step "asking each tenant which version it speaks"
# --8<-- [start:versions]
curl -s "$HOGWARTS/metadata"  | python3 -c 'import sys,json;print(json.load(sys.stdin)["fhirVersion"])'
curl -s "$GRINGOTTS/metadata" | python3 -c 'import sys,json;print(json.load(sys.stdin)["fhirVersion"])'
# --8<-- [end:versions]
h=$(curl -s "$HOGWARTS/metadata" | python3 -c 'import sys,json;print(json.load(sys.stdin)["fhirVersion"])')
g=$(curl -s "$GRINGOTTS/metadata" | python3 -c 'import sys,json;print(json.load(sys.stdin)["fhirVersion"])')
[ "$h" = "5.0.0" ] || fail "hogwarts should speak 5.0.0, said $h"
[ "$g" = "4.0.1" ] || fail "gringotts should speak 4.0.1, said $g"

step "admitting a patient"
# --8<-- [start:create]
created=$(curl -sf -X POST "$HOGWARTS/Patient" \
    -H 'Content-Type: application/fhir+json' \
    -d '{"resourceType":"Patient",
         "identifier":[{"system":"urn:rl:nid","value":"RL-0001"}],
         "name":[{"family":"Potter","given":["Harry"]}]}')
id=$(printf '%s' "$created" | python3 -c 'import sys,json;print(json.load(sys.stdin)["id"])')
# --8<-- [end:create]
[ -n "$id" ] || fail "the write returned no id"
echo "id=$id"
case "$id" in
    ????????-????-????-????-????????????) ;;
    *) fail "expected a uuid, got $id" ;;
esac

step "a readable id is refused"
# --8<-- [start:readable-id]
curl -s -X PUT "$HOGWARTS/Patient/harry" \
    -H 'Content-Type: application/fhir+json' \
    -d '{"resourceType":"Patient","id":"harry"}'
# --8<-- [end:readable-id]
refused=$(curl -s -o /dev/null -w '%{http_code}' -X PUT "$HOGWARTS/Patient/harry" \
    -H 'Content-Type: application/fhir+json' -d '{"resourceType":"Patient","id":"harry"}')
[ "$refused" = "400" ] || fail "expected 400 for a readable id, got $refused"

step "finding him by the identifier the zone declares"
# --data-urlencode, because a token search carries a | and curl will not
# escape it for you.
# --8<-- [start:search]
curl -sf -G "$HOGWARTS/Patient" \
    --data-urlencode "identifier=urn:rl:nid|RL-0001"
# --8<-- [end:search]
found=$(curl -sf -G "$HOGWARTS/Patient" --data-urlencode "identifier=urn:rl:nid|RL-0001" \
    | python3 -c 'import sys,json;print(len(json.load(sys.stdin).get("entry",[])))')
[ "$found" = "1" ] || fail "expected one match, got $found"

step "changing him, and reading what he was"
# --8<-- [start:history]
curl -sf -o /dev/null -X PUT "$HOGWARTS/Patient/$id" \
    -H 'Content-Type: application/fhir+json' \
    -d "{\"resourceType\":\"Patient\",\"id\":\"$id\",
         \"identifier\":[{\"system\":\"urn:rl:nid\",\"value\":\"RL-0001\"}],
         \"name\":[{\"family\":\"Potter\",\"given\":[\"H.\"]}]}"

curl -sf "$HOGWARTS/Patient/$id/_history"
# --8<-- [end:history]
versions=$(curl -sf "$HOGWARTS/Patient/$id/_history" \
    | python3 -c 'import sys,json;print(len(json.load(sys.stdin).get("entry",[])))')
[ "$versions" = "2" ] || fail "expected two versions, got $versions"

step "the same person at the insurer, which is a version behind"
# --8<-- [start:insurer]
curl -sf -X POST "$GRINGOTTS/Patient" \
    -H 'Content-Type: application/fhir+json' \
    -d '{"resourceType":"Patient",
         "identifier":[{"system":"urn:rl:nid","value":"RL-0001"}],
         "name":[{"family":"Potter","given":["Harry"]}]}'
# --8<-- [end:insurer]
at_insurer=$(curl -sf -G "$GRINGOTTS/Patient" --data-urlencode "identifier=urn:rl:nid|RL-0001" \
    | python3 -c 'import sys,json;print(len(json.load(sys.stdin).get("entry",[])))')
[ "$at_insurer" = "1" ] || fail "expected the person at the insurer, got $at_insurer"

step "and the older version refuses what it does not have"
# --8<-- [start:version-refusal]
curl -s -X POST "$GRINGOTTS/Coverage" \
    -H 'Content-Type: application/fhir+json' \
    -d '{"resourceType":"Coverage","status":"active","kind":"insurance",
         "beneficiary":{"reference":"Patient/x"}}'
# --8<-- [end:version-refusal]
outcome=$(curl -s -X POST "$GRINGOTTS/Coverage" \
    -H 'Content-Type: application/fhir+json' \
    -d '{"resourceType":"Coverage","status":"active","kind":"insurance","beneficiary":{"reference":"Patient/x"}}')
printf '%s' "$outcome" | grep -q '4.0.1' \
    || fail "the refusal should name the version it validated against: $outcome"
printf '%s' "$outcome" | grep -q 'OperationOutcome' || fail "expected an OperationOutcome"

step "a type the tenant never declared is refused"
# The hospital declared Observation. The insurer did not — it holds Patient
# and Coverage — so the same request is answered differently by each.
# --8<-- [start:undeclared-type]
curl -s -o /dev/null -w '%{http_code}\n' -X POST "$GRINGOTTS/Observation" \
    -H 'Content-Type: application/fhir+json' \
    -d '{"resourceType":"Observation","status":"final",
         "code":{"text":"house points"}}'
# --8<-- [end:undeclared-type]
undeclared=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$GRINGOTTS/Observation" \
    -H 'Content-Type: application/fhir+json' \
    -d '{"resourceType":"Observation","status":"final","code":{"text":"house points"}}')
[ "$undeclared" = "404" ] || fail "expected 404 for an undeclared type, got $undeclared"

step "a tenant appears when its spec does"
# --8<-- [start:add-tenant]
sed 's/"hogwarts"/"stmungos"/' \
    docs/guide/world/tenants/hogwarts.json > docs/guide/world/tenants/stmungos.json
# --8<-- [end:add-tenant]
trap 'rm -f "$PWD/docs/guide/world/tenants/stmungos.json"; cleanup' EXIT
for _ in $(seq 1 60); do
    if curl -sf -o /dev/null "http://localhost:8090/t/stmungos/fhir/metadata"; then break; fi
    sleep 5
done
# --8<-- [start:new-tenant-serves]
curl -s -o /dev/null -w '%{http_code}\n' http://localhost:8090/t/stmungos/fhir/metadata
# --8<-- [end:new-tenant-serves]
curl -sf -o /dev/null "http://localhost:8090/t/stmungos/fhir/metadata" \
    || fail "stmungos never came up"

step "and stops when its spec goes"
# --8<-- [start:remove-tenant]
rm docs/guide/world/tenants/stmungos.json
# --8<-- [end:remove-tenant]
for _ in $(seq 1 40); do
    [ "$(curl -s -o /dev/null -w '%{http_code}' http://localhost:8090/t/stmungos/fhir/metadata)" != "200" ] && break
    sleep 2
done
gone=$(curl -s -o /dev/null -w '%{http_code}' http://localhost:8090/t/stmungos/fhir/metadata)
[ "$gone" != "200" ] || fail "stmungos was still served after its spec was removed"
trap cleanup EXIT

step "the hospital still has its own records"
still=$(curl -sf -G "$HOGWARTS/Patient" --data-urlencode "identifier=urn:rl:nid|RL-0001" \
    | python3 -c 'import sys,json;print(len(json.load(sys.stdin).get("entry",[])))')
[ "$still" = "1" ] || fail "the hospital lost a record when a neighbour went away"

step "an unsupported search parameter is refused, not ignored"
# --8<-- [start:strict-search]
curl -s -o /dev/null -w '%{http_code}\n' "$HOGWARTS/Patient?favourite-colour=blue"
# --8<-- [end:strict-search]
code=$(curl -s -o /dev/null -w '%{http_code}' "$HOGWARTS/Patient?favourite-colour=blue")
[ "$code" = "400" ] || fail "expected 400 for an unknown parameter, got $code"

printf '\nguide: chapters one to three work\n'
