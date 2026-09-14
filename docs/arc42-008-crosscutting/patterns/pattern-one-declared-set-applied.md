---
title: "One Declared Set, Applied"
eyebrow: Pattern
standfirst: >-
  Value sets, profiles, search parameters, which tenants a deployment serves,
  whether a step is automated. One declared set, read from a repository and
  applied to a tenant, then the next, then the appliance.
pattern: 23
template: essay.html
---

**Intent — make configuration a thing you declare once and apply, rather than
a thing you perform per environment.**

## You are

Running the same software for several tenants, in several places, at several
ages. A new tenant is being brought up this week. One has been serving for two
years. There is an appliance in a building somewhere that syncs when it can.

All of them need the same definitions. All of them got them by somebody doing
it, in a slightly different order, on a different day.

## The question

How does the same intended configuration reach a new tenant, an old one and an
appliance without being rewritten for each?

## The forces

- Applying by hand produces environments that differ in ways nobody can
  enumerate.
- A migration script assumes a starting state, and the appliance's starting
  state is whatever it was when it last had a network.
- Applying has to be safe to repeat, because it will be repeated — by a
  retry, by a schedule, and by somebody who is not sure it worked.

## Therefore

**Declare the set once, read it from a repository, and apply it as a run that
converges.**

--8<-- "assets/diagrams/pattern-one-declared-set-applied.svg"

<p class="diagram-caption">Applying is work, so it leaves a record. What was
applied, to what, and how far it got are the same kind of question as any
other run.</p>

Because applying is a sweep rather than a script, the starting state does not
have to be known — which is what makes the two-year-old tenant and the
appliance the same case as the new one. Because it is a run, there is a
receipt: a pass says what it did, and a redeclaration is noticed rather than
silently re-applied.

The withdrawal side is declared too. Something removed from the set does not
linger because nobody wrote the removal step.

## What each reader gets

- **A regulator** can be shown what a tenant is serving and that it matches
  what was declared for it.
- **A security officer** reviews a declared set rather than the state of each
  environment.
- **An administrator** stops maintaining a runbook of the order things must be
  done in.
- **The business** can promise that every deployment behaves the same, and
  demonstrate it per tenant.

## Relations

- **Builds on** —
  [A Sweep Is Found, Not Started](pattern-a-sweep-is-found-not-started.md);
  [Engine and Faces](pattern-engine-and-faces.md).
- **Makes possible** —
  [Status Is a Lifecycle, Not a List](pattern-status-is-a-lifecycle-not-a-list.md).
- **Composed of** — Control Bus, from Enterprise Integration Patterns.
- **Related work** — declarative configuration and convergent reconciliation,
  where desired state is stated and the system moves towards it.
- **Written up in** — [Running it](../running-it/README.md). Proven by
  `REQ-DBO-PROC-CONFIG-READ-FROM-A-SOURCE`,
  `REQ-DBO-TEN-A-DECLARED-SET-IS-APPLIED-AS-ONE-PASS`,
  `REQ-DBO-TEN-APPLYING-IS-ASKED-FOR-AND-RECORDED` and
  `REQ-DBO-PROC-CONFIG-WITHDRAWAL-IS-DECLARED`.
