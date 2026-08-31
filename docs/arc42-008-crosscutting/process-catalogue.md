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
- **A node answers what it knows, and most of the accumulating needs no
  transport.** The container scans installed bundles' process annotations, so
  a node says what it can do — with the contributing bundle named per step —
  while serving no tenant at all. Across nodes, declared candidates and
  introduced steps meet in the **tenant's own store** rather than in a mesh:
  every participant writes there whatever node it runs on, which is why an
  offline node costs nothing and why no service-sharing layer is involved.
  What does not travel is a node's *installed* catalogue, and it cannot go
  through the introduction door — a step both doors declare is refused as a
  collision, which two nodes carrying the same modules would hit at once. So
  the missing piece is a per-node **inventory**, descriptive rather than a
  second declaration of somebody else's step. What is *running* is a
  different question again, answered by runs, which are records.
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

Manual is the baseline and automation is an attachment. A step is fully defined
— input, output, purpose, who may act — before any runner exists; an automated
executor is then a rule that claims the cases it can handle, the way a mailbox
rule does, and everything it does not claim falls through to a person.

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

## Where the work goes

A step is defined here; **how it reaches whoever performs it** — participation,
resolution against declared candidates, what a connected worker routes for, and
replication between two appliances of one tenant — is
[`distributed-work.md`](distributed-work.md).
