# One lane for the fleet

How a step defined once comes to perform for every tenant: two levels of
step, where each is declared, and what joins their work into one place.
The contract it grows out of is [processes and work](README.md); the delta
against what is built is
[its ledger item](../../arc42-011-risks-and-technical-debt/032-one-lane-for-the-fleet/README.md).

**Nothing here is built.** It is the model
[the contract](README.md) is growing into, written down beside it because a
reader who has just learned how work reaches a participant today deserves to
know which way it is moving. The delta against what exists, and the decisions
still open, are in
[the ledger item](../../arc42-011-risks-and-technical-debt/032-one-lane-for-the-fleet/README.md).

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

So the two catalogues [the contract](README.md) already describes become two
**levels**: the
deployment's own steps, which every tenant's work flows through, and the steps
one tenant admits from one participant.


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
every tenant rather than a list of them.

The thing doing that reading is the **joiner**, and it is the only new component
in this stage. One absent for an hour resumes where it stopped instead of
missing the hour, and a tenant that came up a minute ago is included, because
the consumer is registered per tenant as tenants arrive.

**Two: what it read is partitioned by step**, and partitioning is the point. A
subscriber cannot filter a durable queue cheaply while it is running, so the
filtering is done once, on the way in, and a consumer of one step is offered
only that step's work.

**What a partition can be is decided by the durable layer, not by
preference** — the software that keeps work safe across a restart, running on a
database of its own that the rest of these pages calls a **substrate**. It
offers two shapes with different costs. Its queues are
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
carries what the durable layer restricts and what each shape costs.

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


### A tenant's own steps stay in its own tenant

**A tenant may declare steps nobody else defines**, in its own descriptor, and
they never leave it. They run on the durable layer that tenant already has —
every tenant runs one — so a private step costs no substrate, no provisioning
and no placement decision. Nothing is joined, nothing is partitioned, and no
queue is prepared for it anywhere.

**That works because its consumers are already remote.** A participant
performing a tenant-level step reaches the tenant over HTTP and asks for work;
it is a party outside the deployment, or a tenant's own appliance, and it was
polling a door anyway. The machinery the unified stream exists to remove — one
subscription per tenant for a step defined once across all of them — is not a
cost it ever paid, because the step is defined in one tenant and performed for
one tenant.

**So the two levels sit on two substrates**, and each is the cheap answer for
its own case. An application-level step is declared once, joined from every
tenant, and consumed from a queue on a runtime-owned substrate by something
inside the deployment. A tenant-level step is declared by one tenant, stays in
its durable layer, and is consumed over the lane by somebody outside.

**What private means, said exactly.** The deployment does not define the step,
does not schedule it, does not prepare anything for it, and does not list it at
the manager level. It is not a claim that the operator cannot observe that the
step ran: the tenant's work stream is the tenant's own bookkeeping, and the
deployment is what hosts it. What the operator cannot see is the **data** — the
payload is sealed to the participant that opens it, and a private step's
processing therefore never appears on any register because no application-level
step ever opens it. Privacy here is about who may read, which is the store's
usual answer, rather than about what a host can be prevented from noticing.


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

**Withdrawing an application-level step closes it to new work, and nothing
else.** A tenant's retraction means it; this one deliberately does not. The
joiner stops lifting runs for that step the moment the declaration changes, and
what is already queued is still performed — because that work belongs to
tenants who believe it is being done, and dropping it would be a store losing
runs to tidy up its own configuration.

**The substrate it leaves behind is removed by a person.** Not by the sweep
that reconciles declarations, and not on a timer: it is rare, it is
irreversible, and until it is drained it holds work. That is the same judgement
this store makes about moving work away from an appliance that cannot be
reached — a deliberate act by somebody, rather than something a clock infers.

**And it touches no tenant.** A step's substrate is the runtime's own; what it
holds is a copy of work in flight, and the run itself is a record in the tenant
that authored it. So removing one destroys copies and no records. What a tenant
sees is its runs no longer progressing — which is the state it is in, and which
the store already reads as *not moving* rather than as finished. Declare the
step again and they are joined again.


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
condition [the contract](README.md) warns about for two sites of one tenant,
where *the
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


### The execution state is the substrate's, not the JVM's

**An application-level step keeps nothing in memory between asks.** What it has done, how
far it got and what it is holding live in the durable layer's own execution
state, so a restart, a redeploy or a move to another node loses nothing and
resumes rather than starts again. A step whose progress lived in a field would
make a rolling restart a data-loss event.

**Feedback comes home on a stream of its own.** Outcomes, progress and the
metrics a supervisor reads travel back over a dedicated channel, and something
on the store's side applies them to the originating tenant's run — so the
tenant's own record stays the answer to *what is true right now*, exactly as it
is today, and the unified layer never becomes a second place to ask.

**And the management tenant keeps a reduced account** of execution state, so the
manager level can answer across tenants without asking each of them. Reduced
rather than a copy: what a fleet operator needs is not what a tenant holds, and
anything about a person has no business in a management tenant at all. Which
fields those are is not decided.


### What does not change

Nothing here or in [what a tenant agreed to](processing-and-consent.md) alters
[the contract](README.md). Nothing is pushed; a
claim is still what settles who is doing the work and it still expires;
progress is still evidence rather than a tick; a run still ends closed or
released; and work is still authored on the tenant's own surface and never on
the lane. What changes is how many doors an application asks, and where the
execution state of the deployment's own steps lives.
