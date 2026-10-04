---
title: Work leaves and comes back
eyebrow: Guide
standfirst: >-
  A step performed outside the store is a bean. It is handed what its task
  carries, answers with a result the tenant writes, and opens a sealed payload
  only where the work went — which is what the tenant's trail records as a
  reading.
template: essay.html
---

Meristem builds laboratory software. Their workers sit in practices all over
the country, each practice a tenant, and they run one synchronisation service
for all of them. Most of what it carries, it cannot read. When a worker needs
the specimen document, it is opened there, by that worker, and the
opening is what the practice sees in its trail as a reading; the hops that
carried it unopened are in the trail too, as travel. What Ines is not willing
to build is a copy of the store's work model on her side of the wire. The scene
is [US-DBO-WORK-ROUNDTRIP](../arc42-003-context/user-stories/us-dbo-work-roundtrip.md),
walked at Hogwarts by
[`WorkLeavesTheClinicAndComesBackIT`](https://github.com/jengu-net/dbo/blob/main/samples/spring-boot-server-app/src/test/java/cloud/jengu/dbo/samples/stories/WorkLeavesTheClinicAndComesBackIT.java).

## A step is a bean

The worker application holds no tenant, no database and no way to get one. A
bean implementing `StepService` is the whole of what it writes for a step:

```java
--8<-- "samples/spring-boot-worker-app/src/main/java/cloud/jengu/dbo/samples/worker/AdmittingAPatient.java:step"
```

`step()` names the step the tenant declared. `perform` is handed `Work`: the
run, and the objects its slots name, resolved by whoever holds them. There is
nothing to fetch and nowhere to fetch it from. Progress is reported as
milestones with counts, and the outcome is `done` with a tally or `failed` with
a reason — throwing is the same as failing. A failed run is released with its
reason: back to automation later only for a fault its step declared will pass,
and to the people's list otherwise
([processes and steps](processes-and-steps.md#when-a-run-fails)).

Who the worker is, how often it asks and where its lanes go are configuration:

```yaml
--8<-- "samples/spring-boot-worker-app/src/main/resources/application.yaml"
```

A run records who performed it, which is why the identity is named and
versioned rather than defaulted: an executor that cannot be reproduced cannot
be held to what it did.

## The same beans, in either JVM

Standing alone, the worker application scans its own package. Embedded in the
clinic's application, its beans travel as an auto-configuration instead:

```java
--8<-- "samples/spring-boot-worker-app/src/main/java/cloud/jengu/dbo/samples/worker/TheWorkersSteps.java:embedding"
```

None of the beans holds a URL or names a tenant. Whether work reaches them over
the application's own port, over HTTP from another JVM or over the deployment's
substrate is configuration ([quick start](quick-start.md)).

## A step whose result is records

When somebody arrives, the hospital comes to hold them and the stay they arrived
for. The step is given the person, because there is nothing for a reference to
point at yet, and answers with two records:

```java
--8<-- "samples/spring-boot-worker-app/src/main/java/cloud/jengu/dbo/samples/worker/RegisteringAPatient.java:step"
```

The worker writes neither. It says what should be written, and the hospital
writes it under the run, through its own rules, or refuses it. The two records
are one result, so an encounter about nobody is never left behind by a person
the hospital refused. What a step may ask for is the `writes` list beside its
slots in the hospital's declaration.

## Asking, as a participant

The same application can ask for work as well as perform it — same
credential, same enrolment:

```java
--8<-- "samples/spring-boot-worker-app/src/main/java/cloud/jengu/dbo/samples/worker/AskingForAnAdmission.java:asking"
```

The run is the hospital's, authored on its own door, and whichever entitled
performer takes it does. It answers the application that asked, on the
credential it asked with, and nobody else
([hearing back](care-is-recorded.md#hearing-back)).

## A step the tenant never declared

A participant can bring a capability rather than fill a vacancy. The one extra
method is a declaration:

```java
--8<-- "samples/spring-boot-worker-app/src/main/java/cloud/jengu/dbo/samples/worker/MeasuringASpecimen.java:step"
```

The runner introduces it beside the bean's candidacy, and the hospital's
catalogue learns the step. Bringing it grants nothing: the hospital's own step
door still refuses it by name, a run of it is authored by the hospital as a
`Task` naming the process, the step and a reference per slot, and what the
participant may take stays the intersection of its credential and what the
step admits.

## A step as a bundle of the clinic's own

The clinic ships one of its own steps as an OSGi bundle: the bundle registers
the step it performs, which is how a bundle contributes anything.

```java
--8<-- "samples/ward-bundle/src/main/java/cloud/jengu/dbo/samples/ward/ObservingTheWard.java:bundle"
```

A bundle performing a step belongs in the runner's class space, so it has to
be in the store's one framework. The clinic owns that framework: it creates it
with the properties the store names beside its own, installs its bundle, and
publishes it as a bean.

```java
--8<-- "samples/spring-boot-server-app/src/main/java/cloud/jengu/dbo/samples/server/OwningTheFramework.java:framework"
```

The assemblies find the bean and install the store into it instead of creating
a framework of their own. Hogwarts declares `hogwarts.ward.observe`, no bean
performs it, and the run completes, performed by the bundle over the clinic's
lane. The store checks the framework it is handed: one created without its
properties, a bundle that would take one of the store's shared packages for
itself, and a bundle that does not resolve are refused by name before anything
is served. When the application stops, the store takes its own bundles out and
leaves the framework to the clinic.

## A run's own context

A run started at a tenant's step door also answers as a FHIR base of its own,
`/t/<tenant>/run/<id>/fhir`, to a credential that may act in work — one the
records surface refuses outright. It reads the documents the
run was started over and nothing else: a record of a declared type the run was
not given answers exactly as an id nothing ever minted, so asking cannot reveal
what exists. Its capability statement lists only the step's types. When the
work is ended, at `/t/<tenant>/run/<id>/done`, the context answers its
performer as a run that never existed, so performing a step leaves no standing
way in behind it.

A step that declares an answer — `"answers"` naming an audience, `"collect"` a
duration — opens the same context to the application that asked for the run,
once the work is over and for that long. It reads what the run was given and
each version the run produced, at `Type/id/_history/n`, as the audience sees
it; `DboInitiator.collect` asks for one, and `collected` shuts the window
early ([reading back what was written](care-is-recorded.md#reading-back-what-was-written)).
While a participant performs the run, the asker reads nothing through it.

## What automation may not take, a nurse does

Hogwarts reviews its potassium results, and a machine may review only the
normal ones. The step says so where it is declared, as a condition over what
the task is given:

```json
{ "code": "care.results.review", "slots": { "result": "Reference(Observation)" },
  "automate": { "when": "result.interpretation.coding.code = 'N'" } }
```

The clinic asks for every review the same way, and the hospital decides who may
take each one as it authors the task, from the result itself. The worker's
`ReviewingAResult` is offered the normal ones and finishes them; an abnormal one
is open to people alone and waits on the people's list. Poppy signs in at the
ward screen through Hogwarts's own identity provider, and the ward screen asks
with her token:

```java
--8<-- "samples/spring-boot-server-app/src/main/java/cloud/jengu/dbo/samples/server/TakingATask.java:taking"
```

`waiting` is the people's list: ready, and open to people alone — asked across
the tenant's door as `Task?status=ready&performer-type=person`. `take` claims
the task as the `PractitionerRole` she holds there, so the task's `owner` is her
role, never an executor, on a lease she extends with a checkpoint. While she holds
it the run's context answers her and nobody else — another nurse holding the
same id is answered 404 — and the reading she makes through it lands on the
trail under her practitioner. `finish` completes it, and the clinic, which has
been told `ready` and then `in-progress`, is told `completed`.

## Whether a worker is there

The store tells the application when it is in contact with the workers of a
step, and the application decides what that means. Every request a worker
makes for a step is something the node heard; a worker that is woken rather
than polling, or that holds a long claim, says so with a heartbeat. The runner
sends one each cycle, with its counts per step under `dbo.runner`, and an
application adds its own under a namespace of its choosing:

```java
--8<-- "samples/spring-boot-worker-app/src/main/java/cloud/jengu/dbo/samples/worker/SayingHowItIsGoing.java:statistics"
```

The statistics are one JSON document, nested as deep as the worker likes and
read by nobody in the store. A node refuses a heartbeat over its limit — 64 KB
unless `dbo.heartbeat-limit` says otherwise — and nothing about a person goes in
one, because a heartbeat travels outside any sealed work.

The clinic listens for the workers that register patients. A listener names
its step and how long a worker may say nothing before it is unknown, and there
is no default: a listener without one stops the application at startup.

```java
--8<-- "samples/spring-boot-server-app/src/main/java/cloud/jengu/dbo/samples/server/NoticingTheWorkers.java:listener"
```

Contact is held in memory, on the node that heard the worker, and each event
names that node. When a node starts, every listener is told that everything
about its step is unknown there. Nothing about contact is in a tenant's records
unless a listener asks for it as work, as this one does: under a key of its own,
so the same transition noticed twice — by two nodes, or after a retry — is one
run, and the step writes the note through its result:

```java
--8<-- "samples/spring-boot-worker-app/src/main/java/cloud/jengu/dbo/samples/worker/RecordingContact.java:step"
```

`DboInitiator` is the Spring binding of `RunInitiator`. A plain bundle in the
container starts work with the `RunInitiator` service the container registers,
and reads the same answers.

## What the store guarantees

- **Inputs arrive with the work.** A claimed run's inputs are resolved by the
  party that holds them, and the runner's only read is the run.
- **A carrier reads the manifest and not the payload.** Work travels as a
  readable manifest — tenant, step, task, references — and a payload sealed to
  the participant that offered keys at enrolment. A service that only carries
  work never holds a key.
- **The trail tells travel from reading.** Each hop leaves a travel entry about
  the task; a participant that opened a payload leaves an access entry about the
  document, naming the run as its occasion. Most steps never ask, and leave no
  access entry because none happened.
- **The account comes home chained.** The events of a run's journey link each
  to the one before, rooted in the task the store minted, and the result that
  closes the run verifies the chain; a link that never came home leaves the run
  owed.
- **A result is written by the tenant, and a refused one ends the run.** A
  result refused for what it says — validation, an identity already held, a
  version that moved, an undeclared type — ends the run as `failed` with the
  tenant's reason, and nothing of it is written. A step that threw is released
  to automation again only for a fault its step declared will pass, and to the
  people's list otherwise.
- **Automation takes only what its step admits.** A task closed to automation
  is offered to no machine and refused to one that claims it; a person takes it
  with their own token, as the role they hold.
- **The run answers its initiator**, and anybody else is told it is not there.
- **An application may own the one framework.** The store installs into it,
  refuses one that lacks what it needs by name, and never stops it.
- **Contact is noticed, never stored.** A listener per step hears a worker
  appear, its statistics, and its silence past the listener's own threshold;
  a node start resets it. What is recorded is what a listener asks for as
  work.

The [joins table](../arc42-003-context/user-stories/us-dbo-work-roundtrip.md#joins)
names the test behind each.

## What the store cannot do yet

The story itself has no gap left: every leg is proven over the HTTP lane and the
stream. What an application cannot do yet is narrower than the story:

- **A brought step writes nothing.** A step a participant brings has no `writes`
  in any tenant's declaration, so its result can carry a tally and no records.
- **A step cannot delete, or create conditionally.** A result is creates, and
  updates against a version.
- **A run's context is read-only, and reaches only what the run names.** It
  follows no reference.
