**Open, and every mechanism is now exonerated. Six candidates have been tested
and none reproduces it: the poll's filters, the declaration's domain, the
entitlement, the feed's domain, the face's document door, and the executor's
scope. Two harness probes do in eighty-five seconds what the sample takes five
minutes to fail at, and both pass. What remains is not a mechanism but a
setting: the sample is an embedded container polling continuously against a
tenant under concurrent write load, and nothing in the harness reproduces
that.**

# A brought step's run is not offered back

## What this is

`MeasuringASpecimen` in the worker sample is a Spring bean returning
`Optional.of(DECLARED)` for a step `hogwarts` never declared. The end-to-end
test proves two things and stops:

- **The tenant's own step door still refuses it**, by name. That is right, and
  it is `PROC_INTRODUCTION_GRANTS_NOTHING`: the door is built from the spec, so
  bringing a capability is not a way in.
- **The face takes a run of it**, authored by the tenant as a Task naming the
  process, the step and a reference per slot. That is the introduction having
  reached the catalogue a run is checked against, which is what
  `PROC_STEPS_ARRIVE_BY_INTRODUCTION` promises.

What is not asserted is the line after: that the worker then performs it.

## What was observed

In the same context, in the same run:

| | |
|---|---|
| the spec-declared step | authored, offered, performed, run names this executor |
| the brought step | authored and accepted, never offered within two minutes |

The worker is healthy by then. The startup transient — the credential refused
for about ninety seconds while the tenant finishes coming up — has cleared, and
the declared step's run is performed after it. So this is not the transient,
and it is not the runner.

**The runner is ruled out by a fast test.**
`AnIntroductionSurvivesAnUnreachableLaneTest` in `core/dbo-runner` proves both
of the things that could have been its fault: an introduction refused while the
store was unreachable is made again on the first cycle that completes, and a
service the lane offers no work for costs the service beside it nothing. Twelve
seconds, no container.

## What is now known, by measurement rather than reading

Probed by printing what the store holds the moment the face accepts the run,
then asking for four minutes whether it is ever performed.

**The run exists and is exactly right.**

```
key=hogwarts.admission.assay/a-brought-assay  process=hogwarts.admission
step=assay  holder=AUTOMATION  milestone=null
```

Process and step are split the way the poll filter expects, so
`steps.contains(run.step())` matches. `holder=AUTOMATION` means *a step is
executing, nobody needs to do anything* — the state a run waiting for a
participant is in, not a stalled one.

**The service is wired.** `performing()` answers with both steps, the brought
one included.

**The credential is entitled to everything**, which answers the second question
this item was filed with. The worker's lane carries the test deployment's work
client, whose scope is `work`; `worksAsTheTenant` is `granted.contains("work")`,
so the entitlement is `everything()` and `entitlement.narrow(steps)` is a no-op.

**The lane reads the feed runs live on**, which answers the first. `laneFeed` is
a `PgChangeFeed` over `WorkModel.DOMAIN`, and the comment above that line warns
about exactly the failure this resembles — *a content feed polls a stream runs
never appear in, which looks exactly like a lane with no work, for ever*. It is
not that.

**And it is never offered.** A claim that fails says so, and no `claim failed`
is ever logged. Four minutes at a 507ms poll, after the startup transient has
cleared, in a context where the spec-declared step is performed.

## What the fast probe settled

`ARunIsOfferedWhicheverDoorDeclaredItsStepIT` mints two runs in one world that
differ in exactly one input — the declaration they came from. One is shaped as
`StepSurface` shapes a tenant's own (`of(code, "1", "r5")`, the face's domain
hardcoded); the other is introduced and carries its own (`work`), as the
sample's bean does. Both are then minted by the same call, and the lane is asked
what it offers.

**It offers both.** Fifty-one seconds, and it eliminates every remaining
hypothesis this item carried:

| filter | status |
|---|---|
| `steps.contains(run.step())` | passes |
| `run.item() == null` | passes |
| `!run.claimed(now)` | **passes** — a fresh run has no assignment |
| `Run::open` | **passes** — `holder != NOBODY`, and a fresh run is AUTOMATION |

And the declaration's domain decides nothing: an introduced step's run is
offered exactly as an installed step's is.

## What the second probe settled

`AFaceAuthoredRunReachesTheLaneIT` takes the remaining difference — how the run
came to exist — and varies only that. One tenant, one step a participant
introduces over its own lane, and two doors: a run authored through the face's
document door, and a run of the same step minted directly. Then the lane is
asked what it offers.

**It offers both.** Thirty-four seconds. So the face's document door is not it
either, and neither is anything about a step being introduced rather than
installed.

**And the executor's scope is eliminated with it.** That probe declares its
executor at an organisation, as the worker assembly does rather than at the
baseline a participant performing its own brought step would use — and it
changes nothing about what is offered.

## What is left is not a mechanism

Six candidates, each plausible from reading, each wrong under measurement:

| candidate | eliminated by |
|---|---|
| the poll's four filters | the first probe |
| the declaration's domain | the first probe |
| the credential's entitlement | reading `worksAsTheTenant` against the test deployment's client |
| the feed's domain | `laneFeed` is over `WorkModel.DOMAIN`, where runs are written |
| the face's document door | the second probe |
| the executor's scope | the second probe |

What the harness does not reproduce is the sample's **setting**: an embedded
container, a runner polling every 507ms from context start, and a tenant doing
heavy concurrent work — the same bring-up logs a reindex of 6,379 records and
several definition syncs. The probes author a run into a quiet tenant and ask
once.

**So the next move is instrumentation rather than another probe.** Something in
that setting stops a run reaching a participant that is polling for it, and the
cheapest way to see it is to log, inside `Participation.poll` on a sample run,
what the chunk contained and where the cursor stood — rather than to keep
guessing which condition it was from the outside. The hypothesis worth carrying
in is a cursor advanced past a row that committed after it was read, which is
the classic hazard of a sequence-ordered feed under concurrent writers, and
which a quiet tenant cannot show.

## What has not been checked

Named so that whoever picks this up does not start where this stopped.

1. **What the lane offers, for a step that is introduced rather than
   declared.** The poll filter is a set of bare step names and the brought one
   is in it; whether the tenant's side offers a run whose step reached the
   catalogue by introduction is the question.
2. **The entitlement the work credential yields.** `WorkGrants` gives
   everything to a credential that works as the tenant and otherwise the steps
   its scopes name. The guide's laboratory is provisioned with `["work"]` and
   is offered its brought run; whether the sample's lane credential resolves to
   the same entitlement has not been compared.
3. **Whether an authored Task becomes an offerable run immediately.** The face
   accepted it; that is not the same fact as a run being queued for a lane.

## Why it is filed rather than fixed

Because the sample is what found it, and the sample's job is done: the two
claims that hold are the ones item 027 needs — a bean can bring a capability,
and bringing one grants nothing. The third is a question about the store, not
about the application, and answering it by lengthening a timeout would be
asserting something nobody has explained.

**The guide proves the whole story over the distribution**, through
`sample/participant`, so this is not a gap in what the store is known to do.
It is a gap between that and what an application built on the assembly is
shown to get, which is exactly the duplication item 026 exists to close.

## What proves it

```
./gradlew :core:dbo-runner:test --tests '*AnIntroductionSurvivesAnUnreachableLane*'
./gradlew :samples:spring-boot-worker-app:test
```

The first is the runner, ruled out. The second is the sample, green on the two
claims — and the place the third assertion goes when there is an answer for it.
