# Work that arrives without a poll

Issue: jengu-net/dbo#288

## The premise the issue was written on, corrected

The issue opens by saying two bindings satisfy the runner facade today —
in-JVM and the lane's verbs over HTTP — and asks for a third, over a durable
queue in a database.

**There are three.** `dbo-stream` carries the same verbs over the store's own
substrate: a workflow per tenant as a door, an ask sent to it and the answer
returned as an event, DBOS embedded privately in the bundle exactly as it is in
`dbo-subscriptions`. The deployment shape the issue describes is built.

What the issue is actually about survives the correction and is sharper for it.
All three bindings are **ask and answer**, so a claim is a round trip and ready
work waits for the next tick over the substrate exactly as it does over HTTP.
The cost is not the transport. It is the **activation model**.

## What DBOS actually gives, read from its source

This decided the design, so it is written down rather than remembered. Read
from `dev.dbos:transact` 1.0.0 and confirmed unchanged in 1.1.0-m30:

| path | mechanism |
|---|---|
| queue dequeue | **polled** — one `QueueListenerTask` per queue at its own `pollingInterval`, with jitter and exponential backoff |
| `send`/`recv`, workflow events, streams | **LISTEN/NOTIFY** — `pg_notify` triggers on the `notifications`, `workflow_events` and stream tables |

There is **no trigger on `workflow_status`**, which is where queued work lives,
and `QueueListenerTask` has no signal input: its only timing knobs are the
polling interval, the backoff factor, and a `speedup` field that exists for
tests. So enqueuing wakes nobody, and a queue cannot be poked.

Three consequences follow, and together they chose the design.

**Queue dispatch would not remove the poll, it would move it.** dbo's tick
would become DBOS's tick. Meanwhile `StreamLane` already talks to its door
through `send` and events — which *are* notify-driven — so the transport is
already push and the tick above it is the whole of the latency.

**Queue topology would be a correctness requirement rather than a tuning
choice.** The dequeue selects by queue name only:

```sql
SELECT workflow_uuid FROM workflow_status
WHERE queue_name = ? AND status = 'ENQUEUED' AND (application_version = ? OR ...)
ORDER BY priority ASC, created_at ASC
```

It marks the rows `PENDING` and only then looks for the function, throwing
`DBOSWorkflowFunctionNotFoundException` if this process does not implement that
step — from inside a loop with no per-item catch. So a process that dequeues a
step it cannot perform **strands the whole batch in `PENDING`**, where the next
poll (which selects only `ENQUEUED`) will not find it again, and backs itself
off exponentially while doing so. Avoiding that needs one queue per step, per
tenant — because reach is per-tenant — and every runner setting `listenQueues`,
whose default is *listen on all queues*.

**That queue count is the bill.** Tenants × steps queues, each a listener task
on a scheduled pool sized to the processor count, each polling the system
database on its interval.

## What is being built instead: the hybrid

> **Dispatch keeps dbo's claim model and gains a wake-up. The feedback path is
> DBOS messaging, which is notify-driven.**

Push in both directions, no poller per queue, and the run record stays the only
account of what is owed and by whom.

What is given up, deliberately and by name: **priority ordering** and
**worker-concurrency caps**. Those are the two things DBOS queues offer that
dbo cannot express, and they are not worth *tenants × steps* pollers until
something needs them. When something does, this document is the argument to
revisit rather than a decision to unpick.

### Dispatch: the wake-up

A wake-up **carries nothing** — not the run, not its inputs, not a claim. It
says *look again*, and the runner then polls and claims through the ordinary
path, because the claim race is what decides who takes a run and a second
mechanism deciding it would sit beside the run record's answer to the same
question. The listener takes a `Runnable`, so there is no argument for work to
arrive in and the compiler is the check.

**The poll is the fallback, not the mechanism.** A runner waits for a wake-up
only up to its poll interval and then looks anyway, so a lane that can say
nothing is not degraded and a wake-up that never arrives costs latency rather
than work. That is the property worth testing, and it is tested: the defect
this area came from was a failure repeating in silence, and a delivery nobody
notices going missing would be the same shape again.

Built: `Wakeups`, `Lane.wakeups()`, the wait in `StepRunner`, `Runs.Claimable`
as the store's end, and `InProcessWakeups` joining both ends in one JVM. That
last one is reachable by a host embedding the store in its own JVM — the
appliance shape, a consumer's code — and **not** by the deployment this issue
is about.

Still to build: the wake-up for `StreamLane`, which is the deployment this
issue is about. It travels on DBOS messaging like everything else here.

### The feedback path

The runner's outcome, its milestones, the audit entries its work produced and
its metrics go back as **messages** rather than as synchronous lane verbs.
Durable in the `notifications` table, delivered on `pg_notify`, and waiting
there if the store is down rather than being lost in flight. The store's side
applies them: advancing the run, writing the trail, recording the numbers.

