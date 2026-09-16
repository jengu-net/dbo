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
--8<-- "docs/guide/examples/check.sh:face-roots"
```

```
fhir-r5 392
fhir-r4 740
```

Those are `StructureDefinition` records, readable and searchable like any
other. The hospital and the insurer each declared a dependency on one of these,
which is how the shapes they validate against got there — the same mechanism
chapter seven used for the zone's code systems, pointed at a different tenant.

This is the whole reason two releases coexist. There is no global "the FHIR
version this deployment runs". There is a tenant holding R5's definitions, a
tenant holding R4's, and each ordinary tenant declaring which it reads.

## Conversion happens once, in a tenant that exists to do it

Now the case this arrangement is really for.

The zone speaks R5. The insurer speaks R4 and declares the zone as a
dependency. Somebody has to convert — and the obvious answers are both bad.
Converting in the zone means the zone holds a copy per face of everything.
Converting in each tenant means every R4 member of that zone does the same work
on the same content, separately, forever.

The store does neither. It declares a **projection**: the zone, as seen on a
face. Nobody wrote it and nobody asked for it — it exists because an R4 tenant
declared an R5 zone:

```bash
--8<-- "docs/guide/examples/check.sh:projection"
```

```
4.0.1
5.0.0
```

`rl-on-r4` is a tenant. It has a database, a bring-up, a surface, a
capability statement — everything any other tenant has, because a thing that is
a tenant should be one rather than a second mechanism that will drift. It reads
the zone, converts what it finds, and serves the result to every R4 tenant in
that zone.

Add a second R4 insurer tomorrow and no more conversion happens. It reads the
projection that is already there.

The insurer's own request does not know any of this. It asked its own tenant
for a code and got an answer, and chapter seven's `Spell Damage` lookup is the
proof — that code was written to an R5 tenant and read from an R4 one.

## The face decides what a refusal says

Chapter two showed the insurer refusing a `Coverage` shaped the R5 way, and the
refusal naming `4.0.1`. Chapter six showed the same wrong code refused by both
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
