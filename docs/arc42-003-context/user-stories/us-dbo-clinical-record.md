# US-DBO-CLINICAL-RECORD — care is recorded, found again, and accounted for

> The clinic Ines opened in [US-DBO-TENANT-OPENING](us-dbo-tenant-opening.md)
> is running, and Maarja is seeing patients in it. This is the store doing
> the thing it exists for.
>
> Liis Tamm arrives twice in a fortnight and is one patient both times,
> because the identifier her clinic knows her by decides that and nothing
> guesses. Her visit arrives as one document and lands whole. A severity
> code means what this clinic's own terminology says it means, and a code
> system the clinic never loaded is *unresolvable* rather than *invalid* —
> which is the difference between "our content is incomplete" and "your
> data is wrong". Everything Maarja did is in a trail nobody can edit,
> including her.
>
> None of this is clinical logic. The store does not know what a
> temperature is for. What it knows is what a record is, what makes two
> writes one patient, and what it is not allowed to quietly forget.

## The scene

Ines has wired her application's EMR module to the clinic's surface with a
service credential. What she is testing is the set of things she would
otherwise have had to build: identity resolution, atomic visits, a
terminology server, an audit trail, and a change feed for everything
downstream.

## One patient, however many times she arrives

Liis is written once and reads back as what was written. Not as something
reassembled from indexed columns — her birth date comes back although nothing
searches on it, because the payload is the record and every projection is
derived from it.

She arrives again a fortnight later and her clinic's system writes her again,
conditionally: *create her unless somebody with this identifier already
exists*. The store answers 200 rather than 201 and hands back the record she
already has. A different Liis Tamm, same name, different national identifier,
is a different patient — identity here is the declared identifier, never a
resemblance the store decided on by itself.

When her birth date turns out to be wrong by a day, correcting it keeps the
version that was wrong. What the record said last year is a fact about last
year, and a store that overwrites it cannot answer questions about what
somebody knew when they decided something.

## A visit lands whole, or not at all

The visit arrives as one document: an encounter and an observation, both
naming Liis by the identifier the sending system knows rather than by an id
only this store has ever seen.

That question — *whoever has this identifier* — is answered once, at write
time, and stored as a concrete reference. A record that kept the question
would mean something different every time it was read.

When a second visit names somebody nobody has, the whole document is refused
and **nothing** from it is in the store, including the entry that would have
been fine on its own. A batch makes no such promise and says so per entry,
which is the point of there being two words.

## What a code means here

The clinic loads a small severity code system of its own. From then on the
clinic answers `$lookup` for it — from its own concepts, not from a shared
terminology server somewhere that may or may not be reachable this afternoon.

A system the clinic never loaded is refused as unresolvable. That is
deliberately not the same answer as invalid: one says this store's content is
incomplete and the other says the caller's data is wrong, and a caller who
cannot tell them apart cannot act on either.

## Finding it again

The store publishes what it can actually search, and refuses a parameter it
does not implement rather than ignoring it. An ignored parameter answers a
narrower question than the one that was asked, and returns a plausible page
of wrong results.

Liis is found by the identifier she was written under, and the observation
from her visit is reachable from her, because the reference the store
resolved at write time is a real edge.

## And accounting for all of it

Every one of those acts is in the trail, attributed to the credential the
authority validated rather than to the machinery's own name. Updating an
entry is refused — including to whoever wrote it. A trail its author can
edit is a record of what somebody was willing to leave, not of what happened.

Underneath, one change feed carries every one of those writes exactly once. A
named consumer resumes from where it stopped rather than from the beginning,
which is what lets a report, a subscription and an appliance all watch the
same tenant without three mechanisms.

## Joins

The promises this story rests on, projected from the catalogue rather than
written here: a story claims no evidence, and a leg is what its promise's own
citations say it is.

<!-- story:begin — generated from the promise catalogue; do not edit. Regenerate: ./gradlew :core:harness:promiseProjection -->

