---
title: Erasure
eyebrow: Guide
standfirst: >-
  Erasing somebody destroys their key rather than hunting their rows — so the
  copies you cannot recall are erased too, and what comes back is a receipt
  rather than a bare acknowledgement.
template: essay.html
---

A deletion request is the operation that exposes whether a system's privacy
story was real. The rows are easy. The backup from Tuesday, the analytics
replica, the archive a partner holds, the copy somebody restored onto a laptop
in March — those are the problem, and no `DELETE` reaches them.

This store does not chase them. It destroys the key.

## Asking

```bash
--8<-- "docs/guide/examples/check.sh:erasure-ask"
```

```json
{"open":false,"step":"shred","process":"dbo.erasure",
 "run":"dbo.erasure/shred/01a0b029-076a-…","tally":{"known":1}}
```

What comes back is **the receipt**, not a bare `200`: the run, what it found,
and how far it got. That is what a regulator asks for, and it is why this is
its own door rather than an operation on the maintenance surface — archiving
and restoring are things done to the store, and erasing a person is something
done *for somebody, on instruction*.

Asking twice returns the same run rather than a second account of one erasure.
And asking about somebody this store never held still opens a run — whether
anybody was there is the run's answer, in its tally, not a different status
code.

!!! info "Its own scope, outside the resource grammar"

    A credential that may erase is **structurally blind** to the store's
    resource surface rather than filtered away from it — the same shape as the
    directory credential in [Provisioning people](scim.md). The desk that
    handles rights requests does not thereby read charts.

## What it did

```bash
--8<-- "docs/guide/examples/check.sh:erasure-unfindable"
```

```
0 found
```

The identifier that resolved to them a moment ago resolves to nobody. Not *no
permission*, not *an error* — **not found**, and indistinguishable from a
person who was never here. That indistinguishability is the point: an answer
that said *this person existed and is gone* would still be an answer about
them.

The record itself is still there, and empty of them:

```bash
--8<-- "docs/guide/examples/check.sh:erasure-remains"
```

```json
{"resourceType":"Patient","id":"01a0b029-075d-…",
 "meta":{"versionId":"1","security":[{"system":"urn:dbo:handling","code":"operational"}]}}
```

No name, no identifier, and not even the coarse birth year that
[Personal data](personal-data.md) kept in the clear. The clinical shape of the
record survives; the person is gone out of it.

## Why the copies are erased too

Here is the part that makes this different from a delete, and it is visible in
the database: **the ciphertext is still in the row.** The `__pdiEnc` blob
[Encryption](encryption.md) showed is untouched. What changed is in the vault —
that person's wrapped key is gone, and the row is marked shredded.

So nothing had to be rewritten, and that is exactly why the erasure reaches
further than any rewrite could:

| Copy | What happened to it |
|---|---|
| the live row | unchanged, unreadable |
| last night's backup | unchanged, unreadable |
| the replica | unchanged, unreadable |
| the archive a partner holds | unchanged, unreadable |
| a copy restored onto a laptop in March | unchanged, unreadable |

One small secret was destroyed, and every copy of that person's data became
ciphertext nobody can open — including copies this store never knew existed and
could not have reached. That is what a key per person buys, and it is the whole
reason [Encryption](encryption.md) does not use one key per tenant.

## What is kept

A shred ledger. The fact that an erasure happened, when, and what it covered —
because *we destroyed this person's key on this date* is a thing you must be
able to show, and it is not personal data about them.

Erasure is a **run**, like other work — [The trail](the-trail.md) records it
the way it records everything else, and the run says how far it got rather than
claiming completeness it cannot verify.

## Erasing a whole tenant

A different act, and a blunter one: a tenant is erased by dropping its
database. There is no key ceremony because there is nothing left to be
unreadable — [Lifecycle](lifecycle.md) covers the deletion policy that decides
whether a withdrawn tenant's database is retained or dropped.

## What you would otherwise have written

A `DELETE` across every table that mentions somebody, and the list of tables
that is one migration out of date.

The same across every replica, and the one nobody remembered.

A conversation about backups that ends in *we will let them age out* — which is
a retention argument standing in for an erasure you cannot perform.

A tombstone row saying this person was deleted, which is a record about them
that outlives the erasure.

And a letter to a regulator that says the data is gone, written by somebody who
knows about the archive and is hoping.
