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
source docs/guide/examples/snippets/up.sh
fi

source docs/guide/examples/snippets/bases.sh

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
source docs/guide/examples/snippets/token.sh
[ -n "$HOSPITAL" ] || fail "no token for the hospital"
[ -n "$INSURER" ] || fail "no token for the insurer"
[ -n "$JURISDICTION" ] || fail "no token for the zone"
FACE_R5=$(token fhir-r5 r5-secret)

# Two shapes of the token endpoint that the chapters show in place rather than
# as a helper, so they are written out here once for the assertions to use.
token_code() {
    curl -sf -X POST http://localhost:8090/t/hogwarts/oidc/token \
        -H 'Content-Type: application/x-www-form-urlencoded' \
        -d "grant_type=authorization_code&code=$1&redirect_uri=https%3A%2F%2Fward.example%2Fcb&client_id=ward-console&client_secret=console-secret" \
      | python3 -c 'import sys,json;print(json.load(sys.stdin)["access_token"])'
}
token_exchange() {
    local form="grant_type=urn:ietf:params:oauth:grant-type:token-exchange"
    form="$form&client_id=night-ledger&client_secret=ledger-secret"
    for part in "$@"; do form="$form&$part"; done
    curl -sf -X POST http://localhost:8090/t/hogwarts/oidc/token \
        -H 'Content-Type: application/x-www-form-urlencoded' -d "$form" \
      | python3 -c 'import sys,json;print(json.load(sys.stdin)["access_token"])'
}

FACE_R4=$(token fhir-r4 r4-secret)

step "and without one, the store says no"
source docs/guide/examples/snippets/no-token.sh
refused=$(curl -s -o /dev/null -w '%{http_code}' "$HOGWARTS/Patient")
[ "$refused" = "401" ] || fail "an unauthenticated read should be refused, got $refused"

step "the zone publishes terminology of its own"
source docs/guide/examples/snippets/zone-publishes.sh

step "and answers questions about it"
source docs/guide/examples/snippets/zone-lookup.sh
looked=$(curl -s -H "Authorization: Bearer $JURISDICTION" "$ZONE/CodeSystem/\$lookup?system=urn:rl:wards&code=dai")
printf '%s' "$looked" | grep -q 'Dai Llewellyn' || fail "the zone cannot look up its own code: $looked"

source docs/guide/examples/snippets/zone-expand.sh
expanded=$(curl -s -H "Authorization: Bearer $JURISDICTION" "$ZONE/ValueSet/\$expand?url=urn:rl:wards:vs" \
    | python3 -c 'import sys,json;print(json.load(sys.stdin)["expansion"]["total"])')
[ "$expanded" = "4" ] || fail "expected the value set to expand to 4 concepts, got $expanded"

step "asking each tenant which version it speaks"
source docs/guide/examples/snippets/versions.sh
h=$(curl -s -H "Authorization: Bearer $HOSPITAL" "$HOGWARTS/metadata" | python3 -c 'import sys,json;print(json.load(sys.stdin)["fhirVersion"])')
g=$(curl -s -H "Authorization: Bearer $INSURER" "$GRINGOTTS/metadata" | python3 -c 'import sys,json;print(json.load(sys.stdin)["fhirVersion"])')
[ "$h" = "5.0.0" ] || fail "hogwarts should speak 5.0.0, said $h"
[ "$g" = "4.0.1" ] || fail "gringotts should speak 4.0.1, said $g"

step "admitting a patient"
source docs/guide/examples/snippets/create.sh
[ -n "$id" ] || fail "the write returned no id"
echo "id=$id"
case "$id" in
    ????????-????-????-????-????????????) ;;
    *) fail "expected a uuid, got $id" ;;
esac

step "a readable id is refused"
source docs/guide/examples/snippets/readable-id.sh
refused=$(curl -s -o /dev/null -w '%{http_code}' -X PUT -H "Authorization: Bearer $HOSPITAL" "$HOGWARTS/Patient/harry" \
    -H 'Content-Type: application/fhir+json' -d '{"resourceType":"Patient","id":"harry"}')
[ "$refused" = "400" ] || fail "expected 400 for a readable id, got $refused"

step "finding him by the identifier the zone declares"
# --data-urlencode, because a token search carries a | and curl will not
# escape it for you.
source docs/guide/examples/snippets/search.sh
found=$(curl -sf -G -H "Authorization: Bearer $HOSPITAL" "$HOGWARTS/Patient" --data-urlencode "identifier=urn:rl:nid|RL-0001" \
    | python3 -c 'import sys,json;print(len(json.load(sys.stdin).get("entry",[])))')
[ "$found" = "1" ] || fail "expected one match, got $found"

step "changing him, and reading what he was"
source docs/guide/examples/snippets/history.sh
versions=$(curl -sf -H "Authorization: Bearer $HOSPITAL" "$HOGWARTS/Patient/$id/_history" \
    | python3 -c 'import sys,json;print(len(json.load(sys.stdin).get("entry",[])))')
[ "$versions" = "2" ] || fail "expected two versions, got $versions"

step "the same person at the insurer, which is a version behind"
source docs/guide/examples/snippets/insurer.sh
at_insurer=$(curl -sf -G -H "Authorization: Bearer $INSURER" "$GRINGOTTS/Patient" --data-urlencode "identifier=urn:rl:nid|RL-0001" \
    | python3 -c 'import sys,json;print(len(json.load(sys.stdin).get("entry",[])))')
[ "$at_insurer" = "1" ] || fail "expected the person at the insurer, got $at_insurer"

step "and the older version refuses what it does not have"
source docs/guide/examples/snippets/version-refusal.sh
outcome=$(curl -s -X POST -H "Authorization: Bearer $INSURER" "$GRINGOTTS/Coverage" \
    -H 'Content-Type: application/fhir+json' \
    -d '{"resourceType":"Coverage","status":"active","kind":"insurance","beneficiary":{"reference":"Patient/x"}}')
