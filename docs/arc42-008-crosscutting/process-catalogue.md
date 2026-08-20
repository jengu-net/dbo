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

The delivery run is keyed by subscription and sequence and the sweep by domain,
so a step re-executed after a crash finds its run rather than starting a second
one — a duplicate would double every count taken from it.

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

**A step declares the actions it contains.** Open a task, close it, reopen a
closed one. Roles narrow *actions within* a step — an operator works the open
tasks, a supervisor also reaches the closed ones — so without declared actions
there is nothing for a role to narrow. It is also what "held by a person" means:
manual is the baseline here, and what that person may do is exactly the set an
automated executor would otherwise perform.
