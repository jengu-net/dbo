---
title: "The Tenant Is a Database"
eyebrow: Pattern
standfirst: >-
  Each organisation's records live in a database of their own, not behind a
  filter over a shared one. That difference decides what happens the day
  somebody writes a query with a bug in it.
pattern: 2
template: essay.html
---

**Intent — make separation a fact about where the data is, not a rule every
query has to remember.**

## You are

Serving several organisations from one system. Perhaps they are hospitals,
perhaps laboratories, perhaps one customer's several countries. Each believes
their records are theirs alone, and each has written that belief into a
contract with you.

The convenient arrangement is one database with a column saying which
organisation each row belongs to, and a rule that every query must filter on
it.

## The question

What is your separation actually worth, given that it has to be re-applied
correctly by every query anybody ever writes?

## The forces

- A shared database is cheaper to run and easier to report across.
- The filter is a rule, and rules are re-applied by people under time
  pressure, in paths written after the rule was made.
- The failure is silent and asymmetric: nothing breaks when a filter is
  forgotten, it simply returns more than it should, to somebody who has no way
  of knowing.
- Deleting one organisation's data from a shared store is a project. Deleting
  a database is an afternoon.

## Therefore

**Give each tenant its own database, so a query that forgets has nowhere to
go.**

--8<-- "assets/diagrams/pattern-the-tenant-is-a-database.svg"

<p class="diagram-caption">Neither column depends on the query being correct.
The difference is what an incorrect one can reach.</p>

Two sentences become worth something. *Your data is separated from theirs*
stops being a description of a convention and becomes a description of the
storage. And *your data is gone* means a database was dropped, which is a
thing that can be witnessed, rather than a deletion that has to be trusted.

The cost is real and worth stating: more databases to provision, back up and
upgrade, and cross-tenant reporting that has to be built deliberately rather
than falling out of a query. Bringing a tenant up is therefore a first-class
operation here rather than an administrative afterthought.

## What each reader gets

- **A regulator** is shown an arrangement rather than a policy, and erasure of
  a whole organisation is a demonstrable act.
- **A security officer** knows the blast radius of a bad query, a bad
  migration or a stolen connection string is one tenant.
- **An administrator** can restore one organisation without touching the rest,
  and move one to different hardware.
- **The business** can answer the isolation question in a tender without
  reaching for the word "logical".

## Relations

- **Builds on** — [Property, Not Policy](pattern-property-not-policy.md).
- **Makes possible** — [Committed with the Change](pattern-committed-with-the-change.md); [The Trail Is Records](pattern-the-trail-is-records.md); [The Person Is a Key](pattern-the-person-is-a-key.md); [Leaving Is the Nightly Path](pattern-leaving-is-the-nightly-path.md); [Status Is a Lifecycle, Not a List](pattern-status-is-a-lifecycle-not-a-list.md).
- **Related work**
    - [Multitenancy models](https://learn.microsoft.com/en-us/azure/architecture/guide/multitenant/considerations/tenancy-models), which set database-per-tenant against a shared schema with a discriminator column.
