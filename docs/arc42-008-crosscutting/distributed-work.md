# Distributed work (§8)

## The store does not do the work

This engine is a store for regulated data, and the domain is a face over it
rather than a fact about it — clinical exchange today, and the same shape
wherever a published standard, competing parties and a provenance requirement
meet. Whatever the domain, the same thing is true: almost everything the record
is *about* happens somewhere the store is not.

A sample is analysed on an instrument in a laboratory. A model is exported from
the authoring tool of a firm that competes with the others on the project. A
consignment is inspected at a border. A component's declaration is signed by a
supplier's own system, which the store does not own and cannot call. And a great
deal of it is a person at a screen deciding something. Some of those places are
behind a router with no public address; some are offline for a weekend; some
belong to organisations that are not on speaking terms.

So the store cannot be the thing that *performs* work. What it can be is the
place where work is **recorded, offered, taken and reported on** — and this
document is about how that happens across machines, buildings and organisations
without the store ever reaching out to any of them.

Its companion, [`process-catalogue.md`](process-catalogue.md), says what a
process, a step and a run *are*. This says how one gets to whoever does it.

## Work is a record, not a message

A **run** is one attempt at one step, and it is an ordinary record in the
tenant's own store — not a message on a queue, not a row in a scheduler's
private table. Everything else follows from that:

- it survives restarts of anything, because it was never in flight;
- it has a history, an audit trail and an owner, because every record here does;
- it can be listed, counted and read by whoever is entitled to, without a
  special interface for looking at work.

A queue would have given none of that, and would have needed its own answer to
each of them.

## Whoever does the work comes and gets it

Nothing is pushed. A **participant** — a service, an on-site appliance, a
supplier's or a member's own system, or a person opening a screen — asks the
store what is waiting for the steps it performs, and takes what it can.

That is one decision doing a great deal of work. A participant behind a router
needs no inbound address. A participant that is switched off for the weekend is
simply one that has not asked lately, rather than a failed delivery, an alert,
or a retry queue somebody has to drain on Monday. And the store keeps no client,
no credential and no connection for any of them, which is what stops it from
slowly becoming an integration platform with a hundred outbound dependencies.

## Taking work is a claim, and a claim expires

Two participants may ask at the same moment and see the same run. Taking it is a
**claim**: a conditional write that exactly one of them wins. The loser simply
takes the next run; nothing coordinates, and nothing needs a lock service.

Every claim has a **deadline**, because a participant can die holding work and
nothing else would notice. When the deadline passes without word, the run goes
back to being available.

This is also why a participant must not rely on its own memory to avoid doing
something twice: it will occasionally see a run it has already seen, and the
claim — not its bookkeeping — is what settles who is doing it.

## Progress is evidence, not a heartbeat

A long step extends its claim by **saying what it has got done** — how many
records validated, which named milestone it reached — rather than by sending a
tick. A tick proves a process is running; what a deadline protects against is a
process that is running and getting nowhere.

That has a pleasant side effect: because progress is evidence, it is also the
answer to "how far along is this?", so a supervisor watching a long job and the
mechanism that decides whether to take the work away are reading the same thing.

## Finishing, and not finishing

A run ends closed, or it ends **released** — handed back, with the reason. That
distinction is deliberate. A system where the only ending is "done" quietly
turns failures into successes, and the next person cannot tell a job that
finished from a job that gave up. Whether a participant is even allowed to close
a step, as opposed to only advancing it, is something the step itself declares.

## Knowing who is out there

Nobody registers a participant in a configuration file. A participant
**announces itself**: it says which step it performs, in which version, on whose
behalf, and at what scope — a particular region, a particular organisation, or
everywhere. Resolution then walks those announcements, which is what allows a
local implementation and a participating organisation's own system to be two
candidates for the same step, ranked by how local they are rather than by which
machine they happen to run on.

**Presence is worked out, not claimed.** A participant that is keeping up with
what it asked for is present; one that is behind and not moving is not — and the
store says exactly that, which is a different sentence from "nothing is
declared". Nobody sends a heartbeat, and a component that has frozen cannot
report that it is healthy, because it does not get a say.

The subtlety worth knowing: a participant with *nothing to do* also stops
moving. Silence is only absence when there is work waiting.

## Things that cannot speak for themselves

