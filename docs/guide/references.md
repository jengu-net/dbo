---
title: References
eyebrow: Guide
standfirst: >-
  Records point at each other, the store extracts those pointers on write so
  they can be searched — and a reference is deliberately not a foreign key.
template: essay.html
---

An observation is about somebody. A claim is against a coverage. Almost nothing
in a real record set stands alone, and how a store treats the pointers between
records decides what you can ask it and what it will refuse you.

## Finding what belongs to a record

The bundle in [Bundles and transactions](transactions.md) wrote an observation
about a patient. Ask for it by the
reference:

```bash
--8<-- "docs/guide/examples/snippets/reference-search.sh"
```

```
height
```

The reference was extracted when the observation was written, and rebuilt from
the payload every time it is written again. You did not index anything and
there is no join table: the edge is derived from the record, so it cannot
disagree with it.

That last property is worth more than it sounds. A denormalised link column
maintained beside the document is a second copy of a fact, and the two drift
the first time something writes one without the other.

## A reference is not a foreign key

Now the part people expect to go the other way. Write an observation about a
patient this store has never heard of:

```bash
--8<-- "docs/guide/examples/snippets/reference-unheld.sh"
```

```
201
```

Accepted. That is deliberate, and it is not laxity.

A reference in a record of this kind routinely points somewhere the store does
not hold: a practitioner at another organisation, a patient whose record lives
in the tenant next door, an identifier in a national registry. A store that
refused those writes would be insisting it is the whole world, and the usual
consequence is that senders strip the references to get their data accepted —
which loses the very information the check was protecting.

So the store keeps the pointer as written and lets you ask about it.

## Where closure *is* enforced

There is one place the store does insist: a restore that would leave records
pointing at nothing inside the archive is refused rather than warned about.

The difference is the difference between the two situations. A live tenant is
part of a larger world and its references reach out of it by design. An archive
claims to be a complete account of a tenant — that is the whole of what
[Export and import](export-and-import.md) said a backup is — and one that has lost
half of what it refers to is not a complete account, it is a corrupted one that
would restore quietly.

## Pointing by identifier

A reference can name what the sender knows rather than an id only this store
has:

```json
"subject": { "identifier": { "system": "urn:rl:nid", "value": "RL-0001" } }
```

This matters when the sender genuinely does not have your ids — which, for
anything arriving from another organisation, is most of the time.
[Records](records.md) made the same point about writing a record by identity
instead of by id; this is that idea applied to the links between them.

## What an integrator writes

All three kinds of reference, and the call that asks what belongs to somebody:

```java title="sample/src/main/java/cloud/jengu/dbo/sample/Observing.java"
--8<-- "sample/src/main/java/cloud/jengu/dbo/sample/Observing.java"
```

Notice what is not there. No edge to insert beside the document, no lookup
before the write to find out whether the subject exists, and no branch on the
answer — because a reference that names a number rather than an id is answered
by the store on the way in.

## What you would otherwise have written

Foreign keys, and then the discovery that a third of your inbound references
point outside the database — so the constraints come off, or the rows get
rejected, or somebody writes a placeholder row to satisfy them.

An index on every column you might search a relationship by, maintained by
hand, one migration behind the schema.

And a nightly job checking for orphans, which is the thing a reference derived
from the payload cannot become, because there is nowhere for the copy to drift
from.
