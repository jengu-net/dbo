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

The promises this story rests on, projected from the catalogue rather than
written here: a story claims no evidence, and a leg is what its promise's own
citations say it is.

<!-- story:begin — generated from the promise catalogue; do not edit. Regenerate: ./gradlew :core:harness:promiseProjection -->

| Promise | Says | Status |
|---|---|---|
| `REQ-DBO-POL-AUDIT-AS-RECORDS` | Audit entries are regular, pseudonymous records in the tenant's own store — feed-visible, exported and restored with the tenant, re-identifiable only through the vault. | PROVEN |
| `REQ-DBO-POL-ACTOR-FROM-AUTHORITY` | Every audit entry names its actor from the tenant authority's token (client and subject) — no anonymous mutations under any audited policy. | PROVEN |
| `REQ-DBO-POL-CUSTOM-AUDIT-EVENTS` | Applications contribute business-level audit events; the machinery stamps actor and time from the validated token and its own clock, overriding caller claims — the trail can be enriched, never impersonated or backdated. | PROVEN |
| `REQ-DBO-POL-AUDIT-UNCONDITIONALLY-APPEND-ONLY` | Audit entries are exempt from the tenant's write discipline: no update, no tombstone under any policy; retention's sweep is the only removal. | PROVEN |
| `REQ-DBO-CORE-VERSIONED-HISTORY` | Every write appends an immutable version; version-aware reads and optimistic concurrency (ETag) are first-class. | PROVEN |
| `REQ-DBO-PROC-FAILURE-IS-RELEASED` | A failing or throwing step service releases the run with the reason — never closed, never lost — and a later cycle may take it again. | PROVEN |
| `REQ-DBO-PROC-RUN-NAMES-WHAT-RAN-IT` | A run records the executor, its version, its provider and the scope it was chosen at. A provider can be withdrawn and a scope re-declared, so a resolution nobody wrote down is a decision nobody can reproduce. | PROVEN |
| `REQ-DBO-POL-POLICY-REPLAY-ON-RESTORE` | Before a restored tenant serves, the machinery re-applies the shred ledger and the retention sweep — an archive cannot resurrect what policy required gone; archives carry removeAfter themselves. | PROVEN |
| `REQ-DBO-POL-TRAVEL-AND-ACCESS-ARE-DIFFERENT-ENTRIES` | One trail; the target says what an entry is about. A hop that carried work leaves a travel entry about the task. A participant that opened a payload leaves an access entry about the document, landing where every other reading of it lands and naming the task execution as its occasion. The machinery's own read to seal a payload records nothing: a read that yields only ciphertext is not a disclosure. So who read this is answered from the document by somebody who need not know work exists, and where did this go from the task, and the trail can say that nobody looked. | PROVEN |
| `REQ-DBO-POL-A-RUNS-TRAIL-IS-CHAINED-FROM-THE-TASK` | A run's travel and access entries each carry a link to the one before, rooted in the task the store minted, so a participant cannot present a journey that never started. The result that closes the run is the last link and carries the head it commits to; the store checks the chain when the result lands, and a completion with a gap is refused and told which link. A travel entry names who it handed to, so a skipped hop is exposed by the next author. Links are signed by the participant, which buys non-forgery and non-repudiation and not omission-proofing: an intended recipient can open a payload and never say so, and that limit is accepted. The link lives on the entry, so the chain outlives nothing the trail does not, and a pruned predecessor reads unchained rather than broken. | PROVEN |

Coverage: {PROVEN=10} — a leg marked PLANNED cites a promise that exists and is not yet cited by any test.
<!-- story:end -->

## What the store cannot do yet

- **Travel and access are different entries.** A hop leaves a travel entry
  on the task naming who it was handed to. A participant that opened a sealed
  document reports it from where its key is, and that lands on the document
  as an access entry naming the run; the store's own read to seal records
  nothing. So *nobody looked* is sayable, with the limit the review accepted:
  an intended recipient can open and not say so, and that is the one thing
  the trail cannot see.

## Decided in review

- **A completion whose chain has a hole is refused, and told which link.**
  That is what makes the refusal actionable: a link lost in transit is one
  the participant still holds and resends, and the run closes; a link that
  cannot be resent never existed, and the run stays owed with a named
  participant and a named link. Landing it and flagging it instead would
  leave a run marked done that may not be.
- **The link lives on the entry**, so there is no separate chain to keep or
  prune. A predecessor that has been pruned reads as *unchained* rather than
  *broken* — the distinction the version chain already draws for rows older
  than itself, and an honest one, since nothing was ever attested.
- **A travel link names who it handed to.** That is what makes a skipped
  hop detectable: the next author is always predictable, so a hop that
  omits its own link leaves a mismatch the following participant exposes.
- **Ines's answer has a known limit, and it is stated.** The analyser is the
  intended recipient, so it could open the payload and not say so. "Opened
  once" is therefore what the analyser attested, signed and unrepudiable —
  not something the store watched happen. The alternative, unwrapping on the
  store's side, would break offline benches and turn a structural exclusion
  into a filter, and was declined knowing the cost.
