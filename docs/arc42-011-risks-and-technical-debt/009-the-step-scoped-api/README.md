**Open. The store promises that reaching data means performing a step, and a
plain read still bypasses it. The slice that exists is built and proven; what
is left is traversal, a write refusal that needs it, and demoting the general
surface. The traversal half is not this item's to design — item 021 defers the
same question from the other side and has it written out, and one answer has to
serve both doors. How an application reads what its run produced is proposed
below and awaits review.**

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

The context and the `done` verb answer only the client holding the run: the one
that started it at the step door, until a lane claims it, and then the client
that claimed it, recorded on the run as its claimant. Any other credential with
`work`, holding the run's id, gets the 404 a run that never existed gets
(`PROC_A_RUN_CONTEXT_IS_ITS_PERFORMERS`).

Still open, deliberately: reach is the named documents with no traversal, the
context is read-only, and the general surface is untouched.

## The run answers its initiator

A run started at the step door records the client that asked for it, and
`GET /t/<tenant>/run/<id>` — the run's own address, beside its context and its
`done` verb — answers that client with the run as a `Task`: its key as the
`urn:dbo:run` identifier, a status derived from who holds it, the slots as they
were filled, and the step's result as outputs — the tally, each version it
produced, and the milestone it reached. The rendering is the records surface's
own, so a run has one shape whichever door shows it.

It answers after the run has ended, unlike the context, because the end is what
the asker is waiting for; and it carries no document, so reading it is not a
disclosure and leaves no access entry. Anyone else — another client, a
credential without `work`, a run started any other way, a run that does not
exist — gets the same 404; no credential gets 401. An application reads it
with `DboInitiator.answer`, or `awaiting` to poll until the run comes to rest
(`PROC_A_RUN_ANSWERS_ITS_INITIATOR`).

## A result is written by the tenant

A step's result carries what it counted and the records it wants the tenant
to hold — `Outcome.done(tally).writing(Write.create(patient), …)`. The
lane carries them to the tenant on one verb, `committed`, which writes them and
closes the run in that order; the participant that performed the step never
holds a records credential.

**The tenant writes through its own face.** The records go into one transaction
bundle, written under the run (`Caller.setRun`) by the same facade a bundle
posted to the records surface reaches. So nothing about a step's write is
special: the profile validates it, the identity rules hold, the membrane seals a
person's identifying elements, the trail names the run as the occasion, and a
`urn:uuid` reference between two records of one result resolves inside that
one commit.

**What a step may write is declared beside what it takes** — `"writes":
["Patient", "Encounter"]` on the step in the tenant's spec, each a type the
tenant holds. A record of any other type is refused by name before the face
sees it, and so is a record whose own `resourceType` is not the type its write
names. A step that declares nothing writes nothing.

**A refused result ends the run; a failure is released.** The two want
opposite recoveries. A result refused for what it says — validation, an identity already
held, a version that moved, an undeclared type — would be refused again in the
same words, so the run ends with nobody holding it and the tenant's reason on
it (`Run.refused`), and its `Task` reads `failed` with that reason as the
contained outcome. A step that threw, timed out or could not reach the tenant
is released and taken again, as before. So the application that asked can tell
done (`completed`), refused (`failed`) and still owed apart.

**The run names what it produced, and that is the production path.** The
versions the face answered with are recorded on the run in the same advance
that closes it, so a reader never sees a run that has written everything and
is still held. Nothing else fills a run's manifest in a deployment.

Two limits, said rather than hidden. The records and the run are not one
transaction — the records are the tenant's and the run is the work domain's — so
a crash between the commit and the close leaves records the run does not name,
and the run is released and may write them again. The one refusal closing can
make (a step whose declared actions omit `close`) is asked before anything is
written, so it never strands records. And a step brought by a participant, or
one the deployment declares for the fleet, has no `writes` in any tenant's spec
and so writes nothing yet.

`PROC_A_RESULT_IS_WRITTEN_BY_THE_TENANT` and `PROC_A_REFUSED_RESULT_ENDS_THE_RUN`,
proven over the lane in `AResultCrossesTheLaneWholeTest`, in the rendering in
`ARunsResultIsItsTasksOutputTest`, and end to end on the edge round-trip story,
where the worker application registers somebody by giving the person and then
tries again.

## The asker collects what the run produced

**Proposed, for review. Nothing below is built.**

