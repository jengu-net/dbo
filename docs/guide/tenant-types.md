---
title: Kinds of tenant
eyebrow: Guide
standfirst: >-
  Five of them appear in this guide and four exist to do a job for the others —
  but every one is an ordinary tenant, brought up the same way, answering on
  the same surfaces.
template: essay.html
---

By now you have met all of them without being told they were a set. This page
is the set, because a reader who has seen `mom`, `fhir-r5`, `rl` and `rl-on-r4`
go past in four different chapters is owed one place that says how they relate.

| It is called | What it holds | Where it is covered |
|---|---|---|
| an ordinary tenant | an organisation's own records | [Tenants](tenants.md) |
| the managing tenant | what this deployment was told to serve | [Tenants](tenants.md) |
| a face root | one release of a standard, as records | [The face](the-face.md) |
| a zone | a jurisdiction's rules and vocabulary | [A zone is a jurisdiction](zones.md) |
| a projection | a zone as seen on another face | [Across faces](zone-chain.md) |

## The thing they have in common is the point

None of these is a mechanism. Each is a tenant: a database of its own, brought
up by the same path, with the same history, the same trail, the same surfaces
and the same rules about who may write what.

That is a design decision and it could have gone the other way. The
specification could have held a version's definitions in a bundled package, the
deployment's own configuration in a table, and a jurisdiction's terminology in a
config file — three mechanisms, three formats, three ways to ask what is in
them, three things to back up separately and three that can be out of step with
the records they govern.

Instead there is one mechanism, and the consequences fall out for free. You can
query a face root to see what definitions your tenant validates against. The
managing tenant's history tells you when a tenant was declared and by whom,
because it is history, and the rule that nothing is overwritten is not suspended
for configuration. An archive of a zone is an archive, produced by the same
door as any other.

## Only one of them is created for you

The projection is the exception worth knowing about. Nobody declares
`rl-on-r4` — it appears because an R4 tenant declared a dependency on an R5
zone, and something had to convert once rather than per member. It is still an
ordinary tenant when it arrives, with a database and a surface you can ask.

Everything else in the table is a file somebody wrote.

## What you would otherwise have written

A package manager for specification versions, a configuration store for what the
deployment serves, and a terminology distribution for what a jurisdiction
mandates — each with its own format, its own update path, its own failure mode
and its own answer to *what is in there right now*.

Then the fourth thing: whatever reconciles them, because they are all describing
the same records and none of them can see the others.
