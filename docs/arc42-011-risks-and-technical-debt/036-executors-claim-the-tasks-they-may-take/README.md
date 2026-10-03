**Proposed, for review. Nothing is built and no issue is filed. A run's
holder answers three questions in one field, and automation can take work it
was never meant to touch; this is the design that separates them, with four
questions only the user can answer.**

# Executors claim the tasks they may take

## What this is

Every open run of a step is a task on that step's list. Executors — workers
on a lane, the fleet processor, an edge device behind its router, people
through an application — take what they are eligible for. Automation takes
only what the step's criterion admits, and a failure returns to the list with
its eligibility set by declared policy.

## What is wrong now

`Holder` — `automation`, `retry`, `person`, `nobody`
(`PROC_RUN_SAYS_WHO_HOLDS_IT`) — carries three facts:

- **lifecycle**: `nobody` means over, and means done or abandoned alike;
- **claimant**: who is executing now, which `Run.Assignment` also records;
- **eligibility**: `retry` means machines again later, `person` means humans.

What that costs, read in the code:

- `Participation.poll` offers every open run, so automation can claim a run
  held by `person`, and `Runs.claim` makes it `automation`.
- A person taking a run through `Runner.open` becomes `automation` too, and
  the `Task` names a `Device` as owner.
- A released run keeps `automation` with no executor and renders
  `in-progress` while nobody works on it.
- `retry` is set only on a sweep's items; a run that failed transiently is
  claimable again at once.
- `Failure.of` reads any fault not known to be the record's as transient, so
  an unknown fault is retried silently and without end.

## The model

**A run is a FHIR `Task`, and its fields keep their published meaning.** The
run answer already renders one; the change is that the stored run holds what
the rendering now derives.

| Fact | Field | Values |
|---|---|---|
| lifecycle | `Task.status` | `ready`, `in-progress`, `on-hold`, `completed`, `failed`, `cancelled` |
| claimant | `Task.owner` | an executor as `Device`, a person as `PractitionerRole` |
| eligibility | `Task.performerType` (R5 `requestedPerformer`) | `automation` and `person`, or `person` alone |
| retry timing | `Task.restriction.period.start` (R5 `requestedPeriod`) | not before |
| why it stands so | `Task.statusReason` | the failure or fall-through, in words |

`ready` is unclaimed and open; `in-progress` is claimed; `on-hold` is
unclaimed with its not-before still ahead; `cancelled` is ended without being
done, which separates abandoned from `completed`. `businessStatus` keeps the
milestone and stops carrying the holder.

**A person may always take an open task.** Manual is already the baseline for
a step nobody automated, so eligibility is a single question: is the task
open to automation as well. One flag, plus the not-before, decides it.

**Who owes the next act is derived, not stored.** A claimed task waits for its
owner. An unclaimed task open to automation waits for a machine and is nobody's
card. An unclaimed task open only to people waits for a human. The operator's
"waiting for a human" list is the query `status=ready&performer-type=person`
with no owner.

## Claiming

A claim stays a conditional write against the version the claimant saw, so the
race guard and the lease exist already (`PROC_CLAIM_IS_THE_INTERSECTION`). What
a claimant may take becomes the intersection of three things: its credential,
the scope the step admits, and the task's eligibility. A machine is refused a
task closed to automation, at `poll` and again at `claim`.

A person claims as a `PractitionerRole` the tenant holds, named as
`Task.owner`, on a lease extended by checkpoints as an executor's is. Under
[item 009](../009-the-step-scoped-api/README.md)'s reach rule — the performer
while it holds the run — the run context then answers that person, and each
reading names them on the trail.

## Criteria for automation

**A step declares when automation may take it**, as a FHIRPath condition over
the task's inputs:

```json
{ "code": "lab.result.verify", "slots": { "result": "Reference(Observation)" },
  "automate": { "when": "result.interpretation.coding.code = 'N'" } }
```

**The store evaluates it once, when the task is authored.** A run's inputs
are fixed at creation, so the answer cannot change afterwards. The executor
cannot evaluate it: it would have to read the inputs to learn whether it may,
and reading is what a claim confers. The FHIRPath subset the in-heap checker
runs serves; a condition outside it is refused at declaration.

**A condition may not read an identifying element.** The store would have to
unseal it to decide, and an eligibility decision is not a disclosure with a
purpose. It is refused at declaration, naming the element.

A step with no `automate` is open to automation, as today. The per-scope
switch stays, read at claim time (`PROC_AUTOMATION_IS_A_DECLARED_SWITCH`): the
criterion is the step's and fixed, the switch is the deployment's and can
change while the task waits.

## Failure is routed by declared policy

The step declares which faults are transient:

```json
"retry": { "on": ["unreachable", "lapsed"], "after": "PT1M", "attempts": 5 }
```

- **A declared transient fault** returns the task `on-hold`, open to
  automation from now plus `after`. The attempt is counted, and past
  `attempts` the task goes to a person. `unreachable` is the store's own
  `StoreUnreachableException`, and `lapsed` a lease nobody extended.
- **A record fault** — the classes `Failure.of` already names, and a result
  the tenant refused — ends the task as `failed`, as a refused result does now.
  Trying again would be refused in the same words.
- **Any other failure returns it to the list open only to people**, with the
  failure as `statusReason`.

