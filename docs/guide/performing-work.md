---
title: Performing work
eyebrow: Guide
standfirst: >-
  A step is one interface and one registration. The runner does the pulling,
  claiming, checkpointing and reporting; what you write is what the work
  actually is.
template: essay.html
---

[Runners](runners.md) described the lane a runner joins a tenant over. This is
the other side of it: what you write, and where you put it.

It is smaller than the surrounding machinery suggests. **One interface, one
registration.** Everything else — taking runs, holding claims, extending them,
reporting outcomes, carrying vitals — belongs to the runner and is written
once.

## The interface

You implement `StepService`.

```java
--8<-- "sample/src/main/java/cloud/jengu/dbo/sample/AdmitStep.java"
```

Two methods matter, and a third is optional.

`step()` names what this performs — `<module>.<process>.<step>`, opaque and
stable. `perform(Work)` does it. And `declaration()`, which a service
overrides only when it performs a step the catalogue has not got: the
laboratory in [Runners](runners.md#joining-with-a-step-of-its-own) is one,
and it is the only difference between joining a process and bringing a
capability to it. A service performing an installed step brings nothing,
because the module already contributed it.

## The work arrives whole

`Work` carries the run and the objects it names. **A service never fetches.**

That is not a convenience. It is what keeps the same service honest on a
runner with nothing to fetch from — no store handle, no credential for the
tenant, nothing to reach back through. A step that could fetch would work in
the container and fail on an edge, and the failure would arrive in production
rather than here.

## What an outcome means

`Outcome.done` closes the run. `Outcome.failed` releases it with a reason, and
**throwing is the same as failing** — released is not done, and a later cycle
may take it again.

!!! warning "Done means done"

    The store closes a run on what `perform` returns and has **no view below
    that seam**. Returning `done` before the work is done leaves the store
    holding a true-looking record of something that did not happen — and
    nobody goes looking for work the store says is finished.

    So a service with durable execution underneath waits for its workflow
    rather than returning its handle, and a router holding a claim for an edge
    waits for what it forwarded. If the workflow wedges or the edge goes
    silent, `perform` blocks, the claim lapses, and the run reads *released* —
    visibly still owed, which is the outcome this design wants.

## Long work says how far it has got

`work.progress()` takes a checkpoint, and `milestone` names a declared point
the step reached — "parsed", "measured", "signed". Both extend the claim.

Counts are the evidence, deliberately, rather than a heartbeat: what a
deadline protects against is a process that is alive and getting nowhere, and
a heartbeat cannot tell those apart. The store derives the position over the
step's declared order; the service asserts only the name.

## The registration is the whole wiring

Register it as a service in the container the store runs in, and that is all
of it:

```java
context.registerService(StepService.class, new AdmitStep(), null);
```

**Nothing is configured.** The service *is* the configuration: the runner
takes it up when it appears and lets it go when it goes, so a module
contributes a step the way it contributes anything else. Embedding the store
in your own process instead? Then you construct the runner and hand it lanes
directly, and there is no registration at all.

## The same service, three deployments

The service above does not change between them. That is the promise the lane
facade exists to keep.

| where it runs | what carries the work |
|---|---|
| inside the consuming platform's container | the in-process lane — no network at all |
| on a separate machine, or a pod scaled per step | the HTTP lane |
| wherever the store's own substrate reaches | the stream lane, over the tenant's door |

What changes is which lane is registered beside it, and nothing above it
moves. The laboratory in [Runners](runners.md) holds the HTTP one because it
runs in another organisation's process; the hospital's own runner holds the
same lane against the same tenant. Neither service can tell.

## What a lane deliberately is not

No verb accepts a record reference. None hands out a store handle. None reads
an object the work did not name.

That is why the interface above has no store in it, and why `Work` arrives
whole rather than as something to resolve. A lane wide enough to fetch would
make every runner a client of the tenant's database, which is the coupling the
whole participation model exists to remove.

## What you would otherwise have written

A queue consumer, and the claim protocol to go with it.

A heartbeat, and the argument about how often.

A way for the worker to read the record it is working on — and the credential,
the network path and the audit question that follow it.

And a second answer to "what is owed, and by whom", sitting beside the run
record and disagreeing with it.
