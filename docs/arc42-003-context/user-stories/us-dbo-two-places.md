# US-DBO-TWO-PLACES — one tenant in two places, and content that belongs somewhere else

> The clinic takes its canonical content from a national zone it does not
> run, and it puts an appliance in the building so that a lost connection
> is an inconvenience rather than a closed practice.
>
> Both halves are the same idea — content that belongs somewhere else,
> arriving because somebody declared that it should, and staying legible
> about where it came from. What differs is **the bound**, and the two are
> deliberately different.
>
> Canonical definitions travel **by type**: the clinic asks for code
> systems and gets every version of them, because none of it is about
> anybody. Patient data travels **by work**: it arrives with a task and
> leaves when no open run still names it. That second bound is the whole
> reason an appliance does not slowly become a copy of the clinic.

## The scene

Ines's clinic is a dependent of a zone tenant that publishes terminology, and
the practice building has an appliance running the same tenant as the cloud.
Neither arrangement involves a second copy of the store's model on anybody's
side of a wire.

## What the clinic asks for is what it gets

The zone publishes before the clinic exists, which is the ordinary case:
canonical content is older than the practices that use it. The clinic's spec
declares the dependency and the types it wants, and the runtime wires the
stream at bring-up.

A type the clinic did not declare brings nothing, however much of it the zone
holds. That is the point of a declaration being a bound rather than a hint: a
clinic that took whatever its upstream happened to have would eventually hold
somebody else's whole catalogue.

Dependencies are against the **direct** upstream only. Chains compose hop by
hop, so nobody inherits a transitive relationship they did not agree to.

## Two appliances of one tenant

The appliance is not a second tenant. Same code, same declarations, same face;
only the local settings differ.

What a run produced on the appliance travels with that run, and lands on the
cloud **filed under the appliance that made it** rather than merged into the
cloud's own records of the same type. The run comes too, mirrored beside the
cloud's rather than on top of them, because the side that authored a run is
the only side that advances it.

That asymmetry is not tidiness. Across a link that is merely slow, "the
deadline passed" and "the checkpoint is in flight" can both be true, and a
peer acting on the first has the work done twice.

## Reconnecting is ordinary

A batch sent twice applies once. An appliance that loses its connection,
reconnects and repeats itself is the normal case rather than an error, so the
lane absorbs the repeat instead of refusing it.

A peer resuming a cursor that a different lane instance issued is refused
rather than replayed. An appliance restored from last week's copy looks
perfectly healthy while resuming a position that means nothing, and the epoch
is what makes that visible instead of silently wrong.

## A bound nobody can see is a bound nobody can rely on

Asking the lane for a type it does not carry is refused **by name**, saying
what it does admit. A tenant admits every declared type except the ones about
a person: those travel by work or not at all.

## Joins

The promises this story rests on, projected from the catalogue rather than
written here: a story claims no evidence, and a leg is what its promise's own
citations say it is.

<!-- story:begin — generated from the promise catalogue; do not edit. Regenerate: ./gradlew :core:harness:promiseProjection -->

