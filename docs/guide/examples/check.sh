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

# The world, normally the pinned one the guide publishes. DBO_GUIDE_COMPOSE
# points this at a candidate instead — a locally built image, say — so the
# guide's own commands can be run against a build BEFORE the pin moves to it.
# Three findings were filed against the pinned image in one day that were
# already fixed in the tree; this is how that is checked rather than guessed.
COMPOSE_FILE="${DBO_GUIDE_COMPOSE:-docs/guide/examples/compose.yaml}"
COMPOSE="docker compose -f $COMPOSE_FILE"
step() { printf '\n=== %s\n' "$1"; }
fail() { echo "guide: $1" >&2; exit 1; }
cleanup() {
    rm -f "$PWD/hogwarts.archive"
    $COMPOSE down --remove-orphans >/dev/null 2>&1 || true
}
trap cleanup EXIT

step "starting the world"
$COMPOSE pull --quiet >/dev/null 2>&1 || true
# The published command names the guide's own compose file, because that is
# what a reader types. A candidate world is started by the same verb on the
# file DBO_GUIDE_COMPOSE names — the branch exists so the literal below can
# stay literal rather than becoming a variable nobody could copy.
if [ -n "${DBO_GUIDE_COMPOSE:-}" ]; then
    $COMPOSE up -d
else
# --8<-- [start:up]
docker compose -f docs/guide/examples/compose.yaml up -d
# --8<-- [end:up]
fi

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

step "a credential, because the world is guarded"
# --8<-- [start:token]
token() {
    curl -sf -X POST "http://localhost:8090/t/$1/oidc/token" \
        -H 'Content-Type: application/x-www-form-urlencoded' \
        -d "grant_type=client_credentials&client_id=${3:-tenant-bootstrap}&client_secret=$2" \
      | python3 -c 'import sys,json;print(json.load(sys.stdin)["access_token"])'
}

HOSPITAL=$(token hogwarts hogwarts-secret)
INSURER=$(token gringotts gringotts-secret)
JURISDICTION=$(token rl rl-secret)
# --8<-- [end:token]
[ -n "$HOSPITAL" ] || fail "no token for the hospital"
[ -n "$INSURER" ] || fail "no token for the insurer"
[ -n "$JURISDICTION" ] || fail "no token for the zone"
FACE_R5=$(token fhir-r5 r5-secret)
FACE_R4=$(token fhir-r4 r4-secret)

step "and without one, the store says no"
# --8<-- [start:no-token]
curl -s -o /dev/null -w '%{http_code}\n' "$HOGWARTS/Patient"
# --8<-- [end:no-token]
refused=$(curl -s -o /dev/null -w '%{http_code}' "$HOGWARTS/Patient")
[ "$refused" = "401" ] || fail "an unauthenticated read should be refused, got $refused"

step "the zone publishes terminology of its own"
# --8<-- [start:zone-publishes]
curl -s -o /dev/null -w '%{http_code}\n' -X POST -H "Authorization: Bearer $JURISDICTION" "$ZONE/CodeSystem" \
    -H 'Content-Type: application/fhir+json' \
    -d '{"resourceType":"CodeSystem","url":"urn:rl:wards","version":"1",
         "status":"active","content":"complete",
         "concept":[{"code":"dai","display":"Dai Llewellyn Ward"},
                    {"code":"spell","display":"Spell Damage"},
                    {"code":"creature","display":"Creature-Induced Injuries"},
                    {"code":"potion","display":"Potion and Plant Poisoning"}]}'

curl -s -o /dev/null -w '%{http_code}\n' -X POST -H "Authorization: Bearer $JURISDICTION" "$ZONE/ValueSet" \
    -H 'Content-Type: application/fhir+json' \
    -d '{"resourceType":"ValueSet","url":"urn:rl:wards:vs","version":"1",
         "status":"active","compose":{"include":[{"system":"urn:rl:wards"}]}}'
# --8<-- [end:zone-publishes]

step "and answers questions about it"
# --8<-- [start:zone-lookup]
curl -s -H "Authorization: Bearer $JURISDICTION" "$ZONE/CodeSystem/\$lookup?system=urn:rl:wards&code=dai"
# --8<-- [end:zone-lookup]
looked=$(curl -s -H "Authorization: Bearer $JURISDICTION" "$ZONE/CodeSystem/\$lookup?system=urn:rl:wards&code=dai")
printf '%s' "$looked" | grep -q 'Dai Llewellyn' || fail "the zone cannot look up its own code: $looked"

# --8<-- [start:zone-expand]
curl -s -H "Authorization: Bearer $JURISDICTION" "$ZONE/ValueSet/\$expand?url=urn:rl:wards:vs" | python3 -c '
import sys, json
expansion = json.load(sys.stdin)["expansion"]
print(expansion["total"], "concepts")
for concept in expansion["contains"]:
    print(" ", concept["code"], concept["display"])'
# --8<-- [end:zone-expand]
expanded=$(curl -s -H "Authorization: Bearer $JURISDICTION" "$ZONE/ValueSet/\$expand?url=urn:rl:wards:vs" \
    | python3 -c 'import sys,json;print(json.load(sys.stdin)["expansion"]["total"])')
[ "$expanded" = "4" ] || fail "expected the value set to expand to 4 concepts, got $expanded"

step "asking each tenant which version it speaks"
# --8<-- [start:versions]
curl -s -H "Authorization: Bearer $HOSPITAL" "$HOGWARTS/metadata"  | python3 -c 'import sys,json;print(json.load(sys.stdin)["fhirVersion"])'
curl -s -H "Authorization: Bearer $INSURER" "$GRINGOTTS/metadata" | python3 -c 'import sys,json;print(json.load(sys.stdin)["fhirVersion"])'
# --8<-- [end:versions]
h=$(curl -s -H "Authorization: Bearer $HOSPITAL" "$HOGWARTS/metadata" | python3 -c 'import sys,json;print(json.load(sys.stdin)["fhirVersion"])')
g=$(curl -s -H "Authorization: Bearer $INSURER" "$GRINGOTTS/metadata" | python3 -c 'import sys,json;print(json.load(sys.stdin)["fhirVersion"])')
[ "$h" = "5.0.0" ] || fail "hogwarts should speak 5.0.0, said $h"
[ "$g" = "4.0.1" ] || fail "gringotts should speak 4.0.1, said $g"

