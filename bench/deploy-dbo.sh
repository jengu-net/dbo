#!/bin/sh
# Put THIS working tree's dbo on the bench Pi and start it.
#
# The load test is not a one-shot: the point of measuring a bottleneck is
# fixing it and measuring again, so the edit-to-running loop has to be short
# enough that nobody batches up changes to avoid paying for it. rsync ships
# only the bundles that actually changed, which for a one-module edit is a
# few hundred kilobytes.
#
# It builds from the WORKING TREE, uncommitted changes included. That is
# deliberate: a loop that only deploys what is committed makes you commit to
# measure, and half-finished performance experiments should not be commits.
# The trade is that a result can be attributed to a tree that no longer
# exists, so every deploy stamps the git description AND whether the tree was
# dirty into $REMOTE_DIR/version, and the runner copies it into the result.
#
#   bench/deploy-dbo.sh                     # build, ship to the bench Pi, start
#   bench/deploy-dbo.sh --target local      # same tree, run on this machine
#   bench/deploy-dbo.sh --no-build          # ship what is already built
#   bench/deploy-dbo.sh --stop              # stop it, ship nothing
set -eu

TARGET=192.168.1.128
REMOTE_USER=root
REMOTE_DIR=/opt/dbo
TENANT=bench
PORT=8090
# A dedicated role rather than the box's postgres superuser: this Pi carries
# other databases, and a benchmark should not be able to reach them. CREATEDB
# because dbo provisions a database per tenant. Overridable, but the default
# is what the bench Pi is set up with.
PGUSER_DBO="${DBO_PGUSER:-dbobench}"
PGPASS_DBO="${DBO_PGPASSWORD:-benchpass}"
BUILD=1
ACTION=start

while [ $# -gt 0 ]; do
    case "$1" in
        --target)   TARGET=$2; shift 2 ;;
        --tenant)   TENANT=$2; shift 2 ;;
        --port)     PORT=$2; shift 2 ;;
        --no-build) BUILD=0; shift ;;
        --stop)     ACTION=stop; BUILD=0; shift ;;
        -h|--help)
            sed -n '2,20p' "$0"; exit 0 ;;
        *) echo "unknown argument: $1" >&2; exit 2 ;;
    esac
done

cd "$(dirname "$0")/.."
DIST=core/dbo-server/build/install/dbo-server
TMP_SPEC=$(mktemp)
trap 'rm -f "$TMP_SPEC"' EXIT

# A dirty tree is not a defect here, it is the normal case for this loop. It
# is recorded rather than refused, because a number whose code cannot be
# recovered has to SAY so rather than look like a tagged build.
VERSION="$(git describe --always --dirty=+dirty 2>/dev/null || echo unknown)"

# ------------------------------------------------------------------- remote
# One indirection so --target local and a real Pi run the same script body.
# Two code paths that drift is how "it worked locally" starts.
if [ "$TARGET" = "local" ]; then
    at() { sh -c "$1"; }
    put() { mkdir -p "$REMOTE_DIR"; rsync -a --delete "$DIST"/ "$REMOTE_DIR"/; }
    BASE="http://127.0.0.1:$PORT"
else
    at() { ssh -o ConnectTimeout=10 "$REMOTE_USER@$TARGET" "$1"; }
    put() {
        at "mkdir -p $REMOTE_DIR"
        # --delete so a bundle removed from the build is removed from the Pi.
        # Without it a stale bundle keeps being auto-deployed and the thing
        # measured is not the thing built -- the worst failure this loop has,
        # because it looks like a result.
        rsync -a --delete -e "ssh -o ConnectTimeout=10" \
            "$DIST"/ "$REMOTE_USER@$TARGET:$REMOTE_DIR"/
    }
    BASE="http://$TARGET:$PORT"
fi

stop_it() {
    at "if [ -f $REMOTE_DIR/dbo.pid ]; then
            kill \$(cat $REMOTE_DIR/dbo.pid) 2>/dev/null || true
            # Poll for exit rather than sleeping: a fixed sleep is either a
            # wasted second or not enough, and never the right amount.
            i=0
            while kill -0 \$(cat $REMOTE_DIR/dbo.pid) 2>/dev/null && [ \$i -lt 100 ]; do
                sleep 0.1; i=\$((i+1))
            done
            kill -9 \$(cat $REMOTE_DIR/dbo.pid) 2>/dev/null || true
            rm -f $REMOTE_DIR/dbo.pid
        fi"
}

if [ "$ACTION" = "stop" ]; then
    echo "==> stopping dbo on $TARGET"
    stop_it
    echo "    stopped"
    exit 0
