# Performing a tenant's work from a Spring Boot application

One dependency, and a bean that is a step. The application polls the tenants
named in its configuration, performs what they offer and reports — over a
lane, and nothing else. It is handed no store and has no way to reach one,
which is the property that lets it run anywhere.

```kotlin
implementation("cloud.jengu.dbo:dbo-spring-boot-worker:0.1.0-SNAPSHOT")
```

```yaml
dbo:
  worker:
    identity:
      name: my-worker      # who a run records as having performed it
      version: "1"
    poll: 2s
    lanes:                 # one per tenant, and a worker usually holds several
      - tenant: hogwarts
        base: https://dbo.example/t/hogwarts/
        token:
          client-id: ${HOGWARTS_CLIENT_ID}
          client-secret: ${HOGWARTS_CLIENT_SECRET}
      - tenant: st-jerome
        base: https://dbo.example/t/st-jerome/
        token:
          client-id: ${ST_JEROME_CLIENT_ID}
          client-secret: ${ST_JEROME_CLIENT_SECRET}
```

```java
@Component
class AdmittingAPatient implements StepService {

    @Override public String step() { return "hogwarts.admission.admit"; }

    @Override public Outcome perform(Work work) {
        byte[] patient = work.inputs().get("patient").payload();
        work.progress().milestone("identified", Map.of("read", 1L));
        return Outcome.done(Map.of("admitted", 1L));
    }
}
```

That is the whole of what an application writes. Nothing constructs a runner,
registers itself, attaches a lane or names a tenant in code.
`samples/spring-boot-worker-app` is exactly this, running.

## The one rule

**A worker is handed no store, and cannot ask for one.** What arrives is the
run and the objects it named — there is nothing to fetch and nowhere to fetch
it from. An application that needs a store is a server, and that is
[the other module](../spring-boot-server).

This holds even where one is in the same process. A co-located worker reaches
its tenant over a lane like any other, because a worker that read the store
directly when it happened to be nearby would be one that could not be moved.

**The identity is named and versioned, and is not defaulted to an artifact
id.** A run records who performed it and under which version, and an executor
that cannot be reproduced cannot be held to what it did.

## Two ways a bean becomes a step

**Filling a vacancy.** The tenant declares the step in its own spec and the
bean arrives able to perform it. The step code is the tenant's, which is why
it is a string rather than a constant the application invented.

**Bringing one.** A service that returns its own declaration introduces it
beside its candidacy, so the catalogue learns it the moment presence can be
derived:

```java
@Override public Optional<StepDeclaration> declaration() {
    return Optional.of(StepDeclaration.of("hogwarts.admission.assay", "1", "work")
            .taking("specimen", "Observation"));
}
```

**Bringing one grants nothing, and that is not a footnote.** The tenant's own
step door is built from its spec and stays so: it refuses this step by name,
however long the application has been introducing it. A run of a brought step
is authored by the **tenant**, through the face's door, as a Task naming the
process, the step and a reference per declared slot — and the face takes it
because the catalogue it checks a run against now holds the declaration. So a
worker cannot invent work its tenant never asked for; it can only offer to do
something the tenant may then ask for.

What the worker then being offered that run needs is
[item 029](../../docs/arc42-011-risks-and-technical-debt/029-a-brought-step-is-not-offered-back/README.md),
which is open: the run is authored and accepted and is not offered back, while
a spec-declared step in the same cycle is performed.

## What the application gets

```java
@Autowired DboWorker worker;

worker.performing();   // step code -> what the container wired
worker.lanes();        // the tenants it performs for
worker.isRunning();
```

`start()` and `stop()` are `SmartLifecycle`'s and are called for you unless
`dbo.worker.auto-start: false`.

**There is nothing between them.** No `cycleOnce()`, no dial on the running
loop: a loop driven from outside while it is also driving itself is two
schedulers over one lane. A test that wants to decide *when* the asking begins
sets `auto-start: false` and calls `start()` — which is what
[`../spring-boot-test`](../spring-boot-test) does.

## The property surface

| | |
|---|---|
| `dbo.worker.identity.name`, `.version` | who a run records, reproducibly |
| `dbo.worker.poll` | how often a lane is asked (default 2s) |
| `dbo.worker.hold` | how long a claimed run is held (default 1m) |
| `dbo.worker.auto-start` | whether the loop starts with the context (default true) |
| `dbo.worker.grace` | how long `stop()` waits for a step in flight (default 30s) |
| `dbo.worker.lanes[].tenant` | the tenant code |
| `dbo.worker.lanes[].base` | where it answers |
| `dbo.worker.lanes[].token.client-id`, `.client-secret` | a client that tenant issued, refreshed as needed |
| `dbo.worker.lanes[].token.value` | a bearer token instead, for a deployment that mints them elsewhere |

**The credential must be one that may act in work.** A token admitted at the
step surface is refused by the tenant's records door, and that is the split
the two surfaces exist to make — holding one is deliberately not holding the
store.

