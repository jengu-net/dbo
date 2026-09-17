---
title: Encryption
eyebrow: Guide
standfirst: >-
  Every person has their own key. The store holds their identifying elements
  only as ciphertext, matches them without ever holding the values, and an
  operator with the database reads none of it.
template: essay.html
---

[Personal data](personal-data.md) said the identifying elements live behind a
membrane. This is what the membrane is made of, and the reason it can do
something a column cipher cannot: **destroy one key and one person becomes
unreadable everywhere their data ever went.**

## Look in the database

The most direct way to see it is to go around the store entirely and read the
table an operator would read:

```bash
--8<-- "docs/guide/examples/check.sh:pdi-ciphertext"
```

```
__pdiEnc
__pdiPerson
birthDate
resourceType
```

Those are all the keys the stored payload has. `name` is not there.
`identifier` is not there. What is there is a blob, a pseudonym, and the
coarse birth year that [Personal data](personal-data.md) explained.

This is the claim that matters operationally, and it is checkable rather than
promised: a backup, a replica, a restored copy on somebody's laptop, a DBA with
production access — none of them reach identifying data, because it is not in a
readable form to reach.

## A key per person, wrapped

Three keys, doing three jobs:

| Key | Holds | Job |
|---|---|---|
| **the person's key** | one person's identifying elements | the unit of erasure |
| **the deployment's working key** | nothing directly | wraps every person key so the vault stores none of them in the clear |
| **the index key** | nothing | derived from the working key, and used to match without values |

A person's key is stored wrapped, never bare. The working key comes from
custody — [Tenants](tenants.md) covers a deployment presenting its own
credential — so a stolen database is a pile of wrapped keys nothing in it can
unwrap.

!!! info "Per person, not per tenant — and that is the whole design"

    A single tenant key would encrypt at rest and nothing more: erasing one
    person would mean rewriting every record about them, everywhere, including
    the copies you cannot recall.

    A key per person makes erasure an act on one small secret.
    [Erasure](erasure.md) is that act.

## Matching without holding the values

An exact lookup still has to work — `identifier=urn:rl:nid|RL-0001` is the
question every real integration asks. If the store held no form of the value it
could not answer, and if it held the value it would not be encrypted.

So it holds **keyed hashes**. The index key turns a claimed identifier into a
fingerprint, and the match runs over fingerprints.

The consequence is worth spelling out: the index answers *is this exact value
here* and cannot answer *what values are here*. You cannot walk it, and a
fingerprint of a value you do not already have is not something you can produce.
That is why [Personal data](personal-data.md) can refuse enumeration-shaped
questions without also breaking the lookups an integration depends on.

## The whole lifecycle runs blind

Backup and restore are machinery over ciphertext, end to end. An operator can
run a deployment — provision, back up, restore, move a tenant — **without the
ability to read personal data**, which is the separation most systems claim and
few can demonstrate.

The archive is sealed under the owner's key, which is why
[Export and import](export-and-import.md) shows a file whoever stores it can
read nothing in, and why bringing one back is a ceremony this store cannot
perform alone. Opening an archive outside the running system is an owner-only
act, deliberately.

## What you would otherwise have written

Transparent disk encryption, and the discovery that it protects against a
stolen disk and nothing else — every process that can read the database reads
everything.

Column encryption with one key, and the realisation during a deletion request
that you must now rewrite every row that mentions somebody, in every copy.

A key-management story deferred to a later ticket, and the environment variable
it turned into.

A hash of the identifier so you could still search — unkeyed, so anybody
holding the table can confirm a guess, and a national number is very guessable.

And an operator who has to be trusted with everything, because the tools needed
plaintext to work at all.