step "admitting a patient"
# --8<-- [start:create]
created=$(curl -sf -X POST -H "Authorization: Bearer $HOSPITAL" "$HOGWARTS/Patient" \
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
curl -s -X PUT -H "Authorization: Bearer $HOSPITAL" "$HOGWARTS/Patient/harry" \
    -H 'Content-Type: application/fhir+json' \
    -d '{"resourceType":"Patient","id":"harry"}'
# --8<-- [end:readable-id]
refused=$(curl -s -o /dev/null -w '%{http_code}' -X PUT -H "Authorization: Bearer $HOSPITAL" "$HOGWARTS/Patient/harry" \
    -H 'Content-Type: application/fhir+json' -d '{"resourceType":"Patient","id":"harry"}')
[ "$refused" = "400" ] || fail "expected 400 for a readable id, got $refused"

step "finding him by the identifier the zone declares"
# --data-urlencode, because a token search carries a | and curl will not
# escape it for you.
# --8<-- [start:search]
curl -sf -G -H "Authorization: Bearer $HOSPITAL" "$HOGWARTS/Patient" \
    --data-urlencode "identifier=urn:rl:nid|RL-0001"
# --8<-- [end:search]
found=$(curl -sf -G -H "Authorization: Bearer $HOSPITAL" "$HOGWARTS/Patient" --data-urlencode "identifier=urn:rl:nid|RL-0001" \
    | python3 -c 'import sys,json;print(len(json.load(sys.stdin).get("entry",[])))')
[ "$found" = "1" ] || fail "expected one match, got $found"

step "changing him, and reading what he was"
# --8<-- [start:history]
curl -sf -o /dev/null -X PUT -H "Authorization: Bearer $HOSPITAL" "$HOGWARTS/Patient/$id" \
    -H 'Content-Type: application/fhir+json' \
    -d "{\"resourceType\":\"Patient\",\"id\":\"$id\",
         \"identifier\":[{\"system\":\"urn:rl:nid\",\"value\":\"RL-0001\"}],
         \"name\":[{\"family\":\"Potter\",\"given\":[\"H.\"]}]}"

curl -sf -H "Authorization: Bearer $HOSPITAL" "$HOGWARTS/Patient/$id/_history"
# --8<-- [end:history]
versions=$(curl -sf -H "Authorization: Bearer $HOSPITAL" "$HOGWARTS/Patient/$id/_history" \
    | python3 -c 'import sys,json;print(len(json.load(sys.stdin).get("entry",[])))')
[ "$versions" = "2" ] || fail "expected two versions, got $versions"

step "the same person at the insurer, which is a version behind"
# --8<-- [start:insurer]
curl -sf -X POST -H "Authorization: Bearer $INSURER" "$GRINGOTTS/Patient" \
    -H 'Content-Type: application/fhir+json' \
    -d '{"resourceType":"Patient",
         "identifier":[{"system":"urn:rl:nid","value":"RL-0001"}],
         "name":[{"family":"Potter","given":["Harry"]}]}'
# --8<-- [end:insurer]
at_insurer=$(curl -sf -G -H "Authorization: Bearer $INSURER" "$GRINGOTTS/Patient" --data-urlencode "identifier=urn:rl:nid|RL-0001" \
    | python3 -c 'import sys,json;print(len(json.load(sys.stdin).get("entry",[])))')
[ "$at_insurer" = "1" ] || fail "expected the person at the insurer, got $at_insurer"

step "and the older version refuses what it does not have"
# --8<-- [start:version-refusal]
curl -s -X POST -H "Authorization: Bearer $INSURER" "$GRINGOTTS/Coverage" \
    -H 'Content-Type: application/fhir+json' \
    -d '{"resourceType":"Coverage","status":"active","kind":"insurance",
         "beneficiary":{"reference":"Patient/x"}}'
# --8<-- [end:version-refusal]
outcome=$(curl -s -X POST -H "Authorization: Bearer $INSURER" "$GRINGOTTS/Coverage" \
    -H 'Content-Type: application/fhir+json' \
    -d '{"resourceType":"Coverage","status":"active","kind":"insurance","beneficiary":{"reference":"Patient/x"}}')
printf '%s' "$outcome" | grep -q '4.0.1' \
    || fail "the refusal should name the version it validated against: $outcome"
printf '%s' "$outcome" | grep -q 'OperationOutcome' || fail "expected an OperationOutcome"

step "the same person twice is refused, not duplicated"
# --8<-- [start:duplicate-identity]
curl -s -o /dev/null -w '%{http_code}\n' -X POST -H "Authorization: Bearer $HOSPITAL" "$HOGWARTS/Patient" \
    -H 'Content-Type: application/fhir+json' \
    -d '{"resourceType":"Patient",
         "identifier":[{"system":"urn:rl:nid","value":"RL-0001"}],
         "name":[{"family":"Potter","given":["Harry","James"]}]}'
# --8<-- [end:duplicate-identity]
dup=$(curl -s -o /dev/null -w '%{http_code}' -X POST -H "Authorization: Bearer $HOSPITAL" "$HOGWARTS/Patient" \
    -H 'Content-Type: application/fhir+json' \
    -d '{"resourceType":"Patient","identifier":[{"system":"urn:rl:nid","value":"RL-0001"}],
         "name":[{"family":"Potter","given":["Harry","James"]}]}')