printf '%s' "$outcome" | grep -q '4.0.1' \
    || fail "the refusal should name the version it validated against: $outcome"
printf '%s' "$outcome" | grep -q 'OperationOutcome' || fail "expected an OperationOutcome"

step "a credential is for one tenant, and an id means nothing in another"
source docs/guide/examples/snippets/isolation.sh
wrong_tenant=$(curl -s -o /dev/null -w '%{http_code}' \
    -H "Authorization: Bearer $HOSPITAL" "$GRINGOTTS/Patient/$id")
[ "$wrong_tenant" = "401" ] \
    || fail "the hospital's credential was admitted by the insurer, got $wrong_tenant"
unknown_here=$(curl -s -o /dev/null -w '%{http_code}' \
    -H "Authorization: Bearer $INSURER" "$GRINGOTTS/Patient/$id")
[ "$unknown_here" = "404" ] \
    || fail "an id from another tenant resolved here, got $unknown_here"

step "the same person twice is refused, not duplicated"
source docs/guide/examples/snippets/duplicate-identity.sh
dup=$(curl -s -o /dev/null -w '%{http_code}' -X POST -H "Authorization: Bearer $HOSPITAL" "$HOGWARTS/Patient" \
    -H 'Content-Type: application/fhir+json' \
    -d '{"resourceType":"Patient","identifier":[{"system":"urn:rl:nid","value":"RL-0001"}],
         "name":[{"family":"Potter","given":["Harry","James"]}]}')
[ "$dup" = "409" ] || fail "expected 409 for a second create of one identity, got $dup"
count=$(curl -sf -G -H "Authorization: Bearer $HOSPITAL" "$HOGWARTS/Patient" --data-urlencode "identifier=urn:rl:nid|RL-0001" \
    | python3 -c 'import sys,json;print(len(json.load(sys.stdin).get("entry",[])))')
[ "$count" = "1" ] || fail "expected one record for one identity, got $count"

step "updating by identity rather than by id"
source docs/guide/examples/snippets/conditional-update.sh
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
source docs/guide/examples/snippets/vread.sh
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

step "a type declared replicated is not writable here"
source docs/guide/examples/snippets/replicated-refused.sh
readonly_here=$(curl -s -o /tmp/dbo-guide-readonly -w '%{http_code}' -X POST \
    -H "Authorization: Bearer $HOSPITAL" "$HOGWARTS/CodeSystem" \
    -H 'Content-Type: application/fhir+json' \
    -d '{"resourceType":"CodeSystem","url":"urn:hogwarts:local","version":"1",
         "status":"active","content":"complete","concept":[{"code":"x","display":"Local"}]}')
[ "$readonly_here" = "403" ] \
    || fail "writing a replicated type should be refused, got $readonly_here"
grep -q "read-only-here" /tmp/dbo-guide-readonly \
    || fail "the refusal should name the rule: $(cat /tmp/dbo-guide-readonly)"

step "several writes as one act"
source docs/guide/examples/snippets/transaction.sh
bones=$(curl -sf -G -H "Authorization: Bearer $HOSPITAL" "$HOGWARTS/Patient" \
    --data-urlencode "identifier=urn:rl:nid|RL-0009" \
    | python3 -c 'import sys,json;print(json.load(sys.stdin)["entry"][0]["resource"]["id"])')
[ -n "$bones" ] || fail "the transaction did not land its patient"

