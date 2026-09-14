---
title: "Carrying Is Not Reading"
eyebrow: Pattern
standfirst: >-
  A hop that carried work leaves a travel entry about the task. A participant
  that opened a payload leaves an access entry about the document. So the
  trail can say that nobody looked.
pattern: 12
template: essay.html
---

**Intent — keep the question "who read this" answerable from the document, by
whoever asks, without them needing to know that work exists.**

## You are

Moving a sealed payload across organisations. It passes through a router, a
carrier, perhaps an appliance in a building nobody at your end has seen. At
the far end somebody opens it — or nobody does, and it comes back unopened.

If every hop records a disclosure, the trail says a dozen parties read the
patient's sample. If no hop records anything, the trail cannot say where it
went.

## The question

How do you record a journey without recording it as a dozen readings, while
keeping a real reading visible as one?

## The forces

- Custody and disclosure are genuinely different events, and conflating them
  makes both useless.
- The interesting query is asked from the document by somebody who may know
  nothing about the process that moved it.
- A trail that cannot distinguish them cannot answer the most valuable
  question of all, which is that a document was never opened.

## Therefore

**Give the two events different targets. Travel is about the task; access is
about the document.**

--8<-- "assets/diagrams/pattern-carrying-is-not-reading.svg"

<p class="diagram-caption">The access entry lands exactly where every other
reading of that document lands, so nothing special has to be consulted to find
it.</p>

One read is deliberately not recorded at all: the machinery's own, to seal the
payload. A read that yields only ciphertext is not a disclosure, and recording
it as one would make every genuine disclosure harder to find.

The limit is worth stating plainly, because a chained trail invites the belief
that it closes it. An intended recipient can open a payload and simply not say
so. This is non-forgery and non-repudiation. It is not omission-proofing.

## What each reader gets

- **A regulator** asking who saw a person's sample gets the readings, not the
  routing.
- **A security officer** can distinguish an over-broad distribution list from
  an actual disclosure.
- **An administrator** traces where something went from the task, without
  opening anything.
- **The business** can tell a customer that their document was carried by
  three parties and opened by none.

## Relations

- **Builds on** — [The Trail Is Records](pattern-the-trail-is-records.md);
  bytes framed, not rebuilt.
- **Composed of** — Message History, from Enterprise Integration Patterns, for
  the travel entry, and Claim Check for carrying without opening.
- **Written up in** — [Records you can rely on](../records-you-can-rely-on/README.md)
  and [Processes and work](../processes-and-work/README.md). Proven by
  `REQ-DBO-POL-TRAVEL-AND-ACCESS-ARE-DIFFERENT-ENTRIES`,
  `REQ-DBO-WF-HOPS-AUDITED` and
  `REQ-DBO-POL-A-RUNS-TRAIL-IS-CHAINED-FROM-THE-TASK`.
