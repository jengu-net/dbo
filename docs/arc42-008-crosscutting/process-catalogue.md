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
A private table confers none of those, which is why a dead-letter row is
unreachable today — visible in the sense that it exists, and invisible in every
sense that matters.

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

**Item work lives in child runs.** Every update is a version, a history link and
a feed event, so a busy run must not rewrite one large document per item; and a
person fixes one thing at a time, which a single card saying "two problems"
cannot express. **A run has at most one parent, and parenthood never crosses a
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

**A step declares the actions it contains.** Open a task, close it, reopen a
closed one. Roles narrow *actions within* a step — an operator works the open
tasks, a supervisor also reaches the closed ones — so without declared actions
there is nothing for a role to narrow. It is also what "held by a person" means:
manual is the baseline here, and what that person may do is exactly the set an
automated executor would otherwise perform.
