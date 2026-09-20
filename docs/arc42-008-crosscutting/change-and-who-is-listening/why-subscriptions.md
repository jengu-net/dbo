---
title: "One feed for every consumer"
headline: "Paging, subscribing and catching up are one thing"
eyebrow: Why DBO
standfirst: >-
  Four consumers that look unrelated — a client paging, a subscriber, a
  dependent tenant, an appliance that was off since Friday — turn out to be one
  kind of thing, observed the same way and asked the same question.
template: essay.html
---

Four mechanisms means four sets of state, four failure modes, and four
different ways of being observed. It also means that when something is behind,
finding out which of the four is behind is the first hour of the incident.

## The primitive

> A **feed** is an ordered, replayable sequence with an opaque, durable cursor.
> The whole contract is: given a source and a cursor, return a bounded chunk
> and the next cursor.

That one primitive serves all four is [One Feed, Every
Consumer](../patterns/pattern-one-feed-every-consumer.md). What differs between
feeds here is only what they are ordered by: a search result set by its sort
keys, history by version sequence, the change outbox by commit sequence.

--8<-- "assets/diagrams/one-feed-four-consumers.svg"

<p class="diagram-caption">The appliance is a long way back and nothing is wrong. It is behind by a distance, which is a thing you can read off the same line as everyone else's.</p>


## A change starts as a row committed with the write

Every change event originates as an outbox row written in the same transaction
as the change itself — [Committed with the
Change](../patterns/pattern-committed-with-the-change.md).

Distribution then runs on the store's own database rather than an external
broker. That is a deployment decision as much as an architectural one: a broker
is another thing to run, secure, back up, and reason about at two in the
morning, and the ordering guarantee it would provide is already available where
the data is.

<div class="takeaway" markdown>
No broker. No cache tier. No second source of truth about what has happened.
</div>

## A cursor is stable while rows shift

A keyset cursor is opaque to the consumer and stable under concurrent writes —
[A Cursor That Holds Still](../patterns/pattern-a-cursor-that-holds-still.md).
It is worth being blunt about the alternative, because offset paging quietly
pushes a real cost onto every caller: in the platform measured before this was
designed, the same defensive workaround stood at roughly a hundred call
sites.

## What this buys, in order of how much people care

**Subscriptions that cannot silently miss.** A subscriber is a durable
position. If it was down, it resumes; it does not skip.

**Dependent copies that stay honest.** A tenant holding a copy of shared
reference data — a zone's terminology, its broker declarations — is a consumer
of the same feed as everything else. There is no separate synchronisation
subsystem to go stale.

**Appliances that go offline on purpose.** An on-site appliance in a hospital
or a laboratory that spends a weekend disconnected converges when it comes
back, by replaying from its cursor. Nothing had to be queued for it and nothing
had to be retried at it.

**A lag number that means something.** Because all four are the same kind of
consumer, "how far behind is it" is one question with one answer, rather than
four questions whose answers cannot be compared.

<div class="further" markdown>
The three sources, the cursor contract, delivery semantics and what each
transport guarantees are in
[Change, and who is listening](README.md).
</div>
