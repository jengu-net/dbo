---
title: The face
eyebrow: Guide
standfirst: >-
  A face is the standard a tenant speaks, and everything the store knows about
  it is records held by a tenant. Which is why two releases run side by side in
  one deployment, and why a conversion happens once rather than everywhere.
template: essay.html
---

Every tenant so far has spoken FHIR — the hospital R5, the insurer R4. That
choice is one word in a file, and this chapter is about what stands behind it.

A **face** is the standard a tenant speaks: its resource shapes, its search
parameters, its terminology, its rules. The store is not a FHIR server that had
multi-tenancy added. It is an object store, and FHIR is a face over it.

## A face root is a tenant

There is no bundled copy of the specification. The definitions live in a tenant
that exists to hold them:

```bash
--8<-- "docs/guide/examples/snippets/face-roots.sh"
```

```
fhir-r5 392
fhir-r4 740
```

Those are `StructureDefinition` records, readable and searchable like any
other. The hospital and the insurer each declared a dependency on one of these,
which is how the shapes they validate against got there — the same mechanism
[the agreed vocabulary](zone-terminology.md) uses for the zone's code systems,
pointed at a different tenant.

This is the whole reason two releases coexist. There is no global "the FHIR
version this deployment runs". There is a tenant holding R5's definitions, a
tenant holding R4's, and each ordinary tenant declaring which it reads.

## Two releases, and what sits between them

The hospital reads R5's definitions and the insurer reads R4's, and the zone
they share speaks one of the two. Something has to convert, and the store's
answer is a tenant that exists to do it — which is
[across faces](zone-chain.md), because whose content is being converted is the
zone's question rather than the face's.

## The face decides what a refusal says

[The quick start](quick-start.md) showed the insurer refusing a `Coverage` shaped
the R5 way, and the refusal naming `4.0.1`. [Validation](validation.md) showed
the same wrong code refused by both
tenants, each naming its own release of the value set.

Both are the same fact in different clothes. A tenant validates against the
definitions it declared, and says which they were. Nothing in the request
selects a version, because the version is a property of the tenant rather than
of the call — which means a client cannot get it wrong, and an operator cannot
mis-route a request into the wrong release.

## What a face is not

It is not a translation layer you can point at anything. A face is a standard
the store has an implementation of, and today that means FHIR R4 and R5.

It is also not a compatibility promise between them. R4 and R5 disagree about
real things — `Coverage.payor` against `Coverage.insurer` is one you have
already seen refuse a write — and the store does not paper over that. It
converts what is convertible, refuses what is not, and names the version it
judged by. A store that silently made an R5 resource look like an R4 one would
be inventing clinical content, which is worse than a refusal a caller can act
on.

## What you would otherwise have written

A library dependency on one release of one standard, and a deployment per
customer that is on a different one.

Then the conversion, when a partner turns out to be a release behind: written
once as a special case, then again for the next type, then discovered to be
running in every service that touches that partner's data.

And the routing question underneath it — which version does this request mean —
answered by a header, or a path segment, or a config lookup, and got wrong at
least once by somebody who had no way of knowing it was a question.
