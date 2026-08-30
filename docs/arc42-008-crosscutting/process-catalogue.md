# Process catalogue and the live process map (§8)

There is a way to define domain work that is FHIR-native rather than
FHIR-adjacent: a **code-owned process/step catalogue** (a `ProcessStep`
contract plus per-module enums carrying profile references), projected to
`PlanDefinition`/`ActivityDefinition`, with running instances linking back
through `instantiatesCanonical` and every step advancing the workflow resource
and stamping provenance. Applications that already work this way keep those
concepts application-side. They belong *in the store*:

- **The annotation and catalogue library is a DBO module** (`dbo-process`):
  the `ProcessStep` contracts, the plane declaration from §7.4 (platform |
  tenant), profile references, and the projection to
  `PlanDefinition`/`ActivityDefinition` become part of the store's own
  vocabulary. Every DBO-hosted application declares its processes the same
  way, and the catalogue projections are generated, never hand-edited.
- **Every process and step carries a process-domain code** — a free string
  (`clinical.lab`, `identity`, `ops.retention`, …) declaring what sphere the
  work belongs to. It is *not* a taxonomy the model enforces; it acts purely
  as a **filter**: the process map, monitoring views and catalogue projections
  filter by it, so DBOS housekeeping (sync sweeps, retention) never
  masquerades as clinical work in a clinical view, while still being fully
  visible in an operational one. The rule that the FHIR-native catalogue
  governs domain work and not operational machinery becomes a default filter
  rather than a structural boundary: everything on the network
  is declared and scannable; which slice you look at is a query. (Naming
  note: "process domain" is deliberately distinct from the storage **Domain**
  of §2/§3 — the physical table-group knob. Two different words in the code.)
- **A system scanner builds the live process map.** The container scans
  installed bundles' process annotations and publishes each node's *known*
  catalogue (declared processes/steps) and *running* state (active DBOS
  workflow instances per process) into the registry; the dOSGi layer (§5)
  shares and **accumulates the map across the DBO network** — one queryable
  answer to "which processes exist on this network, where are they running,
  in which version". Platform-plane state; no resource content.
- **The catalogue is a security artifact.** Hop grants (§7.4) are only
  issuable for hops the declared process shape actually contains; a workflow
  step that isn't in any declared process has no plane, no grant path, and no
  way to cross a boundary. Scanning makes this checkable at install time.
- **Tracing joins what the store already knows.** Because instances stamp
  `instantiatesCanonical` and every write lands in history + outbox + audit,
  the navigation *process → step → the FHIR update diffs and audit records it
  produced* is a join over data DBO already holds — resource version diffs
  from history, `AuditEvent`/`Provenance` from the stores, DBOS workflow/step
  ids as the correlation spine. Exposed two ways: surfaced in monitoring and
  tracing tools (OpenTelemetry spans carrying process and step codes, so the
  tracing stack shows the same identifiers the catalogue declares) and as a
  DBO query surface for building process-timeline interfaces. This is where
  the earlier engine's unbuilt "tracing chains of changes" thesis finally
  lands (§9.5) — not as a bolted-on audit product, but as a join the data
  model was shaped for.

## What a run is

A step that runs leaves a **run record**, and that record is an ordinary
registered type in a tenant's own store rather than a private table. The
difference is the whole point: registration is what confers an envelope to query
it by, a history, feed events, a place in the backup and erasure with the tenant.
A private table confers none of those: it is visible in the sense that its rows
exist, and invisible in every sense that matters.

**Who holds it now is the load-bearing field.** Automation running, automation
with a retry scheduled, **a person**, or nobody. Every other field answers a
question somebody asks after that one, and the third is the one an operator most
wants: a list that silently omits the run nobody is coming back to answers "which
work is fine" while looking like it answered "which work exists".

**Runs come in two kinds, and they close differently.** A **pipeline** closes
when every item is terminal — a delivery, an import, an ingest. A **sweep**
closes when the world agrees — tenant bring-up, a retention pass, a
configuration application, a stream converging on its upstream. dbo has more of
the second than the first, and modelling a reconciler as a pipeline produces a
run that never ends and a needs-a-person queue filling with work that is merely
still converging.

**Escalation follows the failure class, not the exception.** A record that is
wrong is a person's job; a store that is unavailable is a retry and nobody's
card. The line is already drawn in the type system, so it is read from there
rather than restated — and only record-class failures make work, because a queue
that collects transient faults stops being read.

