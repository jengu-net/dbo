# Eventing: built, closed as done, and reachable from nothing

**Status** — the subscription engine is complete, tested, installed as a
bundle, and constructed only by tests. No tenant has ever delivered a
notification. **The catalogue now says so**: of the four EVT promises, three
read `PLANNED` and say it in their own words, and only the outbox — which is
genuinely reachable — still reads `PROVEN`. So the status page is no longer
the thing to fix, and step 2 is the next one that changes what the store can
do.

**Issues** — open: [#259](https://github.com/jengu-net/dbo/issues/259), a consumer
that has narrowed to the edge and now polls every sixty seconds because this
is unmounted — which is what turns the priority here from ours to set in the
abstract into one with a cost attached. Built and closed:
[#8](https://github.com/jengu-net/dbo/issues/8) (durable rest-hook delivery),
[#15](https://github.com/jengu-net/dbo/issues/15) (topic subscriptions,
R5-native and R4-backported),
[#184](https://github.com/jengu-net/dbo/issues/184) (say what is true while
it is untrue — step 1, closed 2026-09-13). Related:
[#183](https://github.com/jengu-net/dbo/issues/183), the ratchet that would
have caught this, still open. Steps 2 onward are the groomable list below and
are unfiled: nothing is blocked on filing them, and this document is what
carries them until somebody picks one up.

**Concepts** —
[change, and who is listening](../arc42-008-crosscutting/change-and-who-is-listening/README.md) ·
[processes and work](../arc42-008-crosscutting/processes-and-work/README.md) ·
[data isolation](../arc42-008-crosscutting/data-isolation/README.md) ·
[the FHIR face](../arc42-008-crosscutting/the-fhir-face/README.md)

## What this is

Two slices built subscription delivery and closed as done. The engine matches
by search, delivers durably, dedupes to exactly-once, and dead-letters
visibly. Its tests pass. It is in the runtime bundle set, so it resolves in
the container, and both FHIR personalities carry adapters for it.

Nothing constructs it. The composition root does not contain the word. So a
tenant can hold a `Subscription`, the store will accept it, and no
notification will ever be sent — and the requirement catalogue says the
opposite, in four places, because every proof builds the engine itself.

The work is therefore not "finish eventing". It is to decide how delivery is
reached, mount it that way, and correct what the catalogue claims in the
meantime.

## Where it stands

- **The engine is whole.** Matching, topic dispatch, retry and backoff, the
  deterministic workflow id that composes the feed's at-least-once with
  dedup, and a queryable dead-letter row. It already takes `Runs` and already
  declares `dbo.subscriptions.delivery` as a process, with a comment saying an
  exhausted delivery is a run rather than a private table.
- **It is not mounted, and there is no half-mounted state to unwind.** The
  gap is one construction and a lifecycle, not a rewrite.
- **The durable-execution library is private to its own bundle**, embedded
  rather than shared, so mounting it drags nothing into the container.
- **Nothing is decided about how it should be reached.** That is the first
  step below, and it is the only one that needs a design pass.

## Sequence

| # | step | status |
|---|---|---|
| 1 | **Say what is true while it is untrue** ([#184](https://github.com/jengu-net/dbo/issues/184)) — the unreachable EVT promises carry a `TODO: prove it in a test` and the status page stops describing eventing as complete. Cheap, and everything below is decided while believing the status page. | **DONE** 2026-09-13 — three EVT promises read `PLANNED`; since 2026-09-15 they also say it in their own prose rather than only in a status column |
| 2 | **Mount dispatch as a step service** — the runner tracks `StepService` through an OSGi whiteboard, so a bundle contributes one the way it contributes anything else. No new wiring in the composition root, which is where the gap is. | NEXT |
| 3 | **Model dispatch as a sweep** — one run per pass over the feed, checkpointing its position, with per-event durability kept inside the step. A reconciler modelled as a pipeline never ends. | READY, needs 2 |
| 4 | **Id-only notification** — transport becomes routing, and the disclosing read goes back through the door that authorises, audits and decrypts. | **BUILT, and this step was mis-stated** — id-only is not missing and never was: it is per subscription, driven by the subscriber's own declaration (`!channel.hasPayload()` in R4, honoured by both composers), so a subscription declaring no payload already receives the topic, the subscription, the event number and a reference. What is left under this heading is a *default* and a policy question — whether a payload should be refused over a carrier that holds nothing readable — not a mechanism |
| 5 | **Match against the envelope, not the database** — carry the envelope on the feed item so the common criteria are an in-process predicate. Today matching is one query per event per active subscription. | LATER — the throughput item, worth doing when a tenant has enough subscriptions to feel it |
| 6 | **Blind patching on the stream** — a patch whose paths are all non-identifying is applicable with no key, decidable from tenant configuration. | LATER — its own topic if it grows |

The critical path is **2 → 3**, and step 2 is the whole distance between a
tenant holding a `Subscription` and anything arriving: the engine matches
correctly and no deployment constructs it.

Step 4 is not on the path at all, which is a correction rather than a
re-ordering — see its row. A consumer reading this table ranked 4 above 2 on
the strength of the old wording and would have got nothing for it, because
id-only is already the shape they would receive the moment anything delivered.
That is this document doing the opposite of its job, and the reason it is
worth saying so in the row rather than quietly editing the words.

## Decisions

**Delivery keeps its own durability; the run machinery owns the global
truth.** The contract line is already drawn, twice, and this is the case it
was drawn for: the step-service contract says no orchestrator is named there
because durability of a step's half-finished work is the implementor's
choice, and names a durable workflow on a server as a valid one. The runner's
own note says it owns what is owed, by whom, and what happened, and that the
line falls between the two. The engine as built sits exactly on that line.

**Dispatch is a sweep, not a run per delivery.** Making each
subscription-event pair a claimable run costs a conditional write and poll
latency per delivery, puts deliveries into contention with each other, and
pushes per-event dedup out of the one place that currently solves it. The
gain would be a tidier diagram and a slower store. What the work machinery is
wanted for here is reach and visibility, not per-event durability.

**Mounting goes through the whiteboard rather than the composition root.**
The root is already 1,900 lines with one 450-line method, and the runner
already tracks step services registered by any bundle. Adding a construction
to the root makes the root worse and teaches the next subsystem to do the
same.

**Transport routes; it does not disclose.** Under personal-data isolation the
feed carries ciphertext by construction, so a carrier already cannot read
identifying elements. Sending the whole stored payload makes transport a
disclosure anyway, and the standard already has the vocabulary for not doing
that. This is the membrane argument that moved staff provisioning inside,
applied to notifications.

## Traps

**Two slices closed as done, and the thing they built was never reachable.**
Both had passing tests. Every proof constructs the engine itself, which is
the harness trap in its plainest form: a test builds what it needs, so the
question "who constructs this outside a test" is the only one that would have
caught it, and nothing asked. The bundle resolving in the container proves
the packaging, not the wiring, and there is a container proof that loads the
class and stops there.

**A ratchet would have caught this and does not exist.** The same sweep found
four more instances elsewhere in the tree, so this is a class of failure
rather than one mistake. It is [#183](https://github.com/jengu-net/dbo/issues/183),
and it is the reason to fix the mechanism rather than only this instance.

## Not doing

**Deterministic encryption, or a keyed digest beside the sealed block.** It
would let a blind reader say the identifying part did not change between two
versions. It buys that word by leaking equality, which is what lets an
observer link two records, or two people, with no key. A blind differ
characterises the cleartext delta exactly and reports the sealed block as
opaque and possibly changed. That is the honest answer and it is the one to
keep.

**Rewriting delivery onto the participation lane.** See the decisions above.
The engine is already the shape that was proposed; what it lacks is a
lifecycle.

## Verifying

The state this document describes, before any of it is fixed:

```bash
grep -rn 'new SubscriptionEngine(' --include='*.java' core | grep -v /build/
grep -rin 'subscri' core/dbo-tenant/src/main core/dbo-rest/src/main
```

The first prints only test files. The second prints nothing, which is the
whole finding: the composition root and the HTTP surface do not know
subscriptions exist.
