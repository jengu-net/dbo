**Open, and not started. The desired state is described beside the concept it
grows out of — [one lane for the fleet](../../arc42-008-crosscutting/processes-and-work/one-lane-for-the-fleet.md)
and [what a tenant agreed to](../../arc42-008-crosscutting/processes-and-work/processing-and-consent.md).
This item is the delta against what is built, the decisions behind it, and
**[the plan](#the-plan-eight-steps-in-the-order-they-become-possible)** —
eight steps whose first two exist to stop the store acquiring two schedulers
over one run. Nothing is implemented and the plan is proposed rather than
agreed.**

| | what it is |
|---|---|
| [What is already true](#what-is-already-true) | how much of this exists |
| [What does not exist](#what-does-not-exist) | the delta, as a table |
| [What the durable layer actually offers](#what-the-durable-layer-actually-offers) | its queues poll, its channels are fixed, and what that restricts |
| [Where a step is declared](#where-an-application-level-step-is-declared) | the management tenant's descriptor, and what an entry carries |
| [The decisions](#the-decisions-before-anything-is-built) | seven, four settled, three open |
| **[The plan](#the-plan-eight-steps-in-the-order-they-become-possible)** | **eight steps, each with how it is proven** |

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
  participant introduced over a lane — at the face's run document.
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
worker assembly built an HTTP lane and nothing else, so even a worker inside
the deployment polled over HTTP. That is no longer so: `dbo-stream` is in its
bundle set, a lane with no base is carried by the substrate, and a worker's
own name is recorded on the runs it closes. This item's prerequisite is met.

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

Each of these changes what gets written. Three are settled, one is dissolved,
and the rest are open — each says which below.

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

**Settled: a row is a SLOT.** It already exists and already names a type, and
step one's `opens` already carries slot names — but the deciding argument is
the trail. Access is recorded per DOCUMENT, so slot rows and trail entries line
up one to one and a disagreement between them is computable by comparison,
which is the whole mechanism the incident rests on. Anything finer — which
elements of a document are opened — would have no counterpart in the trail, so
the comparison would have to be invented separately before an incident could
mean anything.

**Settled: the word is AUTHORISATION, not consent.** It is the tenant
authorising processing under an agreement it signed by joining, and *consent*
names a data subject's lawful basis in every reader's mind. In a store that
holds health data, a tenant-facing screen inviting that reading of an
operational term is a confusion that costs more than the familiarity buys.

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

## The plan: eight steps, in the order they become possible

**Eight steps, and the ordering is not taste.** Three of them exist to make a
later one provable, and two must land before anything joins work at all or the
store acquires two schedulers over one run. Each step says how it is proven,
because a green build proves nothing here: the characteristic defect compiles,
resolves and dies on first use.

**Every step that adds behaviour claims or adds a REQ.** That is the house rule
and it is also the discipline this plan needs most — a phase with no promise to
cite is a phase nobody can tell is finished.

### 0. A worker in the deployment can reach the substrate at all

Done, and nothing here works without it: `dbo-stream` is in the worker
assembly's bundle set and `dbo.worker.substrate.*` reaches it.

Add the bundle; add substrate and enrolment configuration; infer the carrier
from the shape of a lane — a base and a token is HTTP, neither is the substrate
— and refuse at context refresh when a lane resolves to nothing.

**The long pole is enrolment, not wiring.** A lane on the stream is held only by
a participant enrolled with two keypairs, per tenant, so this step needs a way
to enrol one from configuration or an admin door. That is most of the work and
it is worth knowing before starting.

*Proven by:* a worker in the deployment performing a tenant's work with no HTTP
in the path, and the existing HTTP sample still green beside it.

### 1. Application-level steps are declarable, and nothing runs

**Done.** `fleetSteps` in the management descriptor, carrying the step, its
slots, which of them it **opens**, whether it is required or admitted, its
posture and where its queue is to live. An ordinary tenant declaring one is
refused by name — naming the tenant, the key, the steps, and where a step it
offers itself does go.

Three things the writing settled that the plan had not:

- **`fleet.` is not checked against the declaring tenant's types.** A tenant's
  own step refuses a slot naming a type it does not hold, and copying that
  here would have refused every real declaration: the types belong to the
  tenants whose work the step performs, and the management tenant holds none of
  them.
- **The rule lives with the declaration and is applied by the sweep.** The
  parser cannot know which tenant manages the deployment — both files are the
  same document type read by the same code — but the sweep can, because the
  management tenant arrives through configuration and never travels that road.
  That is the same fact that keeps the sweep from retracting it.
- **A step that opens nothing is a router, and that falls out of the
  declaration** rather than being a category somebody assigns. It is what makes
  *required* mean two different weights without a second field.

*Proven by:* `ADeploymentDeclaresItsOwnStepsTest`, claiming
`REQ-DBO-PROC-AN-APPLICATION-STEP-IS-THE-DEPLOYMENTS-TO-DECLARE` — the
declaration read from the management descriptor, the same one refused for a
tenant by name, an unstated posture defaulting rather than being nothing, a
slot opened but not taken refused, and a slot over a type the declarer does not
hold accepted.

### 2. A step code belongs to one level, enforced

**Done.** A tenant offering a code the management descriptor declares under
`fleetSteps` is refused by the sweep that reconciles declarations, naming the
tenant, the code, the management tenant and both keys. It answers whichever
side arrived second, because every declaration is read again each pass.

**Out of order, this is the bug that produces two schedulers over one run** —
the failure this store already describes for two sites of one tenant, where the
deadline passing and the report being in flight are both true.

**What the writing settled: declared first, refused second.** The pass drops
the trouble ledger's entry for any code nothing declares, so a refusal raised
before the tenant is added to the declared set is swept away in the same pass
that raised it — leaving a tenant that does not serve and no reason anywhere.
The first attempt did exactly that, and the test caught it. The order also
decides what happens to a tenant **already serving** when the deployment grows
a colliding code: declared, so the retraction loop leaves it alone, and refused,
so the contradiction is on the ledger against it. It keeps serving, because
what is wrong is the pair of declarations and taking the tenant down would
punish whichever side did not change.

*Proven by:* `AStepCodeBelongsToOneLevelIT`, claiming
`REQ-DBO-PROC-A-STEP-CODE-BELONGS-TO-ONE-LEVEL` — both declarations present and
the tenant refused with both sides named; a tenant offering a step of its own
untouched, so the rule refuses a collision rather than the act of declaring;
and the renamed step serving, with the ledger entry gone, so the refusal names
something somebody can act on rather than a dead end.

A world of its own, recorded in the worlds ledger as **sweep**: the claim is
about what a deployment-wide pass refuses, and it needs two declarations that
contradict each other — which a shared world cannot carry without carrying the
contradiction for everybody in it.

### 3. Declaring a step provisions its substrate

**Done, with one honest boundary.** Declaring a fleet step prepares the
database its queue will live on, through `stepSubstrate` on the provisioning
seam — the same admin connection that makes a tenant's database, and
deliberately not the method that makes one. Placement is honoured: two steps
naming `retention` share a pool and a database, and a step naming nothing gets
its own. Withdrawing a step leaves the database standing.

**`step_` beside `tenant_`.** The prefix is not decoration: it makes the two
namespaces unable to collide, and it means somebody reading `\l` can tell
which databases hold a person's records and which hold a queue.

**And it names the step, not the deployment**, which assumes a deployment owns
its Postgres instance — the same assumption `tenant_<code>` has always made.
Two deployments sharing an instance would share a step's queue and perform
each other's work. That surfaced as two test classes naming one step and
counting each other's items, which is the cheap version of it; the expensive
version is two fleets on one server, and what rules it out today is the
assumption rather than the name.

**The boundary: what is created is empty, and the durable bootstrap is not put
there by this step.** `dbo-tenant` has no durable layer — DBOS is privately
embedded in `dbo-stream` and `dbo-subscriptions` — and giving the tenant module
one to migrate a schema it never reads would be a dependency bought for a
side effect. The durable layer migrates its own schema when something first
opens the substrate, which is step 4's business and its only writer. So this
step makes the database and step 4 makes it durable, and the test asserts the
emptiness rather than glossing it: a substrate carrying tables would mean it had
gone through the tenant path.

**A provisioner that does not make these says so**, in the same words this
seam already uses for a tenant's storage somebody else prepares, and the
deployment carries on with the steps declared and nowhere to put their work.
Saying that is the joiner's job when it has one.

*Proven by:* `DeclaringAStepPreparesItsSubstrateIT`, claiming
`REQ-DBO-PROC-DECLARING-A-STEP-PREPARES-ITS-SUBSTRATE` — two steps sharing one
substrate and one with its own, the databases present and carrying no schema,
and a withdrawn step whose substrate stays.

### 4. The joiner reads every tenant and writes the queues

**Done.** `StepJoiner` reads each tenant's work feed as the named durable
consumer `fleet-joiner` and offers each run of a declared step into that
step's queue on the substrate the step named. A run of a step the deployment
does not perform has nowhere to go, and being left there **is** the filter the
two levels are made of.

**It claims nothing and consumes nothing**, and the second half turned out to
be something the library gives rather than something to build: a `DBOSClient`
over the substrate writes to the durable layer **without being an executor** —
no registered workflow, no queue polling, no permanently held listener. A
joiner that launched a full durable runtime per substrate would hold a listener
and a pool for every step in the deployment, which is the connection cost
[item 034](../034-where-a-workers-substrate-and-keys-come-from/README.md)
measured.

**Idempotence is also the library's**, not a table to keep: one row per
workflow id, so the id is derived from the run's own identity and a re-offer
after a restart or a lost acknowledgement writes no second item. The ack is
taken **after** the offers, so a crash between them re-offers rather than
skips — which the workflow id makes harmless, and which is the direction to
fail in when only one is available.

**And the durable bootstrap lands here**, which closes step 3's boundary:
the joiner runs `MigrationManager.runMigrations` on each substrate as it is
built. The module that provisions databases still has no durable layer and
still does not need one.

**Two things only a running test could have found.**

- `EnqueueOptions(workflowName, queueName)`, in that order. Reversed — which
  compiles — every item is filed under a queue called `perform` with the step
  code as its name, which is a backlog no consumer of that step will ever look
  in.
- The run's identity is the **feed item's object id**, not an `id` in the
  payload: a stored object's id is the store's and the record does not repeat
  it. Read from the payload it is null, and a null in the workflow id makes
  every run of a tenant one item.

*Proven by:* `TheJoinerOffersEveryTenantsWorkIT`, claiming
`REQ-DBO-PROC-THE-JOINER-OFFERS-EVERY-TENANTS-WORK` — a run of a declared step
in that step's queue naming the tenant and the run, a run of the tenant's own
step in no queue at all, and the cursor put back so the same runs are read
again ending in one item. The third asserts it re-read **before** it asserts
the count, because a reset that quietly did nothing would make the count true
for the wrong reason.

### 5. A bean performs work for every tenant

**Done.** `StepConsumer` registers a step's queue on its substrate and
launches a durable executor; an application hands it a bean per step and names
no tenant anywhere. Work authored in two tenants is performed by one bean,
which is the whole claim: a bean holding a lane per tenant would hold twenty
lanes, twenty cursors and twenty things to go wrong, and would need
redeploying whenever a tenant joined.

**The contract had to change, and the measurement is why.** The plan said one
shared workflow class dispatching on the step; what a probe showed is that the
durable layer records the **implementation's** class name, not the
interface's. A consumer registering an application's own bean as the workflow
would therefore put a class name from somebody's application into the queue —
which the joiner writing the item cannot know. So the shared class is
`FleetPerformer`, concrete and this bundle's, holding a registry of which bean
performs which step. The name is a constant because the class is ours; the
dispatch is a lookup because the step is an argument.

**The two ends agree without having been introduced**, which is the property
worth having: the joiner writes with a client that registers nothing, the
consumer registers a queue and a class, and the item moves because both name
the same two strings.

**An item for a step nothing here performs is left alone** rather than
failing. A substrate may carry several steps' queues and a process may be
registered for one of them, so an unregistered step has to look like an item
nobody has taken yet.

*Proven by:* `OneBeanPerformsForEveryTenantIT`, claiming
`REQ-DBO-PROC-ONE-BEAN-PERFORMS-FOR-EVERY-TENANT` — one bean performing work
authored in two tenants and naming neither, and a consumer closed and rebuilt
with work authored while it was gone, which is performed when it returns.

### 6. The writeback returns it through the tenant's own rules

**Done.** `fleetLane` on the tenant runtime hands out an ordinary lane, built
from the **same factory the tenant's own doors are built from** and entitled
to one step. `LaneWriteback` turns it into the narrow `Reporting` a performer
gets. Every verb is the lane's, so an outcome from a fleet consumer meets the
rules an outcome from a participant on a port meets.

**Administrative only in who may obtain it.** A fleet consumer is inside the
deployment, so nothing physically stops it writing to a tenant's store; if it
did, a tenant's rules about its own work would hold for everybody except the
party doing most of it. The lane is what makes that impossible rather than
merely discouraged.

**The identity is the caller's.** A run records the performer, and a
deployment stamping its own name on work a bean did would lose the one field a
run cannot be re-derived from — which is what item 031 paid for on the
substrate carrier, arriving here by another road.

**It claims before it reports.** A report on a run nobody holds is refused,
and rightly: the hold is what says whose account of the work counts. The joiner
claims nothing precisely so the claim happens here, where the work is done.

**A live handle cannot cross a queue.** Workflow arguments are serialised and
read back by whichever process takes the item, possibly after a restart — so
what travels is four strings and the reporting handle is made on the far side
from them. That is why the workflow interface and the bean interface are two
interfaces.

*Proven by:* `TheWritebackPassesTheTenantsRulesIT`, claiming
`REQ-DBO-PROC-THE-WRITEBACK-PASSES-THE-TENANTS-RULES` — a run closing in the
tenant that authored it, carrying the tally and naming the performer the
application gave; and a step declaring `open` and not `close` refusing a close,
naming the action.

**The refusal test goes through the writeback and not through the queue**, on
purpose: that an item reaches a consumer is step five's claim and is proven
there, and a test that can fail two ways proves neither.

### The substrate-sharing decision holds, and what looked like a limit was a defect

**It was recorded here that one executor could not serve several steps'
queues**, on the evidence of items sitting `ENQUEUED` for ever. That was
wrong, and the correction matters because the placement decision rests on it.

Three probes killed every version of the theory: a running consumer **does**
take newly enqueued work; one consumer **does** serve two queues; and two
consumers sharing a DBOS application name on one substrate do **not**
interfere. What actually happened was a consumer built for one step being
handed a bean for a second — and `performing` registers a BEAN, not a QUEUE, so
nothing was listening to the second queue. Correct behaviour of the durable
layer, and a defect here.

**So placement is a real dial**: two steps naming one substrate are served by
one consumer, one listener and one pool, which is what
[item 034](../034-where-a-workers-substrate-and-keys-come-from/README.md)'s
arithmetic assumed all along.

### And the fix uncovered a way to lose work

A process listens to **every queue registered in its system database** unless
it says otherwise. Several steps share a substrate on purpose — so a consumer
deployed for one step would dequeue another step's item, find no bean for it,
and, as this was first written, return quietly. The workflow would read done,
the queue would look drained, and a tenant's work would have been taken by a
process that never performed it.

Three changes, and each closes a way of being silently wrong:

- a consumer **listens to its own steps' queues only**, through
  `withListenQueues`;
- a bean offered for a step a consumer does not serve is **refused when it is
  offered**, rather than sitting there correct and never being called;
- an item for a step nothing here performs is a **fault**, not a quiet success.

### Queue partitioning, which the plan did not consider

The durable layer can partition a queue by a key and applies its flow control
**per partition**. A fleet step carries every tenant's work, so without it one
tenant's backlog decides how long every other tenant waits.

**The tenant is the partition.** It costs nothing at the enqueue — the key is
the tenant already in the item — and it means a busy tenant slows itself rather
than the fleet. It also makes *at most one task at a time per tenant* a setting
rather than a redesign, if a step ever needs it.

### 7. The register, the trail and the incident

The part a tenant sees, and the part that makes the rest legitimate. In order,
because each needs the one before: the register derived from the declaration;
enrolment answered for a whole deployment at once with change visible in one
comparison; every payload opened recorded and carried home into that tenant's
trail naming the processor; the disagreement between register and trail
classified as an incident; and the postures — processed-and-named by default,
per row, refusing to seal where a row says not until approved.

*Proven by:* a step that opens a payload it never declared, named as an
incident in the tenant's own account; a row declined, and that tenant's work
not offered to that step; a row not yet approved, and the posture obeyed.

**The register and the incident are done, and so is the second of those three.**

**The register is DERIVED, never stored.** Rows come from the management
tenant's declaration and from what this tenant declined, so what the deployment
does and what a tenant reads cannot drift apart — a register kept as records
beside the declaration would be a second place to ask, and the first time they
disagreed a tenant would have no way to know which was true. One row per
**opened slot**: a step that only reads the envelope discloses nothing and is
not on it, which is what makes requiring a router an operational act and
requiring a processor something else.

**The incident is computed from the tenant's own records**, for the same
reason. The evidence is already there — the access entry the store writes when a
participant reports an opening — so the incident is a reading of the trail
rather than a second record beside it.

**And the asymmetry the design rests on became concrete while proving it.** A
processor is sealed TO; it opens where the store cannot see; what lands on the
document is what the participant *says it did*, signed with the key it enrolled.
The store is told, it does not permit. That is precisely why a disagreement
between register and trail can only ever be an incident — and the test reports
the opening the way a real processor does, manifest head and signature and all,
rather than reaching past the mechanism to write an entry.

*That part proven by:* `ATenantReadsWhatIsOpenedOfItsDataIT`, claiming
`REQ-DBO-PROC-A-TENANT-READS-WHAT-IS-OPENED-OF-ITS-DATA` and
`REQ-DBO-PROC-A-DISAGREEMENT-IS-AN-INCIDENT-NOT-A-REFUSAL`.

~~One thing the proof needed by hand~~ — **and it is built now.** The performer
had to be enrolled on the tenant before any of this could happen, because the
access entry exists only on the sealed path.

**Per-tenant enrolment is done.** A deployment names one processor with the
PUBLIC halves of its keys, and every tenant it performs for holds its own
enrolment record: the key a payload is sealed to, and the key an opening it
reports is checked against. Per tenant rather than once for the fleet, because
enrolling at fleet level would mean something re-seals a tenant's payload and
therefore holds tenant keys — the thing the carrier rule exists to exclude.

**One record covers every step on that tenant's register**, so adding a step
needs no second act from the tenant: enrolment being per tenant must not become
enrolment one step at a time, and a tenant answering per step could never be
sure it had finished.

**The credential it carries is held by nobody**, and that is worth stating
because it reads as a bug. The record exists to be sealed to and to have a
signature checked against it; the processor never signs in, because the plane
its asks cross carries no token. So the secret is minted, written once and
forgotten — stronger than a known one, since a credential nobody holds cannot
be used by whoever obtains a copy of the record.

**And a tenant authorises a register rather than rows.** It writes down which
register it read, as that register's own digest — one value for the whole of it —
so authorising is answerable all at once and *has what the deployment does
changed since I looked* is one comparison rather than an audit. The digest
covers the posture too, so a deployment cannot move a row from *not until
approved* to *processed and named* without the tenant's copy ceasing to match,
which is exactly how a deployment would otherwise approve its own widening.
Never having read a register stays a different answer from having read a
different one.

*Proven by:* `AProcessorIsEnrolledPerTenantIT`, claiming
`REQ-DBO-PROC-A-PROCESSOR-IS-ENROLLED-PER-TENANT` and
`REQ-DBO-PROC-A-TENANT-AUTHORISES-A-REGISTER-AND-SEES-IT-CHANGE`.

**Step 7 is complete.** The postures obey, and building them changed one thing
that had been committed a step earlier.

**Authorisation had to become per ROW.** It was one digest over the whole
register, which can say *something changed* and not *which row* — and decision
six needs the second, because a deployment may halt for a brand-new row without
stopping everything else. So a tenant now writes down every row it read, each by
its own digest: all at once, because a tenant approving rows one at a time could
never be sure it had finished, and per row, because the store must still be able
to name the one that is new. The two sounded opposed and were not.

**Not until approved withholds the work entirely**, rather than sealing it and
refusing later. The effect is the same and the failure is cleaner: work never
claimed cannot be work held by a performer that is then told it may not look.
This is the one real refusal in the design, and it is real only because approval
is known BEFORE anything is sealed.

**Processed and named runs, and the incident carries the cost.** It names the
tenant, the step and what is opened, stands until the row is authorised, and
says how long — from the tenant's own records rather than a clock this store
keeps, because the earliest run of that step is durable across restarts and a
remembered timestamp would reset every time the deployment did.

**Applied raises nothing**, which the first attempt got wrong: it reported every
row as unauthorised, including the ones running under terms the tenant agreed to
by joining. Crying wolf about a deployment that stated its terms up front is
exactly how an incident stops being acted on.

**And the withheld set is ASKED each pass, never cached.** The first attempt
held it from when the tenant was followed, so authorising a row left its work
withheld until something restarted — stale at precisely the moment it mattered,
since the whole point of classifying these fields `hot` is that granting or
withdrawing authorisation costs no outage.

*Proven by:* `AnUnauthorisedRowObeysItsPostureIT`, claiming
`REQ-DBO-PROC-AN-UNAUTHORISED-ROW-OBEYS-ITS-POSTURE` — three postures and three
answers; the withheld row raising no incident because nothing happened under it;
and authorising one row releasing that step while the other's incident stands.

**The second of those three is done.** A tenant admits a step by saying
nothing and declines it with one line, and a declined step is offered that
tenant's work at all — the run stays where it is, exactly as a run of a step
the deployment does not perform does, because from the tenant's side those are
one fact. Declining applies while the tenant serves, classified `hot`, because
withdrawing authorisation must not cost an outage.

**And the required case has its teeth.** Declining a step the deployment
declares required is refused at the declaration, naming the step and saying it
is written in the management tenant where the set can be read before anybody
joins. An agreement a tenant can leave by editing its own file is not an
agreement, which is what decision three was really asserting.

*That part proven by:* `ATenantAdmitsOrDeclinesWhatIsDoneToItIT`, claiming
`REQ-DBO-PROC-A-TENANT-ADMITS-OR-DECLINES-WHAT-IS-DONE-TO-IT`.

**What is left of step 7, and why.** The register itself and the incident are
now unblocked by the grain decision and are ordinary work. The two postures
that turn on sealing are not: nothing in steps 4 to 6 seals or carries a
payload — the joiner sends the run by identity and a performer is handed four
strings — so *refusing to seal where a row says not until approved* has nothing
to refuse yet. A performer opens through `Lane.inputs`, which already records
the access on the document naming the opener in that tenant's own trail, so the
third part of step 7 is the existing mechanism rather than new work. What has
to be built before the postures can be is **per-tenant enrolment of an
application-level processor**, which decision two settled in shape and no step
below 7 schedules.

## What this plan does not schedule

**The reduced account**, because what it holds is undecided and the constraint
is easier than the content: nothing about a person, nothing a tenant's own
record already answers.

**Erasure reaching an opened copy.** A sealed copy in flight is answered; a
copy a processor has opened and holds is not, and it is a question about the
store's promises rather than about this design.

~~Whether a row is a slot or something finer, and whether *consent* is the
word.~~ **Both settled** — a row is a slot, because the trail records access
per document and the comparison is what an incident is; and the word is
authorisation, because consent names a data subject's basis and this is a
tenant's.

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

- A worker in the deployment can take the substrate at all — done.
- Decisions 1, 2 and 3 answered, because each changes what is written rather
  than how.
- And the thing to prove first, before a joiner exists: that a step service
  reached over the stream and one reached over HTTP are indistinguishable in a
  deployment — **done**. The harness proved it for the lane, and
  `TheWorkArrivesOverTheSubstrateIT` now proves it for an application built on
  the assemblies: the same application, the same bean and the same step as the
  HTTP test beside it, with one word changed.
