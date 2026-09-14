---
title: "A Cursor That Holds Still"
eyebrow: Pattern
standfirst: >-
  Rows shift while you page. A position that counts from the start pushes that
  cost onto every caller; one that names where you stopped does not.
template: essay.html
---

**Intent — stop paging from handing a correctness problem to every client
that uses it.**

## You are

Returning large result sets a page at a time, while writes continue. The
conventional page is an offset and a count: skip twenty, take ten.

It works until something is inserted above the window. Then a row you already
saw slides into the next page, or one you have not seen slides out of it.
Nobody notices for a while, and when they do, the fix is written in the
client.

## The question

Who pays for the fact that the data moved while somebody was reading it?

## The forces

- Offsets are trivially easy to implement and trivially wrong under concurrent
  writes.
- The failure is small enough to be tolerated and common enough to be
  everywhere, which is the worst combination.
- A client cannot fix it properly, only defend against it, by deduplicating on
  identity and carrying the duplicate window around in application code.

## Therefore

**Make the position name where the caller stopped, not how far in they
counted.**

--8<-- "assets/diagrams/pattern-a-cursor-that-holds-still.svg"

<p class="diagram-caption">The position is opaque to the caller, so what it
encodes can change without breaking anybody holding one.</p>

The work is designed out rather than worked around. In the platform measured
before this was designed, the same defensive deduplication appeared at roughly
a hundred call sites — which is not a hundred bugs, it is one design decision
paid for a hundred times.

Opacity matters as much as stability. A caller that cannot parse a position
cannot come to depend on its shape, which leaves the store free to change how
a position is computed for a given kind of feed.

## What each reader gets

- **A regulator** gets exports and extracts that do not silently duplicate or
  drop rows.
- **A security officer** sees no sort keys or internal identifiers leaking
  through a page token.
- **An administrator** can let a long extract run across a busy period without
  scheduling it for a quiet one.
- **The business** ships clients that do not each carry their own workaround.

## Relations

- **Builds on** —
  [One Feed, Every Consumer](pattern-one-feed-every-consumer.md).
- **Related work** — keyset pagination, against offset pagination. Enterprise
  Integration Patterns has no name for this one.
- **Written up in** — [Change, and who is listening](../change-and-who-is-listening/README.md)
  and [Finding things](../finding-things/README.md). Proven by
  `REQ-DBO-FEED-KEYSET-CURSORS` and `REQ-DBO-SRCH-TYPED-ORDERING`.
