# Records you can rely on (§2–§3, §12)

## Why this is one concept

Two organisations that compete will both use a shared store only if they can
rely on what it holds. That reliance is not one property — it is five, at
different levels, and each can fail on its own while the others hold:

1. **The record is what it says it is** — the bytes somebody wrote are what is
   stored, and everything else about them is derived from those bytes.
2. **It is the same record next time** — identity is declared per type, not
   guessed from whatever field looked unique.
3. **It says what shape it claims to be** — and that claim is a recorded fact
   rather than an assumption made at read time.
4. **It cannot be quietly changed** — every version is kept, and a write either
   lands whole or not at all.
5. **It can be rebuilt** — from the payload alone, without anybody's backup of a
   derived thing being on the critical path.

Miss any one and the others stop being worth much. A perfect index over a
payload nobody can reproduce is a rumour; an immutable history of records whose
identity was guessed is an immutable record of a mess.

## The payload is the truth

**A stored object's payload is the single source of truth. Everything
searchable is derived from it and can always be rebuilt.**

That sentence decides an unusual amount. The searchable projection — the
envelope of typed values a query actually runs against — is a *derivation*, so
changing how objects are indexed is a **background operation, never a data
migration**. References between objects are extracted as owned edges when a
record is written, and those edges answer referential reads; they too are
derived, and they too can be rebuilt.

The physical layout makes the split visible. Per storage domain, roughly:

```
<domain>_data        current state: id, type, version, envelope (typed values), payload
<domain>_history     every previous version, append-only
<domain>_identifier  the identity-bearing identifiers, unique
<domain>_reference   extracted edges: owner, target type, target id
<domain>_outbox      the change stream, written in the same transaction
```

Only `payload` is authoritative. The rest is a cache with a rebuild button —
which is what makes an index change a Tuesday afternoon rather than a project.

## A record is identified, and the rule is declared

Every mechanism that has to recognise *the same thing again* — a conditional
write, replication arriving twice, an import, a converter — needs one answer per
type, written down rather than inferred. So each type declares **exactly one
primary identity class**:

| Class | Identity is | Typical of |
|---|---|---|
| **Canonical** | a canonical url | definitions: code systems, value sets, shape and process definitions |
| **Identifier** | designated `{system, value}` identifiers, in trust order | things with real-world identity: a person, an organisation, a device, an order |
| **Internal** | the store-assigned id, and nothing else | records with no business identity: observations of something, provenance, by-products |

Where a type is logically identified but the standard gives it no field, a
synthetic identifier is encouraged rather than tolerated. Content-addressed
types use their own content hash, which gives deduplication for nothing.

**Identity excludes every version axis, and there are four of them**: the
instance version, a business version on a definition, the standard's version,
and the shape stamp below. The same logical artefact expressed under two
different standards' versions is the *same record*. Stated as an invariant:
**conversion never changes identity**, and the engine checks that after every
conversion rather than trusting the converter.

**Two records claiming one identity-bearing identifier is a conflict surfaced
to whoever owns them — never an implicit merge.** This is the rule that stops a
store from inventing facts. A merge is a decision with consequences somebody
must own; a store that performs one silently has decided that two parties'
records were about the same subject, on the strength of a matching string.

## A record says what shape it claims to be

A record can conform to a published profile, and profiles are versioned. The
question that decides everything here is *when* conformance is established.

**It is established at accept, and recorded.** When an object is accepted, the
version of each declared profile it satisfied is written beside the payload as a
fact of that accept event — a **stamp**. It is not re-derived on read, because
re-deriving it would mean the meaning of a stored record changes when a package
is upgraded, which is exactly the property a regulated store cannot have.

What follows from having it as a fact:

- the stamp is **served beside the claim**, so a reader sees both what the record
  claims to conform to and which version it was actually checked against;
- records are **searchable by stamp**, above or below a stated major, so "what do
  I still have on the old shape" is a query rather than a survey;
- the tenant's inventory **counts stock** per type, profile and stamped version —
  including records that declare a profile and carry no stamp at all;
- a mirrored copy **keeps the stamp of the store that validated it**, because the
  fact belongs to the accept event, not to the copy;
- a stamp **outlives its package**: withdrawing or renumbering a version leaves
  the stock stamped with it findable, countable and convertible. A fact about a
  past event does not become untrue when a catalogue is edited.

## It cannot be quietly changed

**Every version is kept.** History is append-only, so the previous state of a
record is not something anybody has to have thought to preserve.

**A write returns only after its data and its change event are committed
together.** One transaction, so a reader who sees the write sees the notification
of it, and nothing downstream is reacting to a state that was rolled back.

**A batch of writes lands whole or not at all** — every entry validated before
anything is written, all of it in one transaction.

**References may be questions, answered at write time.** A reference can be
written as a criterion rather than a pointer; exactly one match becomes the
pointer, and anything else is refused. The ambiguity is resolved once, by the
writer, instead of being re-resolved differently by every later reader.

**No value is ever concatenated into SQL.** Stated as a promise because it is
the kind of thing that is true until one afternoon it is not.

## Shapes move, and records do not have to move with them

Packages get new versions whether or not anybody is ready. Two rules keep that
from becoming an outage.

**A record stamped above what the tenant understands is refused, on every
read** — naming the record, its stamp and what the tenant declares — and that
refusal is **its own answer**, distinguishable from a fault, a permission
problem and a malformed request, so a consumer can gate on it. The alternative
is worse than an error: silently serving a record under a shape the reader
believes it understands.

**Reshape converts stock in place**, and it is an ordinary operation rather than
a migration event:

- each rewrite is a normal versioned write, so history keeps the pre-conversion
  form;
- it is paged and rate-bounded, hands back a cursor with its counts, and a re-run
  finds only what is still behind;
- a record no converter covers is **named and left behind** rather than stranding
  the rest — the run reports it, and the next run tries again;
- conversion done elsewhere claims nothing and locks nothing; the version check
  when the result comes back is the only guard, so an abandoned claim costs
  nothing. What comes back is re-accepted through the face — validated,
  re-stamped, version-checked — and accounted exactly as an in-process conversion
  would be, because a second, gentler door for converted records is how a store
  ends up holding things it never checked.

## It can be rebuilt

Because the payload is authoritative and everything else derived, recovery has
a small surface: **backup is export and restore is import**, through the same
machinery a tenant's ordinary data movement uses, rather than a
physical-format-specific tool that has to be kept in step with the schema.

The derived things — envelopes, edges, indexes — are rebuilt rather than
restored. So a corrupt index is not a data-loss event, and a new index is not a
migration.

## What this costs

**Writes do more work than they would in a document store.** Extracting an
envelope, edges and identifiers on every write, in the same transaction as the
change event, is the price of every read being answerable without opening the
payload.

**Conformance is a fact about the past, so the store accumulates stamped
stock.** Somebody has to run reshape, and stock stamped with a withdrawn version
stays visible until they do — which is the honest state rather than a tidy one.

**Identity has to be declared per type before the type is useful.** There is no
"work it out from the data" mode, and that is deliberate: the mode that guesses
is the mode that merges two people.

## Where the detail is written down

- **The exact rules and their proofs** — the core, shape and validation entries
  in the [REQ catalogue](../arc42-006-runtime/req-catalogue.md).
- **What a face owes the engine**, including where validation actually happens —
  [the engine and its faces](engine-and-faces.md).
- **How these records are rendered** to a reader who speaks a particular
  standard — [work through a FHIR face](work-through-a-fhir-face.md).
- **Moving data in and out**, and reshape as an operation —
  [maintenance](maintenance.md).