## Starting before the tenant does

**A lane that cannot be reached is not a failure to start.** A worker exists
to be up when its tenant is up, and a deployment that restarts them in the
wrong order should converge rather than crash-loop. So a lane whose tenant
does not answer is registered anyway, logged once, and retried by the poll
loop that was going to run regardless.

Expect that in the log of a cold start: the credential is refused while the
tenant finishes coming up, once per cycle, and it clears. What *is* a refusal
is configuration the worker cannot converge on — a base that is not a URI,
credentials rejected rather than unreachable.

**Stopping is ordered.** The lanes go first and the step services second, so
the runner stops being offered work before it stops being able to perform it.
A step still running when the grace expires is released with a reason rather
than dropped: the run returns to the tenant and a later cycle takes it.

## Where a worker runs, and which carrier it uses

**Both halves in one application** is the ordinary one. It serves its tenants
and performs their work in one process, on one container:

```kotlin
implementation("cloud.jengu.dbo:dbo-spring-boot-server:0.1.0-SNAPSHOT")
implementation("cloud.jengu.dbo:dbo-spring-boot-worker:0.1.0-SNAPSHOT")
```

Nothing is declared to make that work. `core/dbo-embedded` unions every
bundle set it finds on the classpath, so the two assemblies share **one**
framework rather than starting two — which for the element bundle is the
difference between holding a parsed set of FHIR definitions once and holding it
twice, measured at 100 to 215 MB a copy.

**A worker of its own is for scaling**, and it is still part of the
deployment. The performing side and the serving side do not grow together: a
step that runs for a minute over a large payload wants replicas, and serving a
FHIR read does not. So a separate application — several of them, or a pod per
step — takes the same beans and the same lanes, stateless over the tenants it
is handed. Parallel runners claiming from one lane is the design and not a
race: a claim is the scheduler, and two runners introducing an identical
declaration co-introduce without refusal.

**Almost every worker serves many tenants.** A lane is per tenant and the
configuration is a list, so one worker holding a dozen of them is the ordinary
shape rather than a special case.

**A worker belonging to somebody else is the third case**, and the only one
HTTP is for: another organisation's application performing a step for a tenant
it does not run — a laboratory, a tenant's own edge device. It reaches the
deployment over the lane and nothing else, with a credential that tenant
issued, and has no business near the deployment's database.

So the carrier follows the organisation rather than the process boundary:

| the worker | carrier |
|---|---|
| beside the serving half, one process | the substrate |
| its own process, part of this deployment | the substrate |
| another organisation's application | HTTP |

**Today every worker built on this assembly polls over HTTP**, including the
two that should be on the substrate. It is not a missing property name:
`dbo-stream` is not in the worker assembly's bundle set, so `StreamLane` is not
installed, and `DboWorkerProperties` carries no `dbo.framework.*` passthrough
that would reach the activator which does install one.
[Item 031](../../docs/arc42-011-risks-and-technical-debt/031-a-worker-in-the-deployment-takes-the-substrate/README.md)
is that work. The runner cannot tell which carrier brought a run, so it is
wiring and nothing a step service sees.

Neither shape changes a line of a step service, which is the point of the lane.
Moving between them is configuration.

**What does not change either way** is that the worker is handed no store. It
takes work over a lane and reports over the same one, whichever carries it —
which is why the carrier can change without a bean noticing.

**Each half's configuration reaches the container.** Both assemblies declare
the container bean under a condition that it does not already exist, so one of
them builds it — and it is built from every `FrameworkContribution` the host
publishes rather than from the builder's own properties. A host that set one
property to two values is refused at refresh, naming the property and both
values, because there is no correct answer available to it.

## What this deliberately does not do

- **Hold a store.** See the one rule.
- **Integrate with `@Scheduled`.** The loop is the runner's. An application
  scheduling cycles against a loop already running is two schedulers over one
  lane.
- **Carry a retry policy of its own.** A step that fails is released with a
  reason and a later cycle may take it again. That is the tenant's decision,
  recorded in the run; a client-side retry is a second policy with no record.
- **Serve anything.** A worker answers nothing, it polls — so there is no
  servlet bridge here, and nothing to mount.
- **Integrate with Spring Security.** A worker holds a credential rather than
  checking one. An application that wants the tenant's authority as an
  `AuthenticationProvider` is running the server assembly.

## Beside this

- [`core/dbo-embedded`](../../core/dbo-embedded/README.md) — the host. It
  names no framework, so an application wanting none uses it directly.
- [`../spring-boot-server`](../spring-boot-server) — the serving half. An
  application holding both gets **one** framework.
- [`../spring-boot-test`](../spring-boot-test) — testing an application built
  on either.

## The commands

```
./gradlew :assembly:spring-boot-worker:test
./gradlew :assembly:spring-boot-worker:bundleIndex   # what a host installs
./gradlew :samples:spring-boot-worker-app:run
```
