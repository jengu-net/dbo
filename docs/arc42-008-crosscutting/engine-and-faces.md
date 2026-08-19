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
- `dbo-fhir-r4`, `dbo-fhir-r5` — two faces of one family, not two versions of the store.

The families are open on purpose. `fhir-r4` and `fhir-r5` are two; a profile-constrained
face over R4, or a face for a domain with no clinical vocabulary at all, are more of the
same kind of thing.

**Where the code does not keep R6 yet**, stated precisely because a founding
requirement is the thing being missed rather than a preference:

- `TenantSpec` — the engine's own configuration record — carries `String fhirVersion`
  and `List<FhirTypeConfig>`, and **validates the version is exactly `"r4"` or
  `"r5"`**. A custom face cannot be declared: the spec rejects it before any face is
  consulted, and R6 itself would be rejected by the code named after it.
- Four places in `TenantRuntimeManager` branch on that string to choose a personality,
  a storage domain and a payload version, so the set of faces is closed in the wiring
  as well as in the spec.
- One `fhirVersion` per tenant means **one face per tenant**. R6 asks for different
  domains *within* a tenant on different versions; a tenant gets one.

None of that is hard to fix, and none of it is what the engine does wrong internally —
the engine really does hold no version knowledge. It is the *configuration* model that
is FHIR-shaped, which is the last place anybody looks. Tracked in #38.

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
| **ancestor rendering** | the engine's own facts, said in the domain's words — id, version, when, where from, under what shape, under what handling | `core.face.PortableRendering` for export and `toResourceJson` for serving: **two implementations of one body**, saying two of the six facts |
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
| `Meta.source` | **where a copy came from** — a streamed object's upstream | ❌ recorded, never said |
| `Meta.profile` | **the shape stamp** the object was written under | ❌ |
| `Meta.security` | **the declared handling class**, which the store enforces on every write | ❌ |
| `Meta.tag` | operational labels — streamed origin, shadowing state | ❌ |
| `Resource.implicitRules`, `Resource.language` | no engine analogue | face only |
| `DomainResource.text`, `contained`, `extension`, `modifierExtension` | no engine analogue | face only |

Six engine facts have a standard place to be said. **Two are said** — the id and the
version — and four are not, so a client today receives the data but not the
classification the store is enforcing on it. `Meta.security` has existed for exactly
that since R4, and nothing writes it.

Two cautions before anyone maps them:

- **`Meta.profile` is not the storage-format version.** The shape an object was
  authored under and the format its bytes are stored in are different axes; conflating
  them breaks at the first R4→R5 move. `payload_version` stays internal.
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

## The payload seam: bytes at the edge, one parse inside

The obligations above that concern a payload — the codec, validation, conversion
between versions — have one shape, and it is already written down once. `PayloadConverter`
takes a type name, a payload version to hop from and to, and `byte[]`. Nothing in that
signature is FHIR: a type is a string, a version is a string, a payload is bytes. It is
the shape the rest should take, so they are siblings of it rather than a scheme of their
own.

**The coordinates are the face, the type name, and the payload version.** Not
`Meta.profile`: the shape a resource was authored under and the format its bytes are
stored in are different axes, and conflating them breaks at the first R4→R5 move. An
interface that means one of them says which in its name.

**Bytes are the boundary, not the internal contract.** If every operation takes bytes,
the write path parses and discards once per step — deserialize, validate, extract the
envelope, hash — which is more allocation than holding a model, not less, and would make
a change made for efficiency cost efficiency. So a face mints a document handle the
engine passes back to it and never reads:

```java
public interface Payloads<D> {                  // D is the face's own model
    D read(String typeName, byte[] payload);    // parsed once
    List<String> validate(String typeName, D document);
    byte[] write(D document);
}
```

The engine holds `Payloads<?>` and never names `D`. The public API stays bytes, so §7.3
holds — no model type crosses it — while one request parses once. Byte-in, byte-out
convenience sits on top for callers that genuinely have only bytes, the converter chain
on read being the obvious one.

**This is what makes the model a lens rather than a commitment.** Payload is truth and
stored bytes are never rewritten, so a face may parse into whatever represents a
resource best — the HL7 core model, something generated, something that is not FHIR at
all — and a model that round-trips imperfectly still cannot corrupt what is stored,
because what is stored is what arrived. Model choice is therefore reversible in a way it
is not for a store that persists its object graph. That property is worth protecting: it
argues for experimenting freely with representations and against ever letting one become
the persisted form.

**Resources are `byte[]`; streams belong to blobs.** A write is one transaction that
computes an envelope and links the version into a per-object hash chain, all of which
want the whole array — a streaming resource payload would be buffered internally and the
abstraction would be a lie. Blob storage is its own unbuilt front and is where streams
belong, with a contract that admits them honestly.