[ "$dup" = "409" ] || fail "expected 409 for a second create of one identity, got $dup"
count=$(curl -sf -G -H "Authorization: Bearer $HOSPITAL" "$HOGWARTS/Patient" --data-urlencode "identifier=urn:rl:nid|RL-0001" \
    | python3 -c 'import sys,json;print(len(json.load(sys.stdin).get("entry",[])))')
[ "$count" = "1" ] || fail "expected one record for one identity, got $count"

step "updating by identity rather than by id"
# --8<-- [start:conditional-update]
curl -s -o /dev/null -w '%{http_code}\n' -X PUT \
    -H "Authorization: Bearer $HOSPITAL" "$HOGWARTS/Patient?identifier=urn:rl:nid%7CRL-0001" \
    -H 'Content-Type: application/fhir+json' \
    -d '{"resourceType":"Patient",
         "identifier":[{"system":"urn:rl:nid","value":"RL-0001"}],
         "name":[{"family":"Potter","given":["Harry","James"]}]}'
# --8<-- [end:conditional-update]
cond=$(curl -s -o /dev/null -w '%{http_code}' -X PUT \
    -H "Authorization: Bearer $HOSPITAL" "$HOGWARTS/Patient?identifier=urn:rl:nid%7CRL-0001" \
    -H 'Content-Type: application/fhir+json' \
    -d '{"resourceType":"Patient","identifier":[{"system":"urn:rl:nid","value":"RL-0001"}],
         "name":[{"family":"Potter","given":["Harry","James"]}]}')
[ "$cond" = "200" ] || fail "expected 200 from a conditional update, got $cond"
after=$(curl -sf -G -H "Authorization: Bearer $HOSPITAL" "$HOGWARTS/Patient" --data-urlencode "identifier=urn:rl:nid|RL-0001" \
    | python3 -c 'import sys,json;print(len(json.load(sys.stdin).get("entry",[])))')
[ "$after" = "1" ] || fail "a conditional update should not add a record, got $after"

step "reading one version by number"
# --8<-- [start:vread]
curl -s -H "Authorization: Bearer $HOSPITAL" "$HOGWARTS/Patient/$id/_history/1"

curl -s -o /dev/null -w '%{http_code}\n' -H "Authorization: Bearer $HOSPITAL" "$HOGWARTS/Patient/$id/_history/99"
# --8<-- [end:vread]
first=$(curl -sf -H "Authorization: Bearer $HOSPITAL" "$HOGWARTS/Patient/$id/_history/1")
printf '%s' "$first" | grep -q '"given":\["Harry"\]' \
    || fail "version 1 did not come back as it was written: $first"
printf '%s' "$first" | grep -q '"versionId":"1"' \
    || fail "the document does not say which version it is: $first"
absent=$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $HOSPITAL" "$HOGWARTS/Patient/$id/_history/99")
[ "$absent" = "404" ] || fail "expected 404 for a version that never existed, got $absent"
etag=$(curl -s -o /dev/null -D - -H "Authorization: Bearer $HOSPITAL" "$HOGWARTS/Patient/$id/_history/1" | grep -i '^etag:' | tr -d '\r')
printf '%s' "$etag" | grep -q 'W/"1"' \
    || fail "a version read must carry THAT version's validator, got: $etag"

step "a definition is identified by its url, so writing it twice replaces it"
# --8<-- [start:canonical]
curl -s -o /dev/null -w '%{http_code}\n' -X POST -H "Authorization: Bearer $JURISDICTION" "$ZONE/CodeSystem" \
    -H 'Content-Type: application/fhir+json' \
    -d '{"resourceType":"CodeSystem","url":"urn:rl:houses","version":"1",
         "status":"active","content":"complete",
         "concept":[{"code":"gry","display":"Gryffindor"}]}'

curl -s -o /dev/null -w '%{http_code}\n' -X POST -H "Authorization: Bearer $JURISDICTION" "$ZONE/CodeSystem" \
    -H 'Content-Type: application/fhir+json' \
    -d '{"resourceType":"CodeSystem","url":"urn:rl:houses","version":"1",
         "status":"active","content":"complete",
         "concept":[{"code":"gry","display":"Gryffindor"},
                    {"code":"sly","display":"Slytherin"}]}'
# --8<-- [end:canonical]
held=$(curl -sf -G -H "Authorization: Bearer $JURISDICTION" "$ZONE/CodeSystem" --data-urlencode "url=urn:rl:houses" \
    | python3 -c 'import sys,json;d=json.load(sys.stdin);e=d.get("entry",[]);print(str(len(e))+":"+(e[0]["resource"]["meta"]["versionId"] if e else "-"))')
[ "$held" = "1:2" ] || fail "expected one code system at version 2, got $held"

step "a type the tenant never declared is refused"
# The hospital declared Observation. The insurer did not — it holds Patient
# and Coverage — so the same request is answered differently by each.
# --8<-- [start:undeclared-type]
curl -s -o /dev/null -w '%{http_code}\n' -X POST -H "Authorization: Bearer $INSURER" "$GRINGOTTS/Observation" \
    -H 'Content-Type: application/fhir+json' \
    -d '{"resourceType":"Observation","status":"final",
         "code":{"text":"house points"}}'