fi

# -------------------------------------------------------------------- build
if [ "$BUILD" = 1 ]; then
    echo "==> building $VERSION"
    ./gradlew --quiet :core:dbo-server:installDist
fi
[ -d "$DIST" ] || { echo "no dist at $DIST -- drop --no-build" >&2; exit 1; }

# --------------------------------------------------------------------- ship
echo "==> stopping whatever is running"
stop_it

echo "==> shipping to $TARGET:$REMOTE_DIR"
put
at "printf '%s\n' '$VERSION' > $REMOTE_DIR/version"

# ------------------------------------------------------------------- tenant
# The spec is written every deploy rather than once, so the fhirVersion the
# benchmark ran against is a fact of the deploy and not of whatever was left
# on the Pi from last time. r5 because that is the only version all three
# servers under comparison can serve -- fhirest is R5-pinned.
# The types are the ones the cohort actually contains -- computed across
# EVERY bundle, not sampled. Sampling three of them missed
# AllergyIntolerance, and a type a bundle carries that the tenant has not
# declared fails the whole transaction -- which in a load test reads as the
# server being slow at refusing rather than as a missing declaration. A type a bundle carries
# and the tenant has not declared fails the write, which in a load test looks
# like the server being slow at 4xx rather than like a missing declaration.
#
# All operational, all internal identity: this is a synthetic population being
# ingested for measurement, so nothing here is a config projection, nothing is
# mirrored from elsewhere, and nothing is claimed under an external identifier
# system. Patient included -- deduplicating patients on their personal code is
# a real question and NOT this benchmark's, and making it identifier-bearing
# here would put a claim lookup in every write path being timed.
TYPES=AllergyIntolerance,CarePlan,CareTeam,Condition,Device,DiagnosticReport,DocumentReference,Encounter,ImagingStudy,Immunization,Location,Medication,MedicationAdministration,MedicationRequest,Observation,Organization,Patient,Practitioner,PractitionerRole,Procedure,Provenance,SupplyDelivery
at "mkdir -p $REMOTE_DIR/tenants" 
{
    printf '{\"code\":\"%s\",\"fhirVersion\":\"r5\",\"types\":[' "$TENANT"
    printf '%s' "$TYPES" | tr ',' '\n' | awk '{ printf "%s{\"name\":\"%s\",\"identity\":\"internal\",\"handling\":\"operational\"}", (NR>1 ? "," : ""), $0 }'
    printf ']}\n'
} > "$TMP_SPEC"
if [ "$TARGET" = "local" ]; then
    cp "$TMP_SPEC" "$REMOTE_DIR/tenants/$TENANT.json"
else
    scp -q -o ConnectTimeout=10 "$TMP_SPEC" "$REMOTE_USER@$TARGET:$REMOTE_DIR/tenants/$TENANT.json"
fi

# -------------------------------------------------------------------- start
echo "==> starting"
at "cd $REMOTE_DIR && \
    DBO_TENANT_DIR=$REMOTE_DIR/tenants \
    DBO_HTTP_PORT=$PORT \
    DBO_ADMIN_JDBC_URL=jdbc:postgresql://127.0.0.1:5432/postgres \
    DBO_ADMIN_USER=$PGUSER_DBO \
    DBO_ADMIN_PASSWORD=$PGPASS_DBO \
    DBO_AUTH_DISABLED=true \
    DBO_LOG_LEVEL=warn \
    nohup bin/dbo-server > $REMOTE_DIR/dbo.log 2>&1 &
    echo \$! > $REMOTE_DIR/dbo.pid"

# Poll to first 200 rather than sleeping. This doubles as the cold-start
# number when the caller wants one, and as a gate when it does not: a deploy
# that returns before the server answers hands the next step a race.
echo "==> waiting for $BASE/t/$TENANT/fhir/metadata"
START=$(date +%s)
until curl -sf -o /dev/null "$BASE/t/$TENANT/fhir/metadata"; do
    if ! at "kill -0 \$(cat $REMOTE_DIR/dbo.pid) 2>/dev/null"; then
        echo "    server exited; last lines of $REMOTE_DIR/dbo.log:" >&2
        at "tail -30 $REMOTE_DIR/dbo.log" >&2
        exit 1
    fi
    [ $(( $(date +%s) - START )) -lt 120 ] || {
        echo "    still not answering after 120s; see $REMOTE_DIR/dbo.log" >&2
        exit 1
    }
    sleep 0.2
done

echo "    up in $(( $(date +%s) - START ))s -- $VERSION"
echo "    $BASE/t/$TENANT/fhir"
