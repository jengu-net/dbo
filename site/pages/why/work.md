---
title: Work is a record, not a queue
eyebrow: Why DBO
standfirst: >-
  An exchange between two organisations is a process with obligations, not a
  file drop. Somebody has to hold the record of it — what has to be done, who
  is entitled to do it, who is doing it now, and what happened.
template: essay.html
---

Almost none of the work happens where the store is. A sample is analysed on an
instrument in a laboratory. A consignment is inspected at a border. A
declaration is signed by a supplier's own system. A great deal of it is a
person at a screen deciding something.

Some of those places sit behind a router with no public address. Some are
offline for a weekend. Some belong to organisations that are not on speaking
terms. So the store never reaches out to any of them, and the design follows
from that constraint rather than working around it.

## Three words carry most of it

A **process** is a named piece of work with steps — validating a batch of
results, publishing a product passport, applying a configuration.

A **step** is one stage of it, and it is the unit everything attaches to: who
may perform it, what it consumes and produces, what it is allowed to report.

A **run** is one attempt at one step.

## The one decision everything else follows from

A run is an ordinary record in the tenant's own store. Not a message on a
queue. Not a row in a scheduler's private table.

That sounds like an implementation detail and is not. Because a run is a
record, it has history, an audit trail and an owner — every record here does.
It can be listed, counted and read by whoever is entitled to, through the same
interface as everything else, with nothing special built for looking at work.
And it survives a restart of anything at all, because it was never in flight to
begin with.

A queue would have given none of that, and would have needed its own separate
answer to each.

<div class="takeaway" markdown>
Work is not a subsystem beside the store. It is the store, holding a different
kind of record.
</div>

## Nobody is pushed. Everybody pulls

The store does not call out to a laboratory instrument or an inspector's
handheld. Participants ask what is available to them, take it, and report back.
An organisation behind a hostile network, or one that is simply asleep, does
not need a hole opened towards it and does not fall out of step by being
unreachable.

Taking work is a claim, and a claim is checked against two things at once: the
credential the participant holds, and what the step declares about who may
perform it. Neither alone is enough. That intersection is the same shape that
governs jurisdiction and override elsewhere in the store — the outer party
declares the set, the inner one chooses within it and may narrow, never widen.

## The number nobody else can tell you

When work is offered and no automated executor takes it, it falls through to a
person. That is expected, and often correct: some steps should be somebody's
decision.

What matters is that the fall-through is **counted**, per step and per zone.
That number is your automation backlog stated as a fact rather than as an
opinion — the list of exactly which stages of which processes are still costing
somebody's afternoon, ranked by how often. Most organisations arrive at that
list through a consultancy exercise. Here it is a query.

## Two shapes of run, and why the difference matters

A **pipeline** runs over a known set of items and closes when every item has
reached a terminal state.

A **sweep** converges on a condition and closes when it finds nothing left to
do. A sweep is *found* rather than started, which is the part worth
understanding: if the process crashes halfway through, the next attempt resumes
the same run instead of opening a second one running beside the first. Erasing
a person is a sweep, and "started twice" would be a very bad property for it to
have.

<div class="further" markdown>
The full treatment — the vocabulary, executor resolution and its precedence
rules, what a run may report, and the proofs for each — is
[Processes and work (§8)](../docs/arc42-008-crosscutting/processes-and-work.md)
in the specification.
</div>
