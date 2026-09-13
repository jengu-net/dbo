# The local observability instance

Somewhere for a development machine's numbers to land and still be there
tomorrow. Long-lived on purpose: the volume outlives the container and the
container outlives a reboot, so two runs a day apart can be compared.

Nothing in the store depends on it. A node with no collector counts and sends
nowhere, so this being down is not a failure of anything.

## Once

```
cp env.example .env
```

Edit the password. Then:

```
docker compose up -d
```

The console is at <http://localhost:5080>, under the credentials in `.env`.
It stays up from here, reboots included, until it is stopped on purpose.

Two containers come up: the instance, and a collector that reads the
development console's log file and posts the lines to it. The collector needs
`DBO_O2_TOKEN` in `.env` as well — the same email and password, base64-encoded
— because it authenticates as an ordinary client rather than through a side
door.

The collector ships lines as they are rather than parsing them. A console's
log is in Karaf's format rather than the store's JSON, and a parser for it
cost more than it was worth: an operator that fails on one line stops the
reader for the whole file, so one stack trace or the JDK-formatted startup
preamble silently ended the stream. Searching the line is enough for what this
is for, and it cannot half-work.

`.env` is git-ignored and the values are read on the instance's **first boot
only** — changing them later edits nothing, because the account already
exists. To start over, `docker compose down -v` and bring it back up.

## How long it keeps things

Thirty days by default, set in `compose.yaml`. The shipped default is
effectively forever, which is the wrong posture for an instance that is meant
to outlive a reboot and then be forgotten about: a development machine's
history stops being worth much long before a month is out, and a disk filling
quietly is the failure that goes unnoticed longest.

A stream that deserves a different answer sets its own, which overrides the
default:

```bash
curl -X PUT -u "$EMAIL:$PASSWORD" -H 'Content-Type: application/json' \
  'http://localhost:5080/api/default/streams/<stream>/settings?type=logs' \
  -d '{"data_retention": 7}'
```

A stream left at `0` inherits the default rather than keeping data forever.

## Pointing the test suite at it

The seam reads the protocol's own environment variables, so nothing in the
store had to change to make this work. What the build adds is a dial, off
unless it is set:

```
./gradlew test \
  -PdboTelemetryEndpoint=http://localhost:5080/api/default/v1/metrics \
  -PdboTelemetryHeaders="Authorization=Basic <base64 of email:password>"
```

Put it in `~/.gradle/gradle.properties` rather than typing it, and every local
run reports without being asked:

```
dboTelemetryEndpoint=http://localhost:5080/api/default/v1/metrics
dboTelemetryHeaders=Authorization=Basic <base64 of email:password>
```

**Not in the repository's own `gradle.properties`.** That header is a
credential, and the dial being absent is what keeps CI and a machine with no
instance running exactly as they were.

`dboTelemetryInterval` (seconds, default 2) and `dboTelemetryService`
(default `dbo-tests`) are there if needed. Each test task prints what actually
applies:

```
test jvm: maxHeapSize=2g parallelismOverride=1 telemetry=http://localhost:5080/...
```

## What arrives, and what does not

The seam has two call sites, both where a step run is reported, so what shows
up is `dbo.run.reported` and `dbo.run.duration` labelled from the seam's own
closed vocabulary — tenant, process, step, executor, outcome. **Only suites
that actually run steps emit anything**; most of the tree reports nothing
because it counts nothing, which is the honest answer rather than a
misconfiguration.

Tallies on a run — what a sweep applied, skipped or withdrew — are not
telemetry. They live on the run record in the tenant's store, and they are
read from there.

A suite is named `dbo-tests` so it never shares a series with a store somebody
is actually running on the same machine, and each forked worker mints its own
instance id, because counts are cumulative and two processes counting from
zero against one identity read as a counter that keeps falling over.

A test JVM is short-lived and the flusher does not hold exit open, so whatever
has not been posted when a suite ends is lost. Counts being cumulative, each
post carries the running total and the loss is bounded by one interval.
