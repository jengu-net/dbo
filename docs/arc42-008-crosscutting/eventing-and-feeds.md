# Eventing, content dependencies and feeds (§6, §10)

## 6. Eventing

- **Source of truth**: the transactional outbox row, committed with the write.
- **Distribution**: DBOS streams/queues + `pg_notify`-class wakeups; no external
  broker.
- **FHIR surface**: R5/R6-style topic-based Subscriptions (backported to the R4
  personality) — rest-hook and websocket channels, per-tenant, durable,
  replayable from the outbox watermark.
- **In-process surface**: the same topics as OSGi events/callbacks for co-located
  consumers — identical semantics in embedded dev/test and in production.
- Legacy's broker-agnostic `Eventing` SPI (compaction keys, tombstones,
  dead-letter) is a good shape for the internal interface.

### Canonical content dependencies — streamed zone→tenant copies

Shared content published by an upper-chain tenant (zone) is **streamed as
read-only copies** into each dependent lower-chain tenant's own database.
Canonical artifacts (CodeSystems, ConceptMaps, ValueSets, profiles…) are the
motivating case, but **any resource type can be declared as a dependency** —
the mechanism is type-generic; only the grain rules are type-specific. This is DBO's
replacement for Medplum's `Project.link[]` — materialization-time instead of
resolution-time — and it is forced by a hard fact: tenants live in different
databases, and **indexing must be local** (searches, `$expand`, validation all
hit tenant-local envelope indexes; there are no cross-database joins).

- **Declarative and minimal.** The synchronized set must be as small as
  possible: each tenant *declares* its content dependencies — which
  CodeSystems/ConceptMaps/artifact sets it needs from which upstream tenant.
  The declaration is config (git, like everything else); DBOS-based
  cross-tenant synchronization processes derive the sync work automatically
  from it. Tooling can *propose* declarations by detecting references to
  canonicals absent locally — but nothing syncs undeclared.
- **Mechanics**: a dependency is a standing, platform-coordinated stream
  (the §7.4 hop model — no direct tenant-to-tenant connection): source outbox
  → filtered feed → apply into the dependent store. Copies are
  provenance-tagged (source tenant, source version) and **immutable locally**;
  updates, retirements and deletions propagate through the same feed.
  Audit rides at the *stream* level (dependency established/changed/removed),
  not per replicated object — this content is terminology, not PHI.
- **The synchronizer converts at apply.** Sender and receiver need not run the
  same versions — neither the same **FHIR version** (a zone publishing R5
  content to an R4 tenant, or vice versa) nor the same **tenant object shape**
  (jengu-style shape versioning, the stamp riding `meta.extension`). The
  stream carries objects in the sender's form; at apply, the receiver's
  converter chains — the same registered converters that power
  upgrade-on-read (§2) and version transitions — bring the copy into the
  *receiving* tenant's declared FHIR version and shape before it is stored
  and indexed. Provenance keeps the original version alongside the source
  tenant. An object the chain cannot convert **dead-letters visibly** and
  marks the dependency degraded; it is never silently skipped.
- **Override by shadowing.** If the tenant has its *own* object with the same
  canonical identity (url — identifiers/version rules per artifact type), the
  tenant's copy wins: resolution order is local > streamed. The streamed copy
  stays current underneath, so removing the local override falls back to the
  live zone version.
- **Granularity is a per-type grain rule.** For terminology the CodeSystem is
  the unit: declarations (and migration rules) are written at CodeSystem
  level, and a declaration pulls the whole CodeSystem *plus its related
  ValueSets*. No subset streaming — the generic rule stays simple, and
  minimality comes from declaring few units, not from slicing inside one.
  Other types define their own natural grain the same way.
- Chains compose: zone-of-zones flows top-down along the declared chain, each
  hop with the same semantics; a tenant only ever declares against its direct
  upstream.

