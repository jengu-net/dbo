---
title: Processes and steps
eyebrow: Guide
standfirst: >-
  The vocabulary of work in one page: a process and its steps, what a step
  takes and may write, a run, its result, and who may ask for and perform one.
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

## A run

Performing a step once is a **run**, and a run is a record in the tenant's own
store: versioned, searchable, carried by its backup, dropped with it. It has a
key, `<step>/<scope>`, by which the rest of the work model knows it, and an id
that addresses it. A run is held by whoever is performing it; one nobody holds
is over.

## A result

A step answers with an `Outcome`:

- `done(tally)` — what it counted, as named numbers;
- `.writing(Write.create(...), Write.update(ref, version, ...))` — the records
  it asks the tenant to write, as one result, committed whole or not at all;
- `failed(reason)`, or throwing — released with the reason, and taken again on
  a later cycle.

The tenant writes the result under the run, through its own face: the profile
validates it, identity rules hold, the membrane seals a person's identifying
elements, and the trail names the run. A result the tenant refuses ends the run
as `failed`, with the tenant's reason.

## Who asks, and who performs

Asking needs a credential that may act in `work`, issued by the tenant; that
credential is refused by the records surface. The run answers the client that
asked for it, at `/t/<tenant>/run/<id>`, as a FHIR `Task`, and nobody else
([hearing back](care-is-recorded.md#hearing-back)).

Performing needs the same kind of credential, bounded to the steps a
participant may take, over a lane: HTTP into one tenant, the application's own
port when embedded, or the deployment's substrate
([quick start](quick-start.md)). What a participant may take is the
intersection of its credential and what the step admits.

## What a step cannot do yet

- read anything its task did not carry in, or follow a reference from it;
- delete a record, or create one conditionally;
- write, when it is a step the deployment performs for every tenant or one a
  participant brought;
- hand content over, or carry it in, as part of a run.
