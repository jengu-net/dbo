---
title: "A Sweep Is Found, Not Started"
eyebrow: Pattern
standfirst: >-
  Some work converges on a condition rather than running over a list. Asking
  for it twice finds the attempt already open, instead of opening a second one
  beside it.
pattern: 10
template: essay.html
---

**Intent — make a second request for convergent work join the first attempt
rather than duplicate it.**

## You are

Running work that is defined by a condition rather than by a list. Erase this
person from everywhere they appear. Bring this tenant's configuration into
line with what was declared. Retire everything past its retention.

Such work is asked for by people, by schedules and by retries, and sometimes
by all three within a minute. It also crashes halfway through, because
everything does.

## The question

What should the second request do, when the first one is still running or died
without saying so?

## The forces

- Starting a second attempt gives two accounts of one obligation, and neither
  is the answer to "is it done".
- Refusing the second request makes a repeated ask an error, which it is not.
- Waiting for the first to time out delays exactly the work that matters most.
- For an erasure, the worst possible outcome is a run that reads as complete
  when it is not.

## Therefore

**Look for the open run first. If one exists, that is the answer.**

--8<-- "assets/diagrams/pattern-a-sweep-is-found-not-started.svg"

<p class="diagram-caption">The same interruption, twice. The difference is
invisible until something crashes.</p>

The distinction this rests on is between two shapes of run. A **pipeline**
runs over a known set of items and closes when every one has reached a
terminal state. A **sweep** converges on a condition and closes when it finds
nothing left to do. The two look alike until they are interrupted, and a
reconciler modelled as a pipeline never ends — its queue for a person fills
with work that is merely still converging.

A failure releases the run with its reason rather than closing it, because for
an erasure the one outcome the record exists to prevent is reading as done
when it is not.

## What each reader gets

- **A regulator** asking whether a person's erasure happened is answered by
  one run, not by a choice between two.
- **A security officer** can retry a sensitive operation safely, which means
  retries stop being a judgement call.
- **An administrator** reruns a configuration apply without wondering what a
  second one would do.
- **The business** can expose "ask again" to a customer without building a
  deduplication layer in front of it.

## Relations

- **Builds on** — [The Run Is a Record](pattern-the-run-is-a-record.md).
- **Makes possible** — erasure destroys a key; one declared set, applied.
- **Composed of** — Idempotent Receiver, from Enterprise Integration Patterns.
- **Related work** — convergence and reconciliation loops, where the desired
  state is declared and the run closes when observation matches it.
- **Written up in** — [Processes and work](../processes-and-work/README.md)
  and [Data isolation](../data-isolation/README.md). Proven by
  `REQ-DBO-PROC-RUN-KINDS`, `REQ-DBO-PDI-ERASURE-IS-A-RUN` and
  `REQ-DBO-PROC-CONFIG-APPLIES-AS-A-SWEEP`.
