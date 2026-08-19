# The engine and its faces (§1)

## What the split is

The engine's concepts are **regulatory, not clinical**: object, identity, envelope,
handling, history, change feed, personal-data membrane, tenancy and zone, custody,
terminology, shape governance. Not one of them mentions medicine, and that is the
reason they hold — they are what European regulation asks of any system holding
personal data. Know what you hold, know who may change it, prove what happened,
separate identity, erase on request, let people leave with their data.

A **face** maps one domain's own standards onto those concepts. FHIR is the first
face because healthcare is the first domain, not because this is a FHIR store. Two
faces — R4 and R5 — already run in parallel over one engine and interpret it
differently: `SubscriptionsIT` uses R4 criteria strings and `TopicSubscriptionsIT`
uses R5 topics, over the same outbox. If FHIR were the model, that would need two
stores.

**Mechanism in the core, policy in the face.** The core offers the capability; the
face decides the rule. The test for where something belongs is whether the concept
carries domain meaning: *a birth date reduces to its year* is the engine's; *a birth
date is spelled `Patient.birthDate`* is the face's.

## Two directions, and only one of them was named

A face offers **outward facades** — `FhirStoreFacade`, `TerminologyFacade` — which
are what the world calls. An HTTP server holds one and serves requests through it.

The other direction is what the engine requires **from** a face, and it existed long
before it had a name: scattered across a type registration, a personality, and for a
while a class in the engine that knew a birth date is a date. `DomainFace` is that
contract; `DeclaredFace` is how a face provides it, capability by capability, looked
up by type so that adding one is a line rather than a new interface to implement.

A face declares what it provides. A face with no terminology provides nothing for it
rather than stubbing it — a stub is a lie the engine cannot tell apart from a broken
implementation.

## The obligations, as they actually are

This table is the current state, not the target. Where something sits in the wrong
place, it says so.

| Obligation | What it does | Today |
|---|---|---|
| **coarsening** | how a declared element is made coarser | `core.face.Coarsening`, **declared** via `DeclaredFace` — the reference case |
| **grain codec** | reassemble a stored form for transport, take a transported form apart | `core.face.GrainCodec`, passed explicitly (#31) |
| **portable rendering** | the resource form an export hands a stranger | `core.face.PortableRendering`, passed explicitly |
| **envelope extraction** | identifiers, references, indexable paths | a lambda on `TypeRegistration` |
| **payload codec** | parse and render the wire format | inside the personality |
| **query compilation** | the domain's query language to engine criteria | inside the personality |
| **audit rendering** | engine facts as the domain's audit resource | **misplaced** — `FhirAuditProjection` hand-builds AuditEvent JSON inside `dbo-policy`, an engine module |
| **attestation rendering** | an archive's root and signatures as a domain resource | **outside the contract** — `ArchiveProvenance`, a loose static in `dbo-fhir-common` (#34) |
| **run rendering** | an execution record as the domain's work resource | defined in #46 (`Task`, `OperationOutcome`, `Task.partOf`), not built |
| **catalogue projection** | process steps as the domain's definition resources | defined in #46 / ADR 0057 §5 (`PlanDefinition`, `ActivityDefinition`), platform-side today |
| **identity projection** | a subject identity and its claims as domain resources | does not exist; waits on the identity toolset (#39) |

Three of those — audit, attestation, run — are the same shape: **an engine fact said
in the domain's vocabulary.** They have no common seam, and two of them are sitting
outside the contract entirely. That is the clearest argument that this contract is
worth finishing.

## A face translates; it does not act

Every obligation is a pure transformation: data in, data out. **No face method writes
to the store or re-enters the engine.** Otherwise there is re-entrancy inside a
transaction, and two faces running in parallel can disagree about who wrote what.

This is not a style rule, and #31 showed why. Terminology could have been applied by
handing the received CodeSystem to the face to ingest. But the sync engine writes
copies under the **source's object id**, and its origin bookkeeping — which shadowing
is built on — is keyed by that id. A face that wrote would have resolved identity its
own way, by canonical url, and the two keys would have drifted apart with nothing
failing. So `GrainCodec.receive` writes only the part the engine has no place for and
hands the rest straight back.

## The limit this contract has today

`DeclaredFace` lookup is **version-scoped and stateless**: `FhirFace.of("r4")` is a
constant, one face per version, capabilities looked up by type.

Several obligations are not like that. A grain codec reads and writes **one tenant's**
native form; so does audit rendering; so does portable rendering. Two tenants of the
same version need different instances, so those are passed as arguments instead of
being declared — which means the contract cannot currently express a third of its own
table.

The obligations split cleanly in two, and the split is worth seeing before choosing:

- **Stateless renderings** — coarsening, attestation rendering, run rendering,
  catalogue projection. Declarable today, exactly as coarsening already is.
- **Store-holding obligations** — grain codec, audit rendering, portable rendering,
  identity projection. Not expressible.

Either a face becomes per tenant, or the lookup distinguishes the two scopes. The
first keeps the contract uniform and makes `FhirFace.of` no longer a constant; the
second keeps the constant and adds a scope to the lookup. That decision is #38's, and
nothing else in the table should move before it.

## Why this matters beyond tidiness

A contract with no name is a contract nobody notices they have broken. The coarsening
function sat in `dbo-pdi` — engine code that knew a birth date is a date — until
`901c602`, and it was written hours after the line it crossed had been drawn. The
audit projection is in the same position today.

The other half is subtraction: a domain that is not healthcare needs a face, not a
fork. Nothing above the engine should learn what FHIR is, and every obligation left
outside the contract is a place where something already has.

**See also:** [ADR 0057](https://github.com/jengu-net/jengu-platform/blob/main/docs/arc42-009-architecture-decisions/0057-dbo-is-an-engine-for-regulated-work-and-domains-are-faces-over-it.md)
(the decision this describes), #38 (the contract's remaining work).
