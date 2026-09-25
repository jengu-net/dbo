**Open, and not started: a proposal awaiting approval. The desired state is
described in [processes and work](../../arc42-008-crosscutting/processes-and-work/README.md)
under "Where this is going". This item is the delta against what is built, the
decisions that have to be made before anything is, and nothing else. No
implementation plan is agreed.**

# One lane for the fleet, and two levels of step

## What is already true

More of the model exists than its absence suggests, which is why this is a
delta rather than a design from nothing.

- **One participant already serves many tenants without reading any of them.**
  The manifest is readable and the payload is sealed to whoever opens it, and a
  carrier holding no key is the existing rule rather than a new one.
- **The lane already has three carriers** — in-process, HTTP, and the store's
  own stream — and a runner cannot tell which it holds. So changing how work
  arrives changes nothing a step service sees.
- **The stream carrier already runs on the deployment's substrate**, with a
  door per served tenant, guarded by the same authority and participation scope
  as the HTTP door, and the plane between carries no credential.
- **Two catalogues already answer "is that a step"**: the tenant's own spec at
  the step door, and the composed catalogue — installed steps plus those a
  linked participant introduced — at the face's run document.
- **The management tenant already exists** and is already the one the store keeps
  its own history in, named in configuration rather than watched, so the loop
  that retracts undeclared tenants cannot retract it.
- **Disclosure is already recorded with a reason**, and the trail already names
  the run a disclosure happened under.

## What does not exist

| the desired state | today |
|---|---|
| a step defined once performs for every tenant | a lane is per tenant; an application names each one it performs for |
| one unified stream at the management tenant | a door per served tenant on the substrate, and nothing lifts work between tenants |
| a joiner reading every tenant's work stream | **mostly exists**: a work-domain observer is a named durable consumer over every tenant, registered as tenants arrive, resuming from the store's position |
| the joiner unable to reach payloads | **exists, structurally**: the work stream carries the run and not the record, and an observer is handed no store and no resolvable reference |
| queues partitioned per application-level step, waited on rather than polled | nothing partitioned; the notify-driven wait exists in the stream lane and is the mechanism to reuse |
| an administrative writeback the tenant's own code applies | a participant reports over the lane it claimed on; there is no writeback port for work performed elsewhere |
| a step code belonging to exactly one level | nothing: a step is a step, and nothing would refuse the same code at both levels |
| a feedback stream applying outcomes to the originating tenant | the participant reports over the same lane it claimed on, to that tenant directly |
| execution state in the durable layer | the runner holds its cycle in memory; what survives is what the tenant recorded |
| a reduced account of execution in the management tenant | nothing; the manager level asks each node and tenant over their own doors |
| router and processor as derived categories | a participant is sealed to or served in the clear, decided by whether it enrolled a key — not by what it asks for |
| a data-access entry per payload access, carried home | disclosure is recorded where it happens, under the request's purpose |
| two levels of step definition | one level, per tenant, in two catalogues |
| a register of processing a tenant reads as a whole | nothing: a participant enrols, and no document says what it opens |
| a step declaring that it *opens* a slot rather than carries it | a declaration names its slots and their types, and nothing distinguishes opening from routing |
| an incident where the trail disagrees with the register | incidents exist where a mandatory step is uncontributed; nothing compares an access against a declaration |
| a stated posture for work whose processing is not yet approved | nothing: there is no register, so no window between a change and an answer |
| enrolment answered for a whole deployment at once, and change detectable in one comparison | enrolment is a participant offering two keys, per tenant, one at a time |

**And one piece of plumbing is missing underneath all of it.** The Spring
worker assembly builds an HTTP lane and nothing else: `dbo-stream` is absent
from its bundle set, and its properties carry no substrate configuration. So
today even a worker inside the deployment polls over HTTP — which is
[item 031](../031-a-worker-in-the-deployment-takes-the-substrate/README.md),
and is a prerequisite rather than part of this.

## What the durable layer actually offers