# --8<-- [end:undeclared-type]
undeclared=$(curl -s -o /dev/null -w '%{http_code}' -X POST -H "Authorization: Bearer $INSURER" "$GRINGOTTS/Observation" \
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
still=$(curl -sf -G -H "Authorization: Bearer $HOSPITAL" "$HOGWARTS/Patient" --data-urlencode "identifier=urn:rl:nid|RL-0001" \
    | python3 -c 'import sys,json;print(len(json.load(sys.stdin).get("entry",[])))')
[ "$still" = "1" ] || fail "the hospital lost a record when a neighbour went away"

step "what this tenant says it can be asked"
# --8<-- [start:capability-search]
curl -s -H "Authorization: Bearer $HOSPITAL" "$HOGWARTS/metadata" | python3 -c '
import sys, json
rest = json.load(sys.stdin)["rest"][0]
for resource in rest["resource"]:
    if resource["type"] == "Patient":
        print(*sorted(p["name"] for p in resource["searchParam"]), sep="\n")'
# --8<-- [end:capability-search]
declared=$(curl -s -H "Authorization: Bearer $HOSPITAL" "$HOGWARTS/metadata" | python3 -c '
import sys, json
rest = json.load(sys.stdin)["rest"][0]
for resource in rest["resource"]:
    if resource["type"] == "Patient":
        print(len(resource["searchParam"]))')
[ "$declared" -gt 10 ] || fail "expected the capability statement to declare Patient search parameters, got $declared"

step "a modifier the parameter does not have is refused by name"
# --8<-- [start:unknown-modifier]
curl -s -G -H "Authorization: Bearer $HOSPITAL" "$HOGWARTS/Patient" --data-urlencode "family:nosuch=Granger"
# --8<-- [end:unknown-modifier]
modifier=$(curl -s -G -H "Authorization: Bearer $HOSPITAL" "$HOGWARTS/Patient" --data-urlencode "family:nosuch=Granger")
printf '%s' "$modifier" | grep -q "family:nosuch" \
    || fail "the refusal should name what it refused: $modifier"

step "counting without fetching"
# --8<-- [start:count]
curl -s -G -H "Authorization: Bearer $HOSPITAL" "$HOGWARTS/Patient" --data-urlencode "_summary=count"
# --8<-- [end:count]
before=$(curl -s -G -H "Authorization: Bearer $HOSPITAL" "$HOGWARTS/Patient" --data-urlencode "_summary=count" \
    | python3 -c 'import sys,json;print(json.load(sys.stdin)["total"])')

step "enough people to page through"
for family in Granger Longbottom Lovegood Malfoy Diggory; do
    curl -sf -o /dev/null -X POST -H "Authorization: Bearer $HOSPITAL" "$HOGWARTS/Patient" \
        -H 'Content-Type: application/fhir+json' \
        -d "{\"resourceType\":\"Patient\",
             \"identifier\":[{\"system\":\"urn:rl:nid\",\"value\":\"RL-90-$family\"}],
             \"name\":[{\"family\":\"$family\"}]}" || fail "could not create $family"
done

step "a page, and the cursor that follows it"
# --8<-- [start:first-page]
curl -s -G -H "Authorization: Bearer $HOSPITAL" "$HOGWARTS/Patient" --data-urlencode "_count=2"
# --8<-- [end:first-page]
page1=$(curl -sf -G -H "Authorization: Bearer $HOSPITAL" "$HOGWARTS/Patient" --data-urlencode "_count=2")
first=$(printf '%s' "$page1" | python3 -c 'import sys,json;print(" ".join(e["resource"]["id"] for e in json.load(sys.stdin)["entry"]))')
next=$(printf '%s' "$page1" | python3 -c 'import sys,json;print([l["url"] for l in json.load(sys.stdin)["link"] if l["relation"]=="next"][0])')
printf '%s' "$next" | grep -q "_cursor=" || fail "the next link carries no cursor: $next"

step "a write lands between the pages, and the next page does not repeat"
curl -sf -o /dev/null -X POST -H "Authorization: Bearer $HOSPITAL" "$HOGWARTS/Patient" \
    -H 'Content-Type: application/fhir+json' \
    -d '{"resourceType":"Patient",
         "identifier":[{"system":"urn:rl:nid","value":"RL-90-Between"}],
         "name":[{"family":"Aaaa"}]}'
# The link carries the address the node was told to bind to. A deployment
# names itself properly; this world binds to everything, so the example
# points the same cursor at the host the reader is on.
# --8<-- [start:next-page]
curl -s -H "Authorization: Bearer $HOSPITAL" \
    "$(printf '%s' "$next" | sed 's|http://0.0.0.0:8090|http://localhost:8090|')"
# --8<-- [end:next-page]
page2=$(curl -sf -H "Authorization: Bearer $HOSPITAL" \
    "$(printf '%s' "$next" | sed 's|http://0.0.0.0:8090|http://localhost:8090|')")
second=$(printf '%s' "$page2" | python3 -c 'import sys,json;print(" ".join(e["resource"]["id"] for e in json.load(sys.stdin).get("entry",[])))')
python3 - "$first" "$second" <<'OVERLAP' || fail "a page repeated a record the previous page already returned"
import sys
a, b = set(sys.argv[1].split()), set(sys.argv[2].split())
sys.exit(1 if a & b else 0)
OVERLAP

step "an unsupported search parameter is refused, not ignored"
# --8<-- [start:strict-search]
curl -s -o /dev/null -w '%{http_code}\n' -H "Authorization: Bearer $HOSPITAL" "$HOGWARTS/Patient?favourite-colour=blue"
# --8<-- [end:strict-search]
code=$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $HOSPITAL" "$HOGWARTS/Patient?favourite-colour=blue")
[ "$code" = "400" ] || fail "expected 400 for an unknown parameter, got $code"

step "an element the face does not define is refused, not quietly kept"
# --8<-- [start:unknown-element]
curl -s -X POST -H "Authorization: Bearer $HOSPITAL" "$HOGWARTS/Patient" \
    -H 'Content-Type: application/fhir+json' \
    -d '{"resourceType":"Patient",
         "identifier":[{"system":"urn:rl:nid","value":"RL-0006"}],
         "favouriteColour":"blue"}'
# --8<-- [end:unknown-element]
invented=$(curl -s -o /tmp/dbo-guide-invented -w '%{http_code}' -X POST -H "Authorization: Bearer $HOSPITAL" "$HOGWARTS/Patient" \
    -H 'Content-Type: application/fhir+json' \
    -d '{"resourceType":"Patient","identifier":[{"system":"urn:rl:nid","value":"RL-0006"}],
         "favouriteColour":"blue"}')