That last case is the user's "a failure not known to be fixable by hand still
goes to a human", stated precisely. It does not assume a person can perform
the step. It means a person is the only executor left who can judge, and the
task offers them three acts: perform it by hand, return it to automation with
a reason (the `reopen` action, narrowed by the step's actions), or end it as
`cancelled`.

This reverses `Failure.of`'s default, which calls an unknown fault transient
so a person's queue does not fill with faults nobody can fix. A declared list
answers that better: what the step knows will pass never reaches a person, and
an unknown fault that recurs is what somebody should see.

## The collection window is not a task

Item 009's window — the requester may collect until T — is access on a
finished run, not an obligation. It sits on a `completed` task as an extension
and appears in no list of open tasks, and nobody claims it.

## What changes

**Promises.**

| Constant | Fate |
|---|---|
| `PROC_RUN_SAYS_WHO_HOLDS_IT` | replaced by `PROC_A_RUN_KEEPS_STATUS_CLAIMANT_AND_ELIGIBILITY_APART` and `PROC_WHO_OWES_THE_NEXT_ACT_IS_DERIVED` |
| `PROC_ESCALATION_BY_FAILURE_CLASS` | reworded: declared transient faults retry after a delay, record faults end the task, everything else goes to a person |
| `PROC_FAILURE_IS_RELEASED` | reworded: released to the list, open to automation only for a declared transient fault |
| `PROC_CLAIM_IS_THE_INTERSECTION` | reworded: credential, scope and eligibility |
| `PROC_FALL_THROUGH_IS_COUNTABLE` | reworded: open only to people, not held by a person |
| `PROC_CLOSED_CAN_BE_REOPENED` | reworded: reopening states whether automation may take it |
| `PROC_RUN_ENVELOPE_DISCLOSES_STATE_NOT_SUBJECT` | reworded: status and eligibility where it says holder |
| `PROC_A_RUN_CONTEXT_ENDS_WITH_ITS_RUN` | reworded: the context answers the owner while the task is claimed |
| `PROC_AUTOMATION_IS_DECLARED`, `PROC_AUTOMATION_IS_A_DECLARED_SWITCH`, `PROC_A_STEP_GRANTS_THE_RIGHT_TO_OVERRIDE`, `PROC_THE_ROUTER_HOLDS_THE_CLAIM`, `PROC_RUN_KINDS`, `PROC_CLOSE_BY_RE_EVALUATION`, `PROC_A_RUN_ANSWERS_ITS_INITIATOR` | kept |
| new | `PROC_AUTOMATION_TAKES_ONLY_WHAT_ITS_STEP_ADMITS`, `PROC_A_PERSON_CLAIMS_AS_A_PRACTITIONER_ROLE` |

**The operator's surfaces.** `Runs.holding`, `matching` and `backlog`, the
console's `RunView` and its `--holder` option, and the asking vocabulary's
`open`, which today searches `owner=` over holder words, all ask by status and
eligibility instead.

**The run answer.** Status from the stored status, so a released run reads
`ready`; `owner` may be a `PractitionerRole`; `performerType`, `statusReason`
and `restriction` appear. `DboInitiator.Answer.settled` changes with it: today
`ready` counts as settled, which would wrongly include a task waiting for a
machine.

**The lane.** `poll` and `claim` take eligibility into account; `released`
carries the failure for the policy to route; a person's claim names the role.

**The `nobody` checks.** Eleven places, in `Runs`, `Run.open`, `StepSurface`,
`ProvingLane`, `Across`, `Asking` and `Holder.of`. Each becomes "is the task
over" through `Run.open`, and the context's two checks become "is the caller
its owner".

**The stories.** US-DBO-EDGE-ROUNDTRIP asserts `automation` at its claim
leg, `nobody` on its close and `done` legs, and a `holder` envelope path; the
step-for-every-tenant story waits for `nobody`; the care story sets `person`
to build a run that needs somebody.

**The guide.** [Processes and steps](../../guide/processes-and-steps.md)'s
"one nobody holds is over" gives way to the list, eligibility and failure
policy, quoted from the stories.

## Build order

The wire format changes: `holder` goes. The first commits keep it as a field
derived from the new ones, so readers move one at a time and every story stays
green.

1. Every `nobody` check goes through `Run.open`. No behaviour changes.
2. The run stores status, eligibility and not-before beside `holder`, and
   `holder` is written as derived from them. A person's claim derives
   `person`, which fixes the conflation already.
3. `poll` and `claim` refuse a task closed to automation.
4. `retry` on the step and the failure routing, with the delay and the count.
5. `automate.when`, compiled and refused at declaration, evaluated at
   authoring.
6. A person's claim as a `PractitionerRole`, in the sample worker application.
7. The answer renders the stored fields; `settled` follows.
8. The operator's surfaces and the asking vocabulary move to status and
   eligibility.
9. The stories assert status, owner and eligibility; `holder` is dropped from
   the envelope, the run and the vocabulary. The guide is rewritten. Each
   promise lands with the commit that proves it.

## Open questions

1. **How a person proves who they are on a claim.** With their own token
   through the tenant's identity provider, mapped to a `PractitionerRole`, or
   on the application's credential with the application naming the role? The
   first is proposed: a trail entry that says what an application asserted is
   weaker than one the person signed.
2. **Is a lapsed lease transient?** It is the commonest failure there is, and
   an executor that dies says nothing about why. Proposed: declarable as
   `lapsed`, and not transient by default.
3. **Past the attempt cap, a person or the end?** Proposed: a person, open
   only to people, so a fault that will not pass is seen.
4. **Does `holder` stay readable for one release** for applications outside
   this repository, or does it go when the last reader here moves?