An application learns from the answer *which* record a run wrote, as
`Type/id/_history/n`, and cannot read it: the credential that asks for work
holds no records door, and the run's context has closed by the time the
versions exist, because they are recorded in the same advance that ends the
run. So every story leg that proves what a step wrote reads it on the general
surface, which is the door this item exists to demote.

### The decision

**A step may declare an answer, and its run is then held by the asker until
the asker has collected it.** The step names the audience the asker is:

```json
{ "code": "hogwarts.admission.register", "slots": { "patient": "Patient" },
  "writes": ["Patient", "Encounter"], "answers": "desk", "collect": "PT15M" }
```

`desk` is an ordinary entry in the tenant's `disclosure.perAudience`. When the
result is written, the run does not close: it passes to a new holder, the
asker, with `collect` as its `until`. While the asker holds it, the run's
existing context at `/run/<id>/fhir/…` answers **the client recorded as its
requester and nobody else**, for exactly what the run was given and the
versions it produced, read as those versions (`Type/id/_history/n`) rather than
as whatever is current. The asker ends it with `POST /run/<id>/done`, or the
`until` lapses; either way the run is over and its context answers as a run
that never existed. A step that declares no answer closes as it does today.

So a run's reach still ends with the run; the work is simply not over until
its result has reached whoever asked, and a run an asker never collects is
visible as state, not folded out of the trail.

### Why not the others

- **Content in the answer.** The answer outlives the run by design and is
  polled by `awaiting`; content in it would be a standing way in behind the
  work, and either every poll is a disclosure or none is. The `Task` is also
  the records surface's own rendering, so content would appear on every door
  that shows a run.
- **A step whose result is content.** The content would have to rest on the
  run record, in the work domain, outside the person vault — where erasure
  cannot reach it and the fleet's rule that payloads travel sealed does not
  hold. And the performer, not the tenant, would decide what is disclosed.
  A read step is a step with an answer and no `writes`.
- **The performer's context, unchanged.** It ends at the close that makes the
  versions exist, so it can never show them.

### Personal data, purpose, the trail

- **What the asker sees is the audience the step names.** Its `types` bound
  what is collectable and its `reveals` fixes the mode; the request cannot
  raise it. No audience named means references only. A step naming an
  audience the tenant has not declared is refused when the declaration is
  read, since that is configuration and not a serving answer.
- **The strict mode is the default.** On a tenant with `pdi: true` an `omit`
  audience collects the record pseudonymously, birth date generalised to the
  year — the record as the vault leaves it, reassembled at read time, so an
  erasure after the run still reaches everything the asker could collect.
- **The purpose is the step's.** An audience that reveals `include` is
  refused at declaration unless the step states `"purpose": "TREAT"` (any
  statable code). An identifying collection also states the same code in its
  `Purpose-Of-Use` header: two keys, the step's said when it was declared and
  the request's said at the moment of reading. A header naming another code, or
  none, gets the audience's default and never more. The start door still states
  and accepts none, so a slot search on an identifying element stays refused.
- **Collecting is a reading.** Each read in the asker's phase is an access
  entry about the document, landing beside every other reading of it, with the
  asker's client as actor, the run as occasion and the step's purpose. It is
  never travel. Reading the answer itself stays no entry, because it carries
  references only.

### Search

**A set is a repeating slot.** *The ward* is a step with `"encounters":
"Reference(Encounter)[]"`, started over `Encounter?status=in-progress`; the
door resolves the search when the run is authored and records what matched,
which is already built, and the asker collects them. Listing and counting
without content is [asking](../021-asking-the-store/README.md), on the other
side of the asking-against-unsealing line. No search runs inside a context: it
would be traversal by another name, and traversal is 021's to answer for both
doors.

### The listed gaps

- **Run-scoped content.** Collection is the shape a run-scoped read of content
  takes: a `Binary` the run named or produced, under the same audience and
  purpose. Carrying content in with a run and out with a result is separate.
- **`Write.delete`.** A deletion produces a tombstone version; the answer names
  it and collecting it answers gone, as a version read does, disclosing
  nothing.
- **Conditional create.** When the condition matches, the run names a version
  it did not write — so it must be named as matched rather than produced, and
  a condition on an identifying element is an identification, refused as a
  slot search is.

### Promises it would add

- `PROC_A_RUN_IS_COLLECTED_BY_ITS_ASKER` — a step that declares an answer
  leaves its run held by the asker, who alone reads what it was given and the
  versions it produced, and ends it.
- `PROC_AN_UNCOLLECTED_ANSWER_LAPSES` — past the window the context answers
  as a run that never existed; the answer still names the versions.
