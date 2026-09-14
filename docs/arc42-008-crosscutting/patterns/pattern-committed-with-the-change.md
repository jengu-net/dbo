---
title: "Committed with the Change"
eyebrow: Pattern
standfirst: >-
  Every change event is a row written in the same transaction as the change.
  There is no separate publish step, and no window in which the write
  succeeded and the notification did not.
template: essay.html
---

**Intent — remove the interval in which the store and everyone watching it
disagree about what has happened.**

## You are

Letting other systems react to changes. The obvious arrangement is to write
the change, then tell a broker. Most of the time both happen.

The rest of the time one happens. The write succeeds and the publish fails, or
the process dies between them, and a consumer is now permanently behind by one
event that nothing will ever resend.

## The question

How do you make a change and its notification impossible to separate?

## The forces

- Two systems cannot be updated atomically without something that spans them.
- Publishing first risks announcing a change that then fails to commit, which
  is worse.
- Retrying the publish needs somewhere durable to remember it from — and that
  somewhere is a database.
- A broker also has to be run, secured, backed up, and reasoned about at two
  in the morning.

## Therefore

**Write the event as a row in the same transaction as the change.**

--8<-- "assets/diagrams/pattern-committed-with-the-change.svg"

<p class="diagram-caption">Either both are in the database or neither is.
There is no third outcome to handle.</p>

Distribution then runs on the store's own database rather than an external
broker. That is a deployment decision as much as an architectural one: the
ordering guarantee a broker would provide is already available where the data
is, and there is no second source of truth about what has happened.

One subtlety is worth knowing because it is where naive implementations break.
Sequence numbers are assigned when a transaction starts, but transactions
commit out of order, so reading everything above your position is not safe on
its own. The record therefore notes the writing transaction, and readers
deliver only what is genuinely settled.

## What each reader gets

- **A regulator** is told that no change can exist without its event, which is
  what makes downstream records reconcilable.
- **A security officer** has one fewer system holding copies of change data.
- **An administrator** runs no broker, and backs up notifications by backing
  up the database.
- **The business** stops paying for the class of incident that begins "the
  data was right but the other system never heard".

## Relations

- **Builds on** — [The Tenant Is a Database](pattern-the-tenant-is-a-database.md).
- **Makes possible** —
  [One Feed, Every Consumer](pattern-one-feed-every-consumer.md).
- **Composed of** — Guaranteed Delivery, Transactional Client and Event
  Message, from Enterprise Integration Patterns; the transactional outbox as
  Richardson (2018) describes it.
- **Written up in** — [Change, and who is listening](../change-and-who-is-listening/README.md).
  Proven by `REQ-DBO-EVT-TRANSACTIONAL-OUTBOX` and
  `REQ-DBO-FEED-IDEMPOTENT-DELIVERY`.
