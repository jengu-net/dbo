# The durable layer

One third-party library does the part of this store that is hardest to get
right and least interesting to own: making a piece of work survive the process
that was doing it. What work *is* and how it reaches whoever does it is
[processes and work](README.md); this is what carries it when the carrier is
not a request.

## What it is

`dev.dbos:transact` — DBOS's Java library. It keeps workflow state in Postgres
tables of its own, so a workflow that was half done when a process died is
resumed by whatever picks it up next, and a step already taken is not taken
again. That is the whole of why it is here. The alternative was implementing
the same patterns — Postgres queues, exactly-once steps — natively, which
[the decision record](../../arc42-009-architecture-decisions/001-dbos-runs-inside-a-bundle.md)
kept as the fallback and never needed.

**It is a library, not a service.** There is no DBOS server in any deployment
of this store. What runs is a Java object in a process, pointed at a Postgres
database, and the durability is the table it writes.

## Where it lives, and why it is private

It is embedded **privately** in the bundles that use it: `dbo-subscriptions`
and `dbo-stream` each carry DBOS and its whole dependency closure in `lib/`,
as non-exported packages. Nothing Spring-adjacent reaches the container's
wiring, and the bundles' own exports are DBO-owned types.

**One package is deliberately not private.** `org.postgresql` is imported from
the container's driver bundle, because DBOS unwraps a pooled connection to
`org.postgresql.PGConnection` to reach `LISTEN` — and a private copy of that
package makes the class it asks for and the class the connection implements two
classes with one name, so the unwrap can never succeed and the listener falls
back to polling for ever. What has to agree is the interface two parties pass
an object across; the rest of the driver stays private.

**Several DBOS instances run in one JVM.** It is an instantiable class rather
than a singleton, and this store relies on that: a tenant's own durable layer,
a door on the stream and a fleet step's consumer are separate instances over
separate databases in the same process.

## The three places it is used

### A tenant's own durable work

Every tenant launches one at bring-up, over that tenant's database, migrating
its own schema. It is what delivers subscriptions: a dispatch round reads the
change feed, matches, and enqueues one workflow per delivery — so a notification
that was in flight when a node died is delivered by the next one rather than
lost.

### The stream, as a carrier for lanes

A tenant opens a **door** on the deployment's substrate — a long-lived workflow
addressed by a known id — and a participant holds a **lane** that sends asks to
it and waits on events for the answers. The carrier is full duplex over one
channel: work goes out, and travel, access and result events come home.

What this buys over HTTP is that a participant needs no route into the tenant
and the tenant needs no callback: both connect to one database. What it costs
is that the plane carries no token, so an ask is signed with the participant's
enrolment key and a payload is sealed to it. A runner cannot tell which carrier
it holds, which is the point — see
[participants](participants.md) for what a participant may see, and
[one lane for the fleet](one-lane-for-the-fleet.md) for the level above.

### A step the deployment performs for every tenant

A fleet step's work sits in a **queue** on a **step substrate**: a database the
runtime owns, carrying a durable bootstrap and nothing else — no face, no zone,
no isolation, no authority. Several steps may name one substrate and share it.

Two halves use it very differently, and the asymmetry is deliberate:

- The **joiner** holds a `DBOSClient`, which writes to the durable layer
  **without being an executor**: no registered workflow, no queue polling, no
  permanently held listener connection. It offers work and consumes nothing,
  so a joiner reading every tenant costs one connection rather than one per
  step.
- A **consumer** holds a full instance, which registers its steps' queues,
  registers the one workflow class and launches. That costs a listener and a
  pool, which is the price of performing work and is why where a step's queue
  lives is a deployment's decision.

## What the store relies on, precisely

**One row per workflow id.** Offering the same run twice writes one item, which
is what makes the joiner idempotent — no transaction spans reading a tenant's
feed and writing to a step's substrate, so a restart or a lost acknowledgement
re-offers, and re-offering has to be free.

**A consumer listens to the queues it was given.** A process dequeues every
queue registered in its system database unless it says otherwise, and steps
share substrates on purpose — so a consumer names its own queues explicitly.
Without that it would take another step's item, find nothing that performs it,
and drain a tenant's work into a process that never did it.

**Flow control is per partition**, and the partition is the tenant. A fleet
step carries every tenant's work, so one tenant's backlog would otherwise
decide how long every other tenant waits.

**Arguments are serialised.** What crosses a queue is data, read back by
whichever process takes the item, possibly after a restart — so an item names
a run rather than carrying it, and a handle for reporting back is made on the
far side rather than travelling.

## What it is not asked to do

**It is not the store.** Records, history, search and the trail are this
store's own; DBOS holds work in flight and nothing that outlives it. A joined
item is a copy bounded by the work that caused it, and the run it names is a
record in the tenant that authored it.

**It is not an authority.** Who may do what is decided by the tenant's own
authority before anything is enqueued, and an outcome comes home through the
tenant's own lane so that it meets the tenant's rules. A queue is a carrier.

**It is not a notification service.** When its listener cannot be established
the queues poll instead, and correctness is unaffected — which is the property
that lets the store treat notification as an optimisation rather than a
dependency.
