# US-DBO-BRING-UP-UNDER-STRAIN — tenants come up while things around them go wrong, and each reads as what it is

> Marta runs a deployment that brings clinics up on a cluster where the
> databases are made by somebody else's operator, modules arrive and leave,
> and nodes are stopped and replaced during the working day. On a bad
> morning four clinics are declared at once, one of their databases has not
> arrived, a lab module is not installed yet, and a node is drained halfway
> through a sync.
>
> None of that is a fault, and she needs the deployment to say so. What she
> reads has to tell her which tenants are waiting, which failed and why, and
> which are fine — without a stack trace for every pool she closed on
> purpose.

## The scene

One deployment, and the things around it that do not behave:

- **storage** that somebody else provisions and that has not arrived yet;
- **several tenants declared at once** by a consumer that waits for all of
  them;
- **a face** that is missing a capability one declaration needs;
- **a mandatory step** that no module or participant performs yet;
- **the engine's own vocabulary**, which every tenant is given at bring-up
  and which a tenant with an upstream also receives over its stream;
- **a tenant going away** while a second node is still mounting it;
- **a node stopped** while its sync rounds are running;
- **a bring-up held open** while other tenants' streams have work to carry.

Every one of these is ordinary in a real deployment and rare in a test
world, which is why this story stages them on purpose.

## Waiting is not failing

Four clinics declared together come up together, as many at a time as the
node was told it can carry, so the wait is the slowest clinic's and not the
sum of all four. A clinic whose database has not arrived is coming up, not
failed, and the runtime says what it is waiting for. The clinic declared
behind it is not held up. When the database arrives, the next pass is all it
takes, and the clinic stops being anybody's trouble.

A bring-up that is held open does not stop anything else either. Content
written upstream while one tenant is stuck coming up still reaches the
tenant that streams it.

## A failure says what failed

A declaration that asks for something its face cannot do is refused when the
tenant comes up, not halfway through a request weeks later. The refusal names
the capability that is missing, the part of the declaration that asked for
it, and the face that fell short. A tenant on the same face that asks for
nothing unusual comes up as normal: a missing capability is only a fault
against a declaration that needs it.

A declaration naming a face nothing provides is still recorded as failed.
Quieting the cases that are not failures does not quiet the ones that are.

## An incident is not an outage

A tenant that declares a mandatory step nobody performs keeps serving. Its
runs queue, because the system is asynchronous, and the missing step is an
incident on the operator surface, named. When a module is installed or a
participant introduces the step over a lane, the next pass clears the
incident. When the module goes away again, the incident reopens. A step the
tenant did not declare mandatory is never an incident, and a mandatory step
id that is not a step id is refused when the declaration is read.

## Nothing is said about what is fine

The engine's own vocabulary arriving a second time over a stream is the same
publication the tenant already holds. It is not parked as somebody else's
override, and it puts no errors in the database's log. A definition the
tenant wrote itself, with different content at the same address, still wins
over the upstream's copy, and the upstream's version waits for a person.

A tenant whose storage is gone while a racing node is still mounting it is
being taken down. It is not recorded as failed and leaves no trouble against
its name. A node that is stopped while it syncs says nothing about the
connection pools it closed on purpose.

## Joins

The promises this story rests on, projected from the catalogue rather than
written here: a story claims no evidence, and a leg is what its promise's own
citations say it is.

<!-- story:begin — generated from the promise catalogue; do not edit. Regenerate: ./gradlew :core:harness:promiseProjection -->

| Promise | Says | Status |
|---|---|---|
| `REQ-DBO-TEN-DECLARED-TOGETHER-COME-UP-TOGETHER` | A consumer that declares several tenants at once gets several tenants. A node brings them up together, bounded by what it can carry at a time, so the wait is the slowest tenant's rather than the sum of all of them — and a tenant that cannot come up yet is its own trouble rather than a queue everybody behind it is stuck in, which is what made a busy deployment indistinguishable from a broken one. | PROVEN |
| `REQ-DBO-OPS-RUNTIME-SAYS-WHAT-IT-SERVES` | A runtime can be asked which tenants it is serving, and what it is doing about the ones it is not: serving, coming up, failed to come up — one state per tenant it has been told about. The answer comes from runtime state, never from re-reading the declarations, so a caller comparing the two can find a disagreement rather than confirming its own writes. Cross-tenant, so no tenant credential buys it. | PROVEN |
| `REQ-DBO-PROC-MANDATORY-STEPS-CLASSIFY-INCIDENTS` | A tenant's spec declares the steps its work cannot do without. The tenant serves and its runs queue regardless — the system is asynchronous by design — and what the list decides is classification: a mandatory step nothing has contributed is an incident, named and cleared as contributions come and go, while every undeclared step's absence is no incident at all. | PROVEN |
| `REQ-DBO-PROC-STEPS-ARRIVE-BY-INTRODUCTION` | A participant on a lane introduces the step declarations it brings beside its own candidacy; the catalogue records them with the introducer's name, and every consumer of the catalogue — validation, actions, milestones, the mandatory-steps classification — sees them the moment presence does. | PROVEN |
| `REQ-DBO-SYNC-LOCAL-SHADOWING` | A tenant's own object with the same base identity overrides the streamed copy — version-neutrally, across FHIR versions and business versions; removing the override falls back to the live upstream version. | PROVEN |
| `REQ-DBO-TEN-COMING-UP-AND-KEEPING-UP-ARE-NOT-ONE-QUEUE` | Bringing tenants up and keeping their streams in step do not wait on each other. A tenant catching up with a large dependency does not delay another tenant coming up, and a bring-up waiting on storage somebody else provisions does not stop the deployment's streams — neither of which announced itself when they shared a thread, because a wait that is nobody's failure is reported by nobody. Streams run several at a time, each drained before it gives way. | PROVEN |

Coverage: {PROVEN=6} — a leg marked PLANNED cites a promise that exists and is not yet cited by any test.
<!-- story:end -->

## What the store cannot do yet

- **A wait has no deadline.** A tenant whose storage never arrives is coming
  up for ever. The runtime says what it is waiting for, but nothing turns a
  long wait into a failure.
- **A teardown is no state at all.** A tenant being taken down is neither
  coming up nor failed, which is right, and it means the runtime cannot be
  asked about a teardown that is in progress.
- **How many tenants come up at once is the deployment's number.** It
  defaults to two. The store does not size it from the heap it has.

## Open decisions

- **Whether three of these legs are promises.** A face refusing at bring-up
  what it cannot serve, the engine's vocabulary arriving twice without
  being parked, and a node stopping quietly are each proven by a leg, and no
  promise in the catalogue says any of them.