step "and the reference between them resolved to what was created"
source docs/guide/examples/snippets/transaction-reference.sh
pointed=$(curl -sf -G -H "Authorization: Bearer $HOSPITAL" "$HOGWARTS/Observation" \
    --data-urlencode "_count=50" | python3 -c '
import sys, json
for entry in json.load(sys.stdin).get("entry", []):
    resource = entry["resource"]
    if resource.get("code", {}).get("text") == "height":
        print(resource["subject"]["reference"])')
[ "$pointed" = "Patient/$bones" ] \
    || fail "the placeholder did not resolve to the created patient: $pointed"

step "one bad entry takes the whole transaction with it"
source docs/guide/examples/snippets/transaction-refused.sh
rolled=$(curl -sf -G -H "Authorization: Bearer $HOSPITAL" "$HOGWARTS/Patient" \
    --data-urlencode "identifier=urn:rl:nid|RL-0010" \
    | python3 -c 'import sys,json;print(len(json.load(sys.stdin).get("entry",[])))')
[ "$rolled" = "0" ] || fail "a refused transaction left a record behind"

step "a batch answers for each entry separately"
source docs/guide/examples/snippets/batch.sh
kept=$(curl -sf -G -H "Authorization: Bearer $HOSPITAL" "$HOGWARTS/Patient" \
    --data-urlencode "identifier=urn:rl:nid|RL-0011" \
    | python3 -c 'import sys,json;print(len(json.load(sys.stdin).get("entry",[])))')
[ "$kept" = "1" ] || fail "a batch's good entry did not land, got $kept"

step "what belongs to a record is found by the reference to it"
source docs/guide/examples/snippets/reference-search.sh
belonging=$(curl -sf -G -H "Authorization: Bearer $HOSPITAL" "$HOGWARTS/Observation" \
    --data-urlencode "subject=Patient/$bones" \
    | python3 -c 'import sys,json;print(len(json.load(sys.stdin).get("entry",[])))')
[ "$belonging" = "1" ] || fail "the reference did not find what belongs to the record, got $belonging"

step "a reference to something this store does not hold is kept, not refused"
source docs/guide/examples/snippets/reference-unheld.sh
elsewhere=$(curl -s -o /dev/null -w '%{http_code}' -X POST \
    -H "Authorization: Bearer $HOSPITAL" "$HOGWARTS/Observation" \
    -H 'Content-Type: application/fhir+json' \
    -d '{"resourceType":"Observation","status":"final","code":{"text":"referred elsewhere"},
         "subject":{"reference":"Patient/01a00000-0000-7000-8000-00000000dead"}}')
[ "$elsewhere" = "201" ] \
    || fail "a reference out of this store should be kept, got $elsewhere"

step "a write made against a version that has moved is refused"
stale=$(curl -sf -X POST -H "Authorization: Bearer $HOSPITAL" "$HOGWARTS/Patient" \
    -H 'Content-Type: application/fhir+json' \
    -d '{"resourceType":"Patient","identifier":[{"system":"urn:rl:nid","value":"RL-0008"}],
         "name":[{"family":"Prewett"}]}' \
    | python3 -c 'import sys,json;print(json.load(sys.stdin)["id"])')
# Somebody else gets there first, which is the case this exists for.
curl -sf -o /dev/null -X PUT -H "Authorization: Bearer $HOSPITAL" \
    "$HOGWARTS/Patient/$stale" -H 'Content-Type: application/fhir+json' \
    -d "{\"resourceType\":\"Patient\",\"id\":\"$stale\",
         \"identifier\":[{\"system\":\"urn:rl:nid\",\"value\":\"RL-0008\"}],
         \"name\":[{\"family\":\"Prewett\",\"given\":[\"Molly\"]}]}"
source docs/guide/examples/snippets/stale-write.sh
refused_stale=$(curl -s -o /dev/null -w '%{http_code}' -X PUT \
    -H "Authorization: Bearer $HOSPITAL" -H 'If-Match: W/"1"' \
    "$HOGWARTS/Patient/$stale" -H 'Content-Type: application/fhir+json' \
    -d "{\"resourceType\":\"Patient\",\"id\":\"$stale\",
         \"identifier\":[{\"system\":\"urn:rl:nid\",\"value\":\"RL-0008\"}],
         \"name\":[{\"family\":\"Prewett\"}]}")
[ "$refused_stale" = "412" ] \
    || fail "a write against a version that has moved should be refused, got $refused_stale"

step "a definition is identified by its url, so writing it twice replaces it"
source docs/guide/examples/snippets/canonical.sh
held=$(curl -sf -G -H "Authorization: Bearer $JURISDICTION" "$ZONE/CodeSystem" --data-urlencode "url=urn:rl:houses" \
    | python3 -c 'import sys,json;d=json.load(sys.stdin);e=d.get("entry",[]);print(str(len(e))+":"+(e[0]["resource"]["meta"]["versionId"] if e else "-"))')
[ "$held" = "1:2" ] || fail "expected one code system at version 2, got $held"

step "a type the tenant never declared is refused"
# The hospital declared Observation. The insurer did not — it holds Patient
# and Coverage — so the same request is answered differently by each.
source docs/guide/examples/snippets/undeclared-type.sh
undeclared=$(curl -s -o /dev/null -w '%{http_code}' -X POST -H "Authorization: Bearer $INSURER" "$GRINGOTTS/Observation" \
    -H 'Content-Type: application/fhir+json' \
    -d '{"resourceType":"Observation","status":"final","code":{"text":"house points"}}')
[ "$undeclared" = "404" ] || fail "expected 404 for an undeclared type, got $undeclared"

step "a tenant appears when its spec does"
source docs/guide/examples/snippets/add-tenant.sh
trap 'rm -f "$PWD/docs/guide/world/tenants/stmungos.json"; cleanup' EXIT
for _ in $(seq 1 60); do
    if curl -sf -o /dev/null "http://localhost:8090/t/stmungos/fhir/metadata"; then break; fi
    sleep 5
done
source docs/guide/examples/snippets/new-tenant-serves.sh
curl -sf -o /dev/null "http://localhost:8090/t/stmungos/fhir/metadata" \
    || fail "stmungos never came up"

step "changing the declaration rebuilds the tenant where it stands"
source docs/guide/examples/snippets/change-in-place.sh
for _ in $(seq 1 60); do
    serving=$(curl -s http://localhost:8090/t/stmungos/fhir/metadata \
        | python3 -c '
import sys, json
try:
    print(",".join(r["type"] for r in json.load(sys.stdin)["rest"][0]["resource"]))
except Exception:
    print("")' 2>/dev/null)
    case "$serving" in
        "") sleep 3 ;;
        *Observation*) sleep 3 ;;
        *Patient*) break ;;
        *) sleep 3 ;;
    esac
