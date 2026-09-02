# The FHIR face

*Everything this store does, as a FHIR client sees it. Written to be readable on
its own: if you know FHIR and nothing about this store, start here.*

## What you are talking to

Point a FHIR client at a tenant and you get a FHIR server: resources, search,
history, `CapabilityStatement`, `OperationOutcome`, Subscriptions, the
operations you expect.

Underneath, it is not a FHIR server. It is a store for regulated data, and FHIR
is a **face** configured over it — one of several possible, and the reason the
same deployment can serve R4 to one tenant and R5 to another without a fork.
You do not need to care, with two exceptions worth knowing up front:

- **Some things the store does have no FHIR expression at all**, and are absent
  rather than hidden. They are listed at the end.
- **Some things arrive in FHIR's extension points** because FHIR has no field
  for them. They are `urn:dbo:*` and they are documented, not private.

Each section below shows what you see, then links to the concept behind it.

## Resources, versions and history

Ordinary FHIR. `Resource.id`, `Meta.versionId`, `_history`, ETags and
conditional requests behave as you expect. History is append-only: nothing
rewrites a past version.

Four engine facts arrive in `Meta`, which is the most stable corner of the
specification — R4 through R6 define the same elements:

```json
"meta": {
  "versionId": "3",
  "profile":  ["http://example.org/StructureDefinition/lab-report"],
  "extension": [
    { "url": "urn:dbo:shape",    "valueString": "lab-report|2.1.0" },
    { "url": "urn:dbo:handling", "valueCode":   "clinical" },
    { "url": "urn:dbo:sync",     "valueCode":   "streamed" }
  ],
  "source": "https://zone.example/t/ee"
}
```

`meta.profile` says what the record claims. **`urn:dbo:shape` says which version
of that profile it was actually validated against, at the moment it was
accepted** — and that stamp does not change when the profile package is later
upgraded. `urn:dbo:handling` is the classification the store enforces on every
write. `urn:dbo:sync` marks a copy streamed from upstream, and `meta.source`
names where it came from.

One caution: **`meta.profile` is not a storage-format version.** The shape a
record was authored under and the format its bytes are stored in are different
axes, and conflating them breaks at the first R4→R5 move.

→ [Records you can rely on](records-you-can-rely-on.md)

## Identity and conditional writes

`If-None-Exist` and conditional update work, and they resolve against a
**declared** identity rule per type rather than against whatever identifier you
happened to send: a canonical `url` for definitional types, designated
`{system, value}` identifiers for types with real-world identity, and the
server-assigned id for everything else.

A reference may itself be a question, resolved once when the document is
written:

```json
"subject": { "reference": "Patient?identifier=https://ee.example/pid|38001010000" }
```

Exactly one match becomes the pointer. Two matches is a refusal, not a guess —
and two records claiming one identity-bearing identifier is a conflict raised to
the owner, never a silent merge.

→ [Records you can rely on](records-you-can-rely-on.md)

## Search

Standard FHIR search, with one behaviour that differs from many servers and will
be the first thing you notice:

**An unsupported search parameter is rejected, not ignored.**

```json
{ "resourceType": "OperationOutcome",
  "issue": [{ "severity": "error", "code": "not-supported",
              "diagnostics": "search parameter 'foo' is not supported for Observation" }] }
```

The reasoning: an ignored filter returns a superset, and every extra row looks
legitimate. You would rather fix a query than act on rows that should not have
been there.

`CapabilityStatement` is **generated from what is actually routable** — the
types configured, the interactions their handling allows, the parameters
accepted, the operations registered. It cannot drift from behaviour, so it is
worth trusting as your integration contract.

→ [Finding things](finding-things.md)

## Terminology

`$lookup`, `$expand` and `$validate-code` are served by **every** tenant from
its own store, whichever FHIR version it speaks. `CodeSystem` and `ValueSet`
resources are assembled on demand as the wire form; the store holds a
query-optimised form underneath, which is why expansion is fast and why loading
a large code system is a bulk operation rather than a chunking exercise.

Coded values in the resources you write are checked against the terminology the
tenant holds, and the answer follows the binding's strength — a violated
`required` binding refuses the write, weaker bindings return advice. A code
system nobody has loaded is reported as **unresolvable**, which is a coverage
statement about the deployment, not a claim that your data is invalid.

→ [Finding things](finding-things.md)

## Paging, `_history` and Subscriptions

`Bundle.link[relation=next]` carries an **opaque cursor**, which FHIR explicitly
permits. It is a keyset position, not an offset, so paging is stable under
concurrent writes and you do not need the defensive de-duplication offset paging
usually forces on clients.

Subscriptions are R5/R6-style topic-based, backported to the R4 face, with
rest-hook and websocket channels. Delivery is durable and replayable: your
acknowledgement carries the cursor, and an interrupted stream resumes from the
last position you confirmed.

One caveat worth reading twice: **a cursor into a search result is stable only
against that search's sort keys.** A resource updated after the cursor passed it
will not reappear in later pages. If you must never miss an update, subscribe —
that is what the notification stream is for.

→ [Change, and who is listening](change-and-who-is-listening.md)

## Work: `Task`, `PlanDefinition`, `ActivityDefinition`

The store holds work as first-class records. Through this face:

| You see | It is |
|---|---|
| `PlanDefinition` | a process definition, generated from the catalogue |
| `ActivityDefinition` | one step of it |
| `Task` | one run — one attempt at one step |
| child `Task`s under it | the items of a batch run |
| `Task.instantiatesCanonical` | which definition this run is of |
| `Task.owner` | who holds it now — a person, or something that claimed it |
| `Task.businessStatus` | the declared milestone reached: *"validated, 2 of 3"* |
| `Task.focus` | the thing the step acts on |
| `Task.input` | the documents the work is over, in declared order |
| `OperationOutcome` on an item | why that item failed |

