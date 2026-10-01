# A worker, because an application added one dependency

The mirror of [`../spring-boot-server-app`](../spring-boot-server-app), and
what is **absent** is the point. There is no tenant here, no database, no
store and no way to get one. This application is handed work, performs it, and
answers.

| | |
|---|---|
| [`AdmittingAPatient.java`](src/main/java/cloud/jengu/dbo/samples/worker/AdmittingAPatient.java) | a bean implementing `StepService`. **This is the whole of what an integrator writes.** |
| [`RegisteringAPatient.java`](src/main/java/cloud/jengu/dbo/samples/worker/RegisteringAPatient.java) | a step whose result carries records: the person it was given and the stay they arrived for, written by the hospital, never by this application |
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

## Where it is tested

This application has no tests of its own. Its beans are tested where they run
in the ordinary case: embedded in the clinic's application,
[`../spring-boot-server-app`](../spring-boot-server-app), whose tests are the
user stories. There the steps arrive through
[`TheWorkersSteps`](src/main/java/cloud/jengu/dbo/samples/worker/TheWorkersSteps.java),
and the lane they are offered work over is the server's own port — the same
HTTP a worker in another JVM would use, so a step cannot tell the two apart.