done
source docs/guide/examples/snippets/change-took.sh
took=$(curl -sf http://localhost:8090/t/stmungos/fhir/metadata | python3 -c '
import sys, json
served = [r["type"] for r in json.load(sys.stdin)["rest"][0]["resource"]]
print("Patient" in served, "Observation" in served)')
[ "$took" = "True False" ] \
    || fail "the narrowed declaration did not take where it stood: $took"

step "and stops when its spec goes"
source docs/guide/examples/snippets/remove-tenant.sh
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
source docs/guide/examples/snippets/capability-search.sh
declared=$(curl -s -H "Authorization: Bearer $HOSPITAL" "$HOGWARTS/metadata" | python3 -c '
import sys, json
rest = json.load(sys.stdin)["rest"][0]
for resource in rest["resource"]:
    if resource["type"] == "Patient":
        print(len(resource["searchParam"]))')
[ "$declared" -gt 10 ] || fail "expected the capability statement to declare Patient search parameters, got $declared"

step "a modifier the parameter does not have is refused by name"
source docs/guide/examples/snippets/unknown-modifier.sh
modifier=$(curl -s -G -H "Authorization: Bearer $HOSPITAL" "$HOGWARTS/Patient" --data-urlencode "family:nosuch=Granger")
printf '%s' "$modifier" | grep -q "family:nosuch" \
    || fail "the refusal should name what it refused: $modifier"

step "counting without fetching"
source docs/guide/examples/snippets/count.sh
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
source docs/guide/examples/snippets/first-page.sh
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
source docs/guide/examples/snippets/next-page.sh
page2=$(curl -sf -H "Authorization: Bearer $HOSPITAL" \
    "$(printf '%s' "$next" | sed 's|http://0.0.0.0:8090|http://localhost:8090|')")
second=$(printf '%s' "$page2" | python3 -c 'import sys,json;print(" ".join(e["resource"]["id"] for e in json.load(sys.stdin).get("entry",[])))')
python3 - "$first" "$second" <<'OVERLAP' || fail "a page repeated a record the previous page already returned"
import sys
a, b = set(sys.argv[1].split()), set(sys.argv[2].split())
sys.exit(1 if a & b else 0)
OVERLAP

step "an unsupported search parameter is refused, not ignored"
source docs/guide/examples/snippets/strict-search.sh
code=$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $HOSPITAL" "$HOGWARTS/Patient?favourite-colour=blue")
[ "$code" = "400" ] || fail "expected 400 for an unknown parameter, got $code"

step "an element the face does not define is refused, not quietly kept"
source docs/guide/examples/snippets/unknown-element.sh
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
source docs/guide/examples/snippets/validate-binding.sh
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
source docs/guide/examples/snippets/validate-shape.sh
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
source docs/guide/examples/snippets/validate-versions.sh
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
source docs/guide/examples/snippets/validate-ahead.sh
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
source docs/guide/examples/snippets/zone-reaches-hospital.sh
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
source docs/guide/examples/snippets/zone-partial-at-insurer.sh
insurer_code=$(curl -s -H "Authorization: Bearer $INSURER" "$GRINGOTTS/CodeSystem/\$lookup?system=urn:rl:wards&code=spell")
printf '%s' "$insurer_code" | grep -q 'Spell Damage' \
    || fail "the insurer declared CodeSystem from the zone and did not get it: $insurer_code"
insurer_vs=$(curl -sf -G -H "Authorization: Bearer $INSURER" "$GRINGOTTS/ValueSet" --data-urlencode "url=urn:rl:wards:vs" \
    | python3 -c 'import sys,json;print(len(json.load(sys.stdin).get("entry",[])))')
[ "$insurer_vs" = "0" ] || fail "the insurer took a type it never declared, got $insurer_vs"

step "and the standard's own terminology is there by the same mechanism"
source docs/guide/examples/snippets/core-terminology.sh
core=$(curl -s -H "Authorization: Bearer $HOSPITAL" "$HOGWARTS/CodeSystem/\$lookup?system=http://hl7.org/fhir/administrative-gender&code=female")
printf '%s' "$core" | grep -q 'Female' || fail "the core code system is not answerable: $core"

step "a face root is a tenant, and its definitions are records"
source docs/guide/examples/snippets/face-roots.sh
for pair in "fhir-r5 $FACE_R5" "fhir-r4 $FACE_R4"; do
    set -- $pair
    held=$(curl -sf -G -H "Authorization: Bearer $2" \
        "http://localhost:8090/t/$1/fhir/StructureDefinition" \
        --data-urlencode "_summary=count" \
      | python3 -c 'import sys,json;print(json.load(sys.stdin)["total"])')
    [ "$held" -gt 300 ] || fail "$1 should hold the version's definitions, got $held"
done

step "the zone runs the ceremony its members federate to"
source docs/guide/examples/snippets/zone-ceremony.sh
ceremony=$(curl -s -o /dev/null -w '%{http_code}' http://localhost:8090/z/rl/hub/jwks.json)
[ "$ceremony" = "200" ] || fail "the zone has no ceremony of its own, got $ceremony"

step "a projection converts the zone once, for the face that needs it"
for _ in $(seq 1 90); do
    curl -sf -o /dev/null "http://localhost:8090/t/rl-on-r4/fhir/metadata" && break
    sleep 10
done
source docs/guide/examples/snippets/projection.sh
projected=$(curl -sf http://localhost:8090/t/rl-on-r4/fhir/metadata \
    | python3 -c 'import sys,json;print(json.load(sys.stdin)["fhirVersion"])')
[ "$projected" = "4.0.1" ] || fail "the projection should speak the face it serves, said $projected"
source_face=$(curl -sf http://localhost:8090/t/rl/fhir/metadata \
    | python3 -c 'import sys,json;print(json.load(sys.stdin)["fhirVersion"])')
[ "$source_face" = "5.0.0" ] || fail "the zone should still speak its own face, said $source_face"

step "the version that deleted a record is gone, not missing"
source docs/guide/examples/snippets/vread-gone.sh
tomb=$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $HOSPITAL" "$HOGWARTS/Patient/$doomed/_history/2")
[ "$tomb" = "410" ] || fail "the version that deleted it should be gone, got $tomb"
before_it=$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $HOSPITAL" "$HOGWARTS/Patient/$doomed/_history/1")
[ "$before_it" = "200" ] || fail "deleting took the history with it, got $before_it"

step "the hospital is an organisation, with people and what they may do"
source docs/guide/examples/snippets/org-and-people.sh
org=$(curl -sf -G -H "Authorization: Bearer $HOSPITAL" "$HOGWARTS/Organization" \
    --data-urlencode "identifier=urn:rl:org|hogwarts" \
    | python3 -c 'import sys,json;print(json.load(sys.stdin)["entry"][0]["resource"]["id"])')
matron=$(curl -sf -G -H "Authorization: Bearer $HOSPITAL" "$HOGWARTS/Practitioner" \
    --data-urlencode "identifier=urn:rl:nid|RL-POMFREY" \
    | python3 -c 'import sys,json;print(json.load(sys.stdin)["entry"][0]["resource"]["id"])')
[ -n "$org" ] && [ -n "$matron" ] || fail "the organisation or the practitioner is missing"

step "a role is a record, not a column"
source docs/guide/examples/snippets/the-role.sh
# Counted from the entries rather than read from "total": a searchset that
# matched nothing does not carry one, so reading it turns an assertion that
# should fail into a traceback that says nothing.
roles=$(curl -sf -G -H "Authorization: Bearer $HOSPITAL" "$HOGWARTS/PractitionerRole" \
    | python3 -c 'import sys,json;print(len(json.load(sys.stdin).get("entry",[])))')
[ "$roles" -gt 0 ] || fail "the role did not land"

step "and what that role may do is declared, and readable"
source docs/guide/examples/snippets/role-grant.sh
granted=$(curl -sf -H "Authorization: Bearer $HOSPITAL" \
    http://localhost:8090/t/hogwarts/oidc/admin/role-grants \
    | python3 -c 'import sys,json;print(json.load(sys.stdin)["grants"][0]["role"])')
[ "$granted" = "matron" ] || fail "the tenant does not say what it grants, got $granted"

step "a person signs in, and the store works out what she is here"
source docs/guide/examples/snippets/a-person-signs-in.sh
person=$(curl -sf -G -H "Authorization: Bearer $HOSPITAL" "$HOGWARTS/Person" \
    --data-urlencode "identifier=urn:rl:nid|RL-POMFREY" \
    | python3 -c 'import sys,json;print(json.load(sys.stdin)["entry"][0]["resource"]["id"])')
[ -n "$person" ] || fail "the person was not written"

source docs/guide/examples/snippets/her-credential.sh

# The authorization code arrives where a browser would be sent, so the
# redirect is read rather than followed.
code=$(curl -s -o /dev/null -D - -X POST \
    http://localhost:8090/t/hogwarts/oidc/authorize/login \
    -H 'Content-Type: application/x-www-form-urlencoded' \
    -d "client_id=ward-console&redirect_uri=https%3A%2F%2Fward.example%2Fcb&login=pomfrey&password=a-strong-secret" \
    | sed -n 's/.*[?&]code=\([^&[:space:]]*\).*/\1/p' | tr -d '\r')
[ -n "$code" ] || fail "signing in produced no authorization code"
HUMAN=$(token_code "$code")
[ -n "$HUMAN" ] || fail "the code did not exchange for a token"

source docs/guide/examples/snippets/who-she-is.sh
echo "$HUMAN" | grep -q . || fail "no human token"
capacity=$(claims "$HUMAN" | awk '$1=="fhirUser"{print $2}')
[ "$capacity" = "Practitioner/$matron" ] \
    || fail "the token names the wrong capacity: $capacity"

step "a process acts in her name, and carries both names"
source docs/guide/examples/snippets/acting-for-her.sh
actor=$(claims "$ACT" | awk '$1=="act"{print $2}')
[ "$actor" = "night-ledger" ] || fail "the delegated token does not name the actor"
onbehalf=$(claims "$ACT" | awk '$1=="sub"{print $2}')
[ "$onbehalf" = "$person" ] || fail "the delegated token lost the person"

step "and cannot acquire authority she never had"
source docs/guide/examples/snippets/attenuation.sh
widened=$(curl -s -X POST http://localhost:8090/t/hogwarts/oidc/token \
    -H 'Content-Type: application/x-www-form-urlencoded' \
    -d "grant_type=urn:ietf:params:oauth:grant-type:token-exchange&subject_token=$HUMAN&client_id=night-ledger&client_secret=ledger-secret&scope=user%2FPatient.write" \
    | python3 -c 'import sys,json;print(json.load(sys.stdin).get("error",""))')
[ "$widened" = "access_denied" ] \
    || fail "a delegated token widened past its subject, got $widened"

step "work that outlives the token holds a delegation"
source docs/guide/examples/snippets/a-delegation.sh
delegation=$(curl -sf -X POST http://localhost:8090/t/hogwarts/oidc/delegation \
    -H "Authorization: Bearer $HUMAN" \
    -H 'Content-Type: application/x-www-form-urlencoded' \
    -d "client_id=night-ledger&scope=user/Patient.read&valid_until=$(( $(date +%s) + 86400 ))" \
    | python3 -c 'import sys,json;print(json.load(sys.stdin)["delegation_id"])')
[ -n "$delegation" ] || fail "no delegation was recorded"

source docs/guide/examples/snippets/exchange-a-delegation.sh
still=$(claims "$LEDGER" | awk '$1=="sub"{print $2}')
[ "$still" = "$person" ] || fail "the delegation lost the person it was granted by"

step "and ending it stops the next exchange"
source docs/guide/examples/snippets/ending-a-delegation.sh
ended=$(curl -s -X POST http://localhost:8090/t/hogwarts/oidc/token \
    -H 'Content-Type: application/x-www-form-urlencoded' \
    -d "grant_type=urn:ietf:params:oauth:grant-type:token-exchange&delegation_id=$delegation&client_id=night-ledger&client_secret=ledger-secret" \
    | python3 -c 'import sys,json;print(json.load(sys.stdin).get("error",""))')
[ "$ended" = "invalid_grant" ] || fail "an ended delegation still exchanged, got $ended"


step "every tenant issues its own tokens"
source docs/guide/examples/snippets/issuer.sh
own=$(curl -sf http://localhost:8090/t/hogwarts/oidc/.well-known/openid-configuration \
    | python3 -c 'import sys,json;print(json.load(sys.stdin)["issuer"])')
case "$own" in *"/t/hogwarts/oidc") ;; *) fail "the tenant's issuer is not its own: $own" ;; esac

step "the trail records the act, and who did it"
source docs/guide/examples/snippets/trail.sh
# Asserted by the REFERENCE, because that is the form the entry hands back and
# the form a reader copies. It was matched against the id alone, so asking the
# way the answer is written returned an empty bundle — which reads as nothing
# happened to this record.
recorded=$(curl -sf -G -H "Authorization: Bearer $HOSPITAL" "$HOGWARTS/AuditEvent" \
    --data-urlencode "entity=Patient/$id" --data-urlencode "action=C" \
    --data-urlencode "_count=1" \
    | python3 -c 'import sys,json;print(len(json.load(sys.stdin).get("entry",[])))')
[ "$recorded" = "1" ] \
    || fail "the trail cannot be asked about the record it names, got $recorded"

step "and the trail is searched the way it is asked about"
source docs/guide/examples/snippets/trail-search.sh
by_agent=$(curl -sf -G -H "Authorization: Bearer $HOSPITAL" "$HOGWARTS/AuditEvent" \
    --data-urlencode "agent=tenant-bootstrap" \
    | python3 -c 'import sys,json;print(len(json.load(sys.stdin).get("entry",[])))')
[ "$by_agent" -gt 0 ] || fail "the trail cannot be searched by who acted"

step "what the tenant holds, before anything moves"
source docs/guide/examples/snippets/inventory.sh
inventory=$(curl -sf -X POST -H "Authorization: Bearer $HOSPITAL" "$ADMIN/inventory" \
    | python3 -c 'import sys,json;print(len(json.load(sys.stdin)["types"]))')
[ "$inventory" -gt 3 ] || fail "the inventory should name what the tenant holds, got $inventory"

step "the streams a tenant carries, one per domain"
source docs/guide/examples/snippets/feed-domains.sh
domains=$(curl -sf -X POST -H "Authorization: Bearer $HOSPITAL" "$ADMIN/inventory" \
    | python3 -c 'import sys,json;print(",".join(sorted({o["domain"] for o in json.load(sys.stdin)["types"]})))')
case "$domains" in
    *audit*) ;;
    *) fail "the tenant reports no audit domain: $domains" ;;
