# The patterns

## What this is about

The concepts in this tree are written as arguments: each one starts from a
problem and reasons to what the store does. That is the right form for
somebody deciding whether the design is sound, and the wrong form for
somebody who needs to *refer* to one idea in a meeting with four disciplines
in the room.

So the same material is also given as a **pattern language**. Each pattern
names one idea, states the situation it belongs to, the question it answers
and the arrangement that answers it, in language that does not need an
engineer to decode it. Where a pattern is an application of something already
named in the literature — Enterprise Integration Patterns, task-based access
control, business artifacts — it says so and links to the concept page for the
mechanics.

All twenty-four are written, in six families. The proposal they came from,
and the reading list behind them, is the pattern-language plan — which sits
with the other plans rather than here, because a proposal is not a description
of the store.

## The shape of a page

Every pattern is written the same way: a name and a one-line intent, *you
are*, *the question*, *the forces*, *therefore*, *what each reader gets* —
four lines, for a regulator, for security, for an administrator, for the
business — and *relations*. Module names and protocol names appear only in the
last part.

## Every pattern, and what it rests on

--8<-- "assets/diagrams/the-pattern-map.svg"

<p class="diagram-caption">One pattern rests on nothing, and it is sealed.
Reading upwards from any other gives the things that must already be true
before it can be.</p>

## Foundation

- [**Property, Not Policy**](pattern-property-not-policy.md) — the root. A
  policy is a commitment somebody can break; a property is something the
  system does not permit.
- [**The Tenant Is a Database**](pattern-the-tenant-is-a-database.md) — each
  organisation's records live in a database of their own, so a query that
  forgets the filter has nowhere to go.
- [**A Type Declares What It Is**](pattern-a-type-declares-what-it-is.md) —
  append-only, versioned, audited, retained for how long, identified by what,
  declared once per kind and enforced by the engine.

## Work

- [**Work Is the Reason**](pattern-work-is-the-reason.md) — access is granted
  to a step of a process, and the record of the attempt is the record of the
  reason.
- [**The Run Is a Record**](pattern-the-run-is-a-record.md) — one attempt at
  one step is an ordinary record, so it inherits history, an audit trail, an
  owner, and never having been in flight.
- [**Nobody Is Pushed**](pattern-nobody-is-pushed.md) — the store reaches out
  to nobody. Participants ask what is available to them, take it, and report
  back.
- [**Two Parties Bound the Claim**](pattern-two-parties-bound-the-claim.md) —
  what may be taken is the overlap of what the credential covers and what the
  step admits. Neither alone is enough.
- [**A Role Is a Period on a Record**](pattern-a-role-is-a-period-on-a-record.md)
  — the records saying who works here are the grants, and revoking access is
  ending a period on one of them.
- [**Falls to a Person, and Is Counted**](pattern-falls-to-a-person-and-is-counted.md)
  — work no runner takes goes to a person, and the fall is counted, which is
  the automation backlog stated as a fact.
- [**A Sweep Is Found, Not Started**](pattern-a-sweep-is-found-not-started.md)
  — convergent work is found if an attempt is already open, so asking twice
  does not open two accounts of one erasure.

## Evidence

- [**The Trail Is Records**](pattern-the-trail-is-records.md) — audit lives in
  the tenant's own store, append-only against everyone, with the party running
  the deployment included.
- [**Carrying Is Not Reading**](pattern-carrying-is-not-reading.md) — a hop
  that carried leaves a travel entry, a participant that opened leaves an
  access entry, so the trail can say that nobody looked.
- [**Asked, Not Scanned**](pattern-asked-not-scanned.md) — the store answers
  exactly what was asked or refuses and says what would have worked. It never
  widens a question quietly.

## Change

- [**Committed with the Change**](pattern-committed-with-the-change.md) — the
  event is a row in the same transaction as the change, so there is no window
  in which one happened and the other did not.
- [**One Feed, Every Consumer**](pattern-one-feed-every-consumer.md) — paging,
  subscribing, copying and catching up are one primitive, so every consumer is
  a name and a position.
- [**A Cursor That Holds Still**](pattern-a-cursor-that-holds-still.md) — a
  position names where you stopped, not how far you counted, so an insertion
  above the window costs the caller nothing.

## The person

- [**The Person Is a Key**](pattern-the-person-is-a-key.md) — identifying
  material is sealed where it is written, so every copy downstream carries
  ciphertext by construction.
- [**Erasure Destroys a Key**](pattern-erasure-destroys-a-key.md) — the
  entries stay, complete and in order, and the person evaporates from them.
- [**Leaving Is the Nightly Path**](pattern-leaving-is-the-nightly-path.md) —
  departure is the archive that already runs every night, sealed to the owner
  and verifiable by somebody else.

## Shape and operation

- [**Engine and Faces**](pattern-engine-and-faces.md) — the engine holds the
  regulatory mechanism and no domain; the face holds the standard a tenant
  declares.
- [**Bytes Framed, Not Rebuilt**](pattern-bytes-framed-not-rebuilt.md) — the
  payload as written is the truth, read once and carried rather than
  reconstructed.
- [**A Country Is a Zone**](pattern-a-country-is-a-zone.md) — a jurisdiction's
  facts are declared once for everyone inside it, and a tenant may narrow but
  never widen.
- [**One Declared Set, Applied**](pattern-one-declared-set-applied.md) — one
  declared configuration, applied as a run to a new tenant, an old one and an
  appliance alike.
- [**Status Is a Lifecycle, Not a List**](pattern-status-is-a-lifecycle-not-a-list.md)
  — the tenant worth knowing about is the one missing from the list of served
  tenants, so the whole lifecycle is modelled.

## The icons

A pattern is remembered by its glyph, so each one has a tile. Every glyph is
drawn from three marks and no others: an open amber stroke for the thing the
pattern is about, a solid green fill for what the store guarantees, and a
faint dashed outline for what is absent, refused, or merely claimed.

--8<-- "assets/diagrams/pattern-icons.svg"

<p class="diagram-caption">The sheet is also the preset: Lini has no import,
so a pattern's own figure copies its header from this source.</p>
