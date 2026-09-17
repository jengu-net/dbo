---
title: The trail
eyebrow: Guide
standfirst: >-
  Who did what, to which record, on whose authority — as records in the
  tenant's own store, append-only against everybody including whoever wrote
  them.
template: essay.html
---

A log is something a system writes about itself. A trail is evidence, and the
difference is whether the thing that produced it could also tidy it.

## Turning it on

The tenant declares it, beside everything else it declares:

```json
{ "code": "hogwarts", "zone": "rl", "audit": { "level": "writes" }, ... }
```

`writes` records every act that changes a record. `full` records reads as well
— which is what a clinical deployment usually wants, and costs an entry per
read. The default is neither, because a store that audits by default is a store
that decided for you how much you are willing to pay.

## What an entry says

```bash
--8<-- "docs/guide/examples/check.sh:trail"
```

```
action  C
who     tenant-bootstrap
what    Patient/01a0af3b-583f-750a-aff6-d08269c6fe88
```

Three facts, and the third is the one that is usually missing elsewhere. The
entry names the record it is about, so *what happened to this person* is a
query rather than a grep.

**Who is the credential, not the claim.** The agent is what the authority
validated, never a name the caller supplied — a request cannot describe itself
as somebody else, because the field is filled in by the machinery that checked
it.

## It is searched, not scrolled

```bash
--8<-- "docs/guide/examples/check.sh:trail-search"
```

```
1 by that credential
```

The trail is records of a declared type, so it is searched like any other: by
who acted, by what they touched, by when, by the kind of act, and by the run it
belonged to. That last one is the thread back to *why*, which
[Reaching data through a run](runs.md) is about.

It declares its own parameters and refuses the rest, exactly as every other
type does — `_summary` is not among them, and asking for it is a refusal by
name rather than a silently different answer.

## Append-only against everybody

The trail cannot be edited, and that includes by whoever wrote it. There is no
privileged credential that can remove an entry, because an audit trail with a
delete path is a log with extra steps: the first question an auditor asks is
whether anything was removed, and the only satisfying answer is *nothing can
be*.

This is also why erasing a person leaves the trail standing. The entries stop
naming anybody — the person becomes unreadable everywhere — and the fact that
acts occurred, in what order, by whom, survives. Losing that would mean erasure
destroyed the evidence of its own lawfulness.

## What you would otherwise have written

An audit table, and triggers to fill it — or a wrapper somebody has to remember
to call, which is the version that has gaps nobody finds until they are looked
for.

Then write access to that table for the application that fills it, which is the
same credential the application uses for everything else, which means the
answer to *could this have been altered* is yes.

And a retention policy for it, argued separately from the data's, because the
two live in different places and nothing keeps them in step.