Read out of `dev.dbos:transact:1.0.0`'s own sources, because the design above
assumed a shape it does not have. **Its queues are polled. Only messages and
events are notify-driven, and their channels are fixed.** Everything below is
a restriction on how a step-based partition can be built, not a preference.

### Queues poll, and one queue has one poller

`QueueService` schedules a task per queue at a fixed rate; each queue carries a
`pollingInterval`, **default one second**. The interval grows only after an
exception — doubling, capped at 120 seconds — and decays back toward the
configured value on every successful pass. So an **idle** queue keeps polling at
its interval; the cap is a brake on a failing database, not idle backoff, and a
step with nothing waiting is not quietly drifting to a two-minute latency.

**And `partitionQueue` is not a subscription.** `queue_partition_key` partitions
entries by workflow class so each class gets its own concurrency accounting, and
when it is on, one poller walks every partition of that queue in a loop. It
divides limits, not consumers.

### Notification is three fixed channels, and every process hears all of them

The migration installs two triggers, and the listener opens one connection:

| table | channel | payload |
|---|---|---|
| `notifications` | `dbos_notifications_channel` | `destination_uuid::topic` |
| `workflow_events` | `dbos_workflow_events_channel` | `workflow_uuid::key` |
| — | `dbos_streams_channel` | — |

**The channel names are library constants**, so a channel per step is not
available. Every instance connected to that database receives every
notification on all three and matches the payload against its own waiters
locally. The consequence to size before building: notification volume is
proportional to *all* work across *all* tenants and *all* steps, and every
process pays to discard what is not its own.

**Postgres scopes notification to a database.** So a separate database per step
is the only thing that narrows that fan-out — and it costs a listener
connection, a pool, and the schema's migrations each. It is an answer to a
measured notification volume and to nothing else.

**And it can be switched off.** `useListenNotify` decides whether the triggers
are installed at all; without them the same code degrades to polling, so
nothing may depend on a wake-up arriving.

### A database per step, and when that is the cheap answer

**The shape of the load decides this, and the expected shape favours it.** A
few application-level steps, each carrying every tenant's work, is the profile
where a database per step is cheap: the *benefit* scales with load per step and
the *cost* scales with the number of steps. The opposite profile — many steps,
each lightly used — would invert it, and that is the reading to revisit if the
number of application-level steps ever stops being small.

**Four things it buys, and only the first is unavailable elsewhere.**

- **Notification isolation**, which nothing else provides. The channels are
  library constants and Postgres scopes them to a database, so this is the only
  way a step's consumers stop hearing every other step's traffic.
- **Contention isolation.** The durable layer's own tables — workflow status,
  notifications, operation outputs — are exactly what a hot step hammers. A
  database each gives independent vacuum, independent write-ahead log and
  independent lock contention, which is worth more than the notification saving
  under real load.
- **Independent failure and upgrade.** A step that floods does not stall the
  others, and a migration is per step rather than per fleet.
- **A move later without redesign.** A step that outgrows the server moves to
  its own Postgres instance by changing where it points.

**What it costs is connections, and the cost is per participating process.**
Each durable-layer instance holds **one dedicated connection permanently** for
its listener, plus a pool — so a process taking part in *M* step databases holds
*M* listeners and *M* pools before it does any work.

**That is the pairing worth noticing**: this cost is small exactly where a
separate worker application serves one step, which is already the shape scaling
takes here — one database, one listener, one pool per worker. It is large in
the all-in-one embedded server, which would hold every step's connections at
once to perform steps it may barely use.

**So placement should be configuration, not structure.** A step names the
substrate its queue lives on, and several steps may name one. A deployment that
runs everything in one application points them all at one; a deployment scaling
a step gives it its own. The joiner has to resolve *where does this step's queue
live* in either case, so designing for it is nearly free, and deciding it now
would bake a topology into a store that does not know how it will be run.

**And these are not tenants.** A step's database is owned by the runtime and
carries a durable-layer bootstrap and nothing else: no face, no zone, no
personal-data isolation, no store schema, no authority. The provisioning path
that creates tenant databases must not be the one that creates these, or they
arrive with tenant machinery nobody asked for — though the admin connection
that provisions is the same one.