**Sets are framed, not re-serialised.** A searchset, a history, a transaction and a
portable export are all many resources in one document, and the engine holds their
members as bytes it has already been given. Routing them through the single-resource
codec would parse each member and render it again — N parses to emit one page, and
worse, the bytes that came out would be the model's rendering rather than the ones that
were authored. A face frames instead: it writes the document around members whose
payloads pass through untouched, and takes a document apart into members without reading
them.

```java
public interface PayloadFraming {
    record Member(String typeName, byte[] payload, MemberFacts facts) {}

    /** What a document looks like around its members. */
    record Frame(byte[] prologue, byte[] separator, byte[] epilogue) {}

    Frame frame(String frameType, FrameFacts facts);   // total, self, links: known before iterating
    void member(Member member, OutputStream out);      // wrapper + payload, straight through

    Stream<Member> unframe(InputStream document);      // transaction, batch, import
}
```

`frameType` is the face's word for what kind of document this is; `MemberFacts` is what
the engine knows about a member and the face says in its own vocabulary — which is the
ancestor-rendering obligation above, not a second one.

**The face describes the document; the caller writes it.** Taking a list of members and
returning a document would hold a whole page in memory, which contradicts the reason a set
is streamed at all. Returning a sink for the caller to fill would be better and still
wrong in kind: it is a lifecycle, and something with a lifetime spanning a loop is
something a face could hold a cursor in. So the face says what goes around the members —
a prologue, a separator, an epilogue — and how one member is spelled, and the caller
writes the prologue, then a separator before every member but the first, then the
epilogue. The only state is which member is first, and that is loop bookkeeping rather
than format knowledge: the separator comes from the face, so nothing above learns that
one format wants commas and another wants newlines.

Writing a member to a stream rather than returning its bytes is what keeps the payload
from being copied on its way out — the face writes its wrapper and then the stored bytes
straight through. Memory is one member, not one page, and the bytes a reader receives are
the bytes that were authored.

**Unframing may hand back a sequence, and the difference is the point.** The reason a face
must not pull is that a lazy read over a search is an open cursor inside a transaction, so
a face that pulls decides how long that transaction lives and what becomes of it when the
face throws halfway down a page. Unframing reads a document the caller already has — a
request body, an archive being imported — where there is no cursor and no transaction to
outlive. The rule is not that a face never pulls; it is that a face never reaches into the
store, and saying which keeps the seam honest instead of merely symmetrical.

That also puts enrichment where it has to be. Decrypting an identifying element reads the
vault, and a face must not — so the membrane sits in the engine's loop, between the
converter and the frame, and the face is handed a member that is already what a reader
should see.

**The engine's half of it is an ordinary stream.** A row arrives as bytes, which is the
unit the whole path wants, and each step is a map over it:

```java
Frame frame = face.frame("searchset", facts);
out.write(frame.prologue());
try (Stream<Member> rows = engine.page(query)) {     // cursor-backed; closes with the stream
    rows.map(converters::upgrade)                    // per object, pure
        .map(membrane::reveal)                       // reads the vault, so it is the engine's
        .forEach(separatedBy(frame.separator(), member -> face.member(member, out)));
}
out.write(frame.epilogue());
```

Nothing in that stream belongs to the face — it holds the cursor, the transaction and the
order, and the face is the terminal. Handing the stream over instead of the sink is the
same mistake in a different position, and `java.util.stream` makes it a natural thing to
type. Three hazards come with it and are worth naming: the stream owns a cursor and must
be closed, so try-with-resources is not optional; `.parallel()` is one word away and is
wrong twice over on a cursor in a transaction and on results whose order is a page; and
checked exceptions do not pass through `map`, so how a conversion failure travels is a
decision rather than a discovery. Back-pressure needs no mechanism — the terminal writes
to a blocking output, so the pipeline pulls no faster than the reader drains.

**And the outward facade has to admit it.** `FhirStoreFacade.search` and `historyBundle`
return a `String` today, so a page is assembled whole before a reader sees a byte of it.
Streaming behind a facade that materialises would buy nothing: the streaming form writes
into an output rather than returning a document, which keeps bytes at the edge as §7.3
requires and makes a search page, a portable archive and a stream between tenants one
path rather than three.

**Converters stay per object for the same reason.** A converter is a pure function from
one payload to one payload, which is what lets it compose into this loop, be tested on a
single object, and be reordered. A stream-to-stream converter would add nothing the loop
does not already give and would invite an implementation that carries state between
items, or worse, one that drives the cursor.

Byte-exact members are the point rather than an optimisation. This store's posture is
that a payload is truth, and re-rendering a resource to put it in a page would make the
copy a client reads differ from the copy that was stored, in whitespace and key order at
least, and in whatever a model normalises at worst.

**And a set is where a stream is honest.** The caveat above — that resources are
`byte[]` because a write is one transaction over a whole array — does not extend to
documents made of members. An export of a million resources is a sequence of bounded
things, so framing and unframing may be offered over streams as well as arrays without
the abstraction lying about what it can do. The single resource stays an array; the set
is where the stream belongs, along with blobs.

