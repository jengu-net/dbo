---
title: History and concurrency
eyebrow: Guide
standfirst: >-
  Every write appends a version and nothing is overwritten, any version reads
  back as it stood, and two writers cannot silently lose each other's work.
template: essay.html
---

A record here is not a row that changes. It is a sequence of versions, and the
current one is simply the last.

You did not ask for that and you did not configure it. The type declared
`"handling": "operational"`, and keeping every version is part of what that
word means — a different handling would mean different rules, declared in the
same place and enforced by the engine rather than by whichever code happens to
do the writing.

## The history of a record

`_history` returns the versions oldest first. The quick start walked through
it: two entries, `versionId` 1 and 2, the first carrying the name as it was
originally written.

## Any version reads by its number

The bundle gives you all of them; this gives you one. Here is the whole of
what an integrator writes to read a past version and to change a record
without losing somebody else's change:

```java title="sample/src/main/java/cloud/jengu/dbo/sample/Amending.java"
--8<-- "sample/src/main/java/cloud/jengu/dbo/sample/Amending.java"
```

`asItWas` on version 1, and then on a version that never existed:

```json
{"resourceType":"Patient","identifier":[{"system":"urn:rl:nid","value":"RL-0001"}],
 "name":[{"family":"Potter","given":["Harry"]}],
 "meta":{"versionId":"1", ...}}
```
```
404
```

The first call returns the record **as it was written** — `Harry`, not the `H.`
it says now. The second asks for a version that never existed and is told so.

The response carries *that* version's `ETag`, not the record's current one,
which matters for the next section: an `ETag` echoing the newest version would
make a conditional update built on a stale read look safe.

## A deletion is a version too

Create a record, delete it, and ask `asItWas` for the version that did the
deleting, then for the one before it:

```
204
410
200
```

`410`, not `404` — the store will not invent a document for the moment a record
stopped having one. The versions before it still read, which is the whole
reason a deletion is a version rather than an erasure.

That distinction matters more than it looks. A client that cannot tell *there
was never a version 3* from *version 3 is the one that deleted it* cannot tell
a typo from a history, which is most of what it came to ask.

## Two writers, one record

Now the part the versions are for, and the reason `amend` takes the answer you
read rather than a version number you typed. Read a record, take your time
deciding, and write back — while somebody else has already changed it:

```
412
200
```

The first write says `If-Match: W/"1"`, which is *I looked at version 1 and my
change assumes that is still true*. It is not, so the write is refused. The
second says version 2, which is where the record actually is, and lands.

**Nothing was lost, and nobody had to notice.** Without this the second writer
overwrites the first, both operations report success, and the missing change is
found weeks later by whoever needed it — if at all. The refusal is the feature:
it turns a silent data loss into an error at the moment it can still be handled.

This is why a version read carries its own `ETag`. If a stale read handed back
the current version's validator, the conditional write built on it would look
current and succeed, which is precisely the accident the mechanism exists to
prevent.

## What you would otherwise have written

An `updated_at` column, and the convention that everyone checks it — followed
by the discovery that the checking is in four places and one of them forgot.

An audit table filled by triggers, or by a wrapper somebody has to remember to
call, holding a copy of the row as it was. Then the question of what to do when
the shape of the row changes, and the schema migration that quietly rewrites
what last year's record said.

And a "who overwrote this" investigation, which is the one your history was
supposed to make unnecessary and cannot, because the overwrite left nothing
behind.
