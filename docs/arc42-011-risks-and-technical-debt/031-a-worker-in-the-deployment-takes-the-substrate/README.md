**Open, and most of the way there. The wiring is built and the carrier works
up to its last hop: `dbo-stream` is in the worker's bundle set, a lane with no
base is carried by the substrate, both halves point at one database, and the
tenant opens its door. What fails is the signature — an ask is refused 401 on
a plane where a signature is what a token would have been, while the tenant
demonstrably holds the enrolled public key. The end-to-end test exists and is
disabled on that one line.**

# A worker in the deployment takes the substrate

## Which carrier belongs to which case

| the worker | carrier |
|---|---|
| beside the serving half, one process | the substrate |
| its own process, part of this deployment, scaled | the substrate |
| another organisation's application — a tenant's edge device, a laboratory | HTTP |

**The distinction is the organisation, not the process boundary.** A worker
running as several replicas for throughput is inside the deployment and is on
the same database as the serving side; making it authenticate over HTTP to
reach work it could read directly is a hop, a credential and an authority in
the path for nothing. A worker belonging to somebody else has no business near
that database, and HTTP with a credential the tenant issued is exactly right
for it.

So HTTP is not the general case with the substrate as an optimisation. It is
the **cross-organisation** case, and the substrate is the ordinary one.

## What is missing

Two things, and the first is the one that makes this more than a property name.

**`dbo-stream` is not in the worker assembly's bundle set.** `workerModules` in
`assembly/spring-boot-worker/build.gradle.kts` names core, work, runner,
telemetry and their closure. `StreamLane` lives in `core/dbo-stream` and is not
installed, so nothing in a Spring worker could register one however it was
configured.

**And there is no configuration that would reach it.** `DboWorker` builds
`HttpLane.to(base.resolve("work"), …)` for each configured lane and nothing
else. Unlike `DboServerProperties`, `DboWorkerProperties` carries no
`dbo.framework.*` passthrough, so an application cannot set the
`dbo.substrate.url` that `dbo-stream`'s activator reads either. The activator's
path exists and is the right shape — a separate host reaching a deployment's
substrate, a lane per tenant from a comma-separated list — and an application
on this assembly cannot ask for it.

## What the work is

A carrier on a lane, and the bundle to make it possible:

```yaml
dbo:
  worker:
    lanes:
      - tenant: hogwarts
        carrier: substrate
      - tenant: st-jerome
        carrier: substrate
      - tenant: an-edge-device-tenant
        base: https://dbo.example/t/that-tenant/
        token: { client-id: …, client-secret: … }
```

Two tenants over the database and one over HTTP, in one worker, because that is
what a worker looks like: **almost every worker serves many tenants.** The
runner is stateless over the tenants whose lanes it is handed, the lane list is
already a list, and `StreamLane` is already one per tenant over one substrate
pool.

## Where it actually stands

**Built, and working:**

- `:core:dbo-stream` is in the worker assembly's bundle set, installed and
  inert — the activator reads the tenants it is a host for and returns where a
  worker named none, so an HTTP-only application pays a bundle and nothing else.
- A lane with **no base** is carried by the substrate. The carrier is inferred
  from what the lane was given rather than named, because a base and a
  credential already say which one it is.
- `dbo.worker.substrate.*` carries the substrate and the enrolment, and reaches
  the container through the contribution seam rather than a second mechanism.
- The **serving side** gained `dbo.substrate.*` too, which it needed and did not
  have: `manager.substrate(…)` is what opens a tenant's door on the stream, so
  without it there was nothing for a participant to connect to. That was missing
  from this item's own analysis.
- An incomplete enrolment is refused at refresh, unlike an unreachable tenant,
  and for the opposite reason: a tenant that is not answering yet will answer,
  and a missing key never will.
- `assembly/spring-boot-test` derives all of it from one word,
  `dbo.test.lane.carrier: substrate` — a substrate database of its own, a
  keypair per JVM, and the public halves enrolled once the tenant is serving.

**Observed working end to end:** the stream lane is built and attached, the
tenant's door opens, and the two find each other on one substrate. The failure
moved twice under measurement — first *no door is open on the stream*, then,
once the door existed, a refusal.

## The one thing left

```
lane cycle failed: tenant=hogwarts
  hogwarts: release-lapsed refused (401) — an ask on the stream is signed by
  the participant's enrolment key
```

**And the tenant holds the key.** Probed directly at the point of enrolment:
`enrolledHasSigningKey=true`, scopes `[work]`, on the tenant the lane is into.
So `authority.signingKey(participant)` is present and
`key.verifies(signed, signature)` is what returns false — the signature does
not check out against the public half the tenant was given.

**What that narrows it to**, since both halves come from one keypair made once
per JVM: either the private half the container parses is not the private half of
that pair, or the bytes signed are not the bytes verified. The next probe is at
that seam and nowhere else — log the public key the door checks against beside
the one derived from the key the activator parsed, in one run, and they either
match or they do not.

**One more defect was found on the way and is not this one.** The durable
layer's notification listener logs `Cannot unwrap to org.postgresql.PGConnection`
— 1,809 times in a four-minute run. It is pooled connections and a driver the
stream bundle carries privately: the listener degrades to polling, so
correctness holds and latency and noise do not. Worth its own item once this one
is closed.

## What has to be decided

- **Where the substrate connection comes from.** The serving half already has
  one; a separated worker does not and must be given one, which is the
  `dbo.substrate.*` the activator reads. Whether a co-located worker should
  reuse the serving half's pool or open its own is a real question — the store
  manages its own connections per tenant on purpose.
- **`carrier: substrate` with no substrate is a refusal**, at context refresh,
  with the lane's tenant in the message. Not a fallback to HTTP: a worker that
  quietly took the slower path because a URL was missing is a deployment nobody
  can reason about.
- **What identity and participant are over the substrate.** Over HTTP they come
  from the credential. `dbo.worker.identity` already names the executor and its
  version, so this is probably nothing new — but it is the one field a run
  cannot be re-derived from, and an unstated default would put the wrong name
  on it.
- **Whether the sealing and signing keys the activator requires belong in an
  application's configuration**, or are the deployment's and read from where
  the serving half reads its own.

## What this is not

**Not a case against the HTTP lane.** It is the cross-organisation carrier and
nothing here replaces it. `ALaneOverHttpIsIndistinguishableIT` and
`ALaneOverTheStreamIsIndistinguishableIT` both hold, and the runner cannot tell
which carried a run — which is what makes this a wiring change rather than
anything a step service sees.

**Not a reason to let a worker read the store.** It takes work over a lane and
reports over the same one, whichever carries it. A worker that read the store
because it happened to be nearby could not be moved, and moving it is the
property the lane exists to preserve.

## What proves it

Today, that it cannot:

```
./gradlew :assembly:spring-boot-worker:bundleIndex
```

and read `build/generated/dbo/META-INF/dbo/bundles.index` — no
`cloud.jengu.dbo.stream`. Then the `oidc/token` calls in
`:samples:spring-boot-worker-app:test`, which are an application in the
deployment authenticating to reach work.
