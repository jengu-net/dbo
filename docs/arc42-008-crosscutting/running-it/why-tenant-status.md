---
title: "Tenants come and go routinely"
headline: "A tenant's whole life is an operation"
eyebrow: Why DBO
standfirst: >-
  Declared, provisioned, brought up, served, and one day taken away. Only the
  middle of that is a state a running node reports, and the state worth knowing
  about is the one a list of served tenants leaves out.
why: 1
template: essay.html
---

Taking a customer on and letting one go are the two operations a multi-tenant
store is judged by, and in most of them both are projects. Here they are moves
in a sequence the runtime already performs, and the only part of that sequence
anybody has to watch is the middle.

Ask such a system what it is serving and you usually get a list. The list is
true. It is also the wrong shape, because everything interesting is what the
list left out.

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

**Coming up, serving or failed.** The three states below: the only part of the
sequence a running node reports, because it is the only part that can differ
from what was asked for.

**Deprovisioned.** It leaves, carrying [everything it
brought](../data-isolation/why-leaving.md) in one sealed archive.

A restore is that last move read backwards — provision a fresh tenant, then
import the archive by the everyday import route. So bringing a tenant back from
a backup is not a recovery procedure somebody maintains separately. It is
onboarding, with the archive already written.

## The three states a node reports

**Serving** — the endpoint is up and the engine is wired.

**Coming up** — declared, and not answering yet for a reason that resolves
itself: a dependency whose upstream is not up, or a scan that has not reached
it. The next round is where it changes.

**Failed** — declared, and bring-up refused. It will not change on its own, and
somebody has to look.

The difference between the second and the third is the whole value of the
answer. Both are "not serving". One is a system working normally and the other
is a system waiting for a person, and a status that cannot tell them apart
makes an operator watch a healthy thing and ignore a broken one.

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

## Two ways this was wrong before it was right

Both worth stating, because they read identically to whoever declared the
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

<div class="further" markdown>
What a tenant *is* is [a tenant is a database](../data-isolation/why-a-tenant-is-a-database.md);
what gets it declared in the first place is
[applying configuration](why-applying-configuration.md). The operational picture in
full — bring-up, embedding, backup as export, upgrades — is
[Running it](README.md).
</div>
