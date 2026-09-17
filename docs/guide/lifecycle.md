---
title: Lifecycle
eyebrow: Guide
standfirst: >-
  A tenant appears when its declaration does, changes where it stands rather
  than being replaced, and stops when the declaration goes — and each of those
  has a cost worth knowing before you plan around it.
template: essay.html
---

Chapter three showed the two ends of this: a file appears and a tenant comes
up, the file goes and it stops. This is what happens in between, and what each
step actually costs.

## Coming up is not free

The first bring-up of a tenant is minutes, not seconds. A database is
provisioned, and then the part that dominates: a terminology baseline is loaded
so the tenant can validate anything at all, and the definitions of its face
arrive from its face root.

That cost is per tenant and paid once. It is why a world is something you bring
up and keep rather than start per test, and why a deployment that creates
tenants on demand should expect the first request after creation to wait.

A tenant that cannot come up yet — because the upstream it declared is not
serving, or storage somebody else provisions has not arrived — is not a
failure. It is retried on the next scan, and says so rather than reporting a
fault somebody would investigate.

## Changing it happens where it stands

Change the declaration of a tenant that is already running — here, by removing
a type from it:

```bash
--8<-- "docs/guide/examples/check.sh:change-in-place"
```

The tenant is not deleted and recreated. It is **rebuilt where it stands**: the
same database, the same records, the same history, with the declaration applied
over it. What it serves afterwards reflects the change:

```bash
--8<-- "docs/guide/examples/check.sh:change-took"
```

```
True False
```

`Patient` still served, `Observation` no longer. Nothing was migrated and
nothing was moved.

**There is a window.** A rebuild takes seconds, and during it the tenant does
not answer. That is not zero-downtime reconfiguration and this page will not
pretend otherwise — if you change declarations under live traffic, requests in
that window fail, and the caller retries.

What you get in exchange is that a configuration change is never a data
migration. The records were never the thing being changed.

## Going away

Removing the declaration stops the tenant serving, which chapter three showed.
Worth being explicit about what that is and is not: the endpoint stops
answering. It is a retraction of service, not a deletion of data — destroying
what a tenant holds is [erasure](erasure.md) and the archive path, both of which
are deliberate acts with their own doors.

The loop that retracts undeclared tenants is also why the managing tenant sits
outside the watched directory. The thing that records retractions must not be
retractable by the loop that performs them.

## What you would otherwise have written

A provisioning script, and a second one for changes, and the question of which
of them owns a tenant that is half-created because the first failed.

A migration per configuration change, because the configuration lives in the
same tables as the data — so adding a type to one customer is a schema
operation with a rollback plan.

And a deprovisioning path that everyone is slightly afraid of, because it is
the one that deletes, and nothing distinguishes *stop serving this* from
*destroy it*.
