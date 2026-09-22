**Open. The store promises that reaching data means performing a step, and a
plain read still bypasses it. The slice that exists is built and proven; what
is left is traversal, a write refusal that needs it, and demoting the general
surface. The traversal half is not this item's to design — item 021 defers the
same question from the other side and has it written out, and one answer has to
serve both doors.**

# The step-scoped API

Issue: [278](https://github.com/jengu-net/dbo/issues/278)

## What this is

The documentation already claims that access is granted to a step and that
there is no way to reach the data without performing the work that needed it —
`using-dbo.md` says so in the regulation mapping, and says it as the answer to
a legal obligation. It is not true. A credential holding `system/*.read` reads
any record with no step anywhere in the picture, which is how all nine written
guide chapters work.

The rule is written up as a crosscutting concept
([reaching the data](../../arc42-008-crosscutting/reaching-the-data/README.md)).
This is the smallest implementation of it that can be proven by tests and
demonstrated by executed examples in the guide — so the claim stops being
aspirational, and so the guide has something to teach besides the deployment's
own door.

## Where it stands

Built and proven. The slice turned out to be enforcement rather than design:
three promises were already PROVEN — a step declaration names its input slots,
a run's inputs fill them fixed at creation with undeclared and unfilled slots
both refused by name, and each input renders as `Task.input`, all by
`WorkLeavesTheClinicAndComesBackIT`. `Run` already carried the slot-to-reference
map, so the anchor was neither invented nor stored here.

What this added: a tenant declaring the steps it offers, a door that turns a
call into a run, a run context that answers for what the run named, a reading
through that context recorded as a disclosure naming the run, an end to the run
that takes its context with it, and the proof that a withheld record and an
invented id answer identically — as do an ended run and one that never was.

Still open, deliberately: reach is the named documents with no traversal, the
context is read-only, and the general surface is untouched.

## Sequence

1. ~~A step declares its slots~~ — **done already**, and proven.
2. ~~A run's inputs fill them~~ — **done already**, and proven.
3. ~~A tenant declares its steps in its spec~~ — **done**.
4. ~~Starting a run over HTTP, naming a document per slot~~ — **done**.
5. ~~A run-scoped read surface answering for `run.inputs()`~~ — **done**.
6. ~~A client holding `work` and not `system/*`~~ — **done**; registration.
7. ~~The context's metadata lists only the declared types~~ — **done**.
8. ~~A guide chapter using it~~ — **done**: `runs.md`, running as twenty-one
   steps of the shared world and in `docs/guide/examples/check.sh`.
9. ~~The reading is on the record, naming the run~~ — **done**. The mechanism
   was already there: `Caller.setRun` makes the engine record an access entry
   whatever the tenant's audit level, and this surface simply never set it.
10. ~~The context stops answering when the run ends~~ — **done**, with a verb
    for ending it. A run is over when nobody holds it.

`PROC_A_RUN_ANSWERS_ONLY_FOR_ITS_INPUTS` and
`PROC_A_RUN_CONTEXT_ENDS_WITH_ITS_RUN`, both proven on shared-world steps;
`POL_TRAVEL_AND_ACCESS_ARE_DIFFERENT_ENTRIES` gains a site there. The harness
test that owned a world for this is gone — everything it asserted is asserted
over HTTP, which is where the rule about which world a test belongs in puts it.

## Decisions

**Reach is the documents the run names, and nothing else.** No graph traversal
in this slice. `Run.inputs` already holds exactly that, and the slot discipline
around it is proven, the boundary is trivial
to state and to test, and reference-following can be added later without
changing what a step declares. Traversal first would have meant designing depth
limits and cycle rules before anything could be demonstrated.

**Synchronous only.** The step is performed inline and the context answers
immediately. Queued steps with runners claiming work are the larger half of the
work model and are not needed to prove the boundary; they are also the half
that cannot be shown with a curl in a guide.

**Out of reach answers not-found, never forbidden.** A boundary that
distinguishes *you may not see this* from *this does not exist* confirms
existence to anyone who probes it, and the store already refuses to make that
distinction elsewhere — no authority answer says whether a subject exists.

**The general surface stays.** Demoting it is a separate decision with its own
migration; this slice adds a door rather than closing one. The guide will say
which is which.

**A run is ended at its own address, not on the lane.** The lane's `closed`
verb takes the run a participant was handed by a poll, which is the
asynchronous half — and this slice is the synchronous one, where the caller
starts the run, performs it inline and says so. Sending it round the lane would
have meant a poll and a claim to close work nobody queued, and none of it
demonstrable with a curl. So the run door ends the run, and a participant that
polls still closes on the lane.

**Ending it twice is not an error.** The second call finds a run nobody holds,
which answers as a run that is not there — which is what it asked for.

**A run context is addressed by the run's key, in the path.** It is honest,
it is greppable in a log, and a FHIR client configured with that base URL works
unmodified — which is the property that makes this an integration surface
rather than a bespoke protocol.

## Not doing

- **Asynchronous steps, lanes, runners claiming work.** Already partly built
  elsewhere and not needed to prove the boundary.
- **Reference traversal from the anchor.** Deliberate; see above. **And not
  this item's to design**: [asking the store](../021-asking-the-store/README.md)
  defers the same thing from the other side and has the five questions written
  out — deduplication across a page, N+1, the membrane, a reference out of the
  store, and the one that binds them, *an include is not a widening*. A run
  context following a reference and an include bringing a record along are the
  same act through two doors. If only one door holds that invariant the other
  is the way round it, so whichever is built first answers for both.
- **Write refusal for out-of-reach references.** Wanted eventually — a write
  that references something out of reach must be refused or the context
  smuggles links — but it needs traversal to be meaningful.
- **Per-step capability statement generation beyond the type list.** The
  minimal context lists its types; generating the full contract from a
  declaration is the next slice.
- **Demoting `system/*` or changing any existing chapter's examples.** The
  guide gains a chapter; it does not lose nine.

## Verifying

Every line of the acceptance is a step of the shared world's work story, and
the same commands run in `docs/guide/examples/check.sh`:

- a request inside a run reads a document the run names — 200;
- the same credential, same document, outside any run — refused;
- a document of a declared type that the run does *not* name — 404, byte for
  byte the answer an invented id gets;
- a type the step never declared — 404, and absent from the context's
  `/metadata`;
- the trail shows the read, naming the run — from the run, for what it opened,
  and from the document, for who read it;
- a run's context stops answering once the run has ended, byte for byte as a
  run that never existed.

```
./gradlew :guide:test --tests '*WorkAndHowFarARunReaches*'
DBO_GUIDE_COMPOSE="$(docs/guide/examples/tree-world.sh /tmp/tree.yaml)" \
    ./gradlew :guide:test
```

The pin moves with the chapter, in both compose files, because a chapter
demonstrating a surface the pinned image does not carry fails the build it
arrives in — and the image that could carry it is only built from a main the
chapter would have reddened. So the code lands first and the chapter follows
the image built from it.
