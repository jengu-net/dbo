# Processes and work

## What this is about

**A store preserves what is true now. A regulator asks how it came to be
true.** Those are different questions, and the second cannot be answered from
the first. A record with a full version history says a value changed on
Tuesday and who was signed in; it does not say which obligation was being
discharged, on whose behalf, or whether anybody was entitled to discharge it.
That is not a gap in the history. It is a gap in what was ever written down,
because the cause was never something the store held.

**The causes are processes**, and nothing else writes to a store worth
regulating: a value is different today because somebody discharged an
obligation, under a rule, with the right to do it. Model that and the history
becomes legible — every change carries a reason that is itself a record, with a
time, an owner and an entitlement behind it, readable with the same tools as
the state it explains. Leave it out and you have an exact account of *what* is
true and none of *how* it came to be, which is most of what the regulation was
asking about.

So this store also holds **the work**: what has to be done, who is entitled to
do it, who is doing it now, how far they have got, and what happened. That is
also what makes it a place two parties who do not trust each other can both
use — an exchange is a process with obligations, not a file drop, and somebody
has to hold the record of it.

Almost none of that work happens where the store is. A sample is analysed on an
instrument in a laboratory. A model is exported by a firm competing with the
others on the same project. A consignment is inspected at a border. A component
declaration is signed by a supplier's own system. A great deal of it is a person
at a screen deciding something. Some of those places are behind a router with no
public address, some are offline for a weekend, and some belong to organisations
that are not on speaking terms.

So these pages are about how work is defined, offered, taken, reported on and
watched — across machines, buildings and organisations, without the store ever
reaching out to any of them.

**Nothing here is domain-specific.** The engine is a store for regulated data and
the domain is a face over it ([the engine and its
faces](../engine-and-faces/README.md)). How these concepts are *rendered* — as FHIR
resources, or as anything else — is [a face's
business](../the-fhir-face/README.md) and deliberately not described here.

## What is written where

This page is the contract: what work is, how it is described, and how it
reaches whoever does it. Four documents beside it carry the rest.

| | |
|---|---|
| [Participants, and what they may see](participants.md) | who is out there, how presence is worked out, and what is readable against what is sealed past them |
| [How work is watched](watching-the-work.md) | what is true now, what happened, and whether anything is wrong — three questions, three answers |
| [One lane for the fleet](one-lane-for-the-fleet.md) | **intended, not built**: a step defined once that performs for every tenant, and what joins their work into one place |
| [What a tenant agreed to, and what happened](processing-and-consent.md) | **intended, not built**: the register of processing a tenant reads, and what a disagreement with the trail means |

The last two describe where this is going rather than where it is. Everything
on this page, and in the first two, is what the store does today.

## The vocabulary

Three words carry most of it.

A **process** is a named piece of work with steps — validating a batch of
results, publishing a product passport, applying a configuration.

A **step** is one stage of it, and it is the unit everything else attaches to:
who may perform it, what it consumes and produces, what it is allowed to report.

A **run** is one attempt at one step. It is an ordinary record in the **tenant's**
own store — a tenant being one customer's whole world here, its own database and
its own authority, which is [why a tenant is a database](../data-isolation/why-a-tenant-is-a-database.md) — not a message on a queue, not a row in a scheduler's private table.
Everything else follows from that: it survives restarts of anything because it
was never in flight; it has history, an audit trail and an owner, because every
record here does; and it can be listed, counted and read by whoever is entitled
to, with no special interface for looking at work. A queue would have given none
of that and would have needed its own answer to each.

Runs come in two shapes, and the difference is how they end. A **pipeline** is
over N items and closes when every item is terminal. A **sweep** converges on a
condition and closes when it finds nothing left to do — it is *found* rather than
started, so a crash resumes the same run instead of opening a second one that
would double every count taken from it.

## A step is a workplace

The most useful way to read a step is as a **place where work is held and
done** — a desk, a bench, a queue in front of a machine. Someone or something
stands at it.

**Manual is the baseline, and automation is an attachment.** A step is fully
defined — what it takes, what it produces, who may act — before any automation
exists. An automated executor is then a rule that claims the cases it can handle,
the way a mailbox rule does, and everything it does not claim falls through to a
person. That ordering matters: it means a step nobody has automated is a normal
state rather than a gap, and it means turning automation off is a decision
somebody made rather than a code path that stopped being reached.

**What waits at a step is its backlog**, and it is asked for rather than
handed out — which is the whole of how work moves here.

