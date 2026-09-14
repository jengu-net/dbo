---
title: "A Type Declares What It Is"
eyebrow: Pattern
standfirst: >-
  Append-only, versioned, auditable, retained for how long, identified by
  what. Declared once per kind of record and enforced by the engine, not left
  to the habits of whatever code happens to write it.
template: essay.html
---

**Intent — settle a record's discipline where the record is defined, so every
path into the store inherits it.**

## You are

Holding several kinds of record with genuinely different rules. A consent may
never be quietly replaced. A draft may be edited all day. A measurement is
kept for thirty years, a session log for thirty days. An identifier is what
makes one record the same as another, and which identifier that is depends on
the kind.

Today those rules live in the code that writes each kind — and in the code
that corrects it, imports it, and cleans it up at night.

## The question

Where does a record's discipline live, so that a path written next year obeys
rules written this year?

## The forces

- Rules that live in writing code are re-stated once per writer, and diverge
  quietly.
- The paths most likely to break a rule are the ones written in a hurry:
  imports, corrections, migrations, one-off fixes.
- Different kinds genuinely do need different rules, so one global setting is
  not the answer either.

## Therefore

**Declare the discipline on the type, and let the engine enforce it on every
write.**

--8<-- "assets/diagrams/pattern-a-type-declares-what-it-is.svg"

<p class="diagram-caption">The left column is a list of places, not a list of
mistakes. The problem is that the question is asked again in each of them.</p>

What is declared is small and answers questions people actually argue about.
May a version be replaced, or only added to. Is every touch of it recorded.
How long is it kept, at least and at most — because retention is a floor as
well as a ceiling, and a record deleted too early is as much a failure as one
kept too long. What identifies one, and what domain it belongs to.

One consequence is worth naming because it is what makes the pattern more than
tidiness: the audit trail is exempt from whatever the tenant declares. An
audit entry has no update and no tombstone under any policy, for anybody,
including the party running the deployment.

## What each reader gets

- **A regulator** can read a tenant's declarations and know what is true of
  each kind of record, without reading the code that writes them.
- **A security officer** has one place to review when the rules change, rather
  than every writer.
- **An administrator** sets retention as configuration, and a restore
  re-applies the declarations before serving.
- **The business** can promise a customer a retention or immutability term and
  have the promise be a setting rather than a code review.

## Relations

- **Builds on** — [Property, Not Policy](pattern-property-not-policy.md).
- **Makes possible** — [The Run Is a Record](pattern-the-run-is-a-record.md);
  the trail as records;
  [A Role Is a Period on a Record](pattern-a-role-is-a-period-on-a-record.md).
- **Composed of** — Canonical Data Model, from Enterprise Integration
  Patterns, for the part about one agreed shape per kind.
- **Written up in** — [Declared rules](../declared-rules/README.md) and
  [Records you can rely on](../records-you-can-rely-on/README.md). Proven by
  `REQ-DBO-POL-DECLARED-AT-CONFIGURATION`, `REQ-DBO-POL-DECLARATIVE-RETENTION`
  and `REQ-DBO-POL-AUDIT-UNCONDITIONALLY-APPEND-ONLY`.