esac
case "$domains" in
    *work*) ;;
    *) fail "the tenant reports no work domain: $domains" ;;
esac

step "and what is reading them, with how far behind it is"
source docs/guide/examples/snippets/feed-consumers.sh
reading=$(curl -sf -X POST -H "Authorization: Bearer $HOSPITAL" "$ADMIN/inventory" \
    | python3 -c '
import sys, json
rows = json.load(sys.stdin)["delivery"]
# A consumer is a name and a position, so a row without either is not one.
print(all("consumer" in r and "lag" in r and "domain" in r for r in rows) and len(rows) > 0)')
[ "$reading" = "True" ] \
    || fail "the tenant does not say what is reading it, or says it without a position"

step "content a tenant holds whole"
source docs/guide/examples/snippets/blob-write.sh
echo
blob=$(curl -sf -X POST -H "Authorization: Bearer $HOSPITAL" \
    -H 'Content-Type: application/pdf' \
    --data-binary 'PDF-ish bytes' "$BLOBS" \
    | python3 -c 'import sys,json;print(json.load(sys.stdin)["key"])')
[ -n "$blob" ] || fail "the blob was not written"

step "handed back as it was given"
source docs/guide/examples/snippets/blob-read.sh
kind=$(curl -sf -D - -o /dev/null -H "Authorization: Bearer $HOSPITAL" "$BLOBS/$blob" \
    | grep -i '^content-type' | tr -d '\r' | awk '{print $2}')
[ "$kind" = "application/pdf" ] \
    || fail "the media type the writer declared did not come back: $kind"
back=$(curl -sf -H "Authorization: Bearer $HOSPITAL" "$BLOBS/$blob")
[ "$back" = "PDF-ish bytes" ] || fail "the bytes came back changed"

step "and it is guarded by the grant that covers binary content"
source docs/guide/examples/snippets/blob-unheld.sh
unheld=$(curl -s -o /dev/null -w '%{http_code}' "$BLOBS/$blob")
[ "$unheld" = "401" ] || fail "a blob was served without a credential, got $unheld"
absent=$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $HOSPITAL" \
    "$BLOBS/01a00000-0000-7000-8000-00000000dead")
[ "$absent" = "404" ] || fail "a blob that is not there answered $absent"



step "the archive is sealed under a key the store does not hold"
source docs/guide/examples/snippets/archive-no-key.sh
nokey=$(curl -s -o /tmp/dbo-guide-nokey -w '%{http_code}' -X POST \
    -H "Authorization: Bearer $HOSPITAL" "$ADMIN/archive")
[ "$nokey" = "400" ] || fail "an archive with no owner key should be refused, got $nokey"
grep -q "does not hold" /tmp/dbo-guide-nokey \
    || fail "the refusal should say why: $(cat /tmp/dbo-guide-nokey)"

step "the whole tenant leaves as one file"
source docs/guide/examples/snippets/archive.sh
[ -s hogwarts.archive ] || fail "the archive is empty"

step "and whoever stores it can read nothing in it"
source docs/guide/examples/snippets/archive-opaque.sh
if grep -q "Potter" hogwarts.archive; then
    fail "a name is legible in the archive, which is the one thing it must not be"
fi

step "coming back is a ceremony, and the store cannot perform it alone"
source docs/guide/examples/snippets/import-needs-signatures.sh
unsigned=$(curl -s -o /tmp/dbo-guide-unsigned -w '%{http_code}' -X POST \
    -H "Authorization: Bearer $HOSPITAL" -H "X-Owner-Key: $OWNER_KEY" \
    --data-binary @hogwarts.archive "$ADMIN/import")
[ "$unsigned" = "400" ] || fail "an unsigned import should be refused, got $unsigned"
grep -q "cannot sign for either of them" /tmp/dbo-guide-unsigned \
    || fail "the refusal should name what is missing: $(cat /tmp/dbo-guide-unsigned)"
rm -f hogwarts.archive

step "a credential that may act in work, and not read the tenant"
source docs/guide/examples/snippets/worker-credential.sh
[ -n "$PORTER" ] || fail "no token for the porter"

step "and that credential cannot read a record directly"
source docs/guide/examples/snippets/worker-cannot-read.sh
blocked=$(curl -s -o /dev/null -w '%{http_code}' \
    -H "Authorization: Bearer $PORTER" "$HOGWARTS/Patient/$id")
[ "$blocked" = "403" ] || [ "$blocked" = "401" ] \
    || fail "the work credential read a record directly, got $blocked"

step "a run of the step the hospital offers, over one patient"
source docs/guide/examples/snippets/start-a-run.sh
started=$(curl -sf -X POST -H "Authorization: Bearer $PORTER" \
    -H 'Content-Type: application/json' \
    http://localhost:8090/t/hogwarts/step/hogwarts.admission.admit \
    -d "{\"inputs\":{\"patient\":\"Patient/$id\"}}")
CONTEXT=http://localhost:8090$(printf '%s' "$started" \
    | python3 -c 'import sys,json;print(json.load(sys.stdin)["context"])')