[ "$invented" = "422" ] || fail "expected 422 for an undefined element, got $invented"
grep -q "favouriteColour" /tmp/dbo-guide-invented \
    || fail "the refusal should name the element: $(cat /tmp/dbo-guide-invented)"
grep -q "extension" /tmp/dbo-guide-invented \
    || fail "the refusal should point at the sanctioned way to carry it"
landed=$(curl -sf -G -H "Authorization: Bearer $HOSPITAL" "$HOGWARTS/Patient" --data-urlencode "identifier=urn:rl:nid|RL-0006" \
    | python3 -c 'import sys,json;print(len(json.load(sys.stdin).get("entry",[])))')
[ "$landed" = "0" ] || fail "a refused write left a record behind"

step "a code outside a required binding is refused, and the refusal names the element"
# --8<-- [start:validate-binding]
curl -s -o /dev/null -w '%{http_code}\n' -X POST -H "Authorization: Bearer $HOSPITAL" "$HOGWARTS/Patient" \
    -H 'Content-Type: application/fhir+json' \
    -d '{"resourceType":"Patient",
         "identifier":[{"system":"urn:rl:nid","value":"RL-0002"}],
         "gender":"purple"}'

# The outcome repeats itself at length; this prints the first clause of
# each issue, which is the part naming what was wrong and where.
curl -s -X POST -H "Authorization: Bearer $HOSPITAL" "$HOGWARTS/Patient" \
    -H 'Content-Type: application/fhir+json' \
    -d '{"resourceType":"Patient",
         "identifier":[{"system":"urn:rl:nid","value":"RL-0002"}],
         "gender":"purple"} ' \
  | python3 -c '
import sys, json
for issue in json.load(sys.stdin)["issue"]:
    print(issue["severity"], "|", issue["diagnostics"].split(";")[0])'
# --8<-- [end:validate-binding]
binding=$(curl -s -o /tmp/dbo-guide-binding -w '%{http_code}' -X POST -H "Authorization: Bearer $HOSPITAL" "$HOGWARTS/Patient" \
    -H 'Content-Type: application/fhir+json' \
    -d '{"resourceType":"Patient","identifier":[{"system":"urn:rl:nid","value":"RL-0002"}],
         "gender":"purple"}')
[ "$binding" = "422" ] || fail "expected 422 for a code outside a required binding, got $binding"
grep -q 'Patient.gender' /tmp/dbo-guide-binding \
    || fail "the refusal should name the element: $(cat /tmp/dbo-guide-binding)"
rejected=$(curl -sf -G -H "Authorization: Bearer $HOSPITAL" "$HOGWARTS/Patient" --data-urlencode "identifier=urn:rl:nid|RL-0002" \
    | python3 -c 'import sys,json;print(len(json.load(sys.stdin).get("entry",[])))')
[ "$rejected" = "0" ] || fail "a refused write left a record behind"

step "a malformed value and a missing required element are refused the same way"
# --8<-- [start:validate-shape]
curl -s -o /dev/null -w '%{http_code}\n' -X POST -H "Authorization: Bearer $HOSPITAL" "$HOGWARTS/Patient" \
    -H 'Content-Type: application/fhir+json' \
    -d '{"resourceType":"Patient",
         "identifier":[{"system":"urn:rl:nid","value":"RL-0003"}],
         "birthDate":"not-a-date"}'

curl -s -o /dev/null -w '%{http_code}\n' -X POST -H "Authorization: Bearer $HOSPITAL" "$HOGWARTS/Observation" \
    -H 'Content-Type: application/fhir+json' \
    -d '{"resourceType":"Observation","code":{"text":"house points"}}'
# --8<-- [end:validate-shape]
badday=$(curl -s -o /dev/null -w '%{http_code}' -X POST -H "Authorization: Bearer $HOSPITAL" "$HOGWARTS/Patient" \
    -H 'Content-Type: application/fhir+json' \
    -d '{"resourceType":"Patient","identifier":[{"system":"urn:rl:nid","value":"RL-0003"}],
         "birthDate":"not-a-date"}')
[ "$badday" = "422" ] || fail "expected 422 for a malformed date, got $badday"
nostatus=$(curl -s -o /dev/null -w '%{http_code}' -X POST -H "Authorization: Bearer $HOSPITAL" "$HOGWARTS/Observation" \
    -H 'Content-Type: application/fhir+json' \
    -d '{"resourceType":"Observation","code":{"text":"house points"}}')
[ "$nostatus" = "422" ] || fail "expected 422 for a missing required element, got $nostatus"

step "the same mistake at both faces, each naming the version it validated against"
# --8<-- [start:validate-versions]
curl -s -X POST -H "Authorization: Bearer $HOSPITAL" "$HOGWARTS/Patient" \
    -H 'Content-Type: application/fhir+json' \
    -d '{"resourceType":"Patient",
         "identifier":[{"system":"urn:rl:nid","value":"RL-0004"}],
         "gender":"purple"}' \
  | grep -o 'administrative-gender|[0-9.]*' | head -1

curl -s -X POST -H "Authorization: Bearer $INSURER" "$GRINGOTTS/Patient" \
    -H 'Content-Type: application/fhir+json' \
    -d '{"resourceType":"Patient",
         "identifier":[{"system":"urn:rl:nid","value":"RL-0004"}],
         "gender":"purple"}' \
  | grep -o 'administrative-gender|[0-9.]*' | head -1
# --8<-- [end:validate-versions]
r5said=$(curl -s -X POST -H "Authorization: Bearer $HOSPITAL" "$HOGWARTS/Patient" -H 'Content-Type: application/fhir+json' \
    -d '{"resourceType":"Patient","identifier":[{"system":"urn:rl:nid","value":"RL-0004"}],
         "gender":"purple"}' | grep -o 'administrative-gender|[0-9.]*' | head -1)
