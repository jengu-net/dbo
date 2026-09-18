# Work that arrives without a poll

Issue: jengu-net/dbo#288

## The premise the issue was written on, corrected

The issue opens by saying two bindings satisfy the runner facade today —
in-JVM and the lane's verbs over HTTP — and asks for a third, over a durable
queue in a database.

**There are three.** `dbo-stream` carries the same verbs over the store's own
substrate: a workflow per tenant as a door, an ask sent to it and the answer
returned as an event, DBOS embedded privately in the bundle exactly as it is in
`dbo-subscriptions`. A host in the store's own deployment already holds a lane
that opens nothing towards a tenant and accepts no callback, and a runner
cannot tell it from the other two. The deployment shape the issue describes is
built.

What the issue is actually about survives the correction, and is sharper for
it. All three bindings are **ask and answer**. `StreamLane` sends a verb and
waits; `StepRunner` carries a `pollEvery`. So a claim is a round trip and ready
work waits for the next tick over the substrate exactly as it does over HTTP.
The cost the issue objects to is not the transport. It is the **activation
model**.

## What is actually being asked for

Push activation: work that is ready reaches a runner because it became ready,
rather than because a runner asked again.

## The shape that survives the facade

The issue sets its own test, and it is the right one:

> If that distinction does not survive contact with the code, this issue is
> wrong and the answer is no.

The distinction is between a transport under the facade and an orchestrator
above it. A queue that **hands work to a runner** does not survive it, and the
reason is written on `StepRunner`:

> One loop thread, deliberately: a runner scales by being **deployed** more —
> a pod per step, replicas up — never by relaxing the claim; a pool inside one
> runner is the first step of the coordination the claim exists to make
> unnecessary. **The claim race is the scheduler.**

A queue with a deduplication id is that coordination, moved into a database.
It also moves the global truth: the run record says what is owed and by whom,
and a queue that assigns would hold a second answer to the same question.

So:

> **The queue is a wake-up, not an assignment.**

An enqueue when a run becomes claimable. A runner woken by it then claims on
the lane exactly as it does now, with the same verbs, and the run record stays
the only account of who holds what. `pollEvery` stays, demoted to a fallback —
which is what makes a missed wake-up a latency bug rather than a lost run.

Nothing above the facade changes, which is the acceptance the issue asks for:
a `StepService` compiles with no DBOS on its classpath, the same service runs
unchanged under every binding, and nothing can tell which one it has.

## What a runner author sees

Their code does not change. That is the point rather than a convenience.

| | today | with this |
|---|---|---|
| the step service | `StepService`, no DBOS on the classpath | unchanged |
| wiring | install the runner and the stream carrier, name the substrate, the tenants and the enrolment | unchanged |
| configuration | — | one property: this runner shares the store's deployment |
| latency to start ready work | the next poll tick | milliseconds |
| traffic while idle | a claim round trip per tick per lane | none |

## The three the issue left open, and what the wake-up does to them

**The result path.** It does not arise. The outcome goes back the way it
already goes — a lane verb — because the runner still holds a lane. A binding
that removed the wire one way and kept it the other would have moved the cost;
this removes a poll and moves nothing.

**Snapshot size.** It does not arise either, and this is the strongest argument
for the wake-up reading. Nothing rides in the queue but the fact that a run is
claimable, so the system database never holds a document. The issue's own worry
— that workflow inputs live in the system database and it is not a blob store —
is designed out rather than mitigated. (jengu-net/dbo#248 has since closed, so
the blob wire exists if a later slice wants it; this one does not need it.)

**Who is sealed to.** Unchanged. Inputs arrive with the work over the lane, in
the carrier form, sealed to the participant that enrolled — the machinery that
already exists. A wake-up carries no content and so is sealed to nobody.

## What would have to be true to build it

- an enqueue at the point a run becomes claimable, which is `Runs`' business
  and not a door's;
- a runner-side subscription that wakes the loop instead of sleeping out the
  tick, behind the same `Lane` it already holds;
- the fallback poll kept, and a test that proves a runner with its wake-up
  suppressed still does the work — because the alternative is a mechanism whose
  failure is invisible, which is the defect jengu-net/dbo#281 was about.

## Not doing

- **The queue as assignment.** See above; it moves the contract line.
- **A sealed snapshot in the task.** Nothing in the queue but readiness.
- **A second datastore.** The issue proposed a process database holding tasks.
  A wake-up needs no such thing: the substrate is already there and already
  carries the doors.
- **Touching the HTTP binding.** An external actor's runner keeps the door with
  an authority in front of it. That surface is the point, not an overhead.
