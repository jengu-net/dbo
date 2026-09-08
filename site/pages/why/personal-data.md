---
title: The operator cannot read what it hosts
eyebrow: Why DBO
standfirst: >-
  Two different questions get asked of a shared store, and they have different
  answers. Can another organisation see my data — and can anyone, including
  whoever runs the thing, see who this is about?
template: essay.html
---

The first question is asked by a party deciding whether to join a shared
exchange at all. The second is asked by the law, and its answer decides whether
an operator can run the system without being trusted with its contents.

They are solved by different mechanisms: the first structurally, the second
cryptographically. Both matter, and a wall with undocumented holes in it is
worse than no claimed wall at all — so what deliberately crosses is declared.

## An organisation's data is its own database

Not a filter over a shared table. Each tenant's records live in a database of
its own, with credentials of its own.

The difference shows up on the day somebody writes a query that forgets its
tenant filter. In a shared table that query returns another organisation's
records. Here it returns nothing, because there is nothing else in there to
return.

It also changes what deleting an organisation means. Removing a tenant drops a
database — a thing you can watch happen — rather than running a delete sweep
across shared tables that somebody has to take on trust was complete.

There is no cross-tenant surface at all, including for the operator. A process
that legitimately needs a fleet-wide view holds per-tenant credentials and asks
each tenant in turn. That is a walk rather than a join, and it is deliberately
the expensive path: a convenient cross-tenant read would be available to
anything that ever got hold of it.

## A person's data is not the operator's

Identifying material — names, national identifiers, contact details — is
encrypted **inside the payload**, with a key belonging to that person, in the
same atomic write that stores the record.

The usual approach is to separate identity from content by discipline: this
module never stores a name, that runtime never sees a national code. Discipline
is exactly what fails under maintenance, at three in the morning, in the one
code path nobody remembered. Encrypting in the write means history, change
feeds, exports, archives and replicas carry ciphertext **by construction**
rather than because every path that produces them was written correctly.

## The conflict this resolves

Two requirements point in opposite directions, and most systems quietly pick
one.

Every version of every object is kept immutably, and archives are byte-faithful
— that is what makes an audit trail worth anything. But a person can require
erasure, and erasure cannot be honoured by rewriting history without destroying
both properties at once.

The resolution is **crypto-shredding**. Erasure destroys the person's key.
History stays byte-immutable. Archives already taken stay valid as files. And
the person's data is cryptographically gone — from the live store, from
history, and from every archive that ever carried it.

--8<-- "assets/diagrams/crypto-shredding.svg"

<p class="diagram-caption">Nothing is rewritten. The history is the same bytes
it was, and the archive that left the building in March is the same file — it
simply no longer opens.</p>

<div class="takeaway" markdown>
Erasure is not a promise to delete rows. It is the destruction of the only
thing that could ever have read them, and it reaches backups nobody has to go
and find.
</div>

## Rights stop being procedures

Access, portability and erasure become operations the machinery performs
rather than tasks a person carries out and then attests to.

Erasure in particular is asked for as work and answered by a run — what was
found, how far it got, what it could not reach and why. A receipt, produced by
the system, rather than an email saying it was handled.

## What this means for whoever operates it

The party running the deployment can provision, monitor, back up, restore and
upgrade the system without being able to read a person's data in it. Backups
are sealed under the owner's key. Provisioning hands the store a connection and
never a set of credentials. Each tenant is its own login authority, so a token
issued by one tenant dies at another's signature check.

And the operator's own records are a tenant too — distinguished by role rather
than by position, inheriting the same authority, audit, retention and erasure
rules as everyone else, instead of living in a privileged plane with rules
written specially for it.

<div class="further" markdown>
The mechanism in full — both walls, the declared crossings, key custody, and
what a restore replays before it will serve — is
[Data isolation (§14)](../docs/arc42-008-crosscutting/data-isolation.md), with
the handling rules in
[Declared rules (§15, §17)](../docs/arc42-008-crosscutting/declared-rules.md).
</div>