[ -n "$CONTEXT" ] || fail "the run returned no context"
run=$(printf '%s' "$started" \
    | python3 -c 'import sys,json;print(json.load(sys.stdin)["run"])')
[ -n "$run" ] || fail "the run returned no id"

step "inside the run, the patient it was given"
source docs/guide/examples/snippets/read-in-run.sh
inside=$(curl -s -o /dev/null -w '%{http_code}' \
    -H "Authorization: Bearer $PORTER" "$CONTEXT/Patient/$id")
[ "$inside" = "200" ] || fail "the run could not read what it was given, got $inside"

step "and nothing else, whatever its type"
other=$(curl -sf -G -H "Authorization: Bearer $HOSPITAL" "$HOGWARTS/Patient" \
    --data-urlencode "identifier=urn:rl:nid|RL-90-Granger" \
    | python3 -c 'import sys,json;print(json.load(sys.stdin)["entry"][0]["resource"]["id"])')
source docs/guide/examples/snippets/read-outside-reach.sh
withheld=$(curl -s -o /dev/null -w '%{http_code}' \
    -H "Authorization: Bearer $PORTER" "$CONTEXT/Patient/$other")
[ "$withheld" = "404" ] || fail "a run reached a patient it was never given, got $withheld"

step "the context says what it answers for"
source docs/guide/examples/snippets/run-metadata.sh
serves=$(curl -sf -H "Authorization: Bearer $PORTER" "$CONTEXT/metadata" \
    | python3 -c 'import sys,json;print(",".join(r["type"] for r in json.load(sys.stdin)["rest"][0]["resource"]))')