**These are read projections.** You cannot drive work by `PUT`ting a `Task` into
a new state — claiming and reporting happen on a separate participation surface
where entitlement is checked and a claim carries a deadline. A face that
accepted a `Task` write would be a second door onto work with none of the rules
behind it.

The definitions are generated, never hand-edited: a hand-written
`PlanDefinition` would be a second definition of a process that already has one.

→ [Processes and work](processes-and-work.md)

## Authentication and scopes

SMART on FHIR, with **one issuer per tenant**:

```
https://<host>/t/<code>/oidc/.well-known/openid-configuration
```

Pin exactly one tenant. A token minted by another fails signature verification
before any claim is read.

Scopes are the SMART grammar — `system/Observation.read`, `user/*.write` — and a
human token carries `fhirUser: Practitioner/<id>`. **Tokens are pseudonymous**:
the subject is a record id, never a name or a national identifier, so a captured
token identifies nobody. Display names come from authorised reads, which are
themselves recorded.

Automated processes acting for a person use token exchange: the subject stays
the person, an `act` claim names the acting client, and scopes narrow.

→ [Who may act](who-may-act.md)

## What you will not find in a `Patient`

Identifying elements — names, contact details, national identifiers — do not
live on the clinical resource. The `Patient` you read is a **pseudonym**, and
identifying data is held separately, encrypted per person, reachable only
through an authorised path.

This is why erasure works without rewriting history: the record stays, the key
is destroyed, and the person is gone from the live store, from history and from
every archive. It also means a bulk export you are given is pseudonymous unless
you were entitled to more.

→ [Data isolation](data-isolation.md)

## `AuditEvent`, and the rules a tenant declared

The audit trail is served as read-mostly `AuditEvent`. You may **contribute**
business-level events by POSTing one — it is mapped, not stored raw, and the
machinery overwrites the actor and timestamp from your validated token and its
own clock. You supply the *what*; you cannot assert the *who* or the *when*.

Actors are pseudonymous, so the trail is exact and still contains no personal
data.

A tenant's declared posture is visible in `/metadata`: what is audited, whether
deletes are accepted, and retention windows. If a tenant runs append-only, a
`DELETE` returns an `OperationOutcome` naming the policy rather than failing
obscurely — correction is by superseding, the FHIR way.

→ [Declared rules](declared-rules.md)

## Export, and archives you are handed

A tenant's portable export is idempotent upserts, NDJSON per type — importable
into a fresh tenant here, or **into any other FHIR server**, which is the
anti-lock-in position stated deliberately.

An archive's attestation renders as a `Provenance` carrying FHIR's `Signature`,
so your own tooling can verify what you were handed without learning this
store's internal formats.

→ [Running it](running-it.md)

## What has no FHIR expression

Absent rather than hidden, and each for a reason:

- **Claiming and reporting work** — poll, claim, checkpoint, release. These are
  the participation surface, not a read projection, and there is no FHIR
  operation for taking work.
- **Entitlement** — what a participant may claim is decided before any face is
  involved. A face that rendered it would be presenting an authorisation
  decision as content.
- **Presence** — whether a participant is answering is derived from how far it
  has read. A `Task` never claims its owner is alive.
- **What sits behind a connector** — instruments and appliances reported by
  whatever can reach them. The engine records them; a version that spells
  connected things as a resource may project them, and this face does not yet.
- **Cursors, planes and pod assignment** — operational machinery with no
  clinical meaning.

## Looking up what we mint

Everything of ours that reaches you can be resolved from the tenant that sent
it. The codes are `CodeSystem`s and the identifier namespaces are
`NamingSystem`s — so meeting `urn:dbo:run` in an `identifier.system` is
answered by `NamingSystem?value=urn:dbo:run`, a search parameter every version
defines. A tenant serves them whether or not it asked for those types, because
"this tenant did not declare `CodeSystem`" is not an answer you can act on.

That is checked rather than promised: a test drives the surfaces you actually
use, collects every `urn:dbo:` it is *handed*, and fails the build if one of
them does not resolve. Collecting from the responses rather than from our own
list is the point — a value that reaches the wire by a path nobody thought
about is exactly the one that would otherwise go unnoticed.

**The `CapabilityStatement` does not list them, and that is deliberate.** It
has no slot for "systems this server mints", so announcing them there would
mean inventing one — and you would have to learn *our* extension in order to
discover that there is nothing else of ours to learn, which is the leak this
whole arrangement exists to close. The flow that actually happens is that you
meet a system in a payload and resolve it, and that is the half which has to
work.

If discovery-before-first-contact is ever wanted, the FHIR-shaped answers are
an implementation guide or `rest.resource.profile` on the carrying types.
Both are real pieces of work rather than a list, and neither has a caller
asking for it today.

## If you are integrating

Four things that most often surprise people, in the order they usually bite:

1. **Strict search.** Parameters you send must be supported. Check
   `CapabilityStatement` first; it is generated and therefore true.
2. **`Patient` is a pseudonym.** Plan for identity to come from an authorised
   path rather than from the resource.
3. **`Task` is read-only here.** Work is driven through participation, not
   through resource writes.
4. **Policy refusals are `OperationOutcome`s naming a policy** — an
   append-only tenant refusing a delete is configuration, not a bug.

→ Concepts index: [docs/README.md](../README.md)
