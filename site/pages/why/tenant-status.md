---
title: What the node is actually doing
eyebrow: Why DBO
standfirst: >-
  A directory of specifications says what was declared. Only the runtime knows
  that a specification was written and its tenant never came up — which is the
  question worth asking, and the one a list of served tenants cannot answer.
why: 9
template: essay.html
---

Ask a multi-tenant system what it is serving and you usually get a list. The
list is true. It is also the wrong shape, because everything interesting is
what the list left out.

--8<-- "assets/diagrams/declared-and-actual.svg"

<p class="diagram-caption">Two of four, and no indication that the other two
exist. Nothing in that answer is false.</p>

## Three states, one per tenant it has been told about

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
surface](a-tenant-is-a-database.md) for *content*, and a process that needs a
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
What a tenant *is* is [a tenant is a database](a-tenant-is-a-database.md);
what gets it declared in the first place is
[applying configuration](applying-configuration.md). The operational picture in
full — bring-up, embedding, backup as export, upgrades — is
[Running it (§11)](../docs/arc42-008-crosscutting/running-it.md).
</div>
