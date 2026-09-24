# A worker, because an application added one dependency

The mirror of [`../spring-boot-server-app`](../spring-boot-server-app), and
what is **absent** is the point. There is no tenant here, no database, no
store and no way to get one. This application is handed work, performs it, and
answers.

| | |
|---|---|
| [`AdmittingAPatient.java`](src/main/java/cloud/jengu/dbo/samples/worker/AdmittingAPatient.java) | a bean implementing `StepService`. **This is the whole of what an integrator writes.** |
| [`application.yaml`](src/main/resources/application.yaml) | who this worker is, and which tenant's lane it performs for |
| [`WorkerApplication.java`](src/main/java/cloud/jengu/dbo/samples/worker/WorkerApplication.java) | a bare `@SpringBootApplication` |

Nothing here constructs a runner, registers a step service or attaches a lane.
The executor identity, the poll and hold durations, the registration and the
lane are configuration; the container's own whiteboard finds the bean.

## What it needs

A server to take work from — the application beside it, running — and a
credential that tenant issued. **Two credentials exist and they are not
interchangeable:** a token admitted at the step surface is refused by the
records door, and holding one is deliberately not holding the store. This one
needs `work`.

Minting it is the tenant's to do, and the sample does not do it for you. What
the tests do is ask that tenant's own authority for a client with the `work`
scope — see
[`TheTenantIsServing`](../../assembly/spring-boot-test/src/main/java/cloud/jengu/dbo/spring/test/TheTenantIsServing.java).

## Running it

With the server up and a work credential in hand:

```bash
DBO_WORKER_CLIENT_ID=... DBO_WORKER_CLIENT_SECRET=... \
    ./gradlew :samples:spring-boot-worker-app:run
```

It polls, takes what it is given, performs it in the bean above, and answers.
Ask the tenant what it did:

```
GET /t/hogwarts/work?executor=sample-admissions-worker
```

A run records who performed it, which is why the identity is asked for rather
than defaulted to an artifact id: **an executor that cannot be reproduced
cannot be held to what it did.**

## What its test proves, and what it cannot

[`ABeanOfThisApplicationPerformsTheWorkIT`](src/test/java/cloud/jengu/dbo/samples/worker/ABeanOfThisApplicationPerformsTheWorkIT.java)
asserts on **who performed the run**, not on whether one ran — a run driven
locally and one delivered over the lane are identical from inside the step,
and only the executor tells them apart.

It runs both applications in one JVM, which is a testing economy rather than
the shape: in a deployment this is somebody else's process. The HTTP is real —
the worker builds an `HttpLane` and nothing else — but the process boundary is
not exercised, and neither is this application's own `application.yaml`, which
one Spring context cannot load beside the server's. Both limits are written
down in [item
026](../../docs/arc42-011-risks-and-technical-debt/026-two-samples-tell-one-story/README.md).
