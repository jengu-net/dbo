---
title: Guide
eyebrow: Guide
standfirst: >-
  For a Spring Boot developer building an application on this store. The
  application declares the work its organisations do, asks for it, and
  performs it in steps — and a step reaches data only through what its task
  carries in and what its result carries out.
template: essay.html
---

This store is a library your application adds, not a database it talks to.
An ordinary Spring Boot application puts `dbo-spring-boot-server` on its
classpath and serves tenants on its own port; another puts
`dbo-spring-boot-worker` on its classpath and performs their work. Most of
what you write is two kinds of bean and a declaration.

## Work is the spine

An organisation's work is a **process** made of **steps**: registering a
patient, recording a visit, correcting a record. Each step is declared in the
tenant's own file — what it takes, and what it may write — and performing one
is a **run**.

A step is an activity, and an activity reaches data in two ways only. **What
its task carries in**: the records it was named over, resolved by the store and
handed to it, and the objects it was given. **What its result carries out**:
what it counted, and the records it asks the tenant to write. A step holds no
connection, no records credential and no way to look anything up. The tenant
writes what a step answers with, through its own rules, or refuses it and says
why.

That is why the questions an organisation is asked afterwards — who read this,
for what, on whose authority — have answers without anybody writing them down:
the run is the reason the data was reached, and it is a record.

## The world the chapters walk

Every chapter is a user story, and every story is walked by the sample
applications' tests in one world, Rowling Land, declared in
`samples/sample-world`.

| Tenant | Face | What it is |
|---|---|---|
| `mom` | R5 | the deployment's own tenant: what it was told to serve, and the steps it performs for every tenant |
| `rl` | R5 | the zone, Rowling Land, whose terminology the others take |
| `fhir-r5`, `fhir-r4` | R5, R4 | face roots, holding each version's definitions as records |
| `hogwarts` | R5 | a hospital |
| `st-jerome` | R5 | a clinic that keeps its patients under ids of its own |
| `gringotts` | R4 | an insurer, a release behind |

Two applications serve and work it:

- **[the clinic's application](https://github.com/jengu-net/dbo/tree/main/samples/spring-boot-server-app)**,
  with the store embedded. It serves the tenants, asks for work and hears
  what the work came to.
- **[the worker application](https://github.com/jengu-net/dbo/tree/main/samples/spring-boot-worker-app)**,
  which performs the steps. Embedded, its beans run inside the clinic's
  application; separated, it runs in a JVM of its own.

Every example in these pages is quoted from one of them, from the world's
files, or from a story test where an act belongs to the test alone. Nothing is
typed into a page, so an example cannot show a call that no longer exists, and
nothing is shown that the stories do not pass.

## The chapters

1. **[Quick start](quick-start.md)**: the clinic's application with its
   worker embedded, one piece of work asked for and its answer read; then the
   worker in a JVM of its own, over HTTP and over the deployment's substrate.
2. **[A tenant opens](a-tenant-opens.md)**: a clinic is a file, with a
   database and an authority of its own, and its people get in.
3. **[Care is recorded](care-is-recorded.md)**: a patient registered, a
   visit recorded whole, a record corrected, each by asking for a step, and
   what the store keeps about all of it.
4. **[What a person can ask for](what-a-person-can-ask-for.md)**: the person
   behind a record, sealed, looked up only for a reason, and forgotten.
5. **[One tenant in two places](one-tenant-in-two-places.md)**: definitions
   that travel by type, and patient data that travels by work.
6. **[The standard moves](the-standard-moves.md)**: data outliving the shape
   it was written under.
7. **[Work leaves and comes back](work-leaves-and-comes-back.md)**: a step
   performed outside the store, a step a participant brings, and a payload
   read only where it is opened.
8. **[A step for every tenant](a-step-for-every-tenant.md)**: one bean
   performing the deployment's step for every tenant, and a tenant saying no.
9. **[On the stream](on-the-stream.md)**: a worker beside the store, over the
   deployment's own substrate, with keys it made itself.
10. **[Running it](running-it.md)**: the stories told from the side of whoever
    runs the deployment.

The reference pages after them are short definitions of what no single scene
owns: [processes and steps](processes-and-steps.md),
[tenants and faces](tenants-and-faces.md), [zones](zones.md),
[personal data](personal-data.md), [the trail](the-trail.md),
[terminology](terminology.md) and [export and import](export-and-import.md).

Where this guide and
[the requirement catalogue](../arc42-006-runtime/req-catalogue.md) disagree,
the catalogue is right: it is generated from the promises and the tests that
prove them.
