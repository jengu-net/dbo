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
ZONE=http://localhost:8090/t/rl/fhir
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

step "the same person twice is refused, not duplicated"
# --8<-- [start:duplicate-identity]
curl -s -o /dev/null -w '%{http_code}\n' -X POST "$HOGWARTS/Patient" \
    -H 'Content-Type: application/fhir+json' \
    -d '{"resourceType":"Patient",
         "identifier":[{"system":"urn:rl:nid","value":"RL-0001"}],
         "name":[{"family":"Potter","given":["Harry","James"]}]}'
# --8<-- [end:duplicate-identity]
dup=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$HOGWARTS/Patient" \
    -H 'Content-Type: application/fhir+json' \
    -d '{"resourceType":"Patient","identifier":[{"system":"urn:rl:nid","value":"RL-0001"}],
         "name":[{"family":"Potter","given":["Harry","James"]}]}')
[ "$dup" = "409" ] || fail "expected 409 for a second create of one identity, got $dup"
count=$(curl -sf -G "$HOGWARTS/Patient" --data-urlencode "identifier=urn:rl:nid|RL-0001" \
    | python3 -c 'import sys,json;print(len(json.load(sys.stdin).get("entry",[])))')
[ "$count" = "1" ] || fail "expected one record for one identity, got $count"

step "updating by identity rather than by id"
# --8<-- [start:conditional-update]
curl -s -o /dev/null -w '%{http_code}\n' -X PUT \
    "$HOGWARTS/Patient?identifier=urn:rl:nid%7CRL-0001" \
    -H 'Content-Type: application/fhir+json' \
    -d '{"resourceType":"Patient",
         "identifier":[{"system":"urn:rl:nid","value":"RL-0001"}],
         "name":[{"family":"Potter","given":["Harry","James"]}]}'
# --8<-- [end:conditional-update]
cond=$(curl -s -o /dev/null -w '%{http_code}' -X PUT \
    "$HOGWARTS/Patient?identifier=urn:rl:nid%7CRL-0001" \
    -H 'Content-Type: application/fhir+json' \
    -d '{"resourceType":"Patient","identifier":[{"system":"urn:rl:nid","value":"RL-0001"}],
         "name":[{"family":"Potter","given":["Harry","James"]}]}')
[ "$cond" = "200" ] || fail "expected 200 from a conditional update, got $cond"
after=$(curl -sf -G "$HOGWARTS/Patient" --data-urlencode "identifier=urn:rl:nid|RL-0001" \
    | python3 -c 'import sys,json;print(len(json.load(sys.stdin).get("entry",[])))')
[ "$after" = "1" ] || fail "a conditional update should not add a record, got $after"

step "reading one version by number is not supported"
# --8<-- [start:vread]
curl -s -o /dev/null -w '%{http_code}\n' "$HOGWARTS/Patient/$id/_history/1"
# --8<-- [end:vread]
vread=$(curl -s -o /dev/null -w '%{http_code}' "$HOGWARTS/Patient/$id/_history/1")
[ "$vread" = "404" ] || fail "expected 404 from vread while it is unsupported, got $vread"

step "a definition is identified by its url, so writing it twice replaces it"
# --8<-- [start:canonical]
curl -s -o /dev/null -w '%{http_code}\n' -X POST "$ZONE/CodeSystem" \
    -H 'Content-Type: application/fhir+json' \
    -d '{"resourceType":"CodeSystem","url":"urn:rl:houses","version":"1",
         "status":"active","content":"complete",
         "concept":[{"code":"gry","display":"Gryffindor"}]}'

curl -s -o /dev/null -w '%{http_code}\n' -X POST "$ZONE/CodeSystem" \
    -H 'Content-Type: application/fhir+json' \
    -d '{"resourceType":"CodeSystem","url":"urn:rl:houses","version":"1",
         "status":"active","content":"complete",
         "concept":[{"code":"gry","display":"Gryffindor"},
                    {"code":"sly","display":"Slytherin"}]}'
# --8<-- [end:canonical]
held=$(curl -sf -G "$ZONE/CodeSystem" --data-urlencode "url=urn:rl:houses" \
    | python3 -c 'import sys,json;d=json.load(sys.stdin);e=d.get("entry",[]);print(str(len(e))+":"+(e[0]["resource"]["meta"]["versionId"] if e else "-"))')
[ "$held" = "1:2" ] || fail "expected one code system at version 2, got $held"

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

step "what this tenant says it can be asked"
# --8<-- [start:capability-search]
curl -s "$HOGWARTS/metadata" | python3 -c '
import sys, json
rest = json.load(sys.stdin)["rest"][0]
for resource in rest["resource"]:
    if resource["type"] == "Patient":
        print(*sorted(p["name"] for p in resource["searchParam"]), sep="\n")'
