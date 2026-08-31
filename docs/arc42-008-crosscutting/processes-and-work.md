# Processes and work (§8)

## What this is about

A store that only answers questions is a database. This one also holds **the
work**: what has to be done, who is entitled to do it, who is doing it now, how
far they have got, and what happened. That is what makes it a place two parties
who do not trust each other can both use — an exchange is a process with
obligations, not a file drop, and somebody has to hold the record of it.

Almost none of that work happens where the store is. A sample is analysed on an
instrument in a laboratory. A model is exported by a firm competing with the
others on the same project. A consignment is inspected at a border. A component
declaration is signed by a supplier's own system. A great deal of it is a person
at a screen deciding something. Some of those places are behind a router with no
public address, some are offline for a weekend, and some belong to organisations
that are not on speaking terms.

So this document is about how work is defined, offered, taken, reported on and
watched — across machines, buildings and organisations, without the store ever
reaching out to any of them.

**Nothing here is domain-specific.** The engine is a store for regulated data and
the domain is a face over it ([the engine and its
faces](engine-and-faces.md)). How these concepts are *rendered* — as FHIR
resources, or as anything else — is [a face's
business](the-fhir-face.md) and deliberately not described here.

## The vocabulary

Three words carry most of it.

A **process** is a named piece of work with steps — validating a batch of
results, publishing a product passport, applying a configuration.

A **step** is one stage of it, and it is the unit everything else attaches to:
who may perform it, what it consumes and produces, what it is allowed to report.

A **run** is one attempt at one step. It is an ordinary record in the tenant's
own store — not a message on a queue, not a row in a scheduler's private table.
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

**What nothing took is a person's, and it is countable** — per step, per scope.
That number is the automation backlog stated as a fact rather than an opinion,
and whoever is holding the work is told which reason applies: nothing claimed it,
automation was switched off here, or a narrower party tried to override a step
that does not allow it.

## How a process is built

A process is **declared in code and projected outward**, never drawn in a tool
and interpreted at runtime. A step declaration is the contract, and it says:

- **what it consumes and produces** — named input slots with shape references,
  so joining a step is agreeing to an API rather than to a convention;
- **which storage domains it reads and writes**, which is what makes its blast
  radius checkable before it runs;
- **the actions it contains** — open, close, reopen — which is what roles narrow
  and what "held by a person" concretely means;
- **its milestones, in order**, if it has any;
- **who, if anyone, may override it** — by default nobody.

That last point is worth stating carefully, because the alternative is the
common one and is wrong: specificity is self-declared, so if being more local
were enough to win, any party could displace a national rule simply by declaring
a narrower scope. Precedence *selects* among candidates; the step *grants* the
right to override at all.

A step is installed by the module that carries it, or **introduced over a link**
by a participant that brings its own. One id means one definition: two different
definitions of the same id is a collision, refused by name, while two identical
ones are a fleet and perfectly ordinary.

## How work reaches whoever does it

**Nothing is pushed.** A **participant** — a service, an on-site appliance, a
member organisation's own system, or a person opening a screen — asks what is
waiting for the steps it performs and takes what it can.

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
only advancing it — is something the step declared.

## Who is out there

Nobody registers a participant in a configuration file. A participant
**announces itself**: which step it performs, in which version, on whose behalf,
at what scope. Resolution walks those announcements, which is what lets a local
implementation and a member's own system be two candidates for the same step,
ranked by how local they are rather than by which machine they run on.

**Presence is worked out, not claimed.** A participant keeping up with what it
asked for is present; one that is behind and not moving is not — and that is a
different sentence from "nothing is declared". Nobody sends a heartbeat, so a
component that has frozen cannot report that it is fine. The subtlety worth
knowing: a participant with *nothing to do* also stops moving, so silence is only
absence when there is work waiting.

