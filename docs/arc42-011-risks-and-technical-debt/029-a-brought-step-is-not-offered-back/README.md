**Open, and one question. A participant introduces a step, the face accepts a
run of it, the participant is healthy and performing the step it declared in
the same cycle — and a run of the brought one is not offered back to it within
two minutes. Everything up to the run being authored is proven in
`samples/spring-boot-worker-app`; the line after it is not asserted, because
nobody has explained it.**

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
