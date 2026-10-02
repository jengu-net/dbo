---
title: Tenants and faces
eyebrow: Guide
standfirst: >-
  A tenant is a database with an authority of its own, declared in one file. A
  face is the standard it speaks, and a face root is a tenant holding that
  standard's definitions as records.
template: essay.html
---

## A tenant is a database

Every tenant has a database of its own, provisioned when its declaration
appears and dropped when it is erased. There is no `WHERE tenant_id = ?` to
forget: no code path reads or writes without a tenant, and a tenant's store is
a different database from its neighbour's.

Each tenant is also its own authority: its own issuer, keys, token endpoint and
records of who may act. A token from one tenant fails at another before any
claim in it is read.

A tenant is declared as one file in the directory the application watches
([a tenant opens](a-tenant-opens.md)). Changing a declaration that a serving
tenant can take is applied in place; one that cannot work is refused while the
tenant keeps serving.

## Types, identity and handling

A tenant holds the types it declares, and for each says two things.

**What identifies one.** `canonical` — by its `url`, so writing it twice
replaces it; `identifier` — by an identifier in one of the named `systems`, so a
second record claiming the same value is refused; `internal` — by the id the
store gives it.

### Handling

**Who authors it here.** `operational` — this tenant's own people and systems
write it. `replicated` — it is authored somewhere else and arrives as a copy,
because the tenant declared a dependency on it, and a write here is refused by
name rather than overwritten by the next sync.

## A face is the standard a tenant speaks

`"face": "r5"` is one word in the file. What stands behind it is records: the
version's structures, search parameters, value sets and code systems, held by a
**face root** — `fhir-r5` and `fhir-r4` in the sample world — and taken by
every tenant on that face as a dependency. A tenant is served only once those
have arrived.

So two releases run side by side in one deployment: there is a tenant holding
R5's definitions, a tenant holding R4's, and each ordinary tenant declaring
which it reads. The insurer in the sample world is a release behind on purpose,
because a payer on the older release is what actually happens.

A face is cut once per release into an image, and a tenant on that face comes
up from the image rather than expanding the version again; that is why a first
start is slow and later ones are not.

## Where to read more

The [building blocks](../arc42-005-building-blocks/README.md) say how the
pieces are packaged, and [running it](running-it.md) is what an operator
decides about tenants and faces before a deployment starts.