**Some things cannot speak for themselves.** An instrument on a serial cable has
no cursor and no credential; neither does a meter in a substation or a sensor in
a container. Each is reached by something that does, and that thing reports what
it can see behind it, however many hops away. The store keeps one row per thing
whose state is worth knowing, at any depth, so the rule about what a state is
exists once rather than once per reporter. What it will not do is decide whether
a report is stale — it has no path of its own to check, and one freshness
threshold across a serial line and a network socket would be wrong for both.
Where something has a cursor, presence is derived from it; where it does not, the
record carries who last saw it and when, because "where it sits" and "who to ask
about it" are different questions.

## What a participant may see and do

A participant's whole world is a few verbs: ask for work, take it, report on it,
read the documents that work names. It never holds a handle to the store, and no
request takes a reference — so it cannot ask for data, relevant or not. It
receives what the work it holds entitles it to, resolved by the side that
legitimately has it.

What it may work on is the **intersection** of what its credential covers and
what the step admits. Neither widens the other: a step cannot grant its executor
more than the executor already holds, and a credential cannot reach a step that
never opened itself to that kind of participant. There is no implicit
unrestricted — reach is stated when a participant is provisioned, so nobody's
access depends on a parameter somebody forgot.

## Two sites of one tenant

A site with an on-premises appliance and a cloud is **one tenant in two places**,
not two tenants: same code, same declarations, different local settings. What
travels between them is the stored bytes as they are.

- **The store builds no channel.** It hands a caller a batch and accepts one
  back; something outside carries the bytes, authenticates and reconnects. A
  network is somebody's job, not a dependency of the store working.
- **Records travel because work needs them, and leave when it no longer does** —
  not by following references outward, which is how an appliance ends up holding
  a copy of the entire collection.
- **The side that started a run is the side that advances it.** The other holds a
  read-only account. Across a link, "the deadline passed" and "the report is in
  flight" can both be true at once, and a peer acting on the first has the work
  done twice.

The accepted consequence: an appliance that dies holding its own work keeps it
until it returns. Moving that work is a deliberate act by a person, not something
a clock infers from a link that is merely slow.

## How it is watched

Three different questions, deliberately answered by three different things.

**"What is true right now?"** — the store. Runs are records, so what is claimable,
what is stuck and who holds it are ordinary queries against the tenant's own
data. Anything that acts on work reads this and nothing else.

**"What does this node know how to do, and who would take it?"** — the console.
It lists the steps installed here and the steps a participant introduced, names
the contributor of each, and answers which executor would take a given step now
and why that one. It answers while serving no tenant at all, because the
catalogue is what is installed rather than what is running — and a node that has
stopped serving is exactly when somebody asks.

**"How is the fleet doing?"** — telemetry. Counts, durations and outcomes leave
as labelled measurements for whatever collects them. This is lossy by design and
nothing decides anything on it; it is for trends and alerting, not for state. What
may be said there is a closed set, and a failure's own words are not in it: they
stay on the run, in the store of the tenant whose work it was.

**The store's own housekeeping runs on this model rather than beside it.**
Notification delivery, retention, configuration application, tenant serving,
upstream sync — each is a declared process with runs like any other. That is a
visibility decision more than an implementation one: an operator asking what is
running sees the machinery in the same list as the domain work, with the same
counts and the same holders, and a retention pass that fails is a card somebody
can pick up rather than a line in a log.

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

## Where the detail is written down

- **How all of this is rendered** for a reader who speaks a particular standard —
  [the FHIR face](the-fhir-face.md).
- **The exact rules and their proofs** — the participation entries in the
  [REQ catalogue](../arc42-006-runtime/req-catalogue.md), which carry each rule
  above in its precise form and name the test for it.
- **Why the store never calls out** — ADR 0060. **Why precedence selects but a
  step grants the right to override** — ADR 0059. **What may be disclosed about
  work** — ADR 0058. **One tenant across two appliances** — ADR 0062.
- **Why neutrality is the point**, and where a store shaped like this is worth
  building for an industry that is not this one —
  [a neutral repository for a competing industry](../plans/ifc-repository.md) and
  [where a neutral store earns its keep](../plans/neutral-exchange-domains.md).