# --8<-- [end:capability-search]
declared=$(curl -s "$HOGWARTS/metadata" | python3 -c '
import sys, json
rest = json.load(sys.stdin)["rest"][0]
for resource in rest["resource"]:
    if resource["type"] == "Patient":
        print(len(resource["searchParam"]))')
[ "$declared" -gt 10 ] || fail "expected the capability statement to declare Patient search parameters, got $declared"

step "a modifier the parameter does not have is refused by name"
# --8<-- [start:unknown-modifier]
curl -s -G "$HOGWARTS/Patient" --data-urlencode "family:nosuch=Granger"
# --8<-- [end:unknown-modifier]
modifier=$(curl -s -G "$HOGWARTS/Patient" --data-urlencode "family:nosuch=Granger")
printf '%s' "$modifier" | grep -q "family:nosuch" \
    || fail "the refusal should name what it refused: $modifier"

step "counting without fetching"
# --8<-- [start:count]
curl -s -G "$HOGWARTS/Patient" --data-urlencode "_summary=count"
# --8<-- [end:count]
before=$(curl -s -G "$HOGWARTS/Patient" --data-urlencode "_summary=count" \
    | python3 -c 'import sys,json;print(json.load(sys.stdin)["total"])')

step "enough people to page through"
for family in Granger Longbottom Lovegood Malfoy Diggory; do
    curl -sf -o /dev/null -X POST "$HOGWARTS/Patient" \
        -H 'Content-Type: application/fhir+json' \
        -d "{\"resourceType\":\"Patient\",
             \"identifier\":[{\"system\":\"urn:rl:nid\",\"value\":\"RL-90-$family\"}],
             \"name\":[{\"family\":\"$family\"}]}" || fail "could not create $family"
done

step "a page, and the cursor that follows it"
# --8<-- [start:first-page]
curl -s -G "$HOGWARTS/Patient" --data-urlencode "_count=2"
# --8<-- [end:first-page]
page1=$(curl -sf -G "$HOGWARTS/Patient" --data-urlencode "_count=2")
first=$(printf '%s' "$page1" | python3 -c 'import sys,json;print(" ".join(e["resource"]["id"] for e in json.load(sys.stdin)["entry"]))')
next=$(printf '%s' "$page1" | python3 -c 'import sys,json;print([l["url"] for l in json.load(sys.stdin)["link"] if l["relation"]=="next"][0])')
printf '%s' "$next" | grep -q "_cursor=" || fail "the next link carries no cursor: $next"

step "a write lands between the pages, and the next page does not repeat"
curl -sf -o /dev/null -X POST "$HOGWARTS/Patient" \
    -H 'Content-Type: application/fhir+json' \
    -d '{"resourceType":"Patient",
         "identifier":[{"system":"urn:rl:nid","value":"RL-90-Between"}],
         "name":[{"family":"Aaaa"}]}'
# The link carries the address the node was told to bind to. A deployment
# names itself properly; this world binds to everything, so the example
# points the same cursor at the host the reader is on.
# --8<-- [start:next-page]
curl -s "$(printf '%s' "$next" | sed 's|http://0.0.0.0:8090|http://localhost:8090|')"
# --8<-- [end:next-page]
page2=$(curl -sf "$(printf '%s' "$next" | sed 's|http://0.0.0.0:8090|http://localhost:8090|')")
second=$(printf '%s' "$page2" | python3 -c 'import sys,json;print(" ".join(e["resource"]["id"] for e in json.load(sys.stdin).get("entry",[])))')
python3 - "$first" "$second" <<'OVERLAP' || fail "a page repeated a record the previous page already returned"
import sys
a, b = set(sys.argv[1].split()), set(sys.argv[2].split())
sys.exit(1 if a & b else 0)
OVERLAP

step "an unsupported search parameter is refused, not ignored"
# --8<-- [start:strict-search]
curl -s -o /dev/null -w '%{http_code}\n' "$HOGWARTS/Patient?favourite-colour=blue"
# --8<-- [end:strict-search]
code=$(curl -s -o /dev/null -w '%{http_code}' "$HOGWARTS/Patient?favourite-colour=blue")
[ "$code" = "400" ] || fail "expected 400 for an unknown parameter, got $code"

step "a code outside a required binding is refused, and the refusal names the element"
# --8<-- [start:validate-binding]
curl -s -o /dev/null -w '%{http_code}\n' -X POST "$HOGWARTS/Patient" \
    -H 'Content-Type: application/fhir+json' \
    -d '{"resourceType":"Patient",
         "identifier":[{"system":"urn:rl:nid","value":"RL-0002"}],
         "gender":"purple"}'

# The outcome repeats itself at length; this prints the first clause of
# each issue, which is the part naming what was wrong and where.
curl -s -X POST "$HOGWARTS/Patient" \
    -H 'Content-Type: application/fhir+json' \
    -d '{"resourceType":"Patient",
         "identifier":[{"system":"urn:rl:nid","value":"RL-0002"}],
         "gender":"purple"} ' \
  | python3 -c '
import sys, json
for issue in json.load(sys.stdin)["issue"]:
    print(issue["severity"], "|", issue["diagnostics"].split(";")[0])'
