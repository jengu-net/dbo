---
title: Retention
eyebrow: Guide
standfirst: >-
  How long a type is kept is declared as a floor and a ceiling on the tenant,
  and a scheduled sweep is the one sanctioned mutation of history — audited,
  without keeping what it removed.
template: essay.html
---

Retention is usually a document. Somebody writes *clinical records are kept for
thirty years*, and the thing that enforces it is a cron job somebody else wrote,
or nothing at all.

Here it is part of the tenant's declaration, beside the types it holds and the
audit level it keeps.

## A floor and a ceiling

```json
"retention": { "perType": {
  "Observation": { "keepAtLeast": "P10Y", "removeAfter": "P30Y" } } }
```

Two durations, both optional, and they mean different things:

| | Says | Who it protects against |
|---|---|---|
| `keepAtLeast` | this may **not** go before its time | somebody deleting too eagerly |
| `removeAfter` | this **must** go after its time | keeping things forever because nobody decided |

Most systems have the second half as an intention and the first half as an
accident. Declaring both makes the rule legible to somebody who has to sign off
on it, and it composes with write discipline rather than fighting it — an
append-only type holds **even against policy**, because a floor that could be
overridden is not a floor.

## The sweep is the only thing that removes history

Removal is otherwise not a thing this store does — records version, they do not
vanish, which is what [History and concurrency](history.md) is about. The
retention sweep is the single sanctioned exception, and it is scheduled rather
than triggered.

!!! info "Every removal is audited, without keeping what was removed"

    The sweep writes to [the trail](the-trail.md) what it removed and when —
    and does not retain the removed data in order to say so. An audit entry
    that quoted the record it deleted would be a copy of the thing the deletion
    was for.

## Retention is not erasure

They are easy to confuse and they answer different questions.

| | Retention | [Erasure](erasure.md) |
|---|---|---|
| About | a **type**, over time | a **person**, now |
| Trigger | the clock | somebody's request |
| Reaches | records in this store | every copy, including ones you cannot recall |
| Mechanism | removal | destroying a key |

A system that only has retention answers a deletion request with *it will age
out*, which is a retention argument standing in for an erasure it cannot
perform. A system that only has erasure keeps everything else forever. This
store has both, and they compose: shredding never rewrites a record, so the
record remains for as long as its retention says and the person is gone out of
it either way.

## What is not built

Being plain about this, because it is the honest state rather than a detail.

!!! warning "There is no door onto retention, and the sweep does not yet survive a restart"

    Retention is **declared** and swept internally. Nothing over HTTP declares
    it, triggers it, or reports on it — a tenant's spec is the only place it is
    said, and the sweep runs on the deployment's own schedule.

    The sweep is idempotent and correct in process. Promoting it to a durable
    scheduled workflow, so that it survives a restart mid-sweep rather than
    starting the next pass from the beginning, is known work and not done.

So what you can rely on today is the declaration and the sweep's behaviour
while it runs, and what you cannot yet do is ask a running tenant what it is
about to remove.

## What you would otherwise have written

A policy document, and a cron job whose relationship to it is a matter of
faith.

A `deleted_at` column, which keeps the row and calls it deletion.

Retention rules in application code, so a second application against the same
database has its own.

A floor nobody encoded, so an eager cleanup job removed records that a
regulator later asked for.

And the answer *it will age out*, given to somebody exercising a right that
does not wait.
