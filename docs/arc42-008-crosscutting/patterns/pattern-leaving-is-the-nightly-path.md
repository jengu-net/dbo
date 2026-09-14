---
title: "Leaving Is the Nightly Path"
eyebrow: Pattern
standfirst: >-
  One sealed archive, which the party operating the store cannot read and
  somebody else can verify. Getting out is the path that runs every night, not
  a project scoped when the relationship ends.
pattern: 19
template: essay.html
---

**Intent — make departure a rehearsed operation by making it the same
operation as backup.**

## You are

Holding a customer's data under a contract that will one day end, amicably or
otherwise. Everyone involved knows there is an export. Nobody has run it.

When the day comes, the export is written by the party being left, run for the
first time under time pressure, and checked by nobody — because the only
people who could check it against what was actually held are the ones
producing it.

## The question

How does a customer leave with everything, and know that it is everything?

## The forces

- A path exercised once is a path nobody has debugged.
- The party producing the archive has the least incentive to make it complete
  and the most ability to make it look complete.
- Backup and export want almost exactly the same thing, and are almost always
  built twice.

## Therefore

**Make the archive the backup. Seal it to the owner, and make it verifiable by
somebody who need not ask anybody.**

--8<-- "assets/diagrams/pattern-leaving-is-the-nightly-path.svg"

<p class="diagram-caption">The two columns differ in one thing, and it is not
the archive format. It is whether the path has been run before the day it is
needed.</p>

The archive carries a root over its contents, so its completeness is a thing
that can be checked rather than asserted, and both parties can attest to what
was handed over. It is encrypted to the owner's key, so the party operating
the store is holding something it cannot read. And an import refuses an
archive that arrives without attestation, which is the same property read from
the other end.

Because it is the nightly path, it is also the restore path, which means the
thing you would rely on in a disaster is the thing you have been exercising
every day.

## What each reader gets

- **A regulator** sees portability as an exercised capability rather than a
  contractual promise.
- **A security officer** knows the operator holds ciphertext it cannot open.
- **An administrator** has one path to monitor, and it is already monitored,
  because it is the backup.
- **The business** can answer the exit question in a tender without a
  migration-services quote attached.

## Relations

- **Builds on** — [The Tenant Is a Database](pattern-the-tenant-is-a-database.md); [The Person Is a Key](pattern-the-person-is-a-key.md).
- **Composed of** — [Document Message](https://www.enterpriseintegrationpatterns.com/patterns/messaging/DocumentMessage.html) and [Envelope Wrapper](https://www.enterpriseintegrationpatterns.com/patterns/messaging/EnvelopeWrapper.html), from [Enterprise Integration Patterns](https://www.enterpriseintegrationpatterns.com/).
- **Related work**
    - [Article 20 of the GDPR](https://gdpr-info.eu/art-20-gdpr/) on portability.
    - Restore testing, whose argument is the same one: a path exercised once is a path nobody has debugged.
