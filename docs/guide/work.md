---
title: Processes and steps
eyebrow: Guide
standfirst: >-
  Work is declared as a process with steps, and a step is the unit everything
  else attaches to: what data it may touch, who may perform it, and why.
template: essay.html
---

A job table has a row per thing to do, a status column, and a worker loop that
picks rows up. It works. What it cannot answer is the question that arrives
eighteen months later, usually from somebody with a legal reason to ask: *this
person's record was read on the fourth of March — why?*

The row is gone, or it was never the reason in the first place. The reason was
in somebody's head, or in a ticket in another system, or in a free-text field
that was blank in a third of the rows.

This store starts from the other end. The reason is declared first, as a
**step**, and doing the work is the only way to reach the data — so the reason
cannot go missing from the record of the access, because the reason is what
granted it.

## Process, step, run

Three words, used precisely throughout this section.

| Word | What it is | Analogy that is close enough |
|---|---|---|
| **process** | a named piece of work a tenant does, made of steps | the workflow |
| **step** | one declared unit within it — the thing access attaches to | the job *kind* |
| **run** | one performance of a step, over named documents | the job *row* |

A process is the shape. A step is what may be done and by whom. A run is the
act, and the run is a record like any other: versioned, queryable, carried on
the feed, kept in the tenant's own database rather than in a scheduler beside
it.

That last point is the one worth slowing down on. There is no separate workflow
database here. A run lives where the data it acted on lives, which is why
*show me every run that touched this person* is a query rather than a
correlation exercise across two systems with different retention rules.

Those three words are not just vocabulary for this chapter. They are in the
record. Here is a run of the hospital's one step, read back from the tenant's
own surface — [the next chapter](runs.md) is where it gets started, this is
what it leaves behind:

```bash
--8<-- "docs/guide/examples/snippets/run-as-a-record.sh"
```

```
process  hogwarts.admission
step     admit
holder   automation
input    patient = Patient/01a0af70-f38d-…
```

The step code `hogwarts.admission.admit` comes apart into the process it
belongs to and the step within it. The holder is the field this section keeps
returning to. And the input names the slot it filled.

!!! info "The subject is displayed, not linked"

    Read that last line carefully: the patient arrives as a *display* string,
    not as a resolvable reference. A run envelope discloses **what state the
    work is in, not who it is about** — so the credential that may list and
    monitor runs does not thereby acquire a route to the people they concern.
    Reaching the subject means going through the run's own context, which is
    exactly the boundary [Reaching data through a run](runs.md) demonstrates.

A run is read by its id. Searching the type is refused, and the refusal says
where to go instead — the trail is searched by run, which is
[The trail](the-trail.md).

## What a step declares

A step declaration is not a name and a callback. It states, up front, the whole
of what it is allowed to be:

| It declares | Which means |
|---|---|
| an **id** and a **version** | a run records the version it ran under, so a decision made last year can be reproduced against the rules that were in force |
| the **storage domains** it reads and writes | the blast radius, readable before anything runs |
| **slots** — ordered, named, each a shape | what a run of it is *about*; a slot naming a type the tenant does not hold is refused when the file is read |
| the **shapes** it consumes and produces | validated through the face's ordinary payload validation, not a second rulebook |
| its **milestones**, in order | where the work can be, as a closed vocabulary rather than a status string |
| its **actions** | what may be reported: closing needs `close`, reopening needs `reopen`, and a verb the step does not declare is refused |

The hospital's is deliberately the smallest one that can exist — one slot, one
type:

```json
"steps": [
  { "code": "hogwarts.admission.admit", "slots": { "patient": "Patient" } }
]
```

It sits in the tenant file beside the types, because it is the same kind of
statement: what this tenant holds, and what this tenant *does*.

!!! note "Declared where the tenant is declared"

    A step is configuration, not deployment. Adding one is an edit to the
    tenant's spec, and [Lifecycle](lifecycle.md) covers what happens when that
    file changes underneath a running tenant.

## Why the step and not the person

This is the design decision the rest of the section hangs off, so it is worth
stating plainly.

A permission model normally answers *may this user read this record*. The
honest answer is almost always *it depends why they are asking* — a clinician
may read the chart of the patient in front of them and not the chart of their
neighbour, and the difference is not a property of the clinician or of the
record. It is a property of the situation.

A user-and-role model has nowhere to put the situation. So the situation gets
smuggled in as a parameter — a case id, an encounter, a ticket reference —
threaded through every call, validated by nothing, logged by everything.

Here the situation *is* the grant. A run exists over named documents, and the
credential that may perform the step reaches those documents and no others.
[Reaching data through a run](runs.md) is that boundary, demonstrated against
the running world, including what a refusal deliberately does not reveal.

## Who performs one

