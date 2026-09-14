---
title: "Bytes Framed, Not Rebuilt"
eyebrow: Pattern
standfirst: >-
  Data crosses the line between engine and face as bytes, parsed once and
  framed rather than rebuilt. What is carried is not opened.
pattern: 21
template: essay.html
---

**Intent — make what comes out of the store the thing that went in, rather
than a reconstruction of it.**

## You are

Moving a payload through several layers. Each layer has its own idea of what
the data is: a parsed object here, a different object there, a serialised form
on the way out. Each conversion is written by somebody reasonable.

Then a client notices that what they stored and what they retrieved are not
byte-for-byte the same, and asks which one is the record.

## The question

What is the truth: the bytes that arrived, or the object the system built from
them?

## The forces

- Every layer needs something out of the payload, and the obvious way to get
  it is to parse the payload.
- Each parse-and-rebuild is a chance for something the system did not model to
  be quietly dropped.
- A layer that has to open a payload in order to move it cannot honestly claim
  not to have read it.

## Therefore

**Let the payload as written be the truth, read it once for what genuinely has
to be indexed, and frame it for travel rather than rebuilding it.**

--8<-- "assets/diagrams/pattern-bytes-framed-not-rebuilt.svg"

<p class="diagram-caption">Each arrow in the left-hand column is a chance for
what comes out to differ from what went in.</p>

The immediate payoff is fidelity: what a client stored is what a client gets
back, including the parts of it the store has no opinion about.

The structural payoff is larger. Because carrying does not require opening, a
hop that moved a sealed payload has nothing to record about its contents —
which is exactly what lets custody and disclosure remain different events in
the trail. A pattern about parsing turns out to be the one that makes an audit
claim honest.

## What each reader gets

- **A regulator** gets a stored document that is the document, not a
  round-trip of it.
- **A security officer** can say which components ever see plaintext, and the
  list is short.
- **An administrator** debugs with the bytes as written rather than a
  re-serialised approximation.
- **The business** can promise fidelity for content the store does not model,
  which is most of what a specialist domain carries.

## Relations

- **Builds on** — [Engine and Faces](pattern-engine-and-faces.md).
- **Makes possible** —
  [Carrying Is Not Reading](pattern-carrying-is-not-reading.md).
- **Composed of** — Envelope Wrapper and Claim Check, from Enterprise
  Integration Patterns.
- **Written up in** — [The payload seam](../the-payload-seam/README.md).
  Proven by `REQ-DBO-CORE-PAYLOAD-IS-TRUTH`,
  `REQ-DBO-CORE-DECLARED-TRUTH-FORM` and
  `REQ-DBO-SRCH-THE-ENVELOPE-IS-EXTRACTED-WHERE-THE-BYTES-ARE`.
