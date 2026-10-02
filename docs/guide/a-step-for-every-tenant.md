---
title: A step for every tenant
eyebrow: Guide
standfirst: >-
  The deployment declares one step and one bean performs it for every tenant.
  Each run stays the tenant's own — asked for on its door, closed in its store
  through its rules — and a tenant that does not want it says so in one line.
template: essay.html
---

The Ministry runs the deployment Rowling Land lives on, and it checks every
organisation's directory entry against what somebody proposes it should say.
Hogwarts did not install that check, and neither did the clinic that joined
last week: the Ministry declared it once, one bean performs it, and nobody
redeployed anything when the clinic arrived. The scene is
[US-DBO-FLEET-STEP](../arc42-003-context/user-stories/us-dbo-fleet-step.md),
walked by
[`OneStepIsPerformedForEveryTenantIT`](https://github.com/jengu-net/dbo/blob/main/samples/spring-boot-server-app/src/test/java/cloud/jengu/dbo/samples/stories/OneStepIsPerformedForEveryTenantIT.java).

## The deployment declares it

`mom` is the deployment's own tenant, and its record names the steps the
deployment performs for every tenant under `fleetSteps`:

```json
--8<-- "samples/sample-world/mom.json"
```

The check takes the organisation it is about by reference, a proposed entry as
an object, and a list of notes. `opens` names the slots whose payload the
performer reads, which is what a tenant's register of processing is made of,
and `substrate` names where the step's work waits. A step code belongs to
exactly one level: a tenant declaring `fleet.directory.check` as a step of its
own does not come up.

## One bean performs it

The bean lives in the clinic's application and is the same interface as any
tenant's step:

```java
--8<-- "samples/spring-boot-server-app/src/main/java/cloud/jengu/dbo/samples/server/CheckingTheDirectory.java:step"
```

It names no tenant. The tenant arrives as `work.tenant()`, because a tenant
joining the world is a file appearing in a directory and must not also be a
release of this application. `@FleetStep` carries what a run has to record and
the code cannot supply: who performed it, which behaviour that is, and whose
code it is.

## Asked for on the tenant's door

The worker application asks Hogwarts for a check of one of its organisations.
It performs none of it:

```java
--8<-- "samples/spring-boot-worker-app/src/main/java/cloud/jengu/dbo/samples/worker/AskingForADirectoryCheck.java:asking"
```

The organisation is named by what the application knows about it, an
identifier, because it holds no credential that could look up the record's id.
Hogwarts resolves that search against its own records when the run is created
and records the reference it matched, so what the run is over cannot change
afterwards. The run is about Hogwarts' data, so it belongs to Hogwarts: authored
on its surface, carried on its work stream and refused by its rules if it breaks
one.

## A tenant says no in one line

A clinic that does not want the check declines it in its own declaration:

```java
--8<-- "samples/spring-boot-server-app/src/test/java/cloud/jengu/dbo/samples/stories/OneStepIsPerformedForEveryTenantIT.java:declines"
```

Its door then refuses to start one and says why, and a run of the check authored
inside the clinic is never offered. Hogwarts said nothing, which admits the
step.

## What the store guarantees

- **A step code belongs to one level**, checked where it is declared, and the
  deployment's record of what it serves names the conflict until it is fixed.
- **Declaring the step prepares where its work waits** — a database the runtime
  owns, apart from every tenant's — before there is any work.
- **Every tenant's run is offered once**, to the one bean, under the tenant and
  run it came from, including a tenant that joined after the bean was deployed,
  and again exactly once when the deployment reads a tenant's work from the
  start.
- **The run closes in the tenant**, naming the bean as its executor, with its
  tally, through the tenant's own lane and rules.
- **A slot arrives in one of three shapes**: a referred record resolved, a given
  object with no id and no version, and a list in the order it was sent.

The [joins table](../arc42-003-context/user-stories/us-dbo-fleet-step.md#joins)
names the test behind each.

## What the store cannot do yet

- **A fleet step writes nothing.** No tenant's declaration says what the
  deployment's step may write, so its result is a tally and no records.
- **A tenant cannot read its register.** What the deployment opens of a
  tenant's data, the incidents where the trail disagrees with it and the rows a
  tenant has not authorised are answered only inside the deployment's process.
- **A deployment cannot name its processor** in configuration, so none is
  enrolled.
- **A bean whose step nothing declares is noticed only inside the process.**