**The management tenant stays what it is.** It holds the joiner's own bookkeeping
and the reduced account, and step queues live beside it rather than inside it —
otherwise the tenant that records what the deployment did becomes the hottest
database in it.

**Two consequences to design for rather than discover.** The joiner writes into
a different database from the one it read, so there is no transaction spanning
the two: delivery is at-least-once and the write has to be idempotent on the
run's own identity. And anything that answers across steps — the reduced
account, a fleet view — reads *M* databases rather than one.

### What follows for the joiner

**If step-based consumption must be notify-driven, the partition is a
long-lived workflow per step**, addressed by workflow id, woken by `send` to a
topic or by an event key. That is not a new mechanism to invent — it is exactly
the shape of the door a tenant already opens on the stream, one per tenant,
re-keyed to one per step. Its restrictions: a consumer is a workflow that is
running, one `recv` at a time, so parallelism for a hot step means several
workflows rather than several threads.

**If a second of latency is acceptable, a queue per step is much less
machinery** — and a second is already better than this store's own runner
default of two. That is the version to build first, and the notify-driven door
is what a measured latency requirement would buy.

**Either way the joiner is unchanged**, because the partition is chosen where
the item is written and not where it is read.

## Where an application-level step is declared

**In the management tenant's descriptor**, which already exists, is already a
tenant spec, and already carries a `steps` field that the sample's `mom.json`
leaves empty. So the deployment's own declaration needs no new home: it goes
where the deployment's own record goes.

**Declaring is provisioning.** A step's queues and the substrate they sit on are
prepared when the step is declared and reconciled when the declaration changes,
exactly as a tenant's database is prepared when the tenant is declared — and the
placement decision lives in the step's own entry, so "this one gets its own
database" is said beside the step.

**It makes the level invariant checkable where it is written.** One code in both
the management descriptor and a tenant's is a contradiction a sweep can see, and
refusing it at declaration is cheaper by far than discovering it as two
schedulers reaching for one run.

**And it gives the register one source.** What a step takes and which of those
it opens is declared once; the rows a tenant reads are generated from that. A
change in what the deployment does with data is then a change in one file, which
is what makes change detection a comparison rather than an audit.

### A tenant's own steps need none of this

A tenant-level step runs on the durable layer its tenant already has — every
tenant launches one and migrates its own schema at bring-up — so it costs no
substrate, no provisioning and no placement. Its consumers are remote and poll
the tenant's door over HTTP, which they were doing anyway.

**Which means the joiner filters at the join.** A tenant's work stream carries
every run it has, private ones included, so the joiner lifts only the runs whose
step the management descriptor declares. It knows which those are from the same
document that provisioned their queues, and a run it does not lift is simply
left where it belongs.

**And the carriers fall out of the levels.** The stream carrier is held only by
a participant enrolled with both keys, which is what an application-level
consumer inside the deployment is. An HTTP participant carries a credential and
may be enrolled or not, which is what a tenant's own remote participant is. The
two levels therefore differ in who may hold the lane as well as in who declares
the step.

**What must not be overclaimed** is what private means. The deployment does not
define, schedule, prepare or list a tenant's own steps — but it hosts the tenant,
so it can observe that one ran. The protection is the same one the store gives
everywhere: the payload is sealed to whoever opens it, and no application-level
step ever opens a private step's work, so nothing about it reaches a register.

### What it needs that a tenant's `steps` does not carry

An application-level entry says more than a tenant's does: which slots it
**opens** rather than carries, whether it is **required** or admitted, its
**posture** when unapproved, and **where its queue lives**. Those are the facts
the register, the consent and the provisioning are all derived from.

**Which argues for its own key rather than widening `steps`.** A field that
means one thing in a tenant's descriptor and another in the management tenant's
is the `mandatorySteps` collision again, and the refusal — *an ordinary tenant
may not declare an application-level step* — is clearer against a key that has
no business being there than against extra properties on a key that does.