`Manifest.head` already carries the head of the run's chain, so a result
arriving asynchronously has something to commit to — the chain does not need
inventing for this path.

### What travels

The message is a `SealedWork`: a `Manifest` — tenant, step, run, slot to
`Type/id`, recipients, chain head — and the `SealedPayload`s it names. That
object already exists and is proven; nothing is invented here.

**Sealed payload in the message, by default.** A reference would have to be
resolved somewhere, and the only blob door is the tenant's own — so resolving
one would make the runner open a connection back to the tenant and hold a
credential for it, which is the coupling this binding exists to remove. It is
also already the design: inputs leave in the carrier form, so a shred reaches a
copy in flight with no special case, and a reference would need its own erasure
path beside that one.

Above a size threshold the payload spills to a store **on the substrate** and
is referenced from the message — on the substrate, because that keeps the
runner touching one thing. The reason for the threshold is not purity: DBOS
re-reads workflow inputs on recovery, keeps them under its own retention rather
than the tenant's `removeAfter`, and Postgres will TOAST large values into a
blob store with none of a blob store's lifecycle. Spilled bytes need the same
shred reach as in-message ones, or erasure has a hole exactly where the large
payloads are.

### Who may know who the work is about

`SealedPayload` is two layers, and conflating them grants more than the work
needs:

| layer | sealed with | opened by |
|---|---|---|
| outer | a per-payload data key, wrapped to each recipient's enrolment key | the runner, with its own private key — **no vault** |
| inner | the person's key | only through the vault, or a callback |

The outer seal yields the **carrier form** — identifying elements still under
the person's key — which is enough for most work. So a runner needs no vault to
do its job, and reassembling identity is a further, separate act.

**That act is a callback to the tenant, everywhere — not only in cloud.**
Handing a runner the vault would put identity reassembly where nothing records
it, and the trail exists to answer who saw whom. A callback makes every
reassembly an act performed at the tenant, which is where it can be recorded as
a disclosure. In an appliance it is a local call, so one rule costs nothing.

**The callback's answer comes back sealed to the asker.** If it is a DBOS
activity its result is stored in the system database — the shared plane — and
returning reassembled identity there would put identifying data on the plane a
ratchet already checks by reading every row and asserting it holds the
manifest, no content and no token.

## Where it stands

All of it is built.

- ~~a wake-up for `StreamLane`, on DBOS messaging~~ — **done**, on the door's
  own inbox, because an event belongs to the workflow that set it.
- ~~the feedback path~~ — **done**.
- ~~the spill, with its shred reach~~ — **done**. What is spilled is what would
  have travelled: the same sealed carrier form, moved and not transformed,
  which is the whole of why an erasure reaches it. Spilled *inside* the verb's
  step rather than after it — a step's result is what replay hands back, so
  the substrate records it, and an answer set aside after the fact leaves the
  bytes in the substrate's own tables with only the event made small.
- ~~the identity callback, with a sealed answer~~ — **done**, as a lane verb.
  Refused for a document the run does not name, refused without a stated
  purpose, refused outright on a host with no trail, and recorded as an
  opening naming its run.
- ~~and the test that a runner with its wake-up suppressed still does the
  work~~ — **done**, and it is the one that needed the most care: work offered
  before the runner sleeps is taken on its first cycle, so the first version
  of it would have passed against a runner that never looked again.

Two things worth keeping when this document goes.

**The assertions that could not fail.** Three of them here, each found by
mutation rather than by reading. A spill's plane scan passes whether or not
the spill happened, because sealed bytes carry no marker wherever they sit —
the assertion has to be about *size*, not content. A check that reassembled
identity did not travel readable passes on plaintext, because every byte array
renders as Base64 on the wire. And a suppression test whose guard is too strict
fails on the guard rather than on the property, which is a red test proving the
wrong thing.

**A payload moves through the store in time quadratic in its size** — 96 KiB in
42 seconds, 400 KiB in 632. Filed separately with the measurements. It matters
here because this whole design assumes large payloads are normal, and it is
invisible to every other suite, where documents are a few hundred bytes and
`n²` and `n` look the same.

## Not doing

- **Queues as dispatch.** See the source reading above: they would move the
  poll rather than remove it, and cost *tenants × steps* pollers to avoid a
  stranding failure that only exists because of them.
- **The queue as assignment.** It moves the contract line: a deduplication id
  is the coordination the claim exists to make unnecessary, and it puts a
  second answer beside the run record.
- **A process database.** The substrate is already there and already carries
  the doors.
- **Touching the HTTP binding.** An external actor's runner keeps the door with
  an authority in front of it. That surface is the point, not an overhead — and
  its far side cannot be pushed to, so it keeps the poll and is right to.
