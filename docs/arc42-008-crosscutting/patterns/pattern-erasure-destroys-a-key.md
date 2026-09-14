---
title: "Erasure Destroys a Key"
eyebrow: Pattern
standfirst: >-
  Every version kept immutably is what makes an audit trail worth anything. A
  person may still require that their data be gone. Both are kept, because
  erasure destroys a key rather than rewriting anything.
pattern: 18
template: essay.html
---

**Intent — satisfy the right to erasure without weakening the record of what
happened.**

## You are

Holding two obligations that point in opposite directions, and most systems
quietly pick one.

The first says nothing may be altered or removed: that is what makes history
and an audit trail evidence rather than assertion. The second says a person
may require that their data be gone, and means it.

## The question

How do you remove a person from records that may not be altered?

## The forces

- Editing entries to remove a person breaks the very property that made them
  worth keeping.
- Keeping everything and calling the obligation impractical is not an answer
  available to you.
- Erasure must be provable, and must be provable as complete, or it is worth
  nothing to the person who asked.

## Therefore

**Do not touch the records. Destroy the key the person's identifying material
was sealed with.**

--8<-- "assets/diagrams/pattern-erasure-destroys-a-key.svg"

<p class="diagram-caption">The right-hand column keeps every row. That is the
point: nothing is removed, and the person is nonetheless gone.</p>

The entries remain, complete and in order, and the chain still verifies. What
happened is still provable years later. Who it happened to is gone, and cannot
be recovered by anybody, including the party running the deployment.

Two further properties turn it from a gesture into an obligation. Ask for the
erasure as work and answer it with a record, so there is a receipt saying what
was found, how far it got and when — and so that asking twice joins the
attempt already open rather than opening a second account of one erasure. And
let a subject you never held close that record saying so, because an unknown
person is not a refusal.

Where the records were pseudonymous to begin with, re-identifiable only
through one door, this is a smaller step than it sounds.

## What each reader gets

- **A regulator** sees both obligations met, with a receipt for the erasure
  and an intact trail beside it.
- **A security officer** can state that destruction is cryptographic and not
  reversible by any role.
- **An administrator** answers a request by starting work, and can show how far
  it got.
- **The business** can answer the hardest question in a due-diligence
  questionnaire with a mechanism instead of a process document.

## Relations

- **Builds on** — [The Person Is a Key](pattern-the-person-is-a-key.md); [The Trail Is Records](pattern-the-trail-is-records.md); [A Sweep Is Found, Not Started](pattern-a-sweep-is-found-not-started.md).
- **Related work**
    - [Crypto-shredding](https://en.wikipedia.org/wiki/Crypto-shredding), descended from [Boneh and Lipton (1996)](https://www.usenix.org/conference/6th-usenix-security-symposium/revocable-backup-system).
    - [Article 17 of the GDPR](https://gdpr-info.eu/art-17-gdpr/), the right this reconciles with an unalterable record.
