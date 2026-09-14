---
title: "The Trail Is Records"
eyebrow: Pattern
standfirst: >-
  Who read this, who changed it, on whose authority, and when — kept as
  ordinary records in the tenant's own store, append-only against everyone
  with the vendor included.
template: essay.html
---

**Intent — make the evidence survive the infrastructure that produced it, and
be beyond editing by the party that runs it.**

## You are

Obliged to be able to say, years from now, who did what to a record and on
whose authority. The usual arrangement writes that to a log, which is shipped
to an aggregator, which is operated by somebody else and retained on their
schedule.

The question that decides whether any of it is worth anything is not how
completely you log. It is whether the evidence is there on the day somebody
finally asks.

## The question

Where does audit have to live for it to still be evidence when the machines
that produced it have been replaced twice?

## The forces

- A log that leaves the tenant is subject to somebody else's retention, and
  to the end of a contract.
- Evidence anybody can edit is a document, not evidence — and the party most
  able to edit it is the one running the deployment.
- Applications know things the database does not, so the trail has to be open
  to enrichment without becoming forgeable.

## Therefore

**Keep the trail as ordinary records in the tenant's own store, and give it no
edit interface at all.**

--8<-- "assets/diagrams/pattern-the-trail-is-records.svg"

<p class="diagram-caption">The right-hand column is an arrangement, not a
retention setting. There is no operator, and no vendor, for whom an exception
exists.</p>

Audit entries are exempt from whatever write discipline the tenant declares
for everything else: no update and no tombstone under any policy. Retention's
sweep is the only thing that can remove one, and every removal is itself
audited, without retaining what was removed.

Applications may contribute business-level events, so the trail can carry what
happened in a domain rather than only what happened at the database. What an
application cannot do is lie about two fields: the actor is stamped from the
validated token and the time from the store's own clock, whatever the caller
claimed. The trail can be enriched. It cannot be backdated or written in
somebody else's name.

## What each reader gets

- **A regulator** is shown evidence held by the regulated organisation itself,
  under its own declared retention, not by a supplier.
- **A security officer** can state that no role, including the operator's, can
  alter an entry.
- **An administrator** backs up and restores audit with the tenant, because it
  is part of the tenant.
- **The business** can leave a hosting provider without leaving its evidence
  behind.

## Relations

- **Builds on** — [Work Is the Reason](pattern-work-is-the-reason.md);
  [A Type Declares What It Is](pattern-a-type-declares-what-it-is.md);
  [The Tenant Is a Database](pattern-the-tenant-is-a-database.md).
- **Makes possible** —
  [Carrying Is Not Reading](pattern-carrying-is-not-reading.md);
  [Asked, Not Scanned](pattern-asked-not-scanned.md); erasure destroys a key.
- **Composed of** — Wire Tap and Message History, from Enterprise Integration
  Patterns.
- **Related work** — Clark and Wilson (1987), where the audit of each
  transformation procedure is part of the integrity mechanism rather than an
  accessory to it.
- **Written up in** — [Records you can rely on](../records-you-can-rely-on/README.md).
  Proven by `REQ-DBO-POL-AUDIT-AS-RECORDS`,
  `REQ-DBO-POL-AUDIT-UNCONDITIONALLY-APPEND-ONLY`,
  `REQ-DBO-POL-ACTOR-FROM-AUTHORITY` and `REQ-DBO-POL-CUSTOM-AUDIT-EVENTS`.
