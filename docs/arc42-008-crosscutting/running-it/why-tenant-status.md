---
title: "Tenants come and go routinely"
headline: "A tenant's whole life is an operation"
eyebrow: Why DBO
standfirst: >-
  Taking a customer on and letting one go are the two operations a multi-tenant
  store is judged by, and in most of them both are projects. Here they are
  moves in a sequence the runtime already performs.
template: essay.html
---

Ask such a system what it is serving and you usually get a list. The list is
true. It is also the wrong shape, because everything interesting is what the
list left out — which is [Status Is a Lifecycle, Not a
List](../patterns/pattern-status-is-a-lifecycle-not-a-list.md), and the rest of
this page is what the sequence is made of here.

--8<-- "assets/diagrams/declared-and-actual.svg"

<p class="diagram-caption">Two of four, and no indication that the other two
exist. Nothing in that answer is false.</p>

## Declared, provisioned, serving, gone

**Declared.** A specification says a tenant should exist. Nothing has been
created yet — which is why a directory of specifications is not an answer about
what is running.

**Provisioned.** It gets its own database rather than a share of somebody
else's. [Isolation](../data-isolation/why-a-tenant-is-a-database.md) is a
property of this move, and every move after it inherits it.

**Coming up, serving, degraded or failed.** The four states below: the only part of the
sequence a running node reports, because it is the only part that can differ
from what was asked for.

**Deprovisioned.** It leaves, carrying [everything it
brought](../data-isolation/why-leaving.md) in one sealed archive.

A restore is that last move read backwards — provision a fresh tenant, then
import the archive by the everyday import route. So bringing a tenant back from
a backup is not a recovery procedure somebody maintains separately. It is
onboarding, with the archive already written.

## The four states a node reports

**Serving** — the endpoint is up and the engine is wired.

**Degraded** — serving, and still owed a step that comes after serving: the
host told, the tenant's work offered to the fleet. Its doors stay open and the
reason is beside the state; the step is retried on every scan, and the tenant
reads serving once it takes.

**Coming up** — declared, and not answering yet for a reason that resolves
itself: a dependency whose upstream is not up, or a scan that has not reached
it. The next round is where it changes.

**Failed** — declared, and bring-up refused. It will not change on its own, and
somebody has to look.

The difference between the second and the third is the whole value of the
answer. Both are "not serving". One is a system working normally and the other
is a system waiting for a person, and a status that cannot tell them apart
makes an operator watch a healthy thing and ignore a broken one.

## The doors open when the tenant serves

A tenant's surfaces are mounted as its bring-up goes, and a bring-up has work
left after they are: its face to drain, its vocabularies to publish, its
upstreams to be made somebody's. Until that is done every door under
`/t/{code}/` answers **503 with `Retry-After`**. It does not answer 200,
because a client that starts on a 200 writes into a tenant whose bring-up can
still fail; and it does not answer 404, which says nothing is here, when the
truth is that something is and is not ready.

Answering 503 was chosen over mounting the doors only at the end. Mounting late
would answer the same question with a 404 indistinguishable from a tenant
nobody declared, and it would have made every surface — dbo's and an
activity's — remember to be mounted in a second, later step. The gate is one
place every surface passes through, whoever mounted it.

Three rules keep this true:

- **A wait builds nothing.** A tenant brought up from another — a dependency,
  the projection a zone is read through, the zone it federates through — checks
  that every one of them is serving before it provisions anything. A tenant that
  arrives first is coming up, with no database and no door, and comes up when
  they do.
- **Serving is not taken back.** Once a tenant is published, what follows is
  owed rather than thrown: a step that fails degrades it, and its doors, streams
  and storage stay the ones it came up with. Only a bring-up that never reached
  serving is rolled back, and nobody was told about that one.
- **A stream has one reader.** A tenant coming up drains its own face chain;
  the round that keeps streams in step reads only serving tenants', and a
  stream is read by one caller at a time, so a change is applied once.

<div class="takeaway" markdown>
The answer comes from runtime state, never from re-reading the declarations. So
a caller that compares what it declared against what the node reports can find
a **disagreement** — rather than reading back its own writes and being
reassured by them.
</div>

## Why this is a cross-tenant question

No tenant credential buys this answer, and it does not belong to any tenant.
Whether the regional lab came up is not the county hospital's business, and it
is very much the operator's.

That fits the shape of everything else here: [there is no cross-tenant
surface](../data-isolation/why-a-tenant-is-a-database.md) for *content*, and a process that needs a
fleet view walks tenant by tenant with a credential each. What a node is doing
about the tenants it was told about is a different kind of fact — about the
node, not about anybody's records — and it is asked of the node.

## Three ways this was wrong before it was right

Each worth stating, because they read identically to whoever declared the
tenant and they are the reason the states are separated at all.

**Storage that had not arrived yet was waited for on the thread that brings
every tenant up.** So a tenant queued behind a slow one was not slow — it was
absent. Nothing distinguished "not started yet" from "will never start", and
the operator saw a shorter list than they had declared with no explanation for
the difference.

**A bring-up that failed after mounting a surface left the surface mounted.**
The retry then died on its own leftover OIDC context, and the account said
`cannot add context to list` — a true sentence about the second failure, and
silence about the specification that was actually wrong. A status is only worth
having if a retry reports the original cause rather than the wreckage of the
last attempt.

And a third, which was the same shape one step later. **A tenant answered at
its doors before its bring-up had finished, and the bring-up could still roll
back.** Two writers met inside it — its own drain of the face chain and the
reconciler's round reading the same stream from the same cursor — and the
second write of a definition nobody held a moment before failed on the first
one's history row. The bring-up was rolled back, its context removed, and a
client that had already started read `404 No context found` and lost what it
had written. Both writers now take turns, and a client cannot start until there
is nothing left to roll back.

<div class="further" markdown>
What a tenant *is* is [a tenant is a database](../data-isolation/why-a-tenant-is-a-database.md);
what gets it declared in the first place is
[applying configuration](why-applying-configuration.md). The operational picture in
full — bring-up, embedding, backup as export, upgrades — is
[Running it](README.md).
</div>