[ "$serves" = "Patient" ] || fail "the context advertises more than the step declared: $serves"

step "the run is a record, and it says what it is over and who holds it"
source docs/guide/examples/snippets/run-as-a-record.sh
record=$(curl -sf -H "Authorization: Bearer $HOSPITAL" "$HOGWARTS/Task/$run" \
    | python3 -c 'import sys,json;print(json.dumps(json.load(sys.stdin)["entry"][0]["resource"]))')
echo "$record" | grep -q '"urn:dbo:run:input"' \
    || fail "the run record does not carry the slot it was filled with"
# The reference is DISPLAYED and not resolvable, which is the whole point of
# the envelope: a run says what state it is in without disclosing its subject
# to whoever may read runs.
echo "$record" | python3 -c '
import sys, json
ref = json.load(sys.stdin)["input"][0]["valueReference"]
raise SystemExit(0 if "reference" not in ref and "display" in ref else 1)' \
    || fail "the run envelope resolved its subject instead of displaying it"

step "a runner asks the lane for work, and is told what it may have"
source docs/guide/examples/snippets/lane-poll.sh
echo
polled=$(curl -sf -X POST -H "Authorization: Bearer $HOSPITAL" \
    -H 'Content-Type: application/json' \
    http://localhost:8090/t/hogwarts/work/poll \
    -d "{\"participant\":\"ward-runner\",\"identity\":$RUNNER,
         \"steps\":[\"hogwarts.admission.admit\"],\"limit\":5}" \
    | python3 -c 'import sys,json;print("result" in json.load(sys.stdin))')
[ "$polled" = "True" ] || fail "the lane did not answer a poll"

step "and a refusal on the lane says why, rather than going quiet"
source docs/guide/examples/snippets/lane-refuses.sh
echo
for verb in rummage poll; do
    reason=$(curl -s -X POST -H "Authorization: Bearer $HOSPITAL" \
        -H 'Content-Type: application/json' \
        "http://localhost:8090/t/hogwarts/work/$verb" -d '{}' \
        | python3 -c 'import sys,json;d=json.load(sys.stdin);print(d.get("reason","")!="" and d.get("refused") is True)')
    [ "$reason" = "True" ] || fail "the lane refused $verb without saying why"
done

