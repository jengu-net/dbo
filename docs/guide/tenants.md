---
title: Tenants
eyebrow: Guide
standfirst: >-
  A tenant is a database, declared as one file. Dropping the file in brings it
  up; taking the file away stops it. Everything else in the guide happens
  inside one.
template: essay.html
---

If you are building a system that serves more than one organisation, the
tenant is the first decision you inherit rather than make. It is not a column
on your tables and not a filter you have to remember. It is a database.

That is worth being blunt about, because it decides what two sentences are
worth. *Your data is separated from theirs* stops describing a convention and
starts describing the storage. *Your data is gone* means a database was
dropped.

## What you declare

A tenant is one file. This is the insurer, complete:

```json
--8<-- "docs/guide/world/tenants/gringotts.json"
```

Four things are being said, and the engine enforces all of them.

**Which standard it speaks.** `"face": "r4"` is the whole of it. The hospital
says `r5`, in the same deployment, in the same process.

**Which jurisdiction it is in.** `"zone": "rl"` puts it inside Rowling Land,
whose rules it takes and may narrow but never widen.

**Where its definitions come from.** The `face: true` dependency names a face
root — a tenant holding that version's structures, search parameters, value
sets and code systems as records. The insurer subscribes to a version rather
than a node loading one.

**What it holds.** Each type says what identifies one of its records and how it
is handled. `Patient` is identified by an identifier in a system the zone
publishes, so two records with the same national identifier are the same
person. `Coverage` has no business identity, so the store assigns one.

## A type you did not declare does not exist

The hospital declared `Observation`. The insurer did not. So the same request
gets a different answer depending on which door it arrives at:

```bash
--8<-- "docs/guide/examples/check.sh:undeclared-type"
```

```
404
```

Not an empty list, and not a permission error. There is no such collection
here, because this tenant never said it holds one. What a tenant holds is a
closed set, and adding to it is an edit to that file rather than a side effect
of somebody's first write.

## Declaring one brings it up

The store watches a directory and reconciles continuously. A spec that appears
is a tenant that appears — provisioned a database of its own, brought up, and
served:

```bash
--8<-- "docs/guide/examples/check.sh:add-tenant"
```

Wait for it, and it answers:

```bash
--8<-- "docs/guide/examples/check.sh:new-tenant-serves"
```

```
200
```

This is the whole provisioning model, and it is worth trying rather than
reading. Nobody ran a migration, nobody created a schema, and no existing
tenant was touched or restarted.

It is not instant. A new tenant is a database provisioned from nothing with a
terminology baseline loaded into it, which is tens of seconds even on a warm
machine. Onboarding a customer is an operation the runtime performs, not a
project, but it is not free either.

## Taking it away stops it

```bash
--8<-- "docs/guide/examples/check.sh:remove-tenant"
```

The tenant stops being served. Note what that is and is not: retracting a spec
stops serving a tenant, and it does not erase it. Erasing is an explicit
deprovision that drops the database, and the two are deliberately different
operations — one is a Tuesday, the other is irreversible.

Meanwhile the hospital never noticed. Its records are still there, because they
were never in the same database in the first place.

## The managing tenant

One tenant in the world is not like the others in what it holds, and is exactly
like them in every other respect. `mom`, which serves the Ministry of Magic, is
the managing tenant: what this
deployment was told to serve lives there as ordinary records, with the same
history, the same trail and the same rules.

So "what is this deployment supposed to be serving" is a query rather than an
expedition across nodes, and the operator's own actions are recorded under the
rules it operates for everybody else.

It sits outside the watched directory on purpose. The loop that retracts
undeclared tenants must not be able to retract the thing that records
retractions.

A deployment does not have to have one. Without it the node reads its
declarations directly from the source and serves exactly as before — recording
what was declared is not a condition of honouring it.

## What you would otherwise have written

A tenants table and a foreign key on every other table. A filter in every
query, and a review process to make sure nobody forgets it. A provisioning
script. A deletion script somebody has to certify was complete, and a
`deleted` flag a later reporting query forgets about.

The version of that story you do not have to write is the one where a query
with a bug in it returns somebody else's rows and nobody finds out.
