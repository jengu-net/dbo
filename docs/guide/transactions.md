---
title: Bundles and transactions
eyebrow: Guide
standfirst: >-
  Several writes as one act, all of them or none — and the batch that answers
  for each separately when they are not one act at all.
template: essay.html
---

A visit is not one record. It is a person, an encounter, a handful of
observations and something about who did it — and they arrive together because
they happened together.

Writing them one call at a time means deciding what to do when the fourth fails
and the first three are already in. Every answer to that question is worse than
not having it.

## A transaction

```bash
--8<-- "docs/guide/examples/check.sh:transaction"
```

```
201 Created
201 Created
```

Two resources, one request, one commit. Either both are there or neither is,
and the response says what happened to each.

## The reference between them

Look at what the bundle said. The patient is written with a placeholder id —
`urn:uuid:admitted` — and the observation points at *that*, not at an id nobody
knows yet:

```bash
--8<-- "docs/guide/examples/check.sh:transaction-reference"
```

```
Patient/01a0af2b-87fc-7525-bf42-59ef1a4b3e86
```

The placeholder resolved to the record that was actually created. This is the
thing that makes a transaction worth having rather than merely tidy: the whole
graph can be written in one act, with its internal references intact, without
the client first inventing ids or making a round trip to learn them.

## One bad entry takes the rest with it

```bash
--8<-- "docs/guide/examples/check.sh:transaction-refused"
```

```
422
0 entries
```

The `Observation` has no `status`, which chapter six covered — and the
`Patient` beside it, which was perfectly valid, is not there either.

That is the promise. A partially applied visit is worse than a rejected one:
rejected, the sender retries; partial, somebody has a person with no encounter
and no way to know that is what happened.

## When they are not one act

Sometimes the entries are unrelated — a night's worth of messages, drained
together because that is how they queued, not because they belong to each
other. Failing all of them because one was malformed would be its own kind of
wrong.

```bash
--8<-- "docs/guide/examples/check.sh:batch"
```

```
201 Created
422
```

A `batch` answers for each entry on its own. The good one landed; the bad one
did not; the response says which is which, and the sender retries exactly what
failed.

**The choice is yours and it is not a detail.** `transaction` says *these
belong together*. `batch` says *these merely travelled together*. Sending a
batch when you meant a transaction is how a half-written visit gets into a
database, and the store cannot guess which you meant.

## What you would otherwise have written

A service method that opens a transaction and writes four things, then the
discovery that one of the four goes through a different service, and the
compensating logic that tries to undo the first three when it fails.

Then the id problem: the observation needs the patient's id, the patient does
not have one until it is written, so the client makes two calls and holds the
result — and now there is a window where the first call succeeded and the
second never happened, owned by nobody.

And eventually a "cleanup orphans" job, which is the shape that problem takes
once it has been ignored long enough.
