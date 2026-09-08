#!/usr/bin/env bash
#
# Runs the quickstart, exactly as the page tells a reader to run it.
#
# Why this exists: the previous instruction for getting a store running was a
# one-line `docker run` on the landing page, and it could not work — no such
# tag existed, the launcher requires a spec directory, and it refuses to serve
# without an authority or an explicit flag saying not to. Nobody noticed,
# because nothing ran it. A quickstart nothing runs is a quickstart that is
# wrong; this is the thing that runs it.
#
# What it proves is that THIS compose file and THESE commands work — not that
# the current build does. The image is pinned on purpose, so this is a check
# against the quickstart rotting rather than a test of the tree it sits in.
#
#   ./quickstart/check.sh
#
# Leaves nothing behind: the stack comes down on any exit, including a failure
# and including a Ctrl-C.
set -euo pipefail
cd "$(dirname "$0")"

BASE="http://localhost:8090/t/demo/fhir"
step() { printf '\n=== %s\n' "$1"; }
fail() { echo "quickstart: $1" >&2; exit 1; }

cleanup() { docker compose down --remove-orphans >/dev/null 2>&1 || true; }
trap cleanup EXIT

step "starting"
docker compose up -d --quiet-pull

step "waiting for the demo tenant to be served"
# Two tenants are provisioned from nothing on a cold start — a database each,
# a terminology baseline each — so this is tens of seconds, not seconds.
for _ in $(seq 1 90); do
    if curl -sf -o /dev/null "$BASE/metadata"; then break; fi
    sleep 2
done
curl -sf -o /dev/null "$BASE/metadata" || {
    docker compose logs dbo | tail -30 >&2
    fail "the demo tenant never came up"
}

step "writing a Patient"
created=$(curl -sf -X POST "$BASE/Patient" \
    -H 'Content-Type: application/fhir+json' \
    -d '{"resourceType":"Patient",
         "identifier":[{"system":"urn:dbo:demo:mrn","value":"12345"}],
         "name":[{"family":"Lovelace","given":["Ada"]}]}')
id=$(printf '%s' "$created" | python3 -c 'import sys,json;print(json.load(sys.stdin)["id"])')
[ -n "$id" ] || fail "the write returned no id"
echo "id=$id"

step "finding it by identifier"
# --data-urlencode, because a token search carries a | and curl will not
# escape it for you. The page says the same thing for the same reason.
found=$(curl -sf -G "$BASE/Patient" \
    --data-urlencode "identifier=urn:dbo:demo:mrn|12345" \
    | python3 -c 'import sys,json;b=json.load(sys.stdin);print(len(b.get("entry",[])))')
[ "$found" = "1" ] || fail "expected one match, got $found"

step "changing it, then reading what it was"
curl -sf -o /dev/null -X PUT "$BASE/Patient/$id" \
    -H 'Content-Type: application/fhir+json' \
    -d "{\"resourceType\":\"Patient\",\"id\":\"$id\",
         \"identifier\":[{\"system\":\"urn:dbo:demo:mrn\",\"value\":\"12345\"}],
         \"name\":[{\"family\":\"Lovelace\",\"given\":[\"A.\"]}]}"
versions=$(curl -sf "$BASE/Patient/$id/_history" \
    | python3 -c 'import sys,json;print(len(json.load(sys.stdin).get("entry",[])))')
[ "$versions" = "2" ] || fail "expected two versions in history, got $versions"

step "an unsupported search parameter is refused, not ignored"
code=$(curl -s -o /dev/null -w '%{http_code}' "$BASE/Patient?favourite-colour=blue")
[ "$code" = "400" ] || fail "expected 400 for an unknown parameter, got $code"

step "a tenant appears when its spec does"
sed 's/"demo"/"clinic"/; s/urn:dbo:demo:mrn/urn:dbo:clinic:mrn/' \
    tenants/demo.json > tenants/clinic.json
trap 'rm -f "$PWD/tenants/clinic.json"; cleanup' EXIT
for _ in $(seq 1 40); do
    if curl -sf -o /dev/null "http://localhost:8090/t/clinic/fhir/metadata"; then break; fi
    sleep 2
done
curl -sf -o /dev/null "http://localhost:8090/t/clinic/fhir/metadata" \
    || fail "the clinic tenant never came up"

step "and goes when its spec does"
rm -f tenants/clinic.json
for _ in $(seq 1 40); do
    [ "$(curl -s -o /dev/null -w '%{http_code}' "http://localhost:8090/t/clinic/fhir/metadata")" != "200" ] && break
    sleep 2
done
code=$(curl -s -o /dev/null -w '%{http_code}' "http://localhost:8090/t/clinic/fhir/metadata")
[ "$code" != "200" ] || fail "the clinic tenant was still served after its spec was removed"

printf '\nquickstart: works\n'
