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