**Terminology has a native form.** CodeSystems are among the heaviest-used
objects in the platform (every lookup, `$expand`, `validate-code`, alias
resolution, display translation), so the FHIR resource form — a single huge
JSON document — is the *wire* form, not the working form. It is the job of the
`dbo-fhir-*` personality to convert terminology into a **normalized,
well-usable representation** on ingest (concept-per-row with code, display,
designations, properties, hierarchy — the shape `$lookup`/`$expand`/subsumption
actually query), and to reassemble the resource form on demand. ValueSets are
converted likewise into normalized form *keeping their referenced information*
(compose rules, the CodeSystems they draw from), but conceptually they are
**data carriers, like Bundles** — transport envelopes for a selection of
concepts, not a second terminology store. This dissolves the legacy pain
chain: no `$import` workaround (native bulk load into concept rows), no
65535-parameter cap, no 37k-concept JSON documents round-tripping through the
payload column. (This is a deliberate, personality-declared exception to §2's
payload-is-truth rule: for terminology the normalized form is authoritative
and the resource form is a projection — the inverse of ordinary resources.)

## 10. The feed primitive — pagination and synchronization unified

Pagination is not a search feature; it is a special case of a more general
primitive that also underlies cloud↔edge synchronization, subscription
delivery and the zone→tenant content streams (§6). DBO defines it once.

**A feed is an ordered, replayable sequence with an opaque, durable cursor.**
The one contract: `(source, cursor) → bounded chunk + next cursor`. Everything
else is a choice of source and transport:

| Source | Ordering | Serves |
|---|---|---|
| Search result set | keyset over envelope sort keys + id tiebreak | FHIR search pagination |
| History | version sequence | `_history`, diff/tracing views (§8) |
| Outbox | commit sequence per tenant/domain | subscriptions, edge sync, §6 content streams, CDC |

- **Cursors are keyset positions, never offsets.** Opaque to the consumer,
  stable under concurrent writes — the duplicate-window problem that Medplum's
  offset paging forced onto ~100 jengu call sites (dedupe on type+id,
  defensive page cursors) is designed out, not worked around.
- **Pull and push are transports over the same cursor semantics.** Pull: the
  consumer requests the next chunk (HTTP paging). Push: the producer streams
  chunks over WS and the consumer's **ack carries the cursor** — which is
  exactly the shape jengu's edge sync already converged on (ascending
  `_lastUpdated` cursor, wipe gated on the push-confirmed cursor). A dropped
  connection resumes from the last acked cursor; at-least-once delivery +
  idempotent apply (identity + version) is the delivery contract.
- **The FHIR projections ride on top.** `Bundle.link[next]` encodes the opaque
  cursor (FHIR permits fully opaque continuation links); history bundles and
  R5/R6 topic-based subscription notification bundles are framings of the same
  chunks. For pure streaming the base shape is leaner than FHIR: framed
  objects with batched acks, no per-page Bundle envelope — the Bundle, like
  the ValueSet (§6), is a **wire form**, assembled at the FHIR surface only
  when a FHIR client is on the other end. Edge↔cloud sync between two
  DBO-speaking parties uses the lean frames.
- **One consumer-state model.** Durable named consumers (edge device, zone
  content dependency, rest-hook subscription, migration sweep) each hold a
  cursor in the store; progress, lag and replay are uniformly observable —
  and the §8 process map can show any consumer's position the same way.

Search pagination gains one honest caveat: a keyset cursor into a *search
result* is stable only relative to its sort keys; a resource updated after
the cursor passed it will not reappear in the remaining pages. That is the
correct semantic for paging (and what clients already assume); consumers that
need to *never miss an update* are outbox consumers by definition — the model
makes reaching for the right feed a type decision instead of a folklore rule.


### Implementation note: gap-free outbox reads (dbo#5)

`seq > cursor` alone is not a safe outbox read: sequence values are assigned
at insert but transactions commit in any order, so a slow transaction's rows
become visible *behind* a reader that already passed their position — silently
skipped events. DBO's outbox therefore records the writing transaction id
(`xact_id xid8 DEFAULT pg_current_xact_id()`), and readers deliver only rows
with `xact_id < pg_snapshot_xmin(pg_current_snapshot())`: every transaction
below the snapshot's xmin has finished, so any still-invisible row must sort
*after* the reader's frontier. Delivery is gap-free and in commit order with
no extra coordination — the barrier is one predicate.