| Promise | Says | Status |
|---|---|---|
| `REQ-DBO-CORE-PAYLOAD-IS-TRUTH` | A stored object's payload is the single source of truth; every searchable projection is derived from it and can always be rebuilt. | PROVEN |
| `REQ-DBO-CORE-DECLARED-TRUTH-FORM` | Which representation is authoritative for a type (payload or normalized form) is declared by its personality, never implicit. | PROVEN |
| `REQ-DBO-CORE-DECLARED-IDENTITY` | Every type in every personality declares exactly one primary identity class — canonical url, designated identifiers, or internal — and the contract fails closed at registration without it. | PROVEN |
| `REQ-DBO-CORE-EXTERNAL-IDENTIFIERS` | Every object has one internal id and any number of `{system, value}` identifiers, rebuilt from the payload on each write and searchable together. | PROVEN |
| `REQ-DBO-CORE-NO-IMPLICIT-MERGE` | Two PEOPLE claiming the same identity-bearing identifier are a conflict surfaced to the owner, never an implicit merge. One human is spoken about by several records — a Person and a Patient sharing a national number are that human twice, not two of them — so what is refused is a second record of a type the tenant declared identified by that system, where the value IS the record's identity, and a link that would join two people each holding identity claims of their own. | PROVEN |
| `REQ-DBO-CORE-CONDITIONAL-UPSERT` | A write may be addressed by identity rather than by id: `PUT [type]?identifier=…` or `?url=…` creates the resource when absent and replaces it when present, standalone and inside a bundle. Configuration that must match a source can therefore be expressed as itself, rather than as a create that silently does nothing when the record already exists. | PROVEN |
| `REQ-DBO-CORE-IDENTITY-KEYED-CONDITIONALS` | Conditional writes are accepted only when keyed on the type's primary identity; a conditional write on any other criterion is rejected. | PROVEN |
| `REQ-DBO-CORE-READ-YOUR-WRITES` | A write returns only after its data and its change event are committed in one transaction. (D1) | PROVEN |
| `REQ-DBO-CORE-VERSIONED-HISTORY` | Every write appends an immutable version; version-aware reads and optimistic concurrency (ETag) are first-class. | PROVEN |
| `REQ-DBO-CORE-PARAMETERIZED-SQL` | No value is ever concatenated into SQL text. (D2) | PROVEN |
| `REQ-DBO-CORE-SIBLING-MODELS` | Non-FHIR object models ride the same engine as FHIR resources, not beside it. (R6) | PROVEN |
| `REQ-DBO-CORE-ATOMIC-TRANSACTION-BUNDLE` | A transaction bundle lands whole or not at all: every entry validated before anything is written, all writes in one engine transaction with data, history and outbox together, and entries may reference each other by `urn:uuid` — resolved to the allocated ids, never stored dangling. What a transaction does not serve is refused by name with nothing applied. | PROVEN |
| `REQ-DBO-CORE-CONDITIONAL-REFERENCES` | A reference may be a question — `Type?identifier=system\|value` — and it is answered when the document is written: exactly one match becomes the concrete reference, none or several refuse the write naming the question. Inside a transaction, the entries' own claimed identities answer before the store: a reference to an identity exactly one entry claims resolves to that entry, wherever it sits in the document — a hierarchy authored as one document lands whole. The question may ask only by the identity its type is claimed under, so what a write means does not depend on what else happens to match today, and no unanswered question — one neither the document nor the store answers — is ever stored. | PROVEN |
| `REQ-DBO-CORE-REFERENCE-EDGES` | References between objects are extracted as owned edges on write and power referential reads. | PROVEN |
| `REQ-DBO-CORE-BATCH-ANSWERS-PER-ENTRY` | A batch bundle applies each entry independently through the same path the standalone request takes, and answers one response entry per request entry, in order, each with its own status — a failing entry says nothing about its neighbours, and the statuses are the ones the standalone requests would have answered. | PROVEN |
| `REQ-DBO-VER-VERSION-AGNOSTIC-CORE` | The engine has no knowledge of any FHIR version; all version meaning lives in personality bundles. (R6, §1) | PROVEN |
| `REQ-DBO-VER-PERSONALITY-OWNS-MEANING` | Parsing, validation, search-parameter extraction and subscription evaluation are personality responsibilities, per version. | PROVEN |
| `REQ-DBO-VER-SPECIFIED-VALIDATION` | Profile-resolution and validation semantics are specified by DBO — a malformed or versioned canonical reference can never silently disable validation. | PROVEN |
| `REQ-DBO-VER-VALIDATION-WITHOUT-WRITING` | A caller can ask whether a resource would be accepted without writing it (`[Type]/$validate`), and the answer is the write path's own: what it accepts a write accepts, what it rejects a write rejects. Issues carry the locations a refusal carries, so a caller is told what to fix. The verdict is the resource's shape — state a write settles (an identity already claimed, a version moved on) is not promised. | PROVEN |
| `REQ-DBO-VER-ONE-READ-PER-REQUEST` | Accepting a write reads its payload once, however many parts of the write ask about it — the type, the verdict and the searchable envelope come from one read. A payload rewritten on its way into the engine is read as it now stands, so what is indexed is what is stored. | PROVEN |
| `REQ-DBO-TERM-NATIVE-FORM` | Terminology lives in a normalized, query-optimized form; the FHIR resource form is a wire projection assembled on demand. | PROVEN |
| `REQ-DBO-TERM-BULK-LOAD` | Loading a large CodeSystem is a native bulk operation — no chunking workarounds, no parameter-cap ceilings. | PROVEN |
| `REQ-DBO-TERM-EVERY-TENANT-ANSWERS` | Every served tenant answers `$lookup`, `$expand` and `$validate-code` from its own store's native form, whichever FHIR version it speaks; no tenant is a second-class reader. A terminology write reaches that form rather than being stored whole — a resource that is present and answers nothing is worse than one that is absent. | PROVEN |
| `REQ-DBO-TERM-OPERATIONS-FROM-NATIVE-FORM` | `$expand`, `$lookup` and `validate-code` are served from the normalized form at tenant-local speed. | PROVEN |
| `REQ-DBO-TERM-VALIDATION-USES-TENANT-TERMINOLOGY` | Validation resolves coded values against the tenant's own terminology where the carried definitions are silent: a code from a system the tenant holds either exists in it or the write is refused, value-set membership respects the binding's declared strength, and a system nobody holds is reported as unresolvable — a coverage fact, never an invalidity. | PROVEN |
| `REQ-DBO-VAL-BINDING-STRENGTH-IS-THE-ANSWER` | A coded value is checked against the terminology the store holds, and the answer follows the binding's strength: required violated is a refusal, weaker bindings are advice a caller is given rather than refused for, and everything the face had to say reaches the outcome rather than only what would refuse. | PROVEN |
| `REQ-DBO-VAL-UNRESOLVABLE-IS-NOT-INVALID` | A code from a system the store does not hold is reported as unresolvable, never as invalid: one says this store's content is incomplete and the other says the caller's data is wrong, and they are fixed by different people. | PROVEN |
| `REQ-DBO-SRCH-TIER1-PARITY` | Every search feature a production healthcare platform actually issues works identically ([inventory](../../evidence/search-usage-inventory.md)). | PROVEN |
| `REQ-DBO-SRCH-STRICT-BY-DEFAULT` | An unsupported search parameter is rejected, never silently ignored. | PROVEN |
| `REQ-DBO-SRCH-HONEST-CAPABILITY` | The CapabilityStatement is generated from what the server actually serves — the configured types, the interactions their declared handling permits, the conditional writes their identity class allows, the history their durability keeps, the search parameters accepted, and the operations registered by the facades that were wired. An operation is declared because it is routable: the router and the statement read one list, so neither a served-but-undeclared operation nor a declared-but-unanswered one is expressible. | PROVEN |
| `REQ-DBO-SRCH-TYPED-ORDERING` | Sorting and range filtering are typed — numeric, date and token semantics are correct, with matching indexes. (D3) | PROVEN |
| `REQ-DBO-SRCH-DECLARED-INDEXES` | Indexing (including side tables for hard parameters) is declared by the personality as part of its search contract, from day one. | PROVEN |
| `REQ-DBO-SRCH-CUSTOM-PARAMETERS` | A tenant or module can register a custom search parameter; extraction, reindex and the new index follow automatically — over the rows already stored as well as the ones that come after, because a parameter that answered only about the latter would omit the tenant's history while looking healthy. An expression the store cannot evaluate is refused at the write, where somebody is present to fix it, and nothing is advertised or accepted until the reindex behind it has finished. | PROVEN |
| `REQ-DBO-POL-DECLARED-AT-CONFIGURATION` | Audit level and write discipline are declared in the tenant's configuration next to its FHIR version, validated at registration, and visible in the capability statement. | PROVEN |
| `REQ-DBO-POL-AUDIT-AS-RECORDS` | Audit entries are regular, pseudonymous records in the tenant's own store — feed-visible, exported and restored with the tenant, re-identifiable only through the vault. | PROVEN |
| `REQ-DBO-POL-ACTOR-FROM-AUTHORITY` | Every audit entry names its actor from the tenant authority's token (client and subject) — no anonymous mutations under any audited policy. | PROVEN |
| `REQ-DBO-POL-APPEND-ONLY-DISCIPLINE` | Under append-only discipline the engine rejects tombstones (and per-type in-place updates where declared); correction is supersession or entered-in-error, never removal. | PROVEN |
| `REQ-DBO-POL-AUDIT-UNCONDITIONALLY-APPEND-ONLY` | Audit entries are exempt from the tenant's write discipline: no update, no tombstone under any policy; retention's sweep is the only removal. | PROVEN |
| `REQ-DBO-POL-CUSTOM-AUDIT-EVENTS` | Applications contribute business-level audit events; the machinery stamps actor and time from the validated token and its own clock, overriding caller claims — the trail can be enriched, never impersonated or backdated. | PROVEN |
| `REQ-DBO-POL-FHIR-AUDIT-PROJECTION` | On a FHIR tenant the audit stream is served as AuditEvent — native records as the truth form, rendered per personality on read, contribution via mapped POST; write access is scope-gated. | PROVEN |
| `REQ-DBO-POL-DECLARATIVE-RETENTION` | Retention is declared per tenant and type as a floor and a ceiling — keepAtLeast (append-only holds even against policy) and removeAfter (the engine must remove) — composing with write discipline without conflict. | PROVEN |
| `REQ-DBO-POL-RETENTION-SWEEP` | A durable scheduled sweep executes removal as the one sanctioned mutation of history, and every removal is audited without retaining the removed data. | PROVEN |
| `REQ-DBO-FEED-ONE-PRIMITIVE` | Pagination, subscription delivery, content streams and edge sync are all the same primitive: an ordered, replayable sequence with an opaque durable cursor. | PROVEN |
| `REQ-DBO-FEED-KEYSET-CURSORS` | Cursors are keyset positions, never offsets; a page is stable under concurrent writes. | PROVEN |
| `REQ-DBO-FEED-NAMED-CONSUMERS` | Every durable consumer holds a named cursor in the store; progress, lag and replay are uniformly observable. | PROVEN |
| `REQ-DBO-EVT-TRANSACTIONAL-OUTBOX` | Every change event originates as an outbox row committed with the write. (R8, §6) | PROVEN |
| `REQ-DBO-EVT-FHIR-SUBSCRIPTIONS` | Topic-based FHIR Subscriptions (R5/R6 style, backported to the R4 personality) are a core capability. (R8) | PROVEN |
| `REQ-DBO-EVT-DURABLE-DELIVERY` | Subscription delivery is durable, tenant-scoped and replayable, with retries, backoff and dead-lettering. (R8, §9) | PROVEN |
| `REQ-DBO-EVT-IN-PROCESS-SURFACE` | Co-located consumers get the same topics with identical semantics through the in-process/OSGi surface. (R8) | PROVEN |

Coverage: {PROVEN=49} — a leg marked PLANNED cites a promise that exists and is not yet cited by any test.
<!-- story:end -->

## What the store cannot do yet

- **Subscriptions are declared and unreachable.** This story leans on four
  eventing promises. The engine behind them is complete and tested, and
  nothing in a running container constructs it, so no tenant has ever
  delivered a notification. That is a topic of its own
  ([eventing is unreachable](https://github.com/jengu-net/dbo/blob/main/docs/tasks/eventing-is-unreachable.md)), and
  until it lands the joins table below overstates this leg.
- **Search is tier 1.** Typed per-parameter partitions, and everything that
  needs them, are specified and not built. The store refuses what it cannot
  do, so the gap is visible rather than silent, but it is a gap.
- **No blob storage.** Binary content has nowhere to go that is not the
  payload itself.

## Open decisions

- **Whether an ignored search parameter should ever be allowed**, per tenant,
  for a client that cannot be changed. Strictness is right and it is also the
  thing most likely to be asked for by somebody migrating.
- **What a batch should do with an entry that fails validation rather than
  conflict.** Today both are per-entry answers; whether they should be is not
  settled.