**What nothing took is a person's, and it is countable** — per step, and per
**scope**, which is the part of the world a declaration applies to: everywhere,
one country, one organisation. That number is the **automation backlog** stated
as a fact rather than an opinion: the part of a step's backlog no automation
claimed. Whoever is holding the work is told which reason applies — nothing
claimed it, automation was switched off here, or a narrower party tried to
override a step that does not allow it.

## How a process is built

A process is **declared in code and projected outward**, never drawn in a tool
and interpreted at runtime. A step declaration is the contract, and it says:

- **what it consumes and produces** — named input slots with shape references,
  so joining a step is agreeing to an API rather than to a convention;
- **which storage domains it reads and writes** — the named areas a tenant's
  data is divided into, so what a step could touch is readable from its
  declaration rather than from its code, and checkable before it runs;
- **the actions it contains** — open, close, reopen — which is what roles narrow
  and what "held by a person" concretely means;
- **its milestones, in order**, if it has any;
- **who, if anyone, may override it** — by default nobody.

That last point is worth stating carefully, because the alternative is the
common one and is wrong: specificity is self-declared, so if being more local
were enough to win, any party could displace a national rule simply by declaring
a narrower scope. Precedence *selects* among candidates; the step *grants* the
right to override at all.

A step is installed by the module that carries it, or **introduced over a lane**
by a participant that brings its own. One id means one definition: two different
definitions of the same id is a collision, refused by name, while two identical
ones are a fleet and perfectly ordinary.

The grant is what an executor is measured against, and the introducer is
measured too. A participant performing the step it brought is not overriding
anything, so its executor is declared at the **baseline** — it is the rule for
that step. One declared at an organisation is asking to vary somebody else's
step, and a step that did not open itself to that is refused by name.

## How work reaches whoever does it

**Nothing is pushed.** A **participant** — a service, an on-site appliance, a
member organisation's own system, or a person opening a screen — asks what is
waiting for the steps it performs and takes what it can.

What it asks over is a **lane**: everything this participant may do in this
tenant and nothing besides — ask, take, report, read what the work names.

