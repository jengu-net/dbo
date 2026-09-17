---
title: Who may act
eyebrow: Guide
standfirst: >-
  Authorisation is derived from records the tenant already keeps — the
  organisation, its people, and what each of them is — so there is no second
  user directory to drift from the first.
template: essay.html
---

Every system that holds records for more than one person ends up with two
descriptions of those people: the domain one, which says who they are and what
they do, and the security one, which says what they may click.

They start identical and diverge immediately, because they are updated by
different people for different reasons. Somebody leaves and is removed from one.
A department is reorganised in the other. The gap between them is where the
incidents live.

This store has one.

## The organisation is a record

```bash
--8<-- "docs/guide/examples/snippets/org-and-people.sh"
```

```
201
201
```

An `Organization` and a `Practitioner` — ordinary records, with history and a
trail like anything else. Nothing about them is special to security yet.

## A role is a record too

```bash
--8<-- "docs/guide/examples/snippets/the-role.sh"
```

```
201
```

That is the grant: this practitioner, at this organisation, as a matron. It is
an active `PractitionerRole`, which is the same thing a clinical system would
hold anyway to know who works where.

**Revoking it is ending it**, not deleting a permission. The role stops being
active, the history says when and who ended it, and somebody asking *did she
have access in March* has an answer rather than an absence.

## What a role may do is declared

The records say she is a matron. What a matron may do is the tenant's own
statement:

```bash
--8<-- "docs/guide/examples/snippets/role-grant.sh"
```

```json
{"status":"ensured","role":"matron"}
{"grants":[{"role":"matron","organisation":"hogwarts",
 "scopes":["user/Patient.read","user/Observation.read"],"status":"active"}]}
```

Two properties worth naming. The grant is **readable**, which is what lets a
provisioning system converge it — you cannot withdraw what you have no way to
learn about. And it is **per organisation**, so a matron at one hospital is not
a matron at another by virtue of the word.

## What this buys

A person's access is a consequence of the records describing them. There is no
step where somebody also remembers to update a permission table, because there
is no permission table — the role *is* the grant, and it is the same record the
organisation keeps for its own reasons.

So the question *why does she have access to this* has an answer made of things
the organisation already believes: she holds an active role at an organisation,
and that role is granted these scopes. No line of it was written by security
for security.

## What is not shown here

A human actually logging in. That goes through the ceremony the zone runs —
[a zone is a jurisdiction](zones.md) showed it answering — and ends with a token
carrying exactly the scopes the grant above describes. It is an interactive
redirect flow, so it is not a `curl` in a guide, and this page would rather show
you the records that decide the answer than fake the ceremony that delivers it.

Machine access does not go through it at all: a client credential is
[the tenant's authority](authority.md), and holds what it was registered with.

## What you would otherwise have written

A users table, a roles table and a join between them — then the reconciliation
job against the HR system, because the real answer to *who works here* was
never in your database.

A permission matrix maintained by whoever last had time, and the annual access
review that exists because nobody trusts it.

And the leaver problem: an account disabled in one directory, still active in
another, discovered by an auditor rather than by the system that should have
known both facts were about the same person.
