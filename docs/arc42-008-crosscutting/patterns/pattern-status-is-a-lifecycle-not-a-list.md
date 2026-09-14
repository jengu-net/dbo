---
title: "Status Is a Lifecycle, Not a List"
eyebrow: Pattern
standfirst: >-
  Declared, provisioned, brought up, served, and one day taken away. Only the
  middle of that is a state a running node reports, and the state worth
  knowing about is the one a list of served tenants leaves out.
template: essay.html
---

**Intent — make the absent case askable, because the tenant in trouble is the
one that is not in the list.**

## You are

Operating a system that serves many tenants, and watching it through the thing
it naturally tells you: which tenants it is currently serving.

That list is correct and it is the wrong instrument. A tenant that was
declared and never came up is not in it. A tenant that stopped is not in it.
The list is a list of things that are fine.

## The question

How do you notice the tenant that is missing from the list of tenants?

## The forces

- A node can only report what it has: the tenants it has brought up.
- The interesting states happen before serving and after it — declared but not
  provisioned, provisioned but not up, withdrawn.
- An operator under pressure reads the list they have, not the one they should
  have derived.

## Therefore

**Model the whole lifecycle, and make "why is this one not being served" a
question with an answer.**

--8<-- "assets/diagrams/pattern-status-is-a-lifecycle-not-a-list.svg"

<p class="diagram-caption">The interesting state sits outside the served band,
which is exactly why a list of served tenants cannot show it.</p>

Two distinctions carry most of the value. Coming up and keeping up are not one
queue, so a tenant failing to start does not sit behind the ordinary work of
tenants that are running. And a tenant is ready when the definitions it
genuinely needs have arrived, not when a process started — so readiness is a
statement about what it can serve rather than about what was launched.

The end of the lifecycle is declared too. Taking a tenant away is a stated
operation with a record, not a row disappearing from a table.

## What each reader gets

- **A regulator** can be told the state of a tenant at a date, including the
  period it was not being served.
- **A security officer** sees withdrawal as a recorded act rather than an
  absence.
- **An administrator** gets an answer to why something is not up, instead of
  its absence from a list.
- **The business** can tell a customer what stage their onboarding is actually
  at.

## Relations

- **Builds on** —
  [One Declared Set, Applied](pattern-one-declared-set-applied.md);
  [The Tenant Is a Database](pattern-the-tenant-is-a-database.md).
- **Composed of** — Control Bus, from Enterprise Integration Patterns.
- **Written up in** — [Running it](../running-it/README.md) and
  [Deployment](../../arc42-007-deployment/README.md). Proven by
  `REQ-DBO-TEN-COMING-UP-AND-KEEPING-UP-ARE-NOT-ONE-QUEUE`,
  `REQ-DBO-TEN-READY-WHEN-ITS-CRITICAL-DEFINITIONS-ARRIVED` and
  `REQ-DBO-TEN-SERVED-FROM-WHAT-WAS-APPLIED`.