**A run closes by re-evaluation** wherever the condition is machine-checkable:
the person fixes the world and the next pass finds nothing to say. Closing by
hand is the fallback for conditions nothing can re-check, and never the default —
a card closed by click reads *resolved* while the fault is live.

**Item work lives in child runs, and children are exceptions rather than an
enumeration.** Every update is a version, a history link and a feed event, so a
busy run must not rewrite one large document per item; and a person fixes one
thing at a time, which a single card saying "two problems" cannot express. But a
child per item *processed* is the same cost in the other direction: an ingest
over forty thousand concepts would be forty thousand records, forty thousand feed
events and a history nobody can page through. It records a tally of forty
thousand and three children, because three of them need a person. What everything
did is the tally's; what somebody must act on is a child's. **A run has at most one parent, and parenthood never crosses a
domain or a system:** items are children, while a subprocess or a continuation
elsewhere is a reference. A parent's close has to mean something for its
children, and it cannot mean anything across a boundary this runtime does not
control.

**A correlation carried from elsewhere is echoed, never interpreted.** It makes a
cross-system join queryable from either side; parsing it would put the other
system's vocabulary inside the engine (ADR 0060).

**The envelope carries state, not subject.** Holder, step, kind, counts — never
item references or messages. Progress that reveals what was being processed is a
disclosure decision rather than a convenience (ADR 0058), and the record itself
still holds what a person needs in order to act.

### Who runs a step

A step is fully defined — input, output, purpose, who may act — before any runner
exists. An automated executor is then a rule that claims the cases it can handle, the
way a mailbox rule does, and everything it does not claim falls through to a person.

