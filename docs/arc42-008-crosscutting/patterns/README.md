# The patterns

## What this is about

Twenty-four cards, each naming one recurring problem and the arrangement that
solves it. They are written as general design guidance, not as a description
of any one system: a reader who never intends to use this store should still
be able to take a pattern to their own.

Each card states the situation it belongs to, the question it answers, the
forces pulling against each other, the arrangement that resolves them, and
what four different readers get out of it — a regulator, a security officer,
an administrator and the business — because these are decisions all four live
with and usually discuss in different vocabularies.

Where a pattern is an application of something already named in the
literature — Enterprise Integration Patterns, task-based access control,
business artifacts, the transactional outbox — it says so and links to the
source, so a card is a starting point for reading rather than a replacement
for it.

The cards do not cite this store's requirements or link to its internals.
Whether a given pattern is implemented here, and how, is a question for the
rest of the specification.

## The shape of a card

A name and a one-line intent, then *you are*, *the question*, *the forces*,
*therefore*, *what each reader gets*, and *relations*.

Relations are what make the twenty-four a language rather than a list. Each
card names what it **builds on** — the patterns that must already be true
before it makes sense — what it **makes possible**, what it is **composed
of**, and the outside **related work** it descends from.

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
  the system that produced the events, append-only against everyone, the
  operator included.
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

A card is remembered by its glyph before its title, so each one has a tile.
Every glyph is drawn from three marks and no others: an open stroke for the
thing the pattern is about, a solid fill for what the arrangement guarantees,
and a faint dashed outline for what is absent, refused, or merely claimed.

--8<-- "assets/diagrams/pattern-icons.svg"

<p class="diagram-caption">Twenty-four drawings made from three marks, which
is what lets them read as one family rather than as twenty-four pictures.</p>