A step says what may be done. What actually does it is an **executor**, and
executors declare themselves the same way steps do.

Resolution is deterministic, and it is deterministic in a specific way: it
walks an overlay chain — baseline, then zone, then organisation — and the most
local candidate that is both willing and permitted runs. Candidates are tried
one at a time, in declared order. Nothing races, because racing candidates make
the same input behave differently under load, and doubled effects are the kind
nothing outside the store can undo.

Whether a step is automated *here* is a declared switch on that same chain,
most local winning, read at the moment a claim is taken. Switching it off stops
the next automatic claim and leaves work already held alone — and it gives the
operator asking *who would run this step* exactly the answer the runner gets
when it asks to take it. One switch, one answer, which is why the console and
the runtime cannot drift apart.

Work no executor took falls through to a person, and that fall-through is
**counted per step and per zone**. It is the automation backlog stated as a
fact rather than an opinion — a number to alert on rather than a silence.

A run always says who holds it now — automation running, automation with a
retry scheduled, a person, or nobody. That field is load-bearing. It is how
*stuck* becomes a query.

## Runs that are about many things

A run over a thousand items where seven fail is **one run with a tally and
seven item outcomes**, and the remaining nine hundred and ninety-three are
still processed. This sounds obvious and is the thing hand-rolled batch jobs
get wrong most consistently: an exception at item 300 that abandons items 301
to 1000, discovered a day later.

Two kinds, and the difference matters when you design one:

- a **pipeline** closes when every item is terminal
- a **sweep** closes when the world agrees with what was declared

A reconciler modelled as a pipeline never ends, because there is always more
world. Choosing the wrong one is a design error the store can name rather than
a mystery about why a job never finishes.

## When something goes wrong

Failure here is a first-class state rather than an exception that lost a row.

A step service that fails or throws **releases** its run, with the reason. Never
closed, never lost — a later cycle may take it up. And failures are classified
rather than uniformly escalated:

- a record that is **wrong** reaches a person, because a person is what is
  needed
- a store that is **unavailable** is a retry and nobody's card, because waking
  somebody at 3am for a network partition trains them to ignore the pager

Where a condition is machine-checkable, fixing the cause **closes the run on the
next pass**. Closing by hand exists, but only for conditions a machine cannot
check — which keeps the manual close from becoming the way every run ends.

A closed run can be **reopened**, as a deliberate and recorded act through the
step's own `reopen` action. Undoing a judgment already made about work is its
own entitlement, separate from the entitlement to do the work — because the
person who may perform a step and the person who may overrule that performance
are not always the same person, and a model that conflates them has no
supervision in it.

## Steps a tenant cannot do without

A tenant's spec can declare the steps its work depends on. What happens when
one is absent is the part that took thought:

!!! warning "The tenant serves anyway"

    A missing mandatory step does not stop the tenant. It serves, and its runs
    queue — the system is asynchronous by design. What the declaration buys is
    **classification**: a mandatory step nothing has contributed is an incident
    raised by name, and it clears on its own as contributions come and go. The
    absence of a step nobody declared mandatory is not an incident at all.

    That is the difference between *this deployment is incomplete* and *this
    deployment is fine*, decided by a list somebody wrote deliberately rather
    than by whoever is awake at three in the morning.

Refusing to start would be the tidier-looking choice and the worse one. A
tenant that will not serve because one step is missing has converted a
degradation into an outage.

## What is reachable today

Being exact about this, because the section describes more than the HTTP
surface currently exposes.

Over HTTP, a tenant serves the step surface: declare steps in the spec, start a
run, and work inside its context. That is [Reaching data through a
run](runs.md), and every command there is executed against the sample world.

Everything else in this chapter — claiming work, reporting milestones, closing,
reopening, executors and their resolution — is reached over the **participation
lane** rather than the FHIR surface, by a runner that joins the tenant.
[Runners](runners.md) is that mechanism. The distinction is not cosmetic: work
is authored on the tenant's own surface and *performed* over the lane.

A document posted to that surface naming a declared step **becomes a run**,
minted through the same door every run comes through, and refused by name where
its rules are not met: an unknown step, an undeclared slot, a declared slot left
unfilled, a key already used. What is stored is the run, and it reads back as
the same document as it advances.

## What you would otherwise have written

A jobs table, a status enum, and the first argument about whether `FAILED` and
`ERROR` mean different things.

A worker loop, then a second one when the first could not be scaled
independently, then a leader election so the two do not take the same row.

Retry with backoff, written per job type, subtly differently each time — and
the dead-letter table that nobody reads, because nothing distinguishes the rows
that need a human from the rows that need a network.

A status field that accumulates values nobody removed, so `IN_PROGRESS_2` is
load-bearing and no one remembers why.

And then the audit question, which the jobs table cannot answer at all, because
the reason the data was read was never in it.
