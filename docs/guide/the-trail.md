---
title: The trail
eyebrow: Guide
standfirst: >-
  Who did what, to which record, on whose authority and for which run — as
  records in the tenant's own store, append-only against everybody, including
  whoever wrote them.
template: essay.html
---

A log is something a system writes about itself. A trail is evidence, and the
difference is whether the thing that produced it could also tidy it.

## A tenant declares how much

`"audit": { "level": "writes" }` records every act that changes a record;
`"full"` records reads as well, at an entry per read. The default is neither.
Whatever the level, a read made through a run is recorded, because a run is an
occasion somebody may later have to account for.

## What an entry says

An entry names its actor from the credential the tenant's authority validated
— never a name the caller supplied — the record it is about, the kind of act,
when, and the run that occasioned it where there was one. *What happened to
this record* and *what did this run read* are both queries rather than greps;
the clinic's application asks the first with
[`whoTouched`](care-is-recorded.md#asking-what-is-there).

Work leaves two kinds of entry, and the target says which. A hop that carried
work is a travel entry about the task. A participant that opened a payload is
an access entry about the document, beside every other reading of it, naming
the run as its occasion. So the trail can say that nobody looked
([work leaves and comes back](work-leaves-and-comes-back.md)).

## Records, append-only

The trail is a type the tenant holds, served on an R5 or R4 face as
`AuditEvent`, searched by who, what, when, kind and run, exported and restored
with the tenant. No credential can update or remove an entry; retention's sweep
is the only removal. Erasing a person leaves the trail standing and unable to
say whom it was about, which is what lets the clinic show it handled the
request without the proof becoming a copy of what was removed.
