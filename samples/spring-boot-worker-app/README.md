# A worker, because an application added one dependency

The mirror of [`../spring-boot-server-app`](../spring-boot-server-app), and
what is **absent** is the point. There is no tenant here, no database, no
store and no way to get one. This application is handed work, performs it, and
answers.

| | |
|---|---|
| [`AdmittingAPatient.java`](src/main/java/cloud/jengu/dbo/samples/worker/AdmittingAPatient.java) | a bean implementing `StepService`. **This is the whole of what an integrator writes.** |
| [`RegisteringAPatient.java`](src/main/java/cloud/jengu/dbo/samples/worker/RegisteringAPatient.java) | a step whose result carries records: the person it was given and the stay they arrived for, written by the hospital, never by this application |
| [`application.yaml`](src/main/resources/application.yaml) | who this worker is, standing alone |
| [`application-edge.yaml`](src/main/resources/application-edge.yaml), [`application-substrate.yaml`](src/main/resources/application-substrate.yaml) | where its lane goes: over HTTP into one tenant, or over the deployment's own database |
| [`WorkerApplication.java`](src/main/java/cloud/jengu/dbo/samples/worker/WorkerApplication.java) | a bare `@SpringBootApplication` |
| [`TheWorkersSteps.java`](src/main/java/cloud/jengu/dbo/samples/worker/TheWorkersSteps.java) | the same beans, arriving in the clinic's application when it embeds this one |

Nothing here constructs a runner, registers a step service or attaches a lane.
The executor identity, the poll and hold durations, the registration and the
lane are configuration; the container's own whiteboard finds the bean.

## Two ways to run, and the beans cannot tell

**Embedded.** The clinic's application depends on this one, and its step
beans arrive in that context. One JVM; the lane is the clinic's own port.
Nothing here is read — that application's configuration is the one in force.

**Separated.** A JVM of its own, under one of two profiles:

- **`edge`**, the default: an HTTP lane into one tenant, with a client and a
  secret that tenant issued. **Two credentials exist and they are not
  interchangeable:** a token admitted at the step door is refused by the
  records door, and holding one is deliberately not holding the store. This
  one has `work` and nothing else.
- **`substrate`**: the deployment's own database, for scaling out beside the
  store. No base and no token; the worker is the participant name it enrolled
  under, holding the private halves of two keys whose public halves the
  tenant holds. It makes the pair itself —
  [`MintingAnEnrolment`](src/main/java/cloud/jengu/dbo/samples/worker/MintingAnEnrolment.java)
  — and only the public halves are handed over.

Issuing the client, or enrolling the public halves, is the tenant's to do.
The clinic's application asks each tenant to as it comes up
([`EnrollingTheWorker`](../spring-boot-server-app/src/main/java/cloud/jengu/dbo/samples/server/EnrollingTheWorker.java)),
which is why both sides name the same client by default. How to start the
two side by side is in [its README](../spring-boot-server-app/README.md).

It polls, takes what it is given, performs it in the bean above, and answers.
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
