# US-DBO-A-TENANT-IS-ERASED — a clinic is gone, and so is everything it held

> A clinic closes, or a contract ends with a demand that nothing be kept. The
> question is not whether its records stop answering — a retraction does that,
> and a retraction is meant to be undone — but whether **the content itself is
> gone from where it was**.
>
> The answer here is where it was kept. A tenant is a database of its own, and
> everything it held is in it: its records, and content that is identifying as
> a whole, like a recording or a scanned referral. Erasure is dropping that
> database, so nothing survives because a sweep forgot a second system.

## The scene

An operator erases a clinic that held a recording and records. It is an
operator act, deliberately, and nothing a tenant's declaration disappearing
does touches data: retracting stops serving; erasing removes. The clinic is
retracted first, and then erased at the deployment's erasure door.

## Who may erase, and when

The door is `POST /runtime/erase/<code>`, and it answers only the token the
deployment gave for erasure. The operator's own token, which reads what a node
serves, is refused there, because the one act nobody takes back is not
something reading the node entitles anybody to. An erasure states its reason,
which the management tenant's record of it keeps beside who asked; one that
states none is refused for it. A clinic still declared is refused, because the
next pass would bring it up again, empty, under the same code. Asking again for
an erasure that happened answers the same, so an operator whose request timed
out can simply ask again.

## The content goes with the database

A recording is kept in the tenant's own database rather than beside it, so
dropping the database takes it for the same reason the records go. What is
checked is the server: the database is gone, not merely the store's answer
about it.

## For any name a clinic can have

A clinic's code may be long and hyphenated, and the drop considers every name
a declaration accepts — a tenant that could be opened and never erased would
be the failure that looks like success. A name no declaration would accept is
refused by name.

## What the store cannot do yet

Nothing this scene needs. It is walked in Rowling Land, on a clinic the story
declares and erases itself.

## Joins

The promises this story rests on, projected from the catalogue rather than
written here: a story claims no evidence, and a leg is what its promise's own
citations say it is.

<!-- story:begin — generated from the promise catalogue; do not edit. Regenerate: ./gradlew :core:harness:promiseProjection -->

| Promise | Says | Status |
|---|---|---|
| `REQ-DBO-TEN-ERASURE-BY-DROP` | Dropping a tenant's database and blob storage removes all its data — including durable workflow history and feed state. | PROVEN |
| `REQ-DBO-OPS-TENANT-BLOBS-ARE-TENANT-DATA` | Binary content a tenant holds is kept in that tenant's own database and returned byte for byte, needing no credential and no provisioning of its own; erasure-by-drop removes it with the tenant, because it is in what gets dropped rather than in a second place something has to reach. | PROVEN |
| `REQ-DBO-TEN-ERASURE-HAS-A-DOOR-OF-ITS-OWN` | Erasing a tenant is asked at a door of its own, behind a credential the deployment gives for erasure and for nothing else: the operator's own token reads the node and erases nothing. Every erasure states its reason, which is recorded beside who asked; a tenant still declared, and the management tenant, are refused; and asking again for an erasure that already happened answers the same. | PROVEN |

Coverage: {PROVEN=3} — a leg marked PLANNED cites a promise that exists and is not yet cited by any test.
<!-- story:end -->
