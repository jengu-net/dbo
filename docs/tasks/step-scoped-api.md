# The step-scoped API

Issue: jengu-net/dbo#278

## What this is

The documentation already claims that access is granted to a step and that
there is no way to reach the data without performing the work that needed it —
`using-dbo.md` says so in the regulation mapping, and says it as the answer to
a legal obligation. It is not true. A credential holding `system/*.read` reads
any record with no step anywhere in the picture, which is how all nine written
guide chapters work.

The rule is written up as a crosscutting concept
([reaching the data](../arc42-008-crosscutting/reaching-the-data/README.md)).
This is the smallest implementation of it that can be proven by tests and
demonstrated by executed examples in the guide — so the claim stops being
aspirational, and so the guide has something to teach besides the deployment's
own door.

## Where it stands

Groomed; nothing built. **Half of what this looked like is already proven**, so
the slice is enforcement rather than design:

- `REQ-DBO-PROC-STEP-DECLARES-ITS-SLOTS` — PROVEN. A step declaration names its
  input slots, ordered.
- `REQ-DBO-PROC-RUN-INPUTS-FILL-THE-SLOTS` — PROVEN. A run's inputs fill them,
  fixed at creation; undeclared and unfilled slots are refused by name.
- `REQ-DBO-PROC-TASK-CARRIES-THE-INPUTS` — PROVEN. Each input renders as
  `Task.input`.

All three by `WorkLeavesTheClinicAndComesBackIT`. `Run` carries
`Map<String, String> inputs` — slot to `Type/id`. The anchor is not invented
here and not stored here. What is missing is that nothing enforces it, and
nothing outside the JVM can declare a step or start a run.

The other pieces it builds on:

- **`Run`** carries `process`, `step`, a `key`, an assignment and what it
  produced.
- **`Manifest`** already names *the documents a run works on* — `inputs`, a
  slot to a `Type/id` each, "exactly as the run names them". This is the anchor
  primitive; it does not have to be invented.
- **`StepGrant`**, **`ExecutorDeclaration`** and **`StepIntroduction`** decide
  who may perform a step.
- **`WorkScopedStore`** already makes a run record what it produced.
- The trail already names the run a change belonged to.
- The per-tenant authority already issues credentials and validates scopes.

What is missing is a step saying *what data it may reach*, a surface that turns
that into a boundary, and a credential bound to a run.

## Sequence

1. ~~A step declares its slots~~ — **done already**, and proven.
2. ~~A run's inputs fill them~~ — **done already**, and proven.
3. A tenant declares its steps in its spec, beside `types`. **todo**
4. Starting a run over HTTP, naming a document per slot. **todo**
5. A run-scoped read surface at `/t/{code}/run/{key}/fhir/…`, answering for
   `run.inputs()` and 404 for everything else. **todo**
6. A client holding `work` and not `system/*`, so the same token is refused by
   the general surface. **todo** — registration, not a change.
7. The context's `/metadata` lists only the declared types. **todo**
8. A guide chapter using it, with executed examples. **todo**

The promise this claims: `PROC_A_RUN_ANSWERS_ONLY_FOR_ITS_INPUTS`, proven by
`AStepReachesOnlyWhatItNamedIT`.

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

**A run context is addressed by the run's key, in the path.** It is honest,
it is greppable in a log, and a FHIR client configured with that base URL works
unmodified — which is the property that makes this an integration surface
rather than a bespoke protocol.

## Not doing

- **Asynchronous steps, lanes, runners claiming work.** Already partly built
  elsewhere and not needed to prove the boundary.
- **Reference traversal from the anchor.** Deliberate; see above.
- **Write refusal for out-of-reach references.** Wanted eventually — a write
  that references something out of reach must be refused or the context
  smuggles links — but it needs traversal to be meaningful.
- **Per-step capability statement generation beyond the type list.** The
  minimal context lists its types; generating the full contract from a
  declaration is the next slice.
- **Demoting `system/*` or changing any existing chapter's examples.** The
  guide gains a chapter; it does not lose nine.

## Verifying

The slice is done when these pass and the guide chapter runs in
`docs/guide/examples/check.sh`:

- a request inside a run reads a document the run names — 200;
- the same credential, same document, outside any run — refused;
- a document of a declared type that the run does *not* name — 404;
- a type the step never declared — 404, and absent from the context's
  `/metadata`;
- the trail shows the read, naming the run;
- a run's context stops answering once the run has ended.

```
./gradlew :core:harness:test --tests '*StepScopedAccessIT*'
./docs/guide/examples/check.sh
```
