---
title: Terminology
eyebrow: Guide
standfirst: >-
  Code systems and value sets are records like any other, and the codes a
  record is checked against are those records rather than a table somebody
  loaded.
template: essay.html
---

## Terminology is records

A tenant that declares `CodeSystem` and `ValueSet` holds them, identified by
their canonical `url`, written, versioned and searched like a patient. There is
no terminology table and no lookup service to deploy. Every tenant answers
`$lookup`, `$expand` and `$validate-code` from its own concepts, whichever FHIR
version it speaks.

## Where it comes from

Most of a tenant's terminology is somebody else's: the version's own, from its
face root, and a jurisdiction's, from its zone. Both arrive because the tenant
declared a dependency on them, as `replicated` copies it cannot edit
([one tenant in two places](one-tenant-in-two-places.md)). A code system arrives
whole and is taken apart into the tenant's own concepts, so it is answered
locally rather than by a server that may not be reachable this afternoon.

A tenant may hold code systems of its own too. One with the same canonical as
an upstream copy shadows it, and says so.

## What a code means here

A coded value is checked against the terminology the tenant holds, and the
answer follows the binding's strength: a required binding violated is a
refusal, a weaker one is advice the caller is given. A system the tenant never
loaded is **unresolvable** rather than invalid: one says this store's content
is incomplete and the other says the caller's data is wrong, and a caller who
cannot tell them apart cannot act on either
([care is recorded](care-is-recorded.md#what-the-store-guarantees)).
