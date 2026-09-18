#!/usr/bin/env bash
#
# The two numbers the guide migration is measured by.
#
# Every integration test that builds a world of its own pays for it — a
# Postgres, a tenant runtime, a spec, a bootstrap — to run a few seconds of
# assertions. The guide world is built once and shared, so the migration is
# finished when this count reaches the tests that genuinely need a world
# nobody else can share.
#
#   ./.github/scripts/guide-migration-progress.sh
set -euo pipefail
cd "$(git -C "$(dirname "$0")" rev-parse --show-toplevel)"

own=0
shared=0
for test in $(find core -name '*IT.java' -path '*/src/test/*' -not -path '*/build/*'); do
    if grep -qE 'TenantRuntimeManager|PostgreSQLContainer|SharedPostgres|SharedTenants' "$test"; then
        own=$((own + 1))
    else
        shared=$((shared + 1))
    fi
done

SUITE=guide/src/test/java/cloud/jengu/dbo/guide/TheGuideRunsIT.java
stories=$(grep -c '@Nested' "$SUITE")
# Every step carries an @Order and so does every story, so the stories come off.
steps=$(( $(grep -c '@Order(' "$SUITE") - stories ))
# A grep that matches nothing exits 1, and there are none to match on the day
# this is written — which is the whole point of measuring it.
cited=$({ grep -o 'DboPromises\.[A-Z_0-9]*' "$SUITE" || true; } | sort -u | wc -l | tr -d ' ')

printf 'integration tests building a world of their own   %s\n' "$own"
printf 'integration tests that do not                      %s\n' "$shared"
printf 'guide steps, in %s stories                        %s\n' "$stories" "$steps"
printf 'promises proven from the guide world               %s\n' "$cited"
