---
title: Records
eyebrow: Guide
standfirst: >-
  What makes two writes the same record, what happens to the version you
  replaced, and why the store refuses a second copy of a person rather than
  keeping both.
template: essay.html
---

A record here is the bytes you wrote. Everything else — the values a search
runs against, the links between records, the indexes — is derived from that
payload and can be rebuilt from it. Only the payload is authoritative.

That sounds like an implementation detail and decides an unusual amount. It is
why changing how records are indexed is a background operation rather than a
data migration, and it is why what you get back is what you stored rather than
a round trip through somebody's object model.

## Identity is declared, not guessed

Every mechanism that has to recognise *the same thing again* — a second write,
a record arriving twice from upstream, an import, a converter — needs one
answer per type. So each type declares exactly one identity class, and the
hospital's spec in [chapter three](tenants.md) chose them:

| Class | Identity is | Used for |
|---|---|---|
| **Identifier** | designated `{system, value}` pairs | things with real-world identity: a person, an organisation, a device |
| **Canonical** | a canonical url | definitions: code systems, value sets, profiles |
| **Internal** | the store-assigned id, and nothing else | records with no business identity: an observation, a by-product |

The id is never the identity. It is a UUID the store assigns, opaque on
purpose, and [chapter two](quick-start.md) showed what happens if you try to
choose one yourself.

## A second copy of a person is refused

Harry is already in the hospital's store from chapter two. Create him again,
with the same national identifier:

```bash
--8<-- "docs/guide/examples/check.sh:duplicate-identity"
```

```
409
```

Not a second record, and not a silent merge of the two. The type said a patient
is identified by that identifier system, so a create that would produce a
second record for one identity is a conflict, and the store says so.

This is the behaviour worth understanding early, because it is the one that
changes how you write client code. You do not need a read-before-write to
avoid duplicates, and you do not need a nightly job to find the duplicates you
made anyway.

## So you update by identity, not by id

If your system knows the national identifier and not the store's id — which is
the normal case when a message arrives from somewhere else — write against the
identity directly:

```bash
--8<-- "docs/guide/examples/check.sh:conditional-update"
```

```
200
```

One record still, now at version 2. The same call would have created it if
nothing matched, so the upstream system does not have to know or care whether
this person is already here. That is the whole of what a conditional write
buys: no lookup, no branch, no race between the lookup and the write.

## Nothing is overwritten

The previous version is still there, and so is every version before it. That is
the subject of [history and concurrency](history.md) — including reading a
version by its number, what a deletion is, and how two writers who both edit
this record are stopped from silently losing each other's work.

## A definition is identified by its url

Identity behaves differently for definitions, and the difference is the point
of having classes at all. The zone holds code systems, identified canonically:

```bash
--8<-- "docs/guide/examples/check.sh:canonical"
```

```
201
201
```

Two creates, both accepted, and **one record** at version 2 — the second write
replaced the first rather than conflicting with it. That is what you want for
definitions, which arrive repeatedly from packages and upstream zones and are
expected to move forward. It is not what you want for people, which is why a
person got a 409 three sections ago.

Same store, same verb, two outcomes, because the two types declared different
identity classes.

## What you would otherwise have written

A natural key column and a unique index, plus the code that catches the
constraint violation and turns it into something a caller can act on. A
read-before-write on every ingest path, and the race that survives it. An
upsert written once per type, slightly differently each time. A history table
and the triggers that fill it, or worse, an `updated_at` column and the
acceptance that the previous value is gone.

And the conversation, eventually, about which of your tables have natural keys
and which do not, held long after the tables were designed.
