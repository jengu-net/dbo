---
title: Runners
eyebrow: Guide
standfirst: >-
  Something has to perform the work. A runner joins a tenant over a lane, takes
  what it is entitled to and reports what happened — and the lane is
  deliberately narrower than the store.
template: essay.html
---

[Processes and steps](work.md) described work as declared and recorded. None of
that performs it. A **runner** is what does: a process that joins a tenant,
takes runs it is entitled to, and says what happened to them.

The interesting part is not that runners exist. It is how little they are
given.

## The lane is the whole interface

A runner never holds the store. What it holds is a **lane** — a fixed
vocabulary of verbs about work, and nothing else.

!!! info "Nothing on the lane takes a reference"

    No verb accepts a record reference, hands out a store handle, or reads an
    object the work did not name. That is a deliberate ceiling rather than a
    feature not yet added: a widened primitive is available to every caller
    holding the scope, forever, and the widening is invisible at the call site
    that caused it.

So a runner cannot browse. It can be given work, and it can answer about the
work it was given. Asking for some looks like this:

```bash
--8<-- "docs/guide/examples/snippets/lane-poll.sh"
```

```json
{"result":[]}
```

No work waiting, which is the honest answer in a world where nothing has been
handed to a runner. Note what the request had to say: **which participant is
asking**, and **which executor is working**. Neither is a claim about what it
may have — the entitlement comes from the credential, upstream, and is never
something the caller states.

## Wherever it runs, it is the same lane

A runner may live inside the container, holding the store in the same JVM. Or
it may be somewhere else entirely, reaching the tenant over HTTP — which is
the normal case for a cloud deployment, where dbo is its own deployment
precisely so that the application never holds `CREATE DATABASE`.

Both get the same lane, and **a runner cannot tell which one it has**. The
tenant serves the participation verbs on its own private surface, guarded by
its own authority, and the remote case is the same vocabulary over a wire
rather than a thinner version of it.

That is what makes one embeddable runner enough. The same bundle runs inside
the platform's container, on a separate machine, or in a pod scaled per step —
no orchestrator, no transport of its own, no access to the tenant's database —
stateless across whichever tenants' lanes it is handed.

## The vocabulary

Every verb is an act on the tenant's work, so every one is posted. They group
by what a runner is doing at the time:

| Doing what | Verbs |
|---|---|
| joining, and saying what it can do | `declare`, `introduce`, `withdraw`, `routes` |
| finding and taking work | `poll`, `claim` |
| getting what the work is about | `inputs`, `sealed`, `opened` |
| saying how it is going | `checkpoint`, `milestone` |
| saying how it ended | `released`, `closed` |
| supervision | `reopen` |
| housekeeping | `release-lapsed` |

There is deliberately no verb for *which tenant is this* or *who am I*. A
remote lane knows both without asking — they are what it was built with — and
a round trip to be told what you already said is a round trip that can fail.

`release-lapsed` is the odd one, and the reason is worth a sentence: **anybody
may call it**, and a runner does so first thing in every cycle. Lapsed claims
are handed back by whoever happens to be working, because the one participant
that cannot report a lapsed claim is the participant whose claim lapsed.

## What a runner may take

Two rules, and they compose.

**Entitlement is declared, never defaulted.** A lane's reach is stated when the
lane is provisioned: everything, because the host *is* the tenant, or exactly
the steps a credential covers. There is no implicit unrestricted, so the reach
of a remote participant never depends on a parameter somebody forgot to pass.

**A claim is an intersection.** What a participant may claim is the overlap of
what its credential covers and what the step admits. The lane narrows the work
it offers, and refuses a claim outside the entitlement; the store separately
refuses an executor at a scope the step never opened itself to.

The consequence is worth stating on its own, because it is the kind of thing
that is usually the other way round:

!!! warning "A step cannot grant its executor more than the executor already holds"

    Being asked to perform a step is not a source of authority. This is the
    same shape as [Acting for somebody else](delegated.md), where a process
    acting for a person cannot exceed that person — a grant here only ever
    narrows.

And introducing a step grants its introducer nothing. A participant that
brings a step declaration with it is bound by that declaration exactly as
anybody else is.

## What arrives with the work

A claimed run's **inputs arrive with it**, resolved by the party that holds the
objects. The runner's only read takes the run — a run the asking identity has
not claimed is refused, and a run without slots delivers exactly nothing.

This is the same idea as [Reaching data through a run](runs.md), one layer
further out: the work carries what the work is about, so nothing needs a
general read to do a specific job.

### Sealed in flight

Work travels in two parts, and the split is the point:

- the **manifest** — tenant, step, the task, and references to the documents
  named — is readable, because routing on it is its job
- the **payload** — the documents themselves — is sealed under a data key of
  its own, wrapped once per participant meant to open it, and **not** to
  whoever merely carries it

A participant generates its keypair before enrolment and offers the public half
as part of enrolling. The private half never crosses. So what a participant may
open is decided by what it *holds*, not by what it is told — a carrier in the
middle routes correctly and reads nothing.

## Presence without a heartbeat

A participant is present while its named feed cursor moves. There is no
heartbeat and no lease.

A declaration whose consumer is behind and unmoving is **declared but not
present**: skipped by resolution, and shown that way rather than silently
failing to be chosen. The subtlety the design gets right is the inverse case —
a caught-up participant's cursor does not move either, so silence with nothing
waiting is not absence.

Heartbeats would have made both of those wrong: a busy runner that misses one
looks dead, and an idle one that sends them looks healthy while being unable to
take anything.

## A refusal is answered, never dropped

A lane refuses — a run this identity has not claimed, a step it was not
granted, an introduction into a lane that records none. That comes back with
its reason rather than as a silence.

The distinction matters more than it looks:

| | What it is | The right recovery |
|---|---|---|
| refused | settled | asking again is wrong |
| unanswered | transient | asking again is the only way through |

A participant that confused the two would back off from work it is entitled to
and lose the claim it was holding when the deadline passed. So a refusal is
told apart from a link that went quiet, and only one of them is worth
retrying.

Every refusal carries its reason, and they are specific enough to act on:

```bash
--8<-- "docs/guide/examples/snippets/lane-refuses.sh"
```

```json
{"refused":true,"reason":"no such lane verb: rummage"}
{"refused":true,"reason":"the lane's verbs are posted"}
{"refused":true,"reason":"a lane verb says which participant is asking and which executor is working"}
```

A verb that does not exist, a verb reached with the wrong method, and a verb
that did not say who was asking. None of them is a bare status code, and none
of them is silence — which is exactly the difference a runner needs in order to
decide whether trying again could ever help.

## When a runner fails

A step service that fails or throws **releases** its run with the reason —
never closed, never lost, and a later cycle may take it up. That is
[Processes and steps](work.md) again, seen from the side that does the work.

The runner re-declares each service with its own metadata block, replaced
rather than accumulated, so what a runner says about itself cannot become a
pile of stale assertions nobody removed.

## What you would otherwise have written

A worker that holds database credentials because it needed two fields from a
table, and the slow growth of what those credentials reach.

A heartbeat, a lease, and the incident where a busy worker missed a beat and
had its work taken away mid-flight.

A message broker between the queue and the workers, with its own delivery
semantics to reconcile against the database's, and the dead-letter queue that
is really a list of things nobody will look at.

A retry loop that cannot tell *you may not* from *I could not reach you*, so it
either gives up on work it was allowed to do or hammers a door that will never
open.

And a payload that every hop along the way can read, because encrypting it
per-recipient was going to be a later milestone.
