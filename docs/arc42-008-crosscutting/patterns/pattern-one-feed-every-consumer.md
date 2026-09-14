---
title: "One Feed, Every Consumer"
eyebrow: Pattern
standfirst: >-
  Paging through results, subscribing to changes, keeping a dependent copy
  current, and reconciling an appliance that was offline all weekend are one
  primitive, so every consumer is a name and a position.
template: essay.html
---

**Intent — make "how far behind is it" one question with one answer, whatever
kind of consumer is asking.**

## You are

Serving four things that look unrelated. A client paging through a large
result set. A subscriber that must not miss a change. A dependent copy of
shared reference data. An appliance in a building that spends weekends
disconnected.

Built separately, those are four sets of state, four failure modes, and four
different ways of being observed — so when something is behind, working out
which of the four is behind is the first hour of the incident.

## The question

What do those four actually have in common, and is it enough to build once?

## The forces

- Each has its own vocabulary — page token, subscription, replication slot,
  sync state — which hides that all four are a position in an ordered
  sequence.
- Separate mechanisms drift apart in exactly the properties you need to
  compare: ordering, replay, and how far behind something is.
- A consumer that must never miss an update has stricter needs than one that
  is merely paging, so the primitive has to serve both without being two
  things.

## Therefore

**Define one primitive: an ordered, replayable sequence with an opaque,
durable position. Given a source and a position, return a bounded chunk and
the next position.**

--8<-- "assets/diagrams/pattern-one-feed-every-consumer.svg"

<p class="diagram-caption">What differs between feeds is only what they are
ordered by: a result set by its sort keys, history by version, the change
record by commit sequence.</p>

The payoff is not elegance. It is that every durable consumer is the same kind
of thing, so four different questions collapse into one. A subscriber that was
down resumes rather than skips. A dependent copy is a consumer of the same
feed as everything else, so there is no separate synchronisation subsystem to
go stale. An appliance that was offline on purpose converges by replaying from
its position — nothing had to be queued for it, and nothing had to be retried
at it.

And an appliance sitting a long way back is not an alarm. It is behind by a
distance, which is a number you read off the same line as everyone else's.

## What each reader gets

- **A regulator** can be shown that a dependent copy is derived, with its own
  position on the record of changes.
- **A security officer** has one delivery mechanism to review rather than
  four.
- **An administrator** monitors one number per consumer, comparable across all
  of them.
- **The business** can promise an offline-capable site without a bespoke
  synchronisation product behind the promise.

## Relations

- **Builds on** —
  [Committed with the Change](pattern-committed-with-the-change.md);
  [Nobody Is Pushed](pattern-nobody-is-pushed.md).
- **Makes possible** —
  [A Cursor That Holds Still](pattern-a-cursor-that-holds-still.md).
- **Composed of** — Durable Subscriber, Polling Consumer and Message Store,
  from Enterprise Integration Patterns.
- **Related work** — Kreps (2013) on the log as a unifying abstraction, where
  paging, replication and subscription are the same read at different
  positions.
- **Written up in** — [Change, and who is listening](../change-and-who-is-listening/README.md).
  Proven by `REQ-DBO-FEED-ONE-PRIMITIVE`, `REQ-DBO-FEED-NAMED-CONSUMERS` and
  `REQ-DBO-FEED-PUSH-ACK-RESUME`.
