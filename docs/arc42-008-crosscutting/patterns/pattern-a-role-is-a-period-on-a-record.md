---
title: "A Role Is a Period on a Record"
eyebrow: Pattern
standfirst: >-
  The records a tenant keeps anyway say who works there, in what role, from
  when until when. Those records are the grants.
pattern: 8
template: essay.html
---

**Intent — stop maintaining a second list of who may do what, by reading the
first one as the answer.**

## You are

Running an organisation that already records its own people: who they are,
which department, what they are qualified to do, when they started, and when
their contract ends. Beside that, a directory or an access-control list saying
who may log in and what they may reach.

Two lists. Both maintained by hand. One of them is wrong right now, and it is
usually the second.

## The question

Why is there a second list at all, when the first one already says everything
the second is trying to say?

## The forces

- The two lists are updated by different people at different moments, so they
  drift by construction.
- The drift is invisible until the day it matters, and it fails open: the
  leaver keeps access, the joiner is the one who complains.
- A directory has no history, so "who could reach this last March" cannot be
  answered by looking at it.

## Therefore

**Read the organisation's own records as the grants, and let a role carry the
period it is valid for.**

--8<-- "assets/diagrams/pattern-a-role-is-a-period-on-a-record.svg"

<p class="diagram-caption">Access stops when the period ends, without anybody
going to remove it.</p>

Revoking access becomes ending a period on a record that already has history,
an owner and an audit trail. So "who could reach this last March" is a
question about a record as it stood in March, which the store can answer
because it never threw the earlier version away.

The credential is still separate — a person or a system authenticates through
the tenant's own authority — but what that credential *reaches* is derived
from the records, not from a parallel list kept beside them.

## What each reader gets

- **A regulator** gets access history for free, because the grants are records
  and records are versioned.
- **A security officer** reviews one source, and joiners and leavers stop
  being an access-management project.
- **An administrator** offboards somebody by doing the human-resources thing
  they were going to do anyway.
- **The business** can let a locum, a contractor or a partner's employee work
  for a stated period that expires on its own.

## Relations

- **Builds on** — [A Type Declares What It Is](pattern-a-type-declares-what-it-is.md).
- **Makes possible** —
  [Two Parties Bound the Claim](pattern-two-parties-bound-the-claim.md).
- **Related work** — role-based access control (Sandhu and others, 1996), and
  Dietz's enterprise ontology (2006), where an actor role is authorised for
  transaction kinds and may be filled by a person or by a system.
- **Written up in** — [Who may act](../who-may-act/README.md). Proven by
  `REQ-DBO-AUTH-ORG-MODEL-IS-THE-AUTH-MODEL`,
  `REQ-DBO-AUTH-ROLE-GRANTS-AS-RECORDS` and
  `REQ-DBO-AUTH-DEACTIVATION-RETIRES-CREDENTIALS`.
