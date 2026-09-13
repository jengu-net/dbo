---
title: Quickstart (Karaf)
headline: A store from source, and somewhere to watch it think
eyebrow: Quickstart
standfirst: >-
  A local collector, the development console, and a tenant that declares FHIR
  R5. Then the part worth the setup: watching the store's own internal process
  bring that tenant up, and finding the same event twice — once as a log line
  that is a copy, and once as a record that is the original.
template: essay.html
---

This is the route through the development console — the source tree, a stock
Apache Karaf, and a bundle set you can edit while it runs. It takes about
fifteen minutes the first time, most of it spent building a terminology
baseline per tenant, which is the real cost of a cold start.

If what you want is a store answering as quickly as possible, take [the
container route](quickstart-docker.md) instead: two commands, no build, and
every command on it is executed on every build of this repository.

!!! note "Run by hand, not by CI"

    Unlike the container route, nothing in this repository executes the
    commands on this page on every build. They were last walked end to end on
    13 September 2026 against OpenObserve v1.0.0 and Karaf 4.4.11. If one of
    them has drifted, that is a defect worth reporting rather than a typo on
    your side.

You need Docker, a JDK 21, and about 4GB of free memory.

## 1. Somewhere for the output to go

The store reports nothing by default and depends on nothing to run. What
follows gives it somewhere to report *to*, so that bringing a tenant up is
something you can watch rather than something you infer.

```bash
git clone https://github.com/jengu-net/dbo.git
cd dbo/dev/observability
cp env.example .env
```

Edit `.env`: the email needs a real-looking domain and the password needs an
upper, a lower, a digit and a symbol — both are checked on first boot and a
value that fails either stops the process rather than starting it
unconfigured. Then add `DBO_O2_TOKEN`, which is the same pair base64-encoded:

```bash
printf %s 'you@example.com:YourPass1!' | base64
```

```bash
docker compose up -d
```

That is two containers: the instance itself at
[localhost:5080](http://localhost:5080), and a collector that reads the
console's log file. Both are `restart: unless-stopped` on a named volume, so
they survive a reboot and keep what they have seen.

## 2. A Postgres for the store to provision into

The store makes a database per tenant, so the connection it is given needs
`CREATE DATABASE`. On a throwaway instance the superuser is the shortest way
to have that:

```bash
docker run -d --name dbo-console-db -p 5432:5432 \
  -e POSTGRES_PASSWORD=postgres postgres:17-alpine
```

## 3. The console

```bash
cd ../..
cp karaf/dev/local.properties.example karaf/dev/local.properties
```

The example already points at `127.0.0.1:5432` with `postgres`/`postgres`, so
if you used the command above there is nothing to edit. Then:

```bash
./gradlew dev :karaf:console
```

`dev` publishes the bundle set to `~/.m2`, which is where the console's
`bundle:watch` looks; `:karaf:console` assembles a Karaf around it. Put
`dbo.dev=true` in `~/.gradle/gradle.properties` to skip javadoc, which every
module otherwise builds on every republish.

```bash
karaf/build/dbo-console/karaf/bin/karaf
```

and at the prompt:

```
dbo-console:up
```

which installs the bundle set, starts it, and watches what is worth watching.
It is safe to repeat — installing a location that is already installed returns
the existing bundle, so it is also how a session picks up a container that is
already running.

## 4. A tenant is a file

The manager reconciles `karaf/dev/tenants` every two seconds, so provisioning
is a file appearing. In another terminal:

```bash
cat > karaf/dev/tenants/vaatlus.json <<'JSON'
{
  "code": "vaatlus",
  "face": "r5",
  "pdi": false,
  "types": [
    { "name": "Patient", "identity": "identifier",
      "systems": ["urn:dbo:vaatlus:mrn"], "handling": "operational" },
    { "name": "Observation", "identity": "internal",
      "handling": "operational" }
  ]
}
JSON
```

`face` is which standard is mapped onto the engine, and `r5` is a different
answer from the `r4` the example tenant gives — [the engine has no FHIR in
it](../why/index.md), so both faces sit over the same store. Give it a
minute; a face and a vocabulary are being built from nothing.

```bash
curl -s localhost:8090/t/vaatlus/fhir/metadata | head -c 200
```

When that answers, `fhirVersion` is `5.0.0`.

## 5. Watch the store bring it up

Open [localhost:5080](http://localhost:5080), pick **Logs** and the
`dbo_console` stream, and search for `tenant up`. What the bring-up looked
like here:

```
tenant bring-up cost: code=vaatlus facade=3467ms vocabulary=36797ms
tenant up: code=vaatlus fhir=r5 pdi=false in 48044ms
tenants: serving=3 r4=2 r5=1 pdi=0
```

Three lines, and they are three different statements. The first is what the
bring-up *cost*, split so that a slow one can be attributed — a facade is
cheap and a vocabulary is not. The second is the tenant, with the face it
ended up serving. The third is the rollup the scanner writes each pass, which
is the deployment's own answer to "what am I serving", and the only one of the
three that is true independently of when you read it.

## 6. The same event, as a record

The log is a copy. Here is the original:

```
dbo:context haldur
dbo-run:list --holder any
```

```
TENANT │ PROCESS            │ STEP  │ KIND  │ HOLDER │ TALLY                          │ KEY
───────┼────────────────────┼───────┼───────┼────────┼────────────────────────────────┼───────────────────────────────────
haldur │ dbo.tenant.serving │ serve │ sweep │ nobody │ coming_up=0 serving=3 failed=0  │ dbo.tenant.serving/serve/deployment
haldur │ dbo.config.applied │ apply │ sweep │ nobody │ skipped=0 read=2 applied=2 …    │ dbo.config.applied/apply/deployment
```

Bringing your tenant up was a **step of a process**, and it left a **run** —
an ordinary record in the management tenant's own store, with the same
`serving=3` the log line quoted. That is the point of the detour. The line in
the collector is a rendering that can be lost, sampled or truncated without
anything noticing; the run is versioned, audited and owned, and it is what
anything acting on the work reads.

`dbo-run:describe <key>` gives one run's outcomes, its executor and its
correlation.

## What you just looked at, and what you did not

**No metrics were involved.** The store has a telemetry seam and it reports
nothing here, because nothing on this path counts anything: the tallies you
saw — `serving`, `applied`, `skipped` — are written onto the run record, not
emitted as measurements. The seam reports where work runs through a
participation lane, which this quickstart never reaches. A collector that
shows you nothing under Metrics is correct rather than broken.

**The log is Karaf's, not the store's.** A deployment logs one JSON line per
event; a console does not, because the JSON binding is a framework extension
and can only attach at framework initialisation, which a running Karaf is
past. So the collector ships lines as they are and you search the line.

## Two things here that a deployment must not copy

**The key-encryption key is a fixed development constant.** It is in
`local.properties.example`, in the open, and it protects nothing. Without it
the tenant authority is not wired at all and the surface serves
unauthenticated — which is a different runtime from the one being developed,
which is why the example sets one rather than leaving it out.

**Postgres is the superuser.** A deployment gives the store a role with
`CREATE DATABASE` and nothing else.

## Stop it

```
system:shutdown
```

in the console, then:

```bash
docker rm -f dbo-console-db
cd dev/observability && docker compose stop
```

`stop` rather than `down -v`: the instance is meant to still be there
tomorrow, holding what it saw today.

<div class="further" markdown>
Next: [why any of this is shaped the way it is](../why/index.md), or
[what operating it actually involves](../docs/arc42-008-crosscutting/running-it/README.md)
in the specification.
</div>
