---
title: Work is why data is accessed
eyebrow: Why DBO
standfirst: >-
  Every read of a regulated record needs a reason, and a permission is not one.
  The reason is a step of some process — so that is what access is granted to,
  and performing it leaves the proof.
why: 2
template: essay.html
---

A store holding regulated data has to answer a question an ordinary one never
asks. Not *who may read this* — every database can express that — but *why was
this read*. A permission answers the first and is silent on the second, so
every allowed read looks identical afterwards, and the second is most of what
the regulation is about.

Outside, nobody reads a record for no reason. The reason is a step of some
process: a sample is validated, a consignment is cleared, a passport is
published. And the step knows exactly what it needs — these documents, for
this decision, and no others.

## Access is granted to a step, not to somebody

So the step is where the reason already lives, and this store makes it the
mechanism rather than the paperwork. A step declares what it consumes and who
may perform it. A participant claims a run of it, and what arrives is what that
run named:

> A claimed run's inputs arrive with the work, resolved by the party that holds
> the objects; the runner's only read takes the run, a run the asking identity
> has not claimed is refused, and a run without slots delivers exactly nothing.

There is no general read behind that to fall back on, and the claim itself is
bounded twice over: what a participant may take is the intersection of what its
credential covers and what the step admits, and a lane's entitlement is stated
when the lane is provisioned — there is no implicit unrestricted, so the reach
of a remote participant never depends on a parameter somebody forgot.

Then the part that makes it auditable rather than merely careful. Performing
the step leaves a record, because a run is a record. That record is the proof
the work happened *and* the reason the data was read — not two artefacts to be
correlated later, one artefact. An auditor asking why this laboratory saw this
person's sample is answered by the run that made it necessary.

<div class="takeaway" markdown>
Access is not granted to somebody. It is granted to a step, for the length of
one run, and the record of the work is the record of the reason.
</div>

## The work is somewhere else

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

A run is an ordinary record in the tenant's own store.

That sounds like an implementation detail and is not. Because a run is a
record, it has history, an audit trail and an owner — every record here does.
It can be listed, counted and read by whoever is entitled to, through the same
interface as everything else, with nothing special built for looking at work.
And it survives a restart of anything at all, because it was never in flight to
begin with.

Each of those four is something a system that holds work elsewhere — a queue,
a scheduler's own table — has to go and answer for itself, separately. Here
they arrive with the record, because the store already gives them to every
record it holds.

--8<-- "assets/diagrams/work-is-a-record.svg"

<p class="diagram-caption">Nothing was added to get those four. They arrive with the record, in a store that already gives all four to everything it holds.</p>


<div class="takeaway" markdown>
Work is the store, holding a different kind of record.
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
[Processes and work (§8)](README.md)
in the specification.
</div>
