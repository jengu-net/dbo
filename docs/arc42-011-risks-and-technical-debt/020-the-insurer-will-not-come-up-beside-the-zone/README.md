**Fixed, and the cause was not where two readings of it said. A projection
assigns fresh object ids every time it is cut, so it hands one canonical down
one dependency under two ids; the stream read the second as a stale claim of
its own and threw, and the tenant never served. The apply path now asks
whether the content is identical BEFORE it asks where the claim came from.
Kept until the fix has run in CI a few times, because what it fixes was
intermittent.**

# The insurer will not come up beside the zone

The sample world's tenants are one definition, brought up by the guide's
container and by the harness in its own JVM. Six of the seven came up either
way. The insurer did not come up in the harness:

```
declared but not serving after 20 passes: [gringotts]
  gringotts=IdentityConflictException: identifier urn:dbo:canonical|urn:dbo:audit
    already claimed by 01a0c27e-… (claim by 01a0c285-…)
serving=[rl, fhir-r4, fhir-r5, hogwarts, rl-on-r4]
```

## What it actually was

`ContentSyncEngine.tryApply` catches the conflict and asks two questions about
it. Did THIS stream put the existing record here — if so it is a stale claim
of ours and throws loudly. Is the existing record byte-identical to what
arrived — if so there is nothing to do. They were asked in that order.

The insurer is a release behind, so it takes the zone through a projection.
A projection tenant assigns fresh object ids every time it is cut, so it hands
the same publication down the same dependency under two ids. The first
applied. The second met its own earlier copy, was read as a stale claim, and
threw — and the throw stopped the stream at the head of its queue, so the
tenant never served at all. Both copies were byte-identical: the engine's own
audit code system, which nobody had edited.

The fix is the order. Identical content is the same publication whatever route
carried it, and every question below it is about a difference there is not, so
it is asked first. The loud throw is kept for a stale claim whose content
actually differs, which is what it was written for.

## Three readings, and what each one cost

Worth keeping, because the first two were confident and wrong.

**"The projection re-renders it."** Refuted by reading both copies: the zone's
and the r4 root's `urn:dbo:audit` are the same 430 bytes, down to the same
version hash. The engine's vocabulary does not differ across those releases.

**"Two objects in one write batch."** Refuted by the stack trace. The throw
comes from a single-item `put`, and the batch path already catches this
exception and falls back to per-item — so it could never have been the one
killing bring-up. The hundred and seventy microseconds between the two ids is
how close together the projection made them, not evidence of a batch.

**The stack trace settled it in one run.** The lesson is the cheap one: the
trouble ledger keeps `String.valueOf(e)` and no stack, so two readings were
built on a message. A temporary log of the exception at the catch site
answered it immediately.

## What was true all along

**It reproduces on three tenants.** The zone, the r4 root, the insurer and the
projection they imply. The count of tenants was never the cause.

**It was intermittent** — the first run came up, the second did not — because
it depended on both ids reaching one drain.

**The hospital survives because nothing projects for it.** It is on the zone's
own release, so it gets one id for one canonical.

**What the zone hands over is nothing but the engine's own vocabulary.** Ten
code systems, every one a `urn:dbo:` publication. A tenant declaring a code
system dependency on it inherits, exclusively, the set every tenant is already
given at bring-up. That is what made a duplicate certain rather than unlucky.

## Why it matters beyond a test

`EngineVocabularyDoesNotCollideWithItselfIT` exists because this vocabulary
has collided with itself before, across a stream: the second arrival was read
as a local override, parked, and re-attempted every round. That was fixed and
is proven — and the fix was unreachable for this case, sitting one question
behind a throw.

A deployment reaches this the moment a tenant a release behind declares a
zone, which is the ordinary arrangement the projection exists to serve. It
does not need the engine's own vocabulary to do it: any publication a
projection carries is handed over under a new id each time it is cut.

## What to do

1. Reproduce it small: one runtime, the zone, the r4 root, the insurer. If it
   holds, the count of tenants is not the cause.
2. Turn face images off for that run. If it holds, images are not the cause.
3. Find the two writers of the claim and decide which one should not be
   writing, which is the same question `EngineVocabularyDoesNotCollide`
   answered for the stream.

## What it unblocks

The cast is complete. `ScimProvisioningIT` wanted the insurer for its half
about a tenant that declares no directory and took a shared shape instead;
that, and every other class that wants an organisation a release behind, can
now have the member the sample world already describes.