| Promise | Says | Status |
|---|---|---|
| `REQ-DBO-SYNC-SPEC-DECLARED` | A tenant's content dependencies are part of its tenant spec (configuration); the runtime wires declared streams at bring-up and removes them when undeclared. | PROVEN |
| `REQ-DBO-SYNC-DECLARED-ONLY` | Cross-tenant content synchronization happens only for declared dependencies; nothing syncs undeclared. | PROVEN |
| `REQ-DBO-SYNC-DIRECT-UPSTREAM-ONLY` | A tenant declares dependencies only against its direct upstream; chains compose hop by hop. | PROVEN |
| `REQ-DBO-SYNC-ANY-TYPE` | Any resource type can be declared as a cross-tenant dependency; each type defines its grain — for terminology, the CodeSystem together with its related ValueSets. | PROVEN |
| `REQ-DBO-SYNC-FULL-HISTORY-CATCH-UP` | A newly declared dependency catches up from the upstream's full history; pre-existing content arrives the same way live changes do. | PROVEN |
| `REQ-DBO-SYNC-PROVENANCE-COPIES` | Streamed copies are read-only and provenance-tagged with source tenant and version; updates and retirements propagate through the same stream. | PROVEN |
| `REQ-DBO-SYNC-LOCAL-SHADOWING` | A tenant's own object with the same base identity overrides the streamed copy — version-neutrally, across FHIR versions and business versions; removing the override falls back to the live upstream version. | PROVEN |
| `REQ-DBO-SYNC-CONVERT-ON-APPLY` | Streamed objects are converted at apply into the receiving tenant's FHIR version and object shape by the registered converter chains; an unconvertible object dead-letters visibly and degrades the dependency, never silently skips. | PROVEN |
| `REQ-DBO-SYNC-TERMINOLOGY-GRAIN-SURVIVES` | A streamed terminology dependency rebuilds the receiving tenant's native form: the source sends the whole CodeSystem even though it stores a shell, and the dependent takes it apart into its own concepts. After catch-up the dependent answers `$lookup` and `$expand` locally, which is the only proof that the grain survived the hop — a copy's stored payload never contains a concept at either end. | PROVEN |
| `REQ-DBO-ZONE-DECLARATIONS-AS-RECORDS` | A zone is a tenant whose declarations — identity brokers, identifier domains — are regular records: versioned, audited, exported, and streamable down the same chains as any content. Secrets are never in a record. | PROVEN |
| `REQ-DBO-ZONE-SUBJECT-DOMAINS` | Subject-resolution identifier systems come from the zone's declared domains — the official national terminology — never from dbo code. | PROVEN |
| `REQ-DBO-ZONE-BROKER-CHOICE` | The broker set is jurisdictional, the choice organizational: the zone declares the available national brokers; a tenant selects its contracted one and may restrict what it accepts. | PROVEN |
| `REQ-DBO-ZONE-SESSIONS-ACCUMULATE` | The per-zone hub's session records which broker performed each ceremony and accumulates ceremonies; cross-broker reuse is the default, tenant acceptance policy the restriction — the strictest tenant is satisfied without invalidating anyone else's session. | PROVEN |
| `REQ-DBO-PROC-THE-LANE-HAS-TWO-BOUNDS` | What moves between two appliances of one tenant has two bounds, deliberately different: declarations by type — the tenant's own definitions, none of it about anybody — which travel as every version of the types asked for since the peer's position, filed under their source, read-only there, shadowed by a local override and never revoked by work; and patient data by work, which arrives with a task and leaves with it. What a run produced travels with the run as a copy that outlives it. A type the lane does not admit is refused by name, never quietly left out. | PROVEN |
| `REQ-DBO-PROC-WORK-DRIVEN-ARRIVAL-AND-EXPIRY` | A record travels to an appliance because a piece of work names it, and is removed when no open run there still names it. Work-driven arrival without work-driven expiry is a bench accumulating a register one task at a time. | PROVEN |
| `REQ-DBO-PROC-MIRRORED-RUNS-ARE-FILED-BY-APPLIANCE` | A run arriving from another appliance of the same tenant is stored under that appliance, beside the local run of the same key rather than on top of it. | PROVEN |
| `REQ-DBO-PROC-AUDIT-REPLICATES-AS-RECORDED` | An appliance's audit entries reach its peer as that appliance recorded them — original actor, original time, and the appliance named — and the arrival writes no second trail. Direct writes to the audit type stay refused for every caller; the replication lane is admitted through one narrow port that can express no other write, and a re-delivered entry lands exactly once under the source's own identity. | PROVEN |
| `REQ-DBO-PROC-LANE-APPLY-IS-REPLAY-AND-REORDER-SAFE` | What a peer sends applies once however often it is sent, and a batch arriving behind a newer one does not put the older version back. The comparison is the source version, so neither property depends on the transport being careful. | PROVEN |
| `REQ-DBO-PROC-LANE-EPOCH` | A lane carries an epoch, and a peer resuming a cursor issued by another lane instance is refused rather than replayed — an appliance restored from a copy looks healthy while resuming a position that no longer means anything. | PROVEN |
| `REQ-DBO-FEED-PUSH-ACK-RESUME` | Push consumers acknowledge with the cursor; any interrupted stream resumes from the last acknowledged position. | PROVEN |
| `REQ-DBO-FEED-IDEMPOTENT-DELIVERY` | Delivery is at-least-once with idempotent apply by identity and version. | PROVEN |

Coverage: {PROVEN=21} — a leg marked PLANNED cites a promise that exists and is not yet cited by any test.
<!-- story:end -->

## What the store cannot do yet

- **The store builds no channel.** It hands a caller a batch and accepts one
  back; something outside carries the bytes, authenticates and reconnects. A
  network is somebody's job and not a dependency of the store working, which
  is right and also means there is nothing here to configure.
- **Moving work between appliances is a person's act.** An appliance that dies
  holding work it authored keeps that work until it returns. Nothing infers
  from a slow link that the other side should take over.

## Open decisions

- **What a zone should do when a dependent declares a type it does not
  publish.** Today the dependent simply receives nothing, which is
  indistinguishable from an upstream that has not published yet.
