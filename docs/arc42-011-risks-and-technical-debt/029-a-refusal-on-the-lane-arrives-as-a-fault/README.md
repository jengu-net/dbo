**Open, and diagnosed. Two defects, found by instrumenting the poll and the
claim. A settled refusal crosses the lane as HTTP 500, so a participant reads it
as a transient fault, carries on, and loses the run for good because the feed
cursor has already passed it — which breaks
REQ-DBO-PROC-REFUSED-IS-NOT-UNANSWERED by name. And the worker assembly scopes
every executor to the tenant's organisation, which for a step the application
itself brought is asking to vary its own step, and is correctly refused.**

# A refusal on the lane arrives as a fault, and the run is lost

## What actually happens

The run is offered. That was the last thing left to rule out, and instrumenting
`Participation.poll` ruled it out: every filter passes.

```
XPROBE work key=hogwarts.admission.assay/a-brought-assay step=assay
       match=true item=null claimed=false open=true
```

It is offered **once**, because the offer comes off a feed and the cursor is
acknowledged whether or not anything was taken. The runner then looks the
service up, finds it, and claims — and the claim comes back 500:

```
claim failed: tenant=hogwarts step=assay
  hogwarts: claim did not complete (500) — the verb did not complete
```

On the serving side that 500 is an ordinary, correct refusal:

```
Runs$NotAdmitted: step 'hogwarts.admission.assay' does not admit an executor at
scope organisation:hogwarts ('sample-admissions-worker'); it is not open to
local execution at all (not overridable is the default)
```

So the store refused exactly as it promises to, the participant was told the
store had broken, and the run it was holding an offer for went past the cursor
and was never offered again.

## Defect one: a refusal crosses as an unanswered call

**This is the serious half, and it is a promise violated rather than a gap.**
REQ-DBO-PROC-REFUSED-IS-NOT-UNANSWERED says a refusal and an unanswered call
are told apart on the exception, *because the two want opposite recoveries*, and
that its own text names this consequence — *a participant that confused them
would back off from work it is entitled to*.

`Runs.NotAdmitted` is a settled refusal. It reaches `LaneVerbService` unmapped,
becomes a 500, and `WireLane` turns 5xx into "did not complete" — which is the
right mapping for a 500 and the wrong answer about this exception.
`ALaneOverHttpIsIndistinguishableIT#aRefusalIsNotAnUnansweredCall` already
proves the property for the refusals that are mapped; `NotAdmitted` is not one
of them.

**And the cost is not just a misleading log.** Because the offer came off a
feed whose cursor is already acknowledged, a refusal that reads as transient is
a run nobody will be offered again. A refusal that read as settled would at
least be a decision somebody could act on.

## Defect two: the worker assembly scopes every executor to an organisation

`DboWorker` builds one identity per lane:

```java
new Executor(name, version, declared.getTenant(), Scope.organisation(declared.getTenant()))
```

For a step the tenant declared, that is a participant asking to act for that
organisation. **For a step the application brought, it is asking to vary its
own step** — and a declaration is not overridable by default, so the store
refuses. The participant this store already ships, `sample/participant`'s
`Laboratory`, declares `Scope.BASELINE`, and the guide performs its brought step
without trouble.

**The fix is not simply to change the constant.** A lane has one identity and an
application may perform both kinds of step over it, so scope is per step rather
than per lane: a service that carries its own declaration is the baseline for
it, and one filling a tenant's vacancy may legitimately be scoped to that
tenant. Whether the assembly should derive that from
`StepService.declaration()`, or let the application say, is the decision.

## What was eliminated on the way, and what that cost

Six candidates, each plausible from reading, each wrong under measurement: the
poll's filters, the declaration's domain, the credential's entitlement, the
feed's domain, the face's document door, and — tested in the wrong place —
the executor's scope.

**The scope was the answer and a probe said it was not.**
`AFaceAuthoredRunReachesTheLaneIT` declares its executor at an organisation, as
the assembly does, and passes — because it only ever **polls**. The admission
check is in `claim`, and a probe that never claims cannot see it. A probe that
exercises one verb proves something about one verb.

**And one earlier reading of the logs was simply wrong.** This item recorded
that no `claim failed` was ever logged, which sent three probes at the poll. It
is logged; the window in the run I checked ended before the claim was reached.
The lesson is the cheaper one: read the captured output of a run that got far
enough, rather than a run that stopped early.

## What proves it, once fixed

- A lane verb refused for admission crosses as a refusal, beside the existing
  case in `ALaneOverHttpIsIndistinguishableIT`.
- A bean that brings its own step performs its work in
  `samples/spring-boot-worker-app`, which is the assertion that test stops short
  of today.
