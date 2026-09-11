# Change, and who is listening

## Four problems that turn out to be one

A store has to answer several questions that look unrelated:

- give me the next page of these results;
- tell me when something I care about changes;
- keep this tenant's copy of shared reference data in step with its source;
- keep an on-site appliance and a cloud converged after a day offline.

Most systems grow four mechanisms for those, each with its own state, its own
failure modes and its own way of being observed. This one defines **a single
primitive** and picks a source and a transport for each case:

> **A feed is an ordered, replayable sequence with an opaque, durable cursor.**
> The whole contract is `(source, cursor) → bounded chunk + next cursor`.

The payoff is not elegance. It is that **every durable consumer is the same kind
of thing** — a name and a position — so progress, lag and replay are observable
the same way for a paging client, a subscriber, a dependent tenant and an
appliance that has been off since Friday. One mechanism means one place to look
when something is behind.

## A change starts as a row committed with the write

Every change event **originates as an outbox row, committed in the same
transaction as the change itself**. There is no separate publish step, and
therefore no window in which the write happened and the notification did not —
the failure that produces a system whose consumers are permanently, subtly
behind reality.

Distribution runs on the store's own database rather than an external broker.
That is a deployment decision as much as an architectural one: a broker is
another thing to run, secure, back up and reason about during an incident, and
the ordering guarantee it would provide is already available where the data is.

## Three sources, one contract

What differs between feeds is only what they are ordered by:

| Source | Ordered by | Serves |
|---|---|---|
| A search result set | the sort keys, with the record id as tiebreak | paging through results |
| History | version sequence | reading how a record changed |
| The outbox | commit sequence | subscriptions, dependent copies, appliance sync |

**Cursors are keyset positions, never offsets.** They are opaque to the consumer
and stable under concurrent writes. This is worth being blunt about, because
offset paging pushes a real cost onto every caller: rows shift while you page,
so callers dedupe on identity, keep defensive page cursors, and carry the
duplicate-window problem in application code — about a hundred call sites of it
in the platform that was measured before this was designed. That work is
designed out rather than worked around.

## Pull and push are transports, not different feeds

**Pull**: a consumer asks for the next chunk. **Push**: a producer streams
chunks and the consumer's **acknowledgement carries the cursor**. Same
semantics, same cursor, same resume: a dropped connection continues from the
last acknowledged position rather than from a guess.

The delivery contract is **at-least-once with idempotent apply** — identity plus
version decides whether an arriving record is new. A consumer will occasionally
see something twice, and that is safe by construction rather than by the
consumer keeping a list of what it has already seen.

Between parties that both speak this store's own protocol, frames are lean.
Assembling a standard's envelope format around each chunk is a **face** concern
and happens only when a client of that standard is on the other end.

## Every consumer is named, and its position is in the store

An appliance, a dependent tenant, a subscription, a migration sweep: each holds
a **named cursor**. So "how far behind is it?" is a query rather than an
investigation, and the same answer serves an operator, a resolution decision and
a monitoring view.

This is the mechanism [presence](../processes-and-work/README.md) is derived from — a
participant is present while its named cursor moves — and it is why that needed
no heartbeat, no lease service and nothing built specially for it.

## Choosing the right feed is a type decision

One honest caveat, stated where people will meet it: a keyset cursor into a
*search result* is stable only relative to its sort keys. A record updated after
the cursor has passed it will not reappear in the remaining pages.

That is the correct semantic for paging, and what clients already assume. But it
means a consumer that must **never miss an update** is an outbox consumer by
definition. Making that a property of which source you chose — rather than a
rule people learn by being burned — is most of the value of having one primitive
with several sources.

## Copies, not reads across stores

Shared reference data published by an upstream tenant is **streamed as read-only
copies into each dependent tenant's own database**, rather than resolved across
tenants at read time.

That choice is forced rather than preferred: **tenants live in different
databases, and indexing has to be local.** Searching, expanding a set, and
validating a coded value all hit local indexes, and there are no cross-database
joins to fall back on. A design that linked tenants instead would work until the
first query that had to filter on the linked content.

Five rules keep that from becoming an uncontrolled copy of everything:

- **Nothing syncs undeclared.** A tenant declares which dependencies it needs
  from which upstream, as ordinary configuration. Tooling may *propose* a
  declaration by noticing a reference to something absent locally, but proposing
  is not syncing.
- **Any type may be declared**, not a privileged list. What differs per type is
  only the grain — what has to travel together to be useful.
- **Conversion happens at apply.** Sender and receiver need not run the same
  version of a standard or the same shape. Something no converter covers
  **dead-letters visibly and degrades the dependency**; it never silently skips,
  because a silently skipped dependency is a store quietly serving less than it
  claims.
- **A local record of the same identity shadows the streamed copy**, across
  versions, and removing the local one falls back to the upstream. That is what
  makes a copy safe to accept: it can always be overridden without being fought.
- **Copies are provenance-tagged and immutable locally**; updates, retirements
  and deletions arrive through the same feed. Audit rides at the level of the
  dependency — established, changed, removed — rather than per copied record,
  because this is shared reference content rather than anybody's personal data.

## The read that has to be gap-free

Worth writing down because it is unobvious and quietly fatal: **reading an
outbox with `sequence > cursor` is not safe.** Sequence numbers are assigned when
a row is inserted, but transactions commit in any order — so a slow
transaction's rows become visible *behind* a reader that has already passed
their position, and those events are skipped with nothing to indicate it.

The outbox therefore records the writing transaction, and readers deliver only
rows whose transaction is below the snapshot's lower bound: every transaction
below that has finished, so anything still invisible must sort *after* the
reader's frontier. Delivery is gap-free and in commit order, and the barrier is
one predicate rather than a coordination protocol.

**The caveat is a delay, never a loss.** That lower bound is cluster-global, so a
long-running transaction anywhere on the same database instance holds feed
delivery back everywhere on it. Tenants on dedicated instances are unaffected by
neighbours; shared deployments should watch for sessions left idle in a
transaction.

## What this costs

**A copy is storage and staleness.** A dependent tenant holds its own copy of
shared content and is briefly behind the source. That is the price of local
indexing, and the alternative was queries that could not filter.

**Somebody has to declare a dependency** before the data arrives. That is
deliberate — undeclared synchronisation is how a store ends up moving data
nobody agreed to move — but it is a real step, and a missing declaration
presents as content that simply is not there.

**A shared database instance couples feed liveness.** One neglected transaction
delays every consumer on that instance.

## Where the detail is written down

- **The exact rules and their proofs** — the feed, eventing and sync entries in
  the [REQ catalogue](../../arc42-006-runtime/req-catalogue.md).
- **What a cursor's movement is used for** beyond delivery — the presence
  section of [processes and work](../processes-and-work/README.md).
- **Why a copy can be trusted to mean what it meant upstream** — the shape stamp
  in [records you can rely on](../records-you-can-rely-on/README.md).
- **Where a standard's envelope formats are assembled** — [the engine and its
  faces](../engine-and-faces/README.md).
