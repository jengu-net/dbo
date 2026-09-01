# The engine and its faces (§1)

## This is not a FHIR store

It is a store for regulated data that can be **presented** as a FHIR server by
configuring a face — and does so well enough that the distinction is easy to miss.
Miss it and the mistake follows immediately: FHIR ends up in the engine, and the next
domain needs a fork instead of a face.

This is not a later refinement. It is
[founding requirement R6](../arc42-001-introduction/founding-requirements.md), which
asks for R4, R5, **R6 and future versions concurrently** — *"different tenants, and
even different domains within a tenant, on different versions"* — and for the same
property to keep the engine open to *"sibling models that FHIR does not cover"*, so
that non-FHIR domain objects ride on the same engine rather than beside it. What this
document adds is the mechanics: what the engine requires from a face, and where those
obligations currently live.

The module layout is that claim made structural, which is why it is worth reading as a
statement rather than as packaging:

- `dbo-core` — the engine's own concepts. **No FHIR.**
- `core.face` — what the engine requires from any face. No FHIR either: nothing in it
  names a resource, a version or a domain.
- `dbo-fhir-common` — what every FHIR face shares. **A module of its own, deliberately
  not part of core**, because the store is not only for FHIR.
- `dbo-fhir-element` — the FHIR face itself, over the definitions it carries. One
  implementation for every version: reading, validating, framing, extracting a searchable
  envelope and serving are the same code for R4, R5 and R6, differing only in which
  package of StructureDefinitions and SearchParameters it was given.
- `dbo-fhir-r4`, `dbo-fhir-r5` — what is genuinely a version's own beside that face:
  terminology, whose native form is the version's, and subscriptions, whose criteria
  strings and topics are that version's semantics rather than its model.

The families are open on purpose. R4, R5 and R6 are three; a profile-constrained face
over R4, or a face for a domain with no clinical vocabulary at all, are more of the same
kind of thing.

**Why one implementation can serve versions released years apart.** The element model
reads an instance against StructureDefinitions rather than into generated classes, and a
search parameter is an expression and a type in the definitions rather than a method on a
model. So a version is a pair — a definition package and a version code — and R6 is the
proof: it has no generated Java model in any released HL7 core, and it is served through
the same code as the other two.

**Definitions travel with the face.** They are pinned by version, verified by digest at
build time and embedded in the bundle: bringing a tenant up fetches nothing and needs no
writable cache (REQ-DBO-VER-DEFINITIONS-TRAVEL-WITH-THE-FACE). Carrying a version's
definitions and announcing its face are separate — R4's packages ride in the element
bundle so its own face can serve through it, and announcing them in both places would
register two faces under one code.

**How a version is chosen.** A face bundle announces the version it serves, as a
`FhirVersion` service registered under its code, and the tenant wiring resolves a
tenant's declared version against what is installed. Nothing in the wiring knows which
versions exist: adding one is installing a bundle, and a tenant declaring a version
nothing provides is refused because nothing provides it — told, by name, what this
container does serve. `TenantSpec` requires that a version is *named*, not that it is
one of two.

`FhirVersion` is the runtime's counterpart to `DomainFace`. The engine asks a face for
capabilities; the runtime asks a version for what a bring-up builds — the type
registrations, the outward store facade, the tenant's terminology, the storage domain
and the payload version. Resolving only the face would have left the wiring branching,
because the branches never chose a face: they chose those.

**Where the code still does not keep R6**, stated precisely because a founding
requirement is the thing being missed rather than a preference: one `fhirVersion` per
tenant means **one face per tenant**. R6 asks for different domains *within* a tenant on
different versions; a tenant gets one. Tracked in #38.

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

## Three sets, not one

An obligation belongs to one of three groups, and which group it is in decides how it
is provided and when its absence is an error.

**1. What the domain version defines.** Ancestors, resource shapes, search
parameters, parse and render. FHIR versions these itself, so one face per version is
not an implementation choice — it is the shape of the thing. `FhirFace.of("r4")` being
a constant is correct here, and would be wrong for anything else.

**2. What this store adds.** Declared handling, the native terminology form, custody,
identity claims, the shred ledger. FHIR has no version of these because FHIR does not
have them. Some fit an ancestor slot (handling → `Meta.security`, sync origin →
`Meta.source`, the shape stamp → `Meta.profile`); the rest need **a resource of their
own** — `AuditEvent`, `Provenance`, `Task` — which is why three obligations in the
table are the same shape. Every new engine concept lands in this group, so it is the
one that keeps growing.

**3. What this tenant requires.** A tenant uses a subset, and its spec already says
which: `fhirVersion`, `types`, `pdi`, `policies`, `dependencies`. PDI implies
coarsening; terminology types imply a grain codec; policies imply audit rendering; a
dependency implies both ends of a stream.

