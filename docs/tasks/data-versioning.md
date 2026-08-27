# Data versioning

**Status** — the store's side is delivered and closed; the consumer's side is
open and still describes a plan written before this existed.

**Issues** — store: [#130](https://github.com/jengu-net/dbo/issues/130) (closed,
with #131–#135, #145) · consumer:
[platform#778](https://github.com/jengu-net/jengu-platform/issues/778) with
[#781](https://github.com/jengu-net/jengu-platform/issues/781),
[#782](https://github.com/jengu-net/jengu-platform/issues/782),
[#783](https://github.com/jengu-net/jengu-platform/issues/783),
[#784](https://github.com/jengu-net/jengu-platform/issues/784) open

**Concepts** — [shape versioning](../arc42-008-crosscutting/shape-versioning.md) ·
[maintenance](../arc42-008-crosscutting/maintenance.md) ·
consumer-side ADR 0047 (still Medplum-shaped; see *Not doing*)

## What this is

Data outlives the shapes it was written under. A record written in 2026 must
still be readable in 2029 when the profile that shaped it has moved twice, and
the store must be able to say which shape wrote it, find everything behind a
version, convert it, and refuse what it cannot honestly read. Without that,
"upgrade" means either a migration ceremony or a silent misreading.

## Where it stands

**The store does its half.** Every accepted object is stamped with the pack
profile versions it was validated under; the stamp is queryable by bound and
countable per profile and version; `reshape` converts stock in place; data
newer than the pack understands is refused rather than served.

**The consumer's half has not started, and its issues are stale.** #778 and
its children were written against Medplum, where none of this existed and
every runner had to be built over the FHIR API. They now describe building
machinery that exists. Before any of that work starts, those issues need
re-basing — that is the next action on this topic, and it is writing, not code.

Concretely, what the consumer now inherits rather than builds: stamp queries
(#782's scanner walk), stock counts before/after (its verification), the
in-place conversion loop with accept-path discipline, and a loud refusal for
too-new data (#783's admission signal, at the store rather than at the door).

## Decisions

**Two axes, never conflated.** `payload_version` is the storage format the
bytes are held in; the shape stamp is the pack profile version an object was
validated under. Conflating them breaks at the first cross-release move —
an R4→R5 conversion changes no shape, and a profile revision changes no bytes.

**The stamp is a fact of the accept event, so it lives beside the payload** —
a column in state *and* history, like `payload_version` and `chain_hash` — not
inside the payload. The payload-borne variant was the first proposal and was
wrong for a reason worth remembering: sync's verbatim dedup compares transport
bytes, so a store-authored stamp inside the bytes makes identical upstream
content compare unequal. Stored bytes stay the author's own claims.

**Ordering is major-prefix, and an unparseable version is refused at accept.**
Only a major is breaking-with-converter under the ratchet, and converters are
keyed per major hop — so the "below N" query and the converter registry share
one rule. A version with no leading integer major would stamp objects no bound
could ever match, discovered mid-migration; it is refused when the shape
arrives instead.

**Converting is a face capability, not an engine feature.** Whether a model can
express its own converters *as data* is a fact about the model. FHIR can, so
the FHIR face runs pack-shipped StructureMaps in process. A model that cannot
uses the hand-back lane. The engine owns the loop — paging, rate bounds,
cursor, accounting, re-accept — for every model alike.

**A converter declares its hop as a versioned canonical** (`<canonical>|2.0.0`
→ `|3.0.0`), because a breaking shape keeps its canonical and bumps its
version. That is FHIR's own spelling, and it means a converted object still
claims the profile it always claimed: conversion moves an object's shape, never
its identity.

**Too-new data is refused at the serving seam, not at ingress.** The accept
path strips and re-stamps, so an authored write cannot carry a newer stamp; the
paths that can — sync apply, restore — bypass the face entirely and must not
grow pack knowledge. Every arrival path converges at serving, so one rule
covers all of them including lanes not yet invented. The honest consequence:
**too-new data can be stored; it cannot be read.**

**A stamp outlives the pack that made it.** Withdrawing or re-numbering a
version leaves stock stamped with it findable, countable and convertible. Only
a pack declaring an *older* version than the stamp is a conflict.

**No lease on the hand-back lane.** The version check on the way back is the
only guard needed, so an abandoned claim strands nothing and a duplicated one
converges. A lease would buy duplicate-work avoidance and pay with state that
can leak and expire wrongly.

## Traps

**Position eight is not one thing.** Adding a subselect column shifted
`sort_key` and poisoned every keyset cursor — thirteen paging tests, none of
them in the suite being run at the time. Read result columns by label.

**A search that streams cannot refuse mid-document.** The too-new guard first
ran per member while the bundle was already being written, so a refusal arrived
as a `200` with a truncated body — the exact half-answer it exists to prevent.
Guard the whole page before the first byte.

**A published extension's differential is litigated by the validator.** A
hand-authored slicing differential on the stamp extension made every echo-PUT
fail. Keep such definitions minimal, as `DboOriginalContent` already did.

**Converters are tenant content, not version content.** The first wiring held
the shared context and would have answered "no converter" about maps the tenant
was holding. Anything resolved from the pack is tenant-scoped.

**The rewrite must go through accept, not `ObjectStore.put`.** Writing to the
engine stored converted bytes carrying the *old* stamp — converted data
claiming to be old, which is worse than unconverted data telling the truth.

## Not doing

**Version-ahead** (announce → flip: writing at a storage-target shape while
serving the operating one, down-converting on read). The registry keeps room
for the round-trip property it needs and nothing else depends on it. It becomes
an issue when a consumer's release cadence actually calls for it. The same
judgement split the hand-back lane out of #133, and that slice then came in at
a third of its estimate — the deferral was right twice.

**ADR 0047 has not been revised.** It is still *Proposed* and Medplum-shaped,
and it is the consumer's decision record, not the store's. Revising it is part
of the consumer-side re-basing, not a store task.

## Verifying

```bash
./gradlew :core:harness:test --tests '*ShapeStampIT' --tests '*ReshapeIT' --tests '*NewerDataRefusedIT'
```