**Precedence selects; the step grants the right to override** ([ADR 0059](https://github.com/jengu-net/jengu-platform/blob/main/docs/arc42-009-architecture-decisions/0059-precedence-selects-and-a-step-declares-whether-it-may-be-overridden.md)).
Resolution walks the overlay chain terminology and configuration already walk — baseline,
zone, organisation — and offers the work to the most local candidate that is **willing
and permitted**. Willing is the candidate's answer; permitted is the step's, and **not
overridable is the default**. Specificity is self-declared, so precedence alone would let
any party displace a national rule by narrowing its scope. A step that names a class as
able to override admits everything wider than it too: a step that lets an organisation
vary it has already accepted that a zone may.

A refused override does not disappear because the step's own executor ran. It is recorded
on the run, because somebody attempting to displace a rule is a fact about their rule.

**Nothing races.** Candidates are tried one at a time in declared order. Racing them makes
the same input behave differently under load, doubles external effects — the store makes a
losing write a no-op, but nothing makes a losing call to somebody else's registry one —
and splits provenance across two actors, so afterwards nobody can say who did the thing.

**Candidates are asked for, never held.** Existence follows the face registry: a candidate
is available because something providing it is installed, and a provider can be withdrawn.
A resolver holding a list keeps selecting an executor that is no longer there.

**The run names what ran it** — the executor, its version, its provider and the scope it
was chosen at. All four, because a provider can be withdrawn and a scope re-declared, and
without them a decision made last year cannot be reproduced.

**Whether a step is automated here is declared configuration** on the same chain, and the
most local declaration wins: an organisation can turn back on what its zone turned off. A
zone switching automation off is a decision somebody made, not a code path that happens to
be unreachable.

**What nothing took is a person's, and it is countable** — per step, per zone, off the
envelope. That number is the automation backlog stated as a fact rather than an opinion,
and the person holding the work is told which of the three reasons they are looking at:
nothing claimed it, this zone switched automation off, or a narrower scope tried to
override a step that does not allow one.

### dbo's own processes

dbo runs on this model rather than beside it. Two of its own, one of each kind:

| Process | Step | Kind | What a run is |
| --- | --- | --- | --- |
| `dbo.subscriptions.delivery` | `post` | pipeline | one attempt at one notification; retries are the step's, and an attempt that runs out of them leaves a child item **held by a person** — the endpoint is wrong, gone or refusing, and no amount of clock fixes any of those |
| `dbo.policy.retention` | `remove` | sweep | one durable run per tenant domain, found rather than started; each pass tallies what it removed and names any rule it could not apply, and a rule a pass stops finding is closed by that pass |
| `dbo.config.applied` | `apply` | sweep | one durable run per declared scope; *read N, applied M, skipped K with reasons*, one card per declaration nobody can apply, and the correlation the declaration carried echoed on the run. One bad record never takes the rest of the zone with it |
| `dbo.tenant.serving` | `serve` | sweep | one durable run per deployment, in the management tenant; one card per tenant that is not serving — a spec somebody must change is a person's, an upstream that is not up yet is a retry — and a file that never parsed is named by the file, because it has no tenant to be a state of |
| `dbo.tenant.serving` | `retract` / `erase` | pipeline | separate steps carrying different authority. Retraction stops serving and touches no data, and says who did it or that the declaration was withdrawn. Erasure removes the data, is an operator act, and is not reachable from the scan path at all |
| `dbo.sync.stream` | `apply` | sweep | one durable run per declared dependency, converging on the upstream's head. A parked shadow — an upstream version a local override is holding off — is a card held by a person and closes itself on the pass that stops finding it; an unreachable upstream is a retry and nobody's card |

The delivery run is keyed by subscription and sequence and the sweeps by their
scope, so a step re-executed after a crash finds its run rather than starting a
second one — a duplicate would double every count taken from it.

**The deployment's own history lives in a tenant** — the juridical body
operating it, a tenant like the others and distinguished by role rather than by
position (ADR 0061). It is declared by deployment configuration rather than by a
file in the watched directory, so the scan loop that retracts undeclared tenants
cannot retract the thing recording retractions, and a deployment whose management
tenant will not come up serves nothing: that is the one failure with nowhere to
be recorded, and it belongs in the log and the exit code.

Two bullets pull against each other here, and the ADR decides it: management
history is a record of the work, never a condition of it. So the serving sweep is
written **from** the same runtime state `/runtime/tenants` answers with — one
reasoning, recorded — and the endpoint keeps reading that state directly. It has
to answer when the management tenant is down, which it could not do if the answer
came out of that tenant's store.

A stream's run belongs to the **dependent** tenant, because it is their work.
The upstream is named on it and never parented: parenthood cannot cross a tenant,
and a tenant is a legal person (ADR 0061). Nothing reaches back either — whoever
declared the configuration closes their own run by re-evaluating against what
this one reports, rather than dbo calling them and requiring both systems to be
up at once (ADR 0060).

### Who runs a step

Manual is the baseline; automation is an attachment. An automated executor is a
rule that claims the cases it can handle, the way a mailbox rule does, and
everything it does not claim falls through to a person.

**Precedence selects; the step grants the right to override** ([ADR 0059](https://github.com/jengu-net/jengu-platform/blob/main/docs/arc42-009-architecture-decisions/0059-precedence-selects-and-a-step-declares-whether-it-may-be-overridden.md)).
Resolution walks the chain terminology and configuration already walk — baseline,
zone, organisation — and offers the work to the most local candidate that is
**willing and permitted**. Willing is the candidate's answer; permitted is the
step's, and *not overridable* is the default. A grant names the most local class
that may override, and everything wider than it may too: a step that lets an
organisation vary it has already accepted that a zone may.

**Nothing races.** Candidates are tried one at a time in declared order. Racing
them makes the same input behave differently under load, doubles external
effects — the store makes a losing write a no-op, but nothing makes a losing
call to somebody else's registry one — and splits provenance across two actors.

**Candidates are asked for, never held.** Existence follows the face registry:
a candidate is available because something providing it is installed, and a
provider can be withdrawn. A resolver holding a list keeps selecting an executor
that is no longer there.

**A refused override survives.** When a narrower scope tries to displace a step
that forbids it, the step's own executor runs and the attempt is recorded on the
run — a fact about somebody's rule does not stop being one because something
else ran.

**Whether a step is automated here is declared configuration** on the same
chain, most local declaration winning. A zone switching automation off is a
decision somebody made; a step that is simply never reached is not.

**What nothing took is a person's, and countable.** Per step and per zone, that
number is the automation backlog — a fact rather than an opinion about how much
is automated.

### How a run is read

The engine stores a run; a reader asks a face for it. One run and its items render as
one collection: the run as a `Task` carrying its holder, its process and step, its
tally and its correlation, and each item as its own `Task` under it with the failure as
an `OperationOutcome`. The engine never spells any of that — which resource a run is,
and how a holder is said in it, is a face's business
(`core.face.RecordProjection`, [engine-and-faces](engine-and-faces.md)).

A run over a domain no face claims — `identity`, a config domain — renders nowhere, and
the reader is told so rather than handed an empty document.

### What a step declares

A step says what it is **before anything runs it**: its id, its version, the
storage domains it reads and writes, opaque references to the shapes it consumes
and produces, the actions it contains, its named **input slots**, and whether
anybody else may override it. Manual is the baseline —
a step nobody has automated is not an absent step, it is one held by a human, and
automating it later changes the holder and nothing else.

**A step id is `<module>.<process>.<step>`, opaque and globally stable.**
Cross-module references need no compile-time coupling, and a run may name a step
that has no declaration yet — which is why the scheme was fixed with the record
rather than with the catalogue. Two modules declaring one id is a collision
rather than an override, and a step referenced but not installed is refused **by
name**: silently doing nothing is the failure that rule exists to prevent.

**Modules contribute by being installed**, the same rule faces follow — nothing
maintains a central list that can disagree with what is deployed.

**And a linked participant contributes by introducing.** The catalogue's second
door: a component attached only over the participation link brings its step's
whole declaration — shapes, domains, actions, slots, milestones — beside its
own candidacy, recorded in the tenant's store with the introducer's name as
provenance. Kept until withdrawn rather than dropped with presence, because a
run recorded under an introduced step still needs its declaration to be
interpreted while its participant naps — candidacy is what presence gates, and
that is the executor declaration's business. One collision rule holds across
both doors, and it guards the **definition**, not the door: re-introduction by
the same participant replaces, a *different* participant bringing an identical
declaration co-introduces — scaling is parallel runners (DBOS runs parallel
consumers), a replica set comes up racing itself, and a fleet is not a
conflict, so a lost write race is re-read rather than thrown — and what is
refused by name is a different definition for one id: a conflicting
introduction, or a module installed beside one. **An introduction grants its introducer nothing**: the
declaration binds the introducer exactly as it binds anybody, and the record
is written through the tenant's store, so a credential that may not write
there is refused by the authority rather than by the catalogue.

**The catalogue is built up, not ported.** Installed modules contribute their
steps, linked participants introduce theirs, and the only consistency claim is
the tenant spec's **`mandatorySteps`** — the steps this tenant's work cannot do
without. It classifies; it never gates. The system is asynchronous by design:
work buffers on the queue when nothing serves a step, and a participant
arriving later drains it — so a missing step executor must not take the tenant
offline, which would convert that graceful degradation into a self-inflicted
outage. What the list decides is whether the absence is an **incident**: a
mandatory step nothing has contributed is one, named on the operator surface
and in the log, cleared by the scan after the step arrives and reopened if its
contributor leaves. Every step not on the list is non-critical by
construction: free to appear with its participant and disappear with it, its
absence no incident at all. (Contrast the face contract, which genuinely
refuses at bring-up: a missing face capability breaks serving itself, not just
one step's throughput.)

**Shapes are opaque to the engine.** It can no more compare a shape than name
one, so what it does with a shape reference is hand it to the face. Validating a
payload against a step's declared shape is therefore an **overload on the
existing payload capability**, not new machinery: the same validator, held to a
profile somebody else named. A shape the face cannot resolve is an issue rather
than a pass — treating it as nothing-wrong is how a precondition quietly stops
being one.

**A caller can ask the narrower question before committing.** `$validate` takes
the operation's own `profile` parameter, and a **step id is accepted where a
profile is expected** — because *would this be accepted as the input to this
step* is the question a caller actually has, and making them look the canonical
up first asks them to know what the catalogue already knows. The outcome names
the shape it was held to: told only "invalid" against an unnamed profile, a
caller cannot tell whether they used the wrong shape or the wrong data, and
those have different fixes in different people's hands.

A profile this store does not carry is refused rather than fetched. A caller who
wants an arbitrary published IG is asking for a validation service, not for this
store's opinion about its own content.

**A run records the step version it ran under**, beside the executor's version
and provider. Reproducing a decision made last year needs the definition as well
as the runner, and a run naming only one of them explains half of what happened.

**The slots are the input API, and a run fills them at creation.** A step
declares named slots beside `consumes` — `consumes` is the shape of the thing
the step acts on (the item, rendered as `Task.focus`); slots are the additional
documents the work is over (the order, the specimen, the device reading),
rendered as `Task.input` in declaration order, the reference displayed rather
than resolved. Every declared slot is mandatory — an input the step can do
without is not a slot — and creation refuses both mismatches by name. The
references stay opaque to the engine, exactly like the item's: resolution is
the lane's act, by the party that legitimately holds the objects, for the
identity that claimed the work — and there is no verb that takes a reference,
so a runner cannot ask for data the step never entitled it to.

### How work reaches whoever does it

A run says who holds it; a **participant** is how a holder gets it — a service, an
edge, a hospital's own system, a person at a screen. It is the change feed's
fifth use rather than a sixth mechanism: a named consumer, a cursor, an ack
(`REQ-DBO-FEED-ONE-PRIMITIVE`), which is also why each participant's backlog and
lag are observable without anything being built for them.

**Pull, never push.** dbo holding a client for every external system is the shape
ADR 0060 rejected, and participants are precisely the things behind NAT, on
edges, and offline for a weekend. Pulling makes an offline participant a lagging
cursor rather than an outage.

**What a participant may claim is the intersection of what its credential
covers and what the step admits**, and the two halves are enforced where each
belongs. The step's half is at the primitive, where the declaration is: the
baseline always may — it is not an override, it is the rule — and anything more
local may only where the step opened itself to that class (ADR 0059), so a step
cannot grant its executor more than the executor already holds. The credential's
half is at the lane, because the lane is the only door a participant has and
only the host knows what the credential covers: the entitlement narrows what
`poll` offers and refuses what `claim` may take. An entitlement is stated when
the lane is provisioned — *everything*, because the host is the tenant, or the
steps a credential covers — and there is no implicit unrestricted, so no remote
participant's reach depends on a parameter somebody forgot.

**A claim is a conditional write with a deadline.** At-most-one actor needs no
lease service: two participants racing one run produce one winner and one version
conflict, and the loser takes the next run rather than coordinating about this
one. The deadline exists because a participant that dies must not hold work for
ever, and nothing but the clock is going to notice. **The claim is also the dedup
point** — delivery is at-least-once, so a participant will see the same run
twice, and its own bookkeeping must not be what saves it.

**A claim is extended by checkpoint, never by heartbeat.** A tick proves a
process is alive, and what a deadline protects against is a process that is alive
and getting nowhere. Counts are the evidence, and they are on the record anyway.

**A checkpoint can name the milestone reached.** A long-running step is visible
between claim and outcome the way events ride a tracing span: the step declares
its milestones in order, the executor asserts only the name, and the store
derives the position over that order — a completeness nobody declared cannot be
derived, only invented, so an undeclared step's name is recorded verbatim with
no position, and a name outside a declared order is refused naming both sides.
The run keeps the milestone replaced-never-accumulated, across release and
retake, so the next taker resumes from a fact; the face says it in the Task's
`businessStatus` beside the holder ("validated, 2 of 3"). Nothing on the
reporting path may default it away: a lane or decorator that degraded a
milestone to a bare checkpoint would drop the one thing the report said while
passing every test.

**Released is not done.** A run that says done because whoever held it stopped
answering is the failure a deadline exists to prevent, so a lapsed claim is
handed back saying exactly that.

**One participant, embeddable, that automates nothing.** Every place that does
work needs the same three things — pull, claim, report — and none of them should
be written twice: a hospital integrating with dbo embeds a participant, not a
FHIR client plus a webhook plus a queue. Its job is to carry work to wherever the
work is actually done and carry the result back, and the run afterwards reads as
it would if dbo had done the work itself. An integration is not a second kind of
history.

**A host holds the lane, and it does not have to hold the store.** The lane is
built by the party that legitimately has the tenant's objects — that is what keeps
the runner's world to twelve verbs. But the party that *serves* work is not always
the party that *holds* it: an appliance running dbo in its own JVM builds a lane
over its own store, while a deployment where dbo is its own process — so that the
application never holds `CREATE DATABASE` — has no store handle to build one from,
and is exactly the side the appliances pull from. So the tenant serves the
participation verbs on its own private surface, guarded by its own authority, and
such a host holds a lane that reaches them. The runner cannot tell the two apart,
which is the same contract the interface already states.

Two rules keep that from being a wider door than the in-process one. **The
entitlement comes from the credential, never from the request** — the bare
participation scope is a host saying it *is* the tenant, a suffixed one bounds
the holder to the steps it names, and there is no implicit unrestricted. And **a
bounded credential works only as itself**: the executor identity is what a claim
is recorded under and what the input read is checked against, so a credential
free to spell any name could read the inputs of runs it never claimed. What is
offered over the surface is the lane and nothing wider: no verb takes a
reference, none hands back a store handle, and a widened primitive would be
available to every caller with the scope, for ever.

**Two layers own different failures.** Whatever runs the work locally owns local
durability — resuming its own half-finished work after a restart. The
participation client owns the global truth: what is owed, by whom, and what
happened. With only the first, work is durable and invisible to everybody else;
with only the second, a crashed runner loses its half. The contract line falls
between them, which is why **no orchestrator is named** in it — the same
participant runs as a service, on an edge with none, and as a workplace with a
person inside it, where opening a run is the claim and finishing it is the
report.

**You scale by adding claimants, never by relaxing the claim.** Partitioning is
the second lever and is not built: competition is fine at small N, and a
partition hint belongs on the run only once one step has measurably outgrown it.

### Who can run a step here

**An executor exists because something announced itself**, the way a face is
served because a bundle providing it is installed. A participant declares
process › step, scope, version and provider as a record in the tenant's store,
and resolution walks those declarations rather than this container's bundles — so
a local implementation and a hospital's own system are two candidates for the
same step, ordered by the overlay chain rather than by which machine they are on.

**A declaration is a claim to be a candidate, never a grant.** What a participant
may actually take stays the intersection of its scopes and what the step admits;
a step cannot grant its executor more than the executor already holds, and a
record saying otherwise does not change that.

**Presence is derived.** A participant is present while its cursor moves, so a
declaration whose consumer is behind and unmoving is *declared but not present* —
skipped by resolution, and shown to an operator as exactly that, which is a
different sentence from "nothing is declared". The trap worth naming: **a
caught-up participant's cursor does not move either**, so silence with nothing
waiting is not absence, and only silence with work waiting is.

### A trackable may route other trackables

**The topology is a tree, and only its root has a cursor.** A connected worker
reports for itself; it may also be a **router**, carrying the state of things
behind it — an appliance behind a connector, an instrument behind that — to
arbitrary depth. All of them are the same kind of thing: something whose state
is worth knowing. So there is one record shape and one row per trackable at
every depth, and the rule about what a state is exists once rather than once
per router. Three routers each inventing it would disagree, and the
disagreement surfaces as a question about a bench that nobody can answer.

**What a trackable IS stays outside the engine.** It knows that a trackable
may route other trackables, and no more — the same line the run record holds,
where the engine never says the word a face renders it as. A face may project
a trackable and its state onto whatever its version spells connected things
with; the fields are chosen so that projection is mechanical, and the word
still does not appear here.

**Trust is delegated down the chain.** The store has no independent path to a
routed trackable — everything it knows arrived through the router — so a
router is trusted about its routees exactly as it is trusted about itself. It
is enrolled and authenticated, and a router lying about what is behind it is
the same problem as one lying about itself. Each hop owns liveness for the hop
below it, with whatever protocol suits that hop: a serial timeout, a TCP
keepalive, an application ACK.

**So the store imposes no freshness rule on routed state**, and this is a
refusal rather than an omission. It has no means to evaluate one, and a single
threshold would be wrong anyway — an instrument on a serial line and an
appliance on a socket have nothing sensible in common to threshold on. Report
quality is the router's contract, and a router that reports badly is a fact
about that router.

**Presence stays derived where there is a cursor, and is attested where there
is not.** An attestation names **the worker that reported**, which is not
always the parent: a connector reporting an instrument two hops away is the
observer, while the appliance between them is where it sits. That is not
second-class trust — knowing which hop last saw something is what tells an
operator where to look, and "where it sits" and "who to ask" are different
questions.

### A change belongs to a piece of work

A type may declare that **every change to it happens inside a run** — a
handling property like the others, refused by the engine when no run is in
scope. The point is not visibility: history already has the change and audit
already names who made it. The point is that it **belongs** to something, so
what happened is one record rather than an assembly job across two that were
never designed to agree.

**A run then names the versions it produced**, which is what makes work the
*manifest*: reading runs in order reads the content changes in order, and
another appliance asks for exactly what it is missing instead of comparing two
stores. Bounded, because a manifest is an enumeration and children are
exceptions — individual versions up to a cap, a per-type high-water mark past
it, and the run says which of the two it is. A run that stopped naming and did
not say so would let a reader believe it had everything.

**The engine cannot record this itself.** A store writing into the work domain
on every content write is the engine re-entering itself, so the recording is a
decorator: the write commits, then the run is told. The honest limit is a crash
between the two — a version the run does not name, which a far side then reads
by cursor rather than by manifest, and is behind rather than wrong.

**Bulk paths are runs, not exemptions.** An import, a restore and a replication
apply open a run and write under it, which is better than being excused from the
rule: they then appear in the same list as everything else, and what they
changed is as answerable as anything else.

### Two appliances of one tenant

An edge and its cloud hold **one tenant on two appliances** — same code, same
declarations, different local settings (ADR 0062). So a lane between them is
same-version replication: no converter chain, and the stored bytes travel as
they are.

**dbo builds no channel.** It hands a caller a batch and takes one back; a
connector outside dbo carries the bytes, authenticates and reconnects. What is
dbo's is store-level and nothing else: what the far side does not have, an apply
that is idempotent under replay **and safe under reorder** (source version wins,
so neither property depends on the connector being careful), the **epoch** that
makes a cursor resumed from a restored copy detectable, and the **marker** each
side keeps about where the other said it had reached.

**Data before work**, so nothing arrives pointing at something absent. **Bounded
by what the work names**, never by following references as far as they go —
Patient → Encounter → Observation → everything is how a bench ends up holding a
register.

**A record arrives with a piece of work and leaves with it.** What brought it is
noted, and a revocation pass removes what no open run still names — locally,
because the appliance holds the runs and can see for itself, and because a
withdrawal that had to arrive would leave a bench holding a register every time
the link was down. A card still open counts as work still needing the record;
somebody has to be able to look at what they are fixing.

**The side that authored a run is the side that advances it.** A mirror is a
read-only account of somebody else's work: it can be read, counted and compared,
and it cannot be claimed, checkpointed, released or closed where it landed. The
reason is the lane's own latency — across two stores "the deadline passed" and
"the checkpoint is in flight" can both be true at once, and a peer acting on the
first has the work done twice. So a deadline is judged only where the run lives,
and the housekeeping sweep skips what it did not author rather than refusing it.
The consequence is accepted rather than hidden: an appliance that dies holding
work it authored keeps that work until it returns, and moving it is an
operator's deliberate act rather than something a clock infers from a lane that
is merely behind.

**An appliance offers only what it authored.** The other half of the same rule,
and the one a pair discovers the hard way: a mirror sent back is a *new* record
at the far side — filed under the sender, prefixed again — so two appliances
that echoed would deepen a key and add a run every round, without bound. What
arrived from elsewhere does not go back out, which is the rule the replicated
trail already obeys.

**A mirrored run is filed under the appliance that authored it.** Two appliances
running the same task write the same run key, and without the namespace the
second arrival silently replaces the first — which is exactly the comparison
this makes possible: *applied 46/46 here, 44/46 there, same correlation*.

**The lane declares which processes travel.** `dbo.config.applied` does, because
its outcome is the tenant's business on every appliance. `dbo.tenant.serving`
does not: an appliance's account of its own bring-up is housekeeping, and
mirroring it would put an edge's answer to "what is serving" into the cloud's.

**A step declares the actions it contains.** Open a task, close it, reopen a
closed one. Roles narrow *actions within* a step — an operator works the open
tasks, a supervisor also reaches the closed ones — so without declared actions
there is nothing for a role to narrow. It is also what "held by a person" means:
manual is the baseline here, and what that person may do is exactly the set an
automated executor would otherwise perform.

**And a report lands through them.** Closing and reopening are acts of
judgment, checked at the primitive against the step's declaration — a step
whose actions omit `close` has said its closure is somebody else's act, and a
participant reporting done there is refused naming both sides. A step that has
not declared actions is not narrowed (empty means "has not said", never
"admits nothing"), and **releasing is never narrowed**: released-is-not-done
is failure honesty, and a step must not be able to refuse to hear that its
executor failed. A closed run reopens the same way — through the declared
`reopen`, claimable again with the reason on the record, instead of a second
run invented to disagree with the first.
