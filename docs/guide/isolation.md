---
title: Isolation
eyebrow: Guide
standfirst: >-
  A tenant is a database, and that one sentence is most of the isolation story
  — what it buys you, and the two things it does not.
template: essay.html
---

Chapter three said a tenant is a database rather than a column on your tables.
This is what follows from that, shown rather than asserted.

## A credential is for one tenant

The hospital's own credential, pointed at the insurer:

```bash
--8<-- "docs/guide/examples/check.sh:isolation"
```

```
401
404
```

`401`, not `403`. The insurer did not decide the hospital may not read this — it
could not tell who was asking at all. Every tenant runs its own authority, with
its own issuer and its own signing keys, so a token minted by the hospital is
not a weaker credential at the insurer; it is not a credential there.

That is worth dwelling on because it removes a whole class of mistake. There is
no central token service whose misconfiguration lets one customer read another,
and no shared signing key whose compromise is everybody's compromise. The
question *could this token have worked against the wrong tenant* has a
structural answer rather than a policy one.

## An id means nothing in another tenant

The second call uses the *insurer's own* credential — perfectly valid there —
and asks for the id of a patient the hospital holds. `404`.

Ids are not global. The same human being is a record in each tenant that knows
them, with a different id in each, and the thing that says they are the same
person is an identifier the zone declares — which is chapter four's subject and
the reason identity is declared rather than assumed.

So a leaked id is not a key to anything. It is a string that resolves in
exactly one database.

## What this does not give you

Two limits, stated plainly, because a claim about isolation that skips them is
not worth reading.

**Tenants share a process and a machine.** They are separate databases, not
separate servers. A deployment under enough load that one tenant's work starves
another's is possible, and the answer to it is deployment shape — the scaling
material under *Technical* — not this boundary.

**The operator can reach the databases.** What stops an operator reading a
person is not this chapter; it is that identifying material is sealed with a key
the store does not hold, which is the Privacy section's subject. Isolation
separates tenants from each other. It is not what separates the operator from
the data.

## What you would otherwise have written

A `tenant_id` column on every table, and a filter in every query — plus the
review that checks nobody wrote the one query that forgot, forever, on every
change.

Row-level security if your database has it, and the discovery that it holds for
the application's connection and not for the migration script, the analytics
job, or the support engineer with a psql session.

And a shared token issuer, which is the single point whose misconfiguration is
the incident you will actually have.