step "and what an operator with the database sees instead"
# The published form names the guide's own compose file, because that is what a
# reader types. A candidate world is reached by the same verb on the file
# DBO_GUIDE_COMPOSE names — the branch exists so the literal below can stay
# literal, exactly as the one that starts the world does.
if [ -n "${DBO_GUIDE_COMPOSE:-}" ]; then
    $COMPOSE exec -T db psql -U postgres -d tenant_hogwarts -tAc \
        "SELECT convert_from(payload,'UTF8') FROM state.r5_data WHERE type='Patient' LIMIT 1" \
      | python3 -c '
import sys, json
stored = json.loads(sys.stdin.read())
print(*sorted(stored), sep="\n")'
else
source docs/guide/examples/snippets/pdi-ciphertext.sh
fi
stored=$($COMPOSE exec -T db psql -U postgres -d tenant_hogwarts -tAc \
    "SELECT convert_from(payload,'UTF8') FROM state.r5_data WHERE type='Patient' LIMIT 1" \
  | python3 -c '
import sys, json
print(",".join(sorted(json.loads(sys.stdin.read()))))')
case "$stored" in
    *name*|*identifier*) fail "the stored payload carries identifying elements: $stored" ;;
esac
case "$stored" in
    *__pdiEnc*) ;;
    *) fail "the stored payload carries no ciphertext: $stored" ;;
esac

step "asking by name is refused, not answered empty"
source docs/guide/examples/snippets/pdi-name-search.sh
byName=$(curl -s -o /dev/null -w '%{http_code}' -G -H "Authorization: Bearer $HOSPITAL" \
    "$HOGWARTS/Patient" --data-urlencode "family=Potter")
[ "$byName" = "403" ] \
    || fail "a name search under the membrane answered $byName; an empty bundle would have said nobody is called that"

step "and an identifying lookup without a stated reason is refused too"
source docs/guide/examples/snippets/pdi-no-purpose.sh
unstated=$(curl -s -o /dev/null -w '%{http_code}' -G -H "Authorization: Bearer $NO_REASON" \
    "$HOGWARTS/Patient" --data-urlencode "identifier=urn:rl:nid|RL-0001")
[ "$unstated" = "403" ] \
    || fail "a person was resolved without a stated purpose, got $unstated"

step "the directory provisions a person, and the capacity comes with them"
source docs/guide/examples/snippets/scim-create.sh
provisioned=$(curl -sf -G -H "Authorization: Bearer $DIRECTORY" "$SCIM/Users" \
    --data-urlencode 'filter=userName eq "mmcgonagall"' \
    | python3 -c 'import sys,json;print(json.load(sys.stdin)["totalResults"])')
[ "$provisioned" = "1" ] || fail "the directory did not provision the person, got $provisioned"

step "and that credential reaches the store no further than the door it was given"
source docs/guide/examples/snippets/scim-blind.sh
blind=$(curl -s -o /dev/null -w '%{http_code}' \
    -H "Authorization: Bearer $DIRECTORY" "$HOGWARTS/Patient")
[ "$blind" = "403" ] || fail "a directory credential read the store, got $blind"
shut=$(curl -s -o /dev/null -w '%{http_code}' \
    -H "Authorization: Bearer $HOSPITAL" "$SCIM/Users")
[ "$shut" = "403" ] || fail "a store credential reached the provisioning door, got $shut"

step "and who is an administrator here is not the directory's to say"
source docs/guide/examples/snippets/scim-groups.sh
governance=$(curl -s -o /dev/null -w '%{http_code}' -X POST \
    -H "Authorization: Bearer $DIRECTORY" -H 'Content-Type: application/scim+json' \
    "$SCIM/Groups" -d '{"displayName":"matron"}')
[ "$governance" = "405" ] \
    || fail "role governance arrived by provisioning, got $governance"

step "somebody asks to be forgotten"
# A patient of their own, because erasure is irreversible and every step above
# this one is still using Harry.
forgettable=$(curl -sf -X POST -H "Authorization: Bearer $HOSPITAL" \
    -H 'Content-Type: application/fhir+json' "$HOGWARTS/Patient" \
    -d '{"resourceType":"Patient",
         "identifier":[{"system":"urn:rl:nid","value":"RL-FORGET"}],
         "name":[{"family":"Riddle","given":["Tom"]}],"birthDate":"1926-12-31"}' \
    | python3 -c 'import sys,json;print(json.load(sys.stdin)["id"])')
[ -n "$forgettable" ] || fail "the patient who asks to be forgotten was not written"

source docs/guide/examples/snippets/erasure-ask.sh
echo
receipt=$(curl -sf -X POST -H "Authorization: Bearer $RIGHTS" \
    -H 'Content-Type: application/json' http://localhost:8090/t/hogwarts/erasure \
    -d "{\"subject\":\"Patient/$forgettable\"}" \
    | python3 -c 'import sys,json;d=json.load(sys.stdin);print(d.get("run","") != "")')
[ "$receipt" = "True" ] || fail "the erasure answered without a run to show for it"

step "and their number resolves to nobody"
source docs/guide/examples/snippets/erasure-unfindable.sh
left=$(curl -sf -G -H "Authorization: Bearer $HOSPITAL" "$HOGWARTS/Patient" \
    --data-urlencode "identifier=urn:rl:nid|RL-FORGET" \
    | python3 -c 'import sys,json;print(len(json.load(sys.stdin).get("entry",[])))')
[ "$left" = "0" ] || fail "an erased person is still resolvable by their number, got $left"

step "while the record keeps its shape and loses the person"
source docs/guide/examples/snippets/erasure-remains.sh
echo
remains=$(curl -sf -H "Authorization: Bearer $HOSPITAL" "$HOGWARTS/Patient/$forgettable" \
    | python3 -c 'import sys,json;print(",".join(sorted(json.load(sys.stdin))))')
case "$remains" in
    *name*|*identifier*|*birthDate*) fail "the erased record still carries the person: $remains" ;;
esac

printf '\nguide: chapters one to ten work\n'
