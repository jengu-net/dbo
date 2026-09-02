# US-DBO-TRAIL-ANSWERS — a practice asks whether anyone read a document, and the answer is "nobody", not "no record"

> A patient asks their practice who has seen their results. The practice
> asks Meristem. Meristem asks the store.
>
> The answer comes back as a journey: the worklist left the practice at
> 09:14, was carried by the synchronisation service, was carried by the
> bench, reached the analyser, and was opened **once** — by the analyser,
> at 09:16, to run the assay. Nothing else opened it. Not the
> synchronisation service, not the bench, not Meristem.
>
> That last sentence is the one that is hard to say honestly, and it is
> the reason the trail is built the way it is.

## The scene

Ines has to answer a question she cannot answer with a log file: **did
anybody read this?** Logs are good at recording what happened and bad at
proving what did not. "There is no entry" and "nothing happened" look
identical, and only one of them is an answer.

Three things have to be true before the honest answer exists:

1. carrying and reading are **different records**, so a carry is not mistaken
   for a read;
2. every hop leaves its record, so the absence of a read *between* two hops
   means something;
3. the records cannot be quietly incomplete, or the absence proves nothing
   again.

## The question

The practice's own question is about a document, so it is answered where every
reading of that document is answered: **the record's own entries.** One of
them says the analyser opened it, at 09:16, and names the task execution that
occasioned it. There is nothing special to know — it is the same trail a
clinician's read would appear in.

Ines follows the occasion across to the task, and gets the journey: the task
the store minted, a travel entry per hop in order, and the result that closed
it. **No entry there says anybody saw the content**, because none of them is
that kind of record.

So "opened once, by the analyser" is a fact the trail states rather than an
inference from what is missing — and the two halves of the answer sit where
each belongs, joined by the occasion rather than piled into one log.

## The answer she can give

> Carried four times. Opened once, by the analyser, at 09:16, for the assay.

Not *"we have no record of anyone else reading it"* — which is what a system
without travel entries is reduced to saying, and which a regulator correctly
hears as *"we do not know"*.

## When it is not clean

A different run comes back wrong. The result arrived, but its chain commits to
a link the store never received.

Ines does not have to interpret that. **The run does not close**: the store
refuses to accept a completion whose history has a hole in it, so the work
stays owed and shows up as such. What she investigates is a named
participant, a named run, and a specific missing link — rather than a
suspicion that something is off somewhere.

And the other shape: a run whose access entry arrived but whose result never
did. That is more informative than an ordinary lapse — the store knows the
payload was opened *and* the work was not finished, which is exactly the pair
of facts an investigation starts from.

## Why the root matters

A participant could otherwise present a complete, internally consistent chain
describing a journey that never happened. It cannot, because the first link is
the task, and **the store minted the task**. The chain has to begin at
something the store already knows it issued.

## Joins

| Leg | Promised by |
|---|---|
| Audit entries are records, kept like any other | `REQ-DBO-POL-AUDIT-AS-RECORDS` |
| The actor is stamped from the validated token, never claimed by the caller | `REQ-DBO-POL-ACTOR-FROM-AUTHORITY`, `REQ-DBO-POL-CUSTOM-AUDIT-EVENTS` |
| The trail cannot be edited or backdated, whatever the tenant's write discipline says | `REQ-DBO-POL-AUDIT-UNCONDITIONALLY-APPEND-ONLY` |
| A version links to the one before it, so a rewrite is detectable offline | `REQ-DBO-CORE-VERSIONED-HISTORY` |
| Work not completed is released and visibly still owed | `REQ-DBO-PROC-FAILURE-IS-RELEASED` |
| A run names what ran it | `REQ-DBO-PROC-RUN-NAMES-WHAT-RAN-IT` |
| The trail survives a restore rather than being replayed into something new | `REQ-DBO-POL-POLICY-REPLAY-ON-RESTORE` |

## What the store cannot do yet

- **There is no travel entry**, so absence proves nothing yet. This story's
  central claim — *nobody looked* — is exactly the one the store cannot
  currently make.
- **There is no access entry distinct from an ordinary read**, so a carry and
  a reading are not yet different kinds of record.
- **Nothing is chained across participants**, so a chain with a hole is not a
  thing the store can notice, and a completion is accepted on its own word.
- **A result carries no chain head**, so there is nothing to verify it
  against at the moment it would be cheapest to check.

## Open decisions

- **Does a completion with a broken chain refuse, or land and flag?**
  Refusing keeps the store's account true and makes suppression cost the
  participant its own work. Landing-and-flagging is kinder to a fleet with a
  flaky return channel and puts the burden on somebody noticing. The first is
  more in this store's character; the second is what an operator will ask for
  the first week it misfires.
- **How long is the chain kept?** The trail outlives the run by design, and a
  chain that is pruned on a different schedule from the entries it links
  would leave verifiable history that can no longer be verified.
- **Does a partner see the verification, or only the journey?** Telling a
  reseller that a chain is intact is reassuring; telling them it is broken
  discloses something about another party's participant.
