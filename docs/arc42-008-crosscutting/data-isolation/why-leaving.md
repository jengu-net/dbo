---
title: A tenant leaves and takes everything with it
eyebrow: Why DBO
standfirst: >-
  One sealed archive, which the party operating the store cannot read and
  somebody else can verify without asking anybody. Getting out is the path that
  runs every night, not a project somebody scopes when the relationship ends.
why: 5
template: essay.html
---

It is the question asked first and answered vaguely most often: what happens
when we go. Usually the answer is a professional-services engagement, a custom
extract, and a period of trusting that what came out is what was in there.

## The way out is the way it already runs

Backup and export are one mechanism here. Restore and import are another single
one. Every backup is restorable by the everyday import path.

That reads like tidiness and is the opposite. An export exercised only when
somebody leaves is an export nobody has tested — it is written once, against a
schema that then moves, and it is first run in anger on the day it matters. The
route out of this store is the route with the most mileage on it, because it is
the same route the nightly backup takes.

The latest-state export is store-independent FHIR, hash-verified, importable
into a fresh tenant. Not a dump of this store's internals that only this store
can read back.

## Sealed to the tenant, not to whoever runs it

An export bundle is encrypted so that only the tenant owner's master key opens
it. The party operating the deployment takes the backups, holds them, moves
them between sites — and cannot read one.

So "we have your data" and "we can read your data" come apart, which is the
whole arrangement in one sentence. Leaving does not require the operator's
cooperation, because leaving was never something they could withhold.

## Checkable without trusting either party

An archive carries two detached signatures over its root — the vendor's and the
tenant's. Objects enter a store from an archive by one path only: the root
recomputes and both signatures verify, or nothing is written at all.

The root is computed over the manifest's per-entry digests rather than over the
archive's bytes, so repacking does not invalidate it. And the destination
records the root it accepted and the two keys that signed it, in its own audit
trail, so what was imported and who vouched for it is a fact in the receiving
store rather than a claim in an email.

## Removing an organisation is a thing you can watch

A tenant is a database, so removing one drops a database. Not a delete sweep
across shared tables that somebody has to certify was complete, and not a
`deleted` flag a later reporting query forgets about. Afterwards there is no
table left to have missed a row in.

## And an erasure still reaches what you took

The archive that left in March is a file somebody else holds now. If a person
in it requires erasure afterwards, that file stops opening for them — not by
being recalled, which is impossible, but because the key it was sealed under is
destroyed. That is [a person can be forgotten without rewriting
history](why-erasure.md), and it is the reason taking everything with you is
not the same as taking it forever.

## What is not true yet

The attestation on an archive renders as a FHIR `Provenance` carrying a
`Signature`, so a customer's own tooling could check what it was handed without
learning this store's JSON. The rendering exists and is tested. No door hands
it to anybody — the export returns the attestation as this store's own JSON in
a header, which is exactly what the promise was written to stop. Stated because
a gap named is a gap somebody can close.

<div class="takeaway" markdown>
Getting out is an ordinary operation with a receipt, and it does not need the
goodwill of the party you are leaving.
</div>
