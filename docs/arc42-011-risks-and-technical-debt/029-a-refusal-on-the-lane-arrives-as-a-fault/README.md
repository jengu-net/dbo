**Open on one half. The refusal is fixed: `Runs.NotAdmitted` was a plain
`RuntimeException`, which the verb surface does not recognise, so a settled
refusal left as a 500 and reached the participant as *the store did not answer*
— the opposite recovery, and a run lost for good because a feed cursor is
acknowledged either way. It is an `IllegalStateException` now, and
`AFaceAuthoredRunReachesTheLaneIT#anInadmissibleClaimIsARefusal` fails without
that change. What remains is why it refused: the worker assembly scopes every
executor to the tenant's organisation, which for a step the application itself
brought is asking to vary its own step.**

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

## Defect one, fixed: a refusal crossed as an unanswered call

**This is the serious half, and it is a promise violated rather than a gap.**
REQ-DBO-PROC-REFUSED-IS-NOT-UNANSWERED says a refusal and an unanswered call
are told apart on the exception, *because the two want opposite recoveries*, and
that its own text names this consequence — *a participant that confused them
would back off from work it is entitled to*.

`Runs.NotAdmitted` is a settled refusal. It reaches `LaneVerbService` unmapped,
becomes a 500, and `WireLane` turns 5xx into "did not complete" — which is the
right mapping for a 500 and the wrong answer about this exception.
`ALaneOverHttpIsIndistinguishableIT#aRefusalIsNotAnUnansweredCall` already
proved the property for the refusals that were mapped; `NotAdmitted` was not one
of them.

**Fixed by making it an `IllegalStateException`**, which is what the verb
surface already recognises — its catch says *what a lane refuses, said as a
refusal*. Nothing caught the old type, so the change is the superclass and a
comment saying why it matters. The test that would have caught it now sits in
the probe that found it, and was watched failing first: without the change it
reports `claim did not complete (500)`, which is the whole defect in one line.

**One thing that test got wrong on the first attempt** is worth keeping, because
it would have passed for the wrong reason. It relied on a sibling test having
introduced the step — and a catalogue with no declaration for a step admits
every executor, there being nothing to check against. So it passed when it ran
second and proved nothing when it ran first. The introduction is in the fixture
now.

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

## What is left

The sample still does not perform its brought step, and now says why in one
line instead of reporting a 500: *step 'hogwarts.admission.assay' does not admit
an executor at scope organisation:hogwarts*. That is defect two, and it is a
decision rather than a repair — a lane has one identity and scope belongs per
step.

**What will prove it**: a bean that brings its own step performs its work in
`samples/spring-boot-worker-app`, which is the assertion that test stops short
of today.
