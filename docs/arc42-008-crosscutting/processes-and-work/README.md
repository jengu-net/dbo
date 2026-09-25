# Processes and work

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
faces](../engine-and-faces/README.md)). How these concepts are *rendered* — as FHIR
resources, or as anything else — is [a face's
business](../the-fhir-face/README.md) and deliberately not described here.

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

The grant is what an executor is measured against, and the introducer is
measured too. A participant performing the step it brought is not overriding
anything, so its executor is declared at the **baseline** — it is the rule for
that step. One declared at an organisation is asking to vary somebody else's
step, and a step that did not open itself to that is refused by name.

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
which is the installed steps and the ones linked participants introduced, so
a capability somebody brought over a link is authored there and not at the
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
seal to, and is refused a seal by name. A router names its routee as the
recipient and is sealed past: it may name only what it has declared behind
it, naming is the forward and leaves the travel link that makes the routee
the chain's next author, and the opening it carries home is its routee's,
signed with the routee's own key.

The lane has three carriers and a runner cannot tell which it holds:
in-process, HTTP, and the store's own stream. The third is the one a shared
fleet holds. The host connects to the durable substrate it already runs on,
each served tenant opens a door there — one long-lived workflow, guarded by
the same authority and the same participation scope as the HTTP door — and a
verb is a message to that door with its answer an event on it. Work goes out
and the signed openings and the result come home on the one channel, no
tenant accepts a callback, and the verbs are encoded once for both wires so
nothing can be served on one that the other cannot carry. The plane between
holds no credential and nothing readable: an ask is signed with the
participant's enrolment key rather than carrying a token, so a lane on the
stream is held only by a participant enrolled with both keys, and the clear
verb is refused there by name. A container given
no substrate serves its lanes over HTTP and in-process only, as every
container did before the fleet.

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

## How it is watched

Three different questions, deliberately answered by three different things.

**"What is true right now?"** — the store. Runs are records, so what is claimable,
what is stuck and who holds it are ordinary queries against the tenant's own
data. Anything that acts on work reads this and nothing else. Across a
deployment the same question is asked by one process outside every container,
over the doors each node and tenant already serves: a node is asked what it is
serving and what it has installed under the deployment's own token, because
both answers name other tenants' existence; a tenant is asked about its work
under a credential its own authority minted, as envelopes and never payloads,
so the reader holds one credential per tenant and is never handed a surface
that crosses them. Every answer is labelled with the node it came from, nothing
is copied, and a node that did not answer is in the reading as unreachable
rather than absent from it — the missing node being the one an operator opened
the reading for. The union of the nodes' inventories is the network map, by
step and version: descriptive, and never a second declaration of a step, which
is why an inventory travels this way and not through the introduction door.

**"What does this node know how to do, and who would take it?"** — the console.
It lists the steps installed here and the steps a participant introduced, names
the contributor of each, and answers which executor would take a given step now
and why that one. It answers while serving no tenant at all, because the
catalogue is what is installed rather than what is running — and a node that has
stopped serving is exactly when somebody asks.

The reading is **sequential and bounded**: every ask has a timeout and every
outcome is recorded, so one dead node costs one timeout and one line rather
than a hung reading. Asking in parallel buys latency and pays with a second
failure mode; it is worth having when a deployment has enough nodes that a
serial read is slow, and not before.

**Looking and acting are separate, and so is the authority for them.** The
process that reads a deployment can also act on it, but only through the doors
a participant uses, and it holds the supervisory credential separately — often
not at all. An operator needs to look far more often than to act, and looking
must not require the authority to destroy somebody's work. This is why control
planes that bundle both into one channel read as mostly mutations: cancel,
delete, fork, restart. Acting here goes through the lane like every other act,
so the rule the lane enforces is met once rather than bypassed by the tool
built to supervise it.

**"How is the fleet doing?"** — telemetry. Counts, durations and outcomes leave
as labelled measurements for whatever collects them. This is lossy by design and
nothing decides anything on it; it is for trends and alerting, not for state. What
may be said there is a closed set, and a failure's own words are not in it: they
stay on the run, in the store of the tenant whose work it was.

Speaking somebody else's control protocol is deliberately not how any of this
is offered. Those protocols' verbs are overwhelmingly mutations, so an endpoint
speaking one holds cancel, delete, fork and retention rights over every
executor that connects — a large authority surface acquired in order to read
counters — and their metric payloads carry no labels, so nothing said here
could ride them. Nor is anything synthesised so an external engine can emit on
this store's behalf: those metrics are computed from durable rows, so
fabricated telemetry is fabricated state, with real ids, to which recovery and
replay then apply.

