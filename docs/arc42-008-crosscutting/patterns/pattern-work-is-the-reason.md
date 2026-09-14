---
title: "Work Is the Reason"
eyebrow: Pattern
standfirst: >-
  Nobody reads a regulated record for no reason. The reason is a step of some
  process — so that is what access is granted to, and doing it leaves the
  proof.
pattern: 4
template: essay.html
---

**Intent — make the thing that authorises an access and the thing that
explains it the same object, so they cannot disagree.**

## You are

Holding records that people outside your organisation have rights over. You
can already say who is allowed to open each one. What you cannot say, months
later, is why any particular person opened any particular one — and that is
most of what you will be asked.

Meanwhile the actual work is elsewhere. A sample is analysed in a laboratory.
A consignment is inspected at a border. A declaration is signed by a
supplier's own system. Some of those places are behind a router with no public
address, some are offline for a weekend, and some belong to organisations that
are not on speaking terms with each other.

## The question

Where does the reason for an access come from, if not from somebody typing it
into a box afterwards?

## The forces

- A permission answers *who may*, and is silent on *why did*. Every allowed
  access looks identical afterwards.
- A reason recorded beside the access is a second artefact, which can be
  wrong, missing, or written to please the auditor.
- The work that needs the data is real and already has a shape: a step, with
  inputs it needs and a person or system entitled to perform it.

## Therefore

**Grant access to the step, for the length of one attempt at it, and let the
record of the attempt be the record of the reason.** A step declares what it
consumes, what it may change, and who may perform it. A participant claims a
run of that step, and what arrives is what that run named. There is no general
read behind it to fall back on.

--8<-- "assets/diagrams/pattern-work-is-the-reason.svg"

<p class="diagram-caption">The claim is bounded twice over: by what the
participant's credential covers, and by what the step admits. Neither alone is
enough.</p>

Two consequences are worth naming because they are what make the pattern
affordable. The store never reaches out to the laboratory or the border post;
participants ask what is available to them and take it, so an organisation
that is unreachable is not thereby out of step. And when work is offered and
no automated participant takes it, it falls through to a person — which is
often correct, and is counted, so the automation backlog is a query rather
than a consultancy exercise.

## What each reader gets

- **A regulator** asking why this laboratory saw this person's sample is
  answered by the run that made it necessary, not by a correlation across two
  systems.
- **A security officer** has no standing read to defend. The blast radius of a
  stolen credential is the steps it was entitled to claim.
- **An administrator** onboards a participant by saying which steps it may
  perform, and offboards it the same way.
- **The business** can put a partner's system, a contractor and an employee on
  the same footing, because all three are participants claiming steps.

## Relations

- **Builds on** — [Property, Not Policy](pattern-property-not-policy.md).
- **Makes possible** — [The Run Is a Record](pattern-the-run-is-a-record.md); [Nobody Is Pushed](pattern-nobody-is-pushed.md); [The Trail Is Records](pattern-the-trail-is-records.md).
- **Composed of** — [Process Manager](https://www.enterpriseintegrationpatterns.com/patterns/messaging/ProcessManager.html) and [Correlation Identifier](https://www.enterpriseintegrationpatterns.com/patterns/messaging/CorrelationIdentifier.html), from [Enterprise Integration Patterns](https://www.enterpriseintegrationpatterns.com/).
- **Related work**
    - [Clark and Wilson (1987)](https://doi.org/10.1109/SP.1987.10001), where a user is authorised for a transformation procedure rather than for the data.
    - [Thomas and Sandhu (1997)](https://profsandhu.com/confrnc/ifip/i97tbac.pdf) on task-based authorisation, where the permission activates with the task and expires with it.
    - [Byun, Bertino and Li (2005)](https://doi.org/10.1145/1063979.1063998) on access bound to a stated purpose.
    - [Atluri and Huang (1996)](https://doi.org/10.1007/3-540-61770-1_27) on authorisation derived from the workflow rather than declared beside it.
