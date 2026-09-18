---
title: Export and import
eyebrow: Guide
standfirst: >-
  A tenant leaves as one sealed file, under a key this store does not hold and
  cannot recover — and comes back only as an archive both parties signed.
template: essay.html
---

Backup is not a feature beside export here. It is the same act: one archive,
sealed, containing everything the tenant is.

That matters because the two usually diverge. Backups are made by the operator
for the operator, and exports are made for the customer when they ask — so the
export path is exercised rarely, and discovered to be incomplete at the worst
possible moment, which is the moment somebody is leaving.

## What is in here

Before moving anything, ask what there is:

```bash
--8<-- "docs/guide/examples/snippets/inventory.sh"
```

```
identity   ClientApplication    1
identity   SigningKey           1
r5         Patient              7
work       Run                  3
definitions: 6382
```

Four kinds of thing, in three domains. The patients are the ones earlier
chapters wrote — this page runs after them — beside the `Run` records of the
work that wrote them, the tenant's own credentials, and the definitions it took
from its face root, which are most of the volume.

Note what the inventory is: not a report the store assembles for this purpose,
but a count of the records it holds. There is nothing else to enumerate.

## The key is the tenant's, not the store's

Ask for the archive with no key and you are refused:

```bash
--8<-- "docs/guide/examples/snippets/archive-no-key.sh"
```

```json
{"error":"invalid_request","detail":"X-Owner-Key is required: the archive is
 sealed under the tenant owner's key, which this store does not hold"}
```

Read the second clause. This is not validation — it is a statement about who
holds what. The store cannot seal an archive on its own because the key is not
its to use, and it cannot fetch one because it does not have one.

So an operator who takes a backup cannot read it. Neither can whoever stores
the file afterwards, which is usually a third party nobody thought about.

## The whole tenant, as one file

```bash
--8<-- "docs/guide/examples/snippets/archive.sh"
```

```
200 19936728 bytes
```

The exact count moves a little run to run — ids and timestamps differ — but the
order of magnitude is the point. Twenty megabytes for seven patients, because a
backup is **self-contained**: the
definitions travel with it, so the archive can be restored somewhere that has
never heard of this deployment's face roots. Ask for `X-Archive-Kind:
portable-export` instead and you get roughly half — the records without the
specification behind them, for when the receiver already has it.

And the thing the file must never be:

```bash
--8<-- "docs/guide/examples/snippets/archive-opaque.sh"
```

```
0
```

The patient's name is in the tenant. It is not in the archive in any form
anybody can read without the key — which is what makes storing it somewhere
cheap a decision about durability rather than about disclosure.

## The way back is deliberately harder

Push the archive back and the store refuses:

```bash
--8<-- "docs/guide/examples/snippets/import-needs-signatures.sh"
```

```json
{"error":"invalid_request","detail":"X-Archive-Attestation is required: a
 restore applies an archive both parties signed, and this store cannot sign
 for either of them"}
```

An archive is accepted only with two signatures over it: the exporter's and
the tenant's. This store holds neither key, so it cannot manufacture the
ceremony — it can only check that it happened.

That asymmetry is the point. Taking a copy is routine; putting one back is a
change of custody. An importable backup that needed nothing but the file would
mean anyone holding a file could become the tenant, and the sealing would have
bought nothing, because the attacker who has your backup is exactly the person
you sealed it against.

## What you would otherwise have written

A backup job, and separately an export feature, and the slow discovery that
they disagree about what a customer's data is.

Encryption at rest that protects you from a stolen disk and not from your own
backup archive sitting in a bucket. Then key management for that archive, and
the question of who can decrypt it, answered by whoever has cloud console
access.

And a restore path with no notion of custody: a file, a script, and nothing
that distinguishes returning your own data from installing somebody else's.
