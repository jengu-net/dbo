---
title: Processes and steps
eyebrow: Guide
standfirst: >-
  The vocabulary of work in one page: a process and its steps, what a step
  takes and may write, a run, who may take it, its result, what happens when it
  fails, and who may ask for and perform one.
template: essay.html
---

## A process is made of steps

A **process** is a named piece of an organisation's work — admissions, care
records, a directory check. A **step** is one stage of it, named
`<module>.<process>.<step>`: `hogwarts.admission.register`,
`care.visit.record`. The step is the unit everything attaches to: what data it
may touch, who may perform it, and what it may write.

A tenant declares the steps it offers in its own file, under `steps`
([a tenant opens](a-tenant-opens.md#a-declaration-is-the-whole-of-opening-a-clinic)).
The deployment declares the steps it performs for every tenant in its own
tenant's file, under `fleetSteps`
([a step for every tenant](a-step-for-every-tenant.md)). A participant may
bring a step a tenant never declared
([work leaves and comes back](work-leaves-and-comes-back.md#a-step-the-tenant-never-declared)).
A step code belongs to exactly one of those levels.

## Slots: what a step takes

Each slot is one of three shapes:

| Declared as | Filled with | Handed to the step as |
|---|---|---|
| `Reference(Patient)` | a reference the tenant holds, or a search it resolves when the run is created | the record, resolved |
| `Patient` | an object, sent with the run | the object, with no id and no version |
| `Observation[]` | several objects | a list, in the order sent |

An application fills them with `DboInitiator.Slot.reference`, `object`,
`objects` and `matching`. A slot the step does not declare is refused by name,
and so is a declared one left unfilled.

## `writes`: what a step may ask for

`writes` lists the types a step's result may ask the tenant to write. A record
of any other type, or one whose own type is not the one its write names, is
refused before anything is read. A step that declares nothing writes nothing.

## `automate`: when a machine may take it

A person may take any open task. `automate` says when automation may take one
as well, as a FHIRPath condition over what the task is given:

```json
{ "code": "care.results.review", "slots": { "result": "Reference(Observation)" },
  "automate": { "when": "result.interpretation.coding.code = 'N'" } }
```

The tenant decides it once, as the task is authored — the inputs are fixed from
then on, so the answer cannot change — and a task it does not admit is open to
people alone. The condition is a path from a slot compared with `=` or `!=`, or
`.exists()`, joined by `and` and `or`; anything else is refused when the tenant
is declared, and so is a condition that reads an element identifying a person.
A step that says nothing is open to automation.

## `retry`: which failures pass

```json
"retry": { "on": ["unreachable", "lapsed"], "after": "PT1M", "attempts": 5 }
```

`unreachable` is the store, or a system the step reaches, not answering;
`lapsed` is a claim nobody extended. A lapse passes only where a step says so,
because an executor that died said nothing about why. What happens to the
others is [below](#when-a-run-fails).

## `answers`, `collect` and `purpose`: what the asker reads back

```json
{ "code": "care.records.correct",
  "slots": { "record": "Reference(Patient)", "corrected": "Patient" },
  "writes": ["Patient"], "answers": "ward", "collect": "PT15M", "purpose": "TREAT" }
```

`answers` names an audience under the tenant's `disclosure.perAudience`, and
`collect` how long after the result is written the application that asked may
collect through the run: what it was given, as `Type/id`, and each version it
produced, as `Type/id/_history/n`. The audience's `types` bound what it may
collect and its `reveals` is what a collection of a person shows; the request
cannot raise either. An audience that reveals `include` needs `purpose`, and a
collection shows the person whole only when its `Purpose-Of-Use` header states
the same code — otherwise, the strict mode. Each collection is a reading on
the trail. The two come together or not at all, an undeclared audience is
refused, and a step that says neither leaves its asker the answer alone
([reading back what was written](care-is-recorded.md#reading-back-what-was-written)).

## `starts`: when a step starts itself

`starts` names the one occasion on which the tenant starts a step itself:

```json
{ "code": "ward.rounds.walk",
  "starts": { "every": "0 0 * * * *", "missed": "once", "keep": "P30D", "where": "origin" } }
{ "code": "ward.orders.assign", "slots": { "order": "ServiceRequest" },
  "starts": { "change": "ServiceRequest?status=active", "keep": "P7D", "where": "everywhere" } }
{ "code": "ward.orders.chase", "slots": { "order": "ServiceRequest" },
  "starts": { "due": "ServiceRequest?status=active", "at": "authoredOn", "after": "PT24H",
              "keep": "P7D", "where": "origin" } }
```

- `every` is a calendar rule of six fields, from seconds, firing at most once a
  minute. The step takes no slots. `missed` says what the ticks missed while no
  node ran become when one comes back: `once`, one run for the last of them, or
  `skip`. Either way the step says how many there were.
- `change` is a search. Each version of a record it finds starts one run, with
  the record in the step's one slot of that type. A record it does not find
  starts nothing.
- `due` is a search too. A record it finds starts one run once the moment its
  `at` element names, plus `after`, has come. A record the search no longer
  finds by then starts nothing.

Each run is keyed by what started it: `tick/<instant>`, `change/<id>/<version>`
or `due/<id>`. However many nodes notice the occasion, there is one run of it.
Every run started this way is a child of the tenant's **system run**,
`dbo.tenant.system/serve/<tenant>`, and has no requester.

`keep` is how long those runs are kept once they are over; open ones are never
removed. `where` says whether the step starts at the tenant's origin, on its
[places](one-tenant-in-two-places.md), or at both. A place starts its steps on its
own clock with its origin away. A change to `starts` alone is taken while the
tenant serves.

The clock is the deployment's durable layer, in the tenant's own database. An
application hosting the store may bring its own scheduler through
`TenantRuntimeManager.stepScheduler`. The store decides what a tick starts,
whether the step is paused, and what a missed tick becomes; the scheduler says
when a tick is due.

### Reading and steering them

`/t/<tenant>/steps` answers a `GET` with each self-starting step:

- what starts it, and whether it is paused;
- its last tick, its last run and how that run stands;
- its next tick, how many runs it has started and how many it has let go;
- how many ticks were missed and how many of those were skipped.

`POST <step>/now` starts a run now, as the asker's. The next tick still comes
when it was due. `POST <step>/pause` and `POST <step>/resume`, with a `because`,
are recorded as acts under the system run, naming who did it. Reading and
running need a `work` credential. Pausing and resuming need `supervise` over
the step.

## A run

Performing a step once is a **run**, and a run is a record in the tenant's own
store: versioned, searchable, carried by its backup, dropped with it. It has a
key, `<step>/<scope>`, by which the rest of the work model knows it, and an id
that addresses it. It is rendered as a FHIR `Task`, and keeps three facts apart
where a `Task` keeps them:

| Fact | `Task` element | |
|---|---|---|
| where it stands | `status` | `ready`, `in-progress`, `on-hold`, `completed`, `failed`, `cancelled` |
| who holds it | `owner` | an executor as a `Device`, a person as the `PractitionerRole` they took it as |
| who may take it | `performerType` (R4), `requestedPerformer` (R5) | `automation` and `person`, or `person` alone |
| not before | `restriction.period.start` (R4), `requestedPeriod.start` (R5) | for a run held back after a failure that may pass |
| why | `statusReason` | the failure, or why automation may not take it |
| when | `authoredOn`, `lastModified`, `meta.lastUpdated` | authored, and last moved, where it was performed |
| who asked | `requester` | a client as a `Device` under `urn:dbo:auth:client-id`, a person by reference |
| what it is over | `input` | each value a slot holds, as a reference |
| what it is part of | `partOf` | the parent's `Task` by reference, and its key under `urn:dbo:run` |
| where it was performed | extension `performed-at` | the place, for a run a place handed home |

Who owes the next act follows from those: a claimed run waits for its owner, an
unclaimed one open to automation waits for a machine and is nobody's card, and
one open to people alone waits for a person. The people's list is
`Task?status=ready&performer-type=person`, or `awaiting(Awaits.PERSON)` in the
asking vocabulary. A machine is offered nothing on it and is refused when it
claims; a person takes a task with their own token from the tenant's identity
provider, as the role they hold there
([what automation may not take, a nurse does](work-leaves-and-comes-back.md#what-automation-may-not-take-a-nurse-does)).

### A screen of work

A screen lists the roots and opens each onto its parts:

```
GET /t/<tenant>/fhir/Task?part-of:missing=true&_sort=-_lastUpdated&_count=20
GET /t/<tenant>/fhir/Task?part-of=Task/<id>
```

A page that is not the last links to the next. From inside an application the
asking vocabulary says the same, and answers with the same fields:

```java
Questions.Work work = tenants.asking("hogwarts").orElseThrow().work();
Questions.Page page = work.roots().newestMovedFirst().page(20, null);
Questions.Page parts = work.childrenOf(page.runs().get(0).id()).page(20, null);
```

`key(...)` finds one run by its key, and `page.next()` is the cursor for the
following page.

### A run for one participant

Some work only one participant can do: report on the appliance it is, read
the instrument plugged into it. The application starting the run names that
participant, as a `ContactListener` named the worker when it was heard: its
`client`, its executor's name, or both.

```json
{ "inputs": { "patient": "Patient/7" }, "for": { "client": "hogwarts.ward-1" } }
```

`RunInitiator.starting(tenant, step, inputs, key, addressee)` says the same
from inside an application. Only that participant is offered the run and may
claim it; any other holding the step is refused by name. While it is away the
run waits for it, through releases and returns, and its `Task` names it as the
`owner`, a `Device` identified under `urn:dbo:auth:client-id`, until it is
taken. A run naming nobody is open to every participant holding its step, and
the first claim wins.

## A result

A step answers with an `Outcome`:

- `done(tally)` — what it counted, as named numbers;
- `.writing(Write.create(...), Write.update(ref, version, ...))` — the records
  it asks the tenant to write, as one result, committed whole or not at all;
- `failed(reason)`, or throwing — released with the reason, and routed as
  below.

The tenant writes the result under the run, through its own face: the profile
validates it, identity rules hold, the membrane seals a person's identifying
elements, and the trail names the run. A result the tenant refuses ends the run
as `failed`, with the tenant's reason.

## When a run fails

What failed decides where the run goes:

- a fault the step declared in `retry` returns it `on-hold`, open to automation
  again after `after`, and counts the attempt; past `attempts` it goes to a
  person;
- a fault in the record ends it `failed`, because trying again would be refused
  in the same words;
- anything else returns it `ready` and open to people alone, with the failure as
  its reason — a fault nobody said would pass is seen rather than retried without
  end.

A person looking at it may perform it by hand, end it, or reopen it with a
reason, saying whether automation may take it again.

## Who asks, and who performs

Asking needs a credential that may act in `work`, issued by the tenant; that
credential is refused by the records surface. The run answers the client that
asked for it, at `/t/<tenant>/run/<id>`, as a FHIR `Task`, and nobody else
([hearing back](care-is-recorded.md#hearing-back)).

Performing needs the same kind of credential, bounded to the steps a
participant may take, over a lane: HTTP into one tenant, the application's own
port when embedded, or the deployment's substrate
([quick start](quick-start.md)). What a participant may take is the
intersection of its credential, the scope the step admits, and whether the task
is open to automation at all.

## What a step cannot do yet

- read anything its task did not carry in, or follow a reference from it;
- delete a record, or create one conditionally;
- write, when it is a step the deployment performs for every tenant or one a
  participant brought;
- hand content over, or carry it in, as part of a run.
