---
title: The operator cannot read what it hosts
eyebrow: Why DBO
standfirst: >-
  Identifying material is encrypted where it is written, with a key belonging
  to that person. So every copy the store makes of itself carries ciphertext
  because of where the encryption happens, not because each path was written
  correctly.
why: 4
template: essay.html
---

The first of those questions — can another organisation see my data — is
answered structurally, and is [a tenant is a database](why-a-tenant-is-a-database.md).
This one takes the second, and it has a different answer: not separation, but
encryption, and not by discipline.

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

## What this means for whoever operates it

The party running the deployment can provision, monitor, back up, restore and
upgrade the system without being able to read a person's data in it. Backups
are sealed under the owner's key — held, moved and restored by somebody who
cannot open one.

And the operator's own records are a tenant too — distinguished by role rather
than by position, inheriting the same authority, audit, retention and erasure
rules as everyone else, instead of living in a privileged plane with rules
written specially for it.

<div class="further" markdown>
The mechanism in full — both walls, the declared crossings, key custody, and
what a restore replays before it will serve — is
[Data isolation](README.md), with
the handling rules in
[Declared rules](../declared-rules/README.md).
</div>