Where the numbers go is the deployment's to say, never the code's. The seam has
one exporter, installed everywhere and idle without an endpoint: given
`dbo.telemetry.otlp.endpoint` (or the protocol's own environment variables) it
carries counts as sums, levels as gauges and durations as histograms to an
OpenTelemetry collector as OTLP over HTTP, rendered and sent with the JDK's own
client so no protocol library rides in the container. Reporting is not a
dependency of serving: every verb updates an aggregate and returns, a flusher
posts on an interval, and a collector that is absent, slow or refusing is said
once and costs the caller nothing. The seam finds the exporter through the
framework, the way the logging binding is found, and a container proof asks
the seam what it found — because an exporter that resolved and was discarded
in silence is this repository's characteristic failure in its quietest form.

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

## Where this is going

**Nothing in this section is built.** It is the model the rest of this chapter
is growing into, and it is here rather than only in the ledger because a reader
who has just learned how work reaches a participant today deserves to know
which way it is moving. What is true now is everything above this line;
[the delta, and the decisions still open](../../arc42-011-risks-and-technical-debt/032-one-lane-for-the-fleet/README.md)
is where the difference is written down.

### A step is defined at one of two levels

**An application-level step is defined once and performs for every tenant.** A
bean in a serving application says which step it performs and nothing about
whose. The subscription is **by step, never by tenant**: an application that
admits patients admits them for every tenant the deployment serves, and adding
a tenant adds no configuration to it. These are the steps the manager level
knows about, can count, and can report on.

**A tenant-level step is a direct subscription to one tenant**, reached over
HTTP, and it is what a participant outside the deployment holds. It is not
managed at the manager level and does not appear on the unified stream. That is
not a lesser form — it is how another organisation's system participates, how a
tenant's own appliance does, and how a laboratory performs a step for a tenant
it does not run. The two levels answer different questions and both stay.

So the two catalogues this chapter already describes become two **levels**: the
deployment's own steps, which every tenant's work flows through, and the steps
one tenant admits from one participant.

### Where the two levels are declared

**A tenant declares its own steps in its own descriptor**, which is where the
step door's catalogue already comes from. **The deployment declares its
application-level steps in the descriptor of the tenant that represents it** —
the management tenant, the one the store already keeps its own history in and
already names rather than watches. So the deployment's own declaration lives
where the deployment's own record lives, and not in a properties file: a step
catalogue in configuration would provision databases as a side effect of a
config refresh, which is the argument this store already makes about declaring
tenants.

**Declaring one is what prepares it.** The queues an application-level step
consumes, and the substrate they live on, are made ready when the step is
declared and changed when the declaration changes — the same act, at the same
moment, as a tenant's database being made ready when the tenant is declared.
Where a step's queue lives is part of its entry, so a deployment that wants a
step to itself says so beside the step rather than somewhere else.

**And the levels become checkable at the declaration.** One step code in both
descriptors is refused by name, which is where the invariant belongs: the same
sweep that reconciles what a deployment says it serves can see both lists, and
a contradiction is caught when it is written rather than when two schedulers
reach for one run.

**The register is derived from the same declaration**, not maintained beside
it. What a step takes, and which of those it opens rather than carries, is said
once where the step is declared; the rows a tenant reads are generated from it.
One source, so a change in what the deployment does with data is a change in
one file — which is what makes *has anything changed since I last looked* a
comparison rather than an audit.

**What a withdrawal means is not settled.** A tenant's retraction means it, and
the same question here has an answer the store has not chosen: a step removed
from the declaration while its queue still holds work has to stop being joined
to, drain, and then go — and what happens to work that outlives the draining is
the part that needs deciding rather than assuming.

### Which side settles who does the work

A claim is what settles it: two participants may see one run, taking it is a
conditional write that exactly one wins, and the loser takes the next run. That
does not change. What the two levels change is **where the contest happens**,
and the answer only works because there is no contest that spans them.

**A step code belongs to one level and not both.** A run of an application-level
step is never offered on its tenant's own lane, and a run of a tenant-level step
never reaches the unified queues. That has to be an invariant the store
enforces, refusing by name where a tenant declares a step the deployment
defines, or the reverse — because two schedulers over one run is precisely the
condition this chapter warns about for two sites of one tenant, where *the
deadline passed* and *the report is in flight* can both be true and the work
gets done twice.

**With the levels disjoint, each side settles its own.** A tenant-level run is
claimed on the tenant's lane as it is today. An application-level run is settled
where its queue is: the durable layer hands an item to exactly one consumer, and
that is the same statement a conditional write makes, in the place the item
lives. The tenant learns who is performing it when the first report arrives
through the writeback, and its record is the answer to *what is true right now*
exactly as before.

**The failure this replaces one with is worth naming.** Nothing now dies holding
claims across many tenants — the joiner claims nothing, because observing is not
claiming. What can go wrong instead is that the joiner falls behind or stops, and
then work sits in tenants with nobody bringing it forward. That is the failure
the store is already equipped to see: presence here is derived from a cursor, and
a consumer that is behind and not moving is not present — a different sentence
from nothing being declared.

### A tenant admits a step, or the deployment requires one

**Most application-level steps are admitted.** The tenant declares which of the
deployment's steps its work flows through, so the application still names no
tenant — what it performs for is decided by the tenants that admitted it, and
the joiner filters on their declarations. A subscription by step and a tenant's
consent are both kept that way.

**Some are required, and a tenant cannot refuse them and still be a tenant
here.** Retention that must be swept, an erasure that must propagate, integrity
that must be checked, work that must be metered: a deployment that could be
opted out of one tenant at a time cannot make any promise about itself. So the
refusal exists, and it is at the level of the system rather than the step —
declining means not being a tenant of this deployment. That is an agreement
about processing and operation, and it is signed by joining.

**Two different words, because they are two different facts.** A tenant's spec
already declares the steps *its* work cannot do without, and what that list
decides is classification: a mandatory step nothing contributes is an incident.
A required step is the other direction — the deployment's obligation on the
tenant — and it is declared where the deployment's own configuration is, never
in a tenant's spec. Nothing can quietly become required for one tenant, and the
set is one list a reader can enumerate.

**Required is not hidden, and enrolment is what makes that true.** A required
step enrols with each tenant exactly as an admitted one does. So its payloads
are sealed under that tenant's own enrolment and the thing in the middle still
holds no key; the step appears in the tenant's catalogue, marked as required and
naming what requires it; and every payload it reads lands in that tenant's own
data-access trail, naming the step. **A tenant that cannot refuse can still
account for every disclosure**, and it can read the required set before it
joins, which is what makes joining a choice. Cannot refuse never means cannot
see.

**And requiring a router is a smaller act than requiring a processor.** A
router reads only the envelope, so requiring one is operational. A processor
decrypts, so requiring one is data processing and the agreement names that step
specifically rather than covering it by category. Without that line, *required*
becomes the way anything obtains access.

**Required decides that a step runs, not what it may reach.** What it may work
on is still the intersection of what its credential covers and what the step
admits. Nothing above widens that, and a required step asked for something
outside it is refused by name like anything else.

### The work is joined into one stream, and what comes back is split out

A run is a record in its own tenant's store, which is what makes "what is
claimable" an ordinary query. A step-based subscription across a fleet
therefore cannot be a subscription per tenant: a deployment with fifty tenants
would have an application asking fifty doors for work it describes once.

**The join is the first stage of the run's journey, not a detour around it.**
Three stages, and each reuses something the store already does.

**One: every active tenant's work is subscribed to.** A tenant already
publishes a work stream carrying runs, claims, milestones and closes, and it is
already consumed the way anything durable is consumed here — as a **named
consumer** that resumes from the store's own position rather than its own, for
every tenant rather than a list of them. A joiner absent for an hour resumes
where it stopped instead of missing the hour, and a tenant that came up a minute
ago is included because the consumer is registered per tenant as tenants arrive.

**Two: what it read is partitioned by step**, and partitioning is the point. A
subscriber cannot filter a durable queue cheaply while it is running, so the
filtering is done once, on the way in, and a consumer of one step is offered
only that step's work.

**What a partition can be is decided by the substrate, not by preference**, and
the durable layer here offers two shapes with different costs. Its queues are
**polled**, at an interval each queue names. Its wake-ups are **addressed to a
destination**, and a consumer that waits to be told rather than asking is a
long-lived workflow addressed by name — which is exactly what a tenant's door
on the stream already is, one per tenant instead of one per step.

**Where a step's queue lives is a placement decision and not a structure.** A
step names the substrate it runs on and several steps may name one. There are
few application-level steps and each carries every tenant's work, so a step
under real load is worth its own database — for the contention it stops sharing
as much as for the notifications — while a deployment running everything in one
application points them all at one. That the two can be the same design is the
point: a store does not know how it will be run.
[The ledger](../../arc42-011-risks-and-technical-debt/032-one-lane-for-the-fleet/README.md)
carries what the substrate restricts and what each shape costs.

**A wake-up is a hint, and the queue is the truth.** A notification nobody was
listening for is not redelivered, so a consumer that missed one finds the work
when it next looks. That costs latency and never correctness, which is the only
reason a wake-up is safe to depend on at all.

**Three: what comes back is written by the tenant's own code.** Outcomes,
progress and metrics do not reach into a tenant's tables; they arrive at an
administrative writeback the tenant runtime provides, and it applies them. So
the tenant keeps control of its own record — the same rules a lane's verbs pass
through decide whether this step may close, whether a report is in order and
who is recorded as having performed it. **It is a third door, not a back door.**
A writeback that bypassed those rules would be a way to write a tenant's work
records without meeting the conditions every other writer meets.

**And the carrier holds no key because the stream carries no payload.** The
work stream carries the machinery's own bookkeeping — the run, its claim, its
milestones — and a tenant's records are a different domain that carries only
*that* something changed. A joiner is handed no store, no connection and no
reference it can resolve, so it reads manifests because that is all there is to
read. The property the whole arrangement rests on is structural rather than a
rule somebody must remember.

### The execution state is the substrate's, not the JVM's

**A unified step keeps nothing in memory between asks.** What it has done, how
far it got and what it is holding live in the durable layer's own execution
state, so a restart, a redeploy or a move to another node loses nothing and
resumes rather than starts again. A step whose progress lived in a field would
make a rolling restart a data-loss event.

**Feedback comes home on a stream of its own.** Outcomes, progress and the
metrics a supervisor reads travel back over a dedicated channel, and something
on the store's side applies them to the originating tenant's run — so the
tenant's own record stays the answer to *what is true right now*, exactly as it
is today, and the unified layer never becomes a second place to ask.

**And the managing tenant keeps a reduced account** of execution state, so the
manager level can answer across tenants without asking each of them. Reduced
rather than a copy: what a fleet operator needs is not what a tenant holds, and
anything about a person has no business in a managing tenant at all. Which
fields those are is not decided.

### What a tenant consents to is a register, not a list of steps

A tenant does not read the deployment's steps to know what happens to its data.
Most of them never touch it. **What it reads is a register of processing**, one
row per application-level step that opens a payload, and nothing else is on it.

**A carrier is not on the register.** A step that routes on the envelope
discloses nothing, so listing it would pad the one document whose whole value
is that it is short. What a tenant needs to account for is who *opened*
something, and a register that also named everything that carried it would bury
that.

**Each row is a declaration made in advance.** A step that intends to open a
payload says so, and says it where it already says what it takes — a
declaration already names its slots and their types, so what is added is
whether the step opens a slot or only routes it. From that, one row: the step,
what it opens, whether the deployment requires it or the tenant may decline it,
and what requires it.

**Granted once, and for every tenant at once.** Enrolment is per tenant,
because that is what keeps the thing in the middle unable to read anything —
but a tenant should not perform it one step at a time. The register is
answered as a whole, and a tenant reads the whole of it as a whole: what the
deployment processes, which rows it may decline, and **whether anything has
changed since it last looked**. A change in what a deployment does with data is
one comparison rather than a diff somebody has to assemble.

### Declaring is not the same as being recorded

**The trail happens regardless.** Every payload a step opens is recorded and
lands in that tenant's own data-access trail, whether the step declared it,
whether it was granted, and whether anybody is reading. Recording is a
consequence of asking and nothing turns it off.

**So the register and the trail are two different documents**, and comparing
them is the point. The register is what the code said it would do. The trail is
what it did.

**A disagreement is an incident: the code does not follow the consent.** Two
shapes, one meaning:

- a step opened a payload it never declared;
- a step opened one under a row the tenant declined.

Neither is refused in flight. The application is enrolled, so it holds the key
and can open what it was sent — no cryptography can prevent a processor from
processing, and a store that pretended otherwise would be describing a
protection it does not have. What it can do is notice, name the step, and say
which tenant's data it was. That is the same answer this store already gives
for a mandatory step nobody contributes: the honest state, classified, rather
than an enforcement it cannot perform.

**Declining an optional row is a routing decision first.** Work whose
processing a tenant declined is not offered to that step, so the ordinary case
never reaches the incident at all. The incident is what remains: a step
reaching past what it declared, inside work it was legitimately given.

### What happens between a change and its approval

A register is answered once and then the deployment changes: a step that opens
a payload is added, or one already there begins opening more. Work arrives in
the window between the change and the tenant's answer, and something has to
happen to it.

**Only widening asks for an answer.** A row that disappears, or a step that
stops opening a slot, leaves a tenant with less to approve than it already
approved. Renewal is asked for when the deployment would process **more**, and
never as a formality — a register that asks to be re-approved for a narrowing
teaches everyone to approve without reading.

**And here refusal is genuinely available**, which it is not elsewhere in this
design. Whether a step declared what it opens is a fact about code the store
learns from the trail after the act. Whether a tenant has approved a row is
known **before the payload is sent**, and what is sent is sealed by the store
to the participant meant to open it — so declining to seal is a real refusal
rather than a request not to look.

**So a deployment states, once, what it does with work whose processing is not
yet approved.** Three postures, and the choice is itself part of the register a
tenant reads before it joins — a deployment that could change the posture
quietly would have made the whole register advisory.

- **Approved by the agreement.** The change applies and work continues,
  because the tenant's agreement already says the deployment may vary this.
  Legitimate only where that is actually what was signed, and it is the posture
  that costs the most to get wrong: the mechanism's entire value is that a
  change in processing is visible, and this one makes it visible after the fact.
- **Processed, and named as an incident.** Work continues and the store says,
  by name, that a step processed under a row nobody approved. Nothing is
  interrupted and nothing is quiet, which is the same answer this store gives
  wherever it can classify but not prevent.
- **Not processed until approved.** The payload is not sealed to that step, so
  the work is not offered to it. **It is not an error and the run is not lost:**
  it queues, as work waits here by design, and an incident names what it is
  waiting for. A deployment that must not process without an answer chooses
  this and accepts that an unanswered register stops work rather than widening
  quietly.

**A bounded window is the fourth shape**, and it is the two middle ones in
sequence: process and name it for a stated period, then stop. It is what a
deployment that can neither halt on a Friday nor process indefinitely without
an answer actually needs, and it says so as a duration rather than as a habit.

**Processed-and-named is what a deployment gets if it says nothing.** It is the
posture that neither stops work nor hides that work happened, and a default
that halted would make an unanswered register an outage caused by nobody
clicking. What it costs is that the unapproved case runs, so the incident is
the pressure: it names the step, the tenant and the row, and it stands until
the row is approved rather than being a notice that scrolls past.

**And a row may state its own.** The deployment's posture covers the rows that
state none, so a single new row that must not run before it is approved says
so, beside itself, without stopping everything else. A new row and a widened
one are different acts and a deployment may treat them differently.

**A row's posture is part of the row.** It is read with the register, and
changing it is one of the changes a tenant detects — because a deployment that
could move a row from *not until approved* to *processed and named* would have
found a way to approve its own widening. The register's protection is that
everything about it, including how it behaves when unanswered, is visible
before a tenant joins and never changes quietly afterwards.

**What none of them changes** is that the access is recorded. A payload opened
under an unapproved row is in the tenant's trail like every other, so whichever
posture a deployment takes, the tenant's account of what was read is complete.

### Router or processor, and asking is what decides

**A unified step that reads only the envelope is a router.** It routes on the
manifest, holds no key, and the store has disclosed nothing to it.

**The moment it asks for a payload it is a data processor**, and the store
records that it was. Every such access is recorded, travels home on the
feedback stream, and lands in the originating tenant's own data-access trail.

**The classification is derived, not declared**, and that is the whole point. A
processor that had to announce itself could fail to, and an application whose
category was a configuration value would be one where the category and the
behaviour could disagree. Asking for data is the act; being recorded as having
asked is its consequence.

**The entry belongs to the tenant**, is written through the port that writes
that tenant's trail, and names the **processor** as the actor — not whatever
carried the record home. A trail that named the carrier would answer "who saw
this" with the name of something that cannot read it.

### What does not change

Nothing above alters the contract this chapter describes. Nothing is pushed; a
claim is still what settles who is doing the work and it still expires;
progress is still evidence rather than a tick; a run still ends closed or
released; and work is still authored on the tenant's own surface and never on
the lane. What changes is how many doors an application asks, and where the
execution state of the deployment's own steps lives.

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