An instrument on a serial cable has no cursor and no credential. Neither does a
meter in a substation or a sensor in a container. Each is reached by something
that does — an appliance, a gateway, a connector — and that thing **reports what
it can see behind it**, however many hops away.

The store records one row per thing whose state is worth knowing, at any depth,
so the rule about what a state is exists once instead of once per router. What
it does *not* do is decide whether a report is stale: it has no path of its own
to check, and a single freshness threshold across a serial line and a network
socket would be wrong for both. Each hop is responsible for the hop below it.

Where something has a cursor, presence is derived from it. Where it does not,
the record carries **who last saw it and when** — because "where it sits" and
"who to ask about it" are different questions, and an operator chasing a silent
instrument needs the second one.

## What a participant is allowed to see

A participant's whole world is a small set of verbs: ask for work, take it,
report on it, read the documents that work names. It never holds a handle to the
tenant's store, and there is no request that takes a reference — so it cannot
ask for data, relevant or not. It receives what the work it is holding entitles
it to, resolved by the side that legitimately has it.

What it may work on is the **intersection** of two things: what its credential
covers, and what the step admits. Neither side can widen the other. A step
cannot grant its executor more than the executor already holds, and a
credential cannot reach a step that has not opened itself to that kind of
participant.

## Two sites of one tenant

A site with an on-premises appliance and a cloud is **one tenant in two
places**, not two tenants. They run the same code and the same declarations, so
what travels between them is the stored bytes as they are.

Three ideas shape that:

- **The store builds no channel.** It hands a caller a batch and accepts one
  back. Something outside it carries the bytes, reconnects and authenticates —
  so a network is somebody's job, and not a dependency of the store working.
- **Records travel because work needs them, and leave when it no longer does.**
  Not by following references outward, which is how an appliance ends up holding
  a copy of the entire collection.
- **The side that started a run is the side that advances it.** The other side
  holds a read-only account. Across a link, "the deadline passed" and "the report
  is in flight" can both be true at once, and a peer acting on the first does the
  work twice.

The accepted consequence: an appliance that dies holding its own work keeps that
work until it comes back. Moving it is a deliberate act by a person, not
something a clock infers from a link that is merely slow.

## What this buys, and what it costs

**It scales by adding claimants.** More of the same participant, competing for
the same work, with the claim sorting out who does what. There is nothing to
partition and no rebalancing to get wrong.

**A person is a participant.** Opening a run on a screen is the claim; finishing
the form is the report. Manual work is the baseline that automation is added
*over*, so a step nobody has automated is not an omission — and what a person may
do is exactly the set an automated executor would otherwise perform.

**No orchestrator is named anywhere in it.** The same participant runs as a
service, on an appliance with nothing else on it, or as a screen with a person
in front of it. Whatever runs work locally is free to be durable in its own way;
what is owed, by whom, and what happened is the store's.

**The cost is honesty about time.** Nothing here is immediate. A participant
finds out there is work when it next asks; a second site finds out when the link
next runs. Everything above is arranged so that lateness is visible and
survivable rather than hidden — a lagging cursor, a claim that lapsed, a mirror
that is behind — but late is what it is, and a design that needed
work-starts-now would need something other than this.

## Where the detail is written down

This is the shape. The rules, each with the reason it beat the alternative:

- **What is promised, and what proves it** — the participation entries in the
  [REQ catalogue](../arc42-006-runtime/req-catalogue.md). Most of what is
  described above is written there as an exact rule with the test that proves
  it: how presence is derived, what a claim and a checkpoint are, what a report
  may land through, how a replicated batch behaves under replay and reorder,
  and how a routed tree reaches the store.
- **Why the store never calls out**, and why a participant is one component
  rather than a client per system — ADR 0060.
- **Why precedence selects but a step grants the right to override** — ADR 0059.
- **What an envelope may disclose about work** — ADR 0058.
- **One tenant across two appliances** — ADR 0062.

And on why none of the above says which industry it is for: the engine is
neutral by construction ([the engine and its
faces](engine-and-faces.md)), and where that neutrality is worth money is
argued in [a neutral repository for a competing
industry](../plans/ifc-repository.md) and [where a neutral store earns its
keep](../plans/neutral-exchange-domains.md). Distributed work is the part of
the engine those arguments lean on hardest: an exchange nobody can host is one
where every participant's work has to reach them without the host reaching in.