# --8<-- [end:validate-binding]
binding=$(curl -s -o /tmp/dbo-guide-binding -w '%{http_code}' -X POST "$HOGWARTS/Patient" \
    -H 'Content-Type: application/fhir+json' \
    -d '{"resourceType":"Patient","identifier":[{"system":"urn:rl:nid","value":"RL-0002"}],
         "gender":"purple"}')
[ "$binding" = "422" ] || fail "expected 422 for a code outside a required binding, got $binding"
grep -q 'Patient.gender' /tmp/dbo-guide-binding \
    || fail "the refusal should name the element: $(cat /tmp/dbo-guide-binding)"
rejected=$(curl -sf -G "$HOGWARTS/Patient" --data-urlencode "identifier=urn:rl:nid|RL-0002" \
    | python3 -c 'import sys,json;print(len(json.load(sys.stdin).get("entry",[])))')
[ "$rejected" = "0" ] || fail "a refused write left a record behind"

step "a malformed value and a missing required element are refused the same way"
# --8<-- [start:validate-shape]
curl -s -o /dev/null -w '%{http_code}\n' -X POST "$HOGWARTS/Patient" \
    -H 'Content-Type: application/fhir+json' \
    -d '{"resourceType":"Patient",
         "identifier":[{"system":"urn:rl:nid","value":"RL-0003"}],
         "birthDate":"not-a-date"}'

curl -s -o /dev/null -w '%{http_code}\n' -X POST "$HOGWARTS/Observation" \
    -H 'Content-Type: application/fhir+json' \
    -d '{"resourceType":"Observation","code":{"text":"house points"}}'
# --8<-- [end:validate-shape]
badday=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$HOGWARTS/Patient" \
    -H 'Content-Type: application/fhir+json' \
    -d '{"resourceType":"Patient","identifier":[{"system":"urn:rl:nid","value":"RL-0003"}],
         "birthDate":"not-a-date"}')
[ "$badday" = "422" ] || fail "expected 422 for a malformed date, got $badday"
nostatus=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$HOGWARTS/Observation" \
    -H 'Content-Type: application/fhir+json' \
    -d '{"resourceType":"Observation","code":{"text":"house points"}}')
[ "$nostatus" = "422" ] || fail "expected 422 for a missing required element, got $nostatus"

step "the same mistake at both faces, each naming the version it validated against"
# --8<-- [start:validate-versions]
for base in "$HOGWARTS" "$GRINGOTTS"; do
    curl -s -X POST "$base/Patient" \
        -H 'Content-Type: application/fhir+json' \
        -d '{"resourceType":"Patient",
             "identifier":[{"system":"urn:rl:nid","value":"RL-0004"}],
             "gender":"purple"}' \
      | grep -o 'administrative-gender|[0-9.]*' | head -1
done
# --8<-- [end:validate-versions]
r5said=$(curl -s -X POST "$HOGWARTS/Patient" -H 'Content-Type: application/fhir+json' \
    -d '{"resourceType":"Patient","identifier":[{"system":"urn:rl:nid","value":"RL-0004"}],
         "gender":"purple"}' | grep -o 'administrative-gender|[0-9.]*' | head -1)
r4said=$(curl -s -X POST "$GRINGOTTS/Patient" -H 'Content-Type: application/fhir+json' \
    -d '{"resourceType":"Patient","identifier":[{"system":"urn:rl:nid","value":"RL-0004"}],
         "gender":"purple"}' | grep -o 'administrative-gender|[0-9.]*' | head -1)
[ "$r5said" = "administrative-gender|5.0.0" ] \
    || fail "the hospital should validate against its own release, said $r5said"
[ "$r4said" = "administrative-gender|4.0.1" ] \
    || fail "the insurer should validate against its own release, said $r4said"

step "asking for the verdict without writing"
# --8<-- [start:validate-ahead]
curl -s -X POST "$HOGWARTS/Observation/\$validate" \
    -H 'Content-Type: application/fhir+json' \
    -d '{"resourceType":"Observation","code":{"text":"house points"}}' \
  | python3 -c '
import sys, json
for issue in json.load(sys.stdin)["issue"]:
    print(issue["severity"], "|", issue["diagnostics"][:85])'
# --8<-- [end:validate-ahead]
ahead=$(curl -s -o /tmp/dbo-guide-ahead -w '%{http_code}' -X POST "$HOGWARTS/Observation/\$validate" \
    -H 'Content-Type: application/fhir+json' \
    -d '{"resourceType":"Observation","code":{"text":"house points"}}')
[ "$ahead" = "200" ] || fail "a verdict was reached, so \$validate answers 200; got $ahead"
grep -q 'Observation.status' /tmp/dbo-guide-ahead \
    || fail "the verdict should name the missing element: $(cat /tmp/dbo-guide-ahead)"
grep -q '"severity":"warning"' /tmp/dbo-guide-ahead \
    || fail "the verdict should carry advice as well as errors"

printf '\nguide: chapters one to six work\n'