r4said=$(curl -s -X POST -H "Authorization: Bearer $INSURER" "$GRINGOTTS/Patient" -H 'Content-Type: application/fhir+json' \
    -d '{"resourceType":"Patient","identifier":[{"system":"urn:rl:nid","value":"RL-0004"}],
         "gender":"purple"}' | grep -o 'administrative-gender|[0-9.]*' | head -1)
[ "$r5said" = "administrative-gender|5.0.0" ] \
    || fail "the hospital should validate against its own release, said $r5said"
[ "$r4said" = "administrative-gender|4.0.1" ] \
    || fail "the insurer should validate against its own release, said $r4said"

step "asking for the verdict without writing"
# --8<-- [start:validate-ahead]
curl -s -X POST -H "Authorization: Bearer $HOSPITAL" "$HOGWARTS/Observation/\$validate" \
    -H 'Content-Type: application/fhir+json' \
    -d '{"resourceType":"Observation","code":{"text":"house points"}}' \
  | python3 -c '
import sys, json
for issue in json.load(sys.stdin)["issue"]:
    print(issue["severity"], "|", issue["diagnostics"][:85])'
# --8<-- [end:validate-ahead]
ahead=$(curl -s -o /tmp/dbo-guide-ahead -w '%{http_code}' -X POST -H "Authorization: Bearer $HOSPITAL" "$HOGWARTS/Observation/\$validate" \
    -H 'Content-Type: application/fhir+json' \
    -d '{"resourceType":"Observation","code":{"text":"house points"}}')
[ "$ahead" = "200" ] || fail "a verdict was reached, so \$validate answers 200; got $ahead"
grep -q 'Observation.status' /tmp/dbo-guide-ahead \
    || fail "the verdict should name the missing element: $(cat /tmp/dbo-guide-ahead)"
grep -q '"severity":"warning"' /tmp/dbo-guide-ahead \
    || fail "the verdict should carry advice as well as errors"

step "waiting for the zone's terminology to reach the hospital"
# The hospital's first sync from its zone runs some minutes after it comes
# up; once the stream is running, a change propagates in a second or two.
for _ in $(seq 1 120); do
    held=$(curl -sf -G -H "Authorization: Bearer $HOSPITAL" "$HOGWARTS/CodeSystem" --data-urlencode "url=urn:rl:wards" \
        | python3 -c 'import sys,json;print(len(json.load(sys.stdin).get("entry",[])))')
    [ "$held" = "1" ] && break
    sleep 5
done

step "the hospital declared the zone, so it answers the zone's codes as its own"
# --8<-- [start:zone-reaches-hospital]
curl -s -H "Authorization: Bearer $HOSPITAL" "$HOGWARTS/CodeSystem/\$lookup?system=urn:rl:wards&code=spell"
# --8<-- [end:zone-reaches-hospital]
at_hospital=$(curl -s -H "Authorization: Bearer $HOSPITAL" "$HOGWARTS/CodeSystem/\$lookup?system=urn:rl:wards&code=spell")
printf '%s' "$at_hospital" | grep -q 'Spell Damage' \
    || fail "the zone's terminology never reached the hospital: $at_hospital"

# The insurer's copy travels further than the hospital's: the zone speaks R5
# and the insurer R4, so it arrives through the projection that converts once.
# Waiting for the hospital is not waiting for the insurer.
for _ in $(seq 1 120); do
    reached=$(curl -sf -G -H "Authorization: Bearer $INSURER" "$GRINGOTTS/CodeSystem" \
        --data-urlencode "url=urn:rl:wards" \
        | python3 -c 'import sys,json;print(len(json.load(sys.stdin).get("entry",[])))')
    [ "$reached" = "1" ] && break
    sleep 5
done

step "the insurer declared the code systems and not the value sets, and that is what it has"
# --8<-- [start:zone-partial-at-insurer]
curl -s -w '\n' -H "Authorization: Bearer $INSURER" "$GRINGOTTS/CodeSystem/\$lookup?system=urn:rl:wards&code=spell"

curl -sf -G -H "Authorization: Bearer $INSURER" "$GRINGOTTS/ValueSet" --data-urlencode "url=urn:rl:wards:vs" \
  | python3 -c 'import sys,json;print(len(json.load(sys.stdin).get("entry",[])), "value sets")'
# --8<-- [end:zone-partial-at-insurer]
insurer_code=$(curl -s -H "Authorization: Bearer $INSURER" "$GRINGOTTS/CodeSystem/\$lookup?system=urn:rl:wards&code=spell")
printf '%s' "$insurer_code" | grep -q 'Spell Damage' \
    || fail "the insurer declared CodeSystem from the zone and did not get it: $insurer_code"
insurer_vs=$(curl -sf -G -H "Authorization: Bearer $INSURER" "$GRINGOTTS/ValueSet" --data-urlencode "url=urn:rl:wards:vs" \
    | python3 -c 'import sys,json;print(len(json.load(sys.stdin).get("entry",[])))')
[ "$insurer_vs" = "0" ] || fail "the insurer took a type it never declared, got $insurer_vs"

step "and the standard's own terminology is there by the same mechanism"
# --8<-- [start:core-terminology]
curl -s -H "Authorization: Bearer $HOSPITAL" "$HOGWARTS/CodeSystem/\$lookup?system=http://hl7.org/fhir/administrative-gender&code=female"
# --8<-- [end:core-terminology]
core=$(curl -s -H "Authorization: Bearer $HOSPITAL" "$HOGWARTS/CodeSystem/\$lookup?system=http://hl7.org/fhir/administrative-gender&code=female")
printf '%s' "$core" | grep -q 'Female' || fail "the core code system is not answerable: $core"

