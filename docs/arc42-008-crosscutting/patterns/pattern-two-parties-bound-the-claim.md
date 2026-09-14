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

The rule generalises, and it is the same rule in three other places in the
store. A jurisdiction declares which identifier systems and which brokers are
admissible, and a tenant chooses within that set. An operator's override is
bounded by what the tenant declared. A lane's entitlement is stated when the
lane is provisioned. In every case the outer party declares the set, the inner
one chooses within it and may narrow, never widen.

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

- **Builds on** — [Nobody Is Pushed](pattern-nobody-is-pushed.md);
  [A Role Is a Period on a Record](pattern-a-role-is-a-period-on-a-record.md).
- **Makes possible** — a country is a zone.
- **Related work** — least privilege, and separation of duties, where the
  authority to act is deliberately split across two declarations.
- **Written up in** — [Processes and work](../processes-and-work/README.md)
  and [Declared rules](../declared-rules/README.md). Proven by
  `REQ-DBO-PROC-CLAIM-IS-THE-INTERSECTION`,
  `REQ-DBO-PROC-ENTITLEMENT-IS-DECLARED-NOT-DEFAULTED` and
  `REQ-DBO-PROC-THE-LANE-HAS-TWO-BOUNDS`.
