**Open. A step a participant introduces is accepted and stored, and the authoring door goes on refusing it until the tenant is rebuilt. Next: compose the catalogue per request, or invalidate it when an introduction lands.**

# An introduced step is not authorable until the tenant is rebuilt

A participant that brings a capability the catalogue has not got introduces
it over the lane, and the store takes it: the declaration is written into the
tenant's own store and the lane answers success. The authoring door then
refuses a run of that step, naming only the steps the tenant's spec
installed.

Both halves are working as written. The work surface composes its catalogue
**once**, while the tenant is coming up:

```java
Steps composed = new Introductions(engine, steps).composedWith();
server.workSurface = new WorkProjection(engine, new Runs(engine, composed), composed, face);
```

An introduction that lands afterwards is in the store the composition reads
and not in the snapshot the door holds. So the store believes it and the
door does not, and nothing says so — the introduction succeeded.

## How it was found

Writing the sample application's external participant: a laboratory joining
the hospital's process with a step of its own. It introduced the step, the
lane answered success, and the door answered

```
{"error":"not_found","detail":"this tenant offers no step 'hogwarts.admission.assay';
 it offers: [hogwarts.admission.admit]"}
```

A restart makes it work, which is the shape of the defect rather than a
workaround: the same declaration is fine, and only the moment it arrived
differs.

## Why it matters more than it looks

A participant that brings its own capability is how a store that nobody
pushes to is joined from outside, and it is the case the step-service
contract keeps an optional `declaration()` for. If that only works when the
introduction precedes bring-up, then it works for installation and not for
joining, which is the half it exists for.

## Steps

1. Decide where the composition belongs: read per request, or hold the
   snapshot and invalidate it when an introduction is written. The second is
   cheaper and needs a signal the change feed already carries.
2. Prove it with a test that introduces after bring-up and authors a run,
   which is what nothing does today.
3. Then finish the sample's external participant, which is
   [item 002](../002-sample-application/README.md) and waits on this.