That third set is what makes a refusal safe. A face cannot be required to provide
everything — a tenant with no terminology needs no grain codec, and a face without one
is legitimately incomplete rather than broken. **Absence is only an error against a
requirement**, which is exactly why a face declares what it provides rather than
stubbing what it does not.

It is also the missing consumer. `DeclaredFace` exists and nothing in production looks
anything up through it, because the check that would is unwritten: **reconciling a
tenant's requirements against its face's offer at bring-up**. Until that exists, a
tenant whose spec needs something its face lacks comes up fine and fails at first use,
two frames from the cause.

## The obligations, as they actually are

This table is the current state, not the target. Where something sits in the wrong
place, it says so.

| Obligation | What it does | Today |
|---|---|---|
| **coarsening** | how a declared element is made coarser | `core.face.Coarsening`, **declared** via `DeclaredFace` — the reference case |
| **grain codec** | reassemble a stored form for transport, take a transported form apart | `core.face.GrainCodec`, passed explicitly (#31) |
| **ancestor rendering** | the engine's own facts, said in the domain's words — id, version, when, where from, under what shape, under what handling | one body, shared by serving, export and framing: the stored document copied token for token with the slots replaced; still saying two of the six facts |
| **envelope extraction** | identifiers, references, indexable paths | a lambda on `TypeRegistration`, reading through the face's payload codec so a payload is read once |
| **payload codec** | parse and render the wire format | `core.face.Payloads`, **declared** via `DeclaredFace` |
| **document equivalence** | what counts as the same object, so a re-import can skip one | `core.face.DocumentEquivalence`, **declared**; a canonical form over the JSON tree rather than the resource model, so nothing is interpreted and nothing can be lost |
| **framing** | many objects as one document — a page, a history, an export | `core.face.PayloadFraming`, **declared**; a member's payload passes through with only the ancestor slots replaced |
| **query compilation** | the domain's query language to engine criteria | inside the personality |
| **audit rendering** | engine facts as the domain's audit resource | `core.face.RecordProjection`, **declared**; the surface that answers audit queries holds no shape and never learns the name of what it serves |
| **attestation rendering** | an archive's root and signatures as a domain resource | **outside the contract** — `ArchiveProvenance`, a loose static in `dbo-fhir-common` (#34) |
| **run rendering** | an execution record as the domain's work resource | `core.face.RecordProjection`, **declared** — the same capability, and having two consumers is what makes it one |
| **catalogue projection** | process steps as the domain's definition resources | defined in #46 (`PlanDefinition`, `ActivityDefinition`), consumer-side today |
| **identity projection** | a subject identity and its claims as domain resources | does not exist; waits on the identity toolset (#39) |

Three of those — audit, attestation, run — are the same shape: **an engine fact said
in the domain's vocabulary.** Two of them now share one seam, and the third does not.

**Document equivalence went the other way**, and is worth reading beside them: not an
engine fact said in the domain's words, but a *domain question the engine was answering
anyway* — by comparing bytes, which says a resource changed because a tool wrote its
fields in another order. It is now the face's, over the JSON tree rather than the
resource model: the element model writes what a version's definitions describe and drops
what they do not, so canonicalising through it would call two documents equal because it
had thrown away the field they differ in. A tree canonicaliser interprets nothing. Object
keys sort, arrays do not — order is meaningful in FHIR — and a decimal keeps its written
form, because a trailing zero is precision rather than noise.

`RecordProjection` is that seam: a record and whatever belongs to it go in, a document
comes out, and the reverse direction reads what a posted document means without the
engine learning what the document is called. It renders at read and never during the
write that records a run, because a face rendering inside a transaction is re-entrancy,
and two faces over one store could disagree about who wrote what.

**Whether a record projects is not a flag.** A record naming storage domains renders
where the face claims one of them and nowhere else; a record naming none — an audit
entry is about an interaction rather than about a domain's records — is every face's.
Nobody has to remember to set anything, and nobody can set it wrongly. Empty is an
answer, so a run over `identity` renders nowhere rather than producing an empty
document that reads like a run with nothing in it.

**One implementation, three versions.** R4, R5 and R6 are served by one face, so the
projection is written once against carried definitions, and the two places the versions
genuinely differ — R4's `type`/`subtype` against R5's `category`/`code`, and R6 recasting
`Task.focus` as a repeating backbone with a required value — are two lines rather than
two implementations. Each rendered document is validated by the version that produced
it, which is how those differences were found.

## The ancestors: FHIR drew this line first

FHIR's inheritance chain cuts where this split cuts. `Resource` defines what *any*
record has irrespective of what it means; `DomainResource` and below define what a
Patient is. Same seam — FHIR draws it with inheritance, this store draws it with
composition, because a non-FHIR face has no ancestors and nine production types have
no FHIR representation at all.

The elements are worth reading as a checklist rather than an analogy, because **the
set has not changed since R4** — R4, R4B, R5 and R6 all define exactly these, and R6
records "no changes" to `Meta`. It is the most stable corner of the specification,
which makes mapping onto it a cheap bet.

| Ancestor element | The engine fact behind it | Said today |
|---|---|---|
| `Resource.id` | the object's id | ✅ |
| `Meta.versionId` | the version | ✅ |
| `Meta.lastUpdated` | when that version was written | ❌ carried beside a read as HTTP metadata, never in the resource |
| `Meta.source` | **where a copy came from** — a streamed object's upstream | ✅ |
| `Meta.profile` | **the shape stamp** the object was written under | ✅ `urn:dbo:shape` in `meta.extension` ([records you can rely on](records-you-can-rely-on.md)) |
| `Meta.security` | **the declared handling class**, which the store enforces on every write | ✅ `urn:dbo:handling` |
| `Meta.tag` | operational labels — streamed origin, shadowing state | ✅ `urn:dbo:sync` |
| `Resource.implicitRules`, `Resource.language` | no engine analogue | face only |
| `DomainResource.text`, `contained`, `extension`, `modifierExtension` | no engine analogue | face only |

Seven engine facts have a standard place to be said. **Six are said** — the id, the
version, custody, handling, the sync labels and the written-under shape stamp — so a
client receives the classification being enforced on the data and the shape version
that wrote it. `Meta.lastUpdated` still rides beside a read as HTTP metadata.

Two cautions before anyone maps them:

- **`Meta.profile` is not the storage-format version.** The shape an object was
  authored under and the format its bytes are stored in are different axes; conflating
  them breaks at the first R4→R5 move. `payload_version` stays internal. The two-axes
  doctrine is [records you can rely on](records-you-can-rely-on.md)'s to state; this is a pointer.
- **The face-only elements are not uninteresting to the engine.** `text` is narrative —
  which is exactly where identifying data hides, so the membrane has a stake in an
  element it does not define. `contained` puts objects inside an object, which
  reference extraction has to survive. Neither becomes an engine concept; both are
  reasons the engine cares what a face does with them.

**And the checklist immediately finds a duplication.** Putting ancestors back onto a
stored payload happens twice in each personality — `toResourceJson` for serving and
`portableRendering` for export — with byte-for-byte the same body: parse, set the id,
set the version, encode. One obligation, two implementations, and **neither writes
`lastUpdated`**; the timestamp a read carries is HTTP metadata beside the resource,
not part of it. That is this contract's whole argument, found by reading FHIR rather
than the code.

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
constant, one face per version, capabilities looked up by type. That fits set 1
exactly and set 2 not at all — a grain codec reads and writes one *tenant's* native
form, and so does audit rendering. Two tenants of the same version need different
instances, so those are passed as arguments instead of being declared.

So the contract cannot express the half of its own table that this store adds, which
is also the half that keeps growing.

The way out follows from the three sets rather than from taste. **A face per version
stays right for what the version defines**; what the store adds needs a per-tenant
layer, because that is what it is. Collapsing both into one per-tenant face would
multiply objects that genuinely are per version; adding a scope to the lookup names a
division the code already has, badly.

**And the first thing to build is the caller, not the mechanism.** The reconciliation
above — a tenant's spec against its face's offer, at bring-up — is what a scoped
lookup is *for*: it is the one place that knows both what this tenant requires and
what its face provides, so it is where the two scopes have to be asked for
differently. Extending the lookup without it would be widening a road nobody drives
on; writing it makes the scope question answer itself.

The payoff is the reason this contract was named in the first place: the day somebody
writes a face for a domain that is not healthcare, the engine tells them what they owe
**before anything serves a request**, instead of after.

## The payload seam

The obligations above that concern a payload — the codec, validation, conversion
between versions — share one shape, and it is large enough to be its own
document: [the payload seam](the-payload-seam.md). The short version is that a
payload crosses as bytes, sets are framed rather than re-serialised, and the
model a face parses into is a lens rather than a commitment.


## Why this matters beyond tidiness

A contract with no name is a contract nobody notices they have broken. The coarsening
function sat in `dbo-pdi` — engine code that knew a birth date is a date — until
`901c602`, and it was written hours after the line it crossed had been drawn. The
audit projection is in the same position today.

The other half is subtraction: a domain that is not healthcare needs a face, not a
fork. Nothing above the engine should learn what FHIR is, and every obligation left
outside the contract is a place where something already has.

**See also:** #38 — the contract's remaining work.
