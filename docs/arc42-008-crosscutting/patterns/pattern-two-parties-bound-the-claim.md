---
title: "Two Parties Bound the Claim"
eyebrow: Pattern
standfirst: >-
  What a participant may take is the intersection of what its credential
  covers and what the step admits. Neither alone is enough.
pattern: 7
template: essay.html
---

**Intent — make the permitted set smaller than either declaration, so no
single place can widen it.**

## You are

Letting outside participants take work. Each holds a credential saying what it
was enrolled to do. Each step says who may perform it. Sooner or later the two
disagree — a step that admits any laboratory, a credential issued for one
department, a partner whose scope was set generously two years ago.

## The question

When the credential and the step disagree about whether this participant may
do this work, which one wins?

## The forces

- Checking only the credential lets a step be performed by somebody the
  process never contemplated.
- Checking only the step lets a step widen what a credential was ever meant to
  cover.
- Whichever is checked last tends to win by accident, which is a decision
  nobody made.
- A default of unrestricted is the one setting somebody always forgets.

## Therefore

**Neither wins. Take the overlap.**

--8<-- "assets/diagrams/pattern-two-parties-bound-the-claim.svg"

<p class="diagram-caption">The permitted set is smaller than either
declaration. It is never the union, and never whichever of the two happened to
be checked last.</p>

The rule generalises well beyond taking work, and it is worth applying
wherever two declarations meet. A jurisdiction says which identifier systems
and which brokers are admissible, and an organisation inside it chooses within
that set. An operator's override is bounded by what the customer declared. A
connection's entitlement is fixed when the connection is set up, not when it
is used. In every case the outer party declares the set, the inner one chooses
within it and may narrow, never widen.

The other half of the pattern is that there is no implicit unrestricted. An
entitlement that was never stated is empty, not unlimited, so the reach of a
remote participant never depends on a parameter somebody forgot to set.

## What each reader gets

- **A regulator** sees a reach that two independent declarations both had to
  permit.
- **A security officer** knows that over-generous scope on a credential cannot
  by itself open a new kind of work.
- **An administrator** provisions a partner without having to reason about
  every step that exists now or will exist later.
- **The business** can widen a partnership one step at a time, and narrow it
  the same way.

## Relations

- **Builds on** — [Nobody Is Pushed](pattern-nobody-is-pushed.md); [A Role Is a Period on a Record](pattern-a-role-is-a-period-on-a-record.md).
- **Makes possible** — [A Country Is a Zone](pattern-a-country-is-a-zone.md).
- **Related work**
    - [Saltzer and Schroeder (1975)](https://doi.org/10.1109/PROC.1975.9939) on least privilege and on separation of privilege, where authority is deliberately split so that no single declaration suffices.
    - [Park and Sandhu (2004)](https://doi.org/10.1145/984334.984339) on usage control, which generalises the several conditions a use has to satisfy at once.
