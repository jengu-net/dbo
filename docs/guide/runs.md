---
title: Reaching data through a run
eyebrow: Guide
standfirst: >-
  A run is started over the documents it is for, and inside it those documents
  can be read and nothing else — so a credential that may do the work cannot
  use it to read past what the work was given.
template: essay.html
---

Every other chapter reaches records the direct way: a credential the deployment
holds, and any record you can name. That is the deployment's own
door, and [how it fits together](how-it-fits.md) said an application is meant
to come through a different one.

This is that door.

## The hospital says what it can be asked to do

A step is declared beside the types, in the same file, because it is the same
kind of statement — what this tenant does, and over what:

```json
"steps": [
  { "code": "hogwarts.admission.admit", "slots": { "patient": "Patient" } }
]
```

One slot, taking a `Patient`. A slot naming a type the tenant does not hold is
refused when the file is read, not when somebody first tries to use it.

## A credential for doing work is not a credential for reading

```bash
--8<-- "docs/guide/examples/snippets/worker-credential.sh"
```

```
200
```

That client holds `work` and nothing else. Point it at a record the ordinary
way and it gets nowhere:

```bash
--8<-- "docs/guide/examples/snippets/worker-cannot-read.sh"
```

```
403
```

Which is the whole reason the rest of this chapter is worth anything. If that
credential could read the record directly, coming through a run would be a
formality.

## Start a run, over something

```bash
--8<-- "docs/guide/examples/snippets/start-a-run.sh"
```

```json
{"run":"01a0af1c-…","step":"hogwarts.admission.admit",
 "context":"/t/hogwarts/run/01a0af1c-…/fhir"}
```

The run names the patient it is for, and what comes back is a **context** — a
path you resolve against the host you are already talking to. It is a FHIR base
URL: point a client at it and the ordinary resource paths work underneath.

It is a path rather than an absolute address on purpose. What a node is bound
to is not what a caller reached it by, and a context that guessed would hand
out links that work nowhere.

## Inside the run

```bash
--8<-- "docs/guide/examples/snippets/read-in-run.sh"
```

```
200
```

The patient the run was started over, read through the run. Nothing new was
granted to do it — the same credential that could not read this record a moment
ago can read it now, because now there is a run that says why.

## And nothing else

Another patient — the same type the step declared, held by the same tenant,
readable a moment ago by the deployment's own credential:

```bash
--8<-- "docs/guide/examples/snippets/read-outside-reach.sh"
```

```
404
404
```

**Not 403.** An answer that distinguishes *you may not see this* from *this is
not here* is a way to find out what a tenant holds: ask for a thousand ids and
keep the ones that come back forbidden. A withheld record and one that never
existed answer identically, and the test that proves this chapter asserts
exactly that — it compares the refusal for a real record against the refusal
for an invented id and requires them to be the same.

The second `404` is an `Observation`, a type the step never mentioned. Same
answer, for the same reason.

## What the context will answer for

```bash
--8<-- "docs/guide/examples/snippets/run-metadata.sh"
```

```
Patient
```

A capability statement for this context alone, naming the step's own types. An
integrator reads it to find out what this run can be asked, exactly as they
would read a tenant's.

## What this does not do yet

Being plain about the edges, because the chapter is small on purpose.

**Reach is what the run names, and nothing is followed from it.** If the
patient references an organisation, that reference is not resolvable here.
Traversal needs rules about depth and cycles that are worth designing once
rather than guessing now.

**Reads only.** The boundary is the claim, and a boundary is proven by what it
refuses to answer.

**The direct door is still open.** Every other chapter still works, and the
deployment's own credential still reads any record. Closing it is a separate
decision with its own migration; this chapter adds a door rather than removing
one.

## What you would otherwise have written

A permission model that answers *may this user read this record* — and then the
discovery that the honest answer depends on why they are asking, which the
model has nowhere to put.

So you add a reason: a case id, a ticket, an encounter, threaded through every
call as a parameter that nothing validates and everything logs. Then the audit
question arrives — *show me every access to this person and why* — and the
reason turns out to be a free-text field that was blank in a third of the rows.

Here the reason is the thing that grants the access, so it cannot be missing
from the record of it.