**Settled: a withdrawal closes the step to new work and removes nothing.** The
joiner stops lifting runs for it at once; what is queued is still performed,
because that work belongs to tenants who believe it is being done. The substrate
is then removed **by a person**, not by the sweep and not on a timer — rare,
irreversible, and holding work until drained, which is the same judgement this
store already makes about moving work away from an appliance nobody can reach.

**It is deliberately not a retraction.** A tenant's retraction means it; this
one must not, because a configuration change that discarded runs would be the
store losing work to tidy itself up.

**And it is safe because a joined item is a copy.** The run is a record in the
tenant that authored it and the queue holds a copy bounded by the work, so
removing a substrate destroys copies and no records. A tenant sees its runs stop
progressing, which is the state they are in and which this store already reads
as *not moving* rather than as done. Re-declaring the step joins them again.

**What that leaves open is narrow**: a run already reported as progressing when
its substrate goes has a tenant-side record of work in flight that will never
advance. Progress being evidence rather than a tick is what makes it visible,
but whether the store should say more than *not moving* about a run whose
performer was removed is not decided.

## The decisions, before anything is built

Each of these changes what gets written. None is settled.

**1. Where the claim lives once work is joined. Dissolved, and it needed an
invariant rather than an answer.** The question assumed one run could be
contested from two sides. It cannot be, if a step code belongs to **one level**:
a run of an application-level step is never offered on its tenant's lane, and a
tenant-level run never reaches the unified queues. Each side then settles its own
— the tenant's lane by conditional write as today, an application-level run by
the durable layer handing its item to exactly one consumer — and the tenant
learns who is performing it when the first report arrives.

**So the work is that invariant.** The store has to refuse, by name, a tenant
declaring a step the deployment defines and the reverse. Without it this design
has two schedulers over one run, which is the hazard already described for two
sites of one tenant.

**And the joiner claims nothing**, because observing is not claiming — so
nothing dies holding claims across many tenants. What replaces that failure is a
joiner that falls behind, leaving work in tenants with nobody bringing it
forward, and that is a failure this store already sees: presence is derived from
a cursor, and a consumer behind and not moving is not present.

**2. What an application-level processor is enrolled with. Settled: per tenant.** A
payload is sealed to an enrolled participant, so enrolling once at fleet level
would mean something re-seals a tenant's payload and therefore holds tenant
keys — which is exactly what the carrier rule exists to exclude. Per tenant
keeps the management tenant unable to read what it moves, and makes a tenant's
authorisation a real act rather than a setting.

What follows from it is a UX obligation rather than an open question: a tenant
must be able to do it **all at once**, and to see in one comparison whether
what a deployment does with its data has changed since it last looked. Enrolment
being per tenant must not become enrolment one step at a time.

**3. What may be required, and what the word costs.** Settled in shape: a
tenant **admits** most application-level steps, and the deployment **requires**
a few. An admitted step keeps the subscription by step — the joiner filters on
what tenants declared, so the application still names none of them. A required
step cannot be refused per step; the refusal is per system, and declining means
not being a tenant here. It is an agreement about processing and operation,
signed by joining.

What is open is the boundary, and it matters because *required* is the word
everything will want. Two constraints are proposed:

- **Required is declared with the deployment's configuration**, never in a
  tenant's spec, so nothing becomes required for one tenant quietly and the set
  is enumerable. It also must be readable before a tenant joins, or "if it wants
  to be in the system" is not a choice.
- **A required processor is a heavier act than a required router.** A router
  reads only the envelope, so requiring one is operational — retention sweeps,
  erasure propagation, integrity checks, metering. A processor decrypts, so the
  agreement names that step specifically rather than covering it by category.

**And the word collides with one already in use.** A tenant's spec already
declares `mandatorySteps` — the steps *its own* work cannot do without — and
that list decides classification: an incident where nothing contributes one.
Required is the other direction and must not reuse that field, or an obligation
and an incident become the same declaration.

Required decides **that** a step runs, never what it may reach: the
intersection of credential and step stands, and enrolment still happens per
tenant, so a tenant that cannot refuse can still account for every disclosure
in its own trail.

