# Identity rules per artifact type (§12)

Every mechanism that must recognize "the same object again" — conditional
writes, sync idempotency, shadowing, converters, import — needs one answer per
type, declared, not folklore. This section defines the identity model.

## The three identity classes

Each personality declares exactly one **primary identity class** per type:

| Class | Identity is… | Typical types |
|---|---|---|
| **CANONICAL** | the canonical `url` | CodeSystem, ValueSet, ConceptMap, StructureDefinition, SearchParameter, PlanDefinition, ActivityDefinition, Questionnaire |
| **IDENTIFIER** | designated `{system, value}` identifier(s) | Patient, Practitioner, Organization, Device, Location, ServiceRequest (placer/filler), DiagnosticReport (accession) |
| **INTERNAL** | the store-assigned id only | Observation without business identifier, Provenance, most workflow by-products |

Synthetic identifiers are allowed and encouraged where a type is logically
identified but FHIR gives no field: e.g. an audit event's stable event id
rides as a designated identifier/tag system (jengu already does this for
idempotent audit flush). `Binary` uses its **content hash** as identity —
which gives blob dedup for free.

## Orthogonal version axes — none of them are identity

Identity **excludes** all four version axes:

1. instance version (`versionId` / ETag — history),
2. business version (canonical `version` field — a *qualifier*, below),
3. FHIR version (R4/R5/R6 personality),
4. tenant object shape (jengu-style shape stamp).

The same logical artifact expressed in R4-shape-2 and R5-shape-3 has the
**same identity**. Consequence, stated as an invariant: **conversion never
changes identity** — converter chains must preserve `url` and designated
identifiers bit-exact, and the engine verifies this after every conversion.

## Class-specific rules

**CANONICAL.** Base identity = `url`. Business `version` is a resolution
qualifier: a plain canonical reference resolves to the resolution winner for
that `url`; a version-qualified reference (`url|version`) resolves to that
exact business version. Uniqueness: (tenant, url, version); at most one
resolution winner per (tenant, url).

**IDENTIFIER.** The personality (with zone configuration) designates which
identifier systems are *identity-bearing* per type, in trust order — e.g.
Patient: national eID before MRN; Device: EUI/serial; Organization: the jengu
code system. Non-designated identifiers are searchable but never merge
identity. Uniqueness: (tenant, type, system, value) over identity-bearing
systems. Two objects claiming the same identity-bearing identifier is a
**conflict surfaced to the owner** (MPI-class resolution), never an implicit
merge.

**INTERNAL.** No cross-store identity; import into another tenant assigns a
new id (provenance keeps the source id). These objects can never shadow and
never dedupe across tenants.

## Where each mechanism binds

- **Conditional writes** (`If-None-Exist`, conditional update): the criteria
  must express the type's primary identity — url for CANONICAL, an
  identity-bearing identifier for IDENTIFIER. The store rejects conditional
  writes keyed on non-identity fields (that is a search, not an identity
  claim).
- **Sync idempotent apply** (§10): dedupe on identity + source instance
  version.
- **Shadowing** (§6): matches on **base identity, version-neutrally** — a
  local CANONICAL object with the same `url` shadows the streamed copy for
  plain-reference resolution regardless of business version or the FHIR
  version either side is expressed in. Version-qualified references pick the
  exact business version, local first, then streamed.
- **Export/import** (§11): the idempotent state export is keyed on primary
  identity, which is what makes re-import a no-op and cross-store import a
  migration.
- **Terminology native form** (§6): a concept's identity is
  (CodeSystem `url`, `code`) — designations and properties version underneath
  it.

Sibling (non-FHIR) models declare the same way: every type in any personality
carries one of the three classes. There is no fourth class and no undeclared
type — the personality contract fails closed at registration if a type has no
identity declaration.