**Stamping and rendering the ancestors are two acts, and they happen at different
times.** The store's authority statement — these bytes were accepted as version N of
this object, at this time, under this handling — is a claim about custody at a moment,
so it is made at **write** and nowhere else. A read-time stamp would attest what is
being held now rather than what was accepted and kept unchanged since, and the chain
that links a version to the one before it could not exist at all.

Putting the ancestors *into a document a reader receives* is the other act, and it
belongs at **read**, because the alternative is freezing facts into bytes that are never
rewritten. Doing it at read used to mean parsing every member and rendering it again.
It does not have to: a face can stream the stored bytes through, emitting as it reads
and overriding the ancestor slots as they pass. No object graph, one pass, and correct
on a payload that already carries an `id` — replace rather than insert is trivial when
re-emitting a parse, where it is treacherous when editing a string. That is the same
shape as reading a payload's hash without materialising it, and the two belong beside
each other on the face for the same reason: only the structure's owner can walk it
cheaply.

So a reader receives exactly what its author wrote, byte for byte, in every element the
author wrote — and the ancestor slots, which were never theirs. Whether the ancestors
*also* live in the stored bytes stops being a blocker and becomes an optimisation, to
be settled by measuring the read-time pass rather than by argument.

**Two hashes, and only one of them is the face's.** A digest over the stored bytes is
what a history chain needs — it detects a version edited underneath the store, it needs
no parse, and nothing cheaper exists. A hash over what a document *means*, so that two
spellings of one resource agree, is a different function: only the face knows which
differences do not matter, and it is the face that provides it. FHIR fixes element order
and drops whitespace for exactly this, because signatures needed it, so there is a
published rule to implement rather than a convention to invent. One name doing both jobs
would be the mistake — a semantic hash cannot detect tampering it is designed to ignore.

Worth knowing before anyone calls it free: normalising order means a canonical hash
cannot stream purely, because keys are buffered per object level and sorted before
digesting. That is memory proportional to an object's width rather than to the document,
and far less than a model — but it is not nothing, and the claim should survive being
measured.

**A face is a service, and a capability is ordered rather than held.** These functions are
passive: a caller asks the face for one when it needs it, and what stands behind it — a
pooled parser, a thread, a cache — is the providing bundle's business and nobody else's.
The thing to ask is the face itself, registered in the service registry under its code,
with capabilities looked up by type the way `DeclaredFace` was built for. That is also
what turns the closed list of versions into a question with an answer: a tenant declaring
a face the container has no service for is refused because nothing provides it, not
because a validator names two strings.

Three things follow from the provider being free behind the service, and each is a way to
get it wrong.

*A capability is not held across service dynamics.* A personality bundle can be stopped or
updated — the development console does that on every republish — and a field still
holding a capability from the previous revision is an object with a dead classloader
behind it, failing later as something that looks like anything but that. Ask per
operation, or track the service.

*And both of those are worth catching rather than documenting.* A connection pool does not
rely on being told to close connections; it records where one was taken and warns when it
is held too long, with the stack trace of the caller that took it. The same applies here,
in two forms, because the two hazards fail differently.

A cursor-backed stream is an ordinary resource leak: warn when one is held past a
threshold, and — the commoner case — use a {@code Cleaner} to warn when one is collected
having never been closed at all. A dropped stream happens more often than a slow one, and
a timer alone never sees it.

A held capability is not a resource leak but a correctness one, and no timer can see it:
nothing observes that a reference is still out there. What is observable is the moment it
goes stale, so invalidate on unregistration and complain at next use, naming where the
capability was obtained. That fires when the bug becomes real rather than when a clock
guesses, and it needs no threshold to tune.

Two constraints on the warning itself. It may name the code site, the age and the kind of
thing; it may not name what was being read. A search carries `identifier=system|value`,
and a diagnostic that helpfully quoted the request would put in a log the thing §14
encrypts in the payload. And capturing a stack trace is not free, so this is gated and off
by default, in the same shape as the rest of the logging knobs.

*Internal concurrency must not become visible concurrency.* Writing a member goes into the
caller's stream, so whatever a provider does behind the scenes it returns having written,
in the order it was called. The engine owns order and back-pressure because a page is
ordered and the reader is the brake; a provider that parallelised its way to out-of-order
entries would be correct alone and wrong in the pipeline.

*Nothing outlives the call.* A face may use a thread to compute; it may not keep work
running past the call that asked, because then its lifetime stops being the engine's to
reason about. That is the translator-not-an-actor rule in the one form it does not
currently spell out.

**They are version-scoped, which is why they are capabilities.** A codec, a validator and
a converter know what a version defines and nothing about a tenant, so they sit in set 1
and `DeclaredFace` fits them exactly. Envelope extraction does not: it depends on the
declared types, so it stays per tenant. That is the same line drawn twice — the half of a
personality that is per version, and the half that is per tenant.

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
