---
title: The standard moves
eyebrow: Guide
standfirst: >-
  What a record was validated under is recorded when it is accepted, beside
  the record rather than inside it. Stock written under an old shape stays
  countable, findable and convertible when the shape moves, and data newer
  than the tenant understands is refused rather than half-read.
template: essay.html
---

A record written this year has to be readable in three, when the profile that
shaped it has moved twice and nobody who wrote it still works here. Without an
answer to that, an upgrade is either a migration ceremony or a silent
misreading. The scene is
[US-DBO-STANDARD-MOVES](../arc42-003-context/user-stories/us-dbo-standard-moves.md),
walked by
[`TheStandardMovesUnderTheDataIT`](https://github.com/jengu-net/dbo/blob/main/samples/spring-boot-server-app/src/test/java/cloud/jengu/dbo/samples/stories/TheStandardMovesUnderTheDataIT.java).

## A clinic that carries its own profile

A profile is ordinary content, so revising it is a write rather than a
release. The clinic only has to hold the types a profile is made of:

```java
--8<-- "samples/spring-boot-server-app/src/test/java/cloud/jengu/dbo/samples/stories/TheStandardMovesUnderTheDataIT.java:pack-tenant"
```

and the profile arrives the way any record does:

```java
--8<-- "samples/spring-boot-server-app/src/test/java/cloud/jengu/dbo/samples/stories/TheStandardMovesUnderTheDataIT.java:pack"
```

From then on an observation that claims the profile is validated against it,
and the store records which version of it the observation was accepted under.

## What the store guarantees

- **Two facts, kept apart.** An accepted record carries the pack version it was
  validated under as a stamp the store derives, beside the conformance claim it
  makes, which stays an unversioned canonical: conversion moves a record's
  shape and never its identity. Echoing a served record back does not
  accumulate a second stamp.
- **The pack moves, and so does the stamp** — on the next accept. The version
  written under the old pack keeps its own stamp in history.
- **Stock is countable and findable by version bound**, so *what do I still
  have below the current major* is a query rather than a scan somebody writes.
- **Stock is convertible in place**, by the clinic's own map, resumably: a
  re-run finds only what is still behind, an object no map covers is named and
  left with its reason, and a converted form handed back from outside is
  validated, re-stamped and version-checked.
- **What it refuses, it refuses when it can.** A shape whose version has no
  leading integer is refused when the shape arrives. Data stamped above what
  the tenant understands is stored and refused when read, by id, naming the
  stamp and what the pack now declares — and a search whose answer would
  contain it is refused whole rather than answered short.

The [joins table](../arc42-003-context/user-stories/us-dbo-standard-moves.md#joins)
names the test behind each.

## What the store cannot do yet

- **An application has no step for this.** The story writes the profile and
  the stock on the clinic's records surface. A step could be declared to write
  a `StructureDefinition` like any other type, and the sample applications
  declare none, so how a profile arrives through work is not shown here.
- **No version-ahead.** Writing at a storage-target shape while serving the
  operating one, and down-converting on read, is deliberately not built.
- **Somebody has to run the conversion.** Conformance is a fact about the past,
  so the store accumulates stamped stock and does not migrate itself.