step "a face root is a tenant, and its definitions are records"
# --8<-- [start:face-roots]
curl -s -G -H "Authorization: Bearer $FACE_R5" \
    "http://localhost:8090/t/fhir-r5/fhir/StructureDefinition" \
    --data-urlencode "_summary=count" \
  | python3 -c "import sys,json;print('fhir-r5', json.load(sys.stdin)['total'])"

curl -s -G -H "Authorization: Bearer $FACE_R4" \
    "http://localhost:8090/t/fhir-r4/fhir/StructureDefinition" \
    --data-urlencode "_summary=count" \
  | python3 -c "import sys,json;print('fhir-r4', json.load(sys.stdin)['total'])"
# --8<-- [end:face-roots]
for pair in "fhir-r5 $FACE_R5" "fhir-r4 $FACE_R4"; do
    set -- $pair
    held=$(curl -sf -G -H "Authorization: Bearer $2" \
        "http://localhost:8090/t/$1/fhir/StructureDefinition" \
        --data-urlencode "_summary=count" \
      | python3 -c 'import sys,json;print(json.load(sys.stdin)["total"])')
    [ "$held" -gt 300 ] || fail "$1 should hold the version's definitions, got $held"
done

step "a projection converts the zone once, for the face that needs it"
for _ in $(seq 1 90); do
    curl -sf -o /dev/null "http://localhost:8090/t/rl-on-r4/fhir/metadata" && break
    sleep 10
done
# --8<-- [start:projection]
curl -s http://localhost:8090/t/rl-on-r4/fhir/metadata \
  | python3 -c 'import sys,json;print(json.load(sys.stdin)["fhirVersion"])'

curl -s http://localhost:8090/t/rl/fhir/metadata \
  | python3 -c 'import sys,json;print(json.load(sys.stdin)["fhirVersion"])'
# --8<-- [end:projection]
projected=$(curl -sf http://localhost:8090/t/rl-on-r4/fhir/metadata \
    | python3 -c 'import sys,json;print(json.load(sys.stdin)["fhirVersion"])')
[ "$projected" = "4.0.1" ] || fail "the projection should speak the face it serves, said $projected"
source_face=$(curl -sf http://localhost:8090/t/rl/fhir/metadata \
    | python3 -c 'import sys,json;print(json.load(sys.stdin)["fhirVersion"])')
[ "$source_face" = "5.0.0" ] || fail "the zone should still speak its own face, said $source_face"

step "the version that deleted a record is gone, not missing"
# --8<-- [start:vread-gone]
doomed=$(curl -sf -X POST -H "Authorization: Bearer $HOSPITAL" "$HOGWARTS/Patient" \
    -H 'Content-Type: application/fhir+json' \
    -d '{"resourceType":"Patient",
         "identifier":[{"system":"urn:rl:nid","value":"RL-0007"}],
         "name":[{"family":"Fleeting"}]}' \
    | python3 -c 'import sys,json;print(json.load(sys.stdin)["id"])')

curl -s -o /dev/null -w '%{http_code}\n' -X DELETE -H "Authorization: Bearer $HOSPITAL" "$HOGWARTS/Patient/$doomed"

curl -s -o /dev/null -w '%{http_code}\n' -H "Authorization: Bearer $HOSPITAL" "$HOGWARTS/Patient/$doomed/_history/2"

curl -s -o /dev/null -w '%{http_code}\n' -H "Authorization: Bearer $HOSPITAL" "$HOGWARTS/Patient/$doomed/_history/1"
# --8<-- [end:vread-gone]
tomb=$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $HOSPITAL" "$HOGWARTS/Patient/$doomed/_history/2")
[ "$tomb" = "410" ] || fail "the version that deleted it should be gone, got $tomb"
before_it=$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $HOSPITAL" "$HOGWARTS/Patient/$doomed/_history/1")
[ "$before_it" = "200" ] || fail "deleting took the history with it, got $before_it"

step "what the tenant holds, before anything moves"
# --8<-- [start:inventory]
ADMIN=http://localhost:8090/t/hogwarts/admin

curl -s -X POST -H "Authorization: Bearer $HOSPITAL" "$ADMIN/inventory" | python3 -c '
import sys, json
held = json.load(sys.stdin)["types"]
for one in held:
    if one["domain"] != "definitions":
        print("%-10s %-20s %s" % (one["domain"], one["name"], one["total"]))
print("definitions:", sum(o["total"] for o in held if o["domain"] == "definitions"))'
# --8<-- [end:inventory]
inventory=$(curl -sf -X POST -H "Authorization: Bearer $HOSPITAL" "$ADMIN/inventory" \
    | python3 -c 'import sys,json;print(len(json.load(sys.stdin)["types"]))')
[ "$inventory" -gt 3 ] || fail "the inventory should name what the tenant holds, got $inventory"

step "the archive is sealed under a key the store does not hold"
# --8<-- [start:archive-no-key]
curl -s -X POST -H "Authorization: Bearer $HOSPITAL" "$ADMIN/archive"
# --8<-- [end:archive-no-key]
nokey=$(curl -s -o /tmp/dbo-guide-nokey -w '%{http_code}' -X POST \
    -H "Authorization: Bearer $HOSPITAL" "$ADMIN/archive")
[ "$nokey" = "400" ] || fail "an archive with no owner key should be refused, got $nokey"
grep -q "does not hold" /tmp/dbo-guide-nokey \
    || fail "the refusal should say why: $(cat /tmp/dbo-guide-nokey)"

step "the whole tenant leaves as one file"
# --8<-- [start:archive]
OWNER_KEY=$(printf 'hogwarts-owns-this-key-32-bytes!' | base64 | tr -d '\n')

curl -s -X POST -H "Authorization: Bearer $HOSPITAL" \
    -H "X-Owner-Key: $OWNER_KEY" \
    "$ADMIN/archive" -o hogwarts.archive -w '%{http_code} %{size_download} bytes\n'
