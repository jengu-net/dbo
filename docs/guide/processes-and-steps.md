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

Who owes the next act follows from those: a claimed run waits for its owner, an
unclaimed one open to automation waits for a machine and is nobody's card, and
one open to people alone waits for a person. The people's list is
`Task?status=ready&performer-type=person`, or `awaiting(Awaits.PERSON)` in the
asking vocabulary. A machine is offered nothing on it and is refused when it
claims; a person takes a task with their own token from the tenant's identity
provider, as the role they hold there
([what automation may not take, a nurse does](work-leaves-and-comes-back.md#what-automation-may-not-take-a-nurse-does)).

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
