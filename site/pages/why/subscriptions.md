---
title: Four problems that are one problem
eyebrow: Why DBO
standfirst: >-
  Paging through results, subscribing to changes, keeping a dependent copy
  current, and reconciling an appliance that was offline all weekend. Most
  systems grow four mechanisms for those. This one has a single primitive.
template: essay.html
---

Four mechanisms means four sets of state, four failure modes, and four
different ways of being observed. It also means that when something is behind,
finding out which of the four is behind is the first hour of the incident.

## The primitive

> A **feed** is an ordered, replayable sequence with an opaque, durable cursor.
> The whole contract is: given a source and a cursor, return a bounded chunk
> and the next cursor.

That is all of it. What differs between feeds is only what they are ordered by:
a search result set by its sort keys, history by version sequence, the change
outbox by commit sequence.

The payoff is not elegance. It is that **every durable consumer is the same
kind of thing** — a name and a position. A client paging through results, a
subscriber, a dependent tenant and an appliance that has been off since Friday
are all observed identically. Progress, lag and replay mean the same thing for
each of them, and there is one place to look when any of them is behind.

--8<-- "assets/diagrams/one-feed-four-consumers.svg"

<p class="diagram-caption">The appliance is a long way back and nothing is wrong. It is behind by a distance, which is a thing you can read off the same line as everyone else's.</p>


## A change starts as a row committed with the write

Every change event originates as an outbox row written in the same transaction
as the change itself.

There is therefore no separate publish step, and no window in which the write
succeeded and the notification did not. That window is how systems end up with
consumers that are permanently and subtly behind reality — not badly enough to
notice, just badly enough to be wrong.

Distribution then runs on the store's own database rather than an external
broker. That is a deployment decision as much as an architectural one: a broker
is another thing to run, secure, back up, and reason about at two in the
morning, and the ordering guarantee it would provide is already available where
the data is.

<div class="takeaway" markdown>
No broker. No cache tier. No second source of truth about what has happened.
</div>

## Cursors are positions, not offsets

This is worth being blunt about, because offset paging quietly pushes a real
cost onto every caller.

Rows shift while you page. So callers deduplicate on identity, keep defensive
page cursors, and carry the duplicate-window problem around in application
code. In the platform measured before this was designed, that was roughly a
hundred call sites of the same workaround.

A keyset cursor is opaque to the consumer and stable under concurrent writes.
The work is designed out rather than worked around.

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
[Change, and who is listening (§6, §10)](../docs/arc42-008-crosscutting/change-and-who-is-listening.md).
</div>
