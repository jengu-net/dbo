---
title: "The Person Is a Key"
eyebrow: Pattern
standfirst: >-
  Identifying material is encrypted where it is written, with a key belonging
  to that person. So every copy the store makes of itself carries ciphertext
  because of where the encryption happens.
template: essay.html
---

**Intent — make protection of identity a property of the boundary, so no
downstream path has to be written correctly for it to hold.**

## You are

Holding records about people, and making copies of them constantly, whether
you think of it that way or not. A backup. An export. A change feed. A
dependent copy in another tenant. A support extract. A test dataset somebody
made from production last spring.

Each of those is a path, and the usual arrangement asks every path to protect
identity on the way past.

## The question

How many paths have to be written correctly before identity is protected?

## The forces

- Encrypting at the edge protects one path and leaves every other one to be
  remembered.
- Encrypting the whole store protects nothing from anybody who can read it
  normally, which is everybody who is supposed to.
- Identity has to remain usable: the same person must still be findable, and
  re-identifiable by somebody entitled to it.

## Therefore

**Seal the identifying elements at the point of writing, with a key belonging
to that person, and make everything downstream a copy of what was already
sealed.**

--8<-- "assets/diagrams/pattern-the-person-is-a-key.svg"

<p class="diagram-caption">The answer to "how many paths" is one. The rest
inherit.</p>

Re-identification then has one door rather than many, so it is a thing that
can be gated and recorded. The store can still do its work over the sealed
material — matching a person exactly, deciding what a given recipient is
allowed to see — without the material being in the clear anywhere it did not
have to be.

And because the key belongs to the person rather than to the tenant or the
deployment, one person's identity can be removed without touching anybody
else's, which is what the next pattern rests on.

## What each reader gets

- **A regulator** is shown protection that does not depend on each export path
  having been reviewed.
- **A security officer** gets one boundary to defend and one door to monitor,
  instead of an inventory of paths.
- **An administrator** can hand out backups and extracts without them being
  personal data in the clear.
- **The business** can let a support engineer work on real shapes of data
  without seeing who it is about.

## Relations

- **Builds on** — [Property, Not Policy](pattern-property-not-policy.md);
  [The Tenant Is a Database](pattern-the-tenant-is-a-database.md).
- **Makes possible** —
  [Erasure Destroys a Key](pattern-erasure-destroys-a-key.md);
  [Leaving Is the Nightly Path](pattern-leaving-is-the-nightly-path.md).
- **Composed of** — Claim Check, from Enterprise Integration Patterns: the
  identity held aside, a reference travelling in its place.
- **Related work** — Boneh and Lipton (1996) on revocable storage through key
  destruction, which is the same mechanism read forwards.
- **Written up in** — [Data isolation](../data-isolation/README.md). Proven by
  `REQ-DBO-PDI-STRUCTURAL-VAULT`, `REQ-DBO-PDI-BLIND-OPERATIONS`,
  `REQ-DBO-PDI-EXACT-RESOLUTION` and
  `REQ-DBO-PDI-PLAINTEXT-IN-FLIGHT-LEAVES-NO-TRACE`.
