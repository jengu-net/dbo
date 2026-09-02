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

**A participant does not say done before the work is done.** A run closes on
what the participant reports, and the store has no view below that seam, so a
report that arrives early is a true-looking record of something that has not
happened — and nobody looks for work the store says is finished. A participant
with durable execution underneath waits for it; a router waits for its edge. A
wedged one then lets the claim lapse, and the run reads *released* rather than
*done*, which is the honest state.

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

**The thing that can reach the store is the participant, and it holds the
claim.** An instrument behind a router is routed *because* it cannot reach the
lane, so the router claims the run, forwards it, waits, and reports — holding a
claim on work it cannot read, which sounds strange and is exactly the point. The
instrument holds the key and does the work. Participant versus routee is a fact
about the attachment, not the device: a bench with its own lane is a participant,
and the same bench behind a router is a routee.

A routee that stops being reported is a statement, not a gap. A router reports
the full set behind it, so an absence from that report is something the router
said — distinguishable from a quiet router, whose cursor did not move. The store
keeps a departed routee with its last attestation and marks it no longer
reported, so "gone" reads as *last seen by X at T, absent from X's report at
T+1*: absence with a timestamp, which is a fact.

## What a participant may see and do

A participant's whole world is a few verbs: ask for work, take it, report on it,
read the documents that work names. It never holds a handle to the store, and no
request takes a reference — so it cannot ask for data, relevant or not. It
receives what the work it holds entitles it to, resolved by the side that
legitimately has it.

What it receives has two parts, and the split is what lets one participant serve
many tenants without reading any of them. The **manifest** — which tenant, which
step, the task, and *references* to the documents the work names — is readable,
because routing on it is its job. The **payload** — the documents themselves —
is sealed to the participant meant to open it. Whoever merely carries the work
reads the manifest and holds no key.

What it may work on is the **intersection** of what its credential covers and
what the step admits. Neither widens the other: a step cannot grant its executor
more than the executor already holds, and a credential cannot reach a step that
never opened itself to that kind of participant. There is no implicit
unrestricted — reach is stated when a participant is provisioned, so nobody's
access depends on a parameter somebody forgot.

**A participant is sealed to, and a carrier is not.** A participant offers two
public keys when it enrols — one it is sealed to, one it signs with, because
the curve that agrees cannot sign; the private halves never cross, so a copy
of the enrolment records opens nothing and signs nothing. From then on each payload sent to it is sealed
under a data key of its own, wrapped to that participant — and to nobody who
merely carries it. That is the store's usual answer applied to transport: a
carrier that holds no key cannot read what it moves, whatever it is told it may
do, and the arrangement needs no trust in the carrier to hold.

Three consequences are worth stating because each could have gone the other way.
The seal is **per payload, wrapped per participant**, not per tenant — a carrier
enrolled in a tenant would otherwise hold that tenant's key, and the carrier is
the thing being excluded. What is sealed is the **carrier form** — the record as
the store's own encrypted disclosure mode hands it out, identifying elements
already under the person's key — so a sealed copy still in flight after an
erasure is in the same state as the store's own records after a shred. And a
sealed copy is **a copy in flight, not the record**: the store keeps the
original, still indexes and searches it, and the copy is bounded by the work
that caused it.

What a participant holds decides how its work arrives. One that offered a
key at enrolment is answered with a manifest and sealed payloads, and is
refused its inputs in the clear even when it asks; one that offered none is
served in the clear, as every participant was before there was anything to
seal to, and is refused a seal by name. Wrapping to a routee behind the
claimant, and the lane over the store's own stream, are the live topic
[sealed work](../tasks/sealed-work.md) still.

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

**"What happened to this run, and who read what?"** — the trail, and it is one
trail with two kinds of subject. A hop that carried the work leaves a **travel**
entry about the *task*: the journey belongs to the work. A participant that
opened a payload leaves an **access** entry about the *document*, landing where
every other reading of that document lands and naming the task execution as its
occasion. So *who has read this?* is answered from the document by somebody who
need not know work exists, and *where did this go?* from the task; the occasion
is the join. The machinery's own read to seal a payload records nothing, because
a read that yields only ciphertext is not a disclosure.

The entries of a run are chained, each committing to the one before, rooted in
the task the store minted — so a participant cannot present a journey that never
started, and a hop that skipped its own entry is exposed by the next, because
every travel entry names who it handed to. The result that closes the run is the
chain's last link and always was; the store checks the chain when the result
lands, and a completion with a gap is refused and told which link. What the chain
cannot do is compel a participant to send: an intended recipient can open a
payload and never say so, and that limit is accepted rather than hidden — the
data was legitimately theirs, and what is lost is the entry for an authorised
read on a device the tenant answers for.

The tenant wires a trail into its lane. A claim writes the hop on the task.
A participant that opens a sealed document says so from where its key is,
and that lands on the document as its access entry naming the run; for a
participant served in the clear, the read that resolves its inputs is the
opening and is recorded the same way, whatever the audit level. The store's
own read to seal is recorded as nothing.

Those entries are chained. Each carries the link it commits to and its own,
the first commits to the task the store minted, and the participant signs
the links it makes with the signing key it offered at enrolment — so a router
cannot manufacture an edge's opening and an edge cannot deny one. The result
that closes the run carries the head it commits to; the store walks the chain
when the result lands, and a completion whose chain has a hole is refused and
told which link, so the run stays owed under a named participant. A
predecessor retention pruned reads as unchained rather than broken. What the
chain cannot do is compel a link never made: an intended recipient can open a
payload and never say so, and that limit is accepted rather than hidden.

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

That is a statement about the *contract*, not about the store's deployment. The
store's own durable substrate is named in its constraints and used by its own
code; what the seam keeps neutral is *meaning* — a run, a claim and an outcome
say the same thing whether the work happened in a workflow, in a loop, or in
front of a person — which is what lets a bench with nothing on it be a
participant without anybody installing a substrate there.

## Where the detail is written down

- **How all of this is rendered** for a reader who speaks a particular standard —
  [the FHIR face](the-fhir-face.md).
- **The exact rules and their proofs** — the participation entries in the
  [REQ catalogue](../arc42-006-runtime/req-catalogue.md), which carry each rule
  above in its precise form and name the test for it.
- **Why neutrality is the point**, and where a store shaped like this is worth
  building for an industry that is not this one —
  [a neutral repository for a competing industry](../plans/ifc-repository.md) and
  [where a neutral store earns its keep](../plans/neutral-exchange-domains.md).