# --8<-- [end:archive]
[ -s hogwarts.archive ] || fail "the archive is empty"

step "and whoever stores it can read nothing in it"
# --8<-- [start:archive-opaque]
grep -c "Potter" hogwarts.archive || true
# --8<-- [end:archive-opaque]
if grep -q "Potter" hogwarts.archive; then
    fail "a name is legible in the archive, which is the one thing it must not be"
fi

step "coming back is a ceremony, and the store cannot perform it alone"
# --8<-- [start:import-needs-signatures]
curl -s -X POST -H "Authorization: Bearer $HOSPITAL" \
    -H "X-Owner-Key: $OWNER_KEY" \
    --data-binary @hogwarts.archive "$ADMIN/import"
# --8<-- [end:import-needs-signatures]
unsigned=$(curl -s -o /tmp/dbo-guide-unsigned -w '%{http_code}' -X POST \
    -H "Authorization: Bearer $HOSPITAL" -H "X-Owner-Key: $OWNER_KEY" \
    --data-binary @hogwarts.archive "$ADMIN/import")
[ "$unsigned" = "400" ] || fail "an unsigned import should be refused, got $unsigned"
grep -q "cannot sign for either of them" /tmp/dbo-guide-unsigned \
    || fail "the refusal should name what is missing: $(cat /tmp/dbo-guide-unsigned)"
rm -f hogwarts.archive

step "a credential that may act in work, and not read the tenant"
# --8<-- [start:worker-credential]
curl -s -o /dev/null -w '%{http_code}\n' -X POST \
    -H "Authorization: Bearer $HOSPITAL" \
    -H 'Content-Type: application/json' \
    http://localhost:8090/t/hogwarts/oidc/admin/clients \
    -d '{"client_id":"a-porter","secret":"porter-secret","scope":["work"]}'

PORTER=$(token hogwarts porter-secret a-porter)
# --8<-- [end:worker-credential]
[ -n "$PORTER" ] || fail "no token for the porter"

step "and that credential cannot read a record directly"
# --8<-- [start:worker-cannot-read]
curl -s -o /dev/null -w '%{http_code}\n' \
    -H "Authorization: Bearer $PORTER" "$HOGWARTS/Patient/$id"
# --8<-- [end:worker-cannot-read]
blocked=$(curl -s -o /dev/null -w '%{http_code}' \
    -H "Authorization: Bearer $PORTER" "$HOGWARTS/Patient/$id")
[ "$blocked" = "403" ] || [ "$blocked" = "401" ] \
    || fail "the work credential read a record directly, got $blocked"

step "a run of the step the hospital offers, over one patient"
# --8<-- [start:start-a-run]
curl -s -X POST -H "Authorization: Bearer $PORTER" \
    -H 'Content-Type: application/json' \
    http://localhost:8090/t/hogwarts/step/hogwarts.admission.admit \
    -d "{\"inputs\":{\"patient\":\"Patient/$id\"}}"
# --8<-- [end:start-a-run]
started=$(curl -sf -X POST -H "Authorization: Bearer $PORTER" \
    -H 'Content-Type: application/json' \
    http://localhost:8090/t/hogwarts/step/hogwarts.admission.admit \
    -d "{\"inputs\":{\"patient\":\"Patient/$id\"}}")
CONTEXT=http://localhost:8090$(printf '%s' "$started" \
    | python3 -c 'import sys,json;print(json.load(sys.stdin)["context"])')
[ -n "$CONTEXT" ] || fail "the run returned no context"

step "inside the run, the patient it was given"
# --8<-- [start:read-in-run]
curl -s -o /dev/null -w '%{http_code}\n' \
    -H "Authorization: Bearer $PORTER" "$CONTEXT/Patient/$id"
# --8<-- [end:read-in-run]
inside=$(curl -s -o /dev/null -w '%{http_code}' \
    -H "Authorization: Bearer $PORTER" "$CONTEXT/Patient/$id")
[ "$inside" = "200" ] || fail "the run could not read what it was given, got $inside"

step "and nothing else, whatever its type"
other=$(curl -sf -G -H "Authorization: Bearer $HOSPITAL" "$HOGWARTS/Patient" \
    --data-urlencode "identifier=urn:rl:nid|RL-90-Granger" \
    | python3 -c 'import sys,json;print(json.load(sys.stdin)["entry"][0]["resource"]["id"])')
# --8<-- [start:read-outside-reach]
curl -s -o /dev/null -w '%{http_code}\n' \
    -H "Authorization: Bearer $PORTER" "$CONTEXT/Patient/$other"

curl -s -o /dev/null -w '%{http_code}\n' \
    -H "Authorization: Bearer $PORTER" "$CONTEXT/Observation/$other"
# --8<-- [end:read-outside-reach]
withheld=$(curl -s -o /dev/null -w '%{http_code}' \
    -H "Authorization: Bearer $PORTER" "$CONTEXT/Patient/$other")
[ "$withheld" = "404" ] || fail "a run reached a patient it was never given, got $withheld"

step "the context says what it answers for"
# --8<-- [start:run-metadata]
curl -s -H "Authorization: Bearer $PORTER" "$CONTEXT/metadata" | python3 -c '
import sys, json
rest = json.load(sys.stdin)["rest"][0]
print(*[r["type"] for r in rest["resource"]], sep="\n")'
# --8<-- [end:run-metadata]
serves=$(curl -sf -H "Authorization: Bearer $PORTER" "$CONTEXT/metadata" \
    | python3 -c 'import sys,json;print(",".join(r["type"] for r in json.load(sys.stdin)["rest"][0]["resource"]))')
[ "$serves" = "Patient" ] || fail "the context advertises more than the step declared: $serves"

printf '\nguide: chapters one to ten work\n'
