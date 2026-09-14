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

Two properties make it an obligation rather than a gesture. The erasure is
asked for as work and answered by a run, so it has a receipt saying what was
found, how far it got and when — and asking twice finds the run that already
exists rather than opening a second account of one erasure. And a person the
store never held closes the run saying so, because an unknown subject is not a
refusal.

Because audit entries are pseudonymous to begin with, re-identifiable only
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

- **Builds on** — [The Person Is a Key](pattern-the-person-is-a-key.md);
  [The Trail Is Records](pattern-the-trail-is-records.md);
  [A Sweep Is Found, Not Started](pattern-a-sweep-is-found-not-started.md).
- **Related work** — crypto-shredding, descended from Boneh and Lipton's
  revocable backup (1996); the right to erasure in European data-protection
  law.
- **Written up in** — [Data isolation](../data-isolation/README.md). Proven by
  `REQ-DBO-PDI-CRYPTO-SHREDDING`, `REQ-DBO-PDI-ERASURE-IS-A-RUN`,
  `REQ-DBO-PDI-ERASURE-SAYS-HOW-FAR-IT-GOT` and
  `REQ-DBO-PDI-UNFINDABLE-AFTER-ERASURE`.
