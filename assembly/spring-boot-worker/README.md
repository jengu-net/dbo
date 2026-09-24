# The step runner, inside a Spring Boot application

One dependency, and a bean that implements `StepService` is a step this
application performs. Work arrives whole; the outcome goes back. There is no
store here and no way to get one.

This document is the plan it is being built to.

The host — one framework, one class space, the host's own logging, the
Spring Boot generation — is
[`core/dbo-embedded`](../../core/dbo-embedded/README.md), and those decisions
are argued there rather than repeated here. The serving half is
[`../spring-boot-server`](../spring-boot-server), which brings tenants up. An
application holding both gets one framework. This one states what is different here, which is
mostly what is **absent**.

## What is already here

- `build.gradle.kts` — the module, its dependency shape, and `bundleIndex`,
  which writes the ordered bundle set into `META-INF/dbo/bundles.index`.
- The module is wired into `settings.gradle.kts` and publishes as
  `cloud.jengu.dbo:dbo-spring-boot-worker`.

Run `./gradlew :assembly:spring-boot-worker:bundleIndex` and read the index.
Five bundles:

```
cloud.jengu.dbo.core
cloud.jengu.dbo.work
cloud.jengu.dbo.telemetry
cloud.jengu.dbo.telemetry.otlp
cloud.jengu.dbo.runner
```

**That list is the claim.** No store bundle, no face, no tenant, no
transport beyond the runner's own. A party that performs somebody else's work
compiles against the lane and the work vocabulary and nothing else, and a
dependency here that `sample/participant` does not have would be this module
saying that joining costs more than it does.

The container already proves the same set from the other side:
`ADriverBundleContributesAStepIT` installs exactly these, plus a driver
bundle that registers a `StepService` and a `Lane`, and asserts the work is
performed with nothing wired by hand. This module is that driver bundle,
written in Spring.

## What the sample application says about this

Two files, and they are opposite ends of the same sentence.

**`AdmitStep` is what an application writes**, and it is already the right
shape. It implements `StepService`. It declares a step code. It reads
`work.inputs()`, reports a milestone, and returns `Outcome.done` or
`Outcome.failed`. It names no transport, no tenant and no store, and it holds
no state between calls. The comment on it — *this is the whole of what an
integrator writes* — is the requirement this module has to make true, because
today it is not quite: `AdmitStep` is the whole of what an integrator writes
**plus** `Admissions`.

**`Admissions` is what an application should stop writing.** Forty lines
that construct an `Executor` identity, construct a `StepRunner` with a hold
and a poll duration, `register` the step, and `attach` an `HttpLane` built
from a tenant base URI, a bearer supplier, a tenant code and a runner name.
It is `AutoCloseable` and the caller holds it, and it exposes `cycle()` for a
test and `start()` for a process.

Every one of those is configuration, not code:

| `Admissions` line | Becomes |
|---|---|
| `new Executor("ward-runner", "1", tenant, Scope.organisation(tenant))` | `dbo.worker.identity.*` |
| `new StepRunner(ofMinutes(1), ofSeconds(2))` | `dbo.worker.hold` / `dbo.worker.poll` |
| `.register(new AdmitStep())` | the bean |
| `.attach(HttpLane.to(base.resolve("work"), bearer, tenant, name, identity))` | `dbo.worker.lanes[0].*` |
| `close()` | the context closing |
| `cycle()` | see step 6 |

The `Supplier<String>` for the token is the part to copy rather than
simplify. The comment says why: a runner outlives an access token, and one
captured at construction starts failing an hour later in a way that reads
like the store going away. A Spring property holding a literal token is the
mistake that comment describes, and the configuration has to make the
supplier the ordinary case and the literal the awkward one.

`sample/participant` says the rest. It is smaller than the sample on purpose
— the lane and the work vocabulary, no store, no tenant, no face — and it is
the module this one is the Spring binding for.

## How the runner is driven, and by whom

The runner is not called by the application. `StepRunner.start()` runs a poll
loop on its own thread: it asks each attached lane what work is offered,
performs it, and reports. Nothing nudges it.

In the container that loop belongs to `dbo-runner`'s activator, which is the
whiteboard both ways: any bundle registering a `StepService` contributes a
step, and any bundle registering a `Lane` gives it somewhere to poll. The
activator's two dials are framework properties, `dbo.runner.poll.millis` and
`dbo.runner.hold.millis`.

