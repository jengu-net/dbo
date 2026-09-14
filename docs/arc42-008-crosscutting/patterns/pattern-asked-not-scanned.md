---
title: "Asked, Not Scanned"
eyebrow: Pattern
standfirst: >-
  Evidence you cannot find is not evidence. So the store answers exactly what
  was asked, or refuses and says what would have worked. It never
  approximates.
template: essay.html
---

**Intent — never answer a narrower question with a wider result, because that
is the failure the caller cannot see.**

## You are

Holding a large trail, or a large anything, and being asked a narrow question
about it. When was this tenant suspended, and by whom. Which entries of this
kind, coded in this particular system.

Some of what the question names is indexed. Some of it is not.

## The question

What do you do with the part of a question you cannot answer exactly?

## The forces

- Returning a bounded page of a busy trail makes a rare entry that exists look
  like one that never happened.
- Silently widening the question returns rows that look exactly like the ones
  the caller asked for. They cannot tell.
- Refusing everything unsupported leaves the caller guessing, which is its own
  failure.

## Therefore

**Refuse the part you cannot answer exactly, and name it.**

--8<-- "assets/diagrams/pattern-asked-not-scanned.svg"

<p class="diagram-caption">On this surface above all, the caller cannot detect
a wider answer, because the extra rows are indistinguishable from the wanted
ones.</p>

The other half is making narrow questions possible in the first place, so the
refusal is rare. The trail can be narrowed six ways: who acted, which record
it was about, what action, when, which run it belonged to — that one turns a
journey across organisations into a single question — and what kind of event
it was.

What is searchable is deliberately a short list. The kind is searchable
because the store lifts that code out of a contributed document when the entry
is written. Everything else a domain contributed rides opaquely inside the
document and is not indexed, so it is not searchable, and the list of
parameters says so by leaving it out.

A refusal is also not a dead end: it names the search that would have worked.
A caller told only that something is unsupported can do nothing but guess.

## What each reader gets

- **A regulator** can trust that a result set is the answer to the question
  they asked.
- **A security officer** does not have to reason about whether a permissive
  match widened an investigation's scope.
- **An administrator** reads a capability statement that is honest about what
  can be narrowed.
- **The business** avoids the worst class of compliance answer: one that was
  wrong and looked right.

## Relations

- **Builds on** — [The Trail Is Records](pattern-the-trail-is-records.md);
  [A Type Declares What It Is](pattern-a-type-declares-what-it-is.md).
- **Related work** — none named. This is the store's own stance, against the
  common convention of best-effort matching.
- **Written up in** — [Records you can rely on](../records-you-can-rely-on/README.md)
  and [Finding things](../finding-things/README.md). Proven by
  `REQ-DBO-SRCH-STRICT-BY-DEFAULT`, `REQ-DBO-SRCH-HONEST-CAPABILITY` and
  `REQ-DBO-PROC-REFUSED-IS-NOT-UNANSWERED`.