**4. What the reduced account holds.** Explicitly undecided, and the constraint
is easy to state even before the fields are: nothing about a person, because a
management tenant is not a place identifying data goes, and nothing a tenant's own
record is the answer to, because a second place to ask is a second answer.

**5. The register, and what a row is.** Settled in shape: what a tenant reads
is a register of processing — one row per application-level step that opens a
payload — and a step that only routes on the envelope is not on it. A row is a
declaration made in advance, made where a step already declares what it takes,
so what is added is whether a slot is opened or only carried.

Open: **the granularity of a row**. A slot is the obvious unit because it
already exists and already names a type. Whether that is enough — against, say,
declaring which elements of a document are opened — decides how precise an
incident can be, and a register nobody can read is worth as little as one
nobody can act on.

Open too: **whether "consent" is the word.** It is the tenant authorising
processing under an agreement, not a data subject's lawful basis, and the two
are different things that the word does not distinguish. The concept is right;
the risk is a tenant-facing screen that invites the legal reading of a term
being used in an operational sense.

**6. The posture for unapproved change, and its default.** Settled in shape: a
deployment states once what happens to work whose processing a tenant has not
yet approved — applied under the agreement, processed and named as an incident,
or not processed until approved — with a bounded window as the two middle
postures in sequence. Only a widening asks for renewal.

**Refusal is available here and nowhere else in this design**, which is why the
third posture is real: approval is known before the payload is sealed, so
declining to seal actually prevents the processing. Contrast the register-versus-trail
incident, where the application already holds the key and only detection is
possible.

**Settled: processed-and-named is the default, and a row may state its own.**
The default neither stops work nor hides that work happened; a halting default
would turn an unanswered register into an outage caused by nobody clicking. The
cost is accepted rather than argued away — the unapproved case runs — so the
incident carries the weight: it names the step, the tenant and the row, and
stands until the row is approved rather than being a notice that scrolls past.
Per row, because a brand-new row and a widened one are different acts, and a
deployment may halt for the first without stopping everything else.

**What is left is the incident's own behaviour**, and it is the part the
default rests on. It has to be visible where a tenant looks rather than only in
a fleet operator's console, it has to clear when the row is approved, and it
should say how long the row has been unapproved — an incident that reads the
same on day one and day ninety is one nobody acts on, and this default is only
honest if somebody does.

**The constraint that holds it together**: a posture is part of the register a
tenant reads before joining — the deployment's and each row's alike — and
changing one is itself a change a tenant detects. A deployment able to move a
row from *not until approved* to *processed and named* would otherwise have
found a way to approve its own widening.

**7. What a joined item is. Mostly settled by the withdrawal rule: a copy.**
That is what makes removing a step's substrate destroy no records, and it is the
existing shape for a sealed payload —
but one question survives it. A copy in a
queue is bounded by the work that caused it; a copy that has been **opened** by
a processor is the case an erasure has to reach, and where a sealed copy still
in flight is already answered — it is the carrier form, in the same state as
the store's own records after a shred — an item sitting in a step's substrate
is a copy the deployment holds rather than one in transit. Whether an erasure
reaches it, and how, is the part still to decide.

## What is deliberately not proposed

**Not a second answer to "what is true right now".** The tenant's own record
stays it. A unified layer that could be asked instead would be a cache of
work-in-progress, and the chapter's whole answer to how this is watched rests on
there being one place.

**Not an orchestrator.** Nothing here names one. The unified stream carries
work that was authored on a tenant's own surface, in the order claims settle,
exactly as a lane does today.

**Not a replacement for the HTTP lane.** It is how another organisation's
application participates, and the tenant-level step stays for that reason.

## What has to be true before this starts

- Item 031: a worker in the deployment can take the substrate at all.
- Decisions 1, 2 and 3 answered, because each changes what is written rather
  than how.
- And the thing to prove first, before a joiner exists: that a step service
  reached over the stream and one reached over HTTP are indistinguishable in a
  deployment, which the harness proves for the lane and no test proves for an
  application built on the assemblies.