- `PROC_THE_ASKER_READS_NOTHING_WHILE_THE_WORK_IS_DONE` — while automation
  holds the run, its context does not answer the asker.
- `IDN_THE_ASKER_IS_A_DECLARED_AUDIENCE` — what the asker sees is the audience
  the step names, and none named is references only.
- `IDN_A_STEP_STATES_ITS_PURPOSE` — an identifying collection needs the
  step's declared purpose and the same code stated by the request; neither
  alone reveals more than the audience's default.
- `POL_COLLECTING_IS_A_READING` — each collection is an access entry naming
  the asker, the run and the purpose.

### What it closes

The guide gaps "an application cannot read a record's content back through a
run" (care is recorded), "reading her back is the records surface's" (what a
person can ask for) and the answer half of the read-only context bullet (work
leaves and comes back). The legs that move: `whatWasWrittenIsWhatIsRead` in
`TheClinicRecordsCareAndAccountsForItIT`, and `readingHerIsNotTheSameAsWritingHer`
in `WhatAPersonCanAskForIT` — the pseudonymous read through an `omit` desk
step, the treating read through a step declaring `TREAT`. Finding her by her
number stays at the identifying door.

### Build order

1. The context checks whom it serves: the performer while automation holds the
   run, nobody else. Today any `work` credential enters any held context.
2. `answers`, `collect` and `purpose` in the tenant's spec, with the
   declaration-time refusals.
3. The asker as a holder; `committed` passes an answering run to it; the `Task`
   status reads `completed` with the holder on it.
4. The asker's reach: inputs and produced versions, by version, under the
   audience's mode and the step's purpose, with the access entry.
5. The lapse, on the sweep that already finds lapsed claims.
6. `DboInitiator.collect` and `collected`, in the sample worker application.
7. The two legs move and the guide's gaps are rewritten. Each promise lands
   with the commit that proves it.

### Decided

- **Two keys for `include`.** The step declares the purpose, and the
  collecting request states the same code. Either alone reveals nothing more
  than the audience's default, which is how an identifying lookup already
  works: the reason is said at the moment of reading, not only at design time.
- **Finding somebody by her number stays at the identifying door.** It never
  becomes a step. The application asks there, with a purpose, and starts a run
  over the id it is given.
- **A version may be collected any number of times within the window,** each
  collection a reading of its own on the trail. A retry or a reload is the
  ordinary case, and one collection per version would lose the answer to a
  dropped connection.

### Open question

**A new holder, or a window outside the hold?** `Holder` is load-bearing; an
asker holding a finished run is honest about who owes the next act, and adds a
fifth value every run list must show.

## Sequence

1. ~~A step declares its slots~~ — **done already**, and proven.
2. ~~A run's inputs fill them~~ — **done already**, and proven.
3. ~~A tenant declares its steps in its spec~~ — **done**.
4. ~~Starting a run over HTTP, naming a document per slot~~ — **done**.
5. ~~A run-scoped read surface answering for `run.inputs()`~~ — **done**.
6. ~~A client holding `work` and not `system/*`~~ — **done**; registration.
7. ~~The context's metadata lists only the declared types~~ — **done**.
8. ~~A guide chapter using it~~ — **done**:
   [work leaves and comes back](../../guide/work-leaves-and-comes-back.md#a-runs-own-context).
9. ~~The reading is on the record, naming the run~~ — **done**. The mechanism
   was already there: `Caller.setRun` makes the engine record an access entry
   whatever the tenant's audit level, and this surface simply never set it.
10. ~~The context stops answering when the run ends~~ — **done**, with a verb
    for ending it. A run is over when nobody holds it.

`PROC_A_RUN_ANSWERS_ONLY_FOR_ITS_INPUTS` and
`PROC_A_RUN_CONTEXT_ENDS_WITH_ITS_RUN`, both proven on legs of the edge
round-trip story, over a ward of the story's own whose step nothing performs —
because a run's context closes when the run does, and the sample worker
performs every step the world declares within a poll. The harness
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

Every line of the acceptance is a leg of the edge round-trip story:

- a request inside a run reads a document the run names — 200;
- the same credential, same document, outside any run — refused;
- a document of a declared type that the run does *not* name — 404, byte for
  byte the answer an invented id gets;
- a type the step never declared — 404, and absent from the context's
  `/metadata`;
- a run's context stops answering once the run has ended, byte for byte as a
  run that never existed.

```
./gradlew :samples:spring-boot-server-app:storyTest --tests '*WorkLeavesTheClinicAndComesBack*'
```
