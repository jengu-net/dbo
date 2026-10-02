---
title: A tenant opens
eyebrow: Guide
standfirst: >-
  A clinic is a file the application's store is watching. It comes up with a
  database and an authority of its own, the identity provider fills its staff
  directory, and what a clinician may reach is a grant the application
  declares.
template: essay.html
---

Ines builds the platform a clinic group runs on. She does not run a database
team, she does not want to operate a second identity system, and she is not
going to write a tenant boundary of her own. Kevadkliinik is the first clinic to
go live, and Sügiskliinik follows on the same running store: the second must
cost a declaration, not a deployment. The scene is
[US-DBO-TENANT-OPENING](../arc42-003-context/user-stories/us-dbo-tenant-opening.md),
walked by
[`ATenantOpensAndItsPeopleGetInIT`](https://github.com/jengu-net/dbo/blob/main/samples/spring-boot-server-app/src/test/java/cloud/jengu/dbo/samples/stories/ATenantOpensAndItsPeopleGetInIT.java),
where each clinic's code carries the story's name and a mark for the run.

## The store is a dependency

The application is a bare `@SpringBootApplication`:

```java
--8<-- "samples/spring-boot-server-app/src/main/java/cloud/jengu/dbo/samples/server/ServerApplication.java:application"
```

What makes it serve tenants is `dbo-spring-boot-server` on its classpath and
`dbo.tenants.directory` in its `application.yaml`
([quick start](quick-start.md#embedded-one-jvm)). The directory is watched: a
file appearing there is a tenant coming up, and a file leaving is a tenant
retracted. A tenant is added by writing a file, not by a release.

## A declaration is the whole of opening a clinic

This is a tenant, complete — the hospital in the sample world:

```json
--8<-- "samples/sample-world/tenants/hogwarts.json"
```

It names the standard it speaks (`face`), the zone whose rules it takes, the
types it holds and, for each, what identifies one and how it is handled, and
the steps its work is made of. Nothing in it is a hint; the store enforces all
of it. The story opens Kevadkliinik with a smaller one, handed to the same
scan a file in the directory is, which applies it on its own clock:

```java
--8<-- "samples/spring-boot-server-app/src/test/java/cloud/jengu/dbo/samples/stories/ATenantOpensAndItsPeopleGetInIT.java:declare"
```

There is no migration to run, no schema to apply and no restart. What the
clinic gets underneath is a database of its own rather than a slice of a shared
one, and the credential that made it never passes through code you wrote.

## Doing something as a tenant opens

--8<-- "assets/diagrams/a-tenants-life.svg"

The application is told when a tenant reaches a point in its life, by a bean
annotated `@DboTenantListener`. The simplest notices it:

```java
--8<-- "samples/spring-boot-server-app/src/main/java/cloud/jengu/dbo/samples/server/NoticingATenant.java:noticing"
```

A listener is told once, at the point it named, and suits what is rare and can
be derived again if it is missed. Anything that must not miss an event is an
observer instead ([care is recorded](care-is-recorded.md#watching-the-work)).

`target` narrows which tenants a listener hears about, by facts the tenant
publishes. This one declares what Kevadkliinik's people may do as soon as the
clinic serves, in every clinic that keeps a staff directory:

```java
--8<-- "samples/spring-boot-server-app/src/main/java/cloud/jengu/dbo/samples/server/OpeningAClinic.java:opening"
```

A clinician's reach is a role grant held as a record in the clinic's own store,
not code the application ships. Declaring it at every start is the ordinary
case: a bring-up that refused a grant it already had would make every redeploy
a migration. The application is a client of each clinic too, with the one scope
its screens need.

### Selecting tenants

A listener and an observer take the same filter, over facts a tenant publishes
under `dbo.tenant.`: its code, face and zone, whether it has steps, SCIM, an
authority, a vault. A filter naming a fact that does not exist is refused where
it is registered, because `dbo.tenant.hasVualt` would otherwise match nothing,
for ever, in silence. There is no kind of tenant to filter on, deliberately: a
filter on a resolved fact keeps answering correctly for a kind of tenant nobody
anticipated, and a filter on a label has to be found and edited first.

A listener that throws is reported by name and cannot stop the tenant serving.
Nor does it work out its own applicability: where it applies is the filter, so
the answer lives in one place.

### Enrolling the worker

The same kind of listener issues the worker its credential in each tenant it
performs for:

```java
--8<-- "samples/spring-boot-server-app/src/main/java/cloud/jengu/dbo/samples/server/EnrollingTheWorker.java:reached"
```

## What the store guarantees

- **Nothing answers until the clinic's own authority says so.** An anonymous
  request is refused with 401 rather than 404: a mounted, guarded surface and
  an absent one are different facts.
- **The token comes from the clinic's own issuer**, is checked where it is
  presented without a call to anybody, and its scope is the whole of what it
  reaches.
- **The boundary is structural.** Kevadkliinik's token presented to
  Sügiskliinik is 401, not 403: the second clinic's authority has never heard
  of that issuer, so the token is noise rather than a credential being refused.
- **The identity provider fills the directory over SCIM**, with a credential
  that reaches the directory and nothing else. What lands is the person, with a
  practitioner capacity linked to them; groups are read and never written. A
  tenant that declares SCIM without personal-data isolation does not come up.
- **A zone that names no identity broker is its own**, so declaring Rowling
  Land for its rules did not oblige anybody to stand up an identity provider.

Each of these is a leg of the story, and its
[joins table](../arc42-003-context/user-stories/us-dbo-tenant-opening.md#joins)
names the promise and the test that proves it.

## What the store cannot do yet

- **No shared tier.** Every tenant is a database. A clinic too small to justify
  one has nowhere cheaper to go.
- **No quotas.** Nothing bounds what one tenant can consume, so a busy clinic
  and a quiet one on the same node are isolated from each other by nothing but
  luck.
- **The records surface is not private yet.** The design says applications
  reach data through work and that the store's own records surface is never
  publicly routed. Today keeping it off a public route is the deployment's job.