**A lane is a swimlane, not a traffic lane.** It is the band that belongs to
one performer, and it is the only thing a participant is attached by — there is
no second kind of attachment. (*Link* appears in these pages for two other
things: the path between [two sites of one tenant](#two-sites-of-one-tenant),
and a hop in the chain a run's travel is recorded as.) It says nothing about the route work travels or a line it must
stay inside; it says *whose* work this is, which is why it carries an
entitlement rather than a direction. So the picture is an ordinary one: the
step is the bench, its backlog is what waits at that bench, and a lane is
somebody's standing to work there — which bench, in which tenant, with which
verbs.

A participant holds a lane and never a handle to the store, which is why the
list of things it can do is short enough to write in a sentence.
[What a participant may see](participants.md) is that list, and the lane is
also where the three ways of reaching a tenant are made to look identical.

That single decision does a great deal. A participant behind a router needs no
inbound address. One switched off for the weekend is simply one that has not
asked lately, rather than a failed delivery and a retry queue somebody drains on
Monday. And the store keeps no client, credential or connection for any of them,
which is what stops it becoming an integration platform with a hundred outbound
dependencies.

**Taking work is a claim, and a claim expires.** Two participants may see the
same run; taking it is a conditional write that exactly one wins, and the loser
takes the next run rather than coordinating about this one. Every claim carries a
deadline, because a participant can die holding work and nothing else would
notice. This is also why a participant must not rely on its own memory to avoid
doing something twice — it will occasionally see a run it has seen before, and
the claim, not its bookkeeping, is what settles who is doing it.

**Progress is evidence, not a heartbeat.** A long step extends its claim by
saying what it has got done — how many items, which named milestone — rather than
by sending a tick. A tick proves a process is running; what a deadline protects
against is a process that is running and getting nowhere. Usefully, that makes
the thing a supervisor reads and the thing that decides whether to take the work
back the same thing.

**A run ends closed, or released.** Released means handed back, with the reason,
and the distinction is deliberate: where the only ending is "done", failures
quietly become successes and the next person cannot tell a job that finished from
one that gave up. Whether a participant may close a step at all — as opposed to
only advancing it — is something the step declared, and the rule lives at the
primitive every door passes through rather than at any one of them, so the lane,
the authoring surface and whatever comes next meet one copy of it.

**Overturning a closure is a different authority from performing the step.** A
close can be wrong, and discovering that must not require inventing a second run
to disagree with the first: the run itself becomes claimable again, with the
reason on the record. That reopening is reached through the lane like every
other act, and by its own half of an entitlement — the supervisory half, which
nothing implies. A bench that validates results does not thereby overturn the
ones somebody judged done; a credential speaking for the whole tenant supervises
nothing until the word is written down, exactly as one that may write every type
still may not erase a person; and a credential that supervises takes no work.
Both halves must admit the act — the entitlement names the step and the step
declares the action — and a supervisor asked for a step it does not name, or one
whose declaration omits reopening, is refused by name.

**Work is authored on the tenant's own surface, never on the lane.** A
participant holds a lane and takes work; whoever authors work holds the
tenant's store credential and states an obligation. So a document posted on
the surface that names a declared step — the same document the face renders a
run as — becomes a run, minted through the door every run is minted at, and
refused by name where its rules are not met: an unknown step, an undeclared
slot, an unfilled declared slot, a name already used. The run is what is
stored, it reads back as the same document as it advances, and a participation
credential cannot post one.

**Two doors mint a run, and they offer different steps.** The tenant's step
door offers what the tenant's own spec declares — the work this tenant says
it does. The face's run document is checked against the composed catalogue,
which is the installed steps and the ones participants introduced, so
a capability somebody brought over a lane is authored there and not at the
step door. Both mint through the same primitive and refuse by the same
rules; what differs is which catalogue answers "is that a step". A router built against this store then has real
work to claim in a real deployment, authored by the side that originates it.

**A participant does not say done before the work is done.** A run closes on
what the participant reports, and the store has no view below that seam, so a
report that arrives early is a true-looking record of something that has not
happened — and nobody looks for work the store says is finished. A participant
with durable execution underneath waits for it; a router waits for its edge. A
wedged one then lets the claim lapse, and the run reads *released* rather than
*done*, which is the honest state.

## Two sites of one tenant

A site with an on-premises appliance and a cloud is **one tenant in two places**,
not two tenants: same code, same declarations, different local settings. What
travels between them is the stored bytes as they are, in the carrier form —
sealed to the site meant to open them, readable in their manifest by whatever
carries them.

- **The store builds no channel.** It hands a caller a batch and accepts one
  back; something outside carries the bytes, authenticates and reconnects. A
  network is somebody's job, not a dependency of the store working.
- **Records travel because work needs them, and leave when it no longer does** —
  not by following references outward, which is how an appliance ends up holding
  a copy of the entire collection.
- **The lane has a second bound, deliberately different: declarations travel by
  type.** A tenant's own definitions — terminology, canonicals, none of it about
  anybody — are asked for by type and arrive as every version since the peer's
  position on the content feed, filed under their source, read-only there,
  shadowed by a local override and never revoked by work. What a run produced
  travels with the run the same way, as a copy that outlives it. A type the
  lane does not admit is refused by name, and a tenant admits every declared
  type except the ones about a person, which travel by work or not at all.
- **The side that started a run is the side that advances it.** The other holds a
  read-only account. Across a link, "the deadline passed" and "the report is in
  flight" can both be true at once, and a peer acting on the first has the work
  done twice.

The accepted consequence: an appliance that dies holding its own work keeps it
until it returns. Moving that work is a deliberate act by a person, not something
a clock infers from a link that is merely slow.

## What this costs

**Everything here is late.** A participant learns there is work when it next
asks; a second site learns when the link next runs. The design makes lateness
visible and survivable — a lagging cursor, a claim that lapsed, a mirror that is
behind — but late is what it is. Work that must start *now* needs something other
than this.

**Scale comes from adding claimants**, not from relaxing the claim. More of the
same participant, competing, with the claim sorting out who does what; there is
nothing to partition and no rebalancing to get wrong.

**No orchestrator is named anywhere in it.** The same participant runs as a
service, on an appliance with nothing else on it, or as a screen with a person in
front of it. Whatever runs work locally may be durable in its own way; what is
owed, by whom, and what happened is the store's.

That is a statement about the *contract*, not about the store's deployment. The
store's own durable substrate is named in its constraints and used by its own
code; what the seam keeps neutral is *meaning* — a run, a claim and an outcome
say the same thing whether the work happened in a workflow, in a loop, or in
front of a person — which is what lets a bench with nothing on it be a
participant without anybody installing a substrate there.

## Where the detail is written down

- **How all of this is rendered** for a reader who speaks a particular standard —
  [the FHIR face](../the-fhir-face/README.md).
- **The exact rules and their proofs** — the participation entries in the
  [REQ catalogue](../../arc42-006-runtime/req-catalogue.md), which carry each rule
  above in its precise form and name the test for it.
- **Why neutrality is the point**, and where a store shaped like this is worth
  building for an industry that is not this one —
  [a neutral repository for a competing industry](https://github.com/jengu-net/dbo/blob/main/docs/plans/ifc-repository.md) and
  [where a neutral store earns its keep](https://github.com/jengu-net/dbo/blob/main/docs/plans/neutral-exchange-domains.md).
