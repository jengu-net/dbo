---
title: "The Run Is a Record"
eyebrow: Pattern
standfirst: >-
  One attempt at one step is an ordinary record in the same store as everything
  else. So it has history, an audit trail and an owner, and it survives a
  restart of anything, because it was never in flight.
pattern: 5
template: essay.html
---

**Intent — stop work from being a second kind of thing, with a second set of
answers to every question already answered for records.**

## You are

Running work that matters: it is regulated, it crosses organisations, and
somebody will ask about it later. The obvious place to put it is a queue, a
scheduler's own table, or a workflow engine beside the store.

Then the questions arrive. What did this job look like before it was retried?
Who is allowed to see that it exists? Which organisation owns it? What
happened to the ones that were in flight when the node died?

## The question

Where does work live, if every property you need from it is one the store
already gives to everything else it holds?

## The forces

- A queue is good at delivery and has no opinion about history, ownership or
  evidence.
- Each of those properties can be built beside the queue, and each one built
  separately is one more thing that can drift from the records it describes.
- Work in flight is work that can be lost, and the losses are hardest to see
  exactly when the system is under stress.

## Therefore

**Store the attempt as an ordinary record, and let it inherit.** A run is not
a message moving through a system. It is a record, listed, counted and read
through the same interface as everything else, with nothing special built for
looking at work.

--8<-- "assets/diagrams/pattern-the-run-is-a-record.svg"

<p class="diagram-caption">The left column is a bill, not a failure: each of
those four lines is something a system holding work elsewhere has to go and
answer for itself, separately.</p>

Two shapes of run follow, and the difference matters. A **pipeline** runs over
a known set of items and closes when each has reached a terminal state. A
**sweep** converges on a condition and closes when it finds nothing left to
do — and a sweep is *found* rather than started, so a process that crashes
halfway through resumes the same run instead of opening a second one beside
it. Erasing a person is a sweep, and "started twice" is the one property it
must not have.

## What each reader gets

- **A regulator** gets the history of the work itself, not only of the records
  it touched, retained under the same declared rules.
- **A security officer** secures one surface. There is no separate operations
  console with its own login and its own idea of who may look.
- **An administrator** answers "what is running, and who holds it" with an
  ordinary query, and loses nothing to a restart.
- **The business** can hand a partner a view of their own work without
  exposing a scheduler, because the work is records and records already have
  owners.

## Relations

- **Builds on** — [A Type Declares What It Is](pattern-a-type-declares-what-it-is.md); [Work Is the Reason](pattern-work-is-the-reason.md).
- **Makes possible** — [A Sweep Is Found, Not Started](pattern-a-sweep-is-found-not-started.md); [Falls to a Person, and Is Counted](pattern-falls-to-a-person-and-is-counted.md).
- **Composed of** — [Message Store](https://www.enterpriseintegrationpatterns.com/patterns/messaging/MessageStore.html), from [Enterprise Integration Patterns](https://www.enterpriseintegrationpatterns.com/), with the store standing in for the queue rather than sitting beside it.
- **Related work**
    - [Nigam and Caswell (2003)](https://doi.org/10.1147/sj.423.0428) on business artifacts, where the record carries its own lifecycle.
    - [van der Aalst, Weske and Grünbauer (2005)](https://doi.org/10.1016/j.datak.2004.07.003) on case handling.
    - [Case Management Model and Notation](https://www.omg.org/spec/CMMN/), which standardised the same idea.
