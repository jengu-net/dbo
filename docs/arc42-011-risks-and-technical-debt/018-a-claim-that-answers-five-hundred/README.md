**Open. An outside participant is offered a run of the step it introduced and cannot take it: the claim answers 500, the server logs nothing, and the runner reports that it performed no work. Next: find what the claim throws.**

# A claim that answers five hundred, behind two silences

A participant that brings its own capability introduces the step, a run of
it is authored on the face, and the lane offers that run to the participant.
The claim then fails:

```
hogwarts: claim did not complete (500) — the verb did not complete
```

Three things hide it, which is why it took six runs of the guide world to
find:

- **The runner treats a failed claim as a race it lost.** `StepRunner`
  skips it with `continue`, so a refusal, an unreachable store and a genuine
  race are one outcome at the call site.
- **The cycle catches what the lane throws** and logs a warning, then
  answers that it performed nothing. A caller sees nought and no reason.
- **The server logs nothing.** The container's log around the failure
  carries only ordinary INFO lines. A five hundred is an unhandled
  condition, and this one leaves no trace of itself.

So the symptom a reader meets is "the participant was offered nothing",
which is false: it was offered the run and could not take it.

## Reproducing it

Against the guide world, as the hospital's work credential:

1. Attach a runner whose step service returns a `declaration()` for a step
   the tenant's spec does not declare, which introduces it.
2. Author a run on the face, not at the step door: `POST /t/hogwarts/fhir/Task`
   with the step's bare name under `urn:dbo:step`, the process under
   `urn:dbo:process`, a name of its own under an `urn:dbo:run` identifier,
   and one input whose type coding carries `urn:dbo:run:input`.
3. Poll the lane for the bare step name. The run comes back, held by
   automation, carrying its input.
4. Claim it. The verb answers 500.

## What to do

1. Find what the claim throws server-side. It is not in the log, so the
   first move is to make the lane's verb say what failed rather than
   answering an empty five hundred.
2. Decide whether the claim is refusing on purpose — an introduced step's
   run claimed by the participant that introduced it may be a case nobody
   implemented — or failing by accident. The answer decides whether this is
   a defect in the claim or a gap in the participation model.
3. Stop the runner reporting silence. A claim that failed for a reason is
   not a race, and a cycle that performed nothing because it could not reach
   the store is not a quiet tenant.

## Why it matters

This is the whole of what a participant bringing its own capability is for,
and it is the case the optional `declaration()` on a step service exists to
serve. Until it works, an outside system can join a process only by
performing a step the tenant already installed, which is
[item 002](../002-sample-application/README.md)'s third slice and waits on
this.