So this module does **not** construct a `StepRunner`. It registers services
and lets the activator do what it already does. That distinction is the
difference between a Spring starter and a second implementation of the runner
that drifts from the first, and it is why step 4 is shorter than it looks.

## The steps

### 1. The module and its cargo — done

See *What is already here*.

### 2. The host — in `core/dbo-embedded`

Read its [step 2](../../core/dbo-embedded/README.md#2-boot-a-framework-nobody-can-see).
Nothing to build here. The module exists, and this one depends on it.

The API packages this assembly adds to the computed list are the ones it
compiles against — `cloud.jengu.dbo.core.*`, `cloud.jengu.dbo.work`,
`cloud.jengu.dbo.runner` and `cloud.jengu.dbo.runner.http` — plus
`org.slf4j`. Computed from the manifests, not listed here.

*Proved by:* the host's own test, plus one here asserting the five bundles
of this index reach `ACTIVE` with no store bundle among them — the absence is
part of the claim, so something has to assert it.

### 3. Configuration — done

`@ConfigurationProperties("dbo.worker")`, in `DboWorkerProperties`.

```yaml
dbo:
  worker:
    poll: 2s               # dbo.runner.poll.millis
    hold: 1m               # dbo.runner.hold.millis
    identity:
      name: ward-runner
      version: "1"
    lanes:
      - tenant: hogwarts
        base: https://deployment.example/t/hogwarts/
        scope: organisation        # organisation | tenant
        token:
          client-id: ward-runner
          client-secret: ${WARD_SECRET}
```

Two things are load-bearing.

**The token is obtained, not configured.** `token.client-id` and
`token.client-secret` name a client the tenant holds, and the starter signs
in against the tenant's own authority and refreshes before expiry — the
`Supplier<String>` the lane already takes, filled properly. A bean of type
`DboToken` named for a lane overrides it, for an application whose process
acts for a person and carries that person's token rather than minting its
own. A literal `token.value` exists and is documented as what it is: fine for
a spike, wrong for a process that runs longer than an hour.

**The executor identity is four fields and all four are required.** Named,
versioned, provided and scoped — an executor that cannot be reproduced cannot
be held to what it did. `name` and `version` come from configuration;
`provider` and `scope` are derived from the lane's tenant, which is what
`Admissions` does. An application that leaves `identity.name` unset is
refused at context refresh, not defaulted to the artifact id.

*Proved by:* a test asserting that a lane configured with a client id and
secret obtains a token, and that a token which expires mid-run is replaced
without the runner noticing.

### 4. Beans in: a bean that is a step — done

Every bean implementing `StepService` is registered as a `StepService` on the
container's whiteboard, through `DboRegistrar` — the one line between a
Spring bean and a whiteboard, and the only thing in these assemblies that
knows what a service registration is.
That is the whole of it — the step's code is on the interface, so there is no
metadata to carry and no annotation to invent.

Every configured lane becomes a `Lane` service, built with
`HttpLane.to(base.resolve("work"), tokenSupplier, tenant, identityName,
executor)`.

The activator sees both and wires them. Nothing in this module constructs a
`StepRunner`.

**Two beans declaring the same step code is a refusal at context refresh.**
The runner's `register` takes the step code as a key; a second registration
of the same code inside a running container is a last-one-wins that an
application author would have to discover by watching which one ran. Spring
knows both beans at refresh and can say so.

*Proved by:* `ABeanIsAStepThisApplicationPerformsIT`. A bean of the shape
`AdmitStep` has, some configuration, and a standing `ProvingLane` — nothing
in the test constructs a runner, registers a step service or attaches a lane,
so if the step is performed the only thing that can have wired it is the
whiteboard. Three claims, each confirmed red by mutation: the bean's step is
performed; two beans for one code are refused at refresh naming both; a lane
with no way to obtain a credential is refused naming the tenant.

**One thing the first run found.** The container was created as a bean and
never started, so an application bean that registered something while being
constructed met a runtime that refused every call. It is now booted by
`initMethod` rather than on a lifecycle: anything that takes the bean takes a
RUNNING container. The alternative was a bean that is only sometimes what it
says it is, and ordering an application's own constructors against a
lifecycle phase is not something a starter gets to ask of anybody.

### 5. Lifecycle

`SmartLifecycle`, as in the server assembly, with one difference: **a lane
that cannot be reached is not a failure to start.**

A worker exists to be up when its tenant is up, and a deployment that
restarts them in the wrong order should converge rather than crash-loop. So a
lane whose tenant does not answer is registered anyway, logged once at WARN,
and retried by the poll loop that was going to run regardless. What *is* a
refusal is a lane the configuration got wrong — a tenant code that is not a
tenant code, a base that is not a URI, credentials that are rejected rather
than unreachable — because those do not converge.

`stop()` unregisters the lanes first and the step services second, so the
runner stops being offered work before it stops being able to perform it, and
waits for an in-flight step up to a configured grace. A step still running
when the grace expires is released with a reason rather than dropped: the
run returns to the tenant and a later cycle takes it, which is what the
`Outcome.failed` path already promises.

*Proved by:* a test that closes the context mid-step and asserts the run is
released rather than lost.

### 6. Driving one cycle, for the application's own tests

`Admissions.cycle()` exists because a test asks for one pass so it can say
what happened in it. An application testing its own step services needs the
same thing, and polling with an `Awaitility` block is a worse version of it.

So: `DboWorker`, a bean with `cycleOnce()` and `serving()`, available only
when the runner is configured not to start its own loop
(`dbo.worker.auto-start: false`). Not a dial on the running loop — a loop
that can be driven from outside while it is also driving itself is two
schedulers over one lane.

*Proved by:* a test using it, which is also the recommended shape for an
application's own step tests, and therefore the thing that ought to be
documented rather than the thing that ought to exist.

### 7. Events and telemetry out

The runner reports through the telemetry seam, and the exporter is installed
and idle without an endpoint. Two destinations beside it:

- **Micrometer**, where the application has a registry, conditionally. Steps
  performed, steps failed, how long each took, per step code and tenant.
- **Spring events**: `StepPerformed` and `StepFailed`, carrying the step
  code, the tenant and the outcome's counts. Not the work, and not the
  payload — a step's inputs are a tenant's data, and an event stream carrying
  them past the application's own listeners is a second copy of somebody's
  record in a place nobody decided to put it.

`DboContainerFault` is the same event the server assembly publishes, from the
same shared host.

### 8. What proves the whole thing

A Spring Boot application in `src/test` that declares `AdmitStep` as a bean,
configures one lane against a tenant from the guide's world, and performs a
run started over that tenant's step surface.

Four assertions: the step ran; its outcome reached the tenant; a second bean
declaring the same code was refused at refresh; closing the context released
an in-flight run.

The reachability question this module has to answer is the one the guidance
names — *who constructs it outside a test, and where is the state it writes
registered?* For a step service the answer must be: nobody constructs it, the
whiteboard finds it, and what it wrote is in the tenant's run records. A test
that constructs a `StepRunner` to prove a bean works has proved the seam,
which was already proved nine times, and not the wiring, which is all this
module is.

## Deliberately not done

- **No store.** If an application needs one it is a server, and that is the
  other module. A worker reaching a store is a worker that has stopped being
  able to run outside the deployment, which is the property the lane exists
  to preserve.
- **No `@Scheduled` integration.** The runner's loop is the runner's. An
  application scheduling cycles against a loop that is already running is two
  schedulers over one lane.
- **No retry policy of the application's own.** A step that fails is released
  with a reason and a later cycle may take it again. That is the tenant's
  decision to make, recorded in the run, and a client-side retry would be a
  second policy with no record.
- **No step declaration from the bean.** What steps exist is a tenant's
  declaration. A bean that could declare one would let a worker invent work
  its tenant never asked for.
- **No HTTP surface, and so no servlet bridge.** A worker answers nothing; it
  polls. The server assembly's bridge mounts the runtime's surfaces in Spring
  Web, and a worker has none to mount.
- **No Spring Security integration.** A worker holds a credential rather than
  checking one. What it needs is a token supplier that refreshes, which is
  step 3, and an application that wants the tenant's authority as a Spring
  `AuthenticationProvider` is running the server assembly.

## The commands

```
./gradlew :assembly:spring-boot-worker:bundleIndex
./gradlew :assembly:spring-boot-worker:test
./verify
```
