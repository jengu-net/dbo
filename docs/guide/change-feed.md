---
title: The change feed
eyebrow: Guide
standfirst: >-
  Every write lands on an ordered, replayable feed — one per domain, with named
  consumers that hold their own durable position, so a reader that was away
  resumes instead of missing the interval.
template: essay.html
---

Something always needs to know what changed. A projection to rebuild, a
downstream system to notify, a meter to advance, an index to refresh.

The usual answer grows in three stages: a `changed_at` column and a poller, then
a `changes` table because the column could not express a delete, then a queue
beside the database because two readers started overwriting each other's
progress. By the third stage the question *did anybody miss anything* has no
answer.

This store has one feed, and the position belongs to the store rather than to
the reader.

## Ordered, replayable, gap-free

Three properties, and each rules out a failure the hand-rolled version has.

**Ordered** — by commit, not by wall clock, so a transaction that started
earlier and committed later cannot appear to have happened first.

**Replayable** — a consumer can be moved back deliberately and read the same
sequence again. That is how a projection is rebuilt without anybody exporting
and reimporting anything.

**Gap-free** — nothing is skipped between two reads. The cursor is ordered
xid-major, so no commit can land behind a position already handed out. This is
the property a `changed_at` column cannot give you at all, and the one whose
absence you discover long afterwards.

Delivery is **at-least-once**. A consumer that crashed after acting and before
acknowledging sees its batch again, so what a consumer does must tolerate a
repeat. That is the honest guarantee; exactly-once across a process boundary
is not available and claiming it would just move the duplicate somewhere less
visible.

## One feed per domain

A tenant's records are partitioned into domains, and each carries a feed of its
own. Ask a tenant what it holds:

```bash
--8<-- "docs/guide/examples/snippets/feed-domains.sh"
```

```
audit
definitions
identity
r5
work
```

Four of those are the same everywhere. `audit` is [the trail](the-trail.md),
`work` is runs and their progress, `identity` is credentials and delegations,
and `definitions` holds the definitional types — code systems, value sets,
profiles. The fifth is the tenant's own records, and it is named for the face
it speaks.

!!! info "Which domain a type lands in follows from the type"

    A definitional type goes to `definitions` and everything else to the
    face's domain. It is a property of the type rather than of the tenant —
    so a tenant holding *only* definitional types, like a zone or a face root,
    has no records in its face's domain at all. Anything reading a feed has to
    know which domains a tenant actually carries rather than assuming.

Separating them is what lets one thing follow the trail without also being
woken by every observation, and a projection follow definitions without
reading a single patient.

## Named consumers hold a durable position

A consumer reads under a name, and the store remembers where that name got to.
Ask what is reading and how far behind:

```bash
--8<-- "docs/guide/examples/snippets/feed-consumers.sh"
```

```
definitions  shapes.hogwarts  lag 0
```

The position is in the tenant's database, not in the reader. That single
decision is what the third stage of the hand-rolled version was reaching for:

- a reader that was **down for an hour** resumes where it stopped rather than
  missing the hour
- two readers of the same feed cannot consume each other's progress, because
  they are different names
- **lag is a number you can alert on** — undelivered events behind the head,
  per consumer, rather than a guess

And a consumer that has caught up looks exactly like one that has nothing to
do, because it is the same thing. Absence is not inferred from silence.

## How an application reads it

Inside the container, by registering an observer against the stream it wants:

```
dbo.tenant.domain   = work
dbo.tenant.consumer = billing
dbo.tenant.target   = (dbo.tenant.zone=rl)
```

It names the domain, the durable consumer name it reads as, and — optionally —
which tenants it is for. One reader is started per tenant that matches and
carries that stream, each with its own position. A batch is acknowledged only
after the observer returns, which is what makes the at-least-once guarantee
real rather than decorative.

The consumer name is required, and that is the point of the interface rather
than an inconvenience: without a durable position it would be a callback, and a
callback loses everything that happened while it was not running.

!!! warning "There is no door onto the feed from outside the container yet"

    Consuming is an in-process API. Over HTTP a tenant will report what is
    reading it and how far behind, as above, but nothing outside can create a
    consumer, read a page or acknowledge a position.

    So an application that runs beside the store rather than in it reaches
    changes another way today — a replication lane to a tenant it is entitled
    to, or work. This is a gap and is written here as one rather than left for
    you to discover.

## What you would otherwise have written

A `changed_at` column, and the discovery that it cannot express a delete.

So a `changes` table, and a trigger to fill it, and a decision about how long
to keep it that nobody revisits until it is large.

A cursor per reader, kept by the reader, and the incident where one was
restored from a backup and silently reprocessed four days.

A queue beside the database, with its own delivery semantics to reconcile
against the database's — and the question nobody can answer afterwards, which
is whether anything was missed, because the only record of what was delivered
was in the thing that was down.
