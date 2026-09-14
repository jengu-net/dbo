---
title: "The Trail Is Records"
eyebrow: Pattern
standfirst: >-
  Who read this, who changed it, on whose authority, and when — kept as
  ordinary records inside the system that produced them, append-only against
  everyone, the operator included.
pattern: 11
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

- A log that leaves the organisation is subject to somebody else's retention,
  and
  to the end of a contract.
- Evidence anybody can edit is a document, not evidence — and the party most
  able to edit it is the one running the deployment.
- Applications know things the database does not, so the trail has to be open
  to enrichment without becoming forgeable.

## Therefore

**Keep the trail as ordinary records where the events happened, and give it no
edit interface at all.**

--8<-- "assets/diagrams/pattern-the-trail-is-records.svg"

<p class="diagram-caption">The right-hand column is an arrangement, not a
retention setting. There is no operator, and no vendor, for whom an exception
exists.</p>

Make audit entries exempt from whatever write discipline is declared
for everything else: no update and no tombstone under any policy. Let a
retention sweep be the only thing that can remove one, and audit every removal
without retaining what was removed.

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
- **An administrator** backs up and restores the evidence along with the data,
  because it is part of it.
- **The business** can leave a hosting provider without leaving its evidence
  behind.

## Relations

- **Builds on** — [The Tenant Is a Database](pattern-the-tenant-is-a-database.md); [A Type Declares What It Is](pattern-a-type-declares-what-it-is.md); [Work Is the Reason](pattern-work-is-the-reason.md).
- **Makes possible** — [Carrying Is Not Reading](pattern-carrying-is-not-reading.md); [Asked, Not Scanned](pattern-asked-not-scanned.md); [Erasure Destroys a Key](pattern-erasure-destroys-a-key.md).
- **Composed of** — [Wire Tap](https://www.enterpriseintegrationpatterns.com/patterns/messaging/WireTap.html) and [Message History](https://www.enterpriseintegrationpatterns.com/patterns/messaging/MessageHistory.html), from [Enterprise Integration Patterns](https://www.enterpriseintegrationpatterns.com/).
- **Related work**
    - [Clark and Wilson (1987)](https://doi.org/10.1109/SP.1987.10001), where the audit of each transformation procedure is part of the integrity mechanism rather than an accessory to it.
    - [Article 30 of the GDPR](https://gdpr-info.eu/art-30-gdpr/), which is the obligation this answers in European law.
